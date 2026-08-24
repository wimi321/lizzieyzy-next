package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("framingChunks")
  void equivalentByteStreamsParseTheSameRegardlessOfChunking(String name, int[] chunkSizes)
      throws Exception {
    byte[] bytes = utf8("full\nsecond\n你好\nready\nready\n");
    RecordingReadBoard expectedOwner = recordingOwner();
    ReadBoardStream expectedStream =
        new ReadBoardStream(expectedOwner, new ByteArrayInputStream(bytes));
    expectedStream.run();

    RecordingReadBoard chunkedOwner = recordingOwner();
    ReadBoardStream chunkedStream =
        new ReadBoardStream(chunkedOwner, new ChunkedInputStream(bytes, chunkSizes));
    chunkedStream.run();

    assertEquals(expectedOwner.lines, chunkedOwner.lines, name);
    assertEquals(expectedOwner.readyCount, chunkedOwner.readyCount, name);
    assertEquals(List.of("full", "second", "你好", "ready", "ready"), chunkedOwner.lines);
    assertEquals(2, chunkedOwner.readyCount, "exact ready lines still dispatch handleReady");
  }

  static Stream<Arguments> framingChunks() {
    return Stream.of(
        Arguments.of("single-byte", new int[] {1}),
        Arguments.of("two-byte", new int[] {2}),
        Arguments.of("eight-byte", new int[] {8}),
        Arguments.of("whole-buffer", new int[] {1024}),
        Arguments.of("full-message-then-partial", new int[] {5, 3, 20}),
        Arguments.of("split-inside-utf8-codepoint", new int[] {11, 1, 1, 1, 20}));
  }

  @Test
  void consecutiveMessagesAreDeliveredInOrderIncludingDuplicates() throws Exception {
    RecordingReadBoard owner = recordingOwner();
    ReadBoardStream stream =
        new ReadBoardStream(owner, new ByteArrayInputStream(utf8("a\na\nready\nready\n")));
    stream.run();

    assertEquals(List.of("a", "a", "ready", "ready"), owner.lines);
    assertEquals(2, owner.readyCount);
  }

  @Test
  void oneReadCanHoldACompleteMessageAndTheNextPartial() throws Exception {
    byte[] bytes = utf8("complete\npartial");
    RecordingReadBoard owner = recordingOwner();
    ReadBoardStream stream =
        new ReadBoardStream(
            owner, new ChunkedInputStream(bytes, new int[] {"complete\npar".length(), 32}));
    stream.run();

    assertEquals(List.of("complete", "partial"), owner.lines);
    assertEquals(0, owner.readyCount);
  }

  @Test
  void crLfCrAndLfTerminatorsProduceTheSameLogicalLines() throws Exception {
    RecordingReadBoard lf = parseAll("one\ntwo\n");
    RecordingReadBoard crlf = parseAll("one\r\ntwo\r\n");
    RecordingReadBoard cr = parseAll("one\rtwo\r");

    assertEquals(List.of("one", "two"), lf.lines);
    assertEquals(lf.lines, crlf.lines);
    assertEquals(lf.lines, cr.lines);
  }

  @Test
  void emptyStreamAndBlankLinesAreFramedWithoutReadyDispatch() throws Exception {
    RecordingReadBoard empty = parseAll("");
    RecordingReadBoard blanks = parseAll("\n\nready \n");

    assertEquals(List.of(), empty.lines);
    assertEquals(0, empty.readyCount);
    assertEquals(List.of("", "", "ready "), blanks.lines);
    assertEquals(0, blanks.readyCount, "ready is exact; trailing space is a normal payload line");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Ready", "READY", " ready", "ready\t"})
  void readyDispatchIsCaseSensitiveAndExact(String line) throws Exception {
    RecordingReadBoard owner = parseAll(line + "\n");

    assertEquals(List.of(line), owner.lines);
    assertEquals(0, owner.readyCount);
  }

  @Test
  void eofDeliversAnIncompleteFinalLineAndThenStops() throws Exception {
    RecordingReadBoard owner = parseAll("complete\nincomplete");

    assertEquals(List.of("complete", "incomplete"), owner.lines);
  }

  @Test
  void malformedPayloadIsStillDeliveredAsAFramedLine() throws Exception {
    RecordingReadBoard owner = parseAll("{not-json\nre=bad\n");

    assertEquals(List.of("{not-json", "re=bad"), owner.lines);
  }

  @Test
  void closedStreamDoesNotParseFurtherInput() throws Exception {
    RecordingReadBoard owner = recordingOwner();
    ReadBoardStream stream =
        new ReadBoardStream(owner, new ByteArrayInputStream(utf8("ignored\nready\n")));
    stream.close();
    stream.run();

    assertEquals(List.of(), owner.lines);
    assertEquals(0, owner.readyCount);
  }

  private static RecordingReadBoard parseAll(String text) throws Exception {
    RecordingReadBoard owner = recordingOwner();
    ReadBoardStream stream = new ReadBoardStream(owner, new ByteArrayInputStream(utf8(text)));
    stream.run();
    return owner;
  }

  private static RecordingReadBoard recordingOwner() throws Exception {
    RecordingReadBoard owner = allocate(RecordingReadBoard.class);
    owner.lines = new ArrayList<>();
    return owner;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
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

  private static final class RecordingReadBoard extends ReadBoard {
    private List<String> lines;
    private int readyCount;

    private RecordingReadBoard() throws Exception {
      super(true, true);
    }

    @Override
    public void parseLine(String line) {
      lines.add(line);
    }

    @Override
    void handleReady() {
      readyCount++;
    }
  }

  private static final class ChunkedInputStream extends InputStream {
    private final byte[] bytes;
    private final int[] chunkSizes;
    private int position;
    private int chunkIndex;

    private ChunkedInputStream(byte[] bytes, int[] chunkSizes) {
      this.bytes = bytes;
      this.chunkSizes = chunkSizes;
    }

    @Override
    public int read() {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      if (position >= bytes.length || length <= 0) {
        return -1;
      }
      int chunk =
          chunkSizes.length == 0
              ? length
              : chunkSizes[Math.min(chunkIndex, chunkSizes.length - 1)];
      if (chunkIndex < chunkSizes.length) {
        chunkIndex++;
      }
      int n = Math.min(Math.min(length, Math.max(1, chunk)), bytes.length - position);
      System.arraycopy(bytes, position, buffer, offset, n);
      position += n;
      return n;
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
