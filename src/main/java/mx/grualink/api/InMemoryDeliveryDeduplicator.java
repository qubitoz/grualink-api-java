package mx.grualink.api;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * In-memory deduplication for a single process. Retries of one delivery span up to 24 hours, so ids are kept
 * for 48 hours by default. With several instances, use a shared store (a unique key in your database is enough).
 */
public final class InMemoryDeliveryDeduplicator implements DeliveryDeduplicator {
  private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
  private final long ttlMillis;
  private final int maxEntries;
  private final LongSupplier clockMillis;

  public InMemoryDeliveryDeduplicator() {
    this(Duration.ofHours(48), 50_000, System::currentTimeMillis);
  }

  /**
   * @param ttl how long an id is remembered
   * @param maxEntries the oldest ids are forgotten beyond this size
   * @param clockMillis clock in milliseconds, for tests
   */
  public InMemoryDeliveryDeduplicator(Duration ttl, int maxEntries, LongSupplier clockMillis) {
    this.ttlMillis = ttl.toMillis();
    this.maxEntries = maxEntries;
    this.clockMillis = clockMillis;
  }

  @Override
  public synchronized boolean claim(String deliveryId) {
    long now = clockMillis.getAsLong();
    // Entries are in insertion order: drop the expired ones, and the oldest while the map is full.
    Iterator<Map.Entry<String, Long>> entries = seen.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<String, Long> entry = entries.next();
      if (entry.getValue() > now && seen.size() < maxEntries) {
        break;
      }
      entries.remove();
    }
    Long expiresAt = seen.get(deliveryId);
    if (expiresAt != null && expiresAt > now) {
      return false;
    }
    seen.remove(deliveryId);
    seen.put(deliveryId, now + ttlMillis);
    return true;
  }

  @Override
  public synchronized void release(String deliveryId) {
    seen.remove(deliveryId);
  }
}
