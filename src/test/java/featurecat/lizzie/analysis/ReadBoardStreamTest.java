package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReadBoardStreamTest {
  @Test
  void streamKeepsUsingOwnerAfterFrameDetachesReadBoard() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    AtomicReference<Throwable> uncaughtFailure = new AtomicReference<>();
    TrackingReadBoard readBoard = allocate(TrackingReadBoard.class);
    readBoard.initialize();
    LizzieFrame frame = allocate(LizzieFrame.class);
    frame.readBoard = readBoard;
    Lizzie.frame = frame;

    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket streamSocket = serverSocket.accept()) {
      Thread.setDefaultUncaughtExceptionHandler(
          (thread, throwable) -> uncaughtFailure.set(throwable));
      ReadBoardStream stream = new ReadBoardStream(readBoard, streamSocket);
      try {
        frame.readBoard = null;
        clientSocket.getOutputStream().write("noop\n".getBytes(StandardCharsets.UTF_8));
        clientSocket.getOutputStream().flush();

        assertTrue(
            readBoard.awaitParsedLine(), "socket reader should still dispatch to its owner.");
        assertEquals("noop", readBoard.lastParsedLine, "owner should receive the pending line.");
        assertNull(
            uncaughtFailure.get(), "detaching frame.readBoard should not crash the reader thread.");
      } finally {
        stream.close();
        stream.join(1000L);
        assertFalse(stream.isAlive(), "closing the reader should stop its thread.");
      }
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
      Lizzie.frame = previousFrame;
    }
  }

  @Test
  void tcpReadySendsInitialAnalysisState() throws Exception {
    Leelaz previousLeelaz = Lizzie.leelaz;
    ReadBoard readBoard = allocate(ReadBoard.class);
    setField(readBoard, "usePipe", false);
    SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
    leelaz.notPondering();
    Lizzie.leelaz = leelaz;

    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket streamSocket = serverSocket.accept()) {
      ReadBoardStream stream = new ReadBoardStream(readBoard, streamSocket);
      setField(readBoard, "readBoardStream", stream);
      try {
        clientSocket.getOutputStream().write("ready\n".getBytes(StandardCharsets.UTF_8));
        clientSocket.getOutputStream().flush();
        BufferedReader response =
            new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

        assertEquals("version", response.readLine());
        assertEquals("analysisState paused", response.readLine());
      } finally {
        stream.close();
        stream.join(1000L);
      }
    } finally {
      Lizzie.leelaz = previousLeelaz;
    }
  }

  @Test
  void tcpReadyReportsPausedWithoutAnInitializedEngine() throws Exception {
    Leelaz previousLeelaz = Lizzie.leelaz;
    ReadBoard readBoard = allocate(ReadBoard.class);
    setField(readBoard, "usePipe", false);
    Lizzie.leelaz = null;

    try (ServerSocket serverSocket = new ServerSocket(0);
        Socket clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        Socket streamSocket = serverSocket.accept()) {
      ReadBoardStream stream = new ReadBoardStream(readBoard, streamSocket);
      setField(readBoard, "readBoardStream", stream);
      try {
        clientSocket.getOutputStream().write("ready\n".getBytes(StandardCharsets.UTF_8));
        clientSocket.getOutputStream().flush();
        BufferedReader response =
            new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));

        assertEquals("version", response.readLine());
        assertEquals("analysisState paused", response.readLine());
      } finally {
        stream.close();
        stream.join(1000L);
      }
    } finally {
      Lizzie.leelaz = previousLeelaz;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = ReadBoard.class.getDeclaredField(name);
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

  private static final class TrackingReadBoard extends ReadBoard {
    private CountDownLatch parsedSignal;
    private String lastParsedLine;

    private TrackingReadBoard() throws Exception {
      super(true, true);
    }

    private void initialize() {
      parsedSignal = new CountDownLatch(1);
    }

    @Override
    public void parseLine(String line) {
      lastParsedLine = line.trim();
      parsedSignal.countDown();
    }

    private boolean awaitParsedLine() throws InterruptedException {
      return parsedSignal.await(2, TimeUnit.SECONDS);
    }
  }
}
