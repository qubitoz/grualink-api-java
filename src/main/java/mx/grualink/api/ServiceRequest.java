package mx.grualink.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * A service request as the API returns it. Fields the API adds later are ignored. Enumerated values
 * ({@code status}, {@code mode}, {@code unpriced_reason}...) are kept as the strings of the contract so
 * that a new value never breaks parsing.
 *
 * @param object always {@code service_request}
 * @param status {@code received}, {@code quoted}, {@code confirmed}, {@code in_progress}, {@code completed},
 *     {@code rejected}, {@code cancelled} or {@code expired}
 * @param mode {@code test} or {@code live}, from the key
 * @param createdAt ISO 8601 in UTC
 * @param resolvedAt ISO 8601 in UTC, or {@code null}
 * @param rejectionReason set when the status is {@code rejected}
 * @param service set once the company registered the service
 * @param assignment live keys only
 * @param timeline live keys only
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceRequest(
    String id,
    String object,
    String status,
    String mode,
    @JsonProperty("external_ref") String externalRef,
    @JsonProperty("created_at") String createdAt,
    @JsonProperty("resolved_at") String resolvedAt,
    @JsonProperty("rejection_reason") String rejectionReason,
    ServiceInfo service,
    Quote quote,
    Assignment assignment,
    Timeline timeline) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ServiceInfo(String folio, String status) {}

  /**
   * @param estimatedPrice {@code null} when the request could not be priced automatically; see
   *     {@code unpricedReason}
   * @param includesExtras always {@code false}: the estimate never includes extras such as tolls
   * @param unpricedReason {@code missing_coordinates}, {@code missing_destination},
   *     {@code no_zones_configured} or {@code missing_vehicle_class}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Quote(
      @JsonProperty("estimated_price") EstimatedPrice estimatedPrice,
      @JsonProperty("includes_extras") Boolean includesExtras,
      @JsonProperty("approximate_distance") Boolean approximateDistance,
      @JsonProperty("unpriced_reason") String unpricedReason,
      String note) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EstimatedPrice(
      String currency,
      BigDecimal subtotal,
      List<Tax> taxes,
      @JsonProperty("tax_total") BigDecimal taxTotal,
      @JsonProperty("retention_total") BigDecimal retentionTotal,
      BigDecimal total) {}

  /**
   * @param type {@code tax} (added) or {@code retention} (subtracted)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Tax(String name, String type, BigDecimal rate, BigDecimal amount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Assignment(@JsonProperty("operator_first_name") String operatorFirstName, Truck truck) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Truck(@JsonProperty("economic_number") String economicNumber, String plate) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Timeline(
      @JsonProperty("on_the_way_at") String onTheWayAt,
      @JsonProperty("on_site_at") String onSiteAt,
      @JsonProperty("transport_started_at") String transportStartedAt,
      @JsonProperty("completed_at") String completedAt) {}
}
