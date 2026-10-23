package featurecat.lizzie.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JPanel;

/**
 * The card under the win chance. In order: a question for the player (what to do after a 恶手, or a
 * hint, with its buttons), the result once the game is over, or the player's last move with its
 * rating in 边下边提醒.
 */
final class CoachMoveCard extends JPanel {
  private static final int PADDING = 14;
  private static final int PROMPT_PADDING = 16;
  private static final int LINE = 19;
  private static final int PROMPT_LINE = 21;
  private static final int GAP = 12;
  private static final int BUTTON = 44;
  private static final int QUIET_BUTTON = 36;
  private static final Color WARM_FILL = new Color(0x2a, 0x1d, 0x1b);
  private static final Color WARM_EDGE = new Color(255, 122, 110, 107);
  private static final Color WARM_TITLE = new Color(0xf1, 0xd7, 0xd3);
  private static final Color WARM_DETAIL = new Color(0xd9, 0xbd, 0xb8);
  private static final Color WARM_BODY = new Color(0xe5, 0xd3, 0xd0);
  private static final Color HINT_EDGE = new Color(61, 190, 170, 90);

  private final Supplier<CoachPanelState> state;
  private final List<CoachButton> buttons =
      List.of(CoachButton.primary(), CoachButton.secondary(), CoachButton.quiet());
  private final CoachPanelState.Action[] actions = new CoachPanelState.Action[3];

  CoachMoveCard(Supplier<CoachPanelState> state, Consumer<CoachPanelState.Action> onAction) {
    super(null);
    this.state = state;
    setOpaque(false);
    for (int i = 0; i < buttons.size(); i++) {
      int index = i;
      CoachButton button = buttons.get(i);
      button.setVisible(false);
      button.addActionListener(
          event -> {
            if (actions[index] != null) {
              onAction.accept(actions[index]);
            }
          });
      add(button);
    }
  }

  List<CoachButton> buttons() {
    return buttons;
  }

  /** 0 when there is nothing to show. Also places the prompt's buttons. */
  int heightFor() {
    return heightFor(getWidth());
  }

  int heightFor(int width) {
    CoachPanelState s = state.get();
    syncButtons(
        s.prompt() != null
            ? s.prompt().choices()
            : s.summary() != null ? s.summary().choices() : List.of());
    return layout(null, width);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      CoachPaint.smooth(g);
      layout(g, getWidth());
    } finally {
      g.dispose();
    }
  }

  private void syncButtons(List<CoachPanelState.Choice> choices) {
    for (int i = 0; i < buttons.size(); i++) {
      CoachButton button = buttons.get(i);
      if (i < choices.size()) {
        CoachPanelState.Choice choice = choices.get(i);
        actions[i] = choice.action();
        button.setText(choice.label());
        button.setKind(choice.kind());
        button.setVisible(true);
      } else {
        actions[i] = null;
        button.setVisible(false);
      }
    }
  }

  private int layout(Graphics2D g, int width) {
    CoachPanelState s = state.get();
    if (s.prompt() != null) {
      return prompt(g, s.prompt(), width);
    }
    if (s.summary() != null) {
      return summary(g, s.summary(), width);
    }
    CoachPanelState.MoveCard card = s.moveCard();
    if (card == null) {
      return 0;
    }
    return card.verdict() == null && card.change() == null && card.explanation() == null
        ? plainMove(g, card, width)
        : ratedMove(g, card, width);
  }

  /** The 恶手 decision or a hint: tag and title, the headline, why, then the buttons. */
  private int prompt(Graphics2D g, CoachPanelState.Prompt prompt, int width) {
    int inner = width - 2 * PROMPT_PADDING;
    Font bodyFont = CoachPaint.font(prompt.body(), Font.PLAIN, 13.5f);
    List<String> body = CoachPaint.wrap(prompt.body(), getFontMetrics(bodyFont), inner);
    int y = PROMPT_PADDING;
    if (g != null) {
      g.setColor(prompt.warm() ? WARM_FILL : CoachPanel.SURFACE);
      g.fillRoundRect(0, 0, width, getHeight(), 28, 28);
      g.setColor(prompt.warm() ? WARM_EDGE : HINT_EDGE);
      g.drawRoundRect(0, 0, width - 1, getHeight() - 1, 28, 28);
      g.setFont(CoachPaint.font(prompt.tag(), Font.BOLD, 13f));
      int tagWidth =
          CoachPaint.tag(
              g,
              PROMPT_PADDING,
              y,
              24,
              prompt.tag(),
              prompt.verdict() == null
                  ? CoachPanel.ACCENT
                  : CoachPaint.verdictSolid(prompt.verdict()),
              prompt.verdict() == null
                  ? new Color(0x0b, 0x26, 0x22)
                  : CoachPaint.verdictSolidText(prompt.verdict()));
      g.setFont(CoachPaint.font(prompt.title(), prompt.warm() ? Font.PLAIN : Font.BOLD, 15f));
      FontMetrics title = g.getFontMetrics();
      g.setColor(prompt.warm() ? WARM_TITLE : CoachPanel.TEXT);
      g.drawString(
          CoachPaint.fit(title, prompt.title(), inner - tagWidth - 10),
          PROMPT_PADDING + tagWidth + 10,
          y + (24 - title.getHeight()) / 2 + title.getAscent());
    }
    y += 24;
    if (prompt.headline() != null) {
      y += GAP;
      if (g != null) {
        g.setFont(CoachPaint.font(prompt.headline(), Font.BOLD, 28f));
        FontMetrics headline = g.getFontMetrics();
        g.setColor(CoachPaint.verdictColor(prompt.verdict()));
        int baseline = y + headline.getAscent();
        g.drawString(prompt.headline(), PROMPT_PADDING, baseline);
        if (prompt.detail() != null) {
          int detailX = PROMPT_PADDING + headline.stringWidth(prompt.headline()) + 12;
          g.setFont(CoachPaint.font(prompt.detail(), Font.PLAIN, 14f));
          g.setColor(WARM_DETAIL);
          g.drawString(prompt.detail(), detailX, baseline);
        }
      }
      y += 36;
    }
    if (!body.isEmpty()) {
      y += GAP;
      if (g != null) {
        g.setFont(bodyFont);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(prompt.warm() ? WARM_BODY : CoachPanel.MUTED);
        for (int i = 0; i < body.size(); i++) {
          g.drawString(
              body.get(i),
              PROMPT_PADDING,
              y + i * PROMPT_LINE + (PROMPT_LINE - metrics.getHeight()) / 2 + metrics.getAscent());
        }
      }
      y += body.size() * PROMPT_LINE;
    }
    int choices = prompt.choices().size();
    if (choices > 0) {
      y = placeButtons(y + GAP, inner, choices);
    }
    return y + PROMPT_PADDING;
  }

  /**
   * Two buttons side by side, a third one under them across the card; a single one takes the left
   * half. Returns the bottom of the buttons.
   */
  private int placeButtons(int top, int inner, int count) {
    int half = (inner - 8) / 2;
    buttons.get(0).setBounds(PROMPT_PADDING, top, half, BUTTON);
    if (count >= 2) {
      buttons.get(1).setBounds(PROMPT_PADDING + half + 8, top, inner - half - 8, BUTTON);
    }
    int bottom = top + BUTTON;
    if (count >= 3) {
      buttons.get(2).setBounds(PROMPT_PADDING, bottom + 8, inner, QUIET_BUTTON);
      bottom += 8 + QUIET_BUTTON;
    }
    return bottom;
  }

  /** The modes that do not rate moves: the label, then the move in bold. */
  private int plainMove(Graphics2D g, CoachPanelState.MoveCard card, int width) {
    int height = 62;
    if (g != null) {
      g.setColor(CoachPanel.SURFACE);
      g.fillRoundRect(0, 0, width, height, 24, 24);
      g.setColor(CoachPanel.MUTED);
      g.setFont(CoachPaint.font(card.label(), Font.PLAIN, 12.5f));
      g.drawString(card.label(), PADDING, 23);
      g.setColor(CoachPanel.TEXT);
      g.setFont(CoachPaint.font(card.move(), Font.BOLD, 15f));
      g.drawString(CoachPaint.fit(g.getFontMetrics(), card.move(), width - 28), PADDING, 47);
    }
    return height;
  }

  /** 边下边提醒: "你的上一手 · 第 71 手 L15 … 胜率 +1%", then the rating and why. */
  private int ratedMove(Graphics2D g, CoachPanelState.MoveCard card, int width) {
    Font tagFont = CoachPaint.font(card.verdictLabel(), Font.BOLD, 13f);
    int tagWidth =
        card.verdictLabel() == null
            ? 0
            : getFontMetrics(tagFont).stringWidth(card.verdictLabel()) + 14 + 10;
    Font bodyFont = CoachPaint.font(card.explanation(), Font.PLAIN, 13.5f);
    List<String> lines =
        CoachPaint.wrap(
            card.explanation(), getFontMetrics(bodyFont), width - 2 * PADDING - tagWidth);
    int bodyHeight =
        card.verdict() == null && lines.isEmpty() ? 0 : Math.max(24, lines.size() * LINE);
    int height = PADDING + 18 + (bodyHeight == 0 ? 0 : 10 + bodyHeight) + PADDING;
    if (g == null) {
      return height;
    }
    g.setColor(CoachPanel.SURFACE);
    g.fillRoundRect(0, 0, width, height, 24, 24);
    String change = card.change() == null ? "" : card.change();
    Font changeFont = CoachPaint.font(change, Font.BOLD, 13f);
    int changeWidth = getFontMetrics(changeFont).stringWidth(change);
    g.setFont(changeFont);
    g.setColor(
        card.verdict() == null
            ? CoachPanel.MUTED
            : CoachPaint.changeColor(card.verdict(), card.change()));
    g.drawString(change, width - PADDING - changeWidth, PADDING + 13);
    String title = card.label() + " · " + card.move();
    g.setFont(CoachPaint.font(title, Font.PLAIN, 13f));
    g.setColor(CoachPanel.MUTED);
    g.drawString(
        CoachPaint.fit(g.getFontMetrics(), title, width - 2 * PADDING - changeWidth - 8),
        PADDING,
        PADDING + 13);
    int top = PADDING + 18 + 10;
    if (card.verdict() != null) {
      g.setFont(tagFont);
      CoachPaint.tag(
          g,
          PADDING,
          top,
          24,
          card.verdictLabel(),
          CoachPaint.verdictSolid(card.verdict()),
          CoachPaint.verdictSolidText(card.verdict()));
    }
    g.setFont(bodyFont);
    g.setColor(CoachPaint.BODY_TEXT);
    FontMetrics body = g.getFontMetrics();
    int lineTop = lines.size() == 1 ? top + (24 - LINE) / 2 : top;
    for (int i = 0; i < lines.size(); i++) {
      g.drawString(
          lines.get(i),
          PADDING + tagWidth,
          lineTop + i * LINE + (LINE - body.getHeight()) / 2 + body.getAscent());
    }
    return height;
  }

  /** 你赢了 / 你输了, the result, a line of numbers, 开始复盘 and 再来一盘, then where review is. */
  private int summary(Graphics2D g, CoachPanelState.Summary summary, int width) {
    int inner = width - 2 * PROMPT_PADDING;
    Font statsFont = CoachPaint.font(summary.stats(), Font.PLAIN, 12.5f);
    List<String> stats = CoachPaint.wrap(summary.stats(), getFontMetrics(statsFont), inner);
    Font hintFont = CoachPaint.font(summary.hint(), Font.PLAIN, 12.5f);
    List<String> hint = CoachPaint.wrap(summary.hint(), getFontMetrics(hintFont), inner);
    int y = PROMPT_PADDING;
    if (g != null) {
      g.setColor(CoachPanel.RAISED);
      g.fillRoundRect(0, 0, width, getHeight(), 28, 28);
      g.setColor(HINT_EDGE);
      g.drawRoundRect(0, 0, width - 1, getHeight() - 1, 28, 28);
      g.setColor(CoachPanel.MUTED);
      g.setFont(CoachPaint.font(summary.label(), Font.PLAIN, 12.5f));
      g.drawString(summary.label(), PROMPT_PADDING, y + g.getFontMetrics().getAscent());
    }
    y += 20;
    if (g != null) {
      g.setFont(CoachPaint.font(summary.headline(), Font.BOLD, 26f));
      FontMetrics headline = g.getFontMetrics();
      g.setColor(summary.won() ? CoachPanel.ACCENT_TEXT : CoachPanel.TEXT);
      g.drawString(summary.headline(), PROMPT_PADDING, y + headline.getAscent());
      int resultX = PROMPT_PADDING + headline.stringWidth(summary.headline()) + 12;
      g.setFont(CoachPaint.font(summary.result(), Font.PLAIN, 15f));
      g.setColor(CoachPaint.BODY_TEXT);
      g.drawString(
          CoachPaint.fit(g.getFontMetrics(), summary.result(), width - PROMPT_PADDING - resultX),
          resultX,
          y + headline.getAscent());
    }
    y += 36;
    if (g != null) {
      g.setFont(statsFont);
      g.setColor(CoachPanel.MUTED);
      for (int i = 0; i < stats.size(); i++) {
        g.drawString(stats.get(i), PROMPT_PADDING, y + i * LINE + g.getFontMetrics().getAscent());
      }
    }
    y += stats.size() * LINE;
    y = placeButtons(y + GAP, inner, summary.choices().size()) + GAP;
    if (g != null) {
      g.setFont(hintFont);
      g.setColor(CoachPanel.MUTED);
      for (int i = 0; i < hint.size(); i++) {
        g.drawString(hint.get(i), PROMPT_PADDING, y + i * LINE + g.getFontMetrics().getAscent());
      }
    }
    return y + hint.size() * LINE + PROMPT_PADDING;
  }
}
