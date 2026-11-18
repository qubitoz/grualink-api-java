package mx.grualink.api;

/**
 * Receives each verified, non-duplicate delivery. Keep it fast (GRUALINK waits 10 seconds): store the event or
 * enqueue it and do the heavy work later. If it throws, the answer is 500 and the delivery is retried.
 */
@FunctionalInterface
public interface WebhookEventListener {
  void onEvent(WebhookEvent event) throws Exception;
}
