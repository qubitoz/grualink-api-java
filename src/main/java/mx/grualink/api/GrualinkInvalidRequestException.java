package mx.grualink.api;

import java.util.List;

/** 400, 405, 413, 415: the request is malformed. Fix the request; do not retry. */
public class GrualinkInvalidRequestException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkInvalidRequestException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
