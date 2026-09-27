package com.github.h3yow.springrequestobservabilityjava.demo;

import com.github.h3yow.springrequestobservabilityjava.observability.BizException;
import com.github.h3yow.springrequestobservabilityjava.observability.CorrelationIdHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Demo endpoints used to exercise the observability pipeline:
 * normal, async (thread hop), business error and unexpected error flows.
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Executor observabilityExecutor;

    public DemoController(@Qualifier("observabilityExecutor") Executor observabilityExecutor) {
        this.observabilityExecutor = observabilityExecutor;
    }

    @GetMapping("/api/hello")
    public Map<String, String> hello() {
        log.info("handling /api/hello");
        return context("http-" + Thread.currentThread().getName());
    }

    @GetMapping("/api/async")
    public CompletableFuture<Map<String, String>> async() {
        String correlationOnRequestThread = CorrelationIdHolder.correlationId();
        log.info("dispatching /api/async");
        return CompletableFuture.supplyAsync(() -> {
            log.info("handling /api/async on worker thread");
            Map<String, String> body = context("async-" + Thread.currentThread().getName());
            body.put("correlationIdOnRequestThread", correlationOnRequestThread);
            return body;
        }, observabilityExecutor);
    }

    @GetMapping("/api/biz-error")
    public Map<String, String> bizError() {
        log.info("handling /api/biz-error");
        throw new BizException("BIZ_VALIDATION_FAILED", "request rejected by business rule");
    }

    @GetMapping("/api/boom")
    public Map<String, String> boom() {
        log.info("handling /api/boom");
        // Simulate a deep failure: the root cause must survive in the logs.
        throw new IllegalStateException("service layer failed",
                new IllegalArgumentException("root cause: db constraint violated"));
    }

    private static Map<String, String> context(String thread) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("thread", thread);
        body.put("correlationId", CorrelationIdHolder.correlationId());
        body.put("requestId", CorrelationIdHolder.requestId());
        return body;
    }
}
