package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct regression for the live tsumego construction entries {@link
 * Tsumego#getCoverSideAndIndex(boolean, boolean)} and {@link Tsumego#buildCoverWall(boolean,
 * boolean, boolean, boolean)}. The result is flattened through {@link
 * Board#flattenWithCondition(Stone[], Zobrist, boolean, java.util.List, double)} into a root-only
 * {@code SNAPSHOT}. Assertions lock current observable behavior, not internal loop order.
 */
class TsumegoTest {
  private static final int SIZE = 19;

  @Test
  void autoDetectsBlackWhenBlackBoundingBoxSurroundsWhite() throws Exception {
    try (Session session = Session.open()) {
      placeBlackSurroundsWhite(session.board);

      Stone side = new Tsumego().getCoverSideAndIndex(false, false);

      assertEquals(Stone.BLACK, side);
    }
  }

  @Test
  void autoDetectsWhiteWhenWhiteBoundingBoxSurroundsBlack() throws Exception {
    try (Session session = Session.open()) {
      placeWhiteSurroundsBlack(session.board);

      Stone side = new Tsumego().getCoverSideAndIndex(false, false);

      assertEquals(Stone.WHITE, side);
    }
  }

  @Test
  void forcedAttackerOverridesAutoDetection() throws Exception {
    try (Session session = Session.open()) {
      placeBlackSurroundsWhite(session.board);
      Tsumego tsumego = new Tsumego();

      assertEquals(Stone.WHITE, tsumego.getCoverSideAndIndex(true, false));
      assertEquals(Stone.BLACK, tsumego.getCoverSideAndIndex(true, true));
    }
  }

  @Test
  void closeBoundingBoxesLockCurrentCountFallbackToBlack() throws Exception {
    try (Session session = Session.open()) {
      // Same overall box after the nearest-edge subtraction; blackCount == whiteCount.
      place(session.board, Stone.BLACK, 8, 8, 9, 8);
      place(session.board, Stone.WHITE, 8, 9, 9, 9);

      Stone side = new Tsumego().getCoverSideAndIndex(false, false);

      assertEquals(Stone.BLACK, side);
    }
  }

  @Test
  void cornerEdgeAndCenterWallsStayOnBoardAndKeepOriginals() throws Exception {
    Stone[] corner = construct(1, TsumegoTest::placeCornerProblem, false, false, false, false);
    Stone[] edge = construct(1, TsumegoTest::placeLeftEdgeProblem, false, false, false, false);
    Stone[] center = construct(1, TsumegoTest::placeBlackSurroundsWhite, false, false, false, false);

    assertEquals(Stone.BLACK, corner[Board.getIndex(5, 2)]);
    assertEquals(Stone.BLACK, edge[Board.getIndex(4, 8)]);
    assertEquals(Stone.BLACK, center[Board.getIndex(6, 9)]);
    assertTrue(countOccupied(corner) > 6);
    assertTrue(countOccupied(edge) > 9);
    assertTrue(countOccupied(center) > 12);
  }

  @Test
  void twoWallDistancesMoveTheObservableCoverBox() throws Exception {
    Stone[] closeWall =
        construct(1, TsumegoTest::placeBlackSurroundsWhite, false, false, false, false);
    Stone[] farWall =
        construct(3, TsumegoTest::placeBlackSurroundsWhite, false, false, false, false);

    assertFalse(Arrays.equals(closeWall, farWall));
    // Group bbox is x=8..11, y=8..10. Distance 1 places the left wall on x=6;
    // distance 3 leaves that file inside the empty gap.
    assertEquals(Stone.BLACK, closeWall[Board.getIndex(6, 9)]);
    assertEquals(Stone.EMPTY, farWall[Board.getIndex(6, 9)]);
    assertEquals(Stone.BLACK, farWall[Board.getIndex(4, 9)]);
  }

  @Test
  void coverWallUsesForcedAttackerColorAndDoesNotCoverProblemStones() throws Exception {
    Stone[] blackAttack =
        construct(1, TsumegoTest::placeBlackSurroundsWhite, false, false, false, false);
    Stone[] whiteAttack =
        constructForced(
            1, TsumegoTest::placeBlackSurroundsWhite, true, false, false, false, false, false);
    Stone[] originals = originalBlackSurroundsWhite();

    int wallIndex = Board.getIndex(6, 9);
    assertEquals(Stone.BLACK, blackAttack[wallIndex]);
    assertEquals(Stone.WHITE, whiteAttack[wallIndex]);
  }

  @Test
  void koThreatFlagsChangeOnlyWhenThereIsRoom() throws Exception {
    Stone[] none = construct(1, TsumegoTest::placeBlackSurroundsWhite, false, false, false, false);
    Stone[] sideOnly =
        construct(1, TsumegoTest::placeBlackSurroundsWhite, true, false, false, false);
    Stone[] otherOnly =
        construct(1, TsumegoTest::placeBlackSurroundsWhite, false, true, false, false);
    Stone[] both = construct(1, TsumegoTest::placeBlackSurroundsWhite, true, true, false, false);
    Stone[] originals = originalBlackSurroundsWhite();

    assertEquals(
        List.of(
            "15,17=EMPTY",
            "16,17=WHITE",
            "17,17=WHITE",
            "17,18=WHITE",
            "18,17=WHITE",
            "18,18=EMPTY"),
        stoneDeltas(none, sideOnly));
    assertEquals(
        List.of(
            "0,0=EMPTY",
            "0,1=BLACK",
            "1,0=BLACK",
            "1,1=BLACK",
            "2,1=BLACK",
            "3,1=EMPTY"),
        stoneDeltas(none, otherOnly));
    assertEquals(
        List.of(
            "0,0=EMPTY",
            "0,1=BLACK",
            "1,0=BLACK",
            "1,1=BLACK",
            "2,1=BLACK",
            "3,1=EMPTY",
            "15,17=EMPTY",
            "16,17=WHITE",
            "17,17=WHITE",
            "17,18=WHITE",
            "18,17=WHITE",
            "18,18=EMPTY"),
        stoneDeltas(none, both));
    assertOriginalsPreserved(originals, none);
    assertOriginalsPreserved(originals, sideOnly);
    assertOriginalsPreserved(originals, otherOnly);
    assertOriginalsPreserved(originals, both);
  }

  @Test
  void insufficientKoRoomLeavesSideKoUnchangedVersusNoKo() throws Exception {
    Stone[] none = construct(1, TsumegoTest::placeTallLeftEdge, false, false, false, false);
    Stone[] sideKo = construct(1, TsumegoTest::placeTallLeftEdge, true, false, false, false);

    assertTrue(stoneDeltas(none, sideKo).isEmpty());
  }

  @Test
  void keepAndForcedSideToPlayLandOnRootSnapshot() throws Exception {
    try (Session keep = Session.open()) {
      placeBlackSurroundsWhite(keep.board);
      assertTrue(keep.board.setupSetSideToPlay(false));
      Stone[] originals = keep.board.getStones().clone();
      build(keep.board, false, false, false, false, false, false);
      assertRootOnlySnapshot(keep.board, originals);
      assertFalse(keep.board.getHistory().isBlacksTurn());
    }

    try (Session black = Session.open()) {
      placeBlackSurroundsWhite(black.board);
      assertTrue(black.board.setupSetSideToPlay(false));
      Stone[] originals = black.board.getStones().clone();
      build(black.board, false, false, false, false, true, true);
      assertRootOnlySnapshot(black.board, originals);
      assertTrue(black.board.getHistory().isBlacksTurn());
    }

    try (Session white = Session.open()) {
      placeBlackSurroundsWhite(white.board);
      Stone[] originals = white.board.getStones().clone();
      build(white.board, false, false, false, false, true, false);
      assertRootOnlySnapshot(white.board, originals);
      assertFalse(white.board.getHistory().isBlacksTurn());
    }
  }

  @Test
  void emptyBoardSingleStoneAndFourEdgeBoundingBoxNoOpStayInBounds() throws Exception {
    try (Session empty = Session.open()) {
      Stone[] originals = empty.board.getStones().clone();
      assertDoesNotThrow(() -> build(empty.board, false, false, false, false, false, false));
      assertRootOnlySnapshot(empty.board, originals);
    }

    try (Session single = Session.open()) {
      place(single.board, Stone.BLACK, 0, 0);
      Stone[] originals = single.board.getStones().clone();
      Stone side =
          assertDoesNotThrow(() -> build(single.board, false, false, false, false, false, false));
      // Per-color boxes start at the full board and never shrink, so auto side
      // is the count fallback: one black stone => BLACK.
      assertEquals(Stone.BLACK, side);
      assertRootOnlySnapshot(single.board, originals);
    }

    try (Session fourEdges = Session.open()) {
      placeFourEdgeAnchors(fourEdges.board);
      Stone[] originals = fourEdges.board.getStones().clone();
      assertEquals(4, countOccupied(originals));
      assertDoesNotThrow(() -> build(fourEdges.board, false, false, false, false, false, false));
      assertArrayEquals(originals, fourEdges.board.getStones());
      assertRootSnapshotShape(fourEdges.board);
      assertFalse(fourEdges.board.hasStartStone);
      assertTrue(fourEdges.board.getmovelistWithOutStartStone().isEmpty());
    }
  }

  @Test
  void nearlyFullBoardKeepsOccupancyAndDoesNotCorrupt() throws Exception {
    try (Session session = Session.open()) {
      placeNearlyFullBoard(session.board);
      Stone[] originals = session.board.getStones().clone();
      assertEquals(360, countOccupied(originals));
      assertDoesNotThrow(() -> build(session.board, false, false, true, true, false, false));
      assertEquals(360, countOccupied(session.board.getStones()));
      assertArrayEquals(originals, session.board.getStones());
      assertRootSnapshotShape(session.board);
      assertFalse(session.board.hasStartStone);
      assertTrue(session.board.getmovelistWithOutStartStone().isEmpty());
    }
  }

  private static Stone[] construct(
      int wallDistance,
      BoardSetup setup,
      boolean addKoThreatSide,
      boolean addKoThreatOtherSide,
      boolean forceToPlay,
      boolean blackToPlay)
      throws Exception {
    return constructForced(
        wallDistance,
        setup,
        false,
        false,
        addKoThreatSide,
        addKoThreatOtherSide,
        forceToPlay,
        blackToPlay);
  }

  private static Stone[] constructForced(
      int wallDistance,
      BoardSetup setup,
      boolean forceSide,
      boolean forceBlack,
      boolean addKoThreatSide,
      boolean addKoThreatOtherSide,
      boolean forceToPlay,
      boolean blackToPlay)
      throws Exception {
    try (Session session = Session.open(wallDistance)) {
      setup.place(session.board);
      Stone[] originals = session.board.getStones().clone();
      build(
          session.board,
          forceSide,
          forceBlack,
          addKoThreatSide,
          addKoThreatOtherSide,
          forceToPlay,
          blackToPlay);
      assertRootOnlySnapshot(session.board, originals);
      return session.board.getStones().clone();
    }
  }

  private static Stone build(
      Board board,
      boolean forceSide,
      boolean forceBlack,
      boolean addKoThreatSide,
      boolean addKoThreatOtherSide,
      boolean forceToPlay,
      boolean blackToPlay) {
    Tsumego tsumego = new Tsumego();
    Stone side = tsumego.getCoverSideAndIndex(forceSide, forceBlack);
    tsumego.buildCoverWall(addKoThreatSide, addKoThreatOtherSide, forceToPlay, blackToPlay);
    return side;
  }

  private static void assertRootOnlySnapshot(Board board, Stone[] originals) {
    assertRootSnapshotShape(board);
    assertOriginalsPreserved(originals, board.getStones());
    assertTrue(board.hasStartStone);
    assertFalse(board.startStonelist.isEmpty());
    for (Movelist move : board.startStonelist) {
      assertFalse(move.ispass);
      assertTrue(Board.isValid(move.x, move.y));
      int index = Board.getIndex(move.x, move.y);
      assertEquals(Stone.EMPTY, originals[index]);
      assertEquals(move.isblack ? Stone.BLACK : Stone.WHITE, board.getStones()[index]);
    }
    assertTrue(board.getmovelistWithOutStartStone().isEmpty());
    assertNoHistoryActionNodes(board);
  }

  private static void assertRootSnapshotShape(Board board) {
    BoardHistoryNode current = board.getHistory().getCurrentHistoryNode();
    BoardHistoryNode root = board.getHistory().getStart();
    BoardData data = current.getData();
    assertSame(root, current);
    assertFalse(current.previous().isPresent());
    assertEquals(0, current.numberOfChildren());
    assertTrue(data.isSnapshotNode());
    assertFalse(data.isMoveNode());
    assertFalse(data.isPassNode());
    assertFalse(data.isHistoryActionNode());
    assertEquals(0, data.moveNumber);
    assertEquals(0, board.getHistory().getMoveNumber());
    assertFalse(data.lastMove.isPresent());
    assertEquals(Stone.EMPTY, data.lastMoveColor);
    assertAllCoordinatesOnBoard(board.getStones());
  }

  private static void assertNoHistoryActionNodes(Board board) {
    BoardHistoryNode node = board.getHistory().getCurrentHistoryNode();
    while (node != null) {
      assertFalse(node.getData().isHistoryActionNode());
      node = node.previous().orElse(null);
    }
  }

  private static void assertOriginalsPreserved(Stone[] originals, Stone[] actual) {
    assertEquals(originals.length, actual.length);
    for (int i = 0; i < originals.length; i++) {
      if (originals[i] != Stone.EMPTY) {
        assertEquals(originals[i], actual[i]);
      }
    }
  }

  private static void assertAllCoordinatesOnBoard(Stone[] stones) {
    assertEquals(SIZE * SIZE, stones.length);
    for (int x = 0; x < SIZE; x++) {
      for (int y = 0; y < SIZE; y++) {
        Stone stone = stones[Board.getIndex(x, y)];
        assertTrue(stone == Stone.EMPTY || stone == Stone.BLACK || stone == Stone.WHITE);
      }
    }
  }

  private static int countOccupied(Stone[] stones) {
    int count = 0;
    for (Stone stone : stones) {
      if (stone != Stone.EMPTY) {
        count++;
      }
    }
    return count;
  }

  private static List<String> stoneDeltas(Stone[] baseline, Stone[] actual) {
    List<String> deltas = new ArrayList<String>();
    for (int x = 0; x < SIZE; x++) {
      for (int y = 0; y < SIZE; y++) {
        int index = Board.getIndex(x, y);
        if (baseline[index] != actual[index]) {
          deltas.add(x + "," + y + "=" + actual[index]);
        }
      }
    }
    return deltas;
  }

  private static Stone[] originalBlackSurroundsWhite() throws Exception {
    try (Session session = Session.open()) {
      placeBlackSurroundsWhite(session.board);
      return session.board.getStones().clone();
    }
  }

  private static void placeBlackSurroundsWhite(Board board) {
    place(board, Stone.WHITE, 9, 9, 10, 9);
    place(board, Stone.BLACK, 8, 8, 9, 8, 10, 8, 11, 8, 8, 9, 11, 9, 8, 10, 9, 10, 10, 10, 11, 10);
  }

  private static void placeWhiteSurroundsBlack(Board board) {
    place(board, Stone.BLACK, 9, 9, 10, 9);
    place(board, Stone.WHITE, 8, 8, 9, 8, 10, 8, 11, 8, 8, 9, 11, 9, 8, 10, 9, 10, 10, 10, 11, 10);
  }

  private static void placeCornerProblem(Board board) {
    place(board, Stone.WHITE, 1, 1, 2, 1);
    place(board, Stone.BLACK, 0, 0, 0, 1, 0, 2, 1, 2, 2, 2, 3, 1);
  }

  private static void placeLeftEdgeProblem(Board board) {
    place(board, Stone.WHITE, 0, 8, 1, 8);
    place(board, Stone.BLACK, 0, 7, 1, 7, 2, 7, 2, 8, 2, 9, 0, 9, 1, 9);
  }

  private static void placeTallLeftEdge(Board board) {
    for (int y = 1; y <= 17; y++) {
      place(board, Stone.BLACK, 0, y);
      place(board, Stone.WHITE, 1, y);
    }
  }

  private static void placeFourEdgeAnchors(Board board) {
    place(board, Stone.BLACK, 0, 9, 18, 9, 9, 0);
    place(board, Stone.WHITE, 9, 18);
  }

  private static void placeNearlyFullBoard(Board board) {
    for (int x = 0; x < SIZE; x++) {
      for (int y = 0; y < SIZE; y++) {
        if (x == 9 && y == 9) {
          continue;
        }
        place(board, ((x + y) % 2 == 0) ? Stone.BLACK : Stone.WHITE, x, y);
      }
    }
  }

  private static void place(Board board, Stone color, int... coordinates) {
    assertEquals(0, coordinates.length % 2);
    for (int i = 0; i < coordinates.length; i += 2) {
      assertTrue(board.setupPlaceStone(coordinates[i], coordinates[i + 1], color));
    }
  }

  @FunctionalInterface
  private interface BoardSetup {
    void place(Board board);
  }

  private static final class Session implements AutoCloseable {
    private final RulesLayerTestHarness harness;
    private final Board board;

    private Session(RulesLayerTestHarness harness) {
      this.harness = harness;
      this.board = harness.board();
      Lizzie.leelaz = null;
    }

    static Session open() throws Exception {
      return open(1);
    }

    static Session open(int wallDistance) throws Exception {
      RulesLayerTestHarness harness = RulesLayerTestHarness.open(SIZE);
      Lizzie.config.tsumeGoWallDistance = wallDistance;
      return new Session(harness);
    }

    @Override
    public void close() {
      harness.close();
    }
  }
}
