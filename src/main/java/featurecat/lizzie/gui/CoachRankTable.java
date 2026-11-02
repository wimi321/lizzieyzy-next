package featurecat.lizzie.gui;

import featurecat.lizzie.training.CoachLevel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * The KataGo rank table of the AI Coach: every {@link CoachLevel} with its Fox / Tygem rank and
 * real-world grade, strongest first, opened from a link beside 对手棋力. A click on a row, or Enter,
 * picks that level as the opponent.
 */
final class CoachRankTable {
  private static final int ROW_HEIGHT = 26;
  private static final int VISIBLE_ROWS = 18;

  private CoachRankTable() {}

  /**
   * Opens the table with {@code selected} picked out; {@code onPick} gets the row the player
   * chooses, and the table closes.
   */
  static void show(
      Component parent,
      BinaryOperator<String> text,
      CoachLevel selected,
      Consumer<CoachLevel> onPick) {
    Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    JDialog dialog = new JDialog(owner);
    dialog.setTitle(text.apply("HumanSlTraining.rankTable.title", "KataGo rank table"));
    dialog.setModal(true);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    CoachSetupWidgets.Palette p = CoachSetupWidgets.Palette.current();

    Sheet sheet =
        new Sheet(
            text,
            selected,
            level -> {
              dialog.dispose();
              onPick.accept(level);
            });
    JScrollPane scroll = new JScrollPane(sheet);
    scroll.setBorder(BorderFactory.createLineBorder(p.border()));
    scroll.getViewport().setBackground(p.sheet());
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);
    scroll.setPreferredSize(
        new Dimension(sheet.getPreferredSize().width + 18, (VISIBLE_ROWS + 1) * ROW_HEIGHT + 2));

    String noteText =
        text.apply(
            "HumanSlTraining.rankTable.note",
            "A community comparison to help you pick an opponent; it is not a rating. Click a row"
                + " to play that level. Real-world grades follow the Chinese amateur system.");
    JTextArea note = new JTextArea(noteText);
    note.setEditable(false);
    note.setFocusable(false);
    note.setLineWrap(true);
    note.setWrapStyleWord(true);
    note.setOpaque(false);
    note.setFont(CoachSetupWidgets.font(noteText, Font.PLAIN, 12.5f));
    note.setForeground(p.muted());
    note.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

    CoachSetupWidgets.SheetButton close =
        new CoachSetupWidgets.SheetButton(
            CoachSetupWidgets.SheetButton.Shape.OUTLINE,
            CoachSetupWidgets.SheetButton.Glyph.NONE,
            text.apply("HumanSlTraining.rankTable.close", "Close"));
    close.addActionListener(event -> dialog.dispose());
    JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    footer.setOpaque(false);
    footer.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
    footer.add(close);

    JPanel root = new JPanel(new BorderLayout());
    root.setBackground(p.sheet());
    root.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
    root.add(note, BorderLayout.NORTH);
    root.add(scroll, BorderLayout.CENTER);
    root.add(footer, BorderLayout.SOUTH);
    root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
    root.getActionMap()
        .put(
            "close",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                dialog.dispose();
              }
            });
    dialog.setContentPane(root);
    // The note wraps to the table's width instead of stretching the dialog.
    note.setSize(scroll.getPreferredSize().width, Short.MAX_VALUE);
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.getRootPane().setDefaultButton(close);
    SwingUtilities.invokeLater(
        () -> {
          sheet.scrollRectToVisible(sheet.highlightBounds());
          sheet.requestFocusInWindow();
        });
    dialog.setVisible(true);
  }

  /** The link beside 对手棋力 that opens the table. */
  static final class Link extends JButton {
    Link(String toolTip) {
      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setOpaque(false);
      setRolloverEnabled(true);
      setMargin(new Insets(0, 0, 0, 0));
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setToolTipText(toolTip);
      CoachSetupWidgets.repaintOnFocus(this);
    }

    private Font labelFont() {
      return CoachSetupWidgets.font(getText(), Font.PLAIN, 13f);
    }

    @Override
    public Dimension getPreferredSize() {
      FontMetrics metrics = getFontMetrics(labelFont());
      return new Dimension(metrics.stringWidth(getText()) + 18, metrics.getHeight() + 4);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = CoachSetupWidgets.smooth(graphics);
      try {
        CoachSetupWidgets.Palette p = CoachSetupWidgets.Palette.current();
        g.setFont(labelFont());
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(getText());
        int x = getWidth() - 14 - textWidth;
        int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        g.setColor(isEnabled() ? p.accent() : p.muted());
        g.drawString(getText(), x, baseline);
        if (isEnabled() && getModel().isRollover()) {
          g.fillRect(x, baseline + 2, textWidth, 1);
        }
        int cx = getWidth() - 6;
        int cy = baseline - metrics.getAscent() / 2 + 1;
        g.drawPolyline(new int[] {cx - 3, cx, cx - 3}, new int[] {cy - 4, cy, cy + 4}, 3);
        if (isFocusOwner()) {
          CoachSetupWidgets.focusRing(g, 0, 0, getWidth(), getHeight(), 8);
        }
      } finally {
        g.dispose();
      }
    }
  }

  /**
   * The table itself: KataGo level, Fox / Tygem, real-world grade, with equal grades merged. The
   * mouse or the arrow keys move the pick; a click or Enter chooses it.
   */
  static final class Sheet extends CoachSetupWidgets.Control {
    private static final int WIDTH = 480;
    private final BinaryOperator<String> text;
    private final List<CoachLevel> rows = strongestFirst();
    private final Consumer<CoachLevel> onPick;
    private int highlight;
    private int hover = -1;

    Sheet(BinaryOperator<String> text, CoachLevel selected, Consumer<CoachLevel> onPick) {
      super(javax.accessibility.AccessibleRole.TABLE);
      this.text = text;
      this.onPick = onPick;
      this.highlight = rows.indexOf(selected);
      setOpaque(true);
      setFocusable(true);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      getAccessibleContext()
          .setAccessibleName(text.apply("HumanSlTraining.rankTable.title", "KataGo rank table"));
      MouseAdapter mouse =
          new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
              setHover(rowAt(e.getY()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
              setHover(-1);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
              int row = rowAt(e.getY());
              if (row >= 0) {
                pick(row);
              }
            }
          };
      addMouseListener(mouse);
      addMouseMotionListener(mouse);
      addKeyListener(
          new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
              int code = e.getKeyCode();
              if (code == KeyEvent.VK_UP) {
                moveHighlight(-1);
              } else if (code == KeyEvent.VK_DOWN) {
                moveHighlight(1);
              } else if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_SPACE) {
                if (highlight >= 0) {
                  pick(highlight);
                }
              } else {
                return;
              }
              e.consume();
            }
          });
      CoachSetupWidgets.repaintOnFocus(this);
    }

    static List<CoachLevel> strongestFirst() {
      List<CoachLevel> levels = new ArrayList<CoachLevel>(List.of(CoachLevel.values()));
      Collections.reverse(levels);
      return levels;
    }

    /** The row of the picked level, counted from the strongest, or -1. */
    int highlightedRow() {
      return highlight;
    }

    /** The table row at {@code y}, or -1 for the header and below the last row. */
    int rowAt(int y) {
      int row = y / ROW_HEIGHT - 1;
      return row >= 0 && row < rows.size() ? row : -1;
    }

    void pick(int row) {
      onPick.accept(rows.get(row));
    }

    private void setHover(int row) {
      if (row != hover) {
        hover = row;
        repaint();
      }
    }

    private void moveHighlight(int step) {
      int next = Math.max(0, Math.min(rows.size() - 1, (highlight < 0 ? 0 : highlight) + step));
      if (next != highlight) {
        highlight = next;
        scrollRectToVisible(new Rectangle(0, (next + 1) * ROW_HEIGHT, 1, ROW_HEIGHT));
        repaint();
      }
    }

    Rectangle highlightBounds() {
      if (highlight < 0) {
        return new Rectangle(0, 0, 1, 1);
      }
      // Leave a few rows above it so the row does not sit at the very edge.
      int top = Math.max(0, (highlight - 3) * ROW_HEIGHT);
      return new Rectangle(0, top, 1, (VISIBLE_ROWS - 1) * ROW_HEIGHT);
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(WIDTH, (rows.size() + 1) * ROW_HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = CoachSetupWidgets.smooth(graphics);
      try {
        CoachSetupWidgets.Palette p = CoachSetupWidgets.Palette.current();
        int width = getWidth();
        int[] columns = {0, width * 3 / 10, width * 6 / 10, width};
        g.setColor(p.sheet());
        g.fillRect(0, 0, width, getHeight());
        g.setColor(p.segment());
        g.fillRect(0, 0, width, ROW_HEIGHT);
        String[] headers = {
          text.apply("HumanSlTraining.rankTable.kataGo", "KataGo rank"),
          text.apply("HumanSlTraining.rankTable.fox", "Fox / Tygem"),
          text.apply("HumanSlTraining.rankTable.real", "Real-world grade")
        };
        for (int column = 0; column < 3; column++) {
          cell(
              g, headers[column], columns[column], columns[column + 1], 0, 1, Font.BOLD, p.muted());
        }
        if (hover >= 0 && hover != highlight) {
          g.setColor(p.segment());
          g.fillRect(0, (hover + 1) * ROW_HEIGHT, columns[2], ROW_HEIGHT);
        }
        if (highlight >= 0) {
          g.setColor(p.selectedFill());
          g.fillRect(0, (highlight + 1) * ROW_HEIGHT, columns[2], ROW_HEIGHT);
          g.setColor(p.accent());
          g.fillRect(0, (highlight + 1) * ROW_HEIGHT, 3, ROW_HEIGHT);
        }
        for (int i = 0; i < rows.size(); i++) {
          CoachLevel row = rows.get(i);
          int top = (i + 1) * ROW_HEIGHT;
          int style = i == highlight ? Font.BOLD : Font.PLAIN;
          cell(
              g, CoachLevelText.kataGo(text, row), columns[0], columns[1], top, 1, style, p.text());
          cell(g, row.fox(), columns[1], columns[2], top, 1, style, p.text());
          g.setColor(p.divider());
          g.fillRect(0, top, columns[2], 1);
          boolean startsRun = i == 0 || !sameGrade(rows.get(i - 1), row);
          if (startsRun) {
            int span = 1;
            while (i + span < rows.size() && sameGrade(rows.get(i + span), row)) span++;
            boolean holdsHighlight = highlight >= i && highlight < i + span;
            if (holdsHighlight) {
              // A merged grade cell is picked out whole, not with a band through its middle.
              g.setColor(p.selectedFill());
              g.fillRect(columns[2], top, width - columns[2], span * ROW_HEIGHT);
            }
            g.setColor(p.divider());
            g.fillRect(columns[2], top, width - columns[2], 1);
            cell(
                g,
                CoachLevelText.grade(text, row),
                columns[2],
                columns[3],
                top,
                span,
                holdsHighlight ? Font.BOLD : Font.PLAIN,
                p.text());
          }
        }
        g.setColor(p.divider());
        g.fillRect(columns[1], 0, 1, getHeight());
        g.fillRect(columns[2], 0, 1, getHeight());
        if (isFocusOwner() && highlight >= 0) {
          CoachSetupWidgets.focusRing(g, 0, (highlight + 1) * ROW_HEIGHT, width, ROW_HEIGHT, 4);
        }
      } finally {
        g.dispose();
      }
    }

    private static boolean sameGrade(CoachLevel a, CoachLevel b) {
      return a.grade() == b.grade() && a.gradeNumber() == b.gradeNumber();
    }

    private static void cell(
        Graphics2D g, String value, int left, int right, int top, int rows, int style, Color ink) {
      g.setFont(CoachSetupWidgets.font(value, style, 13f));
      FontMetrics metrics = g.getFontMetrics();
      g.setColor(ink);
      int height = rows * ROW_HEIGHT;
      g.drawString(
          value,
          left + (right - left - metrics.stringWidth(value)) / 2,
          top + (height - metrics.getHeight()) / 2 + metrics.getAscent());
    }
  }
}
