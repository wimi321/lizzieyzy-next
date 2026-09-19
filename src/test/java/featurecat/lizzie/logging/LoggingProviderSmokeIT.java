package featurecat.lizzie.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    String configuredJava = System.getProperty("lizzie.test.java.executable", "");
    Path java =
        configuredJava.isBlank()
            ? Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            : Path.of(configuredJava);
    assertTrue(Files.isRegularFile(java), "child Java executable missing: " + java.toAbsolutePath());
    assertTrue(Files.isExecutable(java), "child Java is not executable: " + java.toAbsolutePath());

    Path versionLog = work.resolve("java-version.log");
    Process versionProcess =
        new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version")
            .redirectErrorStream(true)
            .redirectOutput(versionLog.toFile())
            .start();
    assertTrue(
        waitForOrTerminate(versionProcess, 15),
        "child Java -version timed out; process was terminated");
    String versionOutput = Files.readString(versionLog, StandardCharsets.UTF_8);
    assertEquals(0, versionProcess.exitValue(), versionOutput);
    Matcher versionMatcher =
        Pattern.compile("(?m)^\\s*java\\.version\\s*=\\s*(\\S+)\\s*$").matcher(versionOutput);
    assertTrue(versionMatcher.find(), "child Java did not report java.version:\n" + versionOutput);
    String childJavaVersion = versionMatcher.group(1);
    if (!configuredJava.isBlank()) {
      assertTrue(
          childJavaVersion.equals("17") || childJavaVersion.startsWith("17."),
          "explicit child Java must be version 17, found " + childJavaVersion);
    }

    Path childOutput = work.resolve("provider-smoke-child.log");
    Process process =
        new ProcessBuilder(
                java.toString(),
                "-cp",
                shaded.toAbsolutePath().toString(),
                LoggingProviderSmoke.class.getName(),
                work.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .redirectOutput(childOutput.toFile())
            .start();
    boolean exited = waitForOrTerminate(process, 30);
    String output = Files.readString(childOutput, StandardCharsets.UTF_8);
    assertTrue(exited, "logging provider child timed out:\n" + output);
    assertEquals(0, process.exitValue(), output + "\njar=" + shaded.toAbsolutePath());
    String appLog = Files.readString(work.resolve("logs/app.log"));
    assertTrue(appLog.contains("provider-smoke"), appLog);
    assertEquals(1, count(appLog, "provider-smoke"));
    assertEquals(1, count(appLog, "application log session started"));
    System.out.println("shaded-smoke-jar=" + shaded.toAbsolutePath());
    System.out.println("shaded-smoke-java=" + java.toRealPath());
    System.out.println("shaded-smoke-java-version=" + childJavaVersion);
    System.out.println("shaded-smoke-java-version-log=" + versionLog.toAbsolutePath());
  }

  private static boolean waitForOrTerminate(Process process, long timeoutSeconds)
      throws InterruptedException {
    boolean exited = false;
    InterruptedException interrupted = null;
    try {
      exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      interrupted = exception;
    }

    if (!exited) {
      process.destroy();
      interrupted = waitForCleanup(process, 5, interrupted);
      if (process.isAlive()) {
        process.destroyForcibly();
        interrupted = waitForCleanup(process, 5, interrupted);
      }
    }

    if (interrupted != null) {
      Thread.currentThread().interrupt();
      throw interrupted;
    }
    if (process.isAlive()) {
      throw new IllegalStateException(
          "child process survived forced termination: " + process.pid());
    }
    return exited;
  }

  private static InterruptedException waitForCleanup(
      Process process, long timeoutSeconds, InterruptedException interrupted) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    while (process.isAlive()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        break;
      }
      try {
        process.waitFor(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException exception) {
        if (interrupted == null) {
          interrupted = exception;
        }
      }
    }
    return interrupted;
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
