package mx.grualink.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Runs the two examples for real, each in its own JVM, exactly as a reader would: only environment variables,
 * no code changes. The API they talk to is the local fake; nothing leaves this machine.
 */
final class ExamplesEndToEndTest {
  private static final String TEST_KEY = "glk_test_FAKE_KEY_FOR_EXAMPLES_ONLY";
  private static final String SECRET = "whsec_TEST_ONLY_FAKE_SECRET_0001";
  private static final String PREVIOUS = "whsec_TEST_ONLY_FAKE_SECRET_0000";
  private static final String REQUEST_TOW = "mx.grualink.api.examples.RequestTow";
  private static final String WEBHOOK_SERVER = "mx.grualink.api.examples.WebhookServer";

  /** A child JVM running one example, with its combined output. */
  private static final class Example implements AutoCloseable {
    private final Process process;
    private final StringBuffer output = new StringBuffer();
    private final Thread reader;

    Example(String mainClass, Map<String, String> environment) throws IOException {
      String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      ProcessBuilder builder =
          new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), mainClass);
      builder.redirectErrorStream(true);
      builder.environment().keySet().removeIf(name -> name.startsWith("GRUALINK_") || name.equals("PORT"));
      builder.environment().putAll(environment);
      process = builder.start();
      reader = new Thread(() -> {
        try (InputStream in = process.getInputStream()) {
          byte[] chunk = new byte[4096];
          for (int read = in.read(chunk); read >= 0; read = in.read(chunk)) {
            output.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
          }
        } catch (IOException e) {
          // The process was destroyed.
        }
      });
      reader.setDaemon(true);
      reader.start();
    }

    String output() {
      return output.toString();
    }

    /** Waits for the process to end and returns its exit code. */
    int exitCode() throws InterruptedException {
      assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the example did not finish:\n" + output());
      reader.join(10_000);
      return process.exitValue();
    }

    void waitForOutput(String text) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (!output().contains(text)) {
        assertTrue(process.isAlive(), "the example stopped:\n" + output());
        assertTrue(System.nanoTime() < deadline, "no \"" + text + "\" in the output:\n" + output());
        Thread.sleep(50);
      }
    }

    @Override
    public void close() {
      process.destroyForcibly();
      System.out.println("---- output of the example ----");
      System.out.println(output());
      System.out.println("-------------------------------");
    }
  }

  @Test
  void requestTowRunsEndToEndAgainstTheFakeApi() throws Exception {
    try (FakeApi api = FakeApi.start()) {
      api.reply(FakeApi.scripted());
      Map<String, String> environment = Map.of("GRUALINK_API_KEY", TEST_KEY, "GRUALINK_API_BASE_URL", api.baseUrl());
      try (Example example = new Example(REQUEST_TOW, environment)) {
        int exitCode = example.exitCode();
        String output = example.output();
        assertEquals(0, exitCode, output);
        assertTrue(output.contains("API status: ok (v1)"), output);
        assertTrue(output.contains("Created " + FakeApi.SERVICE_REQUEST_ID + " (test) with status \"quoted\""), output);
        assertTrue(output.contains("Subtotal:  864.33 MXN"), output);
        assertTrue(output.contains("+ IVA (16%): 138.29"), output);
        assertTrue(output.contains("Total:     1002.62 MXN"), output);
        assertTrue(output.contains("replayed=true, same id=true"), output);
        assertTrue(output.contains("Status now: \"confirmed\""), output);
        assertTrue(output.contains("Requests with reference TEST-CONFIRMAR-"), output);
        assertFalse(output.contains(TEST_KEY), "the example printed the key");
      }

      List<FakeApi.Recorded> requests = api.requests;
      assertEquals(5, requests.size());
      assertNull(requests.get(0).header("Authorization"), "health is sent without the key");
      assertEquals("Bearer " + TEST_KEY, requests.get(1).header("Authorization"));
      assertEquals(requests.get(1).header("Idempotency-Key"), requests.get(2).header("Idempotency-Key"));
      assertEquals(requests.get(1).body(), requests.get(2).body(), "the replay sends the same bytes");
      assertTrue(requests.get(1).body().contains("\"external_ref\":\"TEST-CONFIRMAR-"));
    }
  }

  @Test
  void requestTowRefusesToRunWithoutATestKey() throws Exception {
    try (Example live = new Example(REQUEST_TOW, Map.of("GRUALINK_API_KEY", "glk_live_FAKE_KEY_FOR_EXAMPLES_ONLY"))) {
      assertEquals(1, live.exitCode());
      assertTrue(live.output().contains("only runs with a TEST key"), live.output());
    }
    try (Example missing = new Example(REQUEST_TOW, Map.of())) {
      assertEquals(1, missing.exitCode());
      assertTrue(missing.output().contains("Set GRUALINK_API_KEY"), missing.output());
    }
  }

  @Test
  void webhookServerVerifiesDeduplicatesAndRejects() throws Exception {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    Map<String, String> environment = Map.of(
        "GRUALINK_WEBHOOK_SECRET", SECRET,
        "GRUALINK_WEBHOOK_SECRET_PREVIOUS", PREVIOUS,
        "PORT", Integer.toString(port));
    String url = "http://127.0.0.1:" + port + "/webhooks/grualink";
    String id = "aaaaaaaa-0000-4000-8000-000000000001";
    String body = "{\"id\": \"" + id + "\", \"object\": \"event\", \"type\": \"service_request.confirmed\", "
        + "\"mode\": \"test\", \"created_at\": \"2026-11-18T10:05:01.000Z\", \"data\": {\"previous_status\": "
        + "\"quoted\", \"service_request\": {\"id\": \"" + FakeApi.SERVICE_REQUEST_ID + "\", \"status\": "
        + "\"confirmed\", \"external_ref\": \"TEST-CONFIRMAR-0001\"}}}";
    String testId = "aaaaaaaa-0000-4000-8000-000000000002";
    String testBody = "{\"id\": \"" + testId + "\", \"object\": \"event\", \"type\": \"webhook.test\", "
        + "\"mode\": \"test\", \"created_at\": \"2026-11-18T10:06:00.000Z\", \"data\": {\"message\": \"Hola\"}}";

    try (Example example = new Example(WEBHOOK_SERVER, environment)) {
      example.waitForOutput("Listening for GRUALINK webhooks on http://localhost:" + port + "/webhooks/grualink");
      long now = System.currentTimeMillis() / 1000;

      HttpResponse<String> first = deliver(url, id, body, signature(List.of(SECRET), now, body));
      assertEquals(200, first.statusCode());
      assertEquals("{\"received\":true}", first.body());

      HttpResponse<String> repeated = deliver(url, id, body, signature(List.of(SECRET), now, body));
      assertEquals(200, repeated.statusCode());
      assertEquals("{\"received\":true,\"duplicate\":true}", repeated.body());

      // Signed with the previous secret, as during a rotation on the receiver side.
      HttpResponse<String> rotated = deliver(url, testId, testBody, signature(List.of(PREVIOUS), now, testBody));
      assertEquals(200, rotated.statusCode());

      String tamperedBody = body.replace("confirmed", "completed");
      assertNotEquals(body, tamperedBody);
      HttpResponse<String> tampered = deliver(url, id, tamperedBody, signature(List.of(SECRET), now, body));
      assertEquals(401, tampered.statusCode());
      assertEquals("{\"received\":false,\"error\":\"invalid_signature\"}", tampered.body());

      HttpResponse<String> stale = deliver(url, id, body, signature(List.of(SECRET), now - 3600, body));
      assertEquals(401, stale.statusCode());

      example.waitForOutput("(timestamp_out_of_tolerance)");
      String output = example.output();
      assertTrue(
          output.contains("[test] service_request.confirmed: request " + FakeApi.SERVICE_REQUEST_ID
              + " (TEST-CONFIRMAR-0001) is now \"confirmed\""),
          output);
      assertTrue(output.contains("[test] webhook.test: Hola"), output);
      assertTrue(output.contains("Delivery " + id + ": 200 (duplicate)"), output);
      assertTrue(output.contains("Delivery " + id + ": 401 (signature_mismatch)"), output);
      assertEquals(1, output.split("is now \"confirmed\"", -1).length - 1, "the event was processed once");
      assertFalse(output.contains(SECRET), "the example printed the secret");
    }
  }

  @Test
  void webhookServerRefusesToStartWithoutASecret() throws Exception {
    try (Example example = new Example(WEBHOOK_SERVER, Map.of())) {
      assertEquals(1, example.exitCode());
      assertTrue(example.output().contains("Set GRUALINK_WEBHOOK_SECRET"), example.output());
    }
  }

  private static String signature(List<String> secrets, long timestamp, String signedBody) {
    return Webhooks.buildSignatureHeader(secrets, timestamp, signedBody.getBytes(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> deliver(String url, String id, String body, String signature)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json; charset=utf-8")
        .header("User-Agent", "GRUALINK-Avisos/1.0")
        .header("X-GruaLink-Delivery", id)
        .header("X-GruaLink-Signature", signature)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
