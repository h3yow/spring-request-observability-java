package com.github.h3yow.springrequestobservabilityjava.tests;

import com.github.h3yow.springrequestobservabilityjava.observability.CorrelationIdFilter;
import com.github.h3yow.springrequestobservabilityjava.observability.RequestMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies correlation-ID propagation, thread-pool isolation, error mapping
 * and per-path metrics under concurrency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityTests {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityTests.class);
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RequestMetrics metrics;

    @BeforeEach
    void resetMetrics() {
        metrics.reset();
    }

    // ---------- correlation ID rules ----------

    @Test
    void validIncomingCorrelationIdIsReused() {
        String incoming = "caller-corr-0001";
        ResponseEntity<Map> res = get("/api/hello", incoming);

        String echoed = res.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        log.info("valid header reused: sent={} echoed={} -> expect equal", incoming, echoed);
        assertThat(echoed).isEqualTo(incoming);
        assertThat(res.getBody()).containsEntry("correlationId", incoming);
        assertThat(res.getHeaders().getFirst(CorrelationIdFilter.REQUEST_ID_HEADER))
                .matches(UUID_PATTERN);
    }

    @Test
    void missingOrInvalidCorrelationIdIsReplaced() {
        ResponseEntity<Map> missing = get("/api/hello", null);
        String generated = missing.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        log.info("missing header: generated={} -> expect UUID", generated);
        assertThat(generated).matches(UUID_PATTERN);

        ResponseEntity<Map> invalid = get("/api/hello", "bad id!!");
        String replaced = invalid.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        log.info("invalid header: sent='bad id!!' echoed={} -> expect regenerated UUID", replaced);
        assertThat(replaced).matches(UUID_PATTERN);
    }

    // ---------- concurrency: no cross-request pollution, exact metrics ----------

    @Test
    void concurrentRequestsKeepIsolatedIdsAndExactCounts() throws Exception {
        int n = 40;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add("conc-corr-" + i + "-abcdef");
        }

        List<ResponseEntity<Map>> responses = runConcurrently(ids, "/api/hello");

        for (int i = 0; i < n; i++) {
            String echoed = responses.get(i).getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
            Object bodyCid = responses.get(i).getBody().get("correlationId");
            log.info("isolation check #{}: sent={} echoedHeader={} bodyCid={} -> all must match",
                    i, ids.get(i), echoed, bodyCid);
            assertThat(echoed).isEqualTo(ids.get(i));
            assertThat(bodyCid).isEqualTo(ids.get(i));
        }

        RequestMetrics.PathStats stats = metrics.snapshot().get("/api/hello");
        log.info("metrics check: path=/api/hello expectedCount={} actual={} failures={}",
                n, stats.count(), stats.failureCount());
        assertThat(stats.count()).isEqualTo(n);
        assertThat(stats.failureCount()).isZero();
    }

    // ---------- async: IDs hop threads, reused threads stay clean ----------

    @Test
    void asyncRequestsPropagateIdsAcrossReusedPoolThreads() throws Exception {
        int n = 24; // pool size is 2 -> heavy thread reuse
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add("async-corr-" + i + "-abcdef");
        }

        List<ResponseEntity<Map>> responses = runConcurrently(ids, "/api/async");

        for (int i = 0; i < n; i++) {
            Map body = responses.get(i).getBody();
            log.info("async check #{}: sent={} workerCid={} requestThreadCid={} thread={} -> ids must match, worker thread must differ",
                    i, ids.get(i), body.get("correlationId"),
                    body.get("correlationIdOnRequestThread"), body.get("thread"));
            assertThat(body.get("correlationId")).isEqualTo(ids.get(i));
            assertThat(body.get("correlationIdOnRequestThread")).isEqualTo(ids.get(i));
            assertThat((String) body.get("thread")).startsWith("async-obs-async-");
            assertThat(body.get("requestId")).isNotNull();
        }

        RequestMetrics.PathStats stats = metrics.snapshot().get("/api/async");
        log.info("metrics check: path=/api/async expectedCount={} actual={}", n, stats.count());
        assertThat(stats.count()).isEqualTo(n);
    }

    // ---------- errors: uniform payload, root cause kept server-side, counted once ----------

    @Test
    void businessErrorReturnsUniformPayloadAndIsCounted() {
        String cid = "biz-corr-00000001";
        ResponseEntity<Map> res = get("/api/biz-error", cid);

        log.info("biz error: status={} body={} -> expect 422 + code BIZ_VALIDATION_FAILED + cid echoed",
                res.getStatusCode(), res.getBody());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody()).containsEntry("code", "BIZ_VALIDATION_FAILED");
        assertThat(res.getBody()).containsEntry("correlationId", cid);
        assertThat(res.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(cid);

        RequestMetrics.PathStats stats = metrics.snapshot().get("/api/biz-error");
        log.info("metrics check: path=/api/biz-error count={} failures={} -> expect 1/1",
                stats.count(), stats.failureCount());
        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.failureCount()).isEqualTo(1);
    }

    @Test
    void unexpectedErrorHidesInternalsAndIsCounted() {
        String cid = "boom-corr-00000001";
        ResponseEntity<Map> res = get("/api/boom", cid);

        log.info("unexpected error: status={} body={} -> expect 500 + INTERNAL_ERROR, no internal details",
                res.getStatusCode(), res.getBody());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(res.getBody()).containsEntry("correlationId", cid);
        assertThat((String) res.getBody().get("message"))
                .doesNotContain("db constraint")
                .doesNotContain("IllegalArgumentException");

        RequestMetrics.PathStats stats = metrics.snapshot().get("/api/boom");
        log.info("metrics check: path=/api/boom count={} failures={} -> expect 1/1",
                stats.count(), stats.failureCount());
        assertThat(stats.count()).isEqualTo(1);
        assertThat(stats.failureCount()).isEqualTo(1);
    }

    @Test
    void metricsEndpointIsReadOnlyAndDoesNotCountItself() {
        get("/api/hello", "ro-corr-000000001");
        ResponseEntity<Map> first = rest.getForEntity("/metrics/requests", Map.class);
        ResponseEntity<Map> second = rest.getForEntity("/metrics/requests", Map.class);

        log.info("metrics endpoint: keys={} -> /metrics/requests must not appear, /api/hello count stable",
                second.getBody().keySet());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).doesNotContainKey("/metrics/requests");
        Map hello = (Map) second.getBody().get("/api/hello");
        assertThat(((Number) hello.get("count")).longValue()).isEqualTo(1L);
    }

    // ---------- helpers ----------

    private ResponseEntity<Map> get(String path, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        if (correlationId != null) {
            headers.set(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private List<ResponseEntity<Map>> runConcurrently(List<String> ids, String path) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<ResponseEntity<Map>>> tasks = ids.stream()
                    .<Callable<ResponseEntity<Map>>>map(id -> () -> get(path, id))
                    .toList();
            List<Future<ResponseEntity<Map>>> futures = pool.invokeAll(tasks);
            List<ResponseEntity<Map>> results = new ArrayList<>();
            for (Future<ResponseEntity<Map>> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
