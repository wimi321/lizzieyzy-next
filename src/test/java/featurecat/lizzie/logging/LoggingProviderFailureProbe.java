package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;
import org.slf4j.helpers.NOPLoggerFactory;

public final class LoggingProviderFailureProbe {
  public static void main(String[] args) {
    try {
      LoggingRuntime.initialize(
          new WorkDirectoryResolution(Path.of(args[0]), List.of()),
          LoggingLimits.production(),
          new NOPLoggerFactory());
      System.out.println("CONTINUED");
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
