package com.github.h3yow.springrequestobservabilityjava.observability;

/**
 * Uniform error payload returned to clients. Never leaks internal details.
 */
public record ApiError(String code, String message, String correlationId) {
}
