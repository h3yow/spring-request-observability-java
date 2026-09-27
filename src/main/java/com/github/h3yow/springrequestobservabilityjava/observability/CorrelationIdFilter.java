package com.github.h3yow.springrequestobservabilityjava.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs first for every HTTP request and guarantees:
 * <ul>
 *   <li>a correlation ID: the caller-provided {@code X-Correlation-ID} header is
 *       reused when it matches {@value #ID_PATTERN_DESCRIPTION}; otherwise a new
 *       one is generated (missing or malformed values are never trusted);</li>
 *   <li>a fresh request ID unique to this request;</li>
 *   <li>both IDs are echoed back via response headers and bound to the logging
 *       context for the whole request, then cleared so pooled threads never
 *       leak them into the next request;</li>
 *   <li>exactly one metrics record per request, including failed or rejected
 *       requests (failure = uncaught exception or HTTP status &gt;= 400).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    static final String ID_PATTERN_DESCRIPTION = "8-64 chars of [A-Za-z0-9-]";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private final RequestMetrics metrics;

    public CorrelationIdFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
        String requestId = newId();

        CorrelationIdHolder.set(correlationId, requestId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        String path = request.getRequestURI();
        log.info("request started method={} path={}", request.getMethod(), path);

        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            int status = response.getStatus();
            boolean failed = failure != null || status >= 400;
            // The metrics endpoint observes the stats; it must not count itself.
            if (!path.startsWith("/metrics")) {
                metrics.record(path, durationNanos, failed);
            }
            log.info("request finished method={} path={} status={} failed={} durationMs={}",
                    request.getMethod(), path, status, failed,
                    TimeUnit.NANOSECONDS.toMillis(durationNanos));
            CorrelationIdHolder.clear();
        }
    }

    private String resolveCorrelationId(String incoming) {
        if (incoming != null && VALID_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return newId();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
