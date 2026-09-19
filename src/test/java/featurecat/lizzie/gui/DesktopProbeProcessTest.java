package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises lifecycle failures with real JVMs, not production navigation acceptance. */
class DesktopProbeProcessTest {
  @Test
  void failedAndHungChildrenRetainIsolatedEvidenceAndCannotOutliveDeadline() throws Exception {
    long started = System.nanoTime();
    AssertionError failed =
        assertThrows(
            AssertionError.class,
            () ->
                DesktopProbeProcess.run(
                    Fixture.class, "explicit-failure", List.of(), List.of("fail"), 5));
    assertTrue(failed.getMessage().contains("exited 7"), failed.getMessage());
    Path failedDirectory = Path.of(failed.getMessage().split(": ", 2)[1]);
    AssertionError hung =
        assertThrows(
            AssertionError.class,
            () ->
                DesktopProbeProcess.run(Fixture.class, "hung-edt", List.of(), List.of("hang"), 2));
    assertTrue(hung.getMessage().contains("timed out"), hung.getMessage());
    Path hungDirectory = Path.of(hung.getMessage().split(": ", 2)[1]);
    assertNotEquals(failedDirectory, hungDirectory);
    assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 30);
    for (Path directory : List.of(failedDirectory, hungDirectory)) {
      assertTrue(Files.readString(directory.resolve("stdout.log")).contains("fixture stdout"));
      assertTrue(Files.readString(directory.resolve("stderr.log")).contains("fixture stderr"));
      assertEquals("fixture config", Files.readString(directory.resolve("work/config.txt")));
      assertEquals(
          "fixture application log", Files.readString(directory.resolve("work/logs/app.log")));
      String lifecycle = Files.readString(directory.resolve("lifecycle.txt"));
      long pid =
          Long.parseLong(
              lifecycle
                  .lines()
                  .filter(line -> line.startsWith("pid="))
                  .findFirst()
                  .orElseThrow()
                  .substring(4));
      assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
      assertTrue(lifecycle.contains("survivors=[]"), lifecycle);
    }
    long descendant =
        Long.parseLong(Files.readString(hungDirectory.resolve("work/descendant.pid")));
    assertFalse(ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false));
    assertTrue(Files.readString(hungDirectory.resolve("phases.log")).contains("timeout"));
    assertTrue(Files.isRegularFile(hungDirectory.resolve("thread-stacks.log")));
    assertTrue(Files.isRegularFile(hungDirectory.resolve("screenshot.log")));
  }
  @Test
  void childEnvironmentIsExplicitAndIsolated() throws Exception {
    String key = "LIZZIE_DESKTOP_PROBE_ENV";
    Path result =
        DesktopProbeProcess.run(
            Fixture.class,
            "environment",
            List.of(),
            List.of("environment", key),
            Map.of(key, "controlled-value"),
            5);

    assertEquals("controlled-value", Files.readString(result));
  }

  @Test
  void boundedCommandInterruptionCleansOwnedProcessTree(@TempDir Path temporary) throws Exception {
    Path parentPid = temporary.resolve("parent.pid");
    Path childPid = temporary.resolve("child.pid");
    List<String> command =
        List.of(
            ProcessHandle.current().info().command().orElseThrow(),
            "-cp",
            System.getProperty("java.class.path"),
            Fixture.class.getName(),
            "tree-parent",
            parentPid.toString(),
            childPid.toString());
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread runner =
        new Thread(
            () -> {
              try {
                TensorRtRepairAcceptanceTest.runBounded(
                    command, temporary, Duration.ofMinutes(1));
              } catch (Throwable thrown) {
                failure.set(thrown);
              }
            },
            "bounded-command-regression");
    runner.start();
    awaitFile(parentPid);
    awaitFile(childPid);
    Thread.sleep(250);

    long parent = Long.parseLong(Files.readString(parentPid));
    long child = Long.parseLong(Files.readString(childPid));
    runner.interrupt();
    runner.join(TimeUnit.SECONDS.toMillis(10));

    assertFalse(runner.isAlive(), "interrupted runner did not finish cleanup");
    assertTrue(failure.get() instanceof InterruptedException, String.valueOf(failure.get()));
    assertFalse(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false));
    assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
  }

  @Test
  void enableDeltaRejectsUnrelatedAndRetainedEntryChanges() {
    JSONObject before = configBeforeEnable();
    JSONObject after = new JSONObject(before.toString());
    JSONObject ui = after.getJSONObject("ui");
    ui.put("default-engine", 1);
    ui.put("katago-auto-setup-engine-path", "managed/katago.exe");
    JSONArray engines = after.getJSONObject("leelaz").getJSONArray("engine-settings-list");
    engines.getJSONObject(0).put("isDefault", false);
    JSONObject tensor = engines.getJSONObject(1);
    tensor
        .put("name", "KataGo TensorRT")
        .put("command", "managed/windows-x64-nvidia-tensorrt/katago.exe")
        .put("isDefault", true)
        .put("useJavaSSH", false)
        .put("useKeyGen", false)
        .put("ip", "")
        .put("port", "")
        .put("userName", "")
        .put("password", "")
        .put("keyGenPath", "");
    TensorRtRepairAcceptanceTest.validateEnableDelta(before, after);

    JSONObject unrelated = new JSONObject(after.toString());
    unrelated.getJSONObject("logging").put("level", "debug");
    assertThrows(
        AssertionError.class,
        () -> TensorRtRepairAcceptanceTest.validateEnableDelta(before, unrelated));

    JSONObject retained = new JSONObject(after.toString());
    retained
        .getJSONObject("leelaz")
        .getJSONArray("engine-settings-list")
        .getJSONObject(1)
        .put("maxVisits", 999);
    assertThrows(
        AssertionError.class,
        () -> TensorRtRepairAcceptanceTest.validateEnableDelta(before, retained));
  }

  private static JSONObject configBeforeEnable() {
    JSONObject direct =
        new JSONObject()
            .put("index", 0)
            .put("name", "DirectML controlled")
            .put("command", "directml/katago.exe")
            .put("isDefault", true)
            .put("maxVisits", 50);
    JSONObject tensor =
        new JSONObject()
            .put("index", 1)
            .put("name", "TensorRT managed missing")
            .put("command", "missing/windows-x64-nvidia-tensorrt/katago.exe")
            .put("isDefault", false)
            .put("useJavaSSH", true)
            .put("useKeyGen", true)
            .put("ip", "example")
            .put("port", "22")
            .put("userName", "user")
            .put("password", "secret")
            .put("keyGenPath", "key")
            .put("maxVisits", 100);
    return new JSONObject()
        .put("ui", new JSONObject().put("default-engine", 0).put("stable", "same"))
        .put(
            "leelaz",
            new JSONObject()
                .put("engine-settings-list", new JSONArray().put(direct).put(tensor)))
        .put("logging", new JSONObject().put("level", "info"));
  }

  private static void awaitFile(Path path) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!Files.isRegularFile(path) && System.nanoTime() < deadline) Thread.sleep(25);
    assertTrue(Files.isRegularFile(path), "timed out waiting for " + path);
  }

  public static final class Fixture {
    public static void main(String[] args) throws Exception {
      System.setProperty("java.awt.headless", "true");
      if ("tree-child".equals(args[0])) {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        while (true) java.util.concurrent.locks.LockSupport.park();
      }
      if ("tree-parent".equals(args[0])) {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                "-cp",
                System.getProperty("java.class.path"),
                Fixture.class.getName(),
                "tree-child",
                args[2])
            .inheritIO()
            .start();
        while (true) java.util.concurrent.locks.LockSupport.park();
      }
      if ("descendant".equals(args[0])) {
        while (true) java.util.concurrent.locks.LockSupport.park();
      }
      if ("environment".equals(args[0])) {
        Files.writeString(Path.of(args[3]), System.getenv(args[1]));
        return;
      }
      Path work = Path.of(args[1]);
      Files.writeString(work.resolve("config.txt"), "fixture config");
      Files.createDirectories(work.resolve("logs"));
      Files.writeString(work.resolve("logs/app.log"), "fixture application log");
      System.out.println("fixture stdout");
      System.err.println("fixture stderr");
      if ("fail".equals(args[0])) System.exit(7);
      Process descendant =
          new ProcessBuilder(
                  ProcessHandle.current().info().command().orElseThrow(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  Fixture.class.getName(),
                  "descendant")
              .inheritIO()
              .start();
      Files.writeString(work.resolve("descendant.pid"), Long.toString(descendant.pid()));
      javax.swing.SwingUtilities.invokeAndWait(
          () -> {
            while (true) java.util.concurrent.locks.LockSupport.park();
          });
    }
  }
}
