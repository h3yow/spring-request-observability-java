package com.github.h3yow.springrequestobservabilityjava.web;

import com.github.h3yow.springrequestobservabilityjava.observability.RequestObservabilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 演示接口：正常、业务异常（含原始 cause 保留）、未处理异常、异步线程上下文透传。
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final ThreadPoolTaskExecutor requestAwareExecutor;

    public DemoController(ThreadPoolTaskExecutor requestAwareExecutor) {
        this.requestAwareExecutor = requestAwareExecutor;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello(@RequestParam(defaultValue = "world") String name) {
        log.info("hello 业务处理中: name={}", name);
        return currentContextView("controller");
    }

    @GetMapping("/business-error")
    public Map<String, Object> businessError() {
        log.info("business-error 触发业务异常");
        // 模拟最底层的原始失败原因：cause 链必须完整保留到服务端日志，但不对外暴露。
        RuntimeException rootCause = new IllegalStateException("库存服务返回: SKU 状态非法 (内部细节)");
        throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, ErrorCode.BUSINESS_CONFLICT.defaultMessage(),
                rootCause);
    }

    @GetMapping("/unexpected-error")
    public Map<String, Object> unexpectedError() {
        log.info("unexpected-error 触发未处理异常");
        throw new RuntimeException("数据库连接字符串: jdbc:internal://secret (不应外泄)");
    }

    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        log.info("async 提交异步任务（请求线程）");
        return CompletableFuture.supplyAsync(() -> {
            // 工作线程：MDC 由 TaskDecorator 恢复，这条日志带的关联 ID 与请求线程一致。
            log.info("async 工作线程执行中: thread={}", Thread.currentThread().getName());
            return currentContextView("async-worker");
        }, requestAwareExecutor);
    }

    @GetMapping("/async-error")
    public CompletableFuture<Map<String, Object>> asyncError() {
        log.info("async-error 提交会失败的异步任务（请求线程）");
        return CompletableFuture.supplyAsync(() -> {
            log.info("async-error 工作线程即将抛出异常: thread={}", Thread.currentThread().getName());
            throw new IllegalStateException("下游服务超时 (内部细节)");
        }, requestAwareExecutor);
    }

    private Map<String, Object> currentContextView(String stage) {
        RequestObservabilityContext ctx = RequestObservabilityContext.current();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("stage", stage);
        view.put("thread", Thread.currentThread().getName());
        view.put("correlationId", ctx == null ? null : ctx.correlationId());
        view.put("requestId", ctx == null ? null : ctx.requestId());
        return view;
    }
}
