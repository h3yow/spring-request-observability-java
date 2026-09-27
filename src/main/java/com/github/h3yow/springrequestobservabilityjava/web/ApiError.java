package com.github.h3yow.springrequestobservabilityjava.web;

/**
 * 统一错误响应体：只包含可对外的错误码、提示与关联信息，不带内部异常细节。
 */
public record ApiError(String code, String message, String correlationId,
                       String requestId, String timestamp, String path) {
}
