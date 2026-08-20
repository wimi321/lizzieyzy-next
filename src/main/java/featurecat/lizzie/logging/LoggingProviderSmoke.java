package featurecat.lizzie.logging;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

public final class LoggingProviderSmoke {
  public static void main(String[] args) {
    int providers = 0;
    for (SLF4JServiceProvider ignored : ServiceLoader.load(SLF4JServiceProvider.class)) {
      providers++;
    }
    if (providers != 1) {
      System.err.println("expected one SLF4J provider, found " + providers);
      System.exit(2);
    }
    ILoggerFactory factory = LoggerFactory.getILoggerFactory();
    if (factory.getClass().getName().contains("NOP")) {
      System.err.println("NOP provider is not allowed: " + factory.getClass().getName());
      System.exit(3);
    }
    Path workDirectory = Path.of(args[0]);
    WorkDirectoryResolution resolution = new WorkDirectoryResolution(workDirectory, List.of());
    LoggingRuntime first = LoggingRuntime.initialize(resolution);
    LoggingRuntime second = LoggingRuntime.initialize(resolution);
    if (first != second) {
      System.err.println("repeated initialize created a second runtime");
      System.exit(4);
    }
    LoggerFactory.getLogger(LogCategories.APP).info("provider-smoke");
    first.awaitIdle();
    first.shutdown();
  }
}
