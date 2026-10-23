package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.util.List;
import java.util.MissingResourceException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Timer;

/**
 * The AI Coach panel at the right of the window while a coaching game runs: who you play, the
 * game's state, captures and time, your win chance, your last move and, in 边下边提醒, its rating and
 * the game's record so far, then the result and the game's actions. Its buttons never take keyboard
 * focus, so the board keeps Space and its other shortcuts.
 */
public final class CoachPanel extends JPanel {
  static final int WIDTH = 360;

  static final Color BACKGROUND = new Color(0x17, 0x19, 0x1a);
  static final Color SURFACE = new Color(0x20, 0x24, 0x26);
  static final Color RAISED = new Color(0x26, 0x2b, 0x2d);
  static final Color LINE = new Color(0x2b, 0x30, 0x32);
  static final Color TEXT = new Color(0xec, 0xef, 0xee);
  static final Color MUTED = new Color(0xa2, 0xab, 0xa7);
  static final Color ACCENT = new Color(0x3d, 0xbe, 0xaa);
  static final Color ACCENT_TEXT = new Color(0x8e, 0xe6, 0xd6);
  static final Color WARNING = new Color(0xf2, 0xc1, 0x4e);
  static final Color DANGER = new Color(0xff, 0x8a, 0x7f);

  private final LizzieFrame frame;
  private final Supplier<Config> config;
  private final CoachFocusLayout focusLayout = new CoachFocusLayout();
  private final CoachSummaryView summary = new CoachSummaryView(this::state);
  private final CoachMoveCard moveCard = new CoachMoveCard(this::state, this::onPromptAction);
  private final CoachHistoryView history =
      new CoachHistoryView(
          this::state, moveNumber -> withController(active -> active.reviewFrom(moveNumber)));
  private final JPanel actions;
  private final CoachPanelContent content;
  private final JScrollPane scroll;
  private final JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
  private final CoachButton hintButton = CoachButton.accent();
  private final CoachButton takeBackButton = CoachButton.secondary();
  private final CoachButton passButton = CoachButton.secondary();
  private final CoachButton resignButton = CoachButton.secondary();
  private final CoachButton retryButton = CoachButton.primary();
  private final CoachButton finishButton = CoachButton.quiet();
  private final Timer refreshTimer = new Timer(500, event -> refreshFromController());
  private HumanSlGameController controller;
  private CoachPanelState state = CoachPanelState.EMPTY;
  private int marksVersion;

  CoachPanel(LizzieFrame frame) {
    this(frame, () -> Lizzie.config);
  }

  CoachPanel(LizzieFrame frame, Supplier<Config> config) {
    super(null);
    this.frame = frame;
    this.config = config;
    setOpaque(true);
    setBackground(BACKGROUND);
    setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, LINE),
            BorderFactory.createEmptyBorder(18, 18, 18, 18)));
    setVisible(false);
    actions = buildActions();
    content = new CoachPanelContent(summary, moveCard, history, actions);
    content.setBorder(getBorder());
    setBorder(null);
    scroll = new JScrollPane(content);
    scroll.setBorder(null);
    scroll.setFocusable(false);
    scroll.getVerticalScrollBar().setFocusable(false);
    scroll.getViewport().setBackground(BACKGROUND);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
    add(scroll);
  }

  public void attach(HumanSlGameController value) {
    controller = value;
    if (value == null) {
      return;
    }
    focusLayout.enter(frame, config.get());
    if (LizzieFrame.boardRenderer != null) {
      LizzieFrame.boardRenderer.setCoachMarks(value.assist().marks());
    }
    setVisible(true);
    refreshTimer.start();
    refreshFromController();
  }

  public void detach(HumanSlGameController value) {
    if (value != null && controller != value) {
      return;
    }
    controller = null;
    refreshTimer.stop();
    if (LizzieFrame.boardRenderer != null) {
      LizzieFrame.boardRenderer.setCoachMarks(null);
    }
    setVisible(false);
    focusLayout.exit(frame, config.get());
  }

  /** Re-reads the game; the controller calls this after every change. */
  public void refresh() {
    refreshFromController();
  }

  CoachPanelState state() {
    return state;
  }

  List<CoachButton> buttons() {
    List<CoachButton> all =
        new java.util.ArrayList<>(
            List.of(
                hintButton, takeBackButton, passButton, resignButton, retryButton, finishButton));
    all.addAll(moveCard.buttons());
    return all;
  }

  /** Full-height content on roomy screens; a vertical scrollbar keeps short windows usable. */
  @Override
  public void doLayout() {
    scroll.setBounds(0, 0, getWidth(), getHeight());
    scroll.doLayout();
    scroll.getViewport().doLayout();
    content.doLayout();
  }

  private JPanel buildActions() {
    JPanel panel = new JPanel(new BorderLayout(0, 8));
    panel.setOpaque(false);
    grid.setOpaque(false);
    hintButton.setText(text("CoachPanel.hint", "Hint"));
    hintButton.setShortcut("H");
    takeBackButton.setText(text("HumanSlTraining.takeBack", "Take back"));
    passButton.setText(text("HumanSlGame.btn.pass", "Pass"));
    resignButton.setText(text("HumanSlGame.btn.resign", "Resign"));
    retryButton.setText(text("HumanSlTraining.retryAi", "Retry AI"));
    finishButton.setText(text("CoachPanel.finish", "End game"));
    hintButton.addActionListener(event -> withController(HumanSlGameController::hint));
    takeBackButton.addActionListener(event -> withController(HumanSlGameController::takeBack));
    passButton.addActionListener(event -> withController(HumanSlGameController::humanPass));
    resignButton.addActionListener(event -> withController(HumanSlGameController::resign));
    retryButton.addActionListener(event -> withController(HumanSlGameController::retryAiMove));
    finishButton.addActionListener(
        event ->
            withController(
                active -> {
                  if (active.isStopRequested()) {
                    active.abort();
                  } else {
                    active.finishAndReturnToBoard();
                  }
                }));
    showInGrid(List.of(takeBackButton, passButton, resignButton));
    panel.add(grid, BorderLayout.CENTER);
    panel.add(finishButton, BorderLayout.SOUTH);
    return panel;
  }

  private void refreshFromController() {
    HumanSlGameController active = controller;
    if (active == null) {
      return;
    }
    if (active.isFinished()) {
      detach(active);
      return;
    }
    state = CoachPanelState.of(active, CoachPanel::text);
    boolean stopping = active.isStopRequested();
    hintButton.setEnabled(!stopping && active.canHint());
    takeBackButton.setEnabled(!stopping && active.canTakeBack());
    passButton.setEnabled(
        !stopping && active.isHumanTurn() && !active.isGameOver() && !active.isCounting());
    resignButton.setEnabled(!stopping && active.canResign());
    List<CoachButton> shown = new java.util.ArrayList<>();
    if (active.moveReviewer() != null) {
      shown.add(hintButton);
    }
    shown.addAll(List.of(takeBackButton, passButton, resignButton));
    if (!stopping && active.hasAiFailure()) {
      shown.add(retryButton);
    }
    showInGrid(shown);
    if (active.isExitRecoveryPending()) {
      finishButton.setText(text("HumanSlTraining.retryCleanup", "Retry cleanup"));
      finishButton.setKind(CoachButton.Kind.PRIMARY);
    } else if (stopping) {
      finishButton.setText(text("HumanSlTraining.stopping", "Stopping..."));
      finishButton.setKind(CoachButton.Kind.QUIET);
    } else if (active.isGameOver()) {
      finishButton.setText(text("CoachPanel.review", "Start review"));
      finishButton.setKind(CoachButton.Kind.PRIMARY);
    } else {
      finishButton.setText(text("CoachPanel.finish", "End game"));
      finishButton.setKind(CoachButton.Kind.QUIET);
    }
    finishButton.setEnabled(!active.isExitInProgress());
    // After the game the summary card carries 开始复盘 and 再来一盘; the game's buttons are done.
    boolean over = active.isGameOver();
    grid.setVisible(!over);
    finishButton.setVisible(!over || stopping || active.isExitRecoveryPending());
    int version = active.assist().marks().version();
    if (version != marksVersion) {
      marksVersion = version;
      if (frame != null) {
        frame.refresh();
      }
    }
    content.revalidate();
    repaint();
  }

  /**
   * The grid keeps only the buttons that apply, so a hidden one never leaves an empty row: a grid
   * lays out its invisible children too.
   */
  private void showInGrid(List<CoachButton> shown) {
    if (java.util.Arrays.asList(grid.getComponents()).equals(shown)) {
      return;
    }
    grid.removeAll();
    shown.forEach(grid::add);
    grid.revalidate();
  }

  private void onPromptAction(CoachPanelState.Action action) {
    switch (action) {
      case RETRY:
        withController(HumanSlGameController::retryBlunder);
        break;
      case SHOW_BEST:
        withController(HumanSlGameController::showBestMove);
        break;
      case CONTINUE:
        withController(HumanSlGameController::continueAfterBlunder);
        break;
      case MORE_HINT:
        withController(HumanSlGameController::hint);
        break;
      case REVIEW:
        withController(HumanSlGameController::finishAndReturnToBoard);
        break;
      case PLAY_AGAIN:
        withController(
            active -> {
              if (frame != null) {
                active.abortAndThen(frame::handleAiCoachToolbarAction);
              } else {
                active.abort();
              }
            });
        break;
      case CLOSE_HINT:
      default:
        withController(HumanSlGameController::closeHint);
        break;
    }
  }

  private void withController(Consumer<HumanSlGameController> action) {
    HumanSlGameController active = controller;
    if (active != null) {
      action.accept(active);
    }
    refreshFromController();
  }

  static String text(String key, String fallback) {
    try {
      return Lizzie.resourceBundle == null ? fallback : Lizzie.resourceBundle.getString(key);
    } catch (MissingResourceException e) {
      return fallback;
    }
  }

  /** Keeps translucent overlays from tinting the panel's own background in screenshots. */
  @Override
  protected void paintComponent(Graphics graphics) {
    Graphics2D g = (Graphics2D) graphics.create();
    try {
      g.setComposite(AlphaComposite.Src);
      g.setColor(BACKGROUND);
      g.fillRect(0, 0, getWidth(), getHeight());
    } finally {
      g.dispose();
    }
  }
}
