package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.gui.LizzieFrame;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct GIBParser.load regression: successful non-empty GIB must return true and keep explicit
 * STO/SKI as MOVE/PASS. Missing or empty inputs stay false without clearing a pre-existing board.
 */
class GIBParserTest {
  private static final int SIZE = 19;
  private static final int BLACK_X = 3;
  private static final int BLACK_Y = 3;
  private static final int WHITE_X = 15;
  private static final int WHITE_Y = 15;
  private static final int SEEDED_X = 4;
  private static final int SEEDED_Y = 4;
  private static final String BLACK_NAME = "BlackPlayer";
  private static final String WHITE_NAME = "WhitePlayer";
  private static final double KOMI = 6.5;

  /**
   * GIB player lines strip the last three characters ({@code \]\r} after a CRLF split), so the
   * fixture uses CRLF and the native {@code \]} terminator.
   */
  private static final String MINIMAL_GIB =
      "\\[GAMEINFOMAIN=GONGJE:65,\\]\r\n"
          + "\\[GAMEBLACKNAME="
          + BLACK_NAME
          + "\\]\r\n"
          + "\\[GAMEWHITENAME="
          + WHITE_NAME
          + "\\]\r\n"
          + "STO 0 1 1 "
          + BLACK_X
          + " "
          + BLACK_Y
          + "\n"
          + "STO 0 2 2 "
          + WHITE_X
          + " "
          + WHITE_Y
          + "\n"
          + "SKI 0 3\n";

  @TempDir Path tempDir;

  @Test
  void supportedGibLoadReturnsTrueAndKeepsExplicitMovesAndPass() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      Lizzie.config.loadSgfLast = false;
      Lizzie.config.readKomi = true;
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;
      Path file = writeGib("supported.gib", MINIMAL_GIB);

      assertTrue(GIBParser.load(file.toString()));

      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
      GameInfo info = env.board().getHistory().getGameInfo();
      assertEquals(BLACK_NAME, info.getPlayerBlack());
      assertEquals(WHITE_NAME, info.getPlayerWhite());
      assertEquals(KOMI, info.getKomi(), 0.0001);
      assertSame(env.board().getHistory().getStart(), env.current());
      assertEquals(BoardNodeKind.SNAPSHOT, env.current().getData().getNodeKind());
      assertEquals(Stone.EMPTY, env.stoneAt(BLACK_X, BLACK_Y));
      assertEquals(Stone.EMPTY, env.stoneAt(WHITE_X, WHITE_Y));
      assertExplicitStoSkiMainline(env.board().getHistory().getStart());
    }
  }

  @Test
  void loadSgfLastStopsOnLastExplicitAction() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      Lizzie.config.loadSgfLast = true;
      Lizzie.config.readKomi = true;
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;
      Path file = writeGib("supported-last.gib", MINIMAL_GIB);

      assertTrue(GIBParser.load(file.toString()));

      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
      BoardHistoryNode pass = lastRealAction(env.board().getHistory().getStart());
      assertSame(pass, env.current());
      assertEquals(BoardNodeKind.PASS, env.current().getData().getNodeKind());
      assertEquals(Stone.BLACK, env.stoneAt(BLACK_X, BLACK_Y));
      assertEquals(Stone.WHITE, env.stoneAt(WHITE_X, WHITE_Y));
      assertExplicitStoSkiMainline(env.board().getHistory().getStart());
    }
  }

  @Test
  void missingFileReturnsFalseWithoutClearingExistingBoard() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      seedExistingPosition(env);
      BoardHistoryNode before = env.current();
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;

      assertFalse(GIBParser.load(tempDir.resolve("missing.gib").toString()));

      assertSame(before, env.current());
      assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
    }
  }

  @Test
  void emptyFileReturnsFalseWithoutClearingExistingBoard() throws Exception {
    try (RulesLayerTestHarness env = openSizedHarness()) {
      seedExistingPosition(env);
      BoardHistoryNode before = env.current();
      Lizzie.config.playSound = true;
      EngineManager.isEmpty = false;
      Path empty = writeGib("empty.gib", "");

      assertFalse(GIBParser.load(empty.toString()));

      assertSame(before, env.current());
      assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
      assertFalse(EngineManager.isEmpty);
      assertTrue(Lizzie.config.playSound);
    }
  }

  private static RulesLayerTestHarness openSizedHarness() throws Exception {
    RulesLayerTestHarness env = RulesLayerTestHarness.open(SIZE);
    LizzieFrame.boardRenderer.setBoardLength(400, 400);
    return env;
  }

  private Path writeGib(String name, String body) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, body, StandardCharsets.UTF_8);
    return file;
  }

  private static void seedExistingPosition(RulesLayerTestHarness env) {
    env.board().place(SEEDED_X, SEEDED_Y, Stone.BLACK);
    assertEquals(Stone.BLACK, env.stoneAt(SEEDED_X, SEEDED_Y));
  }

  private static void assertExplicitStoSkiMainline(BoardHistoryNode root) {
    assertEquals(BoardNodeKind.SNAPSHOT, root.getData().getNodeKind());

    BoardHistoryNode blackMove = root.next().orElseThrow();
    assertEquals(BoardNodeKind.MOVE, blackMove.getData().getNodeKind());
    assertFalse(blackMove.getData().dummy);
    assertEquals(Stone.BLACK, blackMove.getData().lastMoveColor);
    assertTrue(RulesLayerTestHarness.sameLastMove(blackMove.getData(), BLACK_X, BLACK_Y));

    BoardHistoryNode whiteMove = blackMove.next().orElseThrow();
    assertEquals(BoardNodeKind.MOVE, whiteMove.getData().getNodeKind());
    assertFalse(whiteMove.getData().dummy);
    assertEquals(Stone.WHITE, whiteMove.getData().lastMoveColor);
    assertTrue(RulesLayerTestHarness.sameLastMove(whiteMove.getData(), WHITE_X, WHITE_Y));

    BoardHistoryNode pass = whiteMove.next().orElseThrow();
    assertEquals(BoardNodeKind.PASS, pass.getData().getNodeKind());
    assertFalse(pass.getData().dummy);
    assertEquals(Stone.BLACK, pass.getData().lastMoveColor);
    assertFalse(pass.getData().lastMove.isPresent());
    assertFalse(pass.next().isPresent());
    assertEquals(Stone.BLACK, pass.getData().stones[Board.getIndex(BLACK_X, BLACK_Y)]);
    assertEquals(Stone.WHITE, pass.getData().stones[Board.getIndex(WHITE_X, WHITE_Y)]);
  }

  private static BoardHistoryNode lastRealAction(BoardHistoryNode root) {
    BoardHistoryNode node = root;
    while (node.next().isPresent()) {
      node = node.next().orElseThrow();
    }
    return node;
  }
}
