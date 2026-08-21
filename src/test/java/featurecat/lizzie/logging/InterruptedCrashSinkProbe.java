package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;

public final class InterruptedCrashSinkProbe {
  public static void main(String[] args) {
    Path work = Path.of(args[0]);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    runtime.delayNestedAppendForTests(LogStream.APP, 700);
    runtime.delayNestedAppendForTests(LogStream.CRASH, 700);
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> System.exit(1));
    CrashHandlers.install();
    Thread.currentThread().interrupt();
    throw new IllegalStateException("interrupted-sink-canary");
  }
}
