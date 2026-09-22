package com.courtpulse.api.http;

import com.courtpulse.query.GameNotFoundException;
import com.courtpulse.query.InvalidCursorException;
import com.courtpulse.api.rules.RuleConflictException;
import com.courtpulse.api.rules.RuleNotFoundException;
import com.courtpulse.api.rules.RuleQuotaExceededException;
import com.courtpulse.api.rules.RuleValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public final class ApiProblemHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiProblemHandler.class);

    @ExceptionHandler(GameNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(GameNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "game_not_found", "Game not found",
                exception.getMessage(), request);
    }

    @ExceptionHandler(RuleNotFoundException.class)
    ResponseEntity<ProblemDetail> ruleNotFound(
            RuleNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "rule_not_found", "Alert rule not found",
                exception.getMessage(), request);
    }

    @ExceptionHandler(RuleConflictException.class)
    ResponseEntity<ProblemDetail> conflict(
            RuleConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "rule_conflict", "Alert rule conflict",
                exception.getMessage(), request);
    }

    @ExceptionHandler(RuleValidationException.class)
    ResponseEntity<ProblemDetail> unprocessable(
            RuleValidationException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_rule", "Invalid alert rule",
                exception.getMessage(), request);
    }

    @ExceptionHandler(RuleQuotaExceededException.class)
    ResponseEntity<ProblemDetail> quota(
            RuleQuotaExceededException exception, HttpServletRequest request) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "rule_quota_exceeded", "Alert rule quota exceeded",
                exception.getMessage(), request);
    }

    @ExceptionHandler({
        InvalidCursorException.class,
        IllegalArgumentException.class,
        ConstraintViolationException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        HandlerMethodValidationException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> badRequest(Exception exception, HttpServletRequest request) {
        String detail = exception instanceof InvalidCursorException
                ? exception.getMessage()
                : "One or more request parameters are invalid.";
        String code = exception instanceof InvalidCursorException ? "invalid_cursor" : "invalid_parameter";
        return problem(HttpStatus.BAD_REQUEST, code, "Invalid request", detail, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed",
                "The HTTP method is not supported for this resource.", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> notAcceptable(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_ACCEPTABLE, "not_acceptable", "Representation not available",
                "The requested response media type is not supported.", request);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable", "Service unavailable",
                "Durable CourtPulse data is temporarily unavailable.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        String correlationId = correlationId(request);
        LOGGER.atError()
                .addKeyValue("correlationId", correlationId)
                .addKeyValue("errorType", exception.getClass().getSimpleName())
                .log("Unexpected API failure");
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Internal server error",
                "The request could not be completed.", request);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://courtpulse.dev/problems/" + code));
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", correlationId(request));
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return value == null ? "unavailable" : value.toString();
    }
}
