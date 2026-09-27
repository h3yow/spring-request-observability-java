package com.github.h3yow.springrequestobservabilityjava.observability;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe, in-memory per-path request metrics.
 * <p>
 * Counters use {@link LongAdder}s so concurrent requests never lose or
 * duplicate updates. {@link #record(String, long, boolean)} must be called
 * exactly once per request (including failed/rejected ones).
 */
@Component
public class RequestMetrics {

    /**
     * Immutable snapshot of metrics for one request path.
     *
     * @param count               total requests seen for the path
     * @param failureCount        requests that failed (exception or HTTP status >= 400)
     * @param totalDurationMillis cumulative server-side processing time
     * @param avgDurationMillis   average processing time per request
     */
    public record PathStats(long count, long failureCount, long totalDurationMillis, double avgDurationMillis) {
    }

    private static final class Counters {
        private final LongAdder count = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
    }

    private final ConcurrentHashMap<String, Counters> byPath = new ConcurrentHashMap<>();

    public void record(String path, long durationNanos, boolean failed) {
        Counters c = byPath.computeIfAbsent(path, k -> new Counters());
        c.count.increment();
        c.totalNanos.add(durationNanos);
        if (failed) {
            c.failures.increment();
        }
    }

    /**
     * Returns a consistent, immutable snapshot keyed by request path (sorted).
     */
    public Map<String, PathStats> snapshot() {
        Map<String, PathStats> result = new TreeMap<>();
        byPath.forEach((path, c) -> {
            long count = c.count.sum();
            long failures = c.failures.sum();
            long totalMillis = TimeUnit.NANOSECONDS.toMillis(c.totalNanos.sum());
            double avg = count == 0 ? 0.0 : (double) totalMillis / count;
            result.put(path, new PathStats(count, failures, totalMillis, avg));
        });
        return Map.copyOf(result);
    }

    /** Test hook: reset all counters. */
    public void reset() {
        byPath.clear();
    }
}
