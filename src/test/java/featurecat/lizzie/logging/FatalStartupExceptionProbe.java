package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;

public final class FatalStartupExceptionProbe {
  public static void main(String[] args) {
    Path work = Path.of(args[0]);
    LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    CrashHandlers.install();
    throw new IllegalStateException("fatal-canary");
  }
}
