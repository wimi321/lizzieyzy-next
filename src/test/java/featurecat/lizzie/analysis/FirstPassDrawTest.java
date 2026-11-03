package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FirstPassDrawTest {
  private static final String BUTTON_RULES =
      RulePreset.CHINESE_2027_DRAW_TO_FIRST_PASS.rules().toResponseLine();

  @Test
  void theFirstPassOnTheWayToThePositionDecides() {
    assertEquals(Optional.of(Stone.WHITE), FirstPassDraw.firstPasser(line("B", "W-", "B-", "W-")));
    assertEquals(Optional.of(Stone.BLACK), FirstPassDraw.firstPasser(line("B", "W", "B-", "W-")));
    assertEquals(Optional.empty(), FirstPassDraw.firstPasser(line("B", "W")));
  }

  @Test
  void onlyKataGosButtonRulesGiveTheDrawToTheFirstPasser() {
    BoardHistoryNode passed = line("B", "W-", "B-");
    assertTrue(FirstPassDraw.applies(BUTTON_RULES));
    assertEquals(Optional.of(Stone.WHITE), FirstPassDraw.drawWinner(BUTTON_RULES, passed));
    String chinese = KataGoRules.parse("chinese").orElseThrow().toResponseLine();
    assertFalse(FirstPassDraw.applies(chinese));
    assertEquals(Optional.empty(), FirstPassDraw.drawWinner(chinese, passed));
    assertFalse(FirstPassDraw.applies((String) null));
  }

  /** A line of moves from an empty board: "B" plays a stone, "W-" passes. */
  private static BoardHistoryNode line(String... moves) {
    BoardHistoryNode node = new BoardHistoryNode(BoardData.empty(19, 19));
    for (int index = 0; index < moves.length; index++) {
      Stone color = moves[index].startsWith("B") ? Stone.BLACK : Stone.WHITE;
      Stone[] stones = new Stone[19 * 19];
      Arrays.fill(stones, Stone.EMPTY);
      BoardData data =
          moves[index].endsWith("-")
              ? BoardData.pass(
                  stones,
                  color,
                  color.isWhite(),
                  new Zobrist(),
                  index + 1,
                  new int[361],
                  0,
                  0,
                  50,
                  0)
              : BoardData.move(
                  stones,
                  new int[] {index, 3},
                  color,
                  color.isWhite(),
                  new Zobrist(),
                  index + 1,
                  new int[361],
                  0,
                  0,
                  50,
                  0);
      BoardHistoryNode child = new BoardHistoryNode(data);
      node.variations.add(child);
      node.setPreviousForChild(child);
      node = child;
    }
    return node;
  }
}
