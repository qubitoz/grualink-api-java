package mx.grualink.api.examples;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import mx.grualink.api.CreateServiceRequestResult;
import mx.grualink.api.GrualinkApiException;
import mx.grualink.api.GrualinkClient;
import mx.grualink.api.GrualinkException;
import mx.grualink.api.GrualinkValidationException;
import mx.grualink.api.Health;
import mx.grualink.api.ServiceRequest;
import mx.grualink.api.ServiceRequestInput;

/**
 * End-to-end example: request a tow truck with fictitious data, print the quote and check the status.
 *
 * <pre>
 *   GRUALINK_API_KEY=&lt;your TEST key&gt; ./mvnw -q compile exec:java@request-tow
 * </pre>
 *
 * <p>Use a TEST key (glk_test_...). Test requests never become real services.
 */
public final class RequestTow {
  private RequestTow() {}

  public static void main(String[] args) {
    int exitCode = run();
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int run() {
    String apiKey = System.getenv("GRUALINK_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("Set GRUALINK_API_KEY to your TEST key (it starts with glk_test_). See .env.example.");
      return 1;
    }
    if (!apiKey.strip().startsWith("glk_test_")) {
      System.err.println("This example only runs with a TEST key (glk_test_...).");
      return 1;
    }

    GrualinkClient.Builder builder = GrualinkClient.builder()
        .apiKey(apiKey)
        .onRetry(info -> System.err.printf(
            "Attempt %d failed (%s); retrying in %d ms%n",
            info.attempt(), info.error().getClass().getSimpleName(), info.delayMillis()));
    String baseUrl = System.getenv("GRUALINK_API_BASE_URL");
    if (baseUrl != null && !baseUrl.isBlank()) {
      builder.baseUrl(baseUrl.strip());
    }

    // With a test key, the prefix of external_ref decides the final status: TEST-CONFIRMAR -> confirmed,
    // TEST-EN-CURSO -> in_progress, TEST-TERMINAR -> completed, TEST-RECHAZAR -> rejected,
    // TEST-CANCELAR -> cancelled.
    String externalRef = "TEST-CONFIRMAR-" + UUID.randomUUID().toString().substring(0, 8);
    ServiceRequestInput request = new ServiceRequestInput(
        externalRef,
        new ServiceRequestInput.Contact("Persona de Ejemplo", "+525500000000"),
        new ServiceRequestInput.Vehicle("Nissan", "Versa", 2019, "Gris", "ABC123A", "auto"),
        ServiceRequestInput.Place.of("Av. Juárez 100, Centro, Guadalajara, Jal.", 20.6767, -103.3475),
        ServiceRequestInput.Place.of("Av. Patria 500, Zapopan, Jal.", 20.7214, -103.3918),
        new ServiceRequestInput.Service("No arranca", "vial", "A", null),
        "Datos ficticios de ejemplo.");

    try {
      GrualinkClient client = builder.build();

      Health health = client.health();
      System.out.printf("API status: %s (%s)%n", health.status(), health.version());

      // In your system, store this key next to your own record BEFORE calling, and reuse it if you have to retry.
      String idempotencyKey = UUID.randomUUID().toString();

      CreateServiceRequestResult created = client.createServiceRequest(request, idempotencyKey);
      ServiceRequest serviceRequest = created.serviceRequest();
      System.out.printf(
          "%nCreated %s (%s) with status \"%s\", reference %s%n",
          serviceRequest.id(), serviceRequest.mode(), serviceRequest.status(), serviceRequest.externalRef());
      printQuote(serviceRequest);

      // Sending it again with the same key and the same body creates nothing new.
      CreateServiceRequestResult again = client.createServiceRequest(request, idempotencyKey);
      System.out.printf(
          "%nSent again with the same Idempotency-Key: replayed=%s, same id=%s%n",
          again.replayed(), serviceRequest.id().equals(again.serviceRequest().id()));

      ServiceRequest current = client.getServiceRequest(serviceRequest.id());
      String folio = current.service() != null && current.service().folio() != null
          ? ", folio " + current.service().folio()
          : "";
      System.out.printf("%nStatus now: \"%s\"%s%n", current.status(), folio);

      List<ServiceRequest> byReference = client.findServiceRequestsByExternalRef(externalRef);
      System.out.printf("Requests with reference %s: %d%n", externalRef, byReference.size());
      return 0;
    } catch (GrualinkValidationException e) {
      System.err.println("Invalid fields: " + e.getDetails().stream()
          .map(detail -> detail.field() + " (" + detail.issue() + ")")
          .collect(Collectors.joining(", ")));
    } catch (GrualinkApiException e) {
      System.err.printf("API error %d %s: %s%n", e.getStatus(), e.getCode(), e.getMessage());
    } catch (GrualinkException e) {
      System.err.printf("%s: %s%n", e.getClass().getSimpleName(), e.getMessage());
    }
    return 1;
  }

  private static void printQuote(ServiceRequest serviceRequest) {
    ServiceRequest.Quote quote = serviceRequest.quote();
    ServiceRequest.EstimatedPrice price = quote == null ? null : quote.estimatedPrice();
    if (price == null) {
      String reason = quote == null || quote.unpricedReason() == null ? "unknown reason" : quote.unpricedReason();
      System.out.printf("  No estimated price (%s); the company will quote it.%n", reason);
      return;
    }
    System.out.printf(Locale.ROOT, "  Subtotal:  %.2f %s%n", price.subtotal(), price.currency());
    if (price.taxes() != null) {
      for (ServiceRequest.Tax tax : price.taxes()) {
        String sign = "retention".equals(tax.type()) ? "-" : "+";
        System.out.printf(
            Locale.ROOT, "  %s %s (%s%%): %.2f%n", sign, tax.name(), tax.rate(), tax.amount());
      }
    }
    System.out.printf(
        Locale.ROOT, "  Total:     %.2f %s (estimate, extras not included)%n", price.total(), price.currency());
  }
}
