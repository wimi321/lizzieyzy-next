package featurecat.lizzie.gui;

import featurecat.lizzie.AppLocale;
import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.*;
import java.awt.event.*;
import java.text.MessageFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;

/** A single sorted view is shared by painting, navigation and accessibility. */
public class BlunderListPanel extends JPanel {
  private final EntryModel model = new EntryModel();
  private final ReviewList list = new ReviewList();
  private final JComboBox<ProblemListSort> sortBox = new SortControl();
  private final JLabel count = new JLabel();
  private final JPanel toolbar = new JPanel(null);
  private final JScrollPane scrollPane = new JScrollPane(list);
  private final JTextArea emptyText = wrappingText();
  private final JScrollPane emptyScroll = new JScrollPane(emptyText);
  private final JPanel body = new JPanel(new CardLayout());
  private final Consumer<ProblemMoveEntry> navigate;
  private final Supplier<ProblemListSideFilter> sideFilter;
  private ProblemListSnapshot snapshot;
  private Object gameIdentity;
  private EntryKey pressedEntry;
  private Object pressedGame;
  private int hoveredIndex = -1;
  private boolean styled;
  private boolean appleStyle;

  public BlunderListPanel() {
    this(
        entry -> {
          if (Lizzie.frame != null) Lizzie.frame.jumpToProblemMove(entry);
        },
        () ->
            Lizzie.frame == null
                ? ProblemListSideFilter.BLACK
                : Lizzie.frame.getProblemListSideFilter());
  }

  BlunderListPanel(
      Consumer<ProblemMoveEntry> navigate, Supplier<ProblemListSideFilter> sideFilter) {
    this.navigate = navigate;
    this.sideFilter = sideFilter;
    setOpaque(false);
    setLayout(new BorderLayout(0, 4));
    toolbar.setOpaque(false);
    toolbar.add(count);
    toolbar.add(sortBox);
    add(toolbar, BorderLayout.NORTH);
    String saved =
        Lizzie.config == null
            ? null
            : Lizzie.config.uiConfig.optString("problem-list-sort", "loss-desc");
    sortBox.setSelectedItem(ProblemListSort.fromConfigValue(saved));
    sortBox.setToolTipText(text("BlunderListPanel.sort"));
    sortBox.getAccessibleContext().setAccessibleName(text("BlunderListPanel.sort"));
    sortBox.addActionListener(
        event -> {
          if (Lizzie.config != null) {
            Lizzie.config.uiConfig.put("problem-list-sort", sortOrder().configValue);
          }
          rebuildRows(false);
          if (list.getSelectedIndex() < 0) {
            scrollPane.getViewport().setViewPosition(new Point());
          } else {
            list.ensureIndexIsVisible(list.getSelectedIndex());
          }
        });
    list.setModel(model);
    list.setCellRenderer(new ProblemRow());
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setOpaque(false);
    list.setVisibleRowCount(-1);
    list.setToolTipText("");
    list.getAccessibleContext().setAccessibleName(text("SidebarHeader.problems"));
    for (JScrollPane pane : new JScrollPane[] {scrollPane, emptyScroll}) {
      pane.setBorder(BorderFactory.createEmptyBorder());
      pane.setOpaque(false);
      pane.getViewport().setOpaque(false);
      pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
      pane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
      pane.getVerticalScrollBar().setUI(new DemoScrollBarUI());
    }
    emptyText.setBorder(BorderFactory.createEmptyBorder(16, 8, 16, 8));
    body.setOpaque(false);
    body.add(scrollPane, "rows");
    body.add(emptyScroll, "empty");
    add(body, BorderLayout.CENTER);
    installNavigation();
    scrollPane
        .getViewport()
        .addComponentListener(
            new ComponentAdapter() {
              @Override
              public void componentResized(ComponentEvent event) {
                list.setFixedCellWidth(Math.max(1, scrollPane.getViewport().getWidth()));
              }
            });
    refreshStyle();
    rebuildRows(false);
  }

  private void installNavigation() {
    list.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent event) {
            if (list.getSelectedIndex() < 0 && model.getSize() > 0) {
              list.setSelectedIndex(Math.max(0, list.getFirstVisibleIndex()));
            }
          }
        });
    list.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent event) {
            pressedEntry = key(entryAt(event.getPoint()));
            pressedGame = gameIdentity;
          }

          @Override
          public void mouseReleased(MouseEvent event) {
            ProblemMoveEntry entry = entryAt(event.getPoint());
            if (SwingUtilities.isLeftMouseButton(event)
                && event.getClickCount() == 1
                && pressedEntry != null
                && pressedGame == gameIdentity
                && pressedEntry.equals(key(entry))) {
              navigate.accept(entry);
            }
            pressedEntry = null;
          }

          @Override
          public void mouseExited(MouseEvent event) {
            hoveredIndex = -1;
            list.repaint();
          }
        });
    list.addMouseMotionListener(
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent event) {
            int next = indexAt(event.getPoint());
            if (hoveredIndex != next) {
              hoveredIndex = next;
              list.repaint();
            }
          }
        });
    list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-problem");
    list.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "open-problem");
    list.getActionMap()
        .put(
            "open-problem",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                ProblemMoveEntry selected = list.getSelectedValue();
                if (selected != null) navigate.accept(selected);
              }
            });
  }

  public void updateSnapshot(ProblemListSnapshot snapshot) {
    updateSnapshot(snapshot, Lizzie.board == null ? null : Lizzie.board.getHistory().getStart());
  }

  void updateSnapshot(ProblemListSnapshot snapshot, Object currentGame) {
    boolean changedGame = gameIdentity != currentGame;
    gameIdentity = currentGame;
    this.snapshot = snapshot;
    refreshStyle();
    rebuildRows(changedGame);
  }

  private void rebuildRows(boolean changedGame) {
    EntryKey selected = changedGame ? null : key(list.getSelectedValue());
    int first = list.getFirstVisibleIndex();
    EntryKey anchor = changedGame || first < 0 ? null : key(model.getElementAt(first));
    Rectangle bounds = first < 0 ? null : list.getCellBounds(first, first);
    int offset = bounds == null ? 0 : scrollPane.getViewport().getViewPosition().y - bounds.y;
    List<ProblemMoveEntry> entries = new ArrayList<>();
    if (snapshot != null) {
      ProblemListSideFilter side = sideFilter.get();
      if (side == null || side == ProblemListSideFilter.ALL) side = ProblemListSideFilter.BLACK;
      entries.addAll(
          side == ProblemListSideFilter.BLACK ? snapshot.blackEntries : snapshot.whiteEntries);
    }
    entries.sort(sortOrder().comparator());
    if (!model.sameEntries(entries)) {
      hoveredIndex = -1;
      model.replace(entries);
      list.setSelectedIndex(indexOf(selected));
      if (changedGame) {
        pressedEntry = null;
        scrollPane.getViewport().setViewPosition(new Point());
      } else {
        int anchorIndex = indexOf(anchor);
        Rectangle next = anchorIndex < 0 ? null : list.getCellBounds(anchorIndex, anchorIndex);
        if (next != null) {
          int maxY =
              Math.max(0, list.getPreferredSize().height - scrollPane.getViewport().getHeight());
          scrollPane
              .getViewport()
              .setViewPosition(new Point(0, Math.min(maxY, Math.max(0, next.y + offset))));
        }
      }
    } else if (changedGame) {
      list.clearSelection();
      pressedEntry = null;
      scrollPane.getViewport().setViewPosition(new Point());
    }
    count.setText(format("BlunderListPanel.count", entries.size()));
    count.getAccessibleContext().setAccessibleName(count.getText());
    String empty = emptyStateText(snapshot, bundle());
    if (!empty.equals(emptyText.getText())) {
      emptyText.setText(empty);
      emptyText.setCaretPosition(0);
      emptyText.getAccessibleContext().setAccessibleName(empty);
    }
    ((CardLayout) body.getLayout()).show(body, entries.isEmpty() ? "empty" : "rows");
    revalidate();
    repaint();
  }

  private int indexOf(EntryKey target) {
    if (target == null) return -1;
    for (int i = 0; i < model.getSize(); i++) {
      if (target.equals(key(model.getElementAt(i)))) return i;
    }
    return -1;
  }

  int indexAt(Point point) {
    int index = list.locationToIndex(point);
    Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
    return bounds != null && bounds.contains(point) && point.y < bounds.y + bounds.height - 1
        ? index
        : -1;
  }

  private ProblemMoveEntry entryAt(Point point) {
    int index = indexAt(point);
    return index < 0 ? null : model.getElementAt(index);
  }

  JList<ProblemMoveEntry> reviewList() {
    return list;
  }

  JComboBox<ProblemListSort> sortControl() {
    return sortBox;
  }

  ProblemListSort sortOrder() {
    return (ProblemListSort) sortBox.getSelectedItem();
  }

  private void refreshStyle() {
    Font font = AppleStyleSupport.workspaceFont(Font.PLAIN, Config.frameFontSize);
    boolean apple = Lizzie.config != null && Lizzie.config.isAppleStyle;
    if (styled
        && appleStyle == apple
        && font.equals(list.getFont())
        && SidebarPanel.secondaryTextColor().equals(count.getForeground())) return;
    styled = true;
    appleStyle = apple;
    setFont(font);
    list.setFont(font);
    count.setFont(font);
    count.setForeground(SidebarPanel.secondaryTextColor());
    AppleStyleSupport.installComboBoxStyle(sortBox);
    sortBox.setRenderer(new SortRenderer());
    emptyText.setFont(font);
    emptyText.setForeground(SidebarPanel.secondaryTextColor());
  }

  @Override
  public void doLayout() {
    int width = Math.max(1, getWidth());
    FontMetrics metrics = sortBox.getFontMetrics(sortBox.getFont());
    int naturalWidth = 0;
    for (ProblemListSort order : ProblemListSort.values()) {
      naturalWidth = Math.max(naturalWidth, metrics.stringWidth(order.toString()) + 72);
    }
    int sortWidth = Math.min(width, naturalWidth);
    JTextArea measure = wrappingText();
    measure.setFont(sortBox.getFont());
    int sortHeight = 0;
    for (ProblemListSort order : ProblemListSort.values()) {
      measure.setText(order.toString());
      measure.setSize(Math.max(1, sortWidth - 64), Short.MAX_VALUE);
      sortHeight = Math.max(sortHeight, measure.getPreferredSize().height + 12);
    }
    Dimension number = count.getPreferredSize();
    int height = Math.max(sortHeight, number.height);
    boolean wrap = number.width + sortWidth + 16 > width;
    toolbar.setPreferredSize(new Dimension(1, wrap ? number.height + height + 4 : height));
    super.doLayout();
    count.setBounds(0, 0, Math.min(width, number.width), wrap ? number.height : height);
    sortBox.setBounds(
        wrap ? 0 : width - sortWidth, wrap ? number.height + 4 : 0, sortWidth, height);
    body.doLayout();
  }

  private static final class SortControl extends JComboBox<ProblemListSort> {
    SortControl() {
      super(ProblemListSort.values());
    }

    @Override
    public void doLayout() {
      super.doLayout();
      // BasicComboBoxUI otherwise makes the arrow as wide as a multi-line field is tall.
      for (Component child : getComponents()) {
        if (child instanceof JButton) {
          Insets insets = getInsets();
          int width = Math.min(24, Math.max(1, getWidth() - insets.left - insets.right));
          child.setBounds(
              getWidth() - insets.right - width,
              insets.top,
              width,
              Math.max(1, getHeight() - insets.top - insets.bottom));
        }
      }
    }
  }

  private final class SortRenderer extends JTextArea implements ListCellRenderer<ProblemListSort> {
    SortRenderer() {
      setEditable(false);
      setFocusable(false);
      setLineWrap(true);
      setWrapStyleWord(true);
      setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends ProblemListSort> owner,
        ProblemListSort order,
        int index,
        boolean selected,
        boolean focused) {
      setFont(sortBox.getFont());
      setText(order == null ? "" : order.toString());
      setForeground(sortBox.getForeground());
      setBackground(selected ? AppleStyleSupport.workspaceSelection() : sortBox.getBackground());
      int width = Math.max(1, sortBox.getWidth() - 52);
      setSize(width, Short.MAX_VALUE);
      return this;
    }
  }

  static ResourceBundle bundle() {
    return Lizzie.resourceBundle != null ? Lizzie.resourceBundle : AppLocale.ENGLISH.loadBundle();
  }

  static String text(String key) {
    return bundle().getString(key);
  }

  private static String format(String key, Object... args) {
    return new MessageFormat(text(key), bundle().getLocale()).format(args);
  }

  static String emptyStatePrimary(boolean analysisRunning, ResourceBundle bundle) {
    return bundle.getString(
        analysisRunning ? "BlunderListPanel.organizing" : "BlunderListPanel.empty");
  }

  static String emptyStateHint(ResourceBundle bundle) {
    return bundle.getString("BlunderListPanel.emptyHint");
  }

  static String emptyStateText(ProblemListSnapshot snapshot, ResourceBundle bundle) {
    if (snapshot != null && snapshot.analysisRunning) return emptyStatePrimary(true, bundle);
    if (snapshot == null || snapshot.analyzedMoves == 0)
      return bundle.getString("BlunderListPanel.unanalyzed");
    return bundle.getString("BlunderListPanel.filteredEmpty");
  }

  private static String decimal(double value) {
    NumberFormat format = NumberFormat.getNumberInstance(bundle().getLocale());
    format.setMinimumFractionDigits(1);
    format.setMaximumFractionDigits(1);
    format.setGroupingUsed(false);
    return format.format(value);
  }

  static String moveLabel(ProblemMoveEntry entry) {
    return format(
        "BlunderListPanel.move",
        entry.moveNumber,
        text(entry.isBlack ? "SidebarHeader.black" : "SidebarHeader.white"),
        entry.coords);
  }

  static String detailLabel(ProblemMoveEntry entry) {
    String searches = format("BlunderListPanel.searches", entry.playouts);
    return entry.hasScoreLoss
        ? format("BlunderListPanel.score", decimal(entry.scoreLossAbs)) + "  \u00b7  " + searches
        : searches;
  }

  static String accessibleRow(ProblemMoveEntry entry, int index) {
    return format(
        "BlunderListPanel.row",
        index + 1,
        moveLabel(entry),
        decimal(entry.winrateLossAbs),
        detailLabel(entry));
  }

  private static JTextArea wrappingText() {
    JTextArea text = new JTextArea();
    text.setEditable(false);
    text.setFocusable(false);
    text.setOpaque(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setBorder(null);
    text.setMargin(new Insets(0, 0, 0, 0));
    return text;
  }

  private static EntryKey key(ProblemMoveEntry entry) {
    return entry == null ? null : new EntryKey(entry);
  }

  private static final class EntryKey {
    private final int move;
    private final boolean black;
    private final String coords;

    EntryKey(ProblemMoveEntry entry) {
      move = entry.moveNumber;
      black = entry.isBlack;
      coords = entry.coords;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof EntryKey)) return false;
      EntryKey key = (EntryKey) other;
      return move == key.move && black == key.black && Objects.equals(coords, key.coords);
    }

    @Override
    public int hashCode() {
      return Objects.hash(move, black, coords);
    }
  }

  private static final class EntryModel extends AbstractListModel<ProblemMoveEntry> {
    private List<ProblemMoveEntry> entries = new ArrayList<>();

    @Override
    public int getSize() {
      return entries.size();
    }

    @Override
    public ProblemMoveEntry getElementAt(int index) {
      return entries.get(index);
    }

    boolean sameEntries(List<ProblemMoveEntry> next) {
      if (entries.size() != next.size()) return false;
      for (int i = 0; i < next.size(); i++) {
        ProblemMoveEntry a = entries.get(i);
        ProblemMoveEntry b = next.get(i);
        if (!key(a).equals(key(b))
            || a.winrateLossAbs != b.winrateLossAbs
            || a.scoreLossAbs != b.scoreLossAbs
            || a.hasScoreLoss != b.hasScoreLoss
            || a.playouts != b.playouts
            || a.isCurrent != b.isCurrent
            || a.severityTier != b.severityTier) return false;
      }
      return true;
    }

    void replace(List<ProblemMoveEntry> next) {
      int old = entries.size();
      entries = next;
      int common = Math.min(old, next.size());
      if (common > 0) fireContentsChanged(this, 0, common - 1);
      if (next.size() > old) fireIntervalAdded(this, old, next.size() - 1);
      if (old > next.size()) fireIntervalRemoved(this, next.size(), old - 1);
    }
  }

  private final class ReviewList extends JList<ProblemMoveEntry> {
    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
      int index = indexAt(event.getPoint());
      return index < 0 ? null : accessibleRow(model.getElementAt(index), index);
    }

    @Override
    protected void processMouseEvent(MouseEvent event) {
      // BasicListUI otherwise selects the nearest row when the empty tail is clicked.
      if (event.getID() == MouseEvent.MOUSE_PRESSED && indexAt(event.getPoint()) < 0) {
        pressedEntry = null;
        return;
      }
      super.processMouseEvent(event);
    }
  }

  private final class ProblemRow extends JPanel implements ListCellRenderer<ProblemMoveEntry> {
    private final JLabel ordinal = new JLabel("", SwingConstants.RIGHT);
    private final JTextArea title = wrappingText();
    private final JTextArea detail = wrappingText();
    private final JLabel loss = new JLabel("", SwingConstants.RIGHT);
    private final JTextArea lossCaption = wrappingText();
    private boolean selected;
    private boolean current;
    private boolean focused;
    private boolean hovered;
    private int rowWidth;

    ProblemRow() {
      super(null);
      setOpaque(false);
      add(ordinal);
      add(title);
      add(detail);
      add(loss);
      add(lossCaption);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends ProblemMoveEntry> owner,
        ProblemMoveEntry entry,
        int index,
        boolean selected,
        boolean focused) {
      this.selected = selected;
      this.current = entry.isCurrent;
      this.focused = focused;
      this.hovered = index == hoveredIndex;
      rowWidth =
          Math.max(1, owner.getFixedCellWidth() > 0 ? owner.getFixedCellWidth() : owner.getWidth());
      Font primary = owner.getFont();
      Font secondary = primary.deriveFont(Math.max(12f, primary.getSize2D() - 1f));
      ordinal.setFont(secondary);
      title.setFont(primary.deriveFont(Font.BOLD));
      detail.setFont(secondary);
      loss.setFont(primary.deriveFont(Font.BOLD));
      lossCaption.setFont(secondary);
      Color text = SidebarPanel.primaryTextColor();
      Color muted = SidebarPanel.secondaryTextColor();
      ordinal.setForeground(muted);
      title.setForeground(text);
      detail.setForeground(muted);
      lossCaption.setForeground(muted);
      loss.setForeground(entry.severityTier >= 5 ? SidebarPanel.lossTextColor() : text);
      ordinal.setText(Integer.toString(index + 1));
      title.setText(moveLabel(entry));
      detail.setText(detailLabel(entry));
      loss.setText(decimal(entry.winrateLossAbs) + "%");
      String accessible = accessibleRow(entry, index);
      getAccessibleContext().setAccessibleName(accessible);
      getAccessibleContext().setAccessibleDescription(accessible);
      // CellRendererPane can reuse equal-sized cells without calling doLayout again.
      arrange();
      return this;
    }

    private int wrappedHeight(JTextArea text, int width) {
      text.setSize(Math.max(1, width), Short.MAX_VALUE);
      return text.getPreferredSize().height;
    }

    private int arrange() {
      lossCaption.setText(text("BlunderListPanel.loss"));
      int width = Math.max(1, rowWidth);
      int numberWidth =
          Math.max(
              18,
              ordinal
                  .getFontMetrics(ordinal.getFont())
                  .stringWidth(Integer.toString(Math.max(1, model.getSize()))));
      int start = numberWidth + 16;
      int content = Math.max(1, width - start - 8);
      int rightWidth =
          Math.max(
                  loss.getPreferredSize().width,
                  lossCaption
                      .getFontMetrics(lossCaption.getFont())
                      .stringWidth(lossCaption.getText()))
              + 8;
      boolean stacked = content < rightWidth + getFontMetrics(title.getFont()).charWidth('M') * 12;
      int mainWidth = stacked ? content : Math.max(1, content - rightWidth - 12);
      int titleHeight = wrappedHeight(title, mainWidth);
      int detailHeight = wrappedHeight(detail, mainWidth);
      ordinal.setBounds(0, 8, numberWidth, ordinal.getPreferredSize().height);
      title.setBounds(start, 8, mainWidth, titleHeight);
      detail.setBounds(start, 8 + titleHeight + 4, mainWidth, detailHeight);
      if (stacked) {
        // Use a wrapping, fully labelled loss on narrow sidebars; never shrink the font.
        lossCaption.setText(format("BlunderListPanel.lossValue", loss.getText()));
        int height = wrappedHeight(lossCaption, content);
        lossCaption.setBounds(start, 8 + titleHeight + detailHeight + 8, content, height);
        loss.setBounds(0, 0, 0, 0);
        return 8 + titleHeight + detailHeight + 8 + height + 9;
      }
      int captionHeight = wrappedHeight(lossCaption, rightWidth);
      loss.setBounds(width - rightWidth - 8, 8, rightWidth, titleHeight);
      lossCaption.setBounds(width - rightWidth - 8, 8 + titleHeight + 4, rightWidth, captionHeight);
      return 8 + titleHeight + 4 + Math.max(detailHeight, captionHeight) + 9;
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(rowWidth, arrange());
    }

    @Override
    public void doLayout() {
      arrange();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      if (selected || current || hovered) {
        graphics.setColor(SidebarPanel.rowHighlightColor(selected || current));
        graphics.fillRect(0, 0, getWidth(), getHeight() - 1);
      }
      if (current || selected) {
        graphics.setColor(SidebarPanel.accentTextColor());
        graphics.fillRect(0, 5, 2, Math.max(0, getHeight() - 11));
      }
      graphics.setColor(SidebarPanel.rowSeparatorColor());
      graphics.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
      if (focused) {
        graphics.setColor(SidebarPanel.accentTextColor());
        graphics.drawRect(1, 1, Math.max(0, getWidth() - 3), Math.max(0, getHeight() - 4));
      }
    }
  }
}
