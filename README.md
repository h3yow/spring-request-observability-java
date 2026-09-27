# Spring 请求级可观测性

为每个 HTTP 请求提供稳定的**关联 ID（Correlation ID）**与**请求 ID（Request ID）**，
并在此基础上打通日志链路、统一异常响应和按路径聚合的请求指标，覆盖并发与异步场景。

## 1. 两个 ID 的语义

| 名称 | 请求头 / MDC | 作用域 | 用途 |
| --- | --- | --- | --- |
| 关联 ID | `X-Correlation-Id` / `correlationId` | 整条调用链（跨服务、跨异步） | 把一次业务操作在各服务、各线程、正常/异常日志中串成一条链 |
| 请求 ID | `X-Request-Id` / `requestId` | 仅本次 HTTP 请求（含其异步延续） | 在同一关联 ID 下区分具体的一次 HTTP 尝试 |

## 2. 生成与透传规则

### 2.1 入站规则（`RequestIdFilter`）

- **关联 ID**
  - 调用方带了 `X-Correlation-Id` 且**格式合法**时原样沿用，日志记录来源为「调用方透传」。
  - 缺失、为空或**格式不合法**时由服务端生成（`cid-` + 32 位无连字符 UUID），
    并落一条 `WARN` 日志说明判定依据（合法规则与原始值）。
  - 合法格式：长度 8~64，字符集 `[A-Za-z0-9_-]`（正则 `^[A-Za-z0-9_-]{8,64}$`）。
    限制字符集与长度是为了防止日志伪造、CRLF 注入与超长头。
- **请求 ID**：每个请求都由服务端新生成（`req-` + 32 位无连字符 UUID），不采信入站值。
- 两个 ID 在过滤器入口即写入**响应头**，因此即使后续链路失败，客户端也一定能从响应拿回：
  `X-Correlation-Id`、`X-Request-Id`。

### 2.2 线程内与日志

- 两个 ID 保存在 `RequestObservabilityContext`（`ThreadLocal`）中，并同步写入 SLF4J MDC。
- `application.properties` 的日志模板固定输出两个 MDC 字段，因此**每一行**日志都带：
  ```
  %d %-5level [%thread] [correlationId=%X{correlationId:-}, requestId=%X{requestId:-}] %logger - %msg%n
  ```

### 2.3 异步透传（`AsyncConfig`）

- `requestAwareExecutor` 通过 `TaskDecorator`：在**提交任务的请求线程**拍快照，
  在**工作线程**恢复上下文（含 MDC），任务结束（无论成功失败）`finally` 中无条件清理。
- 上下文对象不可变，快照单向传递，工作线程清理不影响请求线程。
- 线程被池化复用：下一任务开始前 ThreadLocal/MDC 必定是空的，不会串到别的请求。
- 异步请求成功或失败、容器恢复请求线程（`ASYNC` / `ERROR` 分发）时，过滤器从
  `HttpServletRequest` 属性恢复 MDC，因此异步异常的统一错误响应体也带得回两个 ID。
- 执行器为「核心 2 / 最大 4 / 队列 16 + AbortPolicy」：过载拒绝会在请求线程抛出
  `RejectedExecutionException`，经统一异常处理返回 500，并被指标计一次。

## 3. 异常处理与错误响应

`GlobalExceptionHandler` 保证对外结构统一、错误可区分，且不泄漏内部细节：

```json
{
  "code": "BUSINESS_CONFLICT",
  "message": "业务冲突",
  "correlationId": "cid-...",
  "requestId": "req-...",
  "timestamp": "2026-09-28T00:00:00Z",
  "path": "/demo/business-error"
}
```

- **业务异常** `BusinessException`（4xx，错误码见 `ErrorCode`）：`WARN` 记录，
  可携带自定义对外文案；构造时可传入 `cause`，**最原始的失败原因完整保留在服务端日志堆栈**，
  但不会出现在响应里。
- **未处理异常**：`ERROR` 记录完整异常链；对外固定为 `500 INTERNAL_ERROR` + 通用文案
  （不返回异常类名、message、堆栈、连接串等）。客户端凭响应里的关联 ID 到日志反查。
- **框架标准异常**（如无处理器 404）：保留原始状态码，套同一响应结构。

## 4. 指标口径

只读接口：`GET /observability/metrics/requests`，返回全量汇总与按路径明细，无任何写入/重置入口。

- **路径维度**：优先使用 Spring `HandlerMapping` 匹配到的 **URI 模板**
  （如 `/orders/{id}`），避免路径参数导致基数爆炸；未匹配处理器时归入
  `NO_HANDLER`（Spring Boot 下未匹配路径通常落到静态资源处理器模板 `/**` 再返回 404）。
- **请求量**：`totalCount`；分类计数 `successCount` / `businessFailureCount` /
  `serverFailureCount` / `rejectedFailureCount`。
- **结果归类**
  - 2xx → `SUCCESS`
  - 4xx（业务异常、参数错误、404 等）→ `BUSINESS_FAILURE`
  - 5xx（未处理异常、异步异常/超时、过载拒绝）→ `SERVER_FAILURE`
  - 请求在过滤器链上被直接打断、未产生正常响应 → `REJECTED_FAILURE`
- **失败率**：`(serverFailureCount + rejectedFailureCount) / totalCount`，
  4xx 业务失败不计入服务端失败率。
- **耗时**：`latency` 提供 `count / totalMillis / maxMillis / avgMillis`；
  异步请求从进入过滤器计时到异步真正完成（`AsyncListener.onComplete/onError/onTimeout`）。
- **恰好一次**：
  - 计数器为 `LongAdder`，最大耗时为 `AtomicLong`，路径桶为 `ConcurrentHashMap`
    且 `computeIfAbsent` 只发布一次；
  - 每个请求在过滤器层只 `record` 一次；异步请求由 `AsyncListener` 的
    `AtomicBoolean` 防止容器多回调重复计数；中途异常也保证计入一次。

## 5. 本地验证

### 5.1 运行测试

```bash
mvn test
```

包含：

- `RequestIdPropagationTest`（单测）：ID 合法性判定、跨线程快照/MDC 恢复、
  池化线程复用两次后 ThreadLocal 与 MDC 均为空（判定依据写在断言消息里）。
- `RequestObservabilityIntegrationTest`（端到端，随机端口）：
  - 合法关联 ID 沿用、缺失/非法自动生成，并经响应头回传；
  - 异步接口工作线程返回体中的关联 ID 与请求头一致；连续 6 次异步请求复用线程不串 ID；
  - 业务 409 / 未处理 500 / 异步 500 的统一错误体可区分且不含内部细节；
  - **16 线程 × 25 = 400 个并发请求**，指标增量与实际请求数逐一对上（不丢不重）；
  - 失败、404 路径、NO_HANDLER/`/**` 各计一次；指标接口只读；
  - 用 Logback `ListAppender` 断言请求链路上的日志带本次 `correlationId`/`requestId`。

### 5.2 手工冒烟

```bash
mvn spring-boot:run
# 正常（带透传关联 ID）
curl -i -H 'X-Correlation-Id: upstream-abc12345' http://localhost:8080/demo/hello
# 非法关联 ID（服务端会重新生成并 WARN）
curl -i -H 'X-Correlation-Id: bad id' http://localhost:8080/demo/hello
# 异步（观察响应体与控制台工作线程日志中的关联 ID 一致）
curl -i -H 'X-Correlation-Id: async-abc12345' http://localhost:8080/demo/async
# 业务异常（409，原始 cause 只在服务端日志）/ 未处理异常（500）/ 异步异常（500）
curl -i http://localhost:8080/demo/business-error
curl -i http://localhost:8080/demo/unexpected-error
curl -i http://localhost:8080/demo/async-error
# 只读指标
curl -s http://localhost:8080/observability/metrics/requests
```

## 6. 代码地图

| 组件 | 职责 |
| --- | --- |
| `observability.RequestObservabilityContext` | ThreadLocal + MDC 上下文，不可变慢照、跨线程恢复 |
| `observability.RequestIdFilter` | ID 解析/生成/回写，REQUEST/ASYNC/ERROR 分发的上下文生命周期 |
| `observability.AsyncConfig` | 带上下文透传与清理的线程池 |
| `observability.RequestMetrics` / `RequestMetricsFilter` | 并发安全指标存储；每请求恰好统计一次（含异步监听器） |
| `observability.MetricsController` | 指标只读接口 |
| `web.GlobalExceptionHandler` / `BusinessException` / `ErrorCode` / `ApiError` | 统一异常收口与错误模型 |
| `web.DemoController` | 正常 / 业务异常 / 未处理异常 / 异步 / 异步异常演示接口 |
