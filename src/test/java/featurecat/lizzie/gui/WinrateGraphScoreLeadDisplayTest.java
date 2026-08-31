package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.AppLocale;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class WinrateGraphScoreLeadDisplayTest {
  @Test
  void positiveScoreLeadUsesBlackPrefixAndEnglishTenths() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    assertEquals("B+7.3", WinrateGraph.formatScoreLead(7.3, english));
  }

  @Test
  void negativeScoreLeadUsesWhitePrefixAndEnglishTenths() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    assertEquals("W+4.6", WinrateGraph.formatScoreLead(-4.6, english));
  }

  @Test
  void exactZeroAndNearZeroDoNotShowALeaderOrNegativeZero() {
    ResourceBundle english = AppLocale.ENGLISH.loadBundle();

    assertEquals("0.0", WinrateGraph.formatScoreLead(0.0, english));
    assertEquals("0.0", WinrateGraph.formatScoreLead(-0.0, english));
    assertEquals("0.0", WinrateGraph.formatScoreLead(0.04, english));
    assertEquals("0.0", WinrateGraph.formatScoreLead(-0.04, english));
    assertEquals("B+0.1", WinrateGraph.formatScoreLead(0.05, english));
    assertEquals("W+0.1", WinrateGraph.formatScoreLead(-0.05, english));
  }

  @ParameterizedTest
  @EnumSource(
      value = AppLocale.class,
      names = {"SYSTEM"},
      mode = EnumSource.Mode.EXCLUDE)
  void scoreLeadPrefixesComeFromEveryMaintainedLocale(AppLocale locale) {
    ResourceBundle bundle = locale.loadBundle();

    assertEquals("B+", bundle.getString("WinrateGraph.scoreLeadBlackPrefix"));
    assertEquals("W+", bundle.getString("WinrateGraph.scoreLeadWhitePrefix"));
    assertEquals("B+7.3", WinrateGraph.formatScoreLead(7.3, bundle));
    assertEquals("W+4.6", WinrateGraph.formatScoreLead(-4.6, bundle));
    assertEquals("0.0", WinrateGraph.formatScoreLead(0.0, bundle));
  }

  @Test
  void baselineMarkFollowsRenderableMetrics() {
    assertEquals(
        "50%", WinrateGraph.baselineMark(WinrateGraph.resolveRenderableMetrics(true, false, true)));
    assertEquals(
        "0", WinrateGraph.baselineMark(WinrateGraph.resolveRenderableMetrics(false, true, true)));
    assertNull(WinrateGraph.baselineMark(WinrateGraph.resolveRenderableMetrics(true, true, true)));
    assertEquals(
        "50%", WinrateGraph.baselineMark(WinrateGraph.resolveRenderableMetrics(true, true, false)));
    assertNull(
        WinrateGraph.baselineMark(WinrateGraph.resolveRenderableMetrics(false, true, false)));
    assertNull(
        WinrateGraph.baselineMark(WinrateGraph.resolveRenderableMetrics(false, false, true)));
    assertTrue(
        WinrateGraph.hasHighlightedBaseline(
            WinrateGraph.resolveRenderableMetrics(true, true, true)));
    assertTrue(
        WinrateGraph.hasHighlightedBaseline(
            WinrateGraph.resolveRenderableMetrics(true, false, true)));
    assertTrue(
        WinrateGraph.hasHighlightedBaseline(
            WinrateGraph.resolveRenderableMetrics(false, true, true)));
    assertFalse(
        WinrateGraph.hasHighlightedBaseline(
            WinrateGraph.resolveRenderableMetrics(false, false, true)));
    assertFalse(WinrateGraph.hasHighlightedBaseline(null));
  }

  @Test
  void defaultThreeGridLinesSkipTheMidlineWhenBaselineIsActive() {
    assertTrue(WinrateGraph.shouldSkipOrdinaryMidlineGrid(2, 3, true));
    assertFalse(WinrateGraph.shouldSkipOrdinaryMidlineGrid(1, 3, true));
    assertFalse(WinrateGraph.shouldSkipOrdinaryMidlineGrid(3, 3, true));
    assertFalse(WinrateGraph.shouldSkipOrdinaryMidlineGrid(2, 3, false));
  }

  @Test
  void positiveScoreLeadSitsAboveTheMidlineAndNegativeBelow() {
    int graphY = 10;
    int graphHeight = 100;
    int midline = graphY + graphHeight / 2;

    assertTrue(WinrateGraph.scoreLeadAnchorY(graphY, graphHeight, 7.3, 15) < midline);
    assertTrue(WinrateGraph.scoreLeadAnchorY(graphY, graphHeight, -4.6, 15) > midline);
  }

  @Test
  void moveOneLastMoveHoverAndCurrentDoNotCoverBaselineOrEachOther() {
    int[][] layouts = {{280, 160}, {520, 360}, {180, 120}};
    for (int[] size : layouts) {
      assertNoOverlaps(layoutValueLabels(size[0], size[1]));
    }
  }

  private static List<Rectangle> layoutValueLabels(int width, int height) {
    int numMoves = 50;
    int textWidth = 36;
    int textHeight = 14;
    Rectangle bounds = new Rectangle(0, 0, width, height);
    Rectangle chip = WinrateGraph.baselineChipBox(0, height / 2, 48, textHeight);
    List<Rectangle> occupied = new ArrayList<>();
    occupied.add(chip);

    int[] moveIndexes = {1, numMoves, 12, 48};
    double[] scores = {7.3, -4.6, 0.8, -0.6};
    List<Rectangle> placed = new ArrayList<>();
    placed.add(chip);
    int[] winrateMoves = {1, numMoves};
    for (int moveIndex : winrateMoves) {
      int x = moveIndex * width / numMoves - textWidth / 2;
      int y = height / 2 - textHeight / 2;
      Rectangle box =
          WinrateGraph.placeGraphLabelBox(
              new Rectangle(x, y, textWidth, textHeight), bounds, occupied);
      occupied.add(box);
      placed.add(box);
    }
    for (int i = 0; i < moveIndexes.length; i++) {
      int x = moveIndexes[i] * width / numMoves - textWidth / 2;
      int y = WinrateGraph.scoreLeadAnchorY(0, height, scores[i], 15) - textHeight;
      Rectangle box =
          WinrateGraph.placeGraphLabelBox(
              new Rectangle(x, y, textWidth, textHeight), bounds, occupied);
      occupied.add(box);
      placed.add(box);
    }
    return placed;
  }

  private static void assertNoOverlaps(List<Rectangle> boxes) {
    for (int i = 0; i < boxes.size(); i++) {
      for (int j = i + 1; j < boxes.size(); j++) {
        assertFalse(
            boxes.get(i).intersects(boxes.get(j)),
            boxes.get(i) + " overlaps " + boxes.get(j) + " in " + boxes);
      }
    }
  }
}
