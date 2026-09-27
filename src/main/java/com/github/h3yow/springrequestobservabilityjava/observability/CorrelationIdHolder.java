package com.github.h3yow.springrequestobservabilityjava.observability;

import org.slf4j.MDC;

/**
 * Holds per-request correlation/request IDs on the current thread.
 * <p>
 * Backed by SLF4J {@link MDC} so the IDs automatically appear in log output
 * (via {@code %X{correlationId}} / {@code %X{requestId}} in the log pattern).
 * Always call {@link #clear()} when a thread finishes serving a request so a
 * reused thread never leaks IDs into another request.
 */
public final class CorrelationIdHolder {

    public static final String CORRELATION_ID_KEY = "correlationId";
    public static final String REQUEST_ID_KEY = "requestId";

    private CorrelationIdHolder() {
    }

    public static void set(String correlationId, String requestId) {
        MDC.put(CORRELATION_ID_KEY, correlationId);
        MDC.put(REQUEST_ID_KEY, requestId);
    }

    public static String correlationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }

    public static String requestId() {
        return MDC.get(REQUEST_ID_KEY);
    }

    public static void clear() {
        MDC.remove(CORRELATION_ID_KEY);
        MDC.remove(REQUEST_ID_KEY);
    }
}
