package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Owned, bounded JVM execution shared by the two production-window probes. */
final class DesktopProbeProcess {
  private DesktopProbeProcess() {}

  static void requireDisplay() {
    boolean headless = GraphicsEnvironment.isHeadless();
    if (Boolean.getBoolean("lizzie.desktop.required") && headless) {
      throw new AssertionError(
          "Desktop lane requires java.awt.headless=false and an operational display");
    }
    assumeFalse(headless);
    if (GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length == 0) {
      throw new AssertionError("Desktop lane has no operational display");
    }
  }

  static Path run(Class<?> entry, String name, List<String> vmArgs, List<String> args)
      throws Exception {
    return run(entry, name, vmArgs, args, 90);
  }

  static Path run(
      Class<?> entry, String name, List<String> vmArgs, List<String> args, long timeoutSeconds)
      throws Exception {
    return run(entry, name, vmArgs, args, Map.of(), timeoutSeconds);
  }

  static Path run(
      Class<?> entry,
      String name,
      List<String> vmArgs,
      List<String> args,
      Map<String, String> environment,
      long timeoutSeconds)
      throws Exception {
    return run(entry, name, vmArgs, args, environment, timeoutSeconds, null, null);
  }
  static Path run(
      Class<?> entry,
      String name,
      List<String> vmArgs,
      List<String> args,
      Map<String, String> environment,
      long timeoutSeconds,
      Path workRoot,
      Path processDirectory)
      throws Exception {
    Path root =
        Path.of(System.getProperty("lizzie.desktop.evidence.dir", "target/desktop-smoke/probes"))
            .toAbsolutePath();
    Files.createDirectories(root);
    Path evidence = Files.createTempDirectory(root, name + "-");
    Path work =
        workRoot == null
            ? Files.createDirectory(evidence.resolve("work"))
            : Files.createTempDirectory(workRoot, name + "-work-");
    Path result = evidence.resolve("result.txt");
    Path lifecycle = evidence.resolve("lifecycle.txt");
    List<String> command = javaCommand(entry);
    command.addAll(1, vmArgs);
    command.addAll(args);
    command.add(work.toString());
    command.add(result.toString());
    phase(result, "launch");
    Files.writeString(
        lifecycle, "entry=" + entry.getName() + "\nwork=" + work + "\ncommand=" + command + "\n");
    Process child = null;
    Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
    Throwable failure = null;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command)
              .directory((processDirectory == null ? work : processDirectory).toFile())
              .redirectOutput(evidence.resolve("stdout.log").toFile())
              .redirectError(evidence.resolve("stderr.log").toFile());
      builder.environment().putAll(environment);
      child = builder.start();
      owned.put(child.pid(), child.toHandle());
      Files.writeString(
          lifecycle,
          "pid=" + child.pid() + "\nstarted=" + Instant.now() + "\n",
          StandardOpenOption.APPEND);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
      while (child.isAlive() && System.nanoTime() < deadline) {
        child.descendants().forEach(handle -> owned.put(handle.pid(), handle));
        child.waitFor(100, TimeUnit.MILLISECONDS);
      }
      if (child.isAlive()) {
        phase(result, "timeout");
        capture(child, evidence);
        throw new AssertionError("Desktop probe timed out: " + evidence);
      }
      Files.writeString(lifecycle, "exit=" + child.exitValue() + "\n", StandardOpenOption.APPEND);
      if (child.exitValue() != 0) {
        throw new AssertionError("Desktop probe exited " + child.exitValue() + ": " + evidence);
      }
      if (!Files.isRegularFile(result)) {
        throw new AssertionError("Desktop probe omitted result: " + evidence);
      }
      phase(result, "passed");
      return result;
    } catch (Throwable error) {
      failure = error;
      throw error;
    } finally {
      // Redirecting both streams directly to files creates no parent output-reader threads.
      // Snapshot descendants while their parent is still alive, then terminate only owned handles.
      if (child != null) {
        child.descendants().forEach(handle -> owned.put(handle.pid(), handle));
      }
      List<ProcessHandle> handles = new ArrayList<>(owned.values());
      for (int index = handles.size() - 1; index >= 0; index--) {
        ProcessHandle handle = handles.get(index);
        if (handle.isAlive()) handle.destroyForcibly();
      }
      long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      boolean interrupted = Thread.interrupted();
      while (handles.stream().anyMatch(ProcessHandle::isAlive)
          && System.nanoTime() < cleanupDeadline) {
        try {
          Thread.sleep(25);
        } catch (InterruptedException error) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
      List<Long> survivors =
          handles.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
      try {
        Files.writeString(
            lifecycle,
            "result="
                + (failure == null ? "success" : failure)
                + "\nowned="
                + owned.keySet()
                + "\nsurvivors="
                + survivors
                + "\nreaders=none\n",
            StandardOpenOption.APPEND);
        if (!survivors.isEmpty())
          throw new AssertionError("Owned probe processes survived cleanup: " + survivors);
      } catch (Throwable cleanupError) {
        if (failure != null) failure.addSuppressed(cleanupError);
        else throw cleanupError;
      }
    }
  }

  static void phase(Path result, String phase) throws IOException {
    Files.writeString(
        result.resolveSibling("phases.log"),
        Instant.now() + " " + phase + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private static List<String> javaCommand(Class<?> entry) {
    String classPath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path", ""));
    String absoluteClassPath =
        String.join(
            File.pathSeparator,
            Pattern.compile(Pattern.quote(File.pathSeparator))
                .splitAsStream(classPath)
                .map(value -> Path.of(value).toAbsolutePath().toString())
                .toList());
    return new ArrayList<>(
        List.of(
            tool("java").toString(),
            "-Djava.awt.headless=false",
            "-cp",
            absoluteClassPath,
            entry.getName()));
  }

  private static Path tool(String name) {
    return Path.of(
        System.getProperty("java.home"),
        "bin",
        name + (System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : ""));
  }

  private static void capture(Process child, Path evidence) {
    diagnostic(
        List.of(tool("jcmd").toString(), Long.toString(child.pid()), "Thread.print", "-l"),
        evidence.resolve("thread-stacks.log"));
    List<String> screenshot = javaCommand(DesktopProbeProcess.class);
    screenshot.add(evidence.resolve("timeout.png").toString());
    diagnostic(screenshot, evidence.resolve("screenshot.log"));
  }

  private static void diagnostic(List<String> command, Path output) {
    Process capture = null;
    try {
      capture =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      if (!capture.waitFor(5, TimeUnit.SECONDS)) {
        Files.writeString(output, "\ndiagnostic timed out\n", StandardOpenOption.APPEND);
      } else {
        Files.writeString(
            output, "\ndiagnostic exit=" + capture.exitValue() + "\n", StandardOpenOption.APPEND);
      }
    } catch (Exception error) {
      try {
        Files.writeString(
            output,
            "\ndiagnostic unavailable/failed: " + error + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException ignored) {
        System.err.println("Unable to record diagnostic failure: " + error);
      }
    } finally {
      if (capture != null && capture.isAlive()) {
        capture.destroyForcibly();
        try {
          if (!capture.waitFor(2, TimeUnit.SECONDS))
            System.err.println("Diagnostic survived termination: " + capture.pid());
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Rectangle screen =
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice()
            .getDefaultConfiguration()
            .getBounds();
    if (!ImageIO.write(new Robot().createScreenCapture(screen), "png", Path.of(args[0]).toFile())) {
      throw new IOException("PNG writer unavailable");
    }
  }
}
