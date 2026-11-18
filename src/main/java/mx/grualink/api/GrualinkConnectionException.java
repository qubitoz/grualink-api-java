package mx.grualink.api;

/** The request never got an HTTP response (DNS, connection reset, TLS...). Retried automatically. */
public class GrualinkConnectionException extends GrualinkException {
  private static final long serialVersionUID = 1L;

  public GrualinkConnectionException(String message) {
    super(message);
  }
}
