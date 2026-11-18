# GRUALINK API: Java example

[Español](README.md)

A clone-and-run example that integrates your system (insurer, roadside assistance, integrator) with the
GRUALINK API: **request a tow truck**, **check its status** and **receive signed webhooks**.

Public API guide: <https://grualink.mx/api>

What is inside:

- A **minimal client** (`GrualinkClient`): creates requests with `Idempotency-Key`, reads the status,
  typed exceptions, retries with exponential backoff and a timeout. It uses `java.net.http.HttpClient`
  and a single runtime dependency: Jackson, for JSON.
- A **webhook receiver** (`Webhooks`, `WebhookHandler`, `WebhookHttpHandler`): verifies the signature
  over the raw body, checks the time window, supports secret rotation and drops duplicates.
- Two **runnable examples** (`src/main/java/mx/grualink/api/examples/`).
- The **contract kit** (`contract/`): the API contract in Spanish and English and the signature test
  cases shared by the examples in every language.

It is not an SDK: it is code meant to be read and copied into your project.

## Requirements

- Java 17 or later (tested on 17 and 21). You do not need to install Maven: the repository ships `mvnw`.
- A **test key** (`glk_test_…`). The towing company you work with gives it to you.
- For webhooks: the **signing secret** (`whsec_…`) the same company gives you when it registers the
  address of your system.

## Installation

```bash
git clone <this repository's address>
cd grualink-api-java
./mvnw -q compile
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

## Environment variables

Names only; the values live on your machine and never in the repository. There is a template in
`.env.example`.

| Variable | Purpose |
|---|---|
| `GRUALINK_API_KEY` | Your **test** key (`glk_test_…`) |
| `GRUALINK_WEBHOOK_SECRET` | Webhook signing secret (`whsec_…`), whole, with its prefix |
| `GRUALINK_WEBHOOK_SECRET_PREVIOUS` | Optional: the previous secret while a rotation is in progress |
| `GRUALINK_API_BASE_URL` | Optional: another base URL (default `https://grualink.mx/api/v1`) |
| `PORT` | Optional: port of the example receiver (default 8787) |

## Running the examples

### Request a tow truck

```bash
export GRUALINK_API_KEY=...   # your test key
./mvnw -q compile exec:java@request-tow
```

With fictitious data, the example sends a request, prints the estimated price, sends it again with the
same `Idempotency-Key` to show that nothing is duplicated, and reads its status. It only runs with test
keys.

With a test key, the start of `external_ref` decides the final status: `TEST-CONFIRMAR`,
`TEST-EN-CURSO`, `TEST-TERMINAR`, `TEST-RECHAZAR`, `TEST-CANCELAR`.

### Receive webhooks

```bash
export GRUALINK_WEBHOOK_SECRET=...   # your signing secret
./mvnw -q compile exec:java@webhook-server
```

It listens on `http://localhost:8787/webhooks/grualink`. GRUALINK only delivers to public `https`
addresses on port 443: publish the receiver behind your domain (or an `https` tunnel while you test),
give that address to the company and ask them to send you a test event.

## Using it in your code

```java
import mx.grualink.api.*;

GrualinkClient client = GrualinkClient.builder()
    .apiKey(System.getenv("GRUALINK_API_KEY"))
    .build();

ServiceRequestInput input = new ServiceRequestInput(
    "TEST-CONFIRMAR-0001",                                                    // external_ref
    new ServiceRequestInput.Contact("Persona de Ejemplo", "+525500000000"),
    null,                                                                     // vehicle (optional)
    ServiceRequestInput.Place.of("Av. Juárez 100, Centro, Guadalajara, Jal.", 20.6767, -103.3475),
    ServiceRequestInput.Place.of("Av. Patria 500, Zapopan, Jal.", 20.7214, -103.3918),
    null,                                                                     // service (optional)
    null);                                                                    // notes (optional)

try {
  // Store the idempotency key next to your record BEFORE calling.
  CreateServiceRequestResult result = client.createServiceRequest(input, myRecord.idempotencyKey());
  ServiceRequest serviceRequest = result.serviceRequest();
  System.out.println(serviceRequest.id() + " " + serviceRequest.status() + " " + result.replayed());
} catch (GrualinkValidationException e) {
  System.err.println(e.getDetails());
}
```

The field names in the JSON are the ones of the contract (`external_ref`, `service_request`…); in Java
they are read with the casing of the language (`externalRef()`, `serviceRequest()`).

### Idempotency and retries

- Every request carries an `Idempotency-Key` of yours. If you repeat the call with the same key and the
  same body within 24 hours, the API answers **`200`** with `Idempotent-Replayed: true` and creates
  nothing (`replayed()` is `true`). The same key with a different body answers `409`.
- The client retries only what makes sense: network errors, timeouts, `5xx` and `429 rate_limited`
  (waiting what `Retry-After` says). It uses exponential backoff with jitter and always resends the same
  key and the same bytes: the body is serialized only once.
- Every attempt has a timeout (15 seconds by default) and redirects are not followed.
- The key never appears in exceptions, logs or the client's `toString()`.

### Verifying a webhook

```java
SignatureVerificationResult result = Webhooks.verifyWebhookSignature(
    rawBody,                                    // the bytes exactly as received
    signatureHeader,                            // the X-GruaLink-Signature header
    System.getenv("GRUALINK_WEBHOOK_SECRET"));  // whole, with whsec_

if (!result.valid()) {
  // result.reason() says why; it is for your logs, not for the answer.
}
```

What matters:

1. Verify the **raw body** (`byte[]`), before parsing it as JSON. If you parse it and serialize it
   again, the signature no longer matches.
2. Use the **whole** secret, with its `whsec_` prefix.
3. Reject timestamps that differ by more than 5 minutes.
4. After a secret rotation two `v1` signatures arrive for 24 hours: accept if either matches.
5. Webhooks can repeat: keep the `id` of the ones you processed and answer `2xx` without processing
   again.
6. Answer `2xx` within 10 seconds and do the heavy work afterwards.

### Mounting it on your server

The delicate part is getting the **raw body**. `WebhookHandler` does not depend on any framework: it
takes the bytes and two headers and returns the status and the body of the answer.

With the HTTP server of the JDK (`com.sun.net.httpserver`), no dependencies: this is what the example
does.

```java
WebhookHandler handler = WebhookHandler.builder()
    .secret(System.getenv("GRUALINK_WEBHOOK_SECRET"))
    .onEvent(event -> queue.add(event))         // fast: store or enqueue
    .build();

HttpServer server = HttpServer.create(new InetSocketAddress(8787), 0);
server.createContext("/", new WebhookHttpHandler(handler, "/webhooks/grualink", null));
server.start();
```

With Spring Boot: ask for the body as `byte[]` (not as an object and not as a `String`), so nothing
converts it before it is verified.

```java
@RestController
class GrualinkWebhookController {
  private final WebhookHandler handler;   // a @Bean built as above

  GrualinkWebhookController(WebhookHandler handler) {
    this.handler = handler;
  }

  @PostMapping("/webhooks/grualink")
  ResponseEntity<String> receive(
      @RequestBody byte[] rawBody,
      @RequestHeader(value = "X-GruaLink-Signature", required = false) String signature,
      @RequestHeader(value = "X-GruaLink-Delivery", required = false) String deliveryId) {
    WebhookHandlerResult result = handler.handle(rawBody, signature, deliveryId);
    return ResponseEntity.status(result.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(result.bodyJson());
  }
}
```

Cap the body size on that route at 64 KB (a webhook is never larger) and do not let a filter read or
rewrite the body before the controller.

With several instances, replace `InMemoryDeliveryDeduplicator` with an implementation of
`DeliveryDeduplicator` backed by your database (a unique key on the event `id` is enough).

## Errors

Every API error has the shape `{ "error": { "code", "message", "details"? } }`. The `code` is stable;
branch on it (`getCode()`), not on the message.

| HTTP | `code` | What happened | Exception | Retried? |
|---|---|---|---|---|
| 400 | `invalid_request` | The request is not valid | `GrualinkInvalidRequestException` | no |
| 400 | `invalid_json` | The body is not valid JSON | `GrualinkInvalidRequestException` | no |
| 400 | `key_in_query` | The key was sent in the URL; have it revoked | `GrualinkInvalidRequestException` | no |
| 400 | `idempotency_key_required` | `Idempotency-Key` is missing | `GrualinkInvalidRequestException` | no |
| 400 | `invalid_idempotency_key` | Invalid `Idempotency-Key` (1 to 255 visible ASCII characters) | `GrualinkInvalidRequestException` | no |
| 401 | `unauthorized` | Key missing, invalid, expired or revoked | `GrualinkAuthenticationException` | no |
| 403 | `insufficient_scope` | The key is not allowed to do this | `GrualinkPermissionException` | no |
| 403 | `customer_unavailable` | Your customer account with the company is not available | `GrualinkPermissionException` | no |
| 403 | `live_mode_unavailable` | Live requests are not enabled yet | `GrualinkPermissionException` | no |
| 403 | `browser_not_allowed` | A live key was used from a browser | `GrualinkPermissionException` | no |
| 404 | `not_found` | It does not exist or is not yours | `GrualinkNotFoundException` | no |
| 405 | `method_not_allowed` | Method not allowed for that path | `GrualinkInvalidRequestException` | no |
| 409 | `idempotency_conflict` | That `Idempotency-Key` was already used with another body | `GrualinkIdempotencyConflictException` | no |
| 413 | `payload_too_large` | The body exceeds 16 KB | `GrualinkInvalidRequestException` | no |
| 415 | `unsupported_media_type` | The body must be sent as `application/json` | `GrualinkInvalidRequestException` | no |
| 422 | `validation_error` | Invalid fields; `getDetails()` says which | `GrualinkValidationException` | no |
| 429 | `rate_limited` | Too many requests; wait `Retry-After` | `GrualinkRateLimitException` | yes |
| 429 | `too_many_open_requests` | Too many unresolved requests with the company | `GrualinkRateLimitException` | no |
| 500 | `internal_error` | Internal error | `GrualinkServerException` | yes |

All of them are unchecked exceptions (`RuntimeException`) in this hierarchy:

```
GrualinkException                        base of all; getAttempts()
├── GrualinkConfigException              invalid configuration or argument; nothing was sent
├── GrualinkConnectionException          no response (network); retried
├── GrualinkTimeoutException             timed out; retried
└── GrualinkApiException                 the API answered an error: getStatus(), getCode(), getMessage(),
    │                                    getDetails(), getRetryAfterSeconds(), getAttempts()
    ├── GrualinkInvalidRequestException       400, 405, 413, 415
    ├── GrualinkAuthenticationException       401
    ├── GrualinkPermissionException           403
    ├── GrualinkNotFoundException             404
    ├── GrualinkIdempotencyConflictException  409
    ├── GrualinkValidationException           422
    ├── GrualinkRateLimitException            429
    └── GrualinkServerException               5xx
```

They are the names the contract uses for every language (`GrualinkError`, `GrualinkApiError`,
`GrualinkValidationError`…), with the `Exception` suffix that is customary in Java. An error answer
whose body is not the documented shape uses the code `unexpected_response`.

The full contract, with fields, limits and the order of checks, is in
[`contract/API.md`](contract/API.md).

## Tests

```bash
./mvnw verify
```

It compiles with every compiler warning as an error and runs the tests (JUnit 5). The tests never call
the real API: they use a local fake server. They cover every case of `contract/signature-vectors.json`,
each error code, retries, idempotency, that the key appears in no exception or log output, and the two
examples running end to end against the fake server.

### Trying the examples without a key

The fake server of the test suite also runs as a process. Use it to see the examples work before you
have your test key; it is not the real API and it always answers the same fictitious request.

```bash
./mvnw -q test-compile exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=mx.grualink.api.FakeApiServer -Dexec.args=8089
```

In another terminal:

```bash
export GRUALINK_API_BASE_URL=http://127.0.0.1:8089/api/v1
export GRUALINK_API_KEY=glk_test_FAKE_ONLY_FOR_THE_FAKE_SERVER
./mvnw -q compile exec:java@request-tow
```

## Layout

```
contract/                                 API contract (es/en), signature test cases and checklist
src/main/java/mx/grualink/api/            client, exceptions, types, webhook verification and receiver
src/main/java/mx/grualink/api/examples/   RequestTow and WebhookServer
src/test/java/mx/grualink/api/            tests (JUnit 5) and fake server
```

`contract/` is an unmodified copy of the kit shared by every language; continuous integration checks
that it has not been changed.

## License

[MIT](LICENSE)
