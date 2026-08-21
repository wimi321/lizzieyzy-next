package featurecat.lizzie.logging;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;

public final class StartupEvidenceProbe {
  public static void main(String[] args) throws Exception {
    Lizzie.bootstrapLogging();
    Config config = new Config();
    LoggingRuntime.current().ifPresent(runtime -> runtime.applySettings(config.loggingSettings));
    Lizzie.logPersistedLoggingSettingsApplied();
    Lizzie.logApplicationReady();
    Lizzie.logShutdownRequested();
    LoggingRuntime.current().ifPresent(LoggingRuntime::awaitIdle);
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    System.out.println("STARTED");
  }
}
