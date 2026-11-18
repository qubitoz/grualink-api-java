package mx.grualink.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs every case of the shared contract file against the verifier. */
final class SignatureVectorsTest {
  // Tests run from the repository root, where the contract kit lives.
  private static final Path VECTORS = Path.of("contract", "signature-vectors.json");
  private static final JsonNode FILE = load();
  private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();

  private static JsonNode load() {
    try {
      return FakeApi.JSON.readTree(Files.readAllBytes(VECTORS));
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + VECTORS.toAbsolutePath(), e);
    }
  }

  private static JsonNode vector(String name) {
    for (JsonNode vector : FILE.get("cases")) {
      if (name.equals(vector.get("name").asText())) {
        return vector;
      }
    }
    throw new IllegalStateException("no case named " + name);
  }

  private static String signatureHeader(JsonNode vector) {
    JsonNode header = vector.get("headers").get(Webhooks.SIGNATURE_HEADER);
    return header == null ? null : header.asText();
  }

  @Test
  void theVectorFileHasTheExpectedShape() {
    assertEquals(1, FILE.get("version").asInt());
    assertEquals(300, FILE.get("tolerance_seconds").asInt());
    assertEquals(Webhooks.SIGNATURE_HEADER, FILE.get("signature_header").asText());
    JsonNode cases = FILE.get("cases");
    assertTrue(cases.size() >= 30);

    Set<String> names = new HashSet<>();
    Set<String> coveredReasons = new HashSet<>();
    boolean someValid = false;
    boolean someInvalid = false;
    for (JsonNode vector : cases) {
      names.add(vector.get("name").asText());
      coveredReasons.add(vector.get("expected").get("reason").asText());
      someValid |= vector.get("expected").get("valid").asBoolean();
      someInvalid |= !vector.get("expected").get("valid").asBoolean();
    }
    assertEquals(cases.size(), names.size(), "case names are unique");
    assertTrue(someValid && someInvalid);

    // The reasons of the file are exactly the reasons of the library, and every one is exercised.
    List<String> fileReasons = new ArrayList<>();
    FILE.get("reasons").forEach(reason -> fileReasons.add(reason.asText()));
    List<String> libraryReasons =
        Arrays.stream(SignatureVerificationReason.values()).map(SignatureVerificationReason::wireName).toList();
    assertEquals(fileReasons, libraryReasons);
    assertEquals(new HashSet<>(fileReasons), coveredReasons);
  }

  @TestFactory
  List<DynamicTest> everyVector() {
    long tolerance = FILE.get("tolerance_seconds").asLong();
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode vector : FILE.get("cases")) {
      String name = vector.get("name").asText();
      tests.add(DynamicTest.dynamicTest("vector: " + name, () -> {
        byte[] rawBody = Base64.getDecoder().decode(vector.get("body_base64").asText());
        if (!vector.get("body").isNull()) {
          assertArrayEquals(
              vector.get("body").asText().getBytes(StandardCharsets.UTF_8), rawBody, "body and body_base64 agree");
        }
        SignatureVerificationResult result = Webhooks.verifyWebhookSignature(
            rawBody, signatureHeader(vector), vector.get("secret").asText(), vector.get("now").asLong(), tolerance);

        String description = vector.get("description").asText();
        assertEquals(vector.get("expected").get("reason").asText(), result.reason().wireName(), description);
        assertEquals(vector.get("expected").get("valid").asBoolean(), result.valid(), description);
        VERIFIED.add(name);
      }));
    }
    return tests;
  }

  /** A case that is skipped or filtered out must fail the suite. */
  @AfterAll
  static void noVectorWasSkipped() {
    Set<String> all = new HashSet<>();
    FILE.get("cases").forEach(vector -> all.add(vector.get("name").asText()));
    assertEquals(all, VERIFIED, "every case of signature-vectors.json must be verified");
  }

  @Test
  void theReceiverMayHoldTwoSecretsDuringItsOwnRotation() {
    JsonNode vector = vector("valid_basic");
    byte[] rawBody = Base64.getDecoder().decode(vector.get("body_base64").asText());
    String header = signatureHeader(vector);
    String secret = vector.get("secret").asText();
    long now = vector.get("now").asLong();

    SignatureVerificationResult ok = Webhooks.verifyWebhookSignature(
        rawBody, header, List.of("whsec_TEST_ONLY_FAKE_NEXT", secret), now, Webhooks.DEFAULT_TOLERANCE_SECONDS);
    assertTrue(ok.valid());
    assertEquals(SignatureVerificationReason.OK, ok.reason());

    SignatureVerificationResult none = Webhooks.verifyWebhookSignature(
        rawBody, header, List.of(), now, Webhooks.DEFAULT_TOLERANCE_SECONDS);
    assertEquals(new SignatureVerificationResult(false, SignatureVerificationReason.MISSING_SECRET), none);
  }

  @Test
  void theSystemClockIsUsedWhenNoTimeIsGiven() {
    JsonNode vector = vector("valid_basic");
    byte[] rawBody = Base64.getDecoder().decode(vector.get("body_base64").asText());
    String secret = vector.get("secret").asText();
    long now = System.currentTimeMillis() / 1000;
    String fresh = Webhooks.buildSignatureHeader(List.of(secret), now, rawBody);
    assertTrue(Webhooks.verifyWebhookSignature(rawBody, fresh, secret).valid());

    String stale = Webhooks.buildSignatureHeader(List.of(secret), now - 3600, rawBody);
    assertEquals(
        SignatureVerificationReason.TIMESTAMP_OUT_OF_TOLERANCE,
        Webhooks.verifyWebhookSignature(rawBody, stale, secret).reason());
  }

  @Test
  void verificationNeverThrowsOnHostileInput() {
    List<String> headers = new ArrayList<>();
    headers.add(null);
    headers.add("");
    headers.add("42");
    headers.add("{}");
    headers.add("x".repeat(100_000));
    headers.add("t=1,v1=" + "a".repeat(64) + ",".repeat(5000));
    headers.add("t=" + "9".repeat(12) + ",v1=" + "a".repeat(64));
    headers.add("=,=,t,v1,t==,v1==");
    List<String> secrets = new ArrayList<>();
    secrets.add(null);
    secrets.add("");
    secrets.add("whsec_TEST_ONLY_FAKE_SECRET_0001");

    for (String header : headers) {
      for (String secret : secrets) {
        for (byte[] rawBody : new byte[][] {"{}".getBytes(StandardCharsets.UTF_8), new byte[0], null}) {
          assertFalse(Webhooks.verifyWebhookSignature(rawBody, header, secret, 1, 300).valid());
        }
      }
    }
    List<String> noSecrets = null;
    assertEquals(
        SignatureVerificationReason.MISSING_SECRET,
        Webhooks.verifyWebhookSignature(new byte[0], "t=1,v1=" + "a".repeat(64), noSecrets, 1, 300).reason());
  }

  @Test
  void buildSignatureHeaderProducesWhatTheVerifierAccepts() {
    byte[] body = "{\"id\": \"x\"}".getBytes(StandardCharsets.UTF_8);
    List<String> secrets = List.of("whsec_TEST_ONLY_FAKE_A", "whsec_TEST_ONLY_FAKE_B");
    String header = Webhooks.buildSignatureHeader(secrets, 1795000000L, body);
    assertTrue(header.matches("t=1795000000,v1=[0-9a-f]{64},v1=[0-9a-f]{64}"));
    for (String secret : secrets) {
      assertTrue(Webhooks.verifyWebhookSignature(body, header, secret, 1795000001L, 300).valid());
    }
    assertNotNull(Webhooks.computeWebhookSignature("whsec_TEST_ONLY_FAKE_A", "1795000000", body));
    assertEquals(
        Webhooks.computeWebhookSignature("whsec_TEST_ONLY_FAKE_A", "1795000000", body),
        Webhooks.computeWebhookSignature("whsec_TEST_ONLY_FAKE_A", 1795000000L, body));
  }
}
