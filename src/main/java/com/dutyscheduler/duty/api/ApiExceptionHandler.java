package com.dutyscheduler.duty.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the exceptions this API can actually produce into RFC 9457 problem
 * responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ScheduleController.UnsolvableException.class)
    public ProblemDetail unsolvable(ScheduleController.UnsolvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("This sheet cannot be covered");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badInput(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("That request does not make sense");
        return problem;
    }

    /**
     * No handler for authorization failures on purpose. Spring Security turns an
     * {@code AccessDeniedException} into a 403 before the response ever reaches a
     * controller advice, and catching it here would only risk turning a refusal
     * into a 200 with an error body.
     */
}
