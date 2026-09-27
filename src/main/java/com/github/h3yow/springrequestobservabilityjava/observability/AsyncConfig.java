package com.github.h3yow.springrequestobservabilityjava.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置：
 * <ul>
 *     <li>{@link TaskDecorator} 在提交任务的请求线程拍快照，在工作线程恢复（含 MDC），
 *     任务结束无条件清理——池化线程复用也不会把上一请求的关联 ID 带给下一请求；</li>
 *     <li>有界队列 + AbortPolicy：过载时拒绝的任务会在请求线程抛出
 *     RejectedExecutionException，由统一异常处理收口为 5xx，并被指标统计一次。</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("requestAwareExecutor")
    public ThreadPoolTaskExecutor requestAwareExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("req-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setTaskDecorator(contextPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /**
     * 上下文透传装饰器。decorate 在提交线程执行（此处拍快照），
     * 包装后的 run 在工作线程执行：先恢复，finally 清理。
     */
    static TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            // 关键：快照在请求线程上捕获，而不是在 run() 里（那时已换到工作线程）。
            RequestObservabilityContext snapshot = RequestObservabilityContext.snapshot();
            return () -> {
                RequestObservabilityContext.restore(snapshot);
                try {
                    runnable.run();
                } finally {
                    // 无论成功失败都清理，保证线程归还池中时 ThreadLocal/MDC 干净。
                    RequestObservabilityContext.clear();
                }
            };
        };
    }
}
