package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ReadBoardShutdownTest {
  private static final long MAX_EDT_RESTART_BLOCK_MS = 150L;
  private static final long SHUTDOWN_WAIT_BLOCK_MS = 300L;
  private static final long RESTART_TIMEOUT_MS = 2L;

  @Test
  void shutdownReleasesHostedReadBoardResources() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      TrackingInputStreamReader inputStream =
          new TrackingInputStreamReader(new ByteArrayInputStream(new byte[0]));
      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      TrackingServerSocket serverSocket = new TrackingServerSocket();
      TrackingSocket socket = new TrackingSocket();
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

      frame.syncBoard = true;
      frame.bothSync = true;
      frame.readBoard = readBoard;
      Lizzie.frame = frame;

      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      setField(readBoard, "usePipe", true);
      setField(readBoard, "inputStream", inputStream);
      setField(readBoard, "outputStream", outputStream);
      setField(readBoard, "executor", executor);
      setField(readBoard, "s", serverSocket);
      setField(readBoard, "socket", socket);

      readBoard.shutdown();

      assertFalse(frame.syncBoard, "shutdown should clear syncBoard state.");
      assertFalse(frame.bothSync, "shutdown should clear bothSync state.");
      assertNull(frame.readBoard, "shutdown should detach the closed ReadBoard from frame.");
      assertTrue(outputStream.closeCalled, "shutdown should close hosted stdin.");
      assertTrue(inputStream.closeCalled, "shutdown should close hosted stdout reader.");
      assertTrue(serverSocket.closeCalled, "shutdown should close hosted server socket.");
      assertTrue(socket.closeCalled, "shutdown should close hosted socket.");
      assertTrue(executor.isShutdown(), "shutdown should stop the read executor.");
      assertNull(
          getField(readBoard, "outputStream"), "shutdown should drop hosted stdin reference.");
      assertNull(
          getField(readBoard, "inputStream"), "shutdown should drop hosted stdout reference.");
      assertNull(
          getField(readBoard, "executor"), "shutdown should drop hosted executor reference.");
      assertNull(getField(readBoard, "socket"), "shutdown should drop hosted socket reference.");
      assertNull(getField(readBoard, "s"), "shutdown should drop hosted server socket reference.");
      assertTrue(
          outputStream.writtenText().contains("quit"),
          "shutdown should send quit before releasing resources.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void shutdownAfterProcessEndDoesNotWriteQuitToClosedPipe() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      TrackingInputStreamReader inputStream =
          new TrackingInputStreamReader(new ByteArrayInputStream(new byte[0]));
      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

      frame.syncBoard = true;
      frame.bothSync = true;
      frame.readBoard = readBoard;
      Lizzie.frame = frame;

      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      setField(readBoard, "usePipe", true);
      setField(readBoard, "inputStream", inputStream);
      setField(readBoard, "outputStream", outputStream);
      setField(readBoard, "executor", executor);

      readBoard.shutdownAfterProcessEnd();

      assertFalse(frame.syncBoard, "process-ended shutdown should clear syncBoard state.");
      assertFalse(frame.bothSync, "process-ended shutdown should clear bothSync state.");
      assertNull(frame.readBoard, "process-ended shutdown should detach the closed ReadBoard.");
      assertTrue(outputStream.closeCalled, "process-ended shutdown should close hosted stdin.");
      assertTrue(
          inputStream.closeCalled, "process-ended shutdown should close hosted stdout reader.");
      assertFalse(
          outputStream.writtenText().contains("quit"),
          "process-ended shutdown should not write quit to a readboard pipe that already ended.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void shutdownOnlyWritesQuitOnceWhenCalledRepeatedly() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      LizzieFrame frame = allocate(LizzieFrame.class);
      TrackingInputStreamReader inputStream =
          new TrackingInputStreamReader(new ByteArrayInputStream(new byte[0]));
      TrackingBufferedOutputStream outputStream = new TrackingBufferedOutputStream();
      ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

      frame.readBoard = readBoard;
      Lizzie.frame = frame;

      setField(readBoard, "conflictTracker", new SyncConflictTracker());
      setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
      setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
      setField(readBoard, "tempcount", new ArrayList<Integer>());
      setField(readBoard, "usePipe", true);
      setField(readBoard, "inputStream", inputStream);
      setField(readBoard, "outputStream", outputStream);
      setField(readBoard, "executor", executor);

      readBoard.shutdown();
      String firstShutdownWrite = outputStream.writtenText();
      readBoard.shutdown();

      assertTrue(
          firstShutdownWrite.contains("quit"), "normal shutdown should ask readboard to quit.");
      assertEquals(
          firstShutdownWrite,
          outputStream.writtenText(),
          "repeated shutdown should not write another quit command.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void openBoardSyncDoesNotBlockEventDispatchThreadWhileRestarting() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      TrackingLizzieFrame frame = allocate(TrackingLizzieFrame.class);
      ReadBoard existingReadBoard = allocate(ReadBoard.class);
      frame.initialize(existingReadBoard, SHUTDOWN_WAIT_BLOCK_MS);
      frame.readBoard = existingReadBoard;
      Lizzie.frame = frame;

      AtomicLong elapsedMs = new AtomicLong();
      SwingUtilities.invokeAndWait(
          () -> {
            long startNanos = System.nanoTime();
            frame.openBoardSync();
            elapsedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
          });

      assertTrue(
          elapsedMs.get() < MAX_EDT_RESTART_BLOCK_MS,
          "restarting readboard should return quickly on the event dispatch thread.");
      assertTrue(
          frame.awaitShutdownStarted(RESTART_TIMEOUT_MS, TimeUnit.SECONDS),
          "restart should begin shutting down the existing readboard in the background.");
      assertTrue(
          frame.awaitRestart(RESTART_TIMEOUT_MS, TimeUnit.SECONDS),
          "restart should eventually create a replacement readboard.");
      assertFalse(
          frame.startedBeforeShutdownCompleted,
          "replacement readboard should not start before the previous instance finishes shutting down.");
      assertSame(
          frame.createdReadBoard, frame.readBoard, "restart should publish the new readboard.");
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }

  private static final class TrackingInputStreamReader extends InputStreamReader {
    private boolean closeCalled;

    private TrackingInputStreamReader(InputStream stream) {
      super(stream, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
      closeCalled = true;
    }
  }

  private static final class TrackingBufferedOutputStream extends BufferedOutputStream {
    private boolean closeCalled;
    private final ByteArrayOutputStream capture;

    private TrackingBufferedOutputStream() {
      this(new ByteArrayOutputStream());
    }

    private TrackingBufferedOutputStream(ByteArrayOutputStream capture) {
      super(capture);
      this.capture = capture;
    }

    @Override
    public void close() {
      closeCalled = true;
    }

    private String writtenText() {
      return capture.toString(StandardCharsets.UTF_8);
    }
  }

  private static final class TrackingServerSocket extends ServerSocket {
    private boolean closeCalled;

    private TrackingServerSocket() throws Exception {
      super();
    }

    @Override
    public void close() {
      closeCalled = true;
    }
  }

  private static final class TrackingSocket extends Socket {
    private boolean closeCalled;

    @Override
    public synchronized void close() {
      closeCalled = true;
    }
  }

  private static final class TrackingLizzieFrame extends LizzieFrame {
    private CountDownLatch shutdownStartedSignal;
    private CountDownLatch restartSignal;
    private ReadBoard existingReadBoard;
    private ReadBoard createdReadBoard;
    private long shutdownWaitBlockMs;
    private volatile boolean shutdownCompleted;
    private volatile boolean startedBeforeShutdownCompleted;

    private void initialize(ReadBoard existingReadBoard, long shutdownWaitBlockMs) {
      this.existingReadBoard = existingReadBoard;
      this.shutdownWaitBlockMs = shutdownWaitBlockMs;
      this.shutdownStartedSignal = new CountDownLatch(1);
      this.restartSignal = new CountDownLatch(1);
      try {
        Field field = LizzieFrame.class.getDeclaredField("readBoardRestartLock");
        field.setAccessible(true);
        field.set(this, new Object());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private boolean awaitShutdownStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return shutdownStartedSignal.await(timeout, unit);
    }

    private boolean awaitRestart(long timeout, TimeUnit unit) throws InterruptedException {
      return restartSignal.await(timeout, unit);
    }

    @Override
    protected void shutdownReadBoard(ReadBoard targetReadBoard) {
      if (targetReadBoard != existingReadBoard) {
        throw new AssertionError("unexpected readboard shutdown target");
      }
      shutdownStartedSignal.countDown();
      try {
        Thread.sleep(shutdownWaitBlockMs);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new AssertionError(ex);
      }
      shutdownCompleted = true;
      readBoard = null;
    }

    @Override
    protected boolean isNativeBoardSyncSupported() {
      return true;
    }

    @Override
    protected boolean isNativeReadBoardAvailable() {
      return true;
    }

    @Override
    protected ReadBoard createNativeReadBoard() throws Exception {
      startedBeforeShutdownCompleted = !shutdownCompleted;
      createdReadBoard = allocate(ReadBoard.class);
      restartSignal.countDown();
      return createdReadBoard;
    }
  }
}
