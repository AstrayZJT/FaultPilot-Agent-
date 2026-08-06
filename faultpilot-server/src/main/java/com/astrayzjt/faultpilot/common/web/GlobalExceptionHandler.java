package com.astrayzjt.faultpilot.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error("Unhandled request failure traceId={} method={} path={} type={}",
                MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY), request.getMethod(), request.getRequestURI(), exception.getClass().getName());
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.error("Unhandled request root cause traceId={} type={} message={}",
                MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY), root.getClass().getName(), safeMessage(root));
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error", request, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleMissingResource(
            NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request, Map.of());
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "";
        }
        String sanitized = message.replaceAll("(?i)(password|authorization|api[-_ ]?key)\\s*[:=]\\s*[^,; ]+", "$1=[redacted]");
        return sanitized.substring(0, Math.min(400, sanitized.length()));
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> details) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                details);
        return ResponseEntity.status(status).body(body);
    }
}
