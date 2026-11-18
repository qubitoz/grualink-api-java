package mx.grualink.api;

import java.util.List;

/** 429 {@code rate_limited} (retried automatically) or {@code too_many_open_requests} (not retried). */
public class GrualinkRateLimitException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkRateLimitException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
