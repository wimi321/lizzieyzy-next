package featurecat.lizzie.logging;

import featurecat.lizzie.gui.LoadEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class LoadEngineNormalExitProbe {
  public static void main(String[] args) throws Exception {
    Path work = Path.of(args[0]);
    Path marker = Path.of(args[1]);
    LoggingRuntime.initialize(new WorkDirectoryResolution(work, List.of()));
    int[] calls = {0};
    LoadEngine.requestNormalExit(
        code -> {
          calls[0]++;
          try {
            Files.writeString(
                marker,
                "calls="
                    + calls[0]
                    + "\ncode="
                    + code
                    + "\nshutdown="
                    + LoggingRuntime.current().orElseThrow().isShutdown());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    LoadEngine.requestNormalExit(
        code -> {
          calls[0]++;
          try {
            Files.writeString(
                marker,
                "calls="
                    + calls[0]
                    + "\ncode="
                    + code
                    + "\nshutdown="
                    + LoggingRuntime.current().orElseThrow().isShutdown());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }
}
