package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.LizzieFrame;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ReadBoardFoxMoveNumberParsingTest {
  @Test
  void validLastMoveSourceUpdatesPendingMetadata() throws Exception {
    assertLastMoveSource("lastMoveSource none", ReadBoardLastMoveSource.NONE);
    assertLastMoveSource("lastMoveSource foxCornerFlip", ReadBoardLastMoveSource.FOX_CORNER_FLIP);
    assertLastMoveSource("lastMoveSource redBlueMarker", ReadBoardLastMoveSource.RED_BLUE_MARKER);
    assertLastMoveSource("lastMoveSource deviation", ReadBoardLastMoveSource.DEVIATION);
    assertLastMoveSource("lastMoveSource stoneCount", ReadBoardLastMoveSource.STONE_COUNT);
  }

  @Test
  void unknownLastMoveSourceReplacesPreviousTrustedMetadata() throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);

    readBoard.parseLine("lastMoveSource foxCornerFlip");
    readBoard.parseLine("lastMoveSource nonsense");

    assertEquals(ReadBoardLastMoveSource.UNKNOWN, pendingContext(readBoard).lastMoveSource);
  }

  @Test
  void invalidLastMoveSourceLineDoesNotThrow() throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);

    assertDoesNotThrow(() -> readBoard.parseLine("lastMoveSource"));
    assertDoesNotThrow(() -> readBoard.parseLine(""));
  }

  @Test
  void lastMoveSourceDoesNotLeakAcrossControlReset() throws Exception {
    LizzieFrame previousFrame = Lizzie.frame;
    Leelaz previousLeelaz = Lizzie.leelaz;
    try {
      ReadBoard readBoard = allocate(ReadBoard.class);
      initializeReadBoardControlResetState(readBoard);
      Lizzie.frame = allocate(LizzieFrame.class);
      Lizzie.leelaz = SnapshotTrackingLeelaz.create();

      readBoard.parseLine("lastMoveSource foxCornerFlip");
      readBoard.parseLine("stopsync");

      assertEquals(ReadBoardLastMoveSource.LEGACY_UNKNOWN, pendingContext(readBoard).lastMoveSource);
    } finally {
      Lizzie.frame = previousFrame;
      Lizzie.leelaz = previousLeelaz;
    }
  }

  @Test
  void invalidFoxMoveNumberDoesNotThrowOrClearPendingMetadata() throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);
    setField(
        readBoard,
        "pendingRemoteContext",
        SyncRemoteContext.forFoxLive(OptionalInt.of(18), "43581号", OptionalInt.empty(), false));

    assertDoesNotThrow(() -> readBoard.parseLine("foxMoveNumber nope"));

    SyncRemoteContext pendingRemoteContext =
        (SyncRemoteContext) getField(readBoard, "pendingRemoteContext");
    assertTrue(pendingRemoteContext.foxMoveNumber.isPresent());
    assertEquals(18, pendingRemoteContext.foxMoveNumber.getAsInt());
  }

  @Test
  void validFoxMoveNumberUpdatesPendingMetadata() throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);

    readBoard.parseLine("foxMoveNumber 42");

    SyncRemoteContext pendingRemoteContext =
        (SyncRemoteContext) getField(readBoard, "pendingRemoteContext");
    assertTrue(pendingRemoteContext.foxMoveNumber.isPresent());
    assertEquals(42, pendingRemoteContext.foxMoveNumber.getAsInt());
  }

  @Test
  void recordAtEndFallsBackToTotalMoveForFoxRecovery() {
    SyncRemoteContext remoteContext =
        SyncRemoteContext.forFoxRecord(
            OptionalInt.of(256),
            OptionalInt.empty(),
            OptionalInt.of(256),
            true,
            "record-fingerprint",
            false);

    assertTrue(remoteContext.recoveryMoveNumber().isPresent());
    assertEquals(256, remoteContext.recoveryMoveNumber().getAsInt());
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

  private static void assertLastMoveSource(String line, ReadBoardLastMoveSource expected)
      throws Exception {
    ReadBoard readBoard = allocate(ReadBoard.class);

    readBoard.parseLine(line);

    assertEquals(expected, pendingContext(readBoard).lastMoveSource);
  }

  private static SyncRemoteContext pendingContext(ReadBoard readBoard) throws Exception {
    return (SyncRemoteContext) getField(readBoard, "pendingRemoteContext");
  }

  private static void initializeReadBoardControlResetState(ReadBoard readBoard) throws Exception {
    setField(readBoard, "conflictTracker", new SyncConflictTracker());
    setField(readBoard, "historyJumpTracker", new SyncHistoryJumpTracker());
    setField(readBoard, "localNavigationTracker", new SyncLocalNavigationTracker());
    setField(readBoard, "tempcount", new ArrayList<Integer>());
  }

  private static Object getField(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
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
