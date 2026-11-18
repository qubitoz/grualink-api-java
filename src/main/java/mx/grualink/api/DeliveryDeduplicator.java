package mx.grualink.api;

/** Remembers which deliveries were already processed. Implement it over your database for several instances. */
public interface DeliveryDeduplicator {
  /** Returns {@code true} the first time an id is seen and {@code false} for a duplicate. */
  boolean claim(String deliveryId);

  /** Forgets an id so a later retry is processed again (called when processing failed). */
  void release(String deliveryId);
}
