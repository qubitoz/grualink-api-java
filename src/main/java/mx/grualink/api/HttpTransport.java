package mx.grualink.api;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Sends one HTTP attempt. The default implementation uses {@link java.net.http.HttpClient}; replace it only
 * in tests. An implementation must not follow redirects and must throw
 * {@link java.net.http.HttpTimeoutException} when the timeout of the request elapses.
 */
@FunctionalInterface
public interface HttpTransport {
  HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException;
}
