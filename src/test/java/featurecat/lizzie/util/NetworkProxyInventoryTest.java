package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkProxyInventoryTest {
  private static final Path MAIN_SOURCES = Path.of("src/main/java");
  private static final String HELPER = "featurecat/lizzie/util/NetworkProxy.java";
  private static final String UPDATER = "featurecat/lizzie/update/WindowsUpdateService.java";
  private static final String ONLINE_DIALOG = "featurecat/lizzie/gui/OnlineDialog.java";

  @Test
  void outboundJavaNetworkCallersUseNetworkProxyHelper() throws IOException {
    List<String> violations = new ArrayList<>();
    try (var paths = Files.walk(MAIN_SOURCES)) {
      paths
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> collectNetworkViolations(path, violations));
    }

    assertTrue(
        violations.isEmpty(),
        "Outbound Java HTTP/WebSocket callers must use NetworkProxy:\n"
            + String.join("\n", violations));
  }

  @Test
  void windowsUpdaterUsesSharedProxyOpener() throws IOException {
    String source = Files.readString(MAIN_SOURCES.resolve(UPDATER));

    assertTrue(source.contains("NetworkProxy.openConnection("));
    assertTrue(!source.contains(".toURL().openConnection("));
  }

  private static void collectNetworkViolations(Path path, List<String> violations) {
    String relative = MAIN_SOURCES.relativize(path).toString().replace('\\', '/');
    try {
      String source = Files.readString(path);
      boolean onlineDialogConfiguresWebSocket =
          ONLINE_DIALOG.equals(relative) && source.contains("NetworkProxy.configure(client)");
      String[] lines = source.split("\\R", -1);
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (isNetworkPattern(line)
            && !isAllowedNetworkPattern(relative, line, onlineDialogConfiguresWebSocket)) {
          violations.add(relative + ":" + (i + 1) + ": " + line.trim());
        }
      }
    } catch (IOException e) {
      throw new AssertionError("Failed to read " + path, e);
    }
  }

  private static boolean isNetworkPattern(String line) {
    return line.contains(".openConnection(")
        || line.contains("Proxy.NO_PROXY")
        || line.contains("HttpClient.newBuilder()")
        || line.contains("new OkHttpClient()")
        || line.contains("new OkHttpClient.Builder()")
        || line.contains("new WebSocketClient(");
  }

  private static boolean isAllowedNetworkPattern(
      String relative, String line, boolean onlineDialogConfiguresWebSocket) {
    if (HELPER.equals(relative)) {
      return true;
    }
    if (line.contains("NetworkProxy.openConnection(")
        || line.contains("NetworkProxy.configure(HttpClient.newBuilder())")
        || line.contains("NetworkProxy.configure(new OkHttpClient.Builder())")) {
      return true;
    }
    return onlineDialogConfiguresWebSocket && line.contains("new WebSocketClient(");
  }
}
