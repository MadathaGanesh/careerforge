package com.careerforge.core;
import org.springframework.http.HttpStatus;
public class ApiError extends RuntimeException {
  public final HttpStatus status; public final String code;
  public ApiError(HttpStatus status, String code, String message) { super(message); this.status=status; this.code=code; }
  public static ApiError notFound() { return new ApiError(HttpStatus.NOT_FOUND,"NOT_FOUND","Resource not found"); }
  public static ApiError invalid(String message) { return new ApiError(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_INPUT",message); }
  public static ApiError conflict(String message) { return new ApiError(HttpStatus.CONFLICT,"CONFLICT",message); }
}

