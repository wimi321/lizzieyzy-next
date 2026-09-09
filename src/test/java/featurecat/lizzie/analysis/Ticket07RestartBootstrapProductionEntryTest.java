package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.JFontMenu;
import featurecat.lizzie.gui.JFontMenuItem;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.rules.Board;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Production-entry coverage for the restart-only receipt boundary. */
class Ticket07RestartBootstrapProductionEntryTest {

  @Test
  void publicRestartPublishesNewBindingAndStartupCrossesLifecycleGate() throws Exception {
    Path runtime = Files.createTempDirectory("ticket07-phase-a");
    Path commandLog = runtime.resolve("commands.log");
    Path nameMarker = runtime.resolve("name.sent");

    Leelaz previousEngine = Lizzie.leelaz;
    Leelaz previousSecondaryEngine = Lizzie.leelaz2;
    Config previousConfig = Lizzie.config;
    LizzieFrame previousFrame = Lizzie.frame;
    featurecat.lizzie.gui.GtpConsolePane previousConsole = Lizzie.gtpConsole;
    Board previousBoard = Lizzie.board;
    boolean previousEmpty = EngineManager.isEmpty;
    int previousEngineNo = EngineManager.currentEngineNo;
    int previousBoardWidth = Board.boardWidth;
    int previousBoardHeight = Board.boardHeight;
    Menu previousMenu = LizzieFrame.menu;
    JFontMenu previousEngineMenu = Menu.engineMenu;
    JFontMenu previousEngineMenu2 = Menu.engineMenu2;
    JFontMenuItem[] previousEngineItems = Menu.engine;
    ImageIcon previousPlaying = Menu.playing;
    ImageIcon previousStop = Menu.stop;
    ImageIcon previousReady = Menu.ready;

    PhaseARestartLeelaz engine = new PhaseARestartLeelaz(fakeGtpCommand(commandLog, nameMarker));
    // The test classpath can contain an "sshd" dependency. Leelaz's legacy command heuristic
    // otherwise mistakes this local Java helper for an SSH engine.
    engine.isSSH = false;
    try {
      Lizzie.config = ConfigTestHelper.createForTests(runtime.resolve("config"));
      Lizzie.frame = allocate(PhaseAFrame.class);
      Lizzie.gtpConsole = null;
      Lizzie.leelaz2 = null;
      Lizzie.leelaz = engine;
      EngineManager.isEmpty = false;
      EngineManager.currentEngineNo = 0;
      Board.boardWidth = 19;
      Board.boardHeight = 19;
      Lizzie.board = new Board();

      Menu.engine = new JFontMenuItem[21];
      for (int index = 0; index < Menu.engine.length; index++) {
        Menu.engine[index] = new JFontMenuItem("engine-" + index);
      }
      Menu.playing = new ImageIcon();
      Menu.stop = new ImageIcon();
      Menu.ready = new ImageIcon();
      Menu.engineMenu = new JFontMenu("engine");
      Menu.engineMenu2 = new JFontMenu("engine2");
      LizzieFrame.menu = allocate(Menu.class);

      engine.started = true;
      engine.isLoaded = true;
      engine.isKatago = true;
      engine.width = 19;
      engine.height = 19;
      engine.oriWidth = 19;
      engine.oriHeight = 19;
      engine.komi = 7.5f;
      engine.orikomi = 7.5f;
      engine.commandLists.addAll(List.of("stop", "boardsize", "komi", "kata-analyze"));
      setField(engine, "endGetCommandList", true);
      setField(engine, "outputStream", new BufferedOutputStream(new ByteArrayOutputStream()));

      Object oldBinding = getField(engine, "readerStreamBinding");

      PhaseAEngineManager manager = new PhaseAEngineManager(List.of(engine));
      try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
        runtime.register(watchService, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
        manager.reStartEngine(0);

        assertTrue(engine.startCompleted.await(5, TimeUnit.SECONDS));
        assertTrue(engine.startupNameQueued.await(5, TimeUnit.SECONDS));
        Process newProcess = (Process) getField(engine, "process");
        Object newBinding = getField(engine, "readerStreamBinding");
        long newPid = newProcess == null ? -1L : newProcess.pid();
        assertNotNull(newProcess, "public restart must publish a new process");
        assertTrue(newProcess.isAlive(), "new process must remain alive while startup is gated");
        assertNotSame(oldBinding, newBinding, "restart must publish a new reader binding");
        assertFalse(
            engine.sendRawConsoleCommand("showboard"),
            "non-receipt commands must not enter the new restart binding");

        boolean markerCreated = awaitMarker(watchService, nameMarker, 2, TimeUnit.SECONDS);
        String queueHead = queueHead(engine);
        int queueSize = queueSize(engine);
        int commandCounter = (int) getField(engine, "cmdNumber");
        boolean lifecycleGate = (boolean) getField(engine, "exclusiveGtpLifecycleQueueGate");
        assertTrue(
            markerCreated,
            "startup name was queued but did not reach the new writer; newPid="
                + newPid
                + ", lifecycleGate="
                + lifecycleGate
                + ", queueHead="
                + queueHead
                + ", queueSize="
                + queueSize
                + ", cmdNumber="
                + commandCounter
                + ", output="
                + readIfExists(commandLog));
        assertTrue(
            manager.synchronizationFailed.await(2, TimeUnit.SECONDS),
            "readiness failure must settle the restart lifecycle");
        assertFalse(
            (boolean) getField(engine, "exclusiveGtpLifecycleQueueGate"),
            "failed restart must reopen the lifecycle gate exactly once");
        assertTrue(
            engine.hasUnrestoredReadBoardGmaState(), "failed restart must remain fail-closed");
        assertTrue(queueSize(engine) == 0, "failed receipt commands must be retired");
      }
    } finally {
      stopEngineForTest(engine);
      Lizzie.leelaz = previousEngine;
      Lizzie.leelaz2 = previousSecondaryEngine;
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.board = previousBoard;
      EngineManager.isEmpty = previousEmpty;
      EngineManager.currentEngineNo = previousEngineNo;
      Board.boardWidth = previousBoardWidth;
      Board.boardHeight = previousBoardHeight;
      LizzieFrame.menu = previousMenu;
      Menu.engineMenu = previousEngineMenu;
      Menu.engineMenu2 = previousEngineMenu2;
      Menu.engine = previousEngineItems;
      Menu.playing = previousPlaying;
      Menu.stop = previousStop;
      Menu.ready = previousReady;
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  private static String fakeGtpCommand(Path commandLog, Path nameMarker) {
    Path javaExecutable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
    String testClasses =
        Path.of(
                java.net.URI.create(
                    Ticket07RestartBootstrapProductionEntryTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toString()))
            .toAbsolutePath()
            .normalize()
            .toString();
    String classPath =
        testClasses + java.io.File.pathSeparator + System.getProperty("java.class.path");
    return commandQuote(javaExecutable.toString())
        + " -cp "
        + commandQuote(classPath)
        + " "
        + FakeGtpProcess.class.getName()
        + " "
        + commandQuote(commandLog.toAbsolutePath().toString())
        + " "
        + commandQuote(nameMarker.toAbsolutePath().toString());
  }

  private static String commandQuote(String value) {
    return "\"" + value.replace("\"", "\\\"") + "\"";
  }

  private static String readIfExists(Path path) throws IOException {
    return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "<empty>";
  }

  private static boolean awaitMarker(
      WatchService watchService, Path marker, long timeout, TimeUnit unit) throws Exception {
    long deadline = System.nanoTime() + unit.toNanos(timeout);
    while (!Files.exists(marker)) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;
      WatchKey key = watchService.poll(remaining, TimeUnit.NANOSECONDS);
      if (key == null) return Files.exists(marker);
      for (WatchEvent<?> ignored : key.pollEvents()) {}
      key.reset();
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static String queueHead(Leelaz engine) throws Exception {
    ArrayDeque<?> queue = (ArrayDeque<?>) getField(engine, "cmdQueue");
    synchronized (queue) {
      if (queue.isEmpty()) return "<empty>";
      Object queued = queue.peekFirst();
      Field command = queued.getClass().getDeclaredField("command");
      command.setAccessible(true);
      return String.valueOf(command.get(queued));
    }
  }

  private static int queueSize(Leelaz engine) throws Exception {
    ArrayDeque<?> queue = (ArrayDeque<?>) getField(engine, "cmdQueue");
    synchronized (queue) {
      return queue.size();
    }
  }


  private static void stopEngineForTest(Leelaz engine) {
    try {
      engine.isNormalEnd = true;
      engine.started = false;
      engine.isLoaded = false;
      Object owner = getField(engine, "exclusiveGtpLifecycleOwner");
      if (owner != null) {
        Method end =
            Leelaz.class.getDeclaredMethod("endExclusiveGtpLifecycleTransition", Object.class);
        end.setAccessible(true);
        end.invoke(engine, owner);
      }
      engine.shutdown();
      for (String executorName : List.of("executor", "executorErr")) {
        Object executor = getField(engine, executorName);
        if (executor instanceof ScheduledExecutorService) {
          ((ScheduledExecutorService) executor).shutdownNow();
        }
      }
      Process process = (Process) getField(engine, "process");
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    } catch (Throwable ignored) {
      // Test cleanup must not hide the production-entry RED.
    }
  }

  private static final class PhaseARestartLeelaz extends Leelaz {
    private final CountDownLatch startCompleted = new CountDownLatch(1);
    private final CountDownLatch startupNameQueued = new CountDownLatch(1);

    private PhaseARestartLeelaz(String command) throws Exception {
      super(command);
    }

    @Override
    public String getEngineName(int index) {
      currentEnginename = "phase-a-fake";
      oriEnginename = currentEnginename;
      return currentEnginename;
    }

    @Override
    public void startEngine(int index) throws IOException {
      super.startEngine(index);
      startCompleted.countDown();
    }

    @Override
    public void sendCommand(String command) {
      if (isCheckingName && "name".equals(command)) {
        startupNameQueued.countDown();
      }
      super.sendCommand(command);
    }
  }

  public static final class FakeGtpProcess {
    public static void main(String[] args) throws Exception {
      Path commandLog = Path.of(args[0]);
      Path nameMarker = Path.of(args[1]);
      try (BufferedReader input =
              new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
          BufferedWriter output = Files.newBufferedWriter(commandLog, StandardCharsets.UTF_8)) {
        String line;
        while ((line = input.readLine()) != null) {
          output.write(line);
          output.newLine();
          output.flush();
          if ("name".equals(line)) {
            Files.write(nameMarker, new byte[0]);
          }
        }
      }
    }
  }

  private static final class PhaseAEngineManager extends EngineManager {
    private final CountDownLatch synchronizationFailed = new CountDownLatch(1);

    private PhaseAEngineManager(List<Leelaz> engines) {
      super(engines);
    }

    @Override
    protected long engineSynchronizationTimeoutMillis(Leelaz engine) {
      return 100L;
    }

    @Override
    protected void showEngineSynchronizationFailure(Leelaz engine) {
      synchronizationFailed.countDown();
    }
  }

  private static final class PhaseAFrame extends LizzieFrame {
    @Override
    public void clearTryPlay() {}

    @Override
    public void clearTrackingPoints() {}

    @Override
    public void refresh() {}
  }
}
