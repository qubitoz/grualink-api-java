package mx.grualink.api;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signature of GRUALINK webhooks: verification for your receiver and signing for your local tests. */
public final class Webhooks {
  public static final String SIGNATURE_HEADER = "x-grualink-signature";
  public static final String DELIVERY_HEADER = "x-grualink-delivery";
  public static final String EVENT_HEADER = "x-grualink-event";
  public static final String TIMESTAMP_HEADER = "x-grualink-timestamp";
  public static final String MODE_HEADER = "x-grualink-mode";

  /** Deliveries whose signed timestamp differs more than this from the current time are rejected. */
  public static final long DEFAULT_TOLERANCE_SECONDS = 300;
  /** A delivery body is never larger than 64 KB. */
  public static final int MAX_WEBHOOK_BODY_BYTES = 64 * 1024;

  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,12}");
  private static final Pattern SIGNATURE_V1 = Pattern.compile("[0-9a-f]{64}");

  private Webhooks() {}

  /** HMAC-SHA256, lowercase hex, of {@code <timestamp>.<raw body>} keyed with the secret exactly as issued. */
  public static String computeWebhookSignature(String secret, String timestamp, byte[] rawBody) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      mac.update(rawBody);
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }

  /** Same as {@link #computeWebhookSignature(String, String, byte[])} with the timestamp in seconds. */
  public static String computeWebhookSignature(String secret, long timestamp, byte[] rawBody) {
    return computeWebhookSignature(secret, Long.toString(timestamp), rawBody);
  }

  /** Builds the header the way GRUALINK sends it. Useful to test your own receiver locally. */
  public static String buildSignatureHeader(List<String> secrets, long timestamp, byte[] rawBody) {
    StringBuilder header = new StringBuilder("t=").append(timestamp);
    for (String secret : secrets) {
      header.append(",v1=").append(computeWebhookSignature(secret, timestamp, rawBody));
    }
    return header.toString();
  }

  /** Verifies with the system clock and the default tolerance of 300 seconds. */
  public static SignatureVerificationResult verifyWebhookSignature(
      byte[] rawBody, String signatureHeader, String secret) {
    return verifyWebhookSignature(
        rawBody, signatureHeader, secret, Instant.now().getEpochSecond(), DEFAULT_TOLERANCE_SECONDS);
  }

  /** Verifies against one secret. See {@link #verifyWebhookSignature(byte[], String, List, long, long)}. */
  public static SignatureVerificationResult verifyWebhookSignature(
      byte[] rawBody, String signatureHeader, String secret, long nowSeconds, long toleranceSeconds) {
    List<String> secrets = secret == null ? List.of() : Collections.singletonList(secret);
    return verifyWebhookSignature(rawBody, signatureHeader, secrets, nowSeconds, toleranceSeconds);
  }

  /**
   * Verifies {@code X-GruaLink-Signature}. Pure and total: it never throws and has no side effects.
   *
   * <p>Order of checks (shared by every language; the contract vectors pin it):
   *
   * <ol>
   *   <li>no secret: {@code missing_secret}
   *   <li>header absent or blank: {@code missing_header}
   *   <li>no {@code t} of 1-12 digits, or no well-formed {@code v1}: {@code malformed_header}
   *   <li>{@code |now - t|} greater than the tolerance: {@code timestamp_out_of_tolerance}
   *   <li>no {@code v1} equals the expected signature: {@code signature_mismatch}
   * </ol>
   *
   * @param rawBody the RAW request body exactly as received, before any JSON parsing
   * @param signatureHeader value of the {@code X-GruaLink-Signature} header, or {@code null}
   * @param secrets your signing secret, whole (with its {@code whsec_} prefix); two during your own rotation
   * @param nowSeconds current time in seconds since 1970 (UTC)
   * @param toleranceSeconds 300 by default; the limit is inclusive
   */
  public static SignatureVerificationResult verifyWebhookSignature(
      byte[] rawBody, String signatureHeader, List<String> secrets, long nowSeconds, long toleranceSeconds) {
    try {
      List<String> usable = new ArrayList<>();
      if (secrets != null) {
        for (String secret : secrets) {
          if (secret != null && !secret.isEmpty()) {
            usable.add(secret);
          }
        }
      }
      if (usable.isEmpty()) {
        return fail(SignatureVerificationReason.MISSING_SECRET);
      }

      if (signatureHeader == null || signatureHeader.isBlank()) {
        return fail(SignatureVerificationReason.MISSING_HEADER);
      }

      String timestampText = null;
      List<String> candidates = new ArrayList<>();
      for (String part : signatureHeader.split(",", -1)) {
        String item = part.strip();
        int eq = item.indexOf('=');
        if (eq < 0) {
          continue;
        }
        String name = item.substring(0, eq);
        String value = item.substring(eq + 1);
        if (name.equals("t") && timestampText == null) {
          timestampText = value;
        } else if (name.equals("v1") && SIGNATURE_V1.matcher(value).matches()) {
          // Anything that is not exactly 64 lowercase hex characters is ignored, as are unknown schemes.
          candidates.add(value);
        }
      }
      if (timestampText == null || !TIMESTAMP.matcher(timestampText).matches()) {
        return fail(SignatureVerificationReason.MALFORMED_HEADER);
      }
      if (candidates.isEmpty()) {
        return fail(SignatureVerificationReason.MALFORMED_HEADER);
      }

      if (Math.abs(nowSeconds - Long.parseLong(timestampText)) > toleranceSeconds) {
        return fail(SignatureVerificationReason.TIMESTAMP_OUT_OF_TOLERANCE);
      }

      boolean matched = false;
      // Every pair is compared in constant time and the loop never exits early.
      for (String secret : usable) {
        byte[] expected = computeWebhookSignature(secret, timestampText, rawBody).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : candidates) {
          if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII), expected)) {
            matched = true;
          }
        }
      }
      return matched
          ? new SignatureVerificationResult(true, SignatureVerificationReason.OK)
          : fail(SignatureVerificationReason.SIGNATURE_MISMATCH);
    } catch (RuntimeException e) {
      return fail(SignatureVerificationReason.MALFORMED_HEADER);
    }
  }

  /**
   * The receiver of the contract: verifies, deduplicates and dispatches one delivery. Equivalent to
   * {@link WebhookHandler#builder()} with these three values.
   *
   * @param secrets signing secret ({@code whsec_...}), or current and previous during your rotation
   * @param onEvent called once per verified, non-duplicate delivery
   * @param deduplicator {@code null} for an {@link InMemoryDeliveryDeduplicator}
   */
  public static WebhookHandler createWebhookHandler(
      List<String> secrets, WebhookEventListener onEvent, DeliveryDeduplicator deduplicator) {
    return WebhookHandler.builder().secrets(secrets).onEvent(onEvent).deduplicator(deduplicator).build();
  }

  private static SignatureVerificationResult fail(SignatureVerificationReason reason) {
    return new SignatureVerificationResult(false, reason);
  }
}
