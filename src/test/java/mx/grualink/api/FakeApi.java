package mx.grualink.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A local stand-in for the API: records what it receives and answers what the test queued. */
final class FakeApi implements AutoCloseable {
  static final ObjectMapper JSON = new ObjectMapper();

  static final String SERVICE_REQUEST_ID = "11111111-2222-4333-8444-555555555555";

  static final String SERVICE_REQUEST = """
      {
        "id": "11111111-2222-4333-8444-555555555555",
        "object": "service_request",
        "status": "quoted",
        "mode": "test",
        "external_ref": "TEST-CONFIRMAR-0001",
        "created_at": "2026-11-18T10:00:00.000Z",
        "resolved_at": null,
        "rejection_reason": null,
        "service": null,
        "quote": {
          "estimated_price": {
            "currency": "MXN", "subtotal": 864.33,
            "taxes": [{ "name": "IVA", "type": "tax", "rate": 16, "amount": 138.29 }],
            "tax_total": 138.29, "retention_total": 0, "total": 1002.62
          },
          "includes_extras": false, "approximate_distance": true, "unpriced_reason": null,
          "note": "Precio estimado."
        },
        "assignment": null,
        "timeline": null,
        "a_field_added_later": { "ignored": true }
      }
      """;

  /** What the fake received. */
  record Recorded(String method, String path, Headers headers, String body) {
    /** First value of a header, or {@code null}. Names are case-insensitive. */
    String header(String name) {
      return headers.getFirst(name);
    }
  }

  /** Decides the answer to one request. */
  @FunctionalInterface
  interface Responder {
    Reply answer(Recorded request);
  }

  /**
   * One queued answer.
   *
   * @param delayMillis wait before answering (to exercise the timeout)
   * @param destroy close the connection without answering (to exercise network errors)
   */
  record Reply(int status, Map<String, String> headers, String contentType, String body, long delayMillis, boolean destroy)
      implements Responder {

    static Reply json(int status, String body) {
      return new Reply(status, Map.of(), "application/json; charset=utf-8", body, 0, false);
    }

    static Reply text(int status, String body) {
      return new Reply(status, Map.of(), "text/html", body, 0, false);
    }

    static Reply destroyed() {
      return new Reply(0, Map.of(), "text/plain", "", 0, true);
    }

    Reply withHeader(String name, String value) {
      Map<String, String> merged = new LinkedHashMap<>(headers);
      merged.put(name, value);
      return new Reply(status, merged, contentType, body, delayMillis, destroy);
    }

    Reply withDelay(long millis) {
      return new Reply(status, headers, contentType, body, millis, destroy);
    }

    @Override
    public Reply answer(Recorded request) {
      return this;
    }
  }

  final List<Recorded> requests = new CopyOnWriteArrayList<>();

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Deque<Responder> queue = new ArrayDeque<>();

  private FakeApi(int port) throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext("/", exchange -> {
      try {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Recorded recorded = new Recorded(
            exchange.getRequestMethod(), exchange.getRequestURI().toString(), exchange.getRequestHeaders(), body);
        requests.add(recorded);
        Reply reply = next().answer(recorded);
        if (reply.destroy()) {
          return;
        }
        if (reply.delayMillis() > 0) {
          Thread.sleep(reply.delayMillis());
        }
        byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", reply.contentType());
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(reply.status(), payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
          exchange.getResponseBody().write(payload);
        }
      } catch (IOException | InterruptedException | RuntimeException e) {
        // The client went away (timeout tests) or the server is stopping.
      } finally {
        // Closing before any response header was sent drops the connection.
        exchange.close();
      }
    });
    server.setExecutor(executor);
    server.start();
  }

  /** Starts on a free port. */
  static FakeApi start() throws IOException {
    return new FakeApi(0);
  }

  static FakeApi start(int port) throws IOException {
    return new FakeApi(port);
  }

  /**
   * A responder that behaves like the API for the happy path of the examples: health, create (201, then a
   * 200 replay for the same key and the same bytes, 409 for the same key with other bytes), read and find.
   */
  static Responder scripted() {
    String confirmed = SERVICE_REQUEST.replace("\"quoted\"", "\"confirmed\"");
    Map<String, String> bodiesByKey = new ConcurrentHashMap<>();
    return request -> {
      String path = request.path();
      boolean get = request.method().equals("GET");
      if (get && path.equals("/api/v1/health")) {
        return Reply.json(200, "{\"status\":\"ok\",\"version\":\"v1\"}");
      }
      if (request.header("Authorization") == null) {
        return apiError(401, "unauthorized");
      }
      if (request.method().equals("POST") && path.equals("/api/v1/service-requests")) {
        String key = request.header("Idempotency-Key");
        if (key == null) {
          return apiError(400, "idempotency_key_required");
        }
        String previous = bodiesByKey.putIfAbsent(key, request.body());
        if (previous == null) {
          return Reply.json(201, SERVICE_REQUEST);
        }
        return previous.equals(request.body())
            ? Reply.json(200, SERVICE_REQUEST).withHeader("Idempotent-Replayed", "true")
            : apiError(409, "idempotency_conflict");
      }
      if (get && path.equals("/api/v1/service-requests/" + SERVICE_REQUEST_ID)) {
        return Reply.json(200, confirmed);
      }
      if (get && path.startsWith("/api/v1/service-requests?external_ref=")) {
        return Reply.json(200, "{\"object\":\"list\",\"data\":[" + confirmed + "]}");
      }
      return apiError(404, "not_found");
    };
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
  }

  /** Queues answers; the last one repeats once the queue is exhausted. */
  void reply(Responder... responders) {
    synchronized (queue) {
      queue.clear();
      queue.addAll(List.of(responders));
    }
  }

  private Responder next() {
    synchronized (queue) {
      if (queue.isEmpty()) {
        return Reply.text(500, "no reply queued");
      }
      return queue.size() > 1 ? queue.removeFirst() : queue.peekFirst();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  static Reply apiError(int status, String code) {
    return apiError(status, code, "Mensaje de ejemplo.", List.of());
  }

  static Reply apiError(int status, String code, String message, List<ErrorDetail> details) {
    ObjectNode error = JSON.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    if (!details.isEmpty()) {
      ArrayNode list = error.putArray("details");
      for (ErrorDetail detail : details) {
        list.addObject().put("field", detail.field()).put("issue", detail.issue());
      }
    }
    ObjectNode root = JSON.createObjectNode();
    root.set("error", error);
    return Reply.json(status, root.toString());
  }
}
