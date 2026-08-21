package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingProviderFailureProcessTest {
  @TempDir Path tempDir;

  @Test
  void isolatedJvmContinuesWhenProviderIsNop() throws Exception {
    Path work = Files.createTempDirectory(tempDir, "nop-work");
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
                "-cp",
                classPath,
                LoggingProviderFailureProbe.class.getName(),
                work.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    assertTrue(output.contains("CONTINUED"), output);
    assertTrue(output.contains(LoggingRuntime.STDERR_PREFIX), output);
  }
}
