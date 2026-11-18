package mx.grualink.api;

/** Base class of every exception thrown by this library. */
public class GrualinkException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private int attempts;

  public GrualinkException(String message) {
    super(message);
  }

  /** Number of HTTP attempts made before giving up (0 when no request was sent). */
  public int getAttempts() {
    return attempts;
  }

  void setAttempts(int attempts) {
    this.attempts = attempts;
  }
}
