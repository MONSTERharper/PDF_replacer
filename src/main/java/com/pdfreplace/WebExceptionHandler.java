package com.pdfreplace;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class WebExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException exception) {
        return json(HttpStatus.BAD_REQUEST, "bad_request", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> invalidState(IllegalStateException exception) {
        return json(HttpStatus.BAD_REQUEST, "invalid_state", exception.getMessage());
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> malformedRequest(Exception exception) {
        return json(HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge() {
        return json(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "The upload is too large.");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(RateLimitExceededException exception) {
        return json(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", exception.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> processingError(IOException exception) {
        return json(HttpStatus.UNPROCESSABLE_ENTITY, "processing_error", exception.getMessage());
    }

    @ExceptionHandler(MailException.class)
    public ResponseEntity<Map<String, Object>> mailFailure(MailException exception) {
        return json(
                HttpStatus.BAD_GATEWAY,
                "mail_error",
                "Unable to deliver inquiry email. Verify SMTP credentials and sender configuration."
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        return json(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error.");
    }

    private static ResponseEntity<Map<String, Object>> json(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
