package com.github.h3yow.springrequestobservabilityjava.observability;

/**
 * Business exception carrying a stable, client-visible error code.
 */
public class BizException extends RuntimeException {

    private final String errorCode;

    public BizException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
