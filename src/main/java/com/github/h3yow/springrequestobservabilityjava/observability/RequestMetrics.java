package com.github.h3yow.springrequestobservabilityjava.observability;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 按请求路径统计请求量、耗时与失败率。
 * <p>
 * 并发安全：计数使用 {@link LongAdder}（高并发下 CAS 分段累加，不丢更新），
 * 最大耗时使用 {@link AtomicLong}；路径桶使用 {@link ConcurrentHashMap} 惰性创建，
 * 同一桶只发布一次，保证每次 {@link #record} 只命中唯一桶——配合过滤器的"每请求一次
 * record"，做到计数不丢、不重。
 */
@Component
public class RequestMetrics {

    /** 一次请求的归类结果。 */
    public enum Outcome {
        /** 2xx 正常完成。 */
        SUCCESS,
        /** 业务异常（4xx，可预期，不计入服务端失败率）。 */
        BUSINESS_FAILURE,
        /** 未处理异常 / 5xx。 */
        SERVER_FAILURE,
        /** 请求在进入业务处理前被拒绝（如未匹配处理器、容器层拒绝）。 */
        REJECTED_FAILURE
    }

    /** 耗时统计快照（毫秒）。 */
    public record DurationStats(long count, long totalMillis, long maxMillis, double avgMillis) {
    }

    /** 单条路径的指标快照（只读视图）。 */
    public record PathMetrics(String path, long totalCount, long successCount,
                              long businessFailureCount, long serverFailureCount,
                              long rejectedFailureCount, double failureRate,
                              DurationStats latency) {
    }

    private static final class PathCounters {
        private final LongAdder total = new LongAdder();
        private final LongAdder success = new LongAdder();
        private final LongAdder businessFailure = new LongAdder();
        private final LongAdder serverFailure = new LongAdder();
        private final LongAdder rejectedFailure = new LongAdder();
        private final LongAdder latencyTotalNanos = new LongAdder();
        private final AtomicLong latencyMaxNanos = new AtomicLong();

        void record(Outcome outcome, long latencyNanos) {
            // 所有计数在一次调用内同步累加，且只调用一次 -> 不丢不重。
            total.increment();
            switch (outcome) {
                case SUCCESS -> success.increment();
                case BUSINESS_FAILURE -> businessFailure.increment();
                case SERVER_FAILURE -> serverFailure.increment();
                case REJECTED_FAILURE -> rejectedFailure.increment();
            }
            latencyTotalNanos.add(latencyNanos);
            latencyMaxNanos.accumulateAndGet(Math.max(0L, latencyNanos), Math::max);
        }

        PathMetrics snapshot(String path) {
            long totalCount = total.sum();
            long serverFailures = serverFailure.sum();
            long rejectedFailures = rejectedFailure.sum();
            long totalNanos = latencyTotalNanos.sum();
            double failureRate = totalCount == 0 ? 0.0
                    : (double) (serverFailures + rejectedFailures) / totalCount;
            DurationStats latency = new DurationStats(
                    totalCount,
                    totalNanos / 1_000_000,
                    latencyMaxNanos.get() / 1_000_000,
                    totalCount == 0 ? 0.0 : (double) totalNanos / totalCount / 1_000_000.0);
            return new PathMetrics(path, totalCount, success.sum(), businessFailure.sum(),
                    serverFailures, rejectedFailures, failureRate, latency);
        }
    }

    private final ConcurrentHashMap<String, PathCounters> countersByPath = new ConcurrentHashMap<>();

    /**
     * 记录一次请求的结果与耗时。调用方必须保证每个请求恰好调用一次。
     *
     * @param path         归一化后的路径（优先 URI 模板，未匹配处理器用 {@code NO_HANDLER}）
     * @param outcome      归类结果
     * @param latencyNanos 纳秒耗时，负值按 0 处理
     */
    public void record(String path, Outcome outcome, long latencyNanos) {
        countersByPath.computeIfAbsent(path, key -> new PathCounters()).record(outcome, latencyNanos);
    }

    /** 导出全部路径的只读指标快照，按路径名字典序排列。 */
    public Map<String, PathMetrics> snapshotByPath() {
        TreeMap<String, PathMetrics> result = new TreeMap<>();
        countersByPath.forEach((path, counters) -> result.put(path, counters.snapshot(path)));
        return Map.copyOf(result);
    }
}
