package com.github.h3yow.springrequestobservabilityjava.web;

import com.github.h3yow.springrequestobservabilityjava.observability.RequestObservabilityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

/**
 * 统一异常处理：
 * <ul>
 *     <li>业务异常：warn 级别记录完整异常链（保留最原始 cause），对外返回约定的 4xx；</li>
 *     <li>其他未处理异常：error 级别记录完整异常链，对外只返回统一的 500 + 通用文案，
 *     客户端可凭响应里的关联 ID 到服务端日志反查细节；</li>
 *     <li>Spring 标准异常（如无处理器 404）保留原始状态码，套同一响应结构；</li>
 *     <li>日志与响应体都带关联 ID / 请求 ID，与正常请求日志串成一条链。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        // 把 ex 作为最后一个参数交给 SLF4J：含 cause 链的完整堆栈都会落盘，原始失败原因不丢。
        log.warn("业务异常: errorCode={}, message={}", ex.errorCode().name(), ex.getMessage(), ex);
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(toApiError(ex.errorCode(), ex.getMessage(), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未处理异常: type={}", ex.getClass().getName(), ex);
        // 对外只给固定文案，绝不透传异常类名/message/堆栈等内部细节。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(toApiError(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), request));
    }

    /**
     * 兜底 Spring MVC 自身抛出的标准异常（NoResourceFoundException 404、参数校验 400 等）：
     * 保留框架解析出的状态码，套用统一错误结构。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        ErrorCode errorCode = mapStatus(statusCode.value());
        log.warn("请求被框架拒绝: status={}, type={}", statusCode.value(), ex.getClass().getName(), ex);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ApiError apiError = new ApiError(
                errorCode.name(),
                errorCode.defaultMessage(),
                currentCorrelationId(),
                currentRequestId(),
                Instant.now().toString(),
                requestPath(request));
        return new ResponseEntity<>(apiError, headers, status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ErrorCode mapStatus(int status) {
        if (status == 404) {
            return ErrorCode.NOT_FOUND;
        }
        if (status == 409) {
            return ErrorCode.BUSINESS_CONFLICT;
        }
        if (status >= 400 && status < 500) {
            return ErrorCode.VALIDATION_ERROR;
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private ApiError toApiError(ErrorCode errorCode, String message, HttpServletRequest request) {
        return new ApiError(
                errorCode.name(),
                message,
                currentCorrelationId(),
                currentRequestId(),
                Instant.now().toString(),
                request.getRequestURI());
    }

    private String requestPath(WebRequest request) {
        // WebRequest 不直接暴露 URI，这里给固定描述，具体路径以请求日志的关联 ID 反查。
        return request.getDescription(false).replace("uri=", "");
    }

    private String currentCorrelationId() {
        RequestObservabilityContext ctx = RequestObservabilityContext.current();
        return ctx == null ? null : ctx.correlationId();
    }

    private String currentRequestId() {
        RequestObservabilityContext ctx = RequestObservabilityContext.current();
        return ctx == null ? null : ctx.requestId();
    }
}
