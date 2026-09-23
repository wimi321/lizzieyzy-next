package featurecat.lizzie.analysis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only access to the production command acknowledgement; no polling UI flags as an ACK. */
public final class PerformanceProbeEngineAccess {
  private PerformanceProbeEngineAccess() {}

  public static Process process(Leelaz engine) throws ReflectiveOperationException {
    java.lang.reflect.Field process = Leelaz.class.getDeclaredField("process");
    process.setAccessible(true);
    return (Process) process.get(engine);
  }

  public static void barrier(Leelaz engine, String command)
      throws InterruptedException, ReflectiveOperationException {
    // Production callbacks settle both '=' and '?' responses. Read their scoped result while the
    // callback is active, before Leelaz resets it; receiving an error is never successful
    // preparation.
    java.lang.reflect.Field errorField =
        Leelaz.class.getDeclaredField("currentCommandResponseError");
    java.lang.reflect.Field lineField = Leelaz.class.getDeclaredField("currentCommandResponseLine");
    errorField.setAccessible(true);
    lineField.setAccessible(true);
    AtomicReference<String> failure = new AtomicReference<>();
    CountDownLatch response = new CountDownLatch(1);
    engine.sendCommandWithResponseForTest(
        command,
        () -> {
          try {
            String line = (String) lineField.get(engine);
            if (errorField.getBoolean(engine) || line == null || !line.startsWith("="))
              failure.set("Engine command was not successful: " + command + ": " + line);
          } catch (ReflectiveOperationException error) {
            failure.set("Cannot inspect engine acknowledgement: " + error);
          } finally {
            response.countDown();
          }
        });
    if (!response.await(30, TimeUnit.SECONDS)) {
      throw new AssertionError("Engine acknowledgement timeout: " + command);
    }
    if (failure.get() != null) throw new AssertionError(failure.get());
  }
}
