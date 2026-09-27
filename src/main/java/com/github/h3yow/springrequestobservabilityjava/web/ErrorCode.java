package com.github.h3yow.springrequestobservabilityjava.web;

/**
 * 对外暴露的业务错误码，与 HTTP 状态一一对应，避免泄漏内部异常细节。
 */
public enum ErrorCode {

    VALIDATION_ERROR(400, "请求参数不合法"),
    NOT_FOUND(404, "资源不存在"),
    BUSINESS_CONFLICT(409, "业务冲突"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
