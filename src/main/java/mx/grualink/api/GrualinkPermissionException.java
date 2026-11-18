package mx.grualink.api;

import java.util.List;

/** 403: the key is not allowed to do this (scope, customer unavailable, live mode, browser). */
public class GrualinkPermissionException extends GrualinkApiException {
  private static final long serialVersionUID = 1L;

  public GrualinkPermissionException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(status, code, message, details, retryAfterSeconds);
  }
}
