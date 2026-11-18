package mx.grualink.api;

import java.util.List;
import java.util.OptionalInt;

/**
 * The API answered with an error. {@link #getCode()} is the stable machine-readable code from
 * {@code { "error": { "code", "message", "details"? } }}; the message may change over time. When the body
 * is not the documented error shape, the code is {@code unexpected_response}.
 */
public class GrualinkApiException extends GrualinkException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final List<ErrorDetail> details;
  private final Integer retryAfterSeconds;

  public GrualinkApiException(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : List.copyOf(details);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  /** HTTP status of the answer. */
  public int getStatus() {
    return status;
  }

  /** Stable error code. Branch on this (and the status), never on the message. */
  public String getCode() {
    return code;
  }

  /** Invalid fields on {@code validation_error} and some {@code invalid_request}; empty otherwise. */
  public List<ErrorDetail> getDetails() {
    return details;
  }

  /** Value of the {@code Retry-After} header in seconds, when present. */
  public OptionalInt getRetryAfterSeconds() {
    return retryAfterSeconds == null ? OptionalInt.empty() : OptionalInt.of(retryAfterSeconds);
  }

  /** Maps an HTTP status to the exception class that represents it. */
  static GrualinkApiException fromStatus(
      int status, String code, String message, List<ErrorDetail> details, Integer retryAfterSeconds) {
    if (status == 401) {
      return new GrualinkAuthenticationException(status, code, message, details, retryAfterSeconds);
    }
    if (status == 403) {
      return new GrualinkPermissionException(status, code, message, details, retryAfterSeconds);
    }
    if (status == 404) {
      return new GrualinkNotFoundException(status, code, message, details, retryAfterSeconds);
    }
    if (status == 409) {
      return new GrualinkIdempotencyConflictException(status, code, message, details, retryAfterSeconds);
    }
    if (status == 422) {
      return new GrualinkValidationException(status, code, message, details, retryAfterSeconds);
    }
    if (status == 429) {
      return new GrualinkRateLimitException(status, code, message, details, retryAfterSeconds);
    }
    if (status >= 500) {
      return new GrualinkServerException(status, code, message, details, retryAfterSeconds);
    }
    if (status == 400 || status == 405 || status == 413 || status == 415) {
      return new GrualinkInvalidRequestException(status, code, message, details, retryAfterSeconds);
    }
    return new GrualinkApiException(status, code, message, details, retryAfterSeconds);
  }
}
