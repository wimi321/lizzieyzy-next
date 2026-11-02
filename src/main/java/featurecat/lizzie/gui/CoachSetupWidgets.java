package featurecat.lizzie.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.JButton;
import javax.swing.JComponent;

/** The hand-drawn controls of the AI Coach setup sheet, light or dark with the app's theme. */
final class CoachSetupWidgets {
  private CoachSetupWidgets() {}

  /** The sheet's colours: the design's light palette, or a dark one for the dark theme. */
  record Palette(
      Color sheet,
      Color text,
      Color muted,
      Color faint,
      Color border,
      Color divider,
      Color accent,
      Color onAccent,
      Color selectedFill,
      Color tagFill,
      Color tagText,
      Color segment,
      Color segmentOn,
      Color track,
      Color switchOff,
      Color ready) {
    static final Palette LIGHT =
        new Palette(
            Color.WHITE,
            new Color(0x1b, 0x24, 0x21),
            new Color(0x5b, 0x66, 0x61),
            new Color(0x66, 0x70, 0x6b),
            new Color(0xd5, 0xdc, 0xd9),
            new Color(0xe5, 0xea, 0xe8),
            new Color(0x1f, 0x7a, 0x70),
            Color.WHITE,
            new Color(0xf1, 0xf8, 0xf6),
            new Color(0xdc, 0xef, 0xea),
            new Color(0x1f, 0x6f, 0x64),
            new Color(0xee, 0xf1, 0xef),
            Color.WHITE,
            new Color(0xe3, 0xe8, 0xe6),
            new Color(0xc3, 0xcc, 0xc8),
            new Color(0x2e, 0x9e, 0x6e));
    static final Palette DARK =
        new Palette(
            new Color(0x26, 0x29, 0x2f),
            new Color(0xec, 0xef, 0xee),
            new Color(0xb9, 0xc3, 0xbf),
            new Color(0x9a, 0xa4, 0xa0),
            new Color(0x4e, 0x54, 0x58),
            new Color(0x3a, 0x3f, 0x44),
            new Color(0x5b, 0xc7, 0xb0),
            new Color(0x0b, 0x26, 0x22),
            new Color(0x25, 0x4c, 0x45),
            // The 默认 tag sits on a selected card; it needs a fill of its own to show.
            new Color(0x2f, 0x6e, 0x63),
            new Color(0x8e, 0xe6, 0xd6),
            new Color(255, 255, 255, 20),
            new Color(0x3a, 0x40, 0x46),
            new Color(0x3a, 0x40, 0x46),
            new Color(0x55, 0x5c, 0x60),
            new Color(0x7d, 0xd7, 0xad));

    static Palette current() {
      return AppleStyleSupport.isAppleStyleEnabled() ? DARK : LIGHT;
    }
  }

  static Font font(String text, int style, float size) {
    return HumanSlTrainingStyle.fontForText(text, style, Math.round(size));
  }

  static Graphics2D smooth(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    return g;
  }

  static void focusRing(Graphics2D g, int x, int y, int width, int height, int arc) {
    g.setColor(Palette.current().accent());
    g.setStroke(new BasicStroke(2f));
    g.drawRoundRect(x + 1, y + 1, width - 3, height - 3, arc, arc);
    g.setStroke(new BasicStroke(1f));
  }

  /** Repaints on focus changes, so the focus ring follows the keyboard. */
  static void repaintOnFocus(JComponent component) {
    component.addFocusListener(
        new FocusAdapter() {
          @Override
          public void focusGained(FocusEvent e) {
            component.repaint();
          }

          @Override
          public void focusLost(FocusEvent e) {
            component.repaint();
          }
        });
  }

  /** A small coloured dot, such as the one before the model's state. */
  static final class Dot implements javax.swing.Icon {
    private final Color color;

    Dot(Color color) {
      this.color = color;
    }

    Color color() {
      return color;
    }

    @Override
    public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
      Graphics2D g = smooth(graphics);
      try {
        g.setColor(color);
        g.fillOval(x, y + 1, 8, 8);
      } finally {
        g.dispose();
      }
    }

    @Override
    public int getIconWidth() {
      return 8;
    }

    @Override
    public int getIconHeight() {
      return 10;
    }
  }

  /** A hand-drawn control that screen readers can name, with the role it plays. */
  abstract static class Control extends JComponent {
    private final javax.accessibility.AccessibleRole role;

    Control(javax.accessibility.AccessibleRole role) {
      this.role = role;
    }

    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
      if (accessibleContext == null) {
        accessibleContext =
            new AccessibleJComponent() {
              @Override
              public javax.accessibility.AccessibleRole getAccessibleRole() {
                return role;
              }
            };
      }
      return accessibleContext;
    }
  }

  /** A sheet button: the round − and +, a square stepper button, an outline or a filled one. */
  static final class SheetButton extends JButton {
    enum Shape {
      ROUND,
      SQUARE,
      OUTLINE,
      PRIMARY
    }

    /** Drawn instead of text: a minus, or a plus when {@code plus}. */
    enum Glyph {
      NONE,
      MINUS,
      PLUS
    }

    private final Shape shape;
    private final Glyph glyph;

    SheetButton(Shape shape, Glyph glyph, String text) {
      super(text);
      this.shape = shape;
      this.glyph = glyph;
      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setOpaque(false);
      setRolloverEnabled(true);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      repaintOnFocus(this);
      if (glyph != Glyph.NONE) {
        getAccessibleContext().setAccessibleName(text);
        setText("");
        setToolTipText(text);
      }
    }

    @Override
    public Dimension getPreferredSize() {
      switch (shape) {
        case ROUND:
          return new Dimension(36, 36);
        case SQUARE:
          return new Dimension(32, 32);
        default:
          FontMetrics metrics = getFontMetrics(labelFont());
          int padding = shape == Shape.PRIMARY ? 26 : 20;
          return new Dimension(metrics.stringWidth(getText()) + 2 * padding, 44);
      }
    }

    private Font labelFont() {
      return font(getText(), shape == Shape.PRIMARY ? Font.BOLD : Font.PLAIN, 15f);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = smooth(graphics);
      try {
        Palette p = Palette.current();
        int width = getWidth();
        int height = getHeight();
        boolean hover = isEnabled() && getModel().isRollover();
        if (!isEnabled()) {
          g.setComposite(
              java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
        }
        int arc = shape == Shape.ROUND ? height : shape == Shape.SQUARE ? 16 : 20;
        if (shape == Shape.PRIMARY) {
          g.setColor(hover ? p.accent().brighter() : p.accent());
          g.fillRoundRect(0, 0, width, height, arc, arc);
        } else {
          g.setColor(hover ? p.selectedFill() : p.sheet());
          g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc);
          g.setColor(p.border());
          g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc);
        }
        if (isFocusOwner()) {
          focusRing(g, 0, 0, width, height, arc);
        }
        if (glyph != Glyph.NONE) {
          g.setColor(p.text());
          g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          int cx = width / 2;
          int cy = height / 2;
          g.drawLine(cx - 5, cy, cx + 5, cy);
          if (glyph == Glyph.PLUS) {
            g.drawLine(cx, cy - 5, cx, cy + 5);
          }
          return;
        }
        g.setFont(labelFont());
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(shape == Shape.PRIMARY ? p.onAccent() : p.text());
        g.drawString(
            getText(),
            (width - metrics.stringWidth(getText())) / 2,
            (height - metrics.getHeight()) / 2 + metrics.getAscent());
      } finally {
        g.dispose();
      }
    }
  }

  /** The opponent's level on one track: click, drag or the arrow keys. */
  static final class RankSlider extends Control {
    private final int stops;
    private final int[] ticks;
    private final IntConsumer onChange;
    private int value;

    /**
     * @param ticks the stops that get a mark on the track, and a label below it
     */
    RankSlider(int stops, int[] ticks, IntConsumer onChange) {
      super(javax.accessibility.AccessibleRole.SLIDER);
      this.stops = stops;
      this.ticks = ticks.clone();
      this.onChange = onChange;
      setFocusable(true);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      repaintOnFocus(this);
      MouseAdapter mouse =
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              if (isEnabled()) {
                requestFocusInWindow();
                setValue(stopAt(e.getX()), true);
              }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
              if (isEnabled()) {
                setValue(stopAt(e.getX()), true);
              }
            }
          };
      addMouseListener(mouse);
      addMouseMotionListener(mouse);
      addKeyListener(
          new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
              if (!isEnabled()) {
                return;
              }
              int code = e.getKeyCode();
              if (code == KeyEvent.VK_LEFT || code == KeyEvent.VK_DOWN) {
                setValue(value - 1, true);
              } else if (code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_UP) {
                setValue(value + 1, true);
              } else if (code == KeyEvent.VK_HOME) {
                setValue(0, true);
              } else if (code == KeyEvent.VK_END) {
                setValue(stops - 1, true);
              } else {
                return;
              }
              e.consume();
            }
          });
    }

    int value() {
      return value;
    }

    void setValue(int next, boolean notify) {
      int bounded = Math.max(0, Math.min(stops - 1, next));
      if (bounded != value) {
        value = bounded;
        repaint();
        if (notify) {
          onChange.accept(value);
        }
      }
    }

    /** The stop nearest to {@code x}. */
    int stopAt(int x) {
      int left = 11;
      int width = Math.max(1, getWidth() - 22);
      return Math.round((x - left) * (stops - 1) / (float) width);
    }

    int xOf(int stop) {
      return 11 + Math.round((getWidth() - 22) * stop / (float) (stops - 1));
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(400, 44);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = smooth(graphics);
      try {
        Palette p = Palette.current();
        int left = xOf(0);
        int right = xOf(stops - 1);
        int thumb = xOf(value);
        g.setColor(p.track());
        g.fillRoundRect(left, 19, right - left, 6, 6, 6);
        g.setColor(p.accent());
        g.fillRoundRect(left, 19, thumb - left, 6, 6, 6);
        for (int tick : ticks) {
          g.setColor(tick <= value ? new Color(255, 255, 255, 216) : p.border());
          g.fillRoundRect(xOf(tick) - 1, 17, 2, 10, 2, 2);
        }
        g.setColor(new Color(20, 40, 35, 50));
        g.fillOval(thumb - 11, 13, 22, 22);
        g.setColor(p.sheet());
        g.fillOval(thumb - 11, 11, 22, 22);
        g.setColor(p.accent());
        g.setStroke(new BasicStroke(2f));
        g.drawOval(thumb - 10, 12, 20, 20);
        if (isFocusOwner()) {
          g.setColor(
              new Color(p.accent().getRed(), p.accent().getGreen(), p.accent().getBlue(), 70));
          g.setStroke(new BasicStroke(4f));
          g.drawOval(thumb - 14, 8, 28, 28);
        }
      } finally {
        g.dispose();
      }
    }

    int[] ticks() {
      return ticks.clone();
    }
  }

  /** A card that picks one of several options, such as a training mode. */
  static final class ChoiceCard extends Control {
    private final String title;
    private final String description;
    private final String tag;
    private final Runnable onPick;
    private boolean selected;

    ChoiceCard(String title, String description, String tag, Runnable onPick) {
      super(javax.accessibility.AccessibleRole.RADIO_BUTTON);
      this.title = title;
      this.description = description;
      this.tag = tag;
      this.onPick = onPick;
      setFocusable(true);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      getAccessibleContext().setAccessibleName(title);
      getAccessibleContext().setAccessibleDescription(description);
      repaintOnFocus(this);
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              if (isEnabled()) {
                requestFocusInWindow();
                onPick.run();
              }
            }
          });
      addKeyListener(
          new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
              if (isEnabled()
                  && (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER)) {
                onPick.run();
                e.consume();
              }
            }
          });
    }

    boolean isSelected() {
      return selected;
    }

    void setSelected(boolean value) {
      if (selected != value) {
        selected = value;
        repaint();
      }
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(200, Math.max(92, contentHeight(200)));
    }

    private int contentHeight(int width) {
      FontMetrics body = getFontMetrics(font(description, Font.PLAIN, 13f));
      List<String> lines = CoachPaint.wrap(description, body, width - 28);
      return 14 + 22 + 6 + lines.size() * 20 + 16;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = smooth(graphics);
      try {
        Palette p = Palette.current();
        int width = getWidth();
        int height = getHeight();
        if (!isEnabled()) {
          g.setComposite(
              java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.55f));
        }
        g.setColor(selected ? p.selectedFill() : p.sheet());
        g.fillRoundRect(0, 0, width - 1, height - 1, 24, 24);
        g.setColor(selected ? p.accent() : p.border());
        g.setStroke(new BasicStroke(selected ? 1.5f : 1f));
        g.drawRoundRect(1, 1, width - 3, height - 3, 24, 24);
        g.setStroke(new BasicStroke(1f));
        if (isFocusOwner()) {
          focusRing(g, 0, 0, width, height, 24);
        }
        g.setFont(font(title, Font.BOLD, 15f));
        FontMetrics titleMetrics = g.getFontMetrics();
        g.setColor(p.text());
        g.drawString(title, 14, 14 + titleMetrics.getAscent());
        if (tag != null) {
          g.setFont(font(tag, Font.BOLD, 11f));
          FontMetrics tagMetrics = g.getFontMetrics();
          int tagX = 14 + titleMetrics.stringWidth(title) + 8;
          int tagWidth = tagMetrics.stringWidth(tag) + 12;
          int tagY = 14 + (titleMetrics.getHeight() - 18) / 2;
          g.setColor(p.tagFill());
          g.fillRoundRect(tagX, tagY, tagWidth, 18, 12, 12);
          g.setColor(p.tagText());
          g.drawString(
              tag, tagX + 6, tagY + (18 - tagMetrics.getHeight()) / 2 + tagMetrics.getAscent());
        }
        g.setFont(font(description, Font.PLAIN, 13f));
        FontMetrics body = g.getFontMetrics();
        g.setColor(p.muted());
        List<String> lines = CoachPaint.wrap(description, body, width - 28);
        int y = 14 + titleMetrics.getHeight() + 6;
        for (String line : lines) {
          g.drawString(line, 14, y + (20 - body.getHeight()) / 2 + body.getAscent());
          y += 20;
        }
      } finally {
        g.dispose();
      }
    }
  }

  /** A row of segments, one selected: 执黑 / 执白 / 随机, or 自然 / 快 / 慢. */
  static final class Segmented extends Control {
    /** What a segment shows before its label: nothing, or a black, white or half stone. */
    enum Dot {
      NONE,
      BLACK,
      WHITE,
      HALF
    }

    private final List<String> labels;
    private final List<Dot> dots;
    private final IntConsumer onChange;
    private int selected;

    Segmented(String name, List<String> labels, List<Dot> dots, IntConsumer onChange) {
      super(javax.accessibility.AccessibleRole.PAGE_TAB_LIST);
      this.labels = labels;
      this.dots = dots;
      this.onChange = onChange;
      setFocusable(true);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      getAccessibleContext().setAccessibleName(name);
      repaintOnFocus(this);
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              if (isEnabled()) {
                requestFocusInWindow();
                int segment = e.getX() * labels.size() / Math.max(1, getWidth());
                select(segment, true);
              }
            }
          });
      addKeyListener(
          new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
              if (!isEnabled()) {
                return;
              }
              if (e.getKeyCode() == KeyEvent.VK_LEFT) {
                select(selected - 1, true);
                e.consume();
              } else if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
                select(selected + 1, true);
                e.consume();
              }
            }
          });
    }

    int selected() {
      return selected;
    }

    void select(int index, boolean notify) {
      int bounded = Math.max(0, Math.min(labels.size() - 1, index));
      if (bounded != selected) {
        selected = bounded;
        repaint();
        if (notify) {
          onChange.accept(selected);
        }
      }
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(300, 42);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = smooth(graphics);
      try {
        Palette p = Palette.current();
        int width = getWidth();
        int height = getHeight();
        if (!isEnabled()) {
          g.setComposite(
              java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f));
        }
        g.setColor(p.segment());
        g.fillRoundRect(0, 0, width, height, 20, 20);
        int inner = width - 6;
        for (int i = 0; i < labels.size(); i++) {
          int x = 3 + inner * i / labels.size();
          int segmentWidth =
              3 + inner * (i + 1) / labels.size() - x - (i < labels.size() - 1 ? 2 : 0);
          boolean on = i == selected;
          if (on) {
            g.setColor(new Color(20, 30, 25, 30));
            g.fillRoundRect(x, 4, segmentWidth, height - 6, 16, 16);
            g.setColor(p.segmentOn());
            g.fillRoundRect(x, 3, segmentWidth, height - 6, 16, 16);
          }
          String label = labels.get(i);
          g.setFont(font(label, on ? Font.BOLD : Font.PLAIN, 14f));
          FontMetrics metrics = g.getFontMetrics();
          Dot dot = dots.get(i);
          int dotWidth = dot == Dot.NONE ? 0 : 20;
          int textX = x + (segmentWidth - metrics.stringWidth(label) - dotWidth) / 2 + dotWidth;
          int baseline = (height - metrics.getHeight()) / 2 + metrics.getAscent();
          if (dot != Dot.NONE) {
            paintDot(g, textX - 20, height / 2 - 6, dot, p);
          }
          g.setColor(on ? p.text() : p.muted());
          g.drawString(label, textX, baseline);
        }
        if (isFocusOwner()) {
          focusRing(g, 0, 0, width, height, 20);
        }
      } finally {
        g.dispose();
      }
    }

    private static void paintDot(Graphics2D g, int x, int y, Dot dot, Palette p) {
      Color black = new Color(0x1b, 0x1b, 0x1b);
      if (dot == Dot.HALF) {
        g.setColor(Color.WHITE);
        g.fillOval(x, y, 12, 12);
        g.setColor(black);
        g.fillArc(x, y, 12, 12, 90, 180);
      } else {
        g.setColor(dot == Dot.BLACK ? black : Color.WHITE);
        g.fillOval(x, y, 12, 12);
      }
      g.setColor(new Color(0x7d, 0x87, 0x83));
      g.drawOval(x, y, 12, 12);
    }
  }

  /** An on/off switch. */
  static final class Switch extends Control {
    private final Runnable onChange;
    private boolean on;

    Switch(String name, Runnable onChange) {
      super(javax.accessibility.AccessibleRole.TOGGLE_BUTTON);
      this.onChange = onChange;
      setFocusable(true);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      getAccessibleContext().setAccessibleName(name);
      repaintOnFocus(this);
      addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              if (isEnabled()) {
                requestFocusInWindow();
                setOn(!on, true);
              }
            }
          });
      addKeyListener(
          new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
              if (isEnabled() && e.getKeyCode() == KeyEvent.VK_SPACE) {
                setOn(!on, true);
                e.consume();
              }
            }
          });
    }

    boolean isOn() {
      return on;
    }

    void setOn(boolean value, boolean notify) {
      if (on != value) {
        on = value;
        repaint();
        if (notify) {
          onChange.run();
        }
      }
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(44, 28);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = smooth(graphics);
      try {
        Palette p = Palette.current();
        if (!isEnabled()) {
          g.setComposite(
              java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
        }
        int x = 2;
        int y = (getHeight() - 24) / 2;
        g.setColor(on ? p.accent() : p.switchOff());
        g.fillRoundRect(x, y, 40, 24, 24, 24);
        g.setColor(new Color(0, 0, 0, 50));
        g.fillOval(x + (on ? 18 : 2), y + 3, 20, 20);
        g.setColor(Color.WHITE);
        g.fillOval(x + (on ? 18 : 2), y + 2, 20, 20);
        if (isFocusOwner()) {
          focusRing(g, 0, y - 2, 44, 28, 28);
        }
      } finally {
        g.dispose();
      }
    }
  }
}
