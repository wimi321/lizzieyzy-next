package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.rules.Zobrist;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class EmptyBoardHintTest {
  private static final ResourceBundle CHINESE =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

  @Test
  void aFreshBoardIsEmpty() {
    assertTrue(EmptyBoardHint.isEmptyGame(new BoardHistoryList(BoardData.empty(19, 19))));
  }

  @Test
  void aPlayedMoveOrASetupStoneEndsTheHint() {
    BoardHistoryList played = new BoardHistoryList(BoardData.empty(19, 19));
    played.add(move(Stone.BLACK, 3, 3));
    assertFalse(EmptyBoardHint.isEmptyGame(played), "a move was played");
    played.previous();
    assertFalse(EmptyBoardHint.isEmptyGame(played), "back at the start, the game still has moves");

    BoardData handicap = BoardData.empty(19, 19);
    handicap.stones[3 * 19 + 3] = Stone.BLACK;
    assertFalse(EmptyBoardHint.isEmptyGame(new BoardHistoryList(handicap)), "handicap stones");
    assertFalse(EmptyBoardHint.isEmptyGame(null));
  }

  @Test
  void theEnginesAnalysisOfTheEmptyBoardTakesOverFromTheHint() {
    BoardHistoryList fresh = new BoardHistoryList(BoardData.empty(19, 19));
    assertTrue(EmptyBoardHint.shouldShow(fresh));

    fresh.getStart().getData().setPlayoutsForce(120);

    assertTrue(EmptyBoardHint.isEmptyGame(fresh), "still no game");
    assertFalse(EmptyBoardHint.shouldShow(fresh), "the candidates are not covered");
  }

  @Test
  void onlySomeoneWhoNeverOpenedAGameSeesItOnTheMainBoard() {
    BoardHistoryList fresh = new BoardHistoryList(BoardData.empty(19, 19));

    assertTrue(EmptyBoardHint.shows(true, true, fresh));
    assertFalse(EmptyBoardHint.shows(true, false, fresh), "a game was opened before");
    assertFalse(EmptyBoardHint.shows(false, true, fresh), "not the main board");
    assertFalse(EmptyBoardHint.shows(true, true, null));
  }

  @Test
  void hintIsPaintedOnlyWhenShown() {
    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    assertTrue(painted(render(true, font)) > 0, "the hint is drawn on an empty board");
    assertTrue(painted(render(false, font)) == 0, "nothing is drawn once there is a game");
  }

  private static BufferedImage render(boolean show, Font font) {
    BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    EmptyBoardHint.paint(g, show, CHINESE, font, 0, 0, 600, 600);
    g.dispose();
    return image;
  }

  private static int painted(BufferedImage image) {
    int count = 0;
    for (int y = 0; y < image.getHeight(); y += 2) {
      for (int x = 0; x < image.getWidth(); x += 2) {
        if ((image.getRGB(x, y) >>> 24) > 0) count++;
      }
    }
    return count;
  }

  private static BoardData move(Stone color, int x, int y) {
    Stone[] stones = BoardData.empty(19, 19).stones;
    stones[x * 19 + y] = color;
    return BoardData.move(
        stones, new int[] {x, y}, color, false, new Zobrist(), 1, new int[361], 0, 0, 50, 0);
  }
}
