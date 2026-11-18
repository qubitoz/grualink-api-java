package mx.grualink.api;

/**
 * @param serviceRequest the request, new or original
 * @param replayed {@code true} when the API answered 200 with {@code Idempotent-Replayed: true} (nothing new
 *     was created)
 * @param idempotencyKey the key that was sent (yours or the generated one)
 */
public record CreateServiceRequestResult(ServiceRequest serviceRequest, boolean replayed, String idempotencyKey) {}
