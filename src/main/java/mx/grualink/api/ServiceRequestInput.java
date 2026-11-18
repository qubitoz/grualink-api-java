package mx.grualink.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Body of {@code POST /service-requests}. Wire names are snake_case; a {@code null} component is not sent.
 * The API rejects unknown fields with {@code validation_error}.
 *
 * @param externalRef your own reference (claim or case number), up to 64 characters
 * @param contact required
 * @param vehicle optional
 * @param pickup required
 * @param destination optional; without pickup and destination coordinates there is no estimated price
 * @param service optional
 * @param notes optional, up to 1,000 characters and 20 lines
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServiceRequestInput(
    @JsonProperty("external_ref") String externalRef,
    Contact contact,
    Vehicle vehicle,
    Place pickup,
    Place destination,
    Service service,
    String notes) {

  /**
   * @param name up to 120 characters
   * @param phone international format ({@code +52...}); 10 digits are taken as a Mexican number
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Contact(String name, String phone) {}

  /**
   * @param address required, up to 500 characters
   * @param lat latitude; goes together with {@code lng}
   * @param lng longitude; goes together with {@code lat}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Place(String address, Double lat, Double lng) {
    /** A place without coordinates. */
    public static Place of(String address) {
      return new Place(address, null, null);
    }

    /** A place with coordinates. */
    public static Place of(String address, double lat, double lng) {
      return new Place(address, lat, lng);
    }
  }

  /**
   * @param type one of {@code auto}, {@code camioneta}, {@code motocicleta}, {@code camion}, {@code otro}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Vehicle(String make, String model, Integer year, String color, String plate, String type) {}

  /**
   * @param reason up to 200 characters
   * @param category {@code vial} or {@code colision}
   * @param requiredClass {@code A}, {@code B}, {@code C} or {@code D}
   * @param requiredAccessories up to 12 values such as {@code dolly} or {@code winch}; see the contract
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Service(
      String reason,
      String category,
      @JsonProperty("required_class") String requiredClass,
      @JsonProperty("required_accessories") List<String> requiredAccessories) {}
}
