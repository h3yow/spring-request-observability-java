package com.github.h3yow.springrequestobservabilityjava.config;

import com.github.h3yow.springrequestobservabilityjava.observability.CorrelationIdHolder;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Async executor whose worker threads inherit the submitting request's
 * correlation/request IDs (and full MDC), and clear them afterwards so a
 * reused worker thread can never leak one request's context into another.
 * <p>
 * The pool is intentionally small (2 threads) so thread reuse is exercised
 * even under light load.
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "observabilityExecutor")
    public Executor observabilityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("obs-async-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * Captures the MDC on the submitting (request) thread and replays it on the
     * worker thread for the duration of the task, always clearing afterwards.
     */
    static class ContextPropagatingTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (context != null) {
                        MDC.setContextMap(context);
                    } else {
                        MDC.clear();
                    }
                    runnable.run();
                } finally {
                    // Never leave this request's IDs on a pooled thread.
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                    CorrelationIdHolder.clear();
                }
            };
        }
    }
}
