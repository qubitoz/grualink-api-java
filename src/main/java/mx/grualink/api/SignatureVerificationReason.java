package mx.grualink.api;

/** Outcome of a signature verification. The wire names are the ones of the contract, shared by every language. */
public enum SignatureVerificationReason {
  OK("ok"),
  MISSING_SECRET("missing_secret"),
  MISSING_HEADER("missing_header"),
  MALFORMED_HEADER("malformed_header"),
  TIMESTAMP_OUT_OF_TOLERANCE("timestamp_out_of_tolerance"),
  SIGNATURE_MISMATCH("signature_mismatch");

  private final String wireName;

  SignatureVerificationReason(String wireName) {
    this.wireName = wireName;
  }

  /** The reason as written in the contract, for example {@code signature_mismatch}. */
  public String wireName() {
    return wireName;
  }

  @Override
  public String toString() {
    return wireName;
  }
}
