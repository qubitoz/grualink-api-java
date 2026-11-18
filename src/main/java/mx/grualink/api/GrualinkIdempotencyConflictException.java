package mx.grualink.api;

import java.util.List;

/** 409 {@code idempotency_conflict}: the Idempotency-Key was already used with a different body. */
public class GrualinkIdempotencyConflictException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkIdempotencyConflictException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
