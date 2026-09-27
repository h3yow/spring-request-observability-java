package com.github.h3yow.springrequestobservabilityjava.observability;

import org.slf4j.MDC;

/**
 * 请求级关联信息上下文（关联 ID + 请求 ID）。
 * <p>
 * 基于 ThreadLocal，且与 SLF4J MDC 保持同步，保证日志模板里始终能取到两个 ID。
 * 对象本身不可变，可作为快照安全地跨线程传递；工作线程执行完必须 {@link #clear()}，
 * 避免池化线程被复用时把上一个请求的 ID 带给下一个请求。
 */
public final class RequestObservabilityContext {

    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final ThreadLocal<RequestObservabilityContext> HOLDER = new ThreadLocal<>();

    private final String correlationId;
    private final String requestId;

    private RequestObservabilityContext(String correlationId, String requestId) {
        this.correlationId = correlationId;
        this.requestId = requestId;
    }

    /**
     * 在当前线程建立上下文并写入 MDC。
     *
     * @throws IllegalStateException 当前线程已存在上下文时（说明上一个请求未清理）
     */
    public static void begin(String correlationId, String requestId) {
        RequestObservabilityContext existing = HOLDER.get();
        if (existing != null) {
            // 防御性检查：复用线程上残留上下文意味着上游漏了 clear()，先清掉再重建，避免串数据。
            clear();
        }
        RequestObservabilityContext context = new RequestObservabilityContext(correlationId, requestId);
        HOLDER.set(context);
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
    }

    /** 当前线程的上下文，不存在时返回 null。 */
    public static RequestObservabilityContext current() {
        return HOLDER.get();
    }

    /** 清理当前线程上下文及 MDC，避免线程复用串数据。 */
    public static void clear() {
        HOLDER.remove();
        MDC.remove(CORRELATION_ID_MDC_KEY);
        MDC.remove(REQUEST_ID_MDC_KEY);
    }

    /**
     * 拍快照，用于跨线程透传。应在提交异步任务的请求线程上调用；
     * 上下文对象不可变，工作线程拿到的只是引用，不会反向污染请求线程。
     *
     * @return 当前上下文快照；无上下文时返回 null
     */
    public static RequestObservabilityContext snapshot() {
        return HOLDER.get();
    }

    /**
     * 在工作线程上恢复快照并写入 MDC。任务结束后必须再调用一次 {@link #clear()}。
     *
     * @param snapshot 上游线程的快照；传 null 表示恢复为无上下文状态（仅清理）
     */
    public static void restore(RequestObservabilityContext snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        begin(snapshot.correlationId, snapshot.requestId);
    }

    public String correlationId() {
        return correlationId;
    }

    public String requestId() {
        return requestId;
    }
}
