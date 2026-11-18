package mx.grualink.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import mx.grualink.api.FakeApi.Reply;
import mx.grualink.api.FakeApi.Responder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class GrualinkClientTest {
  // Obviously fake: it only has the prefix the client checks.
  private static final String TEST_KEY = "glk_test_FAKE_KEY_FOR_UNIT_TESTS_ONLY";

  private static final ServiceRequestInput INPUT = new ServiceRequestInput(
      "TEST-CONFIRMAR-0001",
      new ServiceRequestInput.Contact("Persona de Ejemplo", "+525500000000"),
      null,
      ServiceRequestInput.Place.of("Calle Ficticia 123, Ciudad Ejemplo", 20.6767, -103.3475),
      ServiceRequestInput.Place.of("Taller Ficticio, Avenida Ejemplo 456", 20.7214, -103.3918),
      new ServiceRequestInput.Service(null, "vial", "A", List.of("dolly")),
      null);

  /** The body the API expects for {@link #INPUT}: wire names in snake_case, absent fields not sent. */
  private static final String INPUT_JSON = """
      {
        "external_ref": "TEST-CONFIRMAR-0001",
        "contact": { "name": "Persona de Ejemplo", "phone": "+525500000000" },
        "pickup": { "address": "Calle Ficticia 123, Ciudad Ejemplo", "lat": 20.6767, "lng": -103.3475 },
        "destination": { "address": "Taller Ficticio, Avenida Ejemplo 456", "lat": 20.7214, "lng": -103.3918 },
        "service": { "category": "vial", "required_class": "A", "required_accessories": ["dolly"] }
      }
      """;

  private static FakeApi api;

  private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
  private final List<RetryInfo> retries = Collections.synchronizedList(new ArrayList<>());

  @BeforeAll
  static void startApi() throws IOException {
    api = FakeApi.start();
  }

  @AfterAll
  static void stopApi() {
    api.close();
  }

  @BeforeEach
  void reset() {
    api.requests.clear();
    api.reply();
  }

  private GrualinkClient.Builder builder() {
    return GrualinkClient.builder()
        .apiKey(TEST_KEY)
        .baseUrl(api.baseUrl())
        .sleeper(sleeps::add)
        .random(() -> 0.5)
        .onRetry(retries::add);
  }

  private GrualinkClient client() {
    return builder().build();
  }

  private static GrualinkException rejection(Executable call) {
    return assertThrows(GrualinkException.class, call);
  }

  // ── Success ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void createServiceRequestSendsTheKeyTheIdempotencyKeyAndTheJsonBody() throws Exception {
    api.reply(Reply.json(201, FakeApi.SERVICE_REQUEST));
    CreateServiceRequestResult result = client().createServiceRequest(INPUT, "order-0001");

    assertEquals(FakeApi.SERVICE_REQUEST_ID, result.serviceRequest().id());
    assertEquals("quoted", result.serviceRequest().status());
    assertEquals("TEST-CONFIRMAR-0001", result.serviceRequest().externalRef());
    assertEquals(new BigDecimal("1002.62"), result.serviceRequest().quote().estimatedPrice().total());
    assertEquals("IVA", result.serviceRequest().quote().estimatedPrice().taxes().get(0).name());
    assertNull(result.serviceRequest().assignment());
    assertFalse(result.replayed());
    assertEquals("order-0001", result.idempotencyKey());

    assertEquals(1, api.requests.size());
    FakeApi.Recorded sent = api.requests.get(0);
    assertEquals("POST", sent.method());
    assertEquals("/api/v1/service-requests", sent.path());
    assertEquals("Bearer " + TEST_KEY, sent.header("Authorization"));
    assertEquals("order-0001", sent.header("Idempotency-Key"));
    assertEquals("application/json", sent.header("Content-Type"));
    assertEquals(FakeApi.JSON.readTree(INPUT_JSON), FakeApi.JSON.readTree(sent.body()));
  }

  @Test
  void createServiceRequestGeneratesAnIdempotencyKeyWhenNoneIsGiven() {
    api.reply(Reply.json(201, FakeApi.SERVICE_REQUEST));
    CreateServiceRequestResult result = client().createServiceRequest(INPUT);
    assertTrue(result.idempotencyKey()
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    assertEquals(result.idempotencyKey(), api.requests.get(0).header("Idempotency-Key"));
  }

  @Test
  void aRepeatedRequestIsA200WithIdempotentReplayedAndIsReportedAsReplayed() {
    api.reply(
        Reply.json(201, FakeApi.SERVICE_REQUEST),
        Reply.json(200, FakeApi.SERVICE_REQUEST).withHeader("Idempotent-Replayed", "true"));
    GrualinkClient client = client();
    CreateServiceRequestResult first = client.createServiceRequest(INPUT, "order-0002");
    CreateServiceRequestResult second = client.createServiceRequest(INPUT, "order-0002");

    assertFalse(first.replayed());
    assertTrue(second.replayed());
    assertEquals(first.serviceRequest(), second.serviceRequest());
    assertEquals(api.requests.get(0).body(), api.requests.get(1).body(), "the same bytes are sent both times");
  }

  @Test
  void a200WithoutTheReplayHeaderIsNotReportedAsReplayed() {
    api.reply(Reply.json(200, FakeApi.SERVICE_REQUEST));
    assertFalse(client().createServiceRequest(INPUT, "order-0002b").replayed());
  }

  @Test
  void getServiceRequestAndFindServiceRequestsByExternalRef() {
    api.reply(Reply.json(200, FakeApi.SERVICE_REQUEST));
    ServiceRequest one = client().getServiceRequest(FakeApi.SERVICE_REQUEST_ID);
    assertEquals("quoted", one.status());
    assertEquals("GET", api.requests.get(0).method());
    assertEquals("/api/v1/service-requests/" + FakeApi.SERVICE_REQUEST_ID, api.requests.get(0).path());

    api.reply(Reply.json(200, "{\"object\":\"list\",\"data\":[" + FakeApi.SERVICE_REQUEST + "]}"));
    List<ServiceRequest> list = client().findServiceRequestsByExternalRef("SIN 2026/001 ñ");
    assertEquals(1, list.size());
    assertEquals("/api/v1/service-requests?external_ref=SIN+2026%2F001+%C3%B1", api.requests.get(1).path());

    api.reply(Reply.json(200, "{\"object\":\"list\",\"data\":[]}"));
    assertTrue(client().findServiceRequestsByExternalRef("NONE").isEmpty());
  }

  @Test
  void healthIsSentWithoutTheKey() {
    api.reply(Reply.json(200, "{\"status\":\"ok\",\"version\":\"v1\"}"));
    assertEquals(new Health("ok", "v1"), client().health());
    assertEquals("/api/v1/health", api.requests.get(0).path());
    assertNull(api.requests.get(0).header("Authorization"));
  }

  // ── Every documented error ───────────────────────────────────────────────────────────────────────────

  static Stream<Arguments> errorTable() {
    return Stream.of(
        Arguments.of(400, "invalid_request", GrualinkInvalidRequestException.class),
        Arguments.of(400, "invalid_json", GrualinkInvalidRequestException.class),
        Arguments.of(400, "key_in_query", GrualinkInvalidRequestException.class),
        Arguments.of(400, "idempotency_key_required", GrualinkInvalidRequestException.class),
        Arguments.of(400, "invalid_idempotency_key", GrualinkInvalidRequestException.class),
        Arguments.of(401, "unauthorized", GrualinkAuthenticationException.class),
        Arguments.of(403, "insufficient_scope", GrualinkPermissionException.class),
        Arguments.of(403, "customer_unavailable", GrualinkPermissionException.class),
        Arguments.of(403, "live_mode_unavailable", GrualinkPermissionException.class),
        Arguments.of(403, "browser_not_allowed", GrualinkPermissionException.class),
        Arguments.of(404, "not_found", GrualinkNotFoundException.class),
        Arguments.of(405, "method_not_allowed", GrualinkInvalidRequestException.class),
        Arguments.of(409, "idempotency_conflict", GrualinkIdempotencyConflictException.class),
        Arguments.of(413, "payload_too_large", GrualinkInvalidRequestException.class),
        Arguments.of(415, "unsupported_media_type", GrualinkInvalidRequestException.class),
        Arguments.of(422, "validation_error", GrualinkValidationException.class),
        Arguments.of(429, "too_many_open_requests", GrualinkRateLimitException.class));
  }

  @ParameterizedTest(name = "{0} {1} is not retried")
  @MethodSource("errorTable")
  void everyDocumentedErrorHasItsClassAndIsNotRetried(int status, String code, Class<?> type) {
    api.reply(FakeApi.apiError(status, code));
    GrualinkException error = rejection(() -> client().createServiceRequest(INPUT));

    assertSame(type, error.getClass());
    GrualinkApiException apiError = assertInstanceOf(GrualinkApiException.class, error);
    assertEquals(status, apiError.getStatus());
    assertEquals(code, apiError.getCode());
    assertEquals("Mensaje de ejemplo.", apiError.getMessage());
    assertTrue(apiError.getDetails().isEmpty());
    assertTrue(apiError.getRetryAfterSeconds().isEmpty());
    assertEquals(1, apiError.getAttempts());
    assertEquals(1, api.requests.size());
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void a422ExposesTheInvalidFieldsInDetails() {
    List<ErrorDetail> details = List.of(
        new ErrorDetail("contact.phone", "invalid_format"), new ErrorDetail("pickup", "invalid_value"));
    api.reply(FakeApi.apiError(422, "validation_error", "Hay campos inválidos.", details));
    GrualinkException error = rejection(() -> client().createServiceRequest(INPUT));
    GrualinkValidationException validation = assertInstanceOf(GrualinkValidationException.class, error);
    assertEquals(details, validation.getDetails());
    assertEquals("Hay campos inválidos.", validation.getMessage());
  }

  @Test
  void anErrorBodyThatIsNotJsonBecomesUnexpectedResponseWithoutEchoingTheBody() {
    api.reply(Reply.text(404, "<html>proxy page</html>"));
    GrualinkException error = rejection(() -> client().getServiceRequest("abc"));
    GrualinkNotFoundException notFound = assertInstanceOf(GrualinkNotFoundException.class, error);
    assertEquals("unexpected_response", notFound.getCode());
    assertFalse(notFound.getMessage().contains("proxy page"));
  }

  @Test
  void aSuccessBodyThatIsNotJsonBecomesUnexpectedResponseAndIsNotRetried() {
    api.reply(Reply.text(200, "<html>proxy page</html>"));
    GrualinkException error = rejection(() -> client().getServiceRequest(FakeApi.SERVICE_REQUEST_ID));
    assertSame(GrualinkApiException.class, error.getClass());
    assertEquals("unexpected_response", ((GrualinkApiException) error).getCode());
    assertEquals(1, api.requests.size());
  }

  @Test
  void aRedirectIsNotFollowedAndTheKeyDoesNotTravelToTheNewAddress() {
    api.reply(Reply.text(302, "").withHeader("Location", api.baseUrl() + "/elsewhere"));
    GrualinkException error = rejection(() -> client().getServiceRequest(FakeApi.SERVICE_REQUEST_ID));
    assertSame(GrualinkApiException.class, error.getClass());
    assertEquals(302, ((GrualinkApiException) error).getStatus());
    assertEquals(1, api.requests.size());
  }

  // ── Retries ──────────────────────────────────────────────────────────────────────────────────────────

  @Test
  void a429RateLimitedWaitsAtLeastRetryAfterAndRetriesWithTheSameKeyAndBody() {
    api.reply(
        FakeApi.apiError(429, "rate_limited", "Demasiadas peticiones.", List.of()).withHeader("Retry-After", "7"),
        Reply.json(201, FakeApi.SERVICE_REQUEST));
    CreateServiceRequestResult result = client().createServiceRequest(INPUT, "order-0003");

    assertEquals(FakeApi.SERVICE_REQUEST_ID, result.serviceRequest().id());
    assertEquals(2, api.requests.size());
    // 7 s from the header plus half (random = 0.5) of the first backoff step (500 ms).
    assertEquals(List.of(7250L), sleeps);
    assertEquals(1, retries.get(0).attempt());
    assertEquals(7250L, retries.get(0).delayMillis());
    GrualinkRateLimitException rateLimit =
        assertInstanceOf(GrualinkRateLimitException.class, retries.get(0).error());
    assertEquals(7, rateLimit.getRetryAfterSeconds().getAsInt());
    assertEquals("order-0003", api.requests.get(0).header("Idempotency-Key"));
    assertEquals("order-0003", api.requests.get(1).header("Idempotency-Key"));
    assertEquals(api.requests.get(0).body(), api.requests.get(1).body());
  }

  @Test
  void retryAfterIsReadAsSecondsOrAsAnHttpDate() {
    Instant now = Instant.parse("2026-11-18T10:00:00Z");
    assertEquals(7, GrualinkClient.parseRetryAfter(" 7 ", now));
    assertEquals(30, GrualinkClient.parseRetryAfter("Wed, 18 Nov 2026 10:00:30 GMT", now));
    assertEquals(0, GrualinkClient.parseRetryAfter("Wed, 18 Nov 2026 09:00:00 GMT", now));
    assertNull(GrualinkClient.parseRetryAfter("soon", now));
    assertNull(GrualinkClient.parseRetryAfter(null, now));
  }

  @Test
  void a5xxIsRetriedWithExponentialBackoffAndJitterThenSucceeds() {
    api.reply(
        FakeApi.apiError(500, "internal_error"),
        Reply.text(502, "<html>bad gateway</html>"),
        FakeApi.apiError(503, "internal_error"),
        Reply.json(201, FakeApi.SERVICE_REQUEST));
    CreateServiceRequestResult result = client().createServiceRequest(INPUT);

    assertEquals(FakeApi.SERVICE_REQUEST_ID, result.serviceRequest().id());
    assertEquals(4, api.requests.size());
    // random = 0.5 over ceilings of 500, 1000 and 2000 ms.
    assertEquals(List.of(250L, 500L, 1000L), sleeps);
    Set<String> keys = new HashSet<>();
    Set<String> bodies = new HashSet<>();
    for (FakeApi.Recorded request : api.requests) {
      keys.add(request.header("Idempotency-Key"));
      bodies.add(request.body());
    }
    assertEquals(1, keys.size(), "every retry reuses the Idempotency-Key");
    assertEquals(1, bodies.size(), "every retry sends the same bytes");
  }

  @Test
  void theBackoffIsCappedAndTheJitterComesFromTheInjectedRandomSource() {
    api.reply(FakeApi.apiError(500, "internal_error"));
    GrualinkClient client = builder().maxRetries(6).random(() -> 1.0).build();
    GrualinkException error = rejection(() -> client.getServiceRequest(FakeApi.SERVICE_REQUEST_ID));
    assertInstanceOf(GrualinkServerException.class, error);
    assertEquals(List.of(500L, 1000L, 2000L, 4000L, 8000L, 8000L), sleeps);
  }

  @Test
  void itGivesUpAfterMaxRetriesAndReportsHowManyAttemptsWereMade() {
    api.reply(FakeApi.apiError(500, "internal_error", "Error interno.", List.of()));
    GrualinkClient client = builder().maxRetries(2).build();
    GrualinkException error = rejection(() -> client.createServiceRequest(INPUT));
    GrualinkServerException server = assertInstanceOf(GrualinkServerException.class, error);
    assertEquals("internal_error", server.getCode());
    assertEquals(3, server.getAttempts());
    assertEquals(3, api.requests.size());
    assertEquals(2, sleeps.size());
  }

  @Test
  void maxRetriesZeroDisablesRetries() {
    api.reply(FakeApi.apiError(500, "internal_error"));
    GrualinkClient client = builder().maxRetries(0).build();
    GrualinkException error = rejection(() -> client.getServiceRequest(FakeApi.SERVICE_REQUEST_ID));
    assertEquals(1, error.getAttempts());
    assertEquals(1, api.requests.size());
  }

  @Test
  void aNetworkErrorIsRetriedAndSurfacesAsGrualinkConnectionException() {
    api.reply(Reply.destroyed(), Reply.json(201, FakeApi.SERVICE_REQUEST));
    CreateServiceRequestResult created = client().createServiceRequest(INPUT, "order-0004");
    assertEquals(FakeApi.SERVICE_REQUEST_ID, created.serviceRequest().id());
    assertInstanceOf(GrualinkConnectionException.class, retries.get(0).error());
    assertEquals(2, api.requests.size());
    assertEquals(api.requests.get(0).body(), api.requests.get(1).body());

    api.reply(Reply.destroyed());
    GrualinkClient client = builder().maxRetries(1).build();
    GrualinkException error = rejection(() -> client.createServiceRequest(INPUT, "order-0005"));
    assertInstanceOf(GrualinkConnectionException.class, error);
    assertEquals(2, error.getAttempts());
  }

  @Test
  void aSlowAnswerHitsTheTimeoutIsRetriedAndSurfacesAsGrualinkTimeoutException() {
    api.reply(Reply.json(200, FakeApi.SERVICE_REQUEST).withDelay(3000));
    GrualinkClient client = builder().timeout(Duration.ofMillis(300)).maxRetries(1).build();
    GrualinkException error = rejection(() -> client.createServiceRequest(INPUT, "order-0006"));
    assertInstanceOf(GrualinkTimeoutException.class, error);
    assertEquals(2, error.getAttempts());
    assertEquals(2, api.requests.size());
    assertInstanceOf(GrualinkTimeoutException.class, retries.get(0).error());
  }

  // ── Local validation: nothing is sent ────────────────────────────────────────────────────────────────

  @Test
  void invalidConfigurationAndArgumentsFailBeforeAnyRequest() {
    GrualinkClient.Builder valid = GrualinkClient.builder().apiKey(TEST_KEY);
    assertThrows(GrualinkConfigException.class, () -> GrualinkClient.builder().build());
    assertThrows(GrualinkConfigException.class, () -> GrualinkClient.builder().apiKey("").build());
    assertThrows(GrualinkConfigException.class, () -> GrualinkClient.builder().apiKey("not-a-key").build());
    assertThrows(GrualinkConfigException.class, () -> valid.baseUrl("http://example.com/api/v1").build());
    assertThrows(GrualinkConfigException.class, () -> valid.baseUrl("not a url").build());
    assertThrows(
        GrualinkConfigException.class, () -> valid.baseUrl("https://example.com/api/v1?key=" + TEST_KEY).build());
    assertThrows(GrualinkConfigException.class, () -> valid.baseUrl("https://user@example.com/api/v1").build());
    valid.baseUrl(null);
    assertThrows(GrualinkConfigException.class, () -> valid.timeout(Duration.ZERO).build());
    valid.timeout(GrualinkClient.DEFAULT_TIMEOUT);
    assertThrows(GrualinkConfigException.class, () -> valid.maxRetries(-1).build());
    valid.maxRetries(0);
    assertEquals("test", valid.build().mode());
    assertEquals("test", valid.baseUrl("http://localhost:8080/api/v1/").build().mode());
    assertEquals("live", GrualinkClient.builder().apiKey("glk_live_FAKE_KEY_FOR_UNIT_TESTS_ONLY").build().mode());

    for (String idempotencyKey : new String[] {"", "with space", "ñ", "x".repeat(256)}) {
      assertInstanceOf(
          GrualinkConfigException.class, rejection(() -> client().createServiceRequest(INPUT, idempotencyKey)));
    }
    assertInstanceOf(GrualinkConfigException.class, rejection(() -> client().createServiceRequest(INPUT, null)));
    assertInstanceOf(GrualinkConfigException.class, rejection(() -> client().createServiceRequest(null)));
    ServiceRequestInput tooLarge = new ServiceRequestInput(
        null, INPUT.contact(), null, INPUT.pickup(), null, null, "x".repeat(17 * 1024));
    assertInstanceOf(GrualinkConfigException.class, rejection(() -> client().createServiceRequest(tooLarge)));
    assertInstanceOf(GrualinkConfigException.class, rejection(() -> client().getServiceRequest(" ")));
    assertInstanceOf(
        GrualinkConfigException.class, rejection(() -> client().findServiceRequestsByExternalRef("")));
    assertEquals(0, api.requests.size());
  }

  @Test
  void aBadKeyIsNeverEchoedByTheConfigurationError() {
    String almost = "glk_prod_FAKE_BUT_SHOULD_NOT_BE_ECHOED";
    GrualinkConfigException error =
        assertThrows(GrualinkConfigException.class, () -> GrualinkClient.builder().apiKey(almost).build());
    assertFalse(printed(error).contains(almost));
  }

  // ── The key never leaks ──────────────────────────────────────────────────────────────────────────────

  @Test
  void theApiKeyNeverAppearsInErrorsLogsOrTheClientItself() throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    PrintStream realOut = System.out;
    PrintStream realErr = System.err;
    PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
    List<GrualinkException> errors = new ArrayList<>();
    GrualinkClient client;

    System.setOut(capture);
    System.setErr(capture);
    try {
      client = builder().maxRetries(1).onRetry(info -> System.err.println("retrying " + info)).build();
      System.out.println(client);
      System.out.println("client: " + client + " hash " + client.hashCode());

      // A server that (wrongly) echoes the Authorization header back in every field of the error.
      for (int status : new int[] {400, 401, 403, 404, 409, 422, 429, 500}) {
        Responder echo = request -> {
          String sent = request.header("Authorization");
          return FakeApi.apiError(
              status, "code " + sent, "message " + sent,
              List.of(new ErrorDetail("field " + sent, "issue " + sent)));
        };
        api.reply(echo);
        errors.add(rejection(() -> client.createServiceRequest(INPUT)));
      }
      api.reply(Reply.destroyed());
      errors.add(rejection(() -> client.createServiceRequest(INPUT)));
      api.reply(Reply.json(200, FakeApi.SERVICE_REQUEST).withDelay(3000));
      GrualinkClient impatient = builder().timeout(Duration.ofMillis(200)).maxRetries(0).build();
      errors.add(rejection(() -> impatient.getServiceRequest(FakeApi.SERVICE_REQUEST_ID)));
      // A transport that throws an error mentioning the key.
      HttpTransport leaky = request -> {
        throw new IOException("connect failed for Bearer " + TEST_KEY);
      };
      GrualinkClient unlucky = builder().transport(leaky).maxRetries(0).build();
      errors.add(rejection(() -> unlucky.getServiceRequest(FakeApi.SERVICE_REQUEST_ID)));

      for (GrualinkException error : errors) {
        error.printStackTrace();
        System.out.println(error + " | " + error.getMessage() + " | " + error.getLocalizedMessage());
        if (error instanceof GrualinkApiException apiError) {
          System.out.println(apiError.getCode() + " | " + apiError.getDetails());
        }
      }
    } finally {
      System.setOut(realOut);
      System.setErr(realErr);
    }

    assertEquals(11, errors.size());
    boolean keyWasSent = false;
    for (FakeApi.Recorded request : api.requests) {
      keyWasSent |= ("Bearer " + TEST_KEY).equals(request.header("Authorization"));
    }
    assertTrue(keyWasSent, "the key was really sent");

    StringBuilder everything = new StringBuilder(captured.toString(StandardCharsets.UTF_8));
    assertTrue(everything.length() > 2000, "the output was really captured");
    everything.append(client).append('\n');
    for (GrualinkException error : errors) {
      everything.append(printed(error)).append('\n');
      for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
        everything.append(printed(cause)).append('\n');
      }
    }
    for (RetryInfo retry : retries) {
      everything.append(retry).append('\n');
    }
    String all = everything.toString();
    assertFalse(all.contains(TEST_KEY), "the API key leaked");
    assertFalse(all.contains(TEST_KEY.substring(9)), "part of the API key leaked");
    assertTrue(all.contains("[REDACTED]"), "echoed keys are replaced by a marker");
  }

  /** Everything a logger could print about an exception. */
  private static String printed(Throwable error) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    error.printStackTrace(new PrintStream(out, true, StandardCharsets.UTF_8));
    String text = out.toString(StandardCharsets.UTF_8) + error + error.getMessage();
    if (error instanceof GrualinkApiException apiError) {
      text += apiError.getCode() + apiError.getDetails();
    }
    return text;
  }

  @Test
  void theInputIsSerializedWithTheWireNamesOfTheContract() throws Exception {
    JsonNode sent = Json.MAPPER.valueToTree(INPUT);
    assertEquals(FakeApi.JSON.readTree(INPUT_JSON), sent);
  }
}
