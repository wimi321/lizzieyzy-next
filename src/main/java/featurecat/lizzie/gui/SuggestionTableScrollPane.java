package featurecat.lizzie.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.ScrollPaneLayout;
import javax.swing.SwingUtilities;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

/** Keeps the suggestion table's visible body and resting scroll position on whole rows. */
final class SuggestionTableScrollPane extends JScrollPane {
  private final JTable table;
  private int defaultRowHeight;
  private int safeRowHeight;
  private int headerHeight;
  private boolean measurementsDirty = true;
  private boolean changingGeometry;
  private boolean dragging;
  private boolean snapPending;
  private int topRow;
  private boolean atBottom;
  private int bodyHeight;
  private int dataHeight;
  private int laidOutRowHeight;

  SuggestionTableScrollPane(JTable table) {
    super(table, VERTICAL_SCROLLBAR_ALWAYS, HORIZONTAL_SCROLLBAR_AS_NEEDED);
    this.table = table;
    defaultRowHeight = table.getRowHeight();
    laidOutRowHeight = defaultRowHeight;
    setLayout(new WholeRowLayout());
    // The outer layout owns both the extent and view size. A second viewport layout would
    // restore JTable's preferred size and clamp the retained position during a collapse.
    getViewport().setLayout(null);
    getViewport().addChangeListener(event -> viewportChanged());
    getVerticalScrollBar()
        .addAdjustmentListener(
            event -> {
              if (changingGeometry) return;
              if (event.getValueIsAdjusting()) {
                dragging = true;
              } else if (dragging) {
                dragging = false;
                scheduleSnap();
              }
            });
  }

  void refreshStyle(int defaultRowHeight) {
    this.defaultRowHeight = Math.max(1, defaultRowHeight);
    measurementsDirty = true;
    revalidate();
    repaint();
  }

  /** Called by the existing EDT refresh timer; the live model does not publish events. */
  void refreshData() {
    int height = (int) Math.min(Integer.MAX_VALUE, (long) table.getRowCount() * laidOutRowHeight);
    if (height != dataHeight) {
      changingGeometry = true;
      try {
        dataHeight = height;
        // Data changes clamp the current position, rather than following a growing list.
        int position =
            Math.min(getViewport().getViewPosition().y, Math.max(0, dataHeight - bodyHeight));
        updateRange(getViewport().getViewSize().width, bodyHeight == 0 ? 0 : position);
      } finally {
        changingGeometry = false;
      }
    }
    table.repaint();
  }

  private void measureStyle() {
    Font font = table.getFont();
    safeRowHeight = table.getFontMetrics(font).getHeight() + table.getRowMargin() + 2;
    for (int column = 0; column < table.getColumnCount(); column++) {
      TableColumn tableColumn = table.getColumnModel().getColumn(column);
      TableCellRenderer renderer = tableColumn.getCellRenderer();
      if (renderer == null) renderer = table.getDefaultRenderer(table.getColumnClass(column));
      // Production cells are labels whose geometry does not vary with their value or highlight.
      // Do not invoke them with a fabricated row: they read the live suggestion model.
      if (renderer instanceof Component) {
        Component component = (Component) renderer;
        if (!font.equals(component.getFont())) component.setFont(font);
        Insets insets =
            component instanceof JComponent
                ? ((JComponent) component).getInsets()
                : new Insets(0, 0, 0, 0);
        safeRowHeight =
            Math.max(
                safeRowHeight,
                component.getFontMetrics(component.getFont()).getHeight()
                    + insets.top
                    + insets.bottom
                    + table.getRowMargin()
                    + 2);
      }
    }

    JTableHeader header = table.getTableHeader();
    headerHeight = 0;
    if (header != null) {
      Insets insets = header.getInsets();
      headerHeight =
          header.getFontMetrics(header.getFont()).getHeight() + insets.top + insets.bottom;
      for (int column = 0; column < table.getColumnCount(); column++) {
        TableColumn tableColumn = table.getColumnModel().getColumn(column);
        TableCellRenderer renderer = tableColumn.getHeaderRenderer();
        if (renderer == null) renderer = header.getDefaultRenderer();
        Component component =
            renderer.getTableCellRendererComponent(
                table, tableColumn.getHeaderValue(), false, false, -1, column);
        Insets rendererInsets =
            component instanceof JComponent
                ? ((JComponent) component).getInsets()
                : new Insets(0, 0, 0, 0);
        int contentHeight =
            component.getFontMetrics(component.getFont()).getHeight()
                + rendererInsets.top
                + rendererInsets.bottom;
        headerHeight =
            Math.max(headerHeight, Math.max(contentHeight, component.getPreferredSize().height));
      }
      Dimension preferred = header.getPreferredSize();
      if (preferred.height != headerHeight) {
        header.setPreferredSize(new Dimension(preferred.width, headerHeight));
      }
    }
    measurementsDirty = false;
  }

  private int rowHeightFor(int budget) {
    int baseline = Math.max(defaultRowHeight, safeRowHeight);
    if (budget <= 0 || budget % baseline == 0) return baseline;
    int nextRowCount = budget / baseline + 1;
    int candidate = budget / nextRowCount;
    int reduction = baseline - candidate;
    return candidate >= safeRowHeight && reduction <= Math.min(2, baseline / 10)
        ? candidate
        : baseline;
  }

  private void viewportChanged() {
    if (changingGeometry) return;
    if (bodyHeight == 0) {
      setPosition(0);
    } else if (dragging || getVerticalScrollBar().getValueIsAdjusting()) {
      rememberPosition();
    } else {
      alignPosition();
    }
  }

  private void rememberPosition() {
    int position = getViewport().getViewPosition().y;
    topRow = Math.max(0, position / laidOutRowHeight);
    atBottom = dataHeight > bodyHeight && position == dataHeight - bodyHeight;
  }

  private void scheduleSnap() {
    if (snapPending) return;
    snapPending = true;
    SwingUtilities.invokeLater(
        () -> {
          snapPending = false;
          if (!dragging && !getVerticalScrollBar().getValueIsAdjusting()) alignPosition();
        });
  }

  private void alignPosition() {
    if (changingGeometry) return;
    if (bodyHeight == 0) {
      setPosition(0);
      return;
    }
    int position = getViewport().getViewPosition().y;
    int maximum = Math.max(0, dataHeight - bodyHeight);
    int nearest =
        (int)
            Math.min(
                maximum,
                ((long) Math.max(0, position) + laidOutRowHeight / 2)
                    / laidOutRowHeight
                    * laidOutRowHeight);
    setPosition(nearest);
    rememberPosition();
  }

  private void setPosition(int position) {
    boolean wasChanging = changingGeometry;
    changingGeometry = true;
    try {
      JViewport viewport = getViewport();
      Point current = viewport.getViewPosition();
      if (current.y != position) viewport.setViewPosition(new Point(current.x, position));
      if (getVerticalScrollBar().getValue() != position) {
        getVerticalScrollBar().setValue(position);
      }
    } finally {
      changingGeometry = wasChanging;
    }
  }

  private void updateRange(int viewWidth, int position) {
    int range = bodyHeight == 0 ? 0 : Math.max(bodyHeight, dataHeight);
    Dimension viewSize = getViewport().getViewSize();
    if (viewSize.width != viewWidth || viewSize.height != range) {
      getViewport().setViewSize(new Dimension(viewWidth, range));
    }
    setPosition(position);
    JScrollBar vertical = getVerticalScrollBar();
    if (vertical.getValue() != position
        || vertical.getVisibleAmount() != bodyHeight
        || vertical.getMinimum() != 0
        || vertical.getMaximum() != range) {
      vertical.setValues(position, bodyHeight, 0, range);
    }
    boolean canScroll = bodyHeight > 0 && dataHeight > bodyHeight;
    if (vertical.isEnabled() != canScroll) vertical.setEnabled(canScroll);
    // A zero-height viewport must not replace the user's pre-collapse anchor.
    if (bodyHeight > 0) rememberPosition();
  }

  private final class WholeRowLayout extends ScrollPaneLayout {
    @Override
    public void layoutContainer(Container parent) {
      if (changingGeometry) return;
      int scrollingPosition = viewport.getViewPosition().y;
      boolean continuousScroll =
          dragging || snapPending || getVerticalScrollBar().getValueIsAdjusting();
      changingGeometry = true;
      try {
        if (measurementsDirty) measureStyle();
        // Always obtain a new raw budget; never reuse the already rounded viewport extent.
        super.layoutContainer(parent);
        boolean headerFits =
            colHead == null
                || (colHead.isVisible()
                    ? colHead.getHeight() >= headerHeight
                    : viewport.getHeight() >= headerHeight);
        if (colHead != null && colHead.isVisible() != headerFits) {
          colHead.setVisible(headerFits);
          super.layoutContainer(parent);
        }
        int budget = headerFits ? Math.max(0, viewport.getHeight()) : 0;
        int rowHeight = rowHeightFor(budget);
        if (table.getRowHeight() != rowHeight) {
          table.setRowHeight(rowHeight);
          // Recompute the standard scrollbar allocation once after changing preferred height.
          super.layoutContainer(parent);
          budget = headerFits ? Math.max(0, viewport.getHeight()) : 0;
          rowHeight = rowHeightFor(budget);
          if (table.getRowHeight() != rowHeight) table.setRowHeight(rowHeight);
        }
        laidOutRowHeight = rowHeight;
        bodyHeight = budget / rowHeight * rowHeight;
        dataHeight = (int) Math.min(Integer.MAX_VALUE, (long) table.getRowCount() * rowHeight);
        if (viewport.getHeight() != bodyHeight) {
          viewport.setBounds(viewport.getX(), viewport.getY(), viewport.getWidth(), bodyHeight);
        }
        int viewWidth =
            table.getScrollableTracksViewportWidth()
                ? viewport.getWidth()
                : Math.max(viewport.getWidth(), table.getPreferredSize().width);
        int maximum = Math.max(0, dataHeight - bodyHeight);
        int position =
            bodyHeight == 0
                ? 0
                : (continuousScroll
                    ? Math.min(maximum, scrollingPosition)
                    : (atBottom ? maximum : (int) Math.min(maximum, (long) topRow * rowHeight)));
        updateRange(viewWidth, position);
        JScrollBar vertical = getVerticalScrollBar();
        if (vertical.getUnitIncrement() != rowHeight) vertical.setUnitIncrement(rowHeight);
        if (vertical.getBlockIncrement() != bodyHeight) vertical.setBlockIncrement(bodyHeight);
        if (isWheelScrollingEnabled() != (bodyHeight > 0)) {
          setWheelScrollingEnabled(bodyHeight > 0);
        }
      } finally {
        changingGeometry = false;
      }
    }
  }
}
