package com.dutyscheduler.duty.api;

import org.springframework.http.HttpStatus;

/**
 * A team, invite or member change refused, with the status that says why: 404
 * for something not in the caller's team, 409 for a name taken, 422 for a
 * change that would leave a team with no admin.
 */
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
