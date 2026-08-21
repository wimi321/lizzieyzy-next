package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;

public final class CrashLoggingFailureProbe {
  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    runtime.failWritesForTests(LogStream.APP, true);
    runtime.failWritesForTests(LogStream.CRASH, true);
    CrashHandlers.install();
    Thread worker =
        new Thread(
            () -> {
              throw new IllegalStateException("fail-canary");
            },
            "fail-canary-thread");
    worker.start();
    worker.join();
    runtime.awaitIdle();
    LoggingStatus status = runtime.status();
    System.out.println("CONTINUED");
    System.out.println("persistence=" + status.persistenceEnabled());
    status
        .stream(LogStream.CRASH)
        .ifPresent(stream -> System.out.println("crash-reason=" + stream.reason()));
    status
        .stream(LogStream.APP)
        .ifPresent(stream -> System.out.println("app-reason=" + stream.reason()));
  }
}
