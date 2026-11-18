package mx.grualink.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Receives GRUALINK webhooks on the HTTP server of the JDK ({@code com.sun.net.httpserver}). No framework needed.
 *
 * <pre>{@code
 * HttpServer server = HttpServer.create(new InetSocketAddress(8787), 0);
 * server.createContext("/", new WebhookHttpHandler(handler, "/webhooks/grualink", null));
 * server.start();
 * }</pre>
 */
public final class WebhookHttpHandler implements HttpHandler {
  /** Bytes read and thrown away from an oversized request so the client can still read the answer. */
  private static final long MAX_DISCARDED_BYTES = 1024 * 1024;

  private final WebhookHandler handler;
  private final String path;
  private final BiConsumer<WebhookHandlerResult, HttpExchange> onResult;

  /**
   * @param handler verification, deduplication and dispatch
   * @param path only this path is served; {@code null} for any path
   * @param onResult called after each delivery with the outcome, for your logs; may be {@code null}. The body
   *     and the secret are never passed.
   */
  public WebhookHttpHandler(
      WebhookHandler handler, String path, BiConsumer<WebhookHandlerResult, HttpExchange> onResult) {
    if (handler == null) {
      throw new GrualinkConfigException("handler is required.");
    }
    this.handler = handler;
    this.path = path;
    this.onResult = onResult;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    boolean answered = false;
    try {
      if (path != null && !path.equals(exchange.getRequestURI().getPath())) {
        answered = true;
        send(exchange, 404, "{\"received\":false,\"error\":\"not_found\"}");
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "POST");
        answered = true;
        send(exchange, 405, "{\"received\":false,\"error\":\"method_not_allowed\"}");
        return;
      }
      byte[] rawBody = readRawBody(exchange, Webhooks.MAX_WEBHOOK_BODY_BYTES);
      if (rawBody == null) {
        exchange.getResponseHeaders().set("Connection", "close");
        answered = true;
        send(exchange, 413, "{\"received\":false,\"error\":\"payload_too_large\"}");
        return;
      }
      WebhookHandlerResult result = handler.handle(
          rawBody,
          exchange.getRequestHeaders().getFirst(Webhooks.SIGNATURE_HEADER),
          exchange.getRequestHeaders().getFirst(Webhooks.DELIVERY_HEADER));
      answered = true;
      send(exchange, result.status(), result.bodyJson());
      if (onResult != null) {
        onResult.accept(result, exchange);
      }
    } catch (IOException | RuntimeException e) {
      if (!answered) {
        try {
          send(exchange, 500, "{\"received\":false,\"error\":\"internal_error\"}");
        } catch (IOException | RuntimeException ignored) {
          // The connection is gone; there is nobody to answer.
        }
      }
    } finally {
      exchange.close();
    }
  }

  /**
   * Reads the raw body with a hard cap, without ever buffering more than {@code limit} bytes.
   *
   * @return the bytes, or {@code null} when the body is larger than {@code limit}
   */
  public static byte[] readRawBody(HttpExchange exchange, int limit) throws IOException {
    InputStream in = exchange.getRequestBody();
    boolean tooLarge = false;
    String declared = exchange.getRequestHeaders().getFirst("Content-Length");
    if (declared != null) {
      try {
        tooLarge = Long.parseLong(declared.strip()) > limit;
      } catch (NumberFormatException e) {
        // Not a number: the cap below still applies.
      }
    }
    byte[] body = null;
    if (!tooLarge) {
      body = in.readNBytes(limit + 1);
      tooLarge = body.length > limit;
    }
    if (!tooLarge) {
      return body;
    }
    byte[] scratch = new byte[8192];
    long remaining = MAX_DISCARDED_BYTES;
    while (remaining > 0) {
      int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
      if (read < 0) {
        break;
      }
      remaining -= read;
    }
    return null;
  }

  private static void send(HttpExchange exchange, int status, String json) throws IOException {
    byte[] payload = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }
}
