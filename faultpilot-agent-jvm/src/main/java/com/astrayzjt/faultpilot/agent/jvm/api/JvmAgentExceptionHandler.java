package com.astrayzjt.faultpilot.agent.jvm.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class JvmAgentExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException failure) {
        String message = failure.getMessage() == null ? "Invalid request" : failure.getMessage();
        HttpStatus status = message.contains("Idempotency key") || message.contains("reused")
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("code", status == HttpStatus.CONFLICT
                ? "A2A_IDEMPOTENCY_CONFLICT" : "INVALID_A2A_REQUEST", "message", message));
    }
}
