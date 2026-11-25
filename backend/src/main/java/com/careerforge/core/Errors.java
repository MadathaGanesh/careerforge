package com.careerforge.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class Errors {
    private static final Logger log = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, Object>> api(ApiError e) {
        log.warn("API request rejected code={} httpStatus={}", e.code, e.status.value());
        return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream().map(error -> error.getField()).distinct().toList();
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "code", "INVALID_INPUT", "message", "Check the required fields and supported lengths", "fields", fields));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> bad(Exception e) {
        log.warn("Invalid request exceptionType={}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", "Please check the supplied values"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> large(Exception e) {
        log.warn("Upload size limit exceeded");
        return ResponseEntity.status(413).body(Map.of("code", "FILE_TOO_LARGE", "message", "Maximum file size is 10 MB"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return api(ApiError.notFound());
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<Map<String, Object>> duplicate() {
        return api(ApiError.conflict("A record already exists for these values"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled API exception", e);
        return ResponseEntity.status(500).body(Map.of("code", "INTERNAL_ERROR", "message", "An unexpected error occurred"));
    }
}

