package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MoveShapesTest {
  @Test
  void tacticalMovesAreNamedFirst() {
    assertEquals("capture (提子) of W A19", black("A18", "W A19", "B B19"));
    assertEquals("atari (打吃) on W K10", black("L10", "W K10", "B K11", "B J10"));
    assertEquals("connection (粘) joining B D4 F4", black("E4", "B D4", "B F4"));
    assertEquals("wedge (挖) between W D4 and F4", black("E4", "W D4", "W F4"));
    assertEquals("cut (断) between W D4 and E5", black("E4", "W D4", "W E5", "B D5"));
  }

  @Test
  void contactMovesAreNamedByTheStonesTheyTouch() {
    assertEquals("hane (扳) against W R16", black("R17", "B Q16", "W R16"));
    assertEquals("extension (长) from B Q16", black("Q15", "B Q16", "W R16"));
    assertEquals("attachment (靠) to W Q16", black("R16", "W Q16"));
    assertEquals("", black("P16", "B Q16", "W R16"), "pulling back is not an extension");
    assertEquals("", black("L10", "B K10", "W L11"), "touching both colours is not named");
  }

  @Test
  void cornerMovesFollowTheStonesInTheCorner() {
    assertEquals("move in an empty corner (占角)", black("Q16"));
    assertEquals("move in an empty corner (占角)", black("D4"));
    assertEquals("3-3 invasion (点三三) under W Q16", black("R17", "W Q16"));
    assertEquals("corner approach (挂角) to W Q16", black("R14", "W Q16"));
    assertEquals("corner enclosure (守角) as a knight's move (小飞) from B Q16", black("R14", "B Q16"));
    assertEquals("", black("R14", "W Q16", "W O17"), "an enclosed corner is not approached");
    assertEquals("", black("S18", "B Q16"), "the 2-2 point does not enclose a corner");
  }

  @Test
  void movesAwayFromContactAreNamedByTheirOwnStones() {
    assertEquals("extension along the side (拆二) from B R13", black("R10", "B R13"));
    assertEquals("diagonal move (尖) from B K10", black("L11", "B K10"));
    assertEquals("one-space jump (跳) from B K10", black("K12", "B K10"));
    assertEquals("knight's move (小飞) from B K10", black("L12", "B K10"));
    assertEquals("two-space jump (大跳) from B K10", black("K13", "B K10"));
    assertEquals("large knight's move (大飞) from B K10", black("L13", "B K10"));
    assertEquals("", black("K13", "B K10", "W K11"), "a blocked jump is not a jump");
    assertEquals("invasion (打入)", black("K10", "W H10", "W M10", "W K13", "W K7"));
  }

  @Test
  void whiteMovesAndUnnamedMovesWork() {
    assertEquals("3-3 invasion (点三三) under B D4", shape("C3", false, "B D4"));
    assertEquals("", black("K10"), "the centre of an empty board has no name");
    assertEquals("", black("pass", "B K10"));
    assertEquals("", black("K10", "B K10"), "an occupied point");
  }

  private static String black(String move, String... stones) {
    return shape(move, true, stones);
  }

  private static String shape(String move, boolean black, String... stones) {
    List<Stone> board = new ArrayList<>(Collections.nCopies(19 * 19, Stone.EMPTY));
    for (String stone : stones) {
      String point = stone.substring(2);
      char column = point.charAt(0);
      int x = column - 'A' - (column > 'I' ? 1 : 0);
      int y = 19 - Integer.parseInt(point.substring(1));
      board.set(x * 19 + y, stone.startsWith("B") ? Stone.BLACK : Stone.WHITE);
    }
    Optional<String> named = MoveShapes.describe(board, 19, 19, move, black);
    return named.orElse("");
  }
}
