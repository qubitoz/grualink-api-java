package mx.grualink.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Minimal client of the GRUALINK API: create a service request, read it, find it by your reference.
 *
 * <p>Thread-safe. The API key is kept in a private field and is never part of {@link #toString()}, of an
 * exception or of anything handed to a callback.
 */
public final class GrualinkClient {
  public static final String DEFAULT_BASE_URL = "https://grualink.mx/api/v1";
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
  public static final int DEFAULT_MAX_RETRIES = 3;
  public static final Duration DEFAULT_RETRY_BASE_DELAY = Duration.ofMillis(500);
  public static final Duration DEFAULT_RETRY_MAX_DELAY = Duration.ofSeconds(8);

  private static final Pattern API_KEY = Pattern.compile("glk_(test|live)_[\\x21-\\x7e]+");
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7e]{1,255}");
  private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("[0-9]{1,9}");
  private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
  private static final int MAX_BODY_BYTES = 16 * 1024;
  /** The API never asks to wait more than 60 seconds. */
  private static final int MAX_RETRY_AFTER_SECONDS = 60;
  private static final String REDACTED = "[REDACTED]";

  /** Waits between attempts. Replace it only in tests. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final String apiKey;
  private final String baseUrl;
  private final Duration timeout;
  private final int maxRetries;
  private final long retryBaseDelayMillis;
  private final long retryMaxDelayMillis;
  private final Consumer<RetryInfo> onRetry;
  private final HttpTransport transport;
  private final Sleeper sleeper;
  private final DoubleSupplier random;

  private GrualinkClient(Builder builder) {
    String key = builder.apiKey == null ? "" : builder.apiKey.strip();
    if (key.isEmpty()) {
      throw new GrualinkConfigException("apiKey is required (set GRUALINK_API_KEY).");
    }
    // The rejected value is never echoed: it may be a real key with a typo.
    if (!API_KEY.matcher(key).matches()) {
      throw new GrualinkConfigException("apiKey must start with glk_test_ or glk_live_.");
    }
    String base = (builder.baseUrl == null ? DEFAULT_BASE_URL : builder.baseUrl).replaceAll("/+$", "");
    URI parsed;
    try {
      parsed = new URI(base);
    } catch (URISyntaxException e) {
      throw new GrualinkConfigException("baseUrl is not a valid URL.");
    }
    String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
    String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
    if (host.isEmpty()) {
      throw new GrualinkConfigException("baseUrl is not a valid URL.");
    }
    if (!scheme.equals("https") && !(scheme.equals("http") && LOCAL_HOSTS.contains(host))) {
      throw new GrualinkConfigException("baseUrl must use https (http is only accepted for localhost).");
    }
    if (parsed.getRawQuery() != null || parsed.getRawFragment() != null || parsed.getRawUserInfo() != null) {
      throw new GrualinkConfigException("baseUrl must not contain credentials, query or fragment.");
    }
    this.apiKey = key;
    this.baseUrl = base;
    this.timeout = positive(builder.timeout, "timeout");
    if (builder.maxRetries < 0) {
      throw new GrualinkConfigException("maxRetries must be a non-negative integer.");
    }
    this.maxRetries = builder.maxRetries;
    this.retryBaseDelayMillis = positive(builder.retryBaseDelay, "retryBaseDelay").toMillis();
    this.retryMaxDelayMillis = positive(builder.retryMaxDelay, "retryMaxDelay").toMillis();
    this.onRetry = builder.onRetry;
    this.transport = builder.transport != null ? builder.transport : new DefaultTransport(this.timeout);
    this.sleeper = builder.sleeper != null ? builder.sleeper : Thread::sleep;
    this.random = builder.random != null ? builder.random : () -> ThreadLocalRandom.current().nextDouble();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** {@code test} or {@code live}, from the key prefix. */
  public String mode() {
    return apiKey.startsWith("glk_live_") ? "live" : "test";
  }

  /** {@code GET /health}. Sent without the key. */
  public Health health() {
    return send(newRequest("/health", false).GET().build(), Health.class).body();
  }

  /**
   * {@code POST /service-requests} with a generated {@code Idempotency-Key} (UUID v4). Prefer
   * {@link #createServiceRequest(ServiceRequestInput, String)} with a key you stored beforehand.
   */
  public CreateServiceRequestResult createServiceRequest(ServiceRequestInput input) {
    return createServiceRequest(input, UUID.randomUUID().toString());
  }

  /**
   * {@code POST /service-requests}. The body is serialized once and the exact same bytes and
   * {@code Idempotency-Key} are reused on every retry, so a retry can never create a second request.
   *
   * @param idempotencyKey 1 to 255 visible ASCII characters, unique per request you want to create. Store it
   *     with your record before calling: sending it again with the same body returns the original response
   *     instead of a duplicate.
   */
  public CreateServiceRequestResult createServiceRequest(ServiceRequestInput input, String idempotencyKey) {
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new GrualinkConfigException("idempotencyKey must be 1 to 255 visible ASCII characters.");
    }
    if (input == null) {
      throw new GrualinkConfigException("The service request is required.");
    }
    final byte[] body;
    try {
      body = Json.MAPPER.writeValueAsBytes(input);
    } catch (JsonProcessingException e) {
      throw new GrualinkConfigException("The service request could not be serialized.");
    }
    if (body.length > MAX_BODY_BYTES) {
      throw new GrualinkConfigException("The service request exceeds 16 KB.");
    }
    // Built once: every attempt sends this same request object, so the bytes cannot differ.
    HttpRequest request = newRequest("/service-requests", true)
        .header("Content-Type", "application/json")
        .header("Idempotency-Key", idempotencyKey)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    Answer<ServiceRequest> answer = send(request, ServiceRequest.class);
    boolean replayed = answer.status() == 200
        && "true".equals(answer.headers().firstValue("Idempotent-Replayed").orElse(null));
    return new CreateServiceRequestResult(answer.body(), replayed, idempotencyKey);
  }

  /** {@code GET /service-requests/{id}}. */
  public ServiceRequest getServiceRequest(String id) {
    if (id == null || id.isBlank()) {
      throw new GrualinkConfigException("id is required.");
    }
    String segment = URLEncoder.encode(id.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    return send(newRequest("/service-requests/" + segment, true).GET().build(), ServiceRequest.class).body();
  }

  /** {@code GET /service-requests?external_ref=...}: up to 20 of your requests with that reference, newest first. */
  public List<ServiceRequest> findServiceRequestsByExternalRef(String externalRef) {
    if (externalRef == null || externalRef.isBlank()) {
      throw new GrualinkConfigException("externalRef is required.");
    }
    String query = "external_ref=" + URLEncoder.encode(externalRef, StandardCharsets.UTF_8);
    List<ServiceRequest> data =
        send(newRequest("/service-requests?" + query, true).GET().build(), ServiceRequestList.class).body().data();
    return data == null ? List.of() : List.copyOf(data);
  }

  /** Never shows the key. */
  @Override
  public String toString() {
    return "GrualinkClient[mode=" + mode() + ", baseUrl=" + baseUrl + "]";
  }

  private HttpRequest.Builder newRequest(String path, boolean authenticated) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("Accept", "application/json");
    if (authenticated) {
      request.header("Authorization", "Bearer " + apiKey);
    }
    return request;
  }

  /** Replaces the API key with a marker in any text that will leave this class. */
  private String redact(String text) {
    return text.replace(apiKey, REDACTED);
  }

  private <T> Answer<T> send(HttpRequest request, Class<T> type) {
    for (int attempt = 1; ; attempt++) {
      GrualinkException failure;
      try {
        HttpResponse<byte[]> response = transport.send(request);
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          T body = parse(response.body(), type);
          if (body != null) {
            return new Answer<>(status, response.headers(), body);
          }
          failure = new GrualinkApiException(
              status, "unexpected_response", "The API answered with a body that is not the expected JSON.",
              List.of(), null);
        } else {
          failure = toApiException(response);
        }
      } catch (HttpTimeoutException e) {
        failure = new GrualinkTimeoutException(
            "No response from the GRUALINK API within " + timeout.toMillis() + " ms.");
      } catch (IOException | RuntimeException e) {
        // The cause is described, not attached: its own message could carry what must not be logged.
        failure = new GrualinkConnectionException(redact("Could not reach the GRUALINK API: " + describe(e)));
      } catch (InterruptedException e) {
        throw interrupted(attempt);
      }

      failure.setAttempts(attempt);
      if (!isRetryable(failure) || attempt > maxRetries) {
        throw failure;
      }
      long delayMillis = retryDelayMillis(attempt, failure);
      if (onRetry != null) {
        onRetry.accept(new RetryInfo(attempt, delayMillis, failure));
      }
      try {
        sleeper.sleep(delayMillis);
      } catch (InterruptedException e) {
        throw interrupted(attempt);
      }
    }
  }

  private static GrualinkException interrupted(int attempt) {
    Thread.currentThread().interrupt();
    GrualinkConnectionException error =
        new GrualinkConnectionException("Interrupted while calling the GRUALINK API.");
    error.setAttempts(attempt);
    return error;
  }

  private static boolean isRetryable(GrualinkException error) {
    if (error instanceof GrualinkConnectionException || error instanceof GrualinkTimeoutException) {
      return true;
    }
    if (error instanceof GrualinkServerException) {
      return true;
    }
    // too_many_open_requests only clears when the company resolves a request: waiting a few seconds does not help.
    if (error instanceof GrualinkRateLimitException rateLimit) {
      return !"too_many_open_requests".equals(rateLimit.getCode());
    }
    return false;
  }

  /** Exponential backoff with full jitter; {@code Retry-After} is a floor when the API sends it. */
  private long retryDelayMillis(int attempt, GrualinkException failure) {
    double ceiling = Math.min(retryMaxDelayMillis, retryBaseDelayMillis * Math.pow(2, attempt - 1));
    double jitter = random.getAsDouble() * ceiling;
    if (failure instanceof GrualinkApiException api && api.getRetryAfterSeconds().isPresent()) {
      int retryAfter = Math.min(api.getRetryAfterSeconds().getAsInt(), MAX_RETRY_AFTER_SECONDS);
      return Math.round(retryAfter * 1000.0 + jitter);
    }
    return Math.round(jitter);
  }

  private static <T> T parse(byte[] body, Class<T> type) {
    try {
      return Json.MAPPER.readValue(body, type);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private GrualinkApiException toApiException(HttpResponse<byte[]> response) {
    int status = response.statusCode();
    Integer retryAfterSeconds =
        parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), Instant.now());
    String code = "unexpected_response";
    String message = "The API answered HTTP " + status + ".";
    List<ErrorDetail> details = List.of();
    try {
      JsonNode error = Json.MAPPER.readTree(response.body()).get("error");
      if (error != null && error.path("code").isTextual()) {
        code = redact(error.get("code").asText());
        if (error.path("message").isTextual()) {
          message = redact(error.get("message").asText());
        }
        JsonNode list = error.get("details");
        if (list != null && list.isArray()) {
          List<ErrorDetail> parsed = new ArrayList<>();
          for (JsonNode detail : list) {
            if (detail.isObject()) {
              parsed.add(new ErrorDetail(redact(text(detail.get("field"))), redact(text(detail.get("issue")))));
            }
          }
          details = parsed;
        }
      }
    } catch (IOException | RuntimeException e) {
      // Not JSON (for example an HTML page from a proxy): keep the generic message; the body is not echoed.
    }
    return GrualinkApiException.fromStatus(status, code, message, details, retryAfterSeconds);
  }

  private static String text(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    return node.isTextual() ? node.asText() : node.toString();
  }

  /** Seconds, from either form of the header: a number of seconds or an HTTP date. */
  static Integer parseRetryAfter(String value, Instant now) {
    if (value == null) {
      return null;
    }
    String text = value.strip();
    if (RETRY_AFTER_SECONDS.matcher(text).matches()) {
      return Integer.valueOf(text);
    }
    try {
      Instant date = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      long seconds = (long) Math.ceil(Duration.between(now, date).toMillis() / 1000.0);
      return (int) Math.max(0, Math.min(Integer.MAX_VALUE, seconds));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String describe(Exception cause) {
    String message = cause.getMessage();
    String name = cause.getClass().getSimpleName();
    return message == null || message.isBlank() ? name : name + ": " + message;
  }

  private static Duration positive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new GrualinkConfigException(name + " must be a positive duration.");
    }
    return value;
  }

  private record Answer<T>(int status, HttpHeaders headers, T body) {}

  /** {@link HttpClient} without redirects, with the timeout applied to the whole exchange, body included. */
  private static final class DefaultTransport implements HttpTransport {
    private final HttpClient http;
    private final long timeoutMillis;

    DefaultTransport(Duration timeout) {
      this.timeoutMillis = timeout.toMillis();
      this.http = HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          // A redirect is never followed: the key must not travel to another address.
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(timeout)
          .build();
    }

    @Override
    public HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
      CompletableFuture<HttpResponse<byte[]>> exchange =
          http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
      try {
        return exchange.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        exchange.cancel(true);
        throw new HttpTimeoutException("request timed out");
      } catch (InterruptedException e) {
        exchange.cancel(true);
        throw e;
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) {
          throw io;
        }
        throw new IOException(cause == null ? "request failed" : cause.getClass().getSimpleName());
      }
    }
  }

  /** Configuration of a {@link GrualinkClient}. Only {@link #apiKey(String)} is required. */
  public static final class Builder {
    private String apiKey;
    private String baseUrl;
    private Duration timeout = DEFAULT_TIMEOUT;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private Duration retryBaseDelay = DEFAULT_RETRY_BASE_DELAY;
    private Duration retryMaxDelay = DEFAULT_RETRY_MAX_DELAY;
    private Consumer<RetryInfo> onRetry;
    private HttpTransport transport;
    private Sleeper sleeper;
    private DoubleSupplier random;

    private Builder() {}

    /** {@code glk_test_...} or {@code glk_live_...}. Read it from the environment; never hard-code it. */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /** Defaults to {@code https://grualink.mx/api/v1}. */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /** Maximum time for each attempt (default 15 seconds). */
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /** Retries after the first attempt for 429 {@code rate_limited}, 5xx, timeouts and network errors (default 3). */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    /** First backoff step (default 500 ms). */
    public Builder retryBaseDelay(Duration retryBaseDelay) {
      this.retryBaseDelay = retryBaseDelay;
      return this;
    }

    /** Ceiling of the exponential backoff (default 8 seconds). */
    public Builder retryMaxDelay(Duration retryMaxDelay) {
      this.retryMaxDelay = retryMaxDelay;
      return this;
    }

    /** Called before each wait. Use it for your own logging. */
    public Builder onRetry(Consumer<RetryInfo> onRetry) {
      this.onRetry = onRetry;
      return this;
    }

    /** Injection point for tests. */
    public Builder transport(HttpTransport transport) {
      this.transport = transport;
      return this;
    }

    /** Injection point for tests. */
    public Builder sleeper(Sleeper sleeper) {
      this.sleeper = sleeper;
      return this;
    }

    /** Injection point for tests: a source of numbers in [0, 1) for the jitter. */
    public Builder random(DoubleSupplier random) {
      this.random = random;
      return this;
    }

    /** @throws GrualinkConfigException when a value is invalid */
    public GrualinkClient build() {
      return new GrualinkClient(this);
    }
  }
}
