package com.example.mockbank.api;

import org.springframework.http.HttpStatus;

/** An expected, user-facing failure. {@code code} is stable for clients; {@code message} is shown as-is. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
