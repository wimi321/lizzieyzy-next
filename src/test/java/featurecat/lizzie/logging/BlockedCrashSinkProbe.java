package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class BlockedCrashSinkProbe {
  public static void main(String[] args) {
    Path work = Path.of(args[0]);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    runtime.awaitIdle();
    CountDownLatch gate = new CountDownLatch(1);
    runtime.blockPersistenceForTests(LogStream.APP, gate);
    runtime.blockPersistenceForTests(LogStream.CRASH, gate);
    CrashHandlers.install();
    CrashHandlers.recordFatal(new IllegalStateException("blocked-sink-canary"));
    System.exit(1);
  }
}
