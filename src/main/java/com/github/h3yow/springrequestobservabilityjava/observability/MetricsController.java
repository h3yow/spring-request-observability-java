package com.github.h3yow.springrequestobservabilityjava.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only view over collected request metrics.
 */
@RestController
public class MetricsController {

    private final RequestMetrics metrics;

    public MetricsController(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics/requests")
    public Map<String, RequestMetrics.PathStats> requests() {
        return metrics.snapshot();
    }
}
