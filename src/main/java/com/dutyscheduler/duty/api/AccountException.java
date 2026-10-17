package com.dutyscheduler.duty.api;

import org.springframework.http.HttpStatus;

/** 404 not in the caller's team, 409 name taken, 422 would leave no admin. */
public class AccountException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final HttpStatus status;

    public AccountException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
