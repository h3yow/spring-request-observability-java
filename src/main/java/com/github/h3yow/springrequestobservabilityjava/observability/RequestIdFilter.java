package com.github.h3yow.springrequestobservabilityjava.observability;

import jakarta.servlet.DispatcherType;
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
import java.util.regex.Pattern;

/**
 * 为每个 HTTP 请求解析/生成关联 ID 与请求 ID：
 * <ul>
 *     <li>关联 ID：调用方通过 {@code X-Correlation-Id} 传入且格式合法时沿用；
 *     缺失或不合法时由服务端生成，并记一条 warn 说明判定依据。</li>
 *     <li>请求 ID：每个请求都由服务端重新生成，仅在本请求（含其异步延续）内稳定。</li>
 *     <li>两个 ID 都会写入响应头 {@code X-Correlation-Id} / {@code X-Request-Id}。</li>
 * </ul>
 * 三种分发阶段的处理：
 * <ul>
 *     <li>REQUEST：建立上下文、写响应头；若开启异步则<b>不清</b>上下文（容器恢复请求线程后还要用），
 *     由随后的 ASYNC 分发负责最终清理；同步请求在本阶段 finally 清理。</li>
 *     <li>ASYNC：容器恢复请求线程时从 request 属性恢复 MDC（同线程 ThreadLocal 已在 REQUEST
 *     返回时被容器等待期间的其它请求复用清空），保证异步正常/异常处理日志与统一错误响应都带 ID，
 *     结束后清理。</li>
 *     <li>ERROR：容器直接错误分发时同样从属性恢复。</li>
 * </ul>
 * 工作线程（@Async / supplyAsync 池线程）上的上下文由 {@code AsyncConfig} 的 TaskDecorator 透传。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 初始请求保存的 ID 属性，供 ASYNC / ERROR 分发在容器恢复线程后还原 MDC。 */
    static final String CORRELATION_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".CORRELATION_ID";
    static final String REQUEST_ID_ATTRIBUTE = RequestIdFilter.class.getName() + ".REQUEST_ID";

    static final int ID_MIN_LENGTH = 8;
    static final int ID_MAX_LENGTH = 64;
    static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // 必须参与 ASYNC 分发：异步任务成功/失败恢复到请求线程后，靠这里恢复 MDC 并最终清理。
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // ERROR 分发同样恢复上下文，保证错误响应体带得回关联 ID。
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        DispatcherType dispatcherType = request.getDispatcherType();
        if (dispatcherType == DispatcherType.ASYNC || dispatcherType == DispatcherType.ERROR) {
            // 容器已恢复到请求线程，ThreadLocal 可能在请求挂起期间被清掉；从属性恢复后放行并最终清理。
            restoreFromAttributes(request);
            try {
                filterChain.doFilter(request, response);
            } finally {
                RequestObservabilityContext.clear();
            }
            return;
        }

        String incoming = request.getHeader(CORRELATION_ID_HEADER);
        boolean inherited = isAcceptableId(incoming);
        String correlationId = resolveCorrelationId(incoming);
        String requestId = generateRequestId();

        if (!inherited && incoming != null && !incoming.isBlank()) {
            log.warn("入站关联 ID 不合法，已改为服务端生成。判定依据: 值长度/字符需匹配 {}，incoming=\"{}\"",
                    ID_PATTERN.pattern(), incoming);
        }

        RequestObservabilityContext.begin(correlationId, requestId);
        // 存到 request 属性：ASYNC/ERROR 分发容器恢复线程后凭此还原（请求对象跨整个生命周期有效）。
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        // 尽早写响应头：即使后续链路抛异常，响应提交时这两个头也一定在。
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startNanos = System.nanoTime();
        log.info("收到请求: method={}, uri={}, 关联ID来源={}", request.getMethod(),
                request.getRequestURI(), inherited ? "调用方透传" : "服务端生成");
        try {
            filterChain.doFilter(request, response);
            log.info("请求处理完成: status={}, 耗时Ms={}", response.getStatus(),
                    elapsedMillis(startNanos));
        } catch (IOException | ServletException | RuntimeException ex) {
            // 异常细节（含 cause 链）交给 GlobalExceptionHandler 记录；这里保证完成日志带关联 ID。
            log.error("请求处理抛出未在 Controller 层收口的异常: type={}, 耗时Ms={}",
                    ex.getClass().getName(), elapsedMillis(startNanos), ex);
            throw ex;
        } finally {
            // 已开启异步时不能清：容器很快会在同一线程发起 ASYNC 分发，需要 ThreadLocal 仍在；
            // 真正的清理发生在 ASYNC 分发分支。同步请求在此立即清理。
            if (!request.isAsyncStarted()) {
                RequestObservabilityContext.clear();
            }
        }
    }

    private void restoreFromAttributes(HttpServletRequest request) {
        Object cid = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        Object rid = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (cid instanceof String correlationId && rid instanceof String requestId) {
            RequestObservabilityContext.begin(correlationId, requestId);
            log.info("{} 分发恢复关联上下文: correlationId={}, requestId={}",
                    request.getDispatcherType(), correlationId, requestId);
        } else {
            // 极端情况下没有初始上下文（如容器层直接产生的错误），生成仅用于错误响应关联的新 ID。
            RequestObservabilityContext.begin(newId("cid-"), newId("req-"));
        }
    }

    /** 入站关联 ID 合法则沿用，否则生成新的。 */
    String resolveCorrelationId(String incoming) {
        return isAcceptableId(incoming) ? incoming : newId("cid-");
    }

    /** 每个请求始终新生成请求 ID。 */
    String generateRequestId() {
        return newId("req-");
    }

    boolean isAcceptableId(String value) {
        if (value == null) {
            return false;
        }
        int length = value.length();
        if (length < ID_MIN_LENGTH || length > ID_MAX_LENGTH) {
            return false;
        }
        return ID_PATTERN.matcher(value).matches();
    }

    String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
