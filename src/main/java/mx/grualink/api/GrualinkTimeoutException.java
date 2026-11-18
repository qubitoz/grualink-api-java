package mx.grualink.api;

/** No response within the configured timeout. Retried automatically. */
public class GrualinkTimeoutException extends GrualinkException {
  private static final long serialVersionUID = 1L;

  public GrualinkTimeoutException(String message) {
    super(message);
  }
}
