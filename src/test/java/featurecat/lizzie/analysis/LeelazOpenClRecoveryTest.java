package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LeelazOpenClRecoveryTest {
  @Test
  void openClRecoveryCapturesRestoreBeforeLifecycleReservationAndStart() throws Exception {
    Config previousConfig = Lizzie.config;
    Board previousBoard = Lizzie.board;
    Leelaz previousEngine = Lizzie.leelaz;
    LizzieFrame previousFrame = Lizzie.frame;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-opencl-prepared-restore");
    PreparedRecoveryLeelaz engine = new PreparedRecoveryLeelaz();
    PreparedRestoreBoard board = preparedRestoreBoard();
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Lizzie.board = board;
      Lizzie.leelaz = engine;
      Lizzie.frame = allocate(SilentRecoveryFrame.class);
      engine.mutateOnReservation = () -> mutateHistory(board.getHistory());
      engine.mutateOnStart = () -> mutateHistory(board.getHistory());
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      ExitedProcess process = new ExitedProcess((int) 0xC0000409L);
      setField(engine, "process", process);
      setField(
          engine,
          "inputStream",
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;

      assertTrue(invokeOpenClRecovery(engine));
      assertTrue(board.restoreCompleted.await(2, TimeUnit.SECONDS));
      assertTrue(board.preparedRestoreReceived);
      assertFalse(board.genericRestoreReceived);
      assertTrue(engine.loadedSgf.contains("AB[dd]"));
      assertTrue(engine.loadedSgf.contains("KM[6.5]"));
      assertNotNull(engine.reservation);
      engine.reservation.close();
    } finally {
      if (engine.reservation != null) {
        engine.reservation.close();
      }
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
      Lizzie.board = previousBoard;
      Lizzie.leelaz = previousEngine;
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void automaticRestartWaitsForTheFullStartupCommandSequence() {
    assertFalse(Leelaz.automaticRestartReady(false, false, true));
    assertFalse(Leelaz.automaticRestartReady(true, true, true));
    assertFalse(Leelaz.automaticRestartReady(true, false, false));
    assertTrue(Leelaz.automaticRestartReady(true, false, true));
  }

  @Test
  void currentOpenClNativeEofStartsAtMostOneAutomaticRecovery() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-opencl-recovery");
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      RecordingRecoveryLeelaz engine = new RecordingRecoveryLeelaz();
      ExitedProcess process = new ExitedProcess((int) 0xC0000409L);
      setField(engine, "process", process);
      setField(
          engine,
          "inputStream",
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;

      invokeRead(engine);
      assertTrue(engine.recoveryStarted.await(2, TimeUnit.SECONDS));
      assertFalse(invokeOpenClRecovery(engine));
      assertEquals(1, process.destroyCount);
      assertFalse(engine.isStarted());
      assertEquals(1, engine.restartCount);
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
    }
  }

  @Test
  void staleOpenClNativeEofDoesNotStartRecoveryOrDestroyReboundProcess() throws Exception {
    Config previousConfig = Lizzie.config;
    String previousOsName = System.getProperty("os.name");
    String previousDriver = System.getProperty("lizzie.opencl.nvidiaDriverVersion");
    Path tempRoot = Files.createTempDirectory("leelaz-stale-opencl-recovery");
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "566.36");
      Lizzie.config = ConfigTestHelper.createForTests(tempRoot.resolve("runtime-root"));
      Path enginePath = createOpenClEngine(tempRoot);
      Path modelPath = touch(tempRoot.resolve("weights/current.bin.gz"));
      RecordingRecoveryLeelaz engine = new RecordingRecoveryLeelaz();
      BlockingEofInputStream oldStdout = new BlockingEofInputStream();
      ExitedProcess oldProcess = new ExitedProcess((int) 0xC0000409L, oldStdout);
      setField(engine, "process", oldProcess);
      initializeStreams(engine, oldProcess);
      setField(
          engine,
          "commands",
          List.of(enginePath.toString(), "gtp", "-model", modelPath.toString()));
      engine.started = true;
      engine.isLoaded = true;

      AtomicReference<Throwable> readerFailure = new AtomicReference<>();
      Thread oldReader =
          new Thread(
              () -> {
                try {
                  invokeRead(engine);
                } catch (Throwable failure) {
                  readerFailure.set(failure);
                }
              },
              "stale-opencl-reader");
      oldReader.setDaemon(true);
      oldReader.start();
      assertTrue(oldStdout.awaitRead());

      ExitedProcess newProcess = new ExitedProcess(0);
      setField(engine, "process", newProcess);
      initializeStreams(engine, newProcess);
      oldStdout.release();
      oldReader.join(1000L);

      assertFalse(oldReader.isAlive());
      assertEquals(null, readerFailure.get());
      assertEquals(0, oldProcess.destroyCount);
      assertEquals(0, newProcess.destroyCount);
      assertEquals(0, engine.restartCount);
      assertTrue(engine.isStarted());
      assertTrue(engine.isLoaded());
    } finally {
      restoreProperty("os.name", previousOsName);
      restoreProperty("lizzie.opencl.nvidiaDriverVersion", previousDriver);
      Lizzie.config = previousConfig;
    }
  }

  private static Path createOpenClEngine(Path tempRoot) throws IOException {
    Path engineDirectory = Files.createDirectories(tempRoot.resolve("engines/katago/windows-x64"));
    Files.writeString(engineDirectory.resolve("lizzieyzy-next-engine-backend.txt"), "opencl");
    return touch(engineDirectory.resolve("katago.exe"));
  }

  private static Path touch(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    return Files.write(path, new byte[0]);
  }

  private static void invokeRead(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("read");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static boolean invokeOpenClRecovery(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("tryRecoverBundledOpenClNativeExit");
    method.setAccessible(true);
    return (Boolean) method.invoke(engine);
  }

  private static void initializeStreams(Leelaz engine, Process process) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "initializeStreams", InputStream.class, OutputStream.class, InputStream.class);
    method.setAccessible(true);
    method.invoke(
        engine, process.getInputStream(), process.getOutputStream(), process.getErrorStream());
  }

  private static void setField(Leelaz engine, String name, Object value) throws Exception {
    Field field = Leelaz.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(engine, value);
  }

  private static void restoreProperty(String name, String previousValue) {
    if (previousValue == null) {
      System.clearProperty(name);
    } else {
      System.setProperty(name, previousValue);
    }
  }

  private static PreparedRestoreBoard preparedRestoreBoard() throws Exception {
    BoardData snapshot = BoardData.empty(19, 19);
    snapshot.stones[Board.getIndex(3, 3)] = Stone.BLACK;
    BoardHistoryList history = new BoardHistoryList(snapshot);
    history.getGameInfo().setKomiNoMenu(6.5);
    PreparedRestoreBoard board = allocate(PreparedRestoreBoard.class);
    board.restoreCompleted = new CountDownLatch(1);
    board.startStonelist = new ArrayList<>();
    board.hasStartStone = false;
    board.setHistory(history);
    return board;
  }

  private static void mutateHistory(BoardHistoryList history) {
    history.getStart().getData().stones[Board.getIndex(3, 3)] = Stone.EMPTY;
    history.getGameInfo().setKomiNoMenu(7.5);
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class PreparedRecoveryLeelaz extends Leelaz {
    private Runnable mutateOnReservation;
    private Runnable mutateOnStart;
    private Leelaz.ExclusiveGtpLifecycleReservation reservation;
    private String loadedSgf = "";

    private PreparedRecoveryLeelaz() throws Exception {
      super("controlled-engine");
      installProtocol();
    }

    private void installProtocol() {
      ExactSnapshotRestoreProtocolFixture.install(
          this,
          command -> {
            if (command.startsWith("loadsgf ")) {
              loadedSgf = Files.readString(Path.of(command.substring("loadsgf ".length())));
            }
            return ExactSnapshotRestoreProtocolFixture.Response.success();
          });
    }

    @Override
    public ExclusiveGtpLifecycleReservation beginAutomaticEngineRestartReservation() {
      reservation = super.beginAutomaticEngineRestartReservation();
      if (reservation != null && mutateOnReservation != null) {
        mutateOnReservation.run();
      }
      return reservation;
    }

    @Override
    public void startEngine(int index) {
      if (mutateOnStart != null) {
        mutateOnStart.run();
      }
      started = true;
      isLoaded = true;
      isCheckingName = false;
      installProtocol();
      try {
        setField(this, "endGetCommandList", true);
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  private static final class SilentRecoveryFrame extends LizzieFrame {
    @Override
    public void prepareQuickAnalysisForPrimaryOpenClRecovery() {}
  }

  private static final class PreparedRestoreBoard extends Board {
    private CountDownLatch restoreCompleted;
    private boolean preparedRestoreReceived;
    private boolean genericRestoreReceived;

    @Override
    public void resendMoveToEngine(
        Leelaz engine,
        boolean loadEngine,
        ExactSnapshotEngineRestore.PreparedRestore preparedRestore) {
      if (preparedRestore == null) {
        genericRestoreReceived = true;
      } else {
        preparedRestoreReceived = true;
        preparedRestore.execute();
      }
      restoreCompleted.countDown();
    }

    @Override
    public void resendMoveToEngine(Leelaz engine, boolean loadEngine) {
      genericRestoreReceived = true;
      restoreCompleted.countDown();
    }
  }

  private static final class RecordingRecoveryLeelaz extends Leelaz {
    private final CountDownLatch recoveryStarted = new CountDownLatch(1);
    private int restartCount;

    private RecordingRecoveryLeelaz() throws Exception {
      super("");
    }

    @Override
    public void restartClosedEngine(int index, Runnable afterBoardRestore) {
      restartCount++;
      afterBoardRestore.run();
      recoveryStarted.countDown();
    }
  }

  private static final class ExitedProcess extends Process {
    private final int exitCode;
    private final InputStream stdout;
    private int destroyCount;

    private ExitedProcess(int exitCode) {
      this(exitCode, new ByteArrayInputStream(new byte[0]));
    }

    private ExitedProcess(int exitCode, InputStream stdout) {
      this.exitCode = exitCode;
      this.stdout = stdout;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return exitCode;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public void destroy() {
      destroyCount++;
    }
  }

  private static final class BlockingEofInputStream extends InputStream {
    private final CountDownLatch reading = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);

    @Override
    public int read() throws IOException {
      reading.countDown();
      try {
        if (!released.await(2, TimeUnit.SECONDS)) {
          throw new IOException("timed out waiting to release stale EOF");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException(interrupted);
      }
      return -1;
    }

    private boolean awaitRead() throws InterruptedException {
      return reading.await(1, TimeUnit.SECONDS);
    }

    private void release() {
      released.countDown();
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = load();

    private static sun.misc.Unsafe load() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException failure) {
        throw new ExceptionInInitializerError(failure);
      }
    }
  }
}
