package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;

public final class BackgroundUncaughtExceptionProbe {
  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    CrashHandlers.install();
    Thread worker =
        new Thread(
            () -> {
              throw new IllegalStateException("BG_UNCAUGHT_CANARY");
            },
            "bg-canary-thread");
    worker.start();
    worker.join();
    runtime.awaitIdle();
    System.out.println("CONTINUED");
  }
}
