package featurecat.lizzie.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class LoggingChildProcess {
  record Result(int exitCode, String output) {}

  static Result run(Path workDirectory, Class<?> probe, String... extraVmAndArgs) throws Exception {
    return runWithTimeout(workDirectory, 120_000L, probe, extraVmAndArgs);
  }

  static Result runWithTimeout(
      Path workDirectory, long timeoutMillis, Class<?> probe, String... extraVmAndArgs)
      throws Exception {
    String java =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    String classPath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path", ""));
    List<String> command = new ArrayList<>();
    command.add(java);
    command.add("-Djava.awt.headless=true");
    command.add("-D" + WorkDirectoryEnvironment.WORK_DIR_PROPERTY + "=" + workDirectory.toAbsolutePath());
    command.add("-cp");
    command.add(classPath);
    for (String extra : extraVmAndArgs) {
      if (extra.startsWith("-")) {
        command.add(extra);
      }
    }
    command.add(probe.getName());
    command.add(workDirectory.toAbsolutePath().toString());
    for (String extra : extraVmAndArgs) {
      if (!extra.startsWith("-")) {
        command.add(extra);
      }
    }
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    Thread reader =
        new Thread(
            () -> {
              try {
                process.getInputStream().transferTo(captured);
              } catch (IOException ignored) {
              }
            },
            "logging-child-stdout");
    reader.setDaemon(true);
    reader.start();
    boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    reader.join(2_000L);
    String output = captured.toString(StandardCharsets.UTF_8);
    if (!finished) {
      throw new AssertionError("probe timed out after " + timeoutMillis + "ms\n" + output);
    }
    return new Result(process.exitValue(), output);
  }

  static String readLog(Path workDirectory, String fileName) throws Exception {
    Path file = workDirectory.resolve("logs").resolve(fileName);
    if (!Files.isRegularFile(file)) {
      return "";
    }
    return Files.readString(file);
  }

  private LoggingChildProcess() {}
}
