package com.athenahealth.dwe.api;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import com.athenahealth.dwe.api.ExecutionDtos.ApiError;
import com.athenahealth.dwe.api.ExecutionDtos.ErrorEnvelope;
import com.athenahealth.dwe.persistence.WorkflowQueries.ResourceNotFoundException;
import com.athenahealth.dwe.service.WorkflowValidationService.InvalidWorkflowException;
import com.athenahealth.dwe.persistence.WorkflowStore.RetryConflictException;

/** Converts exceptions from API requests into consistent HTTP statuses and JSON error responses. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Includes field-level validation problems so the client can correct its workflow. */
    @ExceptionHandler(InvalidWorkflowException.class)
    ResponseEntity<ErrorEnvelope> invalid(InvalidWorkflowException e) {
        return ResponseEntity.badRequest().body(new ErrorEnvelope(new ApiError("INVALID_WORKFLOW", e.getMessage(), e.violations())));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorEnvelope> malformed(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed JSON or invalid request field");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ErrorEnvelope> missing(ResourceNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    /** Retry eligibility is state-dependent; clients receive a stable conflict code rather than a server error. */
    @ExceptionHandler(RetryConflictException.class)
    ResponseEntity<ErrorEnvelope> retryConflict(RetryConflictException e) {
        return error(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }

    /** Preserves Spring's HTTP status and headers, including explicit request validation rejections. */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class, ResponseStatusException.class})
    ResponseEntity<ErrorEnvelope> httpError(Exception exception) {
        ErrorResponse response = (ErrorResponse) exception;
        String code = switch (response.getStatusCode().value()) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INVALID_REQUEST";
        };
        String message = exception instanceof ResponseStatusException rejected ? rejected.getReason()
                : "Request does not match a supported endpoint";
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .body(new ErrorEnvelope(new ApiError(code, message, List.of())));
    }

    /** A lost database response can leave the outcome uncertain; return 503 rather than claim success. */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ErrorEnvelope> unavailable(Exception e) {
        log.warn("Database operation failed", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database operation could not be confirmed");
    }

    /** Logs diagnostic details on the server while returning a generic message to the client. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> unexpected(Exception e) {
        log.error("Unexpected request failure", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server failure");
    }

    private static ResponseEntity<ErrorEnvelope> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorEnvelope(new ApiError(code, message, List.of())));
    }
}
