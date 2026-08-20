package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;

public final class DelayedCrashSinkProbe {
  public static void main(String[] args) {
    Path work = Path.of(args[0]);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    runtime.delayNestedAppendForTests(LogStream.APP, 700);
    runtime.delayNestedAppendForTests(LogStream.CRASH, 700);
    CrashHandlers.install();
    CrashHandlers.recordFatal(new IllegalStateException("slow-sink-canary"));
    System.exit(1);
  }
}
