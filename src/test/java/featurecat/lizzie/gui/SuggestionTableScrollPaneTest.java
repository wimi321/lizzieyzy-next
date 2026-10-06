package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.MouseWheelEvent;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JScrollBar;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.Test;

class SuggestionTableScrollPaneTest {
  private static final class Fixture {
    final JTable table;
    final SuggestionTableScrollPane pane;

    Fixture(int rows) {
      table = new JTable(new DefaultTableModel(rows, 6));
      table.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
      pane = new SuggestionTableScrollPane(table);
      pane.setColumnHeaderView(table.getTableHeader());
      pane.setBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY));
      pane.getVerticalScrollBar().setUI(new DemoScrollBarUI2(false));
      pane.refreshStyle(25);
      pane.setSize(450, 250);
      layout();
    }

    void budget(int height) {
      pane.setSize(450, pane.getViewport().getY() + pane.getInsets().bottom + height);
      layout();
    }

    void layout() {
      pane.doLayout();
      pane.getViewport().doLayout();
      table.doLayout();
    }

    int y() {
      return pane.getViewport().getViewPosition().y;
    }

    int extent() {
      return pane.getViewport().getHeight();
    }

    void aligned() {
      assertEquals(0, extent() % table.getRowHeight());
      assertEquals(0, y() % table.getRowHeight());
      assertTrue(y() >= 0);
      assertTrue(
          pane.getVerticalScrollBar().getMaximum()
              >= pane.getVerticalScrollBar().getVisibleAmount());
    }
  }

  private static void edt(Runnable action) throws Exception {
    SwingUtilities.invokeAndWait(action);
  }

  private static void settle() throws Exception {
    edt(() -> {});
    edt(() -> {});
  }

  @Test
  void tightensOnlyForTheNextCompleteRowWithinBothBudgets() throws Exception {
    edt(
        () -> {
          Fixture f = new Fixture(20);
          int[][] cases = {
            {175, 25, 175},
            {174, 24, 168},
            {162, 23, 161},
            {160, 25, 150},
            {12, 25, 0},
            {0, 25, 0},
            {175, 25, 175}
          };
          for (int[] c : cases) {
            f.budget(c[0]);
            assertEquals(c[1], f.table.getRowHeight(), "budget " + c[0]);
            assertEquals(c[2], f.extent(), "budget " + c[0]);
            f.aligned();
          }
          f.table.setRowMargin(8);
          f.pane.refreshStyle(25);
          f.budget(174);
          Component renderer =
              f.table
                  .getDefaultRenderer(Object.class)
                  .getTableCellRendererComponent(f.table, "Text", false, false, 0, 0);
          int safe =
              renderer.getFontMetrics(renderer.getFont()).getHeight()
                  + ((javax.swing.JComponent) renderer).getInsets().top
                  + ((javax.swing.JComponent) renderer).getInsets().bottom
                  + 8
                  + 2;
          assertTrue(f.table.getRowHeight() >= safe);
          f.aligned();
        });
  }

  @Test
  void countDoesNotDetermineDensityAndLastCandidateRemainsReachable() throws Exception {
    edt(
        () -> {
          for (int count : new int[] {0, 1, 7, 20}) {
            Fixture f = new Fixture(count);
            f.budget(174);
            assertEquals(24, f.table.getRowHeight());
            assertEquals(168, f.extent());
            assertEquals(count, f.table.getRowCount());
            f.pane.getVerticalScrollBar().setValue(Integer.MAX_VALUE);
            f.layout();
            f.aligned();
            assertEquals(Math.max(0, count - 7) * 24, f.y());
            if (count > 0) {
              assertTrue(f.table.getCellRect(count - 1, 0, true).y < f.y() + f.extent());
            }
            assertTrue(f.pane.getVerticalScrollBar().isVisible());
            assertTrue(
                f.pane.getViewport().getX() + f.pane.getViewport().getWidth()
                    <= f.pane.getVerticalScrollBar().getX());
          }
        });
  }

  @Test
  void dragRemainsContinuousAndReleaseSnapsToNearestRow() throws Exception {
    Fixture[] ref = new Fixture[1];
    edt(
        () -> {
          Fixture f = ref[0] = new Fixture(20);
          f.budget(175);
          JScrollBar bar = f.pane.getVerticalScrollBar();
          bar.setValueIsAdjusting(true);
          bar.setValue(38);
          assertEquals(38, f.y());
          f.layout();
          assertEquals(38, f.y(), "layout must not interrupt continuous dragging");
          bar.setValue(63);
          assertEquals(63, f.y());
          bar.setValueIsAdjusting(false);
          f.layout();
        });
    settle();
    edt(
        () -> {
          assertEquals(75, ref[0].y());
          ref[0].aligned();
        });
  }

  @Test
  void wheelKeyboardAndTrackActionsConvergeOnWholeRows() throws Exception {
    Fixture[] ref = new Fixture[1];
    edt(
        () -> {
          Fixture f = ref[0] = new Fixture(20);
          f.budget(175);
          f.pane.dispatchEvent(
              new MouseWheelEvent(
                  f.pane,
                  MouseWheelEvent.MOUSE_WHEEL,
                  System.currentTimeMillis(),
                  0,
                  40,
                  40,
                  0,
                  false,
                  MouseWheelEvent.WHEEL_UNIT_SCROLL,
                  3,
                  1));
        });
    settle();
    edt(
        () -> {
          assertTrue(ref[0].y() > 0);
          ref[0].aligned();
        });
    for (String actionName :
        new String[] {
          "scrollDown", "scrollUp", "unitScrollDown", "unitScrollUp", "scrollEnd", "scrollHome"
        }) {
      edt(
          () -> {
            Fixture f = ref[0];
            Action action = f.pane.getActionMap().get(actionName);
            assertNotNull(action, actionName);
            action.actionPerformed(
                new ActionEvent(f.pane, ActionEvent.ACTION_PERFORMED, actionName));
          });
      settle();
      edt(
          () -> {
            ref[0].aligned();
            if (actionName.equals("scrollEnd")) assertEquals(325, ref[0].y());
            if (actionName.equals("scrollHome")) assertEquals(0, ref[0].y());
          });
    }
    edt(() -> ref[0].pane.getVerticalScrollBar().setValue(89));
    settle();
    edt(() -> assertEquals(100, ref[0].y()));
  }

  @Test
  void resizeAndStylePreserveTopRowOrAnActualOverflowBottom() throws Exception {
    edt(
        () -> {
          Fixture f = new Fixture(20);
          f.budget(175);
          f.pane.getVerticalScrollBar().setValue(75);
          for (int i = 0; i < 8; i++) {
            f.budget(174);
            assertEquals(72, f.y());
            assertEquals(24, f.table.getRowHeight());
            f.budget(175);
            assertEquals(75, f.y());
            assertEquals(25, f.table.getRowHeight());
          }
          f.pane.refreshStyle(30);
          f.budget(180);
          assertEquals(90, f.y());
          f.pane.getVerticalScrollBar().setValue(Integer.MAX_VALUE);
          f.budget(240);
          assertEquals(360, f.y());
          f.aligned();
          Fixture shortTable = new Fixture(7);
          shortTable.budget(175);
          shortTable.budget(100);
          assertEquals(0, shortTable.y(), "short table is not a request to follow bottom");
        });
  }

  @Test
  void tinySpacePreservesAnchorAndBackgroundCannotHitCandidates() throws Exception {
    Fixture[] ref = new Fixture[1];
    edt(
        () -> {
          Fixture f = ref[0] = new Fixture(20);
          f.budget(175);
          f.pane.getVerticalScrollBar().setValue(75);
          f.budget(12);
          assertEquals(0, f.extent());
          assertFalse(f.pane.getVerticalScrollBar().isEnabled());
          assertTrue(f.pane.getColumnHeader().isVisible());
          Point blank = new Point(40, f.pane.getViewport().getY() + 3);
          assertNotSame(f.table, SwingUtilities.getDeepestComponentAt(f.pane, blank.x, blank.y));
          f.pane.setSize(450, 3);
          f.layout();
          assertEquals(0, f.extent());
          assertFalse(f.pane.getColumnHeader().isVisible());
          f.pane.getVerticalScrollBar().setValue(999);
        });
    settle();
    edt(
        () -> {
          Fixture f = ref[0];
          f.pane.setSize(450, 250);
          f.layout();
          f.budget(175);
          assertEquals(75, f.y());
          assertTrue(f.pane.getColumnHeader().isVisible());
          assertTrue(f.pane.getVerticalScrollBar().isEnabled());
          f.aligned();
          assertEquals(3, f.table.rowAtPoint(new Point(20, f.y() + 1)));
          assertEquals(9, f.table.rowAtPoint(new Point(20, f.y() + f.extent() - 1)));
          f.budget(160);
          int below = f.pane.getViewport().getY() + f.extent() + 1;
          assertNotSame(f.table, SwingUtilities.getDeepestComponentAt(f.pane, 20, below));
        });
  }

  @Test
  void silentCandidateRefreshClampsRangeWithoutChangingDensityOrFollowingGrowth() throws Exception {
    edt(
        () -> {
          int[] count = {20};
          int[] generation = {0};
          JTable table =
              new JTable(
                  new javax.swing.table.AbstractTableModel() {
                    public int getRowCount() {
                      return count[0];
                    }

                    public int getColumnCount() {
                      return 1;
                    }

                    public Object getValueAt(int row, int column) {
                      return generation[0] + ":" + (count[0] - row);
                    }
                  });
          table.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
          SuggestionTableScrollPane pane = new SuggestionTableScrollPane(table);
          pane.setBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY));
          pane.refreshStyle(25);
          pane.setSize(300, 177);
          pane.doLayout();
          assertEquals(175, pane.getViewport().getHeight());
          pane.getVerticalScrollBar().setValue(325);
          for (int rows : new int[] {12, 7, 0, 20}) {
            count[0] = rows;
            pane.refreshData();
            assertEquals(rows == 12 ? 125 : 0, pane.getViewport().getViewPosition().y);
            assertEquals(Math.max(175, rows * 25), pane.getVerticalScrollBar().getMaximum());
            assertEquals(25, table.getRowHeight());
            assertEquals(177, pane.getHeight());
          }
          pane.getVerticalScrollBar().setValue(75);
          for (int i = 1; i <= 30; i++) {
            generation[0] = i;
            pane.refreshData();
            assertEquals(i + ":17", table.getValueAt(3, 0));
            assertEquals(75, pane.getViewport().getViewPosition().y);
            assertEquals(175, pane.getViewport().getHeight());
            assertEquals(25, table.getRowHeight());
          }
          pane.getVerticalScrollBar().setValue(Integer.MAX_VALUE);
          assertEquals(325, pane.getViewport().getViewPosition().y);
        });
  }

  @Test
  void refreshDuringDragCollapseAndStyleChangeUsesLatestCandidates() throws Exception {
    Fixture[] ref = new Fixture[1];
    edt(
        () -> {
          Fixture f = ref[0] = new Fixture(20);
          DefaultTableModel model = (DefaultTableModel) f.table.getModel();
          f.budget(175);
          JScrollBar bar = f.pane.getVerticalScrollBar();
          bar.setValueIsAdjusting(true);
          bar.setValue(163);
          model.setRowCount(12);
          f.pane.refreshData();
          assertEquals(125, f.y());
          bar.setValue(63);
          model.setRowCount(15);
          f.pane.refreshData();
          assertEquals(63, f.y(), "data refresh must not snap an active drag");
          bar.setValueIsAdjusting(false);
        });
    settle();
    edt(
        () -> {
          Fixture f = ref[0];
          DefaultTableModel model = (DefaultTableModel) f.table.getModel();
          assertEquals(75, f.y());
          f.budget(12);
          model.setRowCount(0);
          f.pane.refreshData();
          assertEquals(0, f.extent());
          assertEquals(0, f.y());
          model.setRowCount(20);
          f.pane.refreshData();
          f.pane.refreshStyle(30);
          f.budget(180);
          assertEquals(90, f.y(), "collapse retains the row anchor until recovery");
          f.budget(12);
          model.setRowCount(7);
          f.pane.refreshData();
          f.budget(180);
          assertEquals(30, f.y(), "recovery clamps against current candidates");
          f.budget(12);
          model.setRowCount(0);
          f.pane.refreshData();
          f.budget(180);
          assertEquals(0, f.y());
          model.setRowCount(20);
          f.pane.refreshData();
          assertEquals(0, f.y(), "empty data must not start following the bottom");
          f.pane.getVerticalScrollBar().setValue(90);
          for (int i = 0; i < 8; i++) {
            model.setRowCount(18 + i % 3);
            f.pane.refreshData();
            f.pane.refreshStyle(25);
            f.budget(174);
            assertEquals(72, f.y());
            f.pane.refreshStyle(30);
            f.budget(180);
            assertEquals(90, f.y());
            f.aligned();
          }
        });
  }
}
