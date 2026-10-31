package featurecat.lizzie.gui;

import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanThinkTime;
import featurecat.lizzie.training.OpponentPreset;
import featurecat.lizzie.training.RankScale;
import featurecat.lizzie.training.TrainingMode;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import javax.swing.JButton;
import javax.swing.JPanel;

/**
 * The settings of the AI Coach setup sheet, top to bottom: the opponent's strength on one scale
 * from 20 kyu to 9 dan, the training mode as three cards, colour and AI 思考时间 side by side, and the
 * handicap, komi and starting position under 更多设置. The labels and values are drawn here; the
 * controls are {@link CoachSetupWidgets}.
 */
final class CoachSetupForm extends JPanel {
  static final int WIDTH = 648;
  private static final int SECTION_GAP = 20;
  private static final int LABEL = 18;
  private static final int RANK_TEXT = 38;
  private static final int ROW = 60;
  private static final double KOMI = 7.5;
  private static final double HANDICAP_KOMI = 0.5;

  private final BinaryOperator<String> text;
  private final Runnable onResize;
  private final CoachSetupWidgets.RankSlider slider;
  private final CoachSetupWidgets.SheetButton weaker;
  private final CoachSetupWidgets.SheetButton stronger;
  private final CoachRankTable.Link rankTable;
  private final List<CoachSetupWidgets.ChoiceCard> modeCards = new ArrayList<>();
  private final CoachSetupWidgets.Segmented color;
  private final CoachSetupWidgets.Segmented pace;
  private final JButton moreHeader;
  private final CoachSetupWidgets.SheetButton fewerStones;
  private final CoachSetupWidgets.SheetButton moreStones;
  private final CoachSetupWidgets.Switch fromCurrent;
  private final boolean fromCurrentAvailable;
  private TrainingMode mode = TrainingMode.LIVE_FEEDBACK;
  private int handicap;
  private boolean more;

  // Where the drawn parts go; set by doLayout.
  private int strengthTop;
  private int modeTop;
  private int pairTop;
  private int moreTop;

  /**
   * @param text looks up a localized string by key, with an English fallback
   * @param fromCurrentAvailable whether the board has a position to start from
   * @param onResize called when 更多设置 opens or closes, so the dialog can fit it
   */
  CoachSetupForm(BinaryOperator<String> text, boolean fromCurrentAvailable, Runnable onResize) {
    super(null);
    this.text = text;
    this.onResize = onResize;
    this.fromCurrentAvailable = fromCurrentAvailable;
    moreHeader = new MoreHeader();
    setOpaque(false);
    slider = new CoachSetupWidgets.RankSlider(RankScale.STOPS, value -> rankChanged());
    slider.getAccessibleContext().setAccessibleName(t("HumanSlTraining.strength", "Opponent"));
    weaker =
        new CoachSetupWidgets.SheetButton(
            CoachSetupWidgets.SheetButton.Shape.ROUND,
            CoachSetupWidgets.SheetButton.Glyph.MINUS,
            t("HumanSlTraining.rank.down", "One step weaker"));
    stronger =
        new CoachSetupWidgets.SheetButton(
            CoachSetupWidgets.SheetButton.Shape.ROUND,
            CoachSetupWidgets.SheetButton.Glyph.PLUS,
            t("HumanSlTraining.rank.up", "One step stronger"));
    rankTable =
        new CoachRankTable.Link(
            t(
                "HumanSlTraining.rankTable.tip",
                "Compare KataGo ranks with Fox / Tygem and real-world grades"));
    rankTable.addActionListener(
        event ->
            CoachRankTable.show(
                this, text, RankScale.rank(slider.value()), RankScale.dan(slider.value())));
    weaker.addActionListener(event -> slider.setValue(slider.value() - 1, true));
    stronger.addActionListener(event -> slider.setValue(slider.value() + 1, true));
    for (TrainingMode choice : TrainingMode.CHOICES) {
      String[] label = choice.label();
      String[] description = description(choice);
      CoachSetupWidgets.ChoiceCard card =
          new CoachSetupWidgets.ChoiceCard(
              t(label[0], label[1]),
              t(description[0], description[1]),
              choice == TrainingMode.CHOICES.get(0)
                  ? t("HumanSlTraining.default", "Default")
                  : null,
              () -> selectMode(choice));
      modeCards.add(card);
    }
    color =
        new CoachSetupWidgets.Segmented(
            t("HumanSlTraining.color", "Your color"),
            List.of(
                t("HumanSlTraining.color.black", "Black"),
                t("HumanSlTraining.color.white", "White"),
                t("HumanSlTraining.color.random", "Random")),
            List.of(
                CoachSetupWidgets.Segmented.Dot.BLACK,
                CoachSetupWidgets.Segmented.Dot.WHITE,
                CoachSetupWidgets.Segmented.Dot.HALF),
            index -> {});
    pace =
        new CoachSetupWidgets.Segmented(
            t("HumanSlTraining.pace", "AI thinking time"),
            List.of(
                t("HumanSlTraining.pace.natural", "Natural"),
                t("HumanSlTraining.pace.fast", "Fast"),
                t("HumanSlTraining.pace.slow", "Slow")),
            List.of(
                CoachSetupWidgets.Segmented.Dot.NONE,
                CoachSetupWidgets.Segmented.Dot.NONE,
                CoachSetupWidgets.Segmented.Dot.NONE),
            index -> repaint());
    moreHeader.addActionListener(event -> setMoreVisible(!more));
    fewerStones =
        new CoachSetupWidgets.SheetButton(
            CoachSetupWidgets.SheetButton.Shape.SQUARE,
            CoachSetupWidgets.SheetButton.Glyph.MINUS,
            t("HumanSlTraining.handicap.less", "Fewer handicap stones"));
    moreStones =
        new CoachSetupWidgets.SheetButton(
            CoachSetupWidgets.SheetButton.Shape.SQUARE,
            CoachSetupWidgets.SheetButton.Glyph.PLUS,
            t("HumanSlTraining.handicap.more", "More handicap stones"));
    fewerStones.addActionListener(event -> setHandicap(handicap - 1));
    moreStones.addActionListener(event -> setHandicap(handicap + 1));
    fromCurrent =
        new CoachSetupWidgets.Switch(
            t("HumanSlTraining.fromCurrent", "Start from the current position"), () -> {});
    add(rankTable);
    add(weaker);
    add(slider);
    add(stronger);
    modeCards.forEach(this::add);
    add(color);
    add(pace);
    add(moreHeader);
    add(fewerStones);
    add(moreStones);
    add(fromCurrent);
    selectMode(mode);
    slider.setValue(RankScale.index(3, true), false);
    updateMoreControls();
  }

  /** Shows the settings of the last game. */
  void restore(HumanSlTrainingConfig config, boolean moreVisible) {
    slider.setValue(RankScale.index(config.rank, config.danRank), false);
    selectMode(config.mode.choice());
    color.select(colorIndex(config.playerColor), false);
    pace.select(config.pace.ordinal(), false);
    handicap = Math.max(0, Math.min(9, config.handicap));
    more = moreVisible;
    updateMoreControls();
    repaint();
  }

  /** The game the sheet describes. */
  HumanSlTrainingConfig config() {
    return HumanSlTrainingConfig.builder()
        .mode(mode)
        .opponentPreset(OpponentPreset.RANK)
        .rank(RankScale.rank(slider.value()), RankScale.dan(slider.value()))
        .playerColor(handicap > 0 ? HumanSlTrainingConfig.PlayerColor.BLACK : playerColor())
        .pace(HumanThinkTime.Pace.values()[pace.selected()])
        .moveTimeSeconds(10)
        .handicap(handicap)
        .komi(handicap > 0 ? HANDICAP_KOMI : KOMI)
        .fromCurrentPosition(fromCurrent.isOn() && fromCurrentAvailable)
        .build();
  }

  boolean isMoreVisible() {
    return more;
  }

  void selectFromCurrentPosition() {
    if (fromCurrentAvailable) {
      fromCurrent.setOn(true, false);
      setMoreVisible(true);
    }
  }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    slider.setEnabled(enabled);
    rankTable.setEnabled(enabled);
    weaker.setEnabled(enabled);
    stronger.setEnabled(enabled);
    modeCards.forEach(card -> card.setEnabled(enabled));
    pace.setEnabled(enabled);
    moreHeader.setEnabled(enabled);
    updateMoreControls();
    repaint();
  }

  private void rankChanged() {
    weaker.setEnabled(isEnabled() && slider.value() > 0);
    stronger.setEnabled(isEnabled() && slider.value() < RankScale.STOPS - 1);
    rankTable.setText(
        CoachRankTable.summary(
            text, RankScale.rank(slider.value()), RankScale.dan(slider.value())));
    revalidate();
    repaint();
  }

  private void selectMode(TrainingMode choice) {
    mode = choice;
    for (int i = 0; i < modeCards.size(); i++) {
      modeCards.get(i).setSelected(TrainingMode.CHOICES.get(i) == choice);
    }
  }

  private void setHandicap(int value) {
    handicap = Math.max(0, Math.min(9, value));
    updateMoreControls();
    repaint();
  }

  private void setMoreVisible(boolean value) {
    more = value;
    updateMoreControls();
    revalidate();
    repaint();
    onResize.run();
  }

  private void updateMoreControls() {
    boolean enabled = isEnabled();
    fewerStones.setVisible(more);
    moreStones.setVisible(more);
    fromCurrent.setVisible(more);
    fewerStones.setEnabled(enabled && handicap > 0);
    moreStones.setEnabled(enabled && handicap < 9);
    fromCurrent.setEnabled(enabled && fromCurrentAvailable);
    // With a handicap the player always takes Black and receives the stones.
    color.setEnabled(enabled && handicap == 0);
    if (handicap > 0) {
      color.select(0, false);
    }
    slider.setEnabled(enabled);
    rankChanged();
  }

  private HumanSlTrainingConfig.PlayerColor playerColor() {
    switch (color.selected()) {
      case 0:
        return HumanSlTrainingConfig.PlayerColor.BLACK;
      case 1:
        return HumanSlTrainingConfig.PlayerColor.WHITE;
      default:
        return HumanSlTrainingConfig.PlayerColor.RANDOM;
    }
  }

  private static int colorIndex(HumanSlTrainingConfig.PlayerColor value) {
    return value == HumanSlTrainingConfig.PlayerColor.BLACK
        ? 0
        : value == HumanSlTrainingConfig.PlayerColor.WHITE ? 1 : 2;
  }

  private static String[] description(TrainingMode choice) {
    switch (choice) {
      case LIVE_FEEDBACK:
        return new String[] {
          "HumanSlTraining.mode.feedback.desc",
          "Each move is rated as soon as you play it; a blunder can be played again."
        };
      case LIVE_ANALYSIS:
        return new String[] {
          "HumanSlTraining.mode.live.desc", "The AI suggestions stay on the board."
        };
      default:
        return new String[] {
          "HumanSlTraining.mode.review.desc", "No help during the game; review it all afterwards."
        };
    }
  }

  String rankLabel(int index) {
    return MessageFormat.format(
        RankScale.dan(index)
            ? t("HumanSlTraining.rank.danValue", "{0} dan")
            : t("HumanSlTraining.rank.kyuValue", "{0} kyu"),
        RankScale.rank(index));
  }

  private String paceHint() {
    switch (pace.selected()) {
      case 1:
        return t(
            "HumanSlTraining.pace.fast.hint",
            "About a second a move, for quick practice. It sets the pace only, not the strength.");
      case 2:
        return t(
            "HumanSlTraining.pace.slow.hint",
            "A few seconds more a move, like a serious game. It sets the pace only, not the"
                + " strength.");
      default:
        return t(
            "HumanSlTraining.pace.natural.hint",
            "Quick for obvious replies, longer where the choice is hard. It sets the pace only, not"
                + " the strength.");
    }
  }

  private String t(String key, String fallback) {
    return text.apply(key, fallback);
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(WIDTH, layoutHeight(WIDTH, false));
  }

  @Override
  public void doLayout() {
    layoutHeight(getWidth(), true);
  }

  /** Places the controls when {@code place} is set, and returns the height either way. */
  private int layoutHeight(int width, boolean place) {
    int y = 0;
    strengthTop = y;
    if (place) {
      Dimension link = rankTable.getPreferredSize();
      int linkWidth = Math.min(link.width, width / 2);
      rankTable.setBounds(width - linkWidth, y + (LABEL - link.height) / 2, linkWidth, link.height);
    }
    y += LABEL + 10 + RANK_TEXT + 10;
    if (place) {
      weaker.setBounds(0, y + 4, 36, 36);
      slider.setBounds(50, y, width - 100, 44);
      stronger.setBounds(width - 36, y + 4, 36, 36);
    }
    y += 44 + 18 + SECTION_GAP;
    modeTop = y;
    y += LABEL + 12;
    int cardWidth = (width - 24) / 3;
    int cardHeight = 92;
    for (CoachSetupWidgets.ChoiceCard card : modeCards) {
      cardHeight = Math.max(cardHeight, card.getPreferredSize().height);
    }
    if (place) {
      for (int i = 0; i < modeCards.size(); i++) {
        modeCards.get(i).setBounds(i * (cardWidth + 12), y, cardWidth, cardHeight);
      }
    }
    y += cardHeight + SECTION_GAP;
    pairTop = y;
    int half = (width - 20) / 2;
    if (place) {
      color.setBounds(0, y + LABEL + 12, half, 42);
      pace.setBounds(half + 20, y + LABEL + 12, width - half - 20, 42);
    }
    FontMetrics hint = getFontMetrics(CoachSetupWidgets.font(paceHint(), Font.PLAIN, 12.5f));
    int hintLines = Math.max(1, CoachPaint.wrap(paceHint(), hint, width - half - 20).size());
    y += LABEL + 12 + 42 + 10 + hintLines * 19 + SECTION_GAP;
    moreTop = y;
    if (place) {
      moreHeader.setBounds(0, y + 5, width, 44);
    }
    y += 5 + 44;
    if (more) {
      if (place) {
        int stepperRight = width;
        moreStones.setBounds(stepperRight - 32, y + (ROW - 32) / 2, 32, 32);
        fewerStones.setBounds(stepperRight - 32 - 4 - 80 - 4 - 32, y + (ROW - 32) / 2, 32, 32);
        fromCurrent.setBounds(width - 44, y + 2 * ROW + (ROW - 28) / 2, 44, 28);
      }
      y += 3 * ROW;
    }
    return y;
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = CoachSetupWidgets.smooth(graphics);
    try {
      CoachSetupWidgets.Palette p = CoachSetupWidgets.Palette.current();
      int width = getWidth();
      sectionLabel(g, t("HumanSlTraining.strength", "Opponent strength"), 0, strengthTop);
      String rank = rankLabel(slider.value());
      g.setFont(CoachSetupWidgets.font(rank, Font.BOLD, 34f));
      FontMetrics big = g.getFontMetrics();
      int baseline = strengthTop + LABEL + 10 + big.getAscent() - 2;
      g.setColor(p.text());
      g.drawString(rank, 0, baseline);
      String habit =
          MessageFormat.format(
              t("CoachPanel.imitates", "Plays like a {0} player"), rankLabel(slider.value()));
      g.setFont(CoachSetupWidgets.font(habit, Font.PLAIN, 14f));
      g.setColor(p.muted());
      g.drawString(habit, big.stringWidth(rank) + 12, baseline);
      int ticksTop = strengthTop + LABEL + 10 + RANK_TEXT + 10 + 44 - 4;
      for (int tick : CoachSetupWidgets.RankSlider.ticks()) {
        String label = rankLabel(tick);
        g.setFont(CoachSetupWidgets.font(label, Font.PLAIN, 12f));
        FontMetrics small = g.getFontMetrics();
        int x = slider.getX() + slider.xOf(tick) - small.stringWidth(label) / 2;
        g.setColor(p.faint());
        g.drawString(label, x, ticksTop + small.getAscent());
      }
      sectionLabel(g, t("HumanSlTraining.mode", "Training mode"), 0, modeTop);
      int half = (width - 20) / 2;
      sectionLabel(g, t("HumanSlTraining.color", "Your color"), 0, pairTop);
      sectionLabel(g, t("HumanSlTraining.pace", "AI thinking time"), half + 20, pairTop);
      String hint = paceHint();
      g.setFont(CoachSetupWidgets.font(hint, Font.PLAIN, 12.5f));
      FontMetrics hintMetrics = g.getFontMetrics();
      g.setColor(p.muted());
      int hintTop = pairTop + LABEL + 12 + 42 + 10;
      List<String> lines = CoachPaint.wrap(hint, hintMetrics, width - half - 20);
      for (int i = 0; i < lines.size(); i++) {
        g.drawString(lines.get(i), half + 20, hintTop + i * 19 + hintMetrics.getAscent());
      }
      g.setColor(p.divider());
      g.fillRect(0, moreTop, width, 1);
      if (more) {
        int rowTop = moreTop + 5 + 44;
        String stones =
            handicap == 0
                ? t("HumanSlTraining.handicap.none", "No handicap")
                : MessageFormat.format(t("HumanSlTraining.handicap.value", "{0} stones"), handicap);
        moreRow(
            g,
            rowTop,
            t("HumanSlTraining.handicap", "Handicap"),
            t(
                "HumanSlTraining.handicap.note",
                "With a handicap you take Black and receive the stones"),
            true);
        g.setFont(CoachSetupWidgets.font(stones, Font.PLAIN, 14f));
        FontMetrics value = g.getFontMetrics();
        g.setColor(p.text());
        int valueCenter = width - 32 - 4 - 40;
        g.drawString(
            stones,
            valueCenter - value.stringWidth(stones) / 2,
            rowTop + (ROW - value.getHeight()) / 2 + value.getAscent());
        moreRow(
            g,
            rowTop + ROW,
            t("HumanSlTraining.komi", "Komi"),
            t("HumanSlTraining.komi.note", "0.5 with a handicap"),
            true);
        String komi = handicap > 0 ? "0.5" : "7.5";
        g.setColor(p.text());
        g.drawString(
            komi,
            valueCenter - value.stringWidth(komi) / 2,
            rowTop + ROW + (ROW - value.getHeight()) / 2 + value.getAscent());
        moreRow(
            g,
            rowTop + 2 * ROW,
            t("HumanSlTraining.fromCurrent", "Start from the current position"),
            fromCurrentAvailable
                ? t(
                    "HumanSlTraining.fromCurrent.note",
                    "Play on from the position on the main board")
                : t("HumanSlTraining.fromCurrent.empty", "The main board has no moves yet"),
            false);
      }
    } finally {
      g.dispose();
    }
  }

  private void sectionLabel(Graphics2D g, String label, int x, int top) {
    g.setFont(CoachSetupWidgets.font(label, Font.BOLD, 13f));
    g.setColor(CoachSetupWidgets.Palette.current().muted());
    g.drawString(label, x, top + g.getFontMetrics().getAscent());
  }

  private void moreRow(Graphics2D g, int top, String title, String note, boolean divider) {
    CoachSetupWidgets.Palette p = CoachSetupWidgets.Palette.current();
    g.setFont(CoachSetupWidgets.font(title, Font.PLAIN, 14f));
    FontMetrics titleMetrics = g.getFontMetrics();
    g.setColor(isEnabled() ? p.text() : p.muted());
    g.drawString(title, 0, top + 10 + titleMetrics.getAscent());
    g.setFont(CoachSetupWidgets.font(note, Font.PLAIN, 12.5f));
    g.setColor(p.muted());
    g.drawString(note, 0, top + 10 + titleMetrics.getHeight() + 2 + g.getFontMetrics().getAscent());
    if (divider) {
      g.setColor(p.divider());
      g.fillRect(0, top + ROW - 1, getWidth(), 1);
    }
  }

  /** 更多设置, with a chevron that turns when the settings are open. */
  private final class MoreHeader extends JButton {
    MoreHeader() {
      super(t("HumanSlTraining.more", "More settings"));
      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setOpaque(false);
      setMargin(new Insets(0, 0, 0, 0));
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      CoachSetupWidgets.repaintOnFocus(this);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = CoachSetupWidgets.smooth(graphics);
      try {
        CoachSetupWidgets.Palette p = CoachSetupWidgets.Palette.current();
        g.setFont(CoachSetupWidgets.font(getText(), Font.BOLD, 14f));
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(isEnabled() ? p.text() : p.muted());
        g.drawString(getText(), 0, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        int cx = getWidth() - 10;
        int cy = getHeight() / 2;
        g.setColor(p.muted());
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (more) {
          g.drawPolyline(new int[] {cx - 4, cx, cx + 4}, new int[] {cy + 2, cy - 2, cy + 2}, 3);
        } else {
          g.drawPolyline(new int[] {cx - 4, cx, cx + 4}, new int[] {cy - 2, cy + 2, cy - 2}, 3);
        }
        if (isFocusOwner()) {
          CoachSetupWidgets.focusRing(g, 0, 0, getWidth(), getHeight(), 12);
        }
      } finally {
        g.dispose();
      }
    }
  }
}
