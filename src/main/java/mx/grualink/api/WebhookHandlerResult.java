package mx.grualink.api;

/**
 * What to answer to one delivery.
 *
 * @param status HTTP status to answer with
 * @param received {@code received} in the answer body
 * @param duplicate {@code true} when the delivery had already been processed
 * @param error generic error for the answer body, or {@code null}
 * @param reason internal detail for your logs; never send it back to the caller. One of the signature reasons
 *     ({@code ok}, {@code missing_secret}, {@code missing_header}, {@code malformed_header},
 *     {@code timestamp_out_of_tolerance}, {@code signature_mismatch}) or {@code invalid_payload},
 *     {@code delivery_id_mismatch}, {@code duplicate}, {@code handler_failed}
 */
public record WebhookHandlerResult(int status, boolean received, boolean duplicate, String error, String reason) {

  /** JSON body to answer with, for example {@code {"received":true}}. */
  public String bodyJson() {
    StringBuilder json = new StringBuilder("{\"received\":").append(received);
    if (duplicate) {
      json.append(",\"duplicate\":true");
    }
    if (error != null) {
      // Only fixed identifiers reach this point, so no escaping is needed.
      json.append(",\"error\":\"").append(error).append('"');
    }
    return json.append('}').toString();
  }
}
