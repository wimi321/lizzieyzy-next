package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReadBoardUpdateProtocolTest {
  @Test
  void parseHostedUpdateRequestAcceptsVersionTagAndAbsoluteZipPath() {
    ReadBoardUpdateRequest request =
        ReadBoardUpdateRequest.tryParse(
            "readboardUpdateReady\tv3.0.2\tC:\\updates\\readboard-github-release-v3.0.2.zip\r\n");

    assertNotNull(request);
    assertEquals("v3.0.2", request.versionTag());
    assertEquals(
        new File("C:\\updates\\readboard-github-release-v3.0.2.zip").getAbsoluteFile(),
        request.zipPath());
  }

  @Test
  void parseHostedUpdateRequestRejectsMalformedPayloads() {
    assertNull(ReadBoardUpdateRequest.tryParse(null));
    assertNull(ReadBoardUpdateRequest.tryParse("readboardUpdateReady"));
    assertNull(
        ReadBoardUpdateRequest.tryParse(
            "readboardUpdateReady\t3.0.2\tC:\\updates\\readboard-github-release-v3.0.2.zip"));
    assertNull(
        ReadBoardUpdateRequest.tryParse(
            "readboardUpdateReady\tv3.0.2\treadboard-github-release-v3.0.2.zip"));
    assertNull(
        ReadBoardUpdateRequest.tryParse("readboardUpdateReady\tv3.0.2\tC:\\a.zip\textra-field"));
  }

  @Test
  void announceHostedUpdateSupportOnlyUsesNativePipeMode() throws Exception {
    ReadBoard nativePipeReadBoard = allocate(ReadBoard.class);
    ReadBoard socketReadBoard = allocate(ReadBoard.class);

    TrackingBufferedOutputStream nativePipeOutput = new TrackingBufferedOutputStream();
    TrackingBufferedOutputStream socketOutput = new TrackingBufferedOutputStream();

    setField(nativePipeReadBoard, "usePipe", true);
    setField(nativePipeReadBoard, "outputStream", nativePipeOutput);

    setField(socketReadBoard, "usePipe", false);
    setField(socketReadBoard, "outputStream", socketOutput);

    nativePipeReadBoard.announceHostedUpdateSupport();
    socketReadBoard.announceHostedUpdateSupport();

    assertEquals("readboardUpdateSupported\n", nativePipeOutput.writtenText());
    assertEquals("", socketOutput.writtenText());
    assertTrue(nativePipeReadBoard.shouldAnnounceHostedUpdateSupport());
    assertFalse(socketReadBoard.shouldAnnounceHostedUpdateSupport());
  }

  @Test
  void nativePipeReadySendsInitialAnalysisStateOnce() throws Exception {
    Leelaz previousLeelaz = Lizzie.leelaz;
    try {
      SnapshotTrackingLeelaz leelaz = SnapshotTrackingLeelaz.create();
      leelaz.Pondering();
      Lizzie.leelaz = leelaz;
      ReadBoard readBoard = allocate(ReadBoard.class);
      TrackingBufferedOutputStream output = new TrackingBufferedOutputStream();
      setField(readBoard, "usePipe", true);
      setField(readBoard, "outputStream", output);

      readBoard.handleReady();
      readBoard.handleReady();

      assertEquals(
          "version\nreadboardUpdateSupported\nanalysisState running\n", output.writtenText());
    } finally {
      Lizzie.leelaz = previousLeelaz;
    }
  }

  @Test
  void nativePipeReadyReportsPausedWhenEngineIsNotInitialized() throws Exception {
    Leelaz previousLeelaz = Lizzie.leelaz;
    try {
      Lizzie.leelaz = null;
      ReadBoard readBoard = allocate(ReadBoard.class);
      TrackingBufferedOutputStream output = new TrackingBufferedOutputStream();
      setField(readBoard, "usePipe", true);
      setField(readBoard, "outputStream", output);

      readBoard.handleReady();

      assertEquals(
          "version\nreadboardUpdateSupported\nanalysisState paused\n", output.writtenText());
    } finally {
      Lizzie.leelaz = previousLeelaz;
    }
  }

  @Test
  void parseLineRoutesHostedUpdateRequestToFrameHandler() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    try {
      TrackingLizzieFrame frame = allocate(TrackingLizzieFrame.class);
      ReadBoard readBoard = allocate(ReadBoard.class);
      Lizzie.frame = frame;

      readBoard.parseLine(
          "readboardUpdateReady\tv3.0.2\tC:\\updates\\readboard-github-release-v3.0.2.zip");

      assertNotNull(frame.lastUpdateRequest);
      assertEquals("v3.0.2", frame.lastUpdateRequest.versionTag());
      assertEquals(readBoard, frame.lastSourceReadBoard);
    } finally {
      Lizzie.frame = previousFrame;
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class TrackingBufferedOutputStream extends BufferedOutputStream {
    private final ByteArrayOutputStream capture;

    private TrackingBufferedOutputStream() {
      this(new ByteArrayOutputStream());
    }

    private TrackingBufferedOutputStream(ByteArrayOutputStream capture) {
      super(capture);
      this.capture = capture;
    }

    private String writtenText() {
      return capture.toString(StandardCharsets.UTF_8);
    }
  }

  private static final class TrackingLizzieFrame extends LizzieFrame {
    private ReadBoard lastSourceReadBoard;
    private ReadBoardUpdateRequest lastUpdateRequest;

    @Override
    public void handleReadBoardHostedUpdateRequest(
        ReadBoard sourceReadBoard, ReadBoardUpdateRequest request) {
      lastSourceReadBoard = sourceReadBoard;
      lastUpdateRequest = request;
    }
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
}
