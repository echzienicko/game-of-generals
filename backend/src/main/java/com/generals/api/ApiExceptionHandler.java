package com.generals.api;

import com.generals.domain.IllegalMoveException;
import com.generals.service.GameAccessException;
import com.generals.service.GameNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/** Turns rule violations into clean, actionable JSON instead of stack traces. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorResponse(int status, String error, String message, String timestamp) {
    }

    @ExceptionHandler(GameNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(GameNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(GameAccessException.class)
    public ResponseEntity<ErrorResponse> forbidden(GameAccessException ex) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** A rejected move is the player's mistake, not a server fault. */
    @ExceptionHandler(IllegalMoveException.class)
    public ResponseEntity<ErrorResponse> illegalMove(IllegalMoveException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> conflict(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        log.debug("{} -> {}", status, message);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(),
                        message == null ? status.getReasonPhrase() : message, Instant.now().toString()));
    }
}
