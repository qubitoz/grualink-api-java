package mx.grualink.api;

import java.util.List;

/** 401 {@code unauthorized}: key missing, invalid, expired or revoked (always the same answer). */
public class GrualinkAuthenticationException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkAuthenticationException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
