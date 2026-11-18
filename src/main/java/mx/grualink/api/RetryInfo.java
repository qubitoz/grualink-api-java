package mx.grualink.api;

/**
 * Passed to the {@code onRetry} callback before each wait.
 *
 * @param attempt attempt that just failed, starting at 1
 * @param delayMillis time the client will wait before the next attempt
 * @param error why it failed; never contains the API key
 */
public record RetryInfo(int attempt, long delayMillis, GrualinkException error) {}
