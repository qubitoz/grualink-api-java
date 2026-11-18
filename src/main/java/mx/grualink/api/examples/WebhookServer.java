package mx.grualink.api.examples;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import mx.grualink.api.ServiceRequest;
import mx.grualink.api.WebhookEvent;
import mx.grualink.api.WebhookHandler;
import mx.grualink.api.WebhookHttpHandler;
import mx.grualink.api.Webhooks;

/**
 * Example webhook receiver with the HTTP server of the JDK, no framework.
 *
 * <pre>
 *   GRUALINK_WEBHOOK_SECRET=&lt;your whsec_ secret&gt; ./mvnw -q compile exec:java@webhook-server
 * </pre>
 *
 * <p>Expose it over https (GRUALINK only delivers to https addresses on port 443) and give that address to the
 * towing company. Ask them to send a test event (webhook.test) to check the connection.
 */
public final class WebhookServer {
  private static final String PATH = "/webhooks/grualink";

  /** Stand-in for your own queue or database. Answer fast and do the real work elsewhere. */
  private static final Queue<WebhookEvent> QUEUE = new ConcurrentLinkedQueue<>();

  private WebhookServer() {}

  public static void main(String[] args) throws IOException {
    List<String> secrets = new ArrayList<>();
    for (String name : List.of("GRUALINK_WEBHOOK_SECRET", "GRUALINK_WEBHOOK_SECRET_PREVIOUS")) {
      String value = System.getenv(name);
      if (value != null && !value.isBlank()) {
        secrets.add(value.strip());
      }
    }
    if (secrets.isEmpty()) {
      System.err.println(
          "Set GRUALINK_WEBHOOK_SECRET to your signing secret (it starts with whsec_). See .env.example.");
      System.exit(1);
    }

    WebhookHandler handler = Webhooks.createWebhookHandler(secrets, WebhookServer::enqueue, null);

    int port = port();
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/", new WebhookHttpHandler(handler, PATH, (result, exchange) -> {
      // The reason stays in your logs; the caller only sees a generic answer.
      if (!"ok".equals(result.reason())) {
        String delivery = exchange.getRequestHeaders().getFirst(Webhooks.DELIVERY_HEADER);
        System.err.printf(
            "Delivery %s: %d (%s)%n", delivery == null ? "-" : delivery, result.status(), result.reason());
      }
    }));
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.start();
    System.out.printf("Listening for GRUALINK webhooks on http://localhost:%d%s%n", port, PATH);
  }

  private static void enqueue(WebhookEvent event) {
    QUEUE.add(event);
    WebhookEvent.Data data = event.data();
    ServiceRequest serviceRequest = data == null ? null : data.serviceRequest();
    if (serviceRequest != null) {
      System.out.printf(
          "[%s] %s: request %s (%s) is now \"%s\"%n",
          event.mode(), event.type(), serviceRequest.id(),
          serviceRequest.externalRef() == null ? "no reference" : serviceRequest.externalRef(),
          serviceRequest.status());
    } else {
      String message = data == null || data.message() == null ? "" : data.message();
      System.out.printf("[%s] %s: %s%n", event.mode(), event.type(), message);
    }
  }

  private static int port() {
    String value = System.getenv("PORT");
    if (value != null && !value.isBlank()) {
      try {
        int port = Integer.parseInt(value.strip());
        if (port > 0 && port <= 65535) {
          return port;
        }
      } catch (NumberFormatException e) {
        // Fall through to the default.
      }
    }
    return 8787;
  }
}
