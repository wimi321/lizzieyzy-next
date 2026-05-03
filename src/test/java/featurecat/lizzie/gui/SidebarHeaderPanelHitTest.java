package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class SidebarHeaderPanelHitTest {

  @Test
  void classicProblemTabUsesTheWholeVisibleLabel() {
    FontMetrics metrics = headerMetrics();

    assertEquals(0, SidebarHeaderPanel.primarySegmentIndexAt(new Point(18, 25), false, metrics));
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(58, 25), false, metrics),
        "clicking the first character of 问题手 should switch to the problem list.");
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(72, 25), false, metrics),
        "the old midpoint split cut through 问题手 and sent this click to 评论.");
    assertEquals(1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(90, 25), false, metrics));
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(58, 39), false, metrics),
        "the whole classic tab row should be clickable, not only the glyph pixels.");
    assertEquals(
        1,
        SidebarHeaderPanel.primarySegmentIndexAt(new Point(102, 25), false, metrics),
        "keep a forgiving right side for 问题手 without covering the inline filters.");
  }

  @Test
  void classicSeparatorGapDoesNotSwitchTabs() {
    FontMetrics metrics = headerMetrics();

    assertEquals(-1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(45, 25), false, metrics));
  }

  @Test
  void appleSegmentedControlStillUsesFullHalves() {
    FontMetrics metrics = headerMetrics();

    assertEquals(0, SidebarHeaderPanel.primarySegmentIndexAt(new Point(20, 24), true, metrics));
    assertEquals(1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(96, 24), true, metrics));
    assertEquals(-1, SidebarHeaderPanel.primarySegmentIndexAt(new Point(150, 24), true, metrics));
  }

  @Test
  void sideFilterHitTestingMatchesVisibleLabels() {
    FontMetrics metrics = headerMetrics();

    assertEquals(0, SidebarHeaderPanel.sideSegmentIndexAt(new Point(116, 25), false, metrics));
    assertEquals(1, SidebarHeaderPanel.sideSegmentIndexAt(new Point(160, 25), false, metrics));
    assertEquals(1, SidebarHeaderPanel.sideSegmentIndexAt(new Point(206, 25), false, metrics));
    assertEquals(0, SidebarHeaderPanel.sideSegmentIndexAt(new Point(156, 28), true, metrics));
    assertEquals(1, SidebarHeaderPanel.sideSegmentIndexAt(new Point(204, 28), true, metrics));
  }

  @Test
  void problemHeaderKeepsFiltersInlineWithoutTakingAnExtraRow() {
    assertEquals(48, SidebarHeaderPanel.preferredHeight(false, false));
    assertEquals(48, SidebarHeaderPanel.preferredHeight(true, false));
    assertEquals(56, SidebarHeaderPanel.preferredHeight(false, true));
    assertEquals(56, SidebarHeaderPanel.preferredHeight(true, true));
  }

  @Test
  void progressLabelExplainsTheRightSideCounter() {
    assertEquals("", SidebarHeaderPanel.progressLabelFor(snapshot(0, 0, false)));
    assertEquals("评估中 228/229", SidebarHeaderPanel.progressLabelFor(snapshot(228, 229, true)));
    assertEquals("已评估 228/229", SidebarHeaderPanel.progressLabelFor(snapshot(228, 229, false)));
    assertEquals("评估完成", SidebarHeaderPanel.progressLabelFor(snapshot(229, 229, false)));
    assertEquals(
        "问题手评估进度：已评估 228/229。", SidebarHeaderPanel.progressTooltipFor(snapshot(228, 229, false)));
  }

  private static FontMetrics headerMetrics() {
    BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setFont(new Font("Dialog", Font.BOLD, 12));
      return graphics.getFontMetrics();
    } finally {
      graphics.dispose();
    }
  }

  private static ProblemListSnapshot snapshot(int analyzedMoves, int totalMoves, boolean running) {
    return new ProblemListSnapshot(
        ProblemListMetric.WINRATE_LOSS,
        Collections.emptyList(),
        Collections.emptyList(),
        analyzedMoves,
        totalMoves,
        running);
  }
}
