package featurecat.lizzie.analysis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only access to the production command acknowledgement; no polling UI flags as an ACK. */
public final class PerformanceProbeEngineAccess {
  private PerformanceProbeEngineAccess() {}

  public static void barrier(Leelaz engine, String command) throws InterruptedException {
    CountDownLatch response = new CountDownLatch(1);
    engine.sendCommandWithResponseForTest(command, response::countDown);
    if (!response.await(30, TimeUnit.SECONDS)) {
      throw new AssertionError("Engine acknowledgement timeout: " + command);
    }
  }
}
