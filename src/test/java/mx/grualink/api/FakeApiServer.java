package mx.grualink.api;

import java.io.IOException;

/**
 * Runs the fake API as a process, to try the examples by hand or from a script without calling the real API:
 *
 * <pre>
 *   ./mvnw -q test-compile exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=mx.grualink.api.FakeApiServer -Dexec.args=8089
 * </pre>
 */
public final class FakeApiServer {
  private FakeApiServer() {}

  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8089;
    FakeApi api = FakeApi.start(port);
    api.reply(FakeApi.scripted());
    System.out.println("Fake API listening on " + api.baseUrl());
  }
}
