package mx.grualink.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class WebhookReceiverTest {
  private static final String SECRET = "whsec_TEST_ONLY_FAKE_SECRET_0001";
  private static final String PREVIOUS = "whsec_TEST_ONLY_FAKE_SECRET_0000";
  private static final long NOW = 1795000000L;
  private static final String PATH = "/webhooks/grualink";

  private static final List<WebhookEvent> RECEIVED = Collections.synchronizedList(new ArrayList<>());
  private static final List<WebhookHandlerResult> RESULTS = Collections.synchronizedList(new ArrayList<>());
  private static final AtomicBoolean FAIL_NEXT = new AtomicBoolean();
  private static final AtomicLong CLOCK = new AtomicLong(NOW);
  private static final AtomicInteger COUNTER = new AtomicInteger();
  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  private static HttpServer server;
  private static ExecutorService executor;
  private static String url;

  /** One delivery as GRUALINK would send it. */
  private record Delivery(String id, String type, String body) {}

  private record Answer(int status, String body, String allow) {}

  @BeforeAll
  static void startServer() throws IOException {
    WebhookHandler handler = WebhookHandler.builder()
        .secrets(List.of(SECRET))
        .nowSeconds(CLOCK::get)
        .onEvent(event -> {
          if (FAIL_NEXT.getAndSet(false)) {
            throw new IllegalStateException("database is down");
          }
          RECEIVED.add(event);
        })
        .build();
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", new WebhookHttpHandler(handler, PATH, (result, exchange) -> RESULTS.add(result)));
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
    executor.shutdownNow();
  }

  @BeforeEach
  void reset() {
    RECEIVED.clear();
    RESULTS.clear();
    FAIL_NEXT.set(false);
    CLOCK.set(NOW);
  }

  private static Delivery delivery() {
    return delivery(String.format("bbbbbbbb-0000-4000-8000-%012d", COUNTER.incrementAndGet()));
  }

  private static Delivery delivery(String id) {
    String type = "service_request.confirmed";
    // Deliberately not what a JSON library would write, and with non-ASCII text.
    String body = "{\"id\": \"" + id + "\", \"data\": {\"previous_status\": \"quoted\", \"service_request\": "
        + "{\"id\": \"11111111-2222-4333-8444-555555555555\", \"status\": \"confirmed\", "
        + "\"external_ref\": \"TEST-CONFIRMAR-ÑU\"}}, \"mode\": \"test\", \"type\": \"" + type + "\", "
        + "\"object\": \"event\", \"created_at\": \"2026-11-18T10:05:01.000Z\"}";
    return new Delivery(id, type, body);
  }

  private static Map<String, String> signedHeaders(Delivery delivery, List<String> secrets, long timestamp) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json; charset=utf-8");
    headers.put("X-GruaLink-Event", delivery.type());
    headers.put("X-GruaLink-Delivery", delivery.id());
    headers.put("X-GruaLink-Timestamp", Long.toString(timestamp));
    headers.put("X-GruaLink-Mode", "test");
    headers.put(
        "X-GruaLink-Signature",
        Webhooks.buildSignatureHeader(secrets, timestamp, delivery.body().getBytes(StandardCharsets.UTF_8)));
    return headers;
  }

  private static Map<String, String> signedHeaders(Delivery delivery) {
    return signedHeaders(delivery, List.of(SECRET), NOW);
  }

  private static Answer post(String body, Map<String, String> headers) throws Exception {
    return send("POST", url, HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8)), headers);
  }

  private static Answer send(
      String method, String target, HttpRequest.BodyPublisher body, Map<String, String> headers) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
        .timeout(Duration.ofSeconds(20))
        .method(method, body);
    headers.forEach(request::header);
    HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(response.statusCode(), response.body(), response.headers().firstValue("Allow").orElse(null));
  }

  private static List<String> reasons() {
    List<String> reasons = new ArrayList<>();
    synchronized (RESULTS) {
      for (WebhookHandlerResult result : RESULTS) {
        reasons.add(result.reason());
      }
    }
    return reasons;
  }

  @Test
  void aSignedDeliveryIsAcceptedAndHandedToOnEvent() throws Exception {
    Delivery delivery = delivery();
    Answer answer = post(delivery.body(), signedHeaders(delivery));
    assertEquals(200, answer.status());
    assertEquals("{\"received\":true}", answer.body());
    assertEquals(1, RECEIVED.size());
    WebhookEvent event = RECEIVED.get(0);
    assertEquals(delivery.id(), event.id());
    assertEquals("service_request.confirmed", event.type());
    assertEquals("test", event.mode());
    assertEquals("2026-11-18T10:05:01.000Z", event.createdAt());
    assertEquals("quoted", event.data().previousStatus());
    assertEquals("confirmed", event.data().serviceRequest().status());
    assertEquals("TEST-CONFIRMAR-ÑU", event.data().serviceRequest().externalRef());
    assertEquals(List.of("ok"), reasons());
  }

  @Test
  void aRetryOfTheSameDeliveryAnswers200ButIsProcessedOnce() throws Exception {
    Delivery delivery = delivery();
    assertEquals(200, post(delivery.body(), signedHeaders(delivery)).status());
    Answer again = post(delivery.body(), signedHeaders(delivery, List.of(SECRET), NOW + 60));
    assertEquals(200, again.status());
    assertEquals("{\"received\":true,\"duplicate\":true}", again.body());
    assertEquals(1, RECEIVED.size());
    assertEquals(List.of("ok", "duplicate"), reasons());
  }

  @Test
  void whenOnEventFailsTheAnswerIs500AndTheRetryIsProcessed() throws Exception {
    Delivery delivery = delivery();
    FAIL_NEXT.set(true);
    Answer first = post(delivery.body(), signedHeaders(delivery));
    assertEquals(500, first.status());
    assertEquals("{\"received\":false,\"error\":\"handler_failed\"}", first.body());
    assertEquals(0, RECEIVED.size());
    Answer retry = post(delivery.body(), signedHeaders(delivery));
    assertEquals(200, retry.status());
    assertEquals("{\"received\":true}", retry.body());
    assertEquals(1, RECEIVED.size());
  }

  @Test
  void duringARotationADeliverySignedWithBothSecretsIsAccepted() throws Exception {
    Delivery delivery = delivery();
    List<String> newAndCurrent = List.of("whsec_TEST_ONLY_FAKE_SECRET_NEXT", SECRET);
    assertEquals(200, post(delivery.body(), signedHeaders(delivery, newAndCurrent, NOW)).status());
    Delivery old = delivery();
    assertEquals(401, post(old.body(), signedHeaders(old, List.of(PREVIOUS), NOW)).status());
  }

  @Test
  void aTamperedBodyAMissingSignatureAndAStaleTimestampAreRejectedWith401() throws Exception {
    Delivery delivery = delivery();
    Answer tampered = post(delivery.body().replace("confirmed", "completed"), signedHeaders(delivery));
    assertEquals(401, tampered.status());
    // No detail about why: the reason only goes to the logs of the receiver.
    assertEquals("{\"received\":false,\"error\":\"invalid_signature\"}", tampered.body());

    Map<String, String> unsigned = signedHeaders(delivery);
    unsigned.remove("X-GruaLink-Signature");
    assertEquals(401, post(delivery.body(), unsigned).status());

    CLOCK.set(NOW + 301);
    Answer stale = post(delivery.body(), signedHeaders(delivery));
    assertEquals(401, stale.status());
    assertEquals(tampered.body(), stale.body());

    assertEquals(List.of("signature_mismatch", "missing_header", "timestamp_out_of_tolerance"), reasons());
    assertEquals(0, RECEIVED.size());
  }

  @Test
  void theBodyParsedAndSerializedAgainDoesNotVerify() throws Exception {
    Delivery delivery = delivery();
    String reserialized = FakeApi.JSON.writeValueAsString(FakeApi.JSON.readTree(delivery.body()));
    assertNotEquals(delivery.body(), reserialized);
    assertEquals(401, post(reserialized, signedHeaders(delivery)).status());
  }

  @Test
  void aDeliveryHeaderThatDisagreesWithTheSignedIdIsRejected() throws Exception {
    Delivery delivery = delivery();
    Map<String, String> headers = signedHeaders(delivery);
    headers.put("X-GruaLink-Delivery", "cccccccc-0000-4000-8000-000000000000");
    assertEquals(400, post(delivery.body(), headers).status());
    headers.remove("X-GruaLink-Delivery");
    assertEquals(400, post(delivery.body(), headers).status());
    assertEquals(List.of("delivery_id_mismatch", "delivery_id_mismatch"), reasons());
    assertEquals(0, RECEIVED.size());
  }

  @Test
  void aSignedBodyThatIsNotAnEventIsA400() throws Exception {
    for (String body : List.of("[1, 2, 3]", "{\"id\": 7, \"type\": \"webhook.test\"}", "not json")) {
      Answer answer = post(body, signedHeaders(new Delivery("x", "webhook.test", body)));
      assertEquals(400, answer.status());
      assertEquals("{\"received\":false,\"error\":\"invalid_payload\"}", answer.body());
    }
    assertEquals(List.of("invalid_payload", "invalid_payload", "invalid_payload"), reasons());
  }

  @Test
  void otherMethodsOtherPathsAndOversizedBodiesAreRefused() throws Exception {
    Answer get = send("GET", url, HttpRequest.BodyPublishers.noBody(), Map.of());
    assertEquals(405, get.status());
    assertEquals("POST", get.allow());

    Answer elsewhere = send(
        "POST", url.replace(PATH, "/other"),
        HttpRequest.BodyPublishers.ofString("{}"), Map.of("Content-Type", "application/json"));
    assertEquals(404, elsewhere.status());

    byte[] big = new byte[Webhooks.MAX_WEBHOOK_BODY_BYTES + 1];
    Arrays.fill(big, (byte) 0x20);
    Answer declared = send(
        "POST", url, HttpRequest.BodyPublishers.ofByteArray(big), Map.of("Content-Type", "application/json"));
    assertEquals(413, declared.status());

    // Without Content-Length (chunked) the cap is applied while reading.
    Answer streamed = send(
        "POST", url,
        HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big)),
        Map.of("Content-Type", "application/json"));
    assertEquals(413, streamed.status());

    assertEquals(0, RECEIVED.size());
    assertTrue(RESULTS.isEmpty());
  }

  @Test
  void aBodyOfExactlyTheLimitIsReadWhole() throws Exception {
    byte[] padding = new byte[Webhooks.MAX_WEBHOOK_BODY_BYTES - 2];
    Arrays.fill(padding, (byte) 0x20);
    String body = "{" + new String(padding, StandardCharsets.US_ASCII) + "}";
    Answer answer = post(body, signedHeaders(new Delivery("x", "webhook.test", body)));
    // It was read and verified; it is refused only because it is not an event.
    assertEquals(400, answer.status());
    assertEquals(List.of("invalid_payload"), reasons());
  }

  @Test
  void theHandlerWorksWithoutAnyHttpServer() {
    List<WebhookEvent> events = new ArrayList<>();
    WebhookHandler handler = Webhooks.createWebhookHandler(List.of(SECRET), events::add, null);
    Delivery delivery = delivery();
    byte[] rawBody = delivery.body().getBytes(StandardCharsets.UTF_8);
    long now = System.currentTimeMillis() / 1000;
    String signature = Webhooks.buildSignatureHeader(List.of(SECRET), now, rawBody);

    WebhookHandlerResult first = handler.handle(rawBody, signature, delivery.id());
    assertEquals(new WebhookHandlerResult(200, true, false, null, "ok"), first);
    WebhookHandlerResult second = handler.handle(rawBody, signature, delivery.id());
    assertTrue(second.duplicate());
    assertEquals(1, events.size());

    WebhookHandlerResult unsigned = handler.handle(rawBody, null, delivery.id());
    assertEquals(401, unsigned.status());
    assertFalse(unsigned.received());
    assertEquals("missing_header", unsigned.reason());

    WebhookHandler noSecret = WebhookHandler.builder().onEvent(events::add).build();
    assertEquals("missing_secret", noSecret.handle(rawBody, signature, delivery.id()).reason());
  }

  @Test
  void inMemoryDeliveryDeduplicatorClaimReleaseExpiryAndSizeCap() {
    AtomicLong now = new AtomicLong();
    InMemoryDeliveryDeduplicator dedup = new InMemoryDeliveryDeduplicator(Duration.ofMillis(1000), 3, now::get);
    assertTrue(dedup.claim("a"));
    assertFalse(dedup.claim("a"));
    dedup.release("a");
    assertTrue(dedup.claim("a"));

    now.set(1001);
    assertTrue(dedup.claim("a"), "an expired id can be claimed again");

    assertTrue(dedup.claim("b"));
    assertTrue(dedup.claim("c"));
    assertTrue(dedup.claim("d"), "the oldest entry is evicted when the cap is reached");
    assertFalse(dedup.claim("d"));
    assertTrue(dedup.claim("a"), "the evicted id is no longer remembered");
  }
}
