package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import org.junit.jupiter.api.Test;

class BlunderListPanelLayoutTest {

  @Test
  void problemListTracksViewportWidthInsteadOfStaleComponentWidth() {
    BlunderListPanel panel = new BlunderListPanel();
    JScrollPane scrollPane = new JScrollPane(panel);
    scrollPane.setSize(240, 320);
    scrollPane.getViewport().setSize(new Dimension(240, 320));
    panel.setSize(900, 50);

    assertTrue(panel.getScrollableTracksViewportWidth());
    assertEquals(240, panel.getPreferredSize().width);
    assertEquals(240, panel.getPreferredScrollableViewportSize().width);
  }

  @Test
  void shortProblemListFillsViewportAndLongProblemListScrollsVertically() {
    BlunderListPanel panel = new BlunderListPanel();
    JScrollPane scrollPane = new JScrollPane(panel);
    scrollPane.setSize(240, 320);
    scrollPane.getViewport().setSize(new Dimension(240, 320));

    assertTrue(panel.getScrollableTracksViewportHeight());

    panel.updateSnapshot(snapshotWithBlackRows(10));

    assertFalse(panel.getScrollableTracksViewportHeight());
    assertEquals(670, panel.getPreferredSize().height);
  }

  @Test
  void scrollIncrementsMatchProblemCardRhythm() {
    BlunderListPanel panel = new BlunderListPanel();
    Rectangle visible = new Rectangle(0, 0, 240, 320);

    assertEquals(32, panel.getScrollableUnitIncrement(visible, SwingConstants.VERTICAL, 1));
    assertEquals(256, panel.getScrollableBlockIncrement(visible, SwingConstants.VERTICAL, 1));
  }

  private static ProblemListSnapshot snapshotWithBlackRows(int rows) {
    List<ProblemMoveEntry> entries = new ArrayList<>();
    for (int i = 0; i < rows; i++) {
      entries.add(new ProblemMoveEntry(true, i + 1, "D4", 5.0 + i, 0.0, false, 1200, false, 3));
    }
    return new ProblemListSnapshot(
        ProblemListMetric.WINRATE_LOSS, entries, Collections.emptyList(), rows, rows, false);
  }
}
