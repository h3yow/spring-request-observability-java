package com.github.h3yow.springrequestobservabilityjava.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to uniform {@link ApiError} responses.
 * <p>
 * The full exception chain — including the original root cause — is logged
 * together with the correlation ID (via MDC), while clients only receive a
 * stable error code, a safe message and the correlation ID they can quote
 * when reporting the problem. Internal exception details are never exposed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> handleBiz(BizException ex) {
        // Business exception: message is safe to show; root cause stays in the log.
        log.warn("business exception code={} message={}", ex.errorCode(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError(ex.errorCode(), ex.getMessage(), CorrelationIdHolder.correlationId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // Unexpected exception: log the whole chain, return a generic message.
        log.error("unhandled exception rootCause={}", rootCauseMessage(ex), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "internal error", CorrelationIdHolder.correlationId()));
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
