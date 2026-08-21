package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingProviderSmokeIT {
  @TempDir Path tempDir;

  @Test
  void shadedArtifactWritesOneProviderEvent() throws Exception {
    String configured = System.getProperty("lizzie.shaded.jar", "");
    assertFalse(configured.isBlank(), "lizzie.shaded.jar must be set by failsafe");
    Path shaded = Path.of(configured);
    assertTrue(Files.isRegularFile(shaded), "shaded artifact missing: " + shaded.toAbsolutePath());

    Path work = Files.createTempDirectory(tempDir, "shaded-smoke");
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                shaded.toAbsolutePath().toString(),
                LoggingProviderSmoke.class.getName(),
                work.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output + "\njar=" + shaded.toAbsolutePath());
    String appLog = Files.readString(work.resolve("logs/app.log"));
    assertTrue(appLog.contains("provider-smoke"), appLog);
    assertEquals(1, count(appLog, "provider-smoke"));
    assertEquals(1, count(appLog, "application log session started"));
    System.out.println("shaded-smoke-jar=" + shaded.toAbsolutePath());
  }

  private static int count(String text, String token) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }
}
