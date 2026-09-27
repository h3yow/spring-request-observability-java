# Spring Request Observability

请求级可观测性示例工程：关联 ID 透传、MDC 日志串联、统一错误响应、按路径的请求指标。

## 关联 ID 生成与透传规则

- **请求头**：`X-Correlation-ID`（关联 ID）、`X-Request-ID`（请求 ID，仅由服务端生成并返回）。
- **沿用规则**：调用方传入的 `X-Correlation-ID` 若匹配 `^[A-Za-z0-9-]{8,64}$` 则原样沿用；缺失或格式不合法时由服务端生成新的 UUID，**不信任非法输入**。
- **请求 ID**：每个请求都会生成一个全新的 UUID 作为 `X-Request-ID`，与关联 ID 一起通过响应头返回。
- **载体**：两个 ID 存放在 SLF4J MDC（`correlationId` / `requestId`），日志格式通过
  `logging.pattern.console` 中的 `%X{correlationId}` / `%X{requestId}` 输出，因此正常处理、业务异常、未处理异常的日志都能按 ID 串成一条链。
- **异步透传**：`observabilityExecutor`（见 `AsyncConfig`）通过 `TaskDecorator` 在提交任务时捕获 MDC、在工作线程上回放，任务结束后恢复并清理，**线程被池化复用也不会把上一个请求的 ID 带到下一个请求**。
- **清理**：`CorrelationIdFilter` 在 `finally` 中清理 MDC，防止 Tomcat 工作线程复用时串号。

## 错误处理口径

- 业务异常 `BizException` → HTTP 422，响应体 `{code, message, correlationId}`，message 为可对外信息。
- 未处理异常 → HTTP 500，响应体 `{code: "INTERNAL_ERROR", message: "internal error", correlationId}`，**不向客户端泄露内部细节**。
- 完整异常链（含最原始 root cause）只记录在服务端日志中，并带有同一个关联 ID，可用响应里的 `correlationId` 反查日志。

## 指标口径（`GET /metrics/requests`，只读）

按请求路径（request URI）统计，每个请求**恰好记录一次**（在过滤器 `finally` 中记录，失败/被拒绝的请求同样计入）：

| 字段 | 含义 |
|---|---|
| `count` | 该路径的请求总数 |
| `failureCount` | 失败数：处理中抛出未捕获异常，或最终 HTTP 状态码 >= 400 |
| `totalDurationMillis` | 服务端处理耗时累计（过滤器链耗时，异步请求不含异步执行段） |
| `avgDurationMillis` | 平均耗时 = totalDurationMillis / count |

- 计数器基于 `LongAdder` + `ConcurrentHashMap`，并发下不丢不重。
- `/metrics/**` 自身不计入统计，避免观测行为污染数据。
- 内存实现，重启即清零；`RequestMetrics#reset()` 仅供测试使用。

## 本地验证

```bash
# 运行全部测试（并发隔离、线程复用、异常链路、指标精确性）
mvn test

# 手动验证
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# 1) 合法关联 ID 被沿用
curl -i -H 'X-Correlation-ID: my-corr-0001' http://localhost:8080/api/hello

# 2) 非法 ID 被重新生成
curl -i -H 'X-Correlation-ID: bad id!!' http://localhost:8080/api/hello

# 3) 异步接口：响应体中 worker 线程上的 correlationId 与请求线程一致
curl -s -H 'X-Correlation-ID: async-corr-01' http://localhost:8080/api/async

# 4) 统一错误响应（422 / 500），日志中可用 correlationId 查到 root cause
curl -i http://localhost:8080/api/biz-error
curl -i http://localhost:8080/api/boom

# 5) 查看按路径统计
curl -s http://localhost:8080/metrics/requests
```

测试日志中每个断言都打印了 `sent=... echoed=... -> 判定依据`，服务端日志行内带
`[cid=... rid=...]`，可直接对照验证。
