# GRUALINK API: ejemplo en Java

[English](README.en.md)

Ejemplo listo para clonar y correr que integra tu sistema (aseguradora, asistencia, integrador) con la
API de GRUALINK: **pedir una grúa**, **consultar el estatus** y **recibir avisos firmados**.

Guía pública de la API: <https://grualink.mx/api>

Qué trae:

- Un **cliente mínimo** (`GrualinkClient`): crea solicitudes con `Idempotency-Key`, consulta el estatus,
  excepciones tipadas, reintentos con espera exponencial y tiempo máximo. Usa `java.net.http.HttpClient`
  y una sola dependencia de ejecución: Jackson, para el JSON.
- Un **receptor de avisos** (`Webhooks`, `WebhookHandler`, `WebhookHttpHandler`): verifica la firma sobre
  el cuerpo crudo, valida la ventana de tiempo, soporta el cambio de secreto y descarta duplicados.
- Dos **ejemplos ejecutables** (`src/main/java/mx/grualink/api/examples/`).
- El **kit de contrato** (`contract/`): el contrato de la API en español e inglés y los casos de prueba
  de firma que comparten los ejemplos de todos los lenguajes.

No es un SDK: es código pensado para leerse y copiarse a tu proyecto.

## Requisitos

- Java 17 o superior (probado en 17 y 21). No necesitas instalar Maven: el repositorio trae `mvnw`.
- Una **llave de prueba** (`glk_test_…`). Te la da la empresa de grúas con la que trabajas.
- Para los avisos: el **secreto de firma** (`whsec_…`) que te entrega la misma empresa al registrar la
  dirección de tu sistema.

## Instalación

```bash
git clone <la dirección de este repositorio>
cd grualink-api-java
./mvnw -q compile
```

En Windows usa `mvnw.cmd` en lugar de `./mvnw`.

## Variables de entorno

Solo los nombres; los valores van en tu equipo y nunca en el repositorio. Hay una plantilla en
`.env.example`.

| Variable | Para qué |
|---|---|
| `GRUALINK_API_KEY` | Tu llave de **prueba** (`glk_test_…`) |
| `GRUALINK_WEBHOOK_SECRET` | Secreto de firma de los avisos (`whsec_…`), completo, con su prefijo |
| `GRUALINK_WEBHOOK_SECRET_PREVIOUS` | Opcional: el secreto anterior mientras dura un cambio de secreto |
| `GRUALINK_API_BASE_URL` | Opcional: otra dirección base (por omisión `https://grualink.mx/api/v1`) |
| `PORT` | Opcional: puerto del receptor de ejemplo (por omisión 8787) |

## Correr los ejemplos

### Pedir una grúa

```bash
export GRUALINK_API_KEY=...   # tu llave de prueba
./mvnw -q compile exec:java@request-tow
```

Con datos ficticios, el ejemplo envía una solicitud, imprime el precio estimado, la vuelve a enviar con
la misma `Idempotency-Key` para mostrar que no se duplica y consulta su estatus. Solo corre con llaves
de prueba.

Con una llave de prueba, el inicio de `external_ref` decide el estatus final: `TEST-CONFIRMAR`,
`TEST-EN-CURSO`, `TEST-TERMINAR`, `TEST-RECHAZAR`, `TEST-CANCELAR`.

### Recibir avisos

```bash
export GRUALINK_WEBHOOK_SECRET=...   # tu secreto de firma
./mvnw -q compile exec:java@webhook-server
```

Queda escuchando en `http://localhost:8787/webhooks/grualink`. GRUALINK solo entrega a direcciones
`https` públicas en el puerto 443: publica el receptor detrás de tu dominio (o de un túnel `https` mientras
pruebas), dale esa dirección a la empresa y pídele que te mande un aviso de prueba.

## Uso en tu código

```java
import mx.grualink.api.*;

GrualinkClient client = GrualinkClient.builder()
    .apiKey(System.getenv("GRUALINK_API_KEY"))
    .build();

ServiceRequestInput input = new ServiceRequestInput(
    "TEST-CONFIRMAR-0001",                                                    // external_ref
    new ServiceRequestInput.Contact("Persona de Ejemplo", "+525500000000"),
    null,                                                                     // vehicle (opcional)
    ServiceRequestInput.Place.of("Av. Juárez 100, Centro, Guadalajara, Jal.", 20.6767, -103.3475),
    ServiceRequestInput.Place.of("Av. Patria 500, Zapopan, Jal.", 20.7214, -103.3918),
    null,                                                                     // service (opcional)
    null);                                                                    // notes (opcional)

try {
  // Guarda la clave de idempotencia junto a tu registro ANTES de llamar.
  CreateServiceRequestResult result = client.createServiceRequest(input, miRegistro.claveDeIdempotencia());
  ServiceRequest serviceRequest = result.serviceRequest();
  System.out.println(serviceRequest.id() + " " + serviceRequest.status() + " " + result.replayed());
} catch (GrualinkValidationException e) {
  System.err.println(e.getDetails());
}
```

Los nombres de los campos en el JSON son los del contrato (`external_ref`, `service_request`…); en Java
se leen con el estilo del lenguaje (`externalRef()`, `serviceRequest()`).

### Idempotencia y reintentos

- Cada solicitud lleva una `Idempotency-Key` tuya. Si repites la llamada con la misma clave y el mismo
  cuerpo en 24 horas, la API responde **`200`** con `Idempotent-Replayed: true` y no crea nada
  (`replayed()` es `true`). La misma clave con otro cuerpo responde `409`.
- El cliente reintenta solo lo que tiene sentido: errores de red, tiempo agotado, `5xx` y
  `429 rate_limited` (esperando lo que diga `Retry-After`). Usa espera exponencial con jitter y reenvía
  siempre la misma clave y los mismos bytes: el cuerpo se serializa una sola vez.
- Cada intento tiene tiempo máximo (15 segundos por omisión) y no se siguen redirecciones.
- La llave nunca aparece en excepciones, registros ni en `toString()` del cliente.

### Verificar un aviso

```java
SignatureVerificationResult result = Webhooks.verifyWebhookSignature(
    rawBody,                                    // los bytes tal como llegaron
    signatureHeader,                            // cabecera X-GruaLink-Signature
    System.getenv("GRUALINK_WEBHOOK_SECRET"));  // completo, con whsec_

if (!result.valid()) {
  // result.reason() dice por qué; es para tus registros, no para la respuesta.
}
```

Lo importante:

1. Verifica el **cuerpo crudo** (`byte[]`), antes de convertirlo a JSON. Si lo conviertes y lo vuelves a
   serializar, la firma ya no coincide.
2. Usa el secreto **completo**, con su prefijo `whsec_`.
3. Rechaza marcas de tiempo con más de 5 minutos de diferencia.
4. Tras un cambio de secreto llegan dos firmas `v1` durante 24 horas: acepta si cualquiera coincide.
5. Los avisos pueden repetirse: guarda el `id` de los ya procesados y responde `2xx` sin reprocesar.
6. Responde `2xx` en menos de 10 segundos y haz el trabajo pesado después.

### Montarlo en tu servidor

El punto delicado es conseguir el **cuerpo crudo**. `WebhookHandler` no depende de ningún framework:
recibe los bytes y dos cabeceras, y devuelve el estatus y el cuerpo de la respuesta.

Con el servidor HTTP del JDK (`com.sun.net.httpserver`), sin dependencias: es lo que hace el ejemplo.

```java
WebhookHandler handler = WebhookHandler.builder()
    .secret(System.getenv("GRUALINK_WEBHOOK_SECRET"))
    .onEvent(event -> cola.add(event))          // rápido: guarda o encola
    .build();

HttpServer server = HttpServer.create(new InetSocketAddress(8787), 0);
server.createContext("/", new WebhookHttpHandler(handler, "/webhooks/grualink", null));
server.start();
```

Con Spring Boot: pide el cuerpo como `byte[]` (no como un objeto ni como `String`), para que nada lo
convierta antes de verificar.

```java
@RestController
class GrualinkWebhookController {
  private final WebhookHandler handler;   // un @Bean construido como arriba

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

Limita el tamaño del cuerpo en esa ruta a 64 KB (un aviso nunca es mayor) y no dejes que un filtro lea
o reescriba el cuerpo antes del controlador.

Con varias instancias, sustituye `InMemoryDeliveryDeduplicator` por una implementación de
`DeliveryDeduplicator` que use tu base de datos (basta una clave única sobre el `id` del aviso).

## Errores

Todos los errores de la API tienen la forma `{ "error": { "code", "message", "details"? } }`. El `code`
es estable; decide con él (`getCode()`), no con el mensaje.

| HTTP | `code` | Qué pasó | Excepción | ¿Se reintenta? |
|---|---|---|---|---|
| 400 | `invalid_request` | La petición no es válida | `GrualinkInvalidRequestException` | no |
| 400 | `invalid_json` | El cuerpo no es JSON válido | `GrualinkInvalidRequestException` | no |
| 400 | `key_in_query` | La llave se envió en la URL; pide que la revoquen | `GrualinkInvalidRequestException` | no |
| 400 | `idempotency_key_required` | Falta `Idempotency-Key` | `GrualinkInvalidRequestException` | no |
| 400 | `invalid_idempotency_key` | `Idempotency-Key` inválida (1 a 255 caracteres ASCII visibles) | `GrualinkInvalidRequestException` | no |
| 401 | `unauthorized` | Llave ausente, inválida, vencida o revocada | `GrualinkAuthenticationException` | no |
| 403 | `insufficient_scope` | La llave no tiene permiso para esto | `GrualinkPermissionException` | no |
| 403 | `customer_unavailable` | Tu cuenta de cliente con la empresa no está disponible | `GrualinkPermissionException` | no |
| 403 | `live_mode_unavailable` | Las solicitudes reales aún no están habilitadas | `GrualinkPermissionException` | no |
| 403 | `browser_not_allowed` | Llave real usada desde un navegador | `GrualinkPermissionException` | no |
| 404 | `not_found` | No existe o no es tuya | `GrualinkNotFoundException` | no |
| 405 | `method_not_allowed` | Método no permitido para esa ruta | `GrualinkInvalidRequestException` | no |
| 409 | `idempotency_conflict` | Esa `Idempotency-Key` ya se usó con otro cuerpo | `GrualinkIdempotencyConflictException` | no |
| 413 | `payload_too_large` | El cuerpo excede 16 KB | `GrualinkInvalidRequestException` | no |
| 415 | `unsupported_media_type` | El cuerpo debe ir como `application/json` | `GrualinkInvalidRequestException` | no |
| 422 | `validation_error` | Campos inválidos; `getDetails()` dice cuáles | `GrualinkValidationException` | no |
| 429 | `rate_limited` | Demasiadas peticiones; espera `Retry-After` | `GrualinkRateLimitException` | sí |
| 429 | `too_many_open_requests` | Demasiadas solicitudes sin resolver con la empresa | `GrualinkRateLimitException` | no |
| 500 | `internal_error` | Error interno | `GrualinkServerException` | sí |

Todas son excepciones no comprobadas (`RuntimeException`) con esta jerarquía:

```
GrualinkException                        base de todas; getAttempts()
├── GrualinkConfigException              configuración o argumento inválido; no se envió nada
├── GrualinkConnectionException          sin respuesta (red); se reintenta
├── GrualinkTimeoutException             tiempo agotado; se reintenta
└── GrualinkApiException                 la API respondió un error: getStatus(), getCode(), getMessage(),
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

Son los mismos nombres que usa el contrato para todos los lenguajes (`GrualinkError`,
`GrualinkApiError`, `GrualinkValidationError`…), con el sufijo `Exception` que se acostumbra en Java.
Una respuesta de error cuyo cuerpo no tiene la forma documentada usa el código `unexpected_response`.

El contrato completo, con campos, límites y el orden de las comprobaciones, está en
[`contract/API.es.md`](contract/API.es.md).

## Pruebas

```bash
./mvnw verify
```

Compila con todas las advertencias del compilador como errores y corre las pruebas (JUnit 5). Las
pruebas no llaman a la API real: usan un servidor falso local. Cubren todos los casos de
`contract/signature-vectors.json`, cada código de error, los reintentos, la idempotencia, que la llave
no aparezca en excepciones ni registros, y los dos ejemplos corriendo de punta a punta contra el
servidor falso.

### Probar los ejemplos sin llave

El servidor falso de las pruebas también corre como proceso. Sirve para ver los ejemplos funcionar antes
de tener tu llave de prueba; no es la API real y siempre responde la misma solicitud ficticia.

```bash
./mvnw -q test-compile exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=mx.grualink.api.FakeApiServer -Dexec.args=8089
```

En otra terminal:

```bash
export GRUALINK_API_BASE_URL=http://127.0.0.1:8089/api/v1
export GRUALINK_API_KEY=glk_test_FAKE_SOLO_PARA_EL_SERVIDOR_FALSO
./mvnw -q compile exec:java@request-tow
```

## Estructura

```
contract/                                 contrato de la API (es/en), casos de prueba de firma y lista de verificación
src/main/java/mx/grualink/api/            cliente, excepciones, tipos, verificación de avisos y receptor
src/main/java/mx/grualink/api/examples/   RequestTow y WebhookServer
src/test/java/mx/grualink/api/            pruebas (JUnit 5) y servidor falso
```

`contract/` es una copia sin cambios del kit que comparten todos los lenguajes; la integración continua
comprueba que no se haya modificado.

## Licencia

[MIT](LICENSE)
