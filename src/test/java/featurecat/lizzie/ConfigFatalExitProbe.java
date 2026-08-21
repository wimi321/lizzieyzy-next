package featurecat.lizzie;

import featurecat.lizzie.logging.CrashHandlers;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONException;

public final class ConfigFatalExitProbe {
  public static void main(String[] args) {
    Path work = Path.of(args[0]);
    LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    CrashHandlers.install();
    Config.failClosed("config", new JSONException("config-exit-canary"));
  }
}
