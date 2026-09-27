package com.github.h3yow.springrequestobservabilityjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：端到端 ID 透传、异常响应口径、并发下指标不丢不重、日志带关联 ID。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestObservabilityIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void echoesIncomingCorrelationIdOrGeneratesOne() {
        // 调用方带合法关联 ID：原样沿用，并回两个头
        HttpHeaders headers = new HttpHeaders();
        String inbound = "upstream-" + UUID.randomUUID().toString().replace("-", "");
        headers.add("X-Correlation-Id", inbound);
        ResponseEntity<Map> resp = rest.exchange(url("/demo/hello"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(inbound);
        String requestId = resp.getHeaders().getFirst("X-Request-Id");
        assertThat(requestId).startsWith("req-");

        // 不带关联 ID：服务端生成
        ResponseEntity<Map> generated = rest.getForEntity(url("/demo/hello"), Map.class);
        String generatedCid = generated.getHeaders().getFirst("X-Correlation-Id");
        assertThat(generatedCid).startsWith("cid-");
        // 两个不同请求的请求 ID 必须不同
        assertThat(generated.getHeaders().getFirst("X-Request-Id")).isNotEqualTo(requestId);

        // 非法关联 ID：拒绝沿用，改为服务端生成
        HttpHeaders bad = new HttpHeaders();
        bad.add("X-Correlation-Id", "has space!!");
        ResponseEntity<Map> rejected = rest.exchange(url("/demo/hello"), HttpMethod.GET,
                new HttpEntity<>(bad), Map.class);
        assertThat(rejected.getHeaders().getFirst("X-Correlation-Id")).startsWith("cid-");
    }

    @Test
    void asyncEndpointKeepsCorrelationIdOnWorkerThread() {
        HttpHeaders headers = new HttpHeaders();
        String inbound = "async-" + UUID.randomUUID().toString().replace("-", "");
        headers.add("X-Correlation-Id", inbound);
        ResponseEntity<Map> resp = rest.exchange(url("/demo/async"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        // 响应头与工作线程返回体里看到的关联 ID 一致 -> 已透传到异步线程
        assertThat(resp.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(inbound);
        Map body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("correlationId")).isEqualTo(inbound);
        assertThat(body.get("stage")).isEqualTo("async-worker");
        assertThat((String) body.get("thread")).startsWith("req-async-");
    }

    @Test
    void asyncWorkerThreadReuseDoesNotLeakPreviousCorrelationId() throws Exception {
        // 执行器核心线程只有 2 个，连续多次异步请求必然复用工作线程：
        // 每次工作线程返回体里的关联 ID 必须与本次请求头一致，不能串到上一个请求。
        for (int i = 0; i < 6; i++) {
            HttpHeaders headers = new HttpHeaders();
            String inbound = "reuse-" + i + "-" + UUID.randomUUID().toString().replace("-", "");
            headers.add("X-Correlation-Id", inbound);
            ResponseEntity<Map> resp = rest.exchange(url("/demo/async"), HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(resp.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(inbound);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().get("correlationId"))
                    .as("第 %d 次复用异步线程，关联 ID 不得被上一请求污染", i)
                    .isEqualTo(inbound);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncErrorKeepsCorrelationIdInUnifiedResponseBody() {
        // 异步工作线程抛异常：容器恢复请求线程做 ASYNC 分发时必须能还原上下文，
        // 统一 500 响应体与响应头的关联 ID 一致，且不泄露内部异常文本。
        HttpHeaders headers = new HttpHeaders();
        String inbound = "asyncerr-" + UUID.randomUUID().toString().replace("-", "");
        headers.add("X-Correlation-Id", inbound);
        ResponseEntity<Map> resp = rest.exchange(url("/demo/async-error"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(inbound);
        Map body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo("INTERNAL_ERROR");
        assertThat(body.get("correlationId")).isEqualTo(inbound);
        assertThat(body.get("requestId")).isEqualTo(resp.getHeaders().getFirst("X-Request-Id"));
        assertThat((String) body.get("message")).doesNotContain("下游服务超时");
    }

    @Test
    @SuppressWarnings("unchecked")
    void businessAndUnexpectedErrorsReturnUnifiedDistinguishableBody() {
        // 业务异常 409：对外是业务文案，不含内部 cause 细节
        ResponseEntity<Map> business = rest.getForEntity(url("/demo/business-error"), Map.class);
        assertThat(business.getStatusCode().value()).isEqualTo(409);
        Map bizBody = business.getBody();
        assertThat(bizBody).isNotNull();
        assertThat(bizBody.get("code")).isEqualTo("BUSINESS_CONFLICT");
        assertThat((String) bizBody.get("message")).doesNotContain("SKU", "库存服务");
        assertThat(bizBody.get("correlationId"))
                .isEqualTo(business.getHeaders().getFirst("X-Correlation-Id"));

        // 未处理异常 500：统一兜底文案，内部类名/连接串一律不外泄
        ResponseEntity<Map> unexpected = rest.getForEntity(url("/demo/unexpected-error"), Map.class);
        assertThat(unexpected.getStatusCode().value()).isEqualTo(500);
        Map sysBody = unexpected.getBody();
        assertThat(sysBody).isNotNull();
        assertThat(sysBody.get("code")).isEqualTo("INTERNAL_ERROR");
        assertThat((String) sysBody.get("message")).doesNotContain("jdbc", "RuntimeException", "secret");
        assertThat(sysBody.get("correlationId"))
                .isEqualTo(unexpected.getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentRequestsAreCountedExactlyOnce() throws Exception {
        int threads = 16;
        int perThread = 25;
        int total = threads * perThread;

        // 并发请求前先记录 /demo/hello 的基线，避免被其他用例的数字干扰
        Map<String, Object> before = metricsForHello();
        long baseline = before == null ? 0L : ((Number) before.get("totalCount")).longValue();

        // 使用 JDK 内置 HttpClient：默认连接池不按路由收紧，避免 16 线程交错等待
        // TestRestTemplate(Apache hc5, maxConnPerRoute=25) 的连接而互相饿死。
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        java.net.http.HttpRequest probe = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(url("/demo/hello"))).timeout(Duration.ofSeconds(10)).GET().build();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        // Phaser 初始只有主线程一个参与方；每个请求注册一次、完成后到达并注销。
        java.util.concurrent.Phaser requests = new java.util.concurrent.Phaser(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        requests.register();
                        try {
                            int status = client.send(probe,
                                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
                            if (status == 200) {
                                ok.incrementAndGet();
                            } else {
                                errors.incrementAndGet();
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            // 每个请求恰好到达一次，任何异常都不会让测试假死
                            requests.arriveAndDeregister();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        requests.arriveAndAwaitAdvance();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).as("并发请求不应出现错误响应/异常").isZero();
        assertThat(ok.get()).isEqualTo(total);

        // 实际发出的请求数必须与指标增量逐一对上：不丢、不重
        Map<String, Object> after = metricsForHello();
        assertThat(after).isNotNull();
        assertThat(((Number) after.get("totalCount")).longValue() - baseline).isEqualTo(total);
        assertThat(((Number) after.get("successCount")).longValue()
                - (before == null ? 0L : ((Number) before.get("successCount")).longValue()))
                .isEqualTo(total);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedAndRejectedRequestsAreAlsoCountedOnce() {
        // 业务异常与未处理异常各一次，对应桶分别 +1；未匹配路径归入 /**（或极端情况下 NO_HANDLER）
        rest.getForEntity(url("/demo/business-error"), Map.class);
        rest.getForEntity(url("/demo/unexpected-error"), Map.class);
        ResponseEntity<Map> noHandler = rest.getForEntity(url("/demo/does-not-exist"), Map.class);
        assertThat(noHandler.getStatusCode().value()).isEqualTo(404);

        Map<String, Object> metrics = rest.getForEntity(url("/observability/metrics/requests"), Map.class).getBody();
        assertThat(metrics).isNotNull();
        Map<String, Map> byPath = (Map<String, Map>) metrics.get("byPath");

        Map biz = byPath.get("/demo/business-error");
        assertThat(((Number) biz.get("totalCount")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) biz.get("businessFailureCount")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) biz.get("serverFailureCount")).longValue()).isZero();

        Map sys = byPath.get("/demo/unexpected-error");
        assertThat(((Number) sys.get("serverFailureCount")).longValue()).isGreaterThanOrEqualTo(1);

        // Spring Boot 未匹配路径会落到静态资源处理器(/**)后抛 404；极端无处理器时才是 NO_HANDLER
        Map none = byPath.containsKey("NO_HANDLER") ? byPath.get("NO_HANDLER") : byPath.get("/**");
        assertThat(none).isNotNull();
        assertThat(((Number) none.get("totalCount")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) none.get("businessFailureCount")).longValue())
                .as("404 属于 4xx 业务失败类").isGreaterThanOrEqualTo(1);

        // 指标接口本身只读且不携带任何写入入口
        assertThat(metrics).containsOnlyKeys("summary", "byPath");
    }

    @Test
    void requestLogsContainCorrelationId() {
        // 在过滤器与控制器两个 logger 上挂内存 appender，断言日志行里带 MDC 关联 ID（判定依据）
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger filterLogger = (Logger) LoggerFactory
                .getLogger("com.github.h3yow.springrequestobservabilityjava.observability.RequestIdFilter");
        Logger controllerLogger = (Logger) LoggerFactory
                .getLogger("com.github.h3yow.springrequestobservabilityjava.web.DemoController");
        filterLogger.addAppender(appender);
        controllerLogger.addAppender(appender);
        try {
            HttpHeaders headers = new HttpHeaders();
            String inbound = "log-" + UUID.randomUUID().toString().replace("-", "");
            headers.add("X-Correlation-Id", inbound);
            rest.exchange(url("/demo/hello"), HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            List<ILoggingEvent> events = appender.list;
            assertThat(events).isNotEmpty();
            boolean matched = events.stream().anyMatch(event ->
                    inbound.equals(event.getMDCPropertyMap().get("correlationId"))
                            && event.getMDCPropertyMap().get("requestId") != null);
            assertThat(matched)
                    .as("至少一条请求链路上的日志带本次 correlationId=%s，实际MDC=%s", inbound,
                            events.stream().map(e -> e.getMDCPropertyMap().toString()).toList())
                    .isTrue();
            assertThat(events).anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO));
        } finally {
            filterLogger.detachAppender(appender);
            controllerLogger.detachAppender(appender);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metricsForHello() {
        Map<String, Object> metrics = rest.getForEntity(url("/observability/metrics/requests"), Map.class).getBody();
        if (metrics == null) {
            return null;
        }
        return (Map<String, Object>) ((Map<String, Object>) metrics.get("byPath")).get("/demo/hello");
    }
}
