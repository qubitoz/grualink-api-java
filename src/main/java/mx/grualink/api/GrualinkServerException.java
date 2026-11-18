package mx.grualink.api;

import java.util.List;

/** 5xx: retried automatically; safe because the same Idempotency-Key is reused. */
public class GrualinkServerException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkServerException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
