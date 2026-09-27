package com.github.h3yow.springrequestobservabilityjava.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求指标只读查询接口：{@code GET /observability/metrics/requests}。
 * 只暴露汇总数字，不提供任何写入/重置能力。
 */
@RestController
@RequestMapping("/observability/metrics")
public class MetricsController {

    private final RequestMetrics metrics;

    public MetricsController(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/requests")
    public Map<String, Object> requestMetrics() {
        Map<String, RequestMetrics.PathMetrics> byPath = metrics.snapshotByPath();

        long total = 0;
        long success = 0;
        long businessFailures = 0;
        long serverFailures = 0;
        long rejectedFailures = 0;
        for (RequestMetrics.PathMetrics pathMetrics : byPath.values()) {
            total += pathMetrics.totalCount();
            success += pathMetrics.successCount();
            businessFailures += pathMetrics.businessFailureCount();
            serverFailures += pathMetrics.serverFailureCount();
            rejectedFailures += pathMetrics.rejectedFailureCount();
        }

        double failureRate = total == 0 ? 0.0
                : (double) (serverFailures + rejectedFailures) / total;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCount", total);
        summary.put("successCount", success);
        summary.put("businessFailureCount", businessFailures);
        summary.put("serverFailureCount", serverFailures);
        summary.put("rejectedFailureCount", rejectedFailures);
        // 失败率口径：(服务端失败 + 被拒绝) / 总请求；业务失败(4xx)不计入。
        summary.put("failureRate", failureRate);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", summary);
        body.put("byPath", byPath);
        return body;
    }
}
