package com.github.h3yow.springrequestobservabilityjava.observability;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
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
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保证每个进入过滤器链的请求恰好统计一次：
 * <ul>
 *     <li>同步请求：链返回（含异常被解析成错误响应）后立即记录；</li>
 *     <li>异步请求：通过 {@link AsyncListener} 在异步真正完成/出错时记录，
 *     耗时覆盖整个异步执行段；多个容器回调并存时用 once 标志保证不重复计数；</li>
 *     <li>链上直接抛出、未能正常收口的请求归类为 REJECTED_FAILURE，也计一次。</li>
 * </ul>
 * 路径优先取 HandlerMapping 匹配到的 URI 模板，避免路径参数造成指标基数爆炸；
 * 未匹配到处理器的请求统一归入 {@link #NO_HANDLER}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestMetricsFilter extends OncePerRequestFilter {

    static final String NO_HANDLER = "NO_HANDLER";

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsFilter.class);

    private final RequestMetrics metrics;

    public RequestMetricsFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            // 请求在过滤器链上被打断（含下游过滤器/容器拒绝），没能产生正常响应：计一次再抛出。
            safeRecord(request, RequestMetrics.Outcome.REJECTED_FAILURE, startNanos);
            throw ex;
        }
        if (request.isAsyncStarted()) {
            registerAsyncCompletion(request, response, startNanos);
        } else {
            safeRecord(request, classify(response.getStatus()), startNanos);
        }
    }

    private void registerAsyncCompletion(HttpServletRequest request, HttpServletResponse response,
                                         long startNanos) {
        // 容器可能对同一异步生命周期触发多个回调（如 timeout 后 complete/error），
        // 用 once 标志兜底，保证一次请求只记一次。
        AtomicBoolean recorded = new AtomicBoolean(false);
        AsyncContext asyncContext = request.getAsyncContext();
        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                recordOnce(classify(response.getStatus()));
            }

            @Override
            public void onError(AsyncEvent event) {
                int status = response.getStatus();
                recordOnce(status >= 400 ? classify(status) : RequestMetrics.Outcome.SERVER_FAILURE);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                recordOnce(RequestMetrics.Outcome.SERVER_FAILURE);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 不需要处理
            }

            private void recordOnce(RequestMetrics.Outcome outcome) {
                if (recorded.compareAndSet(false, true)) {
                    safeRecord(request, outcome, startNanos);
                }
            }
        });
    }

    private void safeRecord(HttpServletRequest request, RequestMetrics.Outcome outcome, long startNanos) {
        try {
            metrics.record(resolvePath(request), outcome, System.nanoTime() - startNanos);
        } catch (RuntimeException ex) {
            // 指标记录失败绝不能影响请求本身。
            log.error("记录请求指标失败", ex);
        }
    }

    /** 取 HandlerMapping 匹配到的路径模板，未匹配则归一为 NO_HANDLER。 */
    String resolvePath(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String pathTemplate && !pathTemplate.isBlank()) {
            return pathTemplate;
        }
        return NO_HANDLER;
    }

    private RequestMetrics.Outcome classify(int status) {
        if (status >= 200 && status < 300) {
            return RequestMetrics.Outcome.SUCCESS;
        }
        if (status >= 400 && status < 500) {
            return RequestMetrics.Outcome.BUSINESS_FAILURE;
        }
        return RequestMetrics.Outcome.SERVER_FAILURE;
    }
}
