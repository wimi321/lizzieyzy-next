package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadEngineNormalExitProcessTest {
  @TempDir Path tempDir;

  @Test
  void engineSelectionExitShutsDownLoggingOnceWithZeroCode() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "exit-work");
    Path marker = tempDir.resolve("exit-marker.txt");
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classPath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path", ""));
    Process process =
        new ProcessBuilder(
                java,
                "-Djava.awt.headless=true",
                "-cp",
                classPath,
                LoadEngineNormalExitProbe.class.getName(),
                work.toAbsolutePath().toString(),
                marker.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    String recorded = Files.readString(marker);
    assertTrue(recorded.contains("calls=2"), recorded);
    assertTrue(recorded.contains("code=0"), recorded);
    assertTrue(recorded.contains("shutdown=true"), recorded);
  }
}
