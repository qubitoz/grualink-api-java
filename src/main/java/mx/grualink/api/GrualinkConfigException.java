package mx.grualink.api;

/** The client was configured or called with an invalid argument. Nothing was sent. */
public class GrualinkConfigException extends GrualinkException {
  private static final long serialVersionUID = 1L;

  public GrualinkConfigException(String message) {
    super(message);
  }
}
