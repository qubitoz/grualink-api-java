package mx.grualink.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Framework-independent handling of one delivery: verify the signature over the raw body, parse, deduplicate
 * and dispatch. Mount it on any HTTP server that can give you the raw bytes.
 */
public final class WebhookHandler {
  private final List<String> secrets;
  private final WebhookEventListener onEvent;
  private final DeliveryDeduplicator deduplicator;
  private final long toleranceSeconds;
  private final LongSupplier nowSeconds;

  private WebhookHandler(Builder builder) {
    if (builder.onEvent == null) {
      throw new GrualinkConfigException("onEvent is required.");
    }
    this.secrets = List.copyOf(builder.secrets);
    this.onEvent = builder.onEvent;
    this.deduplicator = builder.deduplicator != null ? builder.deduplicator : new InMemoryDeliveryDeduplicator();
    this.toleranceSeconds = builder.toleranceSeconds;
    this.nowSeconds = builder.nowSeconds != null ? builder.nowSeconds : () -> Instant.now().getEpochSecond();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * @param rawBody the RAW body bytes, exactly as received
   * @param signatureHeader value of {@code X-GruaLink-Signature}, or {@code null} when absent
   * @param deliveryIdHeader value of {@code X-GruaLink-Delivery}, or {@code null} when absent
   */
  public WebhookHandlerResult handle(byte[] rawBody, String signatureHeader, String deliveryIdHeader) {
    SignatureVerificationResult verification = Webhooks.verifyWebhookSignature(
        rawBody, signatureHeader, secrets, nowSeconds.getAsLong(), toleranceSeconds);
    if (!verification.valid()) {
      return new WebhookHandlerResult(401, false, false, "invalid_signature", verification.reason().wireName());
    }

    WebhookEvent event;
    try {
      // Only now, with the signature verified, is the body parsed.
      String text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(rawBody))
          .toString();
      JsonNode node = Json.MAPPER.readTree(text);
      if (node == null || !node.isObject() || !node.path("id").isTextual() || !node.path("type").isTextual()) {
        return invalidPayload();
      }
      event = Json.MAPPER.treeToValue(node, WebhookEvent.class);
    } catch (IOException | RuntimeException e) {
      return invalidPayload();
    }

    // The header is not covered by the signature; the id inside the signed body is. They must agree.
    if (deliveryIdHeader == null || !deliveryIdHeader.equals(event.id())) {
      return new WebhookHandlerResult(400, false, false, "delivery_id_mismatch", "delivery_id_mismatch");
    }

    if (!deduplicator.claim(deliveryIdHeader)) {
      return new WebhookHandlerResult(200, true, true, null, "duplicate");
    }
    try {
      onEvent.onEvent(event);
    } catch (Exception e) {
      deduplicator.release(deliveryIdHeader);
      return new WebhookHandlerResult(500, false, false, "handler_failed", "handler_failed");
    }
    return new WebhookHandlerResult(200, true, false, null, "ok");
  }

  private static WebhookHandlerResult invalidPayload() {
    return new WebhookHandlerResult(400, false, false, "invalid_payload", "invalid_payload");
  }

  /** Configuration of a {@link WebhookHandler}. A secret and {@link #onEvent} are required. */
  public static final class Builder {
    private final List<String> secrets = new ArrayList<>();
    private WebhookEventListener onEvent;
    private DeliveryDeduplicator deduplicator;
    private long toleranceSeconds = Webhooks.DEFAULT_TOLERANCE_SECONDS;
    private LongSupplier nowSeconds;

    private Builder() {}

    /** Signing secret ({@code whsec_...}), whole. */
    public Builder secret(String secret) {
      return secrets(secret == null ? List.of() : List.of(secret));
    }

    /** Current and previous secret during your rotation. Blank values are ignored. */
    public Builder secrets(List<String> secrets) {
      this.secrets.clear();
      if (secrets != null) {
        for (String secret : secrets) {
          if (secret != null && !secret.isEmpty()) {
            this.secrets.add(secret);
          }
        }
      }
      return this;
    }

    /** Called once per verified, non-duplicate delivery. */
    public Builder onEvent(WebhookEventListener onEvent) {
      this.onEvent = onEvent;
      return this;
    }

    /** Defaults to an {@link InMemoryDeliveryDeduplicator}. */
    public Builder deduplicator(DeliveryDeduplicator deduplicator) {
      this.deduplicator = deduplicator;
      return this;
    }

    /** Defaults to 300 seconds. */
    public Builder toleranceSeconds(long toleranceSeconds) {
      this.toleranceSeconds = toleranceSeconds;
      return this;
    }

    /** Clock in seconds, for tests. */
    public Builder nowSeconds(LongSupplier nowSeconds) {
      this.nowSeconds = nowSeconds;
      return this;
    }

    public WebhookHandler build() {
      return new WebhookHandler(this);
    }
  }
}
