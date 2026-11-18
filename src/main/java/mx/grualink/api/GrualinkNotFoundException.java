package mx.grualink.api;

import java.util.List;

/** 404 {@code not_found}: it does not exist or does not belong to your key. */
public class GrualinkNotFoundException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkNotFoundException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
