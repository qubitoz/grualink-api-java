package mx.grualink.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of a webhook delivery.
 *
 * @param id same value as the {@code X-GruaLink-Delivery} header and on every retry; use it to deduplicate
 * @param object always {@code event}
 * @param type for example {@code service_request.confirmed} or {@code webhook.test}; ignore the ones you do
 *     not know
 * @param mode {@code test} or {@code live}
 * @param createdAt ISO 8601 in UTC
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEvent(
    String id,
    String object,
    String type,
    String mode,
    @JsonProperty("created_at") String createdAt,
    Data data) {

  /**
   * @param previousStatus previous status on status events; {@code null} on assigned, on_the_way and on_site
   * @param serviceRequest same representation as {@code GET /service-requests/{id}} at that moment
   * @param message only on {@code webhook.test}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      @JsonProperty("previous_status") String previousStatus,
      @JsonProperty("service_request") ServiceRequest serviceRequest,
      String message) {}
}
