package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatGptRuntimeSmokeIT {
  @TempDir Path directory;

  @Test
  void shadedArtifactAcceptsLoopbackAndCancelsWithoutCredentials() throws Exception {
    var result = run(false);
    assertEquals(0, result.exitCode, result.output);
    assertTrue(result.output.contains("CHATGPT_LOOPBACK_SMOKE_OK"), result.output);
  }

  @Test
  void oldRuntimeReportsIncompletePackageInsteadOfNetworkFailure() throws Exception {
    var result = run(true);
    assertNotEquals(0, result.exitCode, result.output);
    assertTrue(result.output.contains("Install the latest complete package"), result.output);
    assertFalse(result.output.contains("NoClassDefFoundError"), result.output);
    assertFalse(result.output.contains("Check your network"), result.output);
    assertTrue(ChatGptText.isKnownError(ChatGptHttp.error("runtime").getMessage()));
  }

  private Result run(boolean oldRuntime) throws Exception {
    String jar = System.getProperty("lizzie.shaded.jar", "");
    assertFalse(jar.isBlank());
    assertTrue(Files.isRegularFile(Path.of(jar)));
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
    if (oldRuntime) command.addAll(List.of("--limit-modules", "java.se,jdk.crypto.ec"));
    command.addAll(
        List.of(
            "-Duser.language=en",
            "-Duser.country=US",
            "-cp",
            Path.of(jar).toAbsolutePath().toString(),
            ChatGptRuntimeSmoke.class.getName()));
    Path log = directory.resolve("runtime-" + oldRuntime + ".log");
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Runtime callback probe timed out");
      return new Result(process.exitValue(), Files.readString(log));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Probe process did not stop");
      }
    }
  }

  private record Result(int exitCode, String output) {}
}
