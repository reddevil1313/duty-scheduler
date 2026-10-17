package com.dutyscheduler.duty.api;

import org.springframework.http.HttpStatus;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns the exceptions this API can actually produce into RFC 9457 problem
 * responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ScheduleController.UnsolvableException.class)
    public ProblemDetail unsolvable(ScheduleController.UnsolvableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problem.setTitle("This sheet cannot be covered");
        return problem;
    }

    @ExceptionHandler(TrooperController.RosterException.class)
    public ProblemDetail roster(TrooperController.RosterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle("That roster change cannot be made");
        return problem;
    }

    @ExceptionHandler(AccountException.class)
    public ProblemDetail account(AccountException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle("That account change cannot be made");
        return problem;
    }

    /**
     * A request body that fails its {@code @Valid} checks, with the messages
     * written on the request types as the detail.
     *
     * <p>
     * Left to Spring, this is answered from Boot's {@code /error} page: a body
     * that is not a problem document and carries none of those messages, so
     * the client could only say "Request failed (400)". And for a caller with
     * no account — someone accepting an invite — reaching that page needed one,
     * so a short password came back as a 401 that read "Your session has
     * expired".
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(" "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                detail.isEmpty() ? "Some of the fields are missing or not right." : detail);
        problem.setTitle("That request does not make sense");
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
