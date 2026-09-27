package com.github.h3yow.springrequestobservabilityjava.observability;

import com.github.h3yow.springrequestobservabilityjava.observability.RequestIdFilter;
import com.github.h3yow.springrequestobservabilityjava.observability.RequestObservabilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单测：ID 合法性判定、上下文跨线程透传、池化线程复用时不串数据。
 */
class RequestIdPropagationTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void tearDown() {
        RequestObservabilityContext.clear();
    }

    @Test
    void acceptsLegalIncomingCorrelationIdAndRejectsIllegalOnes() {
        // 合法：8~64 位，字母/数字/下划线/连字符
        assertThat(filter.isAcceptableId("abc123_-")).isTrue();
        assertThat(filter.resolveCorrelationId("abc123_-")).isEqualTo("abc123_-");

        // 判定依据：null、含空格、含冒号、超长(>64)、过短(<8) 一律不合法
        assertThat(filter.isAcceptableId(null)).isFalse();
        assertThat(filter.isAcceptableId("abc 1234")).isFalse();
        assertThat(filter.isAcceptableId("abc:1234")).isFalse();
        assertThat(filter.isAcceptableId("short")).isFalse();
        assertThat(filter.isAcceptableId("a".repeat(65))).isFalse();

        // 不合法时服务端生成，前缀 cid-，自身格式也合法
        String generated = filter.resolveCorrelationId("bad id");
        assertThat(generated).startsWith("cid-");
        assertThat(filter.isAcceptableId(generated)).isTrue();

        // 请求 ID 每次都独立生成，前缀 req-，且两次不相等
        String req1 = filter.generateRequestId();
        String req2 = filter.generateRequestId();
        assertThat(req1).startsWith("req-").isNotEqualTo(req2);
    }

    @Test
    void propagatesContextToAsyncWorkerAndRestoresMdc() throws Exception {
        RequestObservabilityContext.begin("cid-parent001", "req-parent001");
        var snapshot = RequestObservabilityContext.snapshot();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String[]> future = executor.submit(() -> {
                RequestObservabilityContext.restore(snapshot);
                try {
                    RequestObservabilityContext worker = RequestObservabilityContext.current();
                    // 工作线程看到的 ID 与请求线程完全一致，MDC 也已同步（日志判定依据）
                    return new String[]{
                            worker.correlationId(), worker.requestId(),
                            MDC.get(RequestObservabilityContext.CORRELATION_ID_MDC_KEY),
                            MDC.get(RequestObservabilityContext.REQUEST_ID_MDC_KEY)};
                } finally {
                    RequestObservabilityContext.clear();
                }
            });
            String[] seen = future.get(5, TimeUnit.SECONDS);
            assertThat(seen[0]).isEqualTo("cid-parent001");
            assertThat(seen[1]).isEqualTo("req-parent001");
            assertThat(seen[2]).isEqualTo("cid-parent001");
            assertThat(seen[3]).isEqualTo("req-parent001");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        // 父线程上下文不受工作线程 clear 影响（快照是不可变对象，单向传递）
        assertThat(RequestObservabilityContext.current().correlationId()).isEqualTo("cid-parent001");
    }

    @Test
    void pooledThreadIsCleanedBetweenRequests() throws Exception {
        // 单线程池：两个“请求”先后复用同一条工作线程，验证清理后不会串 ID。
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> {
                RequestObservabilityContext.restore(null);
                RequestObservabilityContext.begin("cid-requestAAA", "req-requestAAA");
                String threadName = Thread.currentThread().getName();
                RequestObservabilityContext.clear();
                return threadName;
            });
            String pooledThread = first.get(5, TimeUnit.SECONDS);

            Future<Boolean> second = executor.submit(() -> {
                // 复用同一线程时，上一请求的 ThreadLocal 与 MDC 必须都已清空
                boolean noContext = RequestObservabilityContext.current() == null;
                boolean mdcClean = MDC.get(RequestObservabilityContext.CORRELATION_ID_MDC_KEY) == null
                        && MDC.get(RequestObservabilityContext.REQUEST_ID_MDC_KEY) == null;
                boolean sameThread = Thread.currentThread().getName().equals(pooledThread);
                return noContext && mdcClean && sameThread;
            });
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
