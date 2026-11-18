package mx.grualink.api;

import java.util.List;

/** 422 {@code validation_error}: see {@link #getDetails()} for the invalid fields. */
public class GrualinkValidationException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkValidationException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
