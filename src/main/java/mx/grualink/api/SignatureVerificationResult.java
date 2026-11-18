package mx.grualink.api;

/**
 * @param valid whether to accept the delivery
 * @param reason why; {@link SignatureVerificationReason#OK} when valid
 */
public record SignatureVerificationResult(boolean valid, SignatureVerificationReason reason) {}
