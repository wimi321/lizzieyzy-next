package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.logging.LogCategories;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanSlTrainingPreferences;
import featurecat.lizzie.training.HumanSlTrainingSession;
import featurecat.lizzie.util.AnalysisEngineCommandHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtRepairContext;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ResourceBundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Product-focused setup for one HumanSL AI coaching game. */
public final class NewHumanSlGameDialog extends JDialog {
  private static final long serialVersionUID = 1L;
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.APP);
  private static final Duration ENGINE_READY_TIMEOUT = Duration.ofSeconds(180);
  private static final int FOREGROUND_RESTORE_ATTEMPTS = 2;

  private final ResourceBundle resources = Lizzie.resourceBundle;
  private final HumanSlTrainingSession session;
  private final CoachSetupForm form;
  private final CoachSetupWidgets.SheetButton startButton =
      new CoachSetupWidgets.SheetButton(
          CoachSetupWidgets.SheetButton.Shape.PRIMARY,
          CoachSetupWidgets.SheetButton.Glyph.NONE,
          "");
  private final JFontButton pauseDownloadButton = new JFontButton();
  private final JFontButton cancelDownloadButton = new JFontButton();
  private final JLabel modelStatusLabel = new JFontLabel();
  private final JLabel statusLabel = new JFontLabel();
  private final JProgressBar downloadProgress = new JProgressBar(0, 1000);
  private final JPanel downloadPanel = new JPanel(new GridBagLayout());

  private volatile KataGoAutoSetupHelper.DownloadSession downloadSession;
  private TensorRtRepairContext pendingTensorRtRepairContext;
  private volatile HumanSlAnalysisRunner preparingRunner;
  private volatile HumanSlAnalysisRunner cleanupRetryRunner;
  private volatile Throwable cleanupRetryFailure;
  private volatile BooleanSupplier pendingPostCloseResync;
  private volatile HumanSlGameController pendingFailedHandoffController;
  private volatile boolean postCleanupRecoveryPending;
  private volatile Throwable postCleanupRecoveryFailure;
  private volatile HumanSlAnalysisRunner postCleanupRecoveryRunner;
  private volatile String postCleanupRecoveryUnavailableReason;
  private volatile CountDownLatch preparationPauseSettled = new CountDownLatch(0);
  private volatile boolean runnerCleanupInFlight;
  private volatile boolean downloading;
  private volatile boolean closeRequested;
  private Path verifiedModel;
  private boolean modelChecked;
  private boolean downloadPaused;
  private boolean cancelled = true;
  private Timer startupElapsedTimer;
  private long startupStartedNanos;
  private String startupStageText = "";
  private volatile ForegroundAnalysisPause foregroundAnalysisPause =
      ForegroundAnalysisPause.inactive();
  private final RunnerCleanupClaim runnerCleanupClaim = new RunnerCleanupClaim();

  static record RunnerPreparationOutcome(boolean ready, Throwable failure) {}

  @FunctionalInterface
  interface LifecycleAction {
    /** Runs one best-effort lifecycle action and may return a non-thrown failure. */
    Throwable run();
  }

  @FunctionalInterface
  interface TaskDispatcher {
    void dispatch(Runnable task);
  }

  static final class RunnerCleanupClaim {
    private final AtomicBoolean claimed = new AtomicBoolean();

    void reset() {
      claimed.set(false);
    }

    boolean claim() {
      return claimed.compareAndSet(false, true);
    }
  }

  public NewHumanSlGameDialog(Window owner, HumanSlTrainingSession session) {
    super(owner);
    this.session = session == null ? new HumanSlTrainingSession() : session;
    setTitle(text("HumanSlTraining.title", "AI Coach"));
    setModal(true);
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    try {
      setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException ignored) {
    }
    form = new CoachSetupForm(this::text, CoachSetupContext.ofMainWindow(), this::packForContent);
    JScrollPane contentScroll = new JScrollPane(buildContent());
    contentScroll.setBorder(null);
    contentScroll.getViewport().setBackground(CoachSetupWidgets.Palette.current().sheet());
    contentScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    contentScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
    setContentPane(contentScroll);
    restoreLastStartedSettings();
    installBehavior();
    setModelStatusText(text("HumanSlTraining.model.checking", "Checking the human-style model..."));
    modelStatusLabel.setIcon(
        new CoachSetupWidgets.Dot(CoachSetupWidgets.Palette.current().border()));
    startButton.setEnabled(false);
    HumanSlModelCheck.start(
        HumanSlGameController::resolveDefaultHumanModel,
        model -> {
          verifiedModel = model;
          modelChecked = true;
          startButton.setEnabled(!downloading);
          refreshModelStatus();
        });
    packForContent();
  }

  private JComponent buildContent() {
    configureButtons();
    buildDownloadPanel();
    JPanel root = new WidthTrackingPanel(new BorderLayout(0, 20));
    root.setName("humanSlTrainingSetup");
    root.setBackground(CoachSetupWidgets.Palette.current().sheet());
    root.setBorder(BorderFactory.createEmptyBorder(24, 36, 20, 36));
    root.add(buildHeader(), BorderLayout.NORTH);
    JPanel middle = new JPanel(new BorderLayout(0, 12));
    middle.setOpaque(false);
    middle.add(form, BorderLayout.NORTH);
    JPanel progress = new JPanel(new BorderLayout(0, 8));
    progress.setOpaque(false);
    progress.add(downloadPanel, BorderLayout.NORTH);
    statusLabel.setForeground(CoachSetupWidgets.Palette.current().muted());
    statusLabel.setVisible(false);
    progress.add(statusLabel, BorderLayout.CENTER);
    middle.add(progress, BorderLayout.CENTER);
    root.add(middle, BorderLayout.CENTER);
    root.add(buildFooter(), BorderLayout.SOUTH);
    AccessibilitySupport.applyToTree(root);
    return root;
  }

  /** The two-stone mark, "AI 陪练" and what it does. */
  private JComponent buildHeader() {
    JPanel header = new JPanel(new BorderLayout(16, 0));
    header.setOpaque(false);
    header.add(new CoachMark(), BorderLayout.WEST);
    JPanel copy = new JPanel();
    copy.setOpaque(false);
    copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
    String titleText = text("HumanSlTraining.title", "AI Coach");
    JLabel title = new JFontLabel(titleText);
    title.setFont(CoachSetupWidgets.font(titleText, Font.BOLD, 22f));
    title.setForeground(CoachSetupWidgets.Palette.current().text());
    String subtitleText =
        text(
            "HumanSlTraining.subtitle",
            "Play an AI that plays like a person, and know how good each of your moves was.");
    JLabel subtitle = new JFontLabel(subtitleText);
    subtitle.setFont(CoachSetupWidgets.font(subtitleText, Font.PLAIN, 14f));
    subtitle.setForeground(CoachSetupWidgets.Palette.current().muted());
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
    copy.add(Box.createVerticalGlue());
    copy.add(title);
    copy.add(Box.createVerticalStrut(4));
    copy.add(subtitle);
    copy.add(Box.createVerticalGlue());
    header.add(copy, BorderLayout.CENTER);
    return header;
  }

  private void configureButtons() {
    startButton.setName("humanSlTrainingStart");
    startButton.addActionListener(event -> onPrimaryAction());
  }

  private void buildDownloadPanel() {
    downloadPanel.setOpaque(false);
    downloadPanel.setVisible(false);
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = 0;
    c.anchor = GridBagConstraints.CENTER;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1.0;
    c.gridx = 0;
    downloadProgress.setStringPainted(true);
    downloadProgress.setForeground(HumanSlTrainingStyle.ACCENT);
    downloadProgress.setBackground(HumanSlTrainingStyle.ACCENT_SOFT());
    downloadProgress.setBorderPainted(false);
    downloadPanel.add(downloadProgress, c);
    c.gridx = 1;
    c.weightx = 0.0;
    c.insets = new Insets(0, 10, 0, 0);
    pauseDownloadButton.setText(text("HumanSlTraining.download.pause", "Pause"));
    HumanSlTrainingStyle.styleSecondary(pauseDownloadButton);
    pauseDownloadButton.addActionListener(event -> pauseDownload());
    downloadPanel.add(pauseDownloadButton, c);
    c.gridx = 2;
    cancelDownloadButton.setText(text("HumanSlTraining.download.cancel", "Cancel"));
    HumanSlTrainingStyle.styleSecondary(cancelDownloadButton);
    cancelDownloadButton.addActionListener(event -> cancelDownload());
    downloadPanel.add(cancelDownloadButton, c);
  }

  /** The model's state on the left; 取消 and the primary action on the right. */
  private JComponent buildFooter() {
    JPanel footer = new JPanel(new BorderLayout(16, 0));
    footer.setOpaque(false);
    footer.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(
                1, 0, 0, 0, CoachSetupWidgets.Palette.current().divider()),
            BorderFactory.createEmptyBorder(14, 0, 0, 0)));
    modelStatusLabel.setForeground(CoachSetupWidgets.Palette.current().muted());
    modelStatusLabel.setIconTextGap(8);
    footer.add(modelStatusLabel, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    buttons.setOpaque(false);
    CoachSetupWidgets.SheetButton cancel =
        new CoachSetupWidgets.SheetButton(
            CoachSetupWidgets.SheetButton.Shape.OUTLINE,
            CoachSetupWidgets.SheetButton.Glyph.NONE,
            text("HumanSlTraining.close", "Cancel"));
    cancel.addActionListener(event -> closeDialog());
    buttons.add(cancel);
    buttons.add(startButton);
    footer.add(buttons, BorderLayout.EAST);
    return footer;
  }

  /** Fits the dialog to its content, never past the screen. */
  private void packForContent() {
    pack();
    Rectangle usableBounds = HumanSlDialogBounds.usableBounds(getOwner(), this);
    Dimension target =
        HumanSlDialogBounds.fit(getSize(), null, usableBounds, 720, getPreferredSize().height);
    setMinimumSize(HumanSlDialogBounds.minimum(target, 660, 480));
    setSize(target);
    setLocationRelativeTo(getOwner());
    HumanSlDialogBounds.keepOnScreen(this, usableBounds);
  }

  private void restoreLastStartedSettings() {
    HumanSlTrainingPreferences.SavedSettings saved =
        HumanSlTrainingPreferences.load(Lizzie.config == null ? null : Lizzie.config.uiConfig);
    form.restore(saved.config(), saved.advancedVisible());
  }

  /** The two stones of the AI Coach mark in a rounded square. */
  private static final class CoachMark extends JComponent {
    @Override
    public Dimension getPreferredSize() {
      return new Dimension(46, 46);
    }

    @Override
    protected void paintComponent(java.awt.Graphics graphics) {
      java.awt.Graphics2D g = CoachSetupWidgets.smooth(graphics);
      try {
        CoachSetupWidgets.Palette p = CoachSetupWidgets.Palette.current();
        int top = (getHeight() - 46) / 2;
        g.setColor(p.tagFill());
        g.fillRoundRect(0, top, 46, 46, 24, 24);
        g.setColor(p.accent());
        g.setStroke(new java.awt.BasicStroke(2f));
        g.drawOval(9, top + 12, 14, 14);
        g.fillOval(19, top + 12, 14, 14);
        g.drawOval(19, top + 12, 14, 14);
      } finally {
        g.dispose();
      }
    }
  }

  /**
   * The sheet follows the window's width, so a short screen scrolls it up and down only; it never
   * needs a sideways scroll bar.
   */
  private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
    WidthTrackingPanel(java.awt.LayoutManager layout) {
      super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
      return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
      return Math.max(24, visible.height - 48);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
  }

  private void onPrimaryAction() {
    if (downloading) {
      return;
    }
    if (cleanupRetryRunner != null) {
      retryRunnerCleanup();
      return;
    }
    if (postCleanupRecoveryPending) {
      retryPostCleanupRecovery();
      return;
    }
    if (pendingTensorRtRepairContext != null) {
      openPendingTensorRtRepair();
      return;
    }
    if (rejectActiveUrlSgfSync()) {
      return;
    }
    if (!modelChecked) {
      return;
    }
    Path modelPath = verifiedModel;
    if (modelPath == null) {
      startModelDownload();
      return;
    }
    startConfiguredGame(modelPath);
  }

  private void startModelDownload() {
    downloading = true;
    downloadPaused = false;
    downloadSession = new KataGoAutoSetupHelper.DownloadSession();
    session.setState(HumanSlTrainingSession.State.PREPARING);
    setFormEnabled(false);
    downloadPanel.setVisible(true);
    pauseDownloadButton.setVisible(true);
    cancelDownloadButton.setVisible(true);
    downloadProgress.setIndeterminate(false);
    downloadProgress.setValue(0);
    downloadProgress.setString("0%");
    setStatusText(text("HumanSlTraining.download.starting", "Preparing HumanSL download..."));
    packForContent();
    Thread worker =
        new Thread(
            () -> {
              try {
                Path model =
                    KataGoAutoSetupHelper.downloadHumanSlModel(
                        (status, downloaded, total) ->
                            SwingUtilities.invokeLater(
                                () -> updateDownloadProgress(status, downloaded, total)),
                        downloadSession);
                SwingUtilities.invokeLater(
                    () -> {
                      if (closeRequested) {
                        return;
                      }
                      downloading = false;
                      downloadPanel.setVisible(false);
                      verifiedModel = model;
                      refreshModelStatus();
                      startConfiguredGame(model);
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      if (!closeRequested) {
                        handleDownloadStopped(true, e.getLocalizedMessage());
                      }
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      if (!closeRequested) {
                        handleDownloadStopped(false, e.getLocalizedMessage());
                      }
                    });
              }
            },
            "humansl-coach-download");
    worker.setDaemon(true);
    worker.start();
  }

  private void updateDownloadProgress(String status, long downloaded, long total) {
    setStatusText(
        status == null || status.trim().isEmpty()
            ? text("HumanSlTraining.download.running", "Downloading HumanSL model...")
            : status);
    if (total > 0L) {
      int value = (int) Math.min(1000L, downloaded * 1000L / total);
      downloadProgress.setIndeterminate(false);
      downloadProgress.setValue(value);
      downloadProgress.setString(
          (downloaded * 100L / total)
              + "%  "
              + formatMegabytes(downloaded)
              + " / "
              + formatMegabytes(total));
    } else {
      downloadProgress.setIndeterminate(true);
      downloadProgress.setString(formatMegabytes(downloaded));
    }
  }

  private void pauseDownload() {
    if (!downloading || downloadSession == null) {
      return;
    }
    downloadPaused = true;
    downloadSession.cancel();
  }

  private void cancelDownload() {
    downloadPaused = false;
    if (downloadSession != null) {
      downloadSession.cancel();
    }
  }

  private void handleDownloadStopped(boolean cancelledByUser, String detail) {
    downloading = false;
    downloadSession = null;
    session.setState(HumanSlTrainingSession.State.IDLE);
    setFormEnabled(true);
    downloadPanel.setVisible(false);
    if (downloadPaused) {
      startButton.setText(text("HumanSlTraining.download.resume", "Resume download and start"));
      setStatusText(text("HumanSlTraining.download.paused", "Download paused; progress is kept."));
    } else if (cancelledByUser) {
      setStatusText(text("HumanSlTraining.download.cancelled", "Download cancelled."));
    } else {
      setStatusText(
          MessageFormat.format(
              text("HumanSlTraining.download.failed", "Download failed: {0}"),
              detail == null ? "" : detail));
    }
  }

  private void startConfiguredGame(Path modelPath) {
    // Recheck after a model download/readiness delay: live sync may have started after the button
    // click. This guard still precedes PREPARING, foreground pause, and companion construction.
    if (isLiveUrlSgfSyncActive()) {
      Throwable resetFailure =
          resetPreparationAfterEligibilityRejection(
              session, () -> setFormEnabled(true), () -> downloadPanel.setVisible(false));
      rejectActiveUrlSgfSync();
      logLifecycleFailure("live URL-SGF preparation reset", resetFailure);
      return;
    }
    HumanSlTrainingConfig config = selectedConfig();
    boolean advancedVisible = form.isMoreVisible();
    BoardHistoryNode readinessNode =
        config.fromCurrentPosition
            ? Lizzie.board.getHistory().getCurrentHistoryNode()
            : new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)).root();
    AnalysisEngineCommandHelper.Result commandResult = resolveAnalysisCommand();
    if (!commandResult.isSuccess()) {
      pendingTensorRtRepairContext = null;
      String detail = commandResult.getMessage();
      showInlineError(
          Utils.isBlank(detail)
              ? text("HumanSlGame.error.noEngine", "No analysis engine command is available.")
              : detail);
      return;
    }
    String command = commandResult.getCommand();
    HumanSlAnalysisRunner runner = null;
    try {
      setFormEnabled(false);
      session.setState(HumanSlTrainingSession.State.PREPARING);
      runner = new HumanSlAnalysisRunner(command, modelPath);
      HumanSlAnalysisRunner preparedRunner = runner;
      pendingPostCloseResync = null;
      runnerCleanupClaim.reset();
      preparingRunner = preparedRunner;
      beginEngineStartup(preparedRunner);
      CountDownLatch pauseSettled = new CountDownLatch(1);
      preparationPauseSettled = pauseSettled;
      Thread worker =
          new Thread(
              () -> {
                RunnerPreparationOutcome outcome =
                    preparePausedRunner(
                        preparedRunner,
                        readinessNode,
                        config.humanSlProfile(),
                        ENGINE_READY_TIMEOUT,
                        pauseSettled);
                dispatchRunnerPreparationCompletion(
                    () ->
                        completeRunnerPreparation(preparedRunner, config, advancedVisible, outcome),
                    dispatchFailure ->
                        completeRunnerPreparation(
                            preparedRunner,
                            config,
                            advancedVisible,
                            new RunnerPreparationOutcome(
                                false, appendFailure(outcome.failure(), dispatchFailure))),
                    SwingUtilities::invokeLater);
              },
              "humansl-coach-start");
      worker.setDaemon(true);
      worker.start();
    } catch (RuntimeException | Error failure) {
      cancelled = true;
      preparationPauseSettled.countDown();
      if (runner != null) {
        preparingRunner = null;
        if (runnerCleanupClaim.claim()) {
          scheduleRunnerCleanup(runner, failure, null);
        } else {
          logLifecycleFailure("runner startup ownership", failure);
        }
      } else {
        completeFailedRunnerPreparation(null, failure, null);
      }
    }
  }

  static boolean isLiveUrlSgfSyncActive() {
    return LizzieFrame.urlSgf;
  }

  private boolean rejectActiveUrlSgfSync() {
    if (!isLiveUrlSgfSyncActive()) {
      return false;
    }
    showInlineError(
        text(
            "HumanSlGame.error.liveUrlSgfActive",
            "AI Coach cannot start while URL-SGF live sync is active. Stop live sync first, then try again."));
    return true;
  }

  static Throwable resetPreparationAfterEligibilityRejection(
      HumanSlTrainingSession session, Runnable enableForm, Runnable hideProgress) {
    return runCleanupLifecycle(
        null,
        () -> {
          session.setState(HumanSlTrainingSession.State.IDLE);
          return null;
        },
        () -> {
          enableForm.run();
          return null;
        },
        () -> {
          hideProgress.run();
          return null;
        });
  }

  static RunnerPreparationOutcome prepareRunner(
      HumanSlAnalysisRunner runner,
      BoardHistoryNode readinessNode,
      String profile,
      Duration timeout) {
    try {
      return new RunnerPreparationOutcome(
          runner.start() && runner.verifyReady(readinessNode, profile, timeout), null);
    } catch (RuntimeException | Error failure) {
      // The worker must always reach the EDT completion path. In particular, runtime preparation
      // failures must not strand the paused foreground engine or a disabled PREPARING dialog.
      return new RunnerPreparationOutcome(false, failure);
    }
  }

  private RunnerPreparationOutcome preparePausedRunner(
      HumanSlAnalysisRunner runner,
      BoardHistoryNode readinessNode,
      String profile,
      Duration timeout,
      CountDownLatch pauseSettled) {
    RunnerPreparationOutcome pauseFailure = null;
    try {
      Throwable previousRestoreFailure = restoreForegroundAnalysisBestEffort();
      if (previousRestoreFailure != null || foregroundAnalysisPause.isRestorePending()) {
        pauseFailure =
            new RunnerPreparationOutcome(
                false,
                previousRestoreFailure == null
                    ? new IllegalStateException(
                        "Foreground analysis restore lease remains pending.")
                    : previousRestoreFailure);
      } else {
        ForegroundAnalysisPause.PauseAttempt pauseAttempt =
            ForegroundAnalysisPause.pauseCurrentAttempt();
        foregroundAnalysisPause = pauseAttempt.pause;
        if (pauseAttempt.failure != null) {
          pauseFailure = new RunnerPreparationOutcome(false, pauseAttempt.failure);
        }
      }
    } catch (RuntimeException | Error failure) {
      pauseFailure = new RunnerPreparationOutcome(false, failure);
    } finally {
      pauseSettled.countDown();
    }
    if (pauseFailure != null) {
      return pauseFailure;
    }
    return prepareRunner(runner, readinessNode, profile, timeout);
  }

  static void dispatchRunnerPreparationCompletion(
      Runnable completion,
      Consumer<Throwable> rejectedCompletion,
      TaskDispatcher completionDispatcher) {
    try {
      completionDispatcher.dispatch(completion);
    } catch (RuntimeException | Error dispatchFailure) {
      // A rejected invokeLater must still transfer ownership to the one-shot cleanup path.
      runLifecycleCompletion(rejectedCompletion, dispatchFailure);
    }
  }

  /** Runs handoff steps in order and captures the first unchecked failure. */
  static Throwable runHandoffLifecycle(Runnable... steps) {
    if (steps == null) {
      return null;
    }
    for (Runnable step : steps) {
      if (step == null) {
        continue;
      }
      try {
        step.run();
      } catch (RuntimeException | Error failure) {
        return failure;
      }
    }
    return null;
  }

  /** Runs every cleanup action, even after an earlier action throws, and never throws itself. */
  static Throwable runCleanupLifecycle(Throwable primary, LifecycleAction... actions) {
    Throwable failure = primary;
    if (actions == null) {
      return failure;
    }
    for (LifecycleAction action : actions) {
      if (action == null) {
        continue;
      }
      try {
        failure = appendFailure(failure, action.run());
      } catch (RuntimeException | Error added) {
        failure = appendFailure(failure, added);
      }
    }
    return failure;
  }

  /**
   * Stops the companion on a worker before dispatching state/UI restoration.
   *
   * <p>If the worker cannot be created, ownership remains with the caller and completion receives
   * the dispatch failure. It may expose a retry action, but must never run blocking process
   * teardown on the EDT.
   */
  static void closeRunnerBeforeCompletion(
      Runnable closeRunner,
      Throwable initialFailure,
      Consumer<Throwable> completion,
      TaskDispatcher backgroundDispatcher,
      TaskDispatcher completionDispatcher) {
    Runnable closeTask =
        () -> {
          Throwable closeFailure =
              runCleanupLifecycle(
                  initialFailure,
                  () -> {
                    closeRunner.run();
                    return null;
                  });
          dispatchLifecycleCompletion(completion, closeFailure, completionDispatcher);
        };
    try {
      backgroundDispatcher.dispatch(closeTask);
    } catch (RuntimeException | Error dispatchFailure) {
      runLifecycleCompletion(completion, appendFailure(initialFailure, dispatchFailure));
    }
  }

  private static void dispatchLifecycleCompletion(
      Consumer<Throwable> completion, Throwable failure, TaskDispatcher completionDispatcher) {
    try {
      completionDispatcher.dispatch(() -> runLifecycleCompletion(completion, failure));
    } catch (RuntimeException | Error dispatchFailure) {
      // Losing this callback would strand the session/lease/UI. Complete on the current thread as
      // a last resort; production reaches this only if Swing itself rejects invokeLater.
      runLifecycleCompletion(completion, appendFailure(failure, dispatchFailure));
    }
  }

  private static void runLifecycleCompletion(Consumer<Throwable> completion, Throwable failure) {
    try {
      completion.accept(failure);
    } catch (RuntimeException | Error completionFailure) {
      logLifecycleFailure("runner cleanup completion", appendFailure(failure, completionFailure));
    }
  }

  private void completeRunnerPreparation(
      HumanSlAnalysisRunner runner,
      HumanSlTrainingConfig config,
      boolean advancedVisible,
      RunnerPreparationOutcome outcome) {
    // closeDialog or an earlier completion may already own this runner's one-shot cleanup.
    if (preparingRunner != runner || !runnerCleanupClaim.claim()) {
      return;
    }
    preparingRunner = null;
    if (closeRequested) {
      cancelled = true;
      scheduleRunnerCleanup(runner, outcome.failure(), null);
      return;
    }
    if (!outcome.ready()) {
      cancelled = true;
      String[] failureReason = new String[1];
      Throwable preparationFailure =
          runCleanupLifecycle(
              outcome.failure(),
              () -> {
                failureReason[0] = runner.getUnavailableReason();
                return null;
              });
      scheduleRunnerCleanup(runner, preparationFailure, failureReason[0]);
      return;
    }

    HumanSlGameController[] controller = new HumanSlGameController[1];
    ForegroundAnalysisPause.RestoreLease[] restoreLease =
        new ForegroundAnalysisPause.RestoreLease[] {
          ForegroundAnalysisPause.RestoreLease.inactive()
        };
    boolean[] restoreTransferred = new boolean[1];
    boolean[] controllerStartEntered = new boolean[1];
    Throwable handoffFailure =
        runHandoffLifecycle(
            () -> finishEngineStartup(runner),
            () -> controller[0] = new HumanSlGameController(runner, config, session),
            () -> setVisible(false),
            () -> {
              restoreLease[0] = foregroundAnalysisPause.transferRestoreResponsibility();
              foregroundAnalysisPause = ForegroundAnalysisPause.inactive();
              restoreTransferred[0] = true;
            },
            () -> {
              controllerStartEntered[0] = true;
              controller[0].startWithExternalFailureCleanup(restoreLease[0]);
            });
    if (handoffFailure == null) {
      cancelled = false;
      rememberLastStartedSettings(config, advancedVisible);
      return;
    }

    cancelled = true;
    Throwable cleanupFailure =
        reclaimFailedHandoffRestoreLease(
            controller[0],
            restoreLease[0],
            restoreTransferred[0],
            controllerStartEntered[0],
            handoffFailure);
    scheduleRunnerCleanup(runner, cleanupFailure, null);
    if (!isVisible()) {
      Throwable visibilityFailure =
          runCleanupLifecycle(
              null,
              () -> {
                // Re-enter the modal secondary loop before returning to LizzieFrame, which would
                // otherwise dispose this dialog while its runner/lease recovery is still active.
                setVisible(true);
                return null;
              });
      logLifecycleFailure("failed handoff dialog restore", visibilityFailure);
    }
  }

  private void rememberLastStartedSettings(HumanSlTrainingConfig config, boolean advancedVisible) {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return;
    }
    try {
      HumanSlTrainingPreferences.store(Lizzie.config.uiConfig, config, advancedVisible);
      Lizzie.config.save();
    } catch (IOException | RuntimeException failure) {
      // A preference write must never turn a successfully started coaching game into a failure.
      LOG.warn("Failed to remember the last AI Coach settings", failure);
    }
  }

  private Throwable reclaimFailedHandoffRestoreLease(
      HumanSlGameController controller,
      ForegroundAnalysisPause.RestoreLease transferredLease,
      boolean restoreTransferred,
      boolean controllerStartEntered,
      Throwable startupFailure) {
    if (!restoreTransferred) {
      return startupFailure;
    }
    ForegroundAnalysisPause.RestoreLease[] recoveryLease =
        new ForegroundAnalysisPause.RestoreLease[] {
          transferredLease == null
              ? ForegroundAnalysisPause.RestoreLease.inactive()
              : transferredLease
        };
    return runCleanupLifecycle(
        startupFailure,
        () -> {
          if (controllerStartEntered && controller != null) {
            ForegroundAnalysisPause.RestoreLease released =
                controller.releaseFailedStartRestoreLease();
            if (released != null
                && (released.isRestorePending() || !recoveryLease[0].isRestorePending())) {
              recoveryLease[0] = released;
            }
            pendingPostCloseResync = controller.releaseFailedStartPrimaryResync();
            pendingFailedHandoffController = controller;
          }
          return null;
        },
        () -> {
          foregroundAnalysisPause = ForegroundAnalysisPause.adopt(recoveryLease[0]);
          return null;
        });
  }

  private void scheduleRunnerCleanup(
      HumanSlAnalysisRunner runner, Throwable failure, String unavailableReason) {
    runnerCleanupInFlight = true;
    AtomicBoolean cleanupSucceeded = new AtomicBoolean();
    closeRunnerBeforeCompletion(
        () -> {
          awaitPreparationPauseSettled();
          runner.close();
          BooleanSupplier resync = pendingPostCloseResync;
          if (resync != null) {
            if (!resync.getAsBoolean()) {
              throw new IllegalStateException(
                  text(
                      "HumanSlGame.error.primaryResyncFailed",
                      "The foreground engine did not accept the restored pre-coach position."));
            }
            pendingPostCloseResync = null;
          }
          Throwable restoreFailure = restoreForegroundAnalysisBestEffort();
          if (restoreFailure != null || foregroundAnalysisPause.isRestorePending()) {
            if (restoreFailure != null) {
              throwUnchecked(restoreFailure);
            }
            throw new IllegalStateException("Foreground analysis restore lease remains pending.");
          }
          cleanupSucceeded.set(true);
        },
        failure,
        closeFailure -> {
          runnerCleanupInFlight = false;
          if (!cleanupSucceeded.get()) {
            retainFailedRunnerCleanup(runner, closeFailure);
            return;
          }
          cleanupRetryRunner = null;
          cleanupRetryFailure = null;
          if (closeRequested) {
            completeCancelledRunnerPreparation(runner, closeFailure);
          } else {
            completeFailedRunnerPreparation(runner, closeFailure, unavailableReason);
          }
        },
        NewHumanSlGameDialog::dispatchRunnerClose,
        SwingUtilities::invokeLater);
  }

  private void retainFailedRunnerCleanup(HumanSlAnalysisRunner runner, Throwable failure) {
    cleanupRetryRunner = runner;
    cleanupRetryFailure = failure;
    Throwable uiFailure =
        runCleanupLifecycle(
            failure,
            () -> {
              session.setState(HumanSlTrainingSession.State.PREPARING);
              return null;
            },
            () -> {
              finishEngineStartup(runner);
              return null;
            },
            () -> {
              setFormEnabled(false);
              startButton.setEnabled(true);
              startButton.setText(text("HumanSlTraining.retryCleanup", "Retry cleanup"));
              HumanSlTrainingStyle.styleDanger(startButton);
              return null;
            },
            () -> {
              showInlineError(
                  text(
                      "HumanSlGame.error.exitRecoveryFailed",
                      "The AI Coach process could not be closed safely. Retry cleanup before continuing."));
              return null;
            },
            () -> {
              if (!isVisible()) {
                setVisible(true);
              }
              return null;
            });
    logLifecycleFailure("runner close/resync retry", uiFailure);
  }

  private void retryRunnerCleanup() {
    if (runnerCleanupInFlight) {
      return;
    }
    HumanSlAnalysisRunner runner = cleanupRetryRunner;
    if (runner == null) {
      return;
    }
    Throwable failure = cleanupRetryFailure;
    cleanupRetryRunner = null;
    cleanupRetryFailure = null;
    setFormEnabled(false);
    scheduleRunnerCleanup(runner, failure, null);
  }

  private void retryPostCleanupRecovery() {
    if (!postCleanupRecoveryPending) {
      return;
    }
    Throwable failure = postCleanupRecoveryFailure;
    HumanSlAnalysisRunner runner = postCleanupRecoveryRunner;
    String unavailableReason = postCleanupRecoveryUnavailableReason;
    postCleanupRecoveryPending = false;
    postCleanupRecoveryFailure = null;
    postCleanupRecoveryRunner = null;
    postCleanupRecoveryUnavailableReason = null;
    if (closeRequested) {
      completeCancelledRunnerPreparation(runner, failure);
    } else {
      completeFailedRunnerPreparation(runner, failure, unavailableReason);
    }
  }

  private void retainPostCleanupRecovery(
      HumanSlAnalysisRunner runner, Throwable failure, String unavailableReason) {
    postCleanupRecoveryPending = true;
    postCleanupRecoveryFailure = failure;
    postCleanupRecoveryRunner = runner;
    postCleanupRecoveryUnavailableReason = unavailableReason;
    Throwable uiFailure =
        runCleanupLifecycle(
            failure,
            () -> {
              session.setState(HumanSlTrainingSession.State.PREPARING);
              return null;
            },
            () -> {
              setFormEnabled(false);
              startButton.setEnabled(true);
              startButton.setText(text("HumanSlTraining.retryCleanup", "Retry cleanup"));
              HumanSlTrainingStyle.styleDanger(startButton);
              return null;
            },
            () -> {
              showInlineError(foregroundRestoreFailureMessage(failure));
              return null;
            });
    logLifecycleFailure("post-runner recovery retry", uiFailure);
  }

  private static void dispatchRunnerClose(Runnable task) {
    Thread closer = new Thread(task, "humansl-coach-prepare-close");
    closer.setDaemon(true);
    closer.start();
  }

  private void awaitPreparationPauseSettled() {
    CountDownLatch pauseSettled = preparationPauseSettled;
    try {
      pauseSettled.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for foreground pause ownership.", interrupted);
    }
  }

  private void completeFailedRunnerPreparation(
      HumanSlAnalysisRunner runner, Throwable preparationFailure, String unavailableReason) {
    Throwable[] restoreFailure = new Throwable[1];
    Throwable cleanupFailure =
        runCleanupLifecycle(
            preparationFailure,
            () -> {
              try {
                restoreFailure[0] = restoreForegroundAnalysisBestEffort();
                return restoreFailure[0];
              } catch (RuntimeException | Error failure) {
                restoreFailure[0] = failure;
                throw failure;
              }
            },
            () -> {
              if (restoreFailure[0] != null) {
                return null;
              }
              HumanSlGameController failedController = pendingFailedHandoffController;
              if (failedController != null) {
                Throwable settingsFailure =
                    failedController.restoreFailedStartAnalysisSettingsBestEffort();
                if (settingsFailure != null) {
                  restoreFailure[0] = settingsFailure;
                  return settingsFailure;
                }
              }
              return null;
            },
            () -> {
              if (restoreFailure[0] == null) {
                try {
                  session.setState(HumanSlTrainingSession.State.IDLE);
                } catch (RuntimeException | Error failure) {
                  restoreFailure[0] = failure;
                  throw failure;
                }
              }
              return null;
            },
            () -> {
              try {
                finishEngineStartup(runner);
              } catch (RuntimeException | Error failure) {
                if (restoreFailure[0] == null) {
                  restoreFailure[0] = failure;
                }
                throw failure;
              }
              return null;
            },
            () -> {
              if (restoreFailure[0] == null) {
                try {
                  setFormEnabled(true);
                  restorePrimaryActionLabel();
                } catch (RuntimeException | Error failure) {
                  restoreFailure[0] = failure;
                  throw failure;
                }
              }
              return null;
            },
            () -> {
              presentPreparationFailure(
                  runner, preparationFailure, unavailableReason, restoreFailure[0]);
              return null;
            });
    if (restoreFailure[0] == null) {
      pendingFailedHandoffController = null;
      postCleanupRecoveryPending = false;
      postCleanupRecoveryFailure = null;
      postCleanupRecoveryRunner = null;
      postCleanupRecoveryUnavailableReason = null;
    } else {
      retainPostCleanupRecovery(runner, cleanupFailure, unavailableReason);
    }
    // Populate and record the error before showing this modal dialog. setVisible(true) enters a
    // Swing secondary loop and does not return until the user closes it again.
    logLifecycleFailure("runner preparation", cleanupFailure);
    Throwable[] dialogRestoreFailure = new Throwable[1];
    runCleanupLifecycle(
        cleanupFailure,
        () -> {
          try {
            setVisible(true);
            return null;
          } catch (RuntimeException | Error failure) {
            dialogRestoreFailure[0] = failure;
            throw failure;
          }
        });
    logLifecycleFailure("runner preparation dialog restore", dialogRestoreFailure[0]);
  }

  private void completeCancelledRunnerPreparation(
      HumanSlAnalysisRunner runner, Throwable preparationFailure) {
    Throwable[] restoreFailure = new Throwable[1];
    Throwable cleanupFailure =
        runCleanupLifecycle(
            preparationFailure,
            () -> {
              try {
                restoreFailure[0] = restoreForegroundAnalysisBestEffort();
                return restoreFailure[0];
              } catch (RuntimeException | Error failure) {
                restoreFailure[0] = failure;
                throw failure;
              }
            },
            () -> {
              if (restoreFailure[0] != null) {
                return null;
              }
              HumanSlGameController failedController = pendingFailedHandoffController;
              if (failedController != null) {
                Throwable settingsFailure =
                    failedController.restoreFailedStartAnalysisSettingsBestEffort();
                if (settingsFailure != null) {
                  restoreFailure[0] = settingsFailure;
                  return settingsFailure;
                }
              }
              return null;
            },
            () -> {
              if (restoreFailure[0] == null) {
                try {
                  session.setState(HumanSlTrainingSession.State.IDLE);
                } catch (RuntimeException | Error failure) {
                  restoreFailure[0] = failure;
                  throw failure;
                }
              }
              return null;
            },
            () -> {
              try {
                finishEngineStartup(runner);
              } catch (RuntimeException | Error failure) {
                if (restoreFailure[0] == null) {
                  restoreFailure[0] = failure;
                }
                throw failure;
              }
              return null;
            });
    if (restoreFailure[0] == null) {
      pendingFailedHandoffController = null;
      postCleanupRecoveryPending = false;
      postCleanupRecoveryFailure = null;
      postCleanupRecoveryRunner = null;
      postCleanupRecoveryUnavailableReason = null;
    } else {
      retainPostCleanupRecovery(runner, cleanupFailure, null);
    }
    logLifecycleFailure("runner preparation cancellation", cleanupFailure);
    if (restoreFailure[0] == null) {
      Throwable[] hideFailure = new Throwable[1];
      runCleanupLifecycle(
          cleanupFailure,
          () -> {
            try {
              setVisible(false);
              return null;
            } catch (RuntimeException | Error failure) {
              hideFailure[0] = failure;
              throw failure;
            }
          });
      logLifecycleFailure("runner preparation cancellation dialog hide", hideFailure[0]);
      return;
    }

    Throwable restoreUiFailure =
        runCleanupLifecycle(
            cleanupFailure,
            () -> {
              setFormEnabled(false);
              return null;
            },
            () -> {
              showInlineError(foregroundRestoreFailureMessage(restoreFailure[0]));
              return null;
            });
    logLifecycleFailure("runner preparation cancellation UI", restoreUiFailure);
    Throwable[] dialogRestoreFailure = new Throwable[1];
    runCleanupLifecycle(
        restoreUiFailure,
        () -> {
          try {
            setVisible(true);
            return null;
          } catch (RuntimeException | Error failure) {
            dialogRestoreFailure[0] = failure;
            throw failure;
          }
        });
    logLifecycleFailure("runner preparation cancellation dialog restore", dialogRestoreFailure[0]);
  }

  private void presentPreparationFailure(
      HumanSlAnalysisRunner runner,
      Throwable preparationFailure,
      String unavailableReason,
      Throwable restoreFailure) {
    HumanSlTensorRtRepairView view =
        HumanSlTensorRtRepairView.fromPreparation(
            true, verifiedModel != null, runner == null ? null : runner.getTensorRtRepairContext());
    if (restoreFailure == null && view.offerRepair) {
      pendingTensorRtRepairContext = view.context;
      startButton.setText(view.repairActionLabel(key -> text(key, "Open Auto Setup repair")));
      startButton
          .getAccessibleContext()
          .setAccessibleName(
              text(
                  HumanSlTensorRtRepairView.REPAIR_ACTION_ACCESSIBLE_NAME_KEY,
                  "Open TensorRT repair"));
      startButton
          .getAccessibleContext()
          .setAccessibleDescription(
              text(
                  HumanSlTensorRtRepairView.REPAIR_ACTION_ACCESSIBLE_DESCRIPTION_KEY,
                  "Open KataGo Auto Setup and repair the TensorRT engine that failed to start."));
      showInlineError(view.inlineError(key -> text(key, "")));
      return;
    }
    pendingTensorRtRepairContext = null;
    if (restoreFailure == null) {
      restorePrimaryActionLabel();
    }
    String failureDetail = preparationFailureMessage(preparationFailure, unavailableReason);
    if (restoreFailure != null) {
      failureDetail = failureDetail + " | " + foregroundRestoreFailureMessage(restoreFailure);
    }
    showInlineError(
        MessageFormat.format(
            text("HumanSlGame.error.startFailed", "Failed to start HumanSL engine: {0}"),
            failureDetail));
  }

  private void openPendingTensorRtRepair() {
    TensorRtRepairContext context = pendingTensorRtRepairContext;
    pendingTensorRtRepairContext = null;
    restorePrimaryActionLabel();
    HumanSlTensorRtRepairView.fromPreparation(true, verifiedModel != null, context)
        .openDirectedRepair(this);
  }

  private String preparationFailureMessage(Throwable failure, String unavailableReason) {
    String detail = failure == null ? "" : failure.getLocalizedMessage();
    if (!Utils.isBlank(detail)) {
      return detail;
    }
    if (!Utils.isBlank(unavailableReason)) {
      return unavailableReason;
    }
    return text(
        "HumanSlTraining.preparing.failedDetail",
        "The engine did not return a readiness response.");
  }

  private static Throwable appendFailure(Throwable primary, Throwable added) {
    if (added == null) {
      return primary;
    }
    if (primary == null) {
      return added;
    }
    if (primary != added) {
      try {
        primary.addSuppressed(added);
      } catch (RuntimeException | Error ignored) {
        // Aggregation itself must never interrupt lifecycle cleanup.
      }
    }
    return primary;
  }

  private static void throwUnchecked(Throwable failure) {
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    throw (RuntimeException) failure;
  }

  private static void logLifecycleFailure(String operation, Throwable failure) {
    if (failure == null) {
      return;
    }
    try {
      LOG.error("HumanSL {} failed", operation, failure);
    } catch (RuntimeException | Error ignored) {
      // A logging backend failure must not create an uncaught EDT exception.
    }
  }

  private Throwable restoreForegroundAnalysisBestEffort() {
    Throwable restoreFailure =
        foregroundAnalysisPause.restoreBestEffort(FOREGROUND_RESTORE_ATTEMPTS);
    if (!foregroundAnalysisPause.isRestorePending()) {
      foregroundAnalysisPause = ForegroundAnalysisPause.inactive();
    }
    return restoreFailure;
  }

  private String foregroundRestoreFailureMessage(Throwable failure) {
    String detail = failure == null ? "" : failure.getLocalizedMessage();
    String message =
        text(
            "AnalysisEngine.foregroundRestoreFailed",
            "Failed to restore the foreground engine. Try again or restart it before continuing.");
    return Utils.isBlank(detail) ? message : message + " (" + detail + ")";
  }

  private void beginEngineStartup(HumanSlAnalysisRunner runner) {
    startupStartedNanos = System.nanoTime();
    startupStageText = text("HumanSlTraining.preparing", "Starting the AI coach...");
    downloadPanel.setVisible(true);
    pauseDownloadButton.setVisible(false);
    cancelDownloadButton.setVisible(false);
    downloadProgress.setIndeterminate(true);
    downloadProgress.setValue(0);
    downloadProgress.getAccessibleContext().setAccessibleName(startupStageText);
    updateEngineStartupText();
    runner.setStartupListener(
        (stage, detail) ->
            SwingUtilities.invokeLater(
                () -> {
                  if (preparingRunner == runner && !closeRequested) {
                    updateEngineStartupStage(stage);
                  }
                }));
    if (startupElapsedTimer != null) {
      startupElapsedTimer.stop();
    }
    startupElapsedTimer = new Timer(1000, event -> updateEngineStartupText());
    startupElapsedTimer.start();
    packForContent();
  }

  private void updateEngineStartupStage(HumanSlAnalysisRunner.StartupStage stage) {
    if (stage == null) {
      return;
    }
    switch (stage) {
      case LOADING_MODELS:
        startupStageText =
            text("HumanSlTraining.preparing.loading", "Loading the AI and HumanSL models...");
        break;
      case OPTIMIZING_GPU:
        startupStageText =
            text(
                "HumanSlTraining.preparing.optimizing",
                "Optimizing GPU kernels for this model; the first run may take longer...");
        break;
      case CACHE_READY:
        startupStageText =
            text(
                "HumanSlTraining.preparing.cacheReady",
                "GPU cache is ready; verifying the model...");
        break;
      case READY:
        startupStageText = text("HumanSlTraining.preparing.ready", "AI coach is ready.");
        break;
      case STARTING:
      default:
        startupStageText = text("HumanSlTraining.preparing", "Starting the AI coach...");
        break;
    }
    updateEngineStartupText();
  }

  private void updateEngineStartupText() {
    long elapsedSeconds =
        startupStartedNanos <= 0L
            ? 0L
            : Math.max(0L, (System.nanoTime() - startupStartedNanos) / 1_000_000_000L);
    setStatusText(startupStageText);
    downloadProgress.setString(
        MessageFormat.format(
            text("HumanSlTraining.preparing.elapsed", "Elapsed {0} s"), elapsedSeconds));
    downloadProgress.getAccessibleContext().setAccessibleName(startupStageText);
    downloadProgress.getAccessibleContext().setAccessibleDescription(startupStageText);
  }

  private void finishEngineStartup(HumanSlAnalysisRunner runner) {
    Throwable failure =
        runCleanupLifecycle(
            null,
            () -> {
              if (runner != null) {
                runner.setStartupListener(null);
              }
              return null;
            },
            () -> {
              Timer timer = startupElapsedTimer;
              startupElapsedTimer = null;
              if (timer != null) {
                timer.stop();
              }
              return null;
            },
            () -> {
              startupStartedNanos = 0L;
              startupStageText = "";
              return null;
            },
            () -> {
              downloadProgress.setIndeterminate(false);
              return null;
            },
            () -> {
              downloadPanel.setVisible(false);
              return null;
            },
            () -> {
              pauseDownloadButton.setVisible(true);
              return null;
            },
            () -> {
              cancelDownloadButton.setVisible(true);
              return null;
            },
            () -> {
              packForContent();
              return null;
            });
    if (failure instanceof RuntimeException) {
      throw (RuntimeException) failure;
    }
    if (failure instanceof Error) {
      // In the success handoff this is immediately captured by runHandoffLifecycle; in cleanup it
      // is captured by runCleanupLifecycle. It can therefore never escape the EDT completion.
      throw (Error) failure;
    }
  }

  private HumanSlTrainingConfig selectedConfig() {
    return form.config();
  }

  private void refreshModelStatus() {
    Path model = verifiedModel;
    boolean installed = model != null;
    String size = "";
    if (installed) {
      try {
        size = " · " + formatMegabytes(java.nio.file.Files.size(model)).replace(".0 MB", " MB");
      } catch (IOException | RuntimeException ignored) {
        size = "";
      }
    }
    setModelStatusText(
        text(
                installed ? "HumanSlTraining.model.ready" : "HumanSlTraining.model.missing",
                installed ? "Human-style model ready" : "The human-style model is not downloaded")
            + size);
    modelStatusLabel.setIcon(
        new CoachSetupWidgets.Dot(
            installed
                ? CoachSetupWidgets.Palette.current().ready()
                : HumanSlTrainingStyle.WARNING));
    restorePrimaryActionLabel();
  }

  private void restorePrimaryActionLabel() {
    boolean installed = verifiedModel != null;
    startButton.setText(
        text(
            installed ? "HumanSlTraining.start" : "HumanSlTraining.downloadAndStart",
            installed ? "Start the game" : "Download and start"));
    startButton.revalidate();
  }

  private void setFormEnabled(boolean enabled) {
    form.setEnabled(enabled);
    startButton.setEnabled(enabled && modelChecked);
  }

  private void showInlineError(String message) {
    setStatusText(message == null ? "" : message);
    statusLabel.setForeground(HumanSlTrainingStyle.WARNING);
  }

  private void setModelStatusText(String value) {
    modelStatusLabel.setText(value);
    modelStatusLabel.setFont(CoachSetupWidgets.font(value, Font.PLAIN, 13f));
  }

  /** Errors and progress under the sheet; an empty line takes no room. */
  private void setStatusText(String value) {
    statusLabel.setForeground(CoachSetupWidgets.Palette.current().muted());
    statusLabel.setText(value);
    statusLabel.setFont(CoachSetupWidgets.font(value, Font.PLAIN, 13f));
    statusLabel.setVisible(value != null && !value.isEmpty());
  }

  private AnalysisEngineCommandHelper.Result resolveAnalysisCommand() {
    String command = Lizzie.config == null ? "" : Lizzie.config.analysisEngineCommand;
    return AnalysisEngineCommandHelper.resolveHumanSlCommand(command);
  }

  private void closeDialog() {
    closeRequested = true;
    cancelled = true;
    Throwable cancellationFailure =
        runCleanupLifecycle(
            null,
            () -> {
              if (downloadSession != null) {
                downloadSession.cancel();
              }
              return null;
            });
    if (cleanupRetryRunner != null) {
      cleanupRetryFailure = appendFailure(cleanupRetryFailure, cancellationFailure);
      retryRunnerCleanup();
      return;
    }
    if (postCleanupRecoveryPending) {
      postCleanupRecoveryFailure = appendFailure(postCleanupRecoveryFailure, cancellationFailure);
      retryPostCleanupRecovery();
      return;
    }
    HumanSlAnalysisRunner preparing = preparingRunner;
    if (preparing != null) {
      // Claim cleanup before the readiness worker can post its own completion. The foreground
      // restore remains owned by the dialog until the companion has actually been stopped.
      if (!runnerCleanupClaim.claim()) {
        return;
      }
      preparingRunner = null;
      cancellationFailure =
          runCleanupLifecycle(
              cancellationFailure,
              () -> {
                setFormEnabled(false);
                return null;
              });
      scheduleRunnerCleanup(preparing, cancellationFailure, null);
      return;
    }
    if (runnerCleanupInFlight) {
      logLifecycleFailure("runner preparation cancellation request", cancellationFailure);
      return;
    }
    completeCancelledRunnerPreparation(null, cancellationFailure);
  }

  private void installBehavior() {
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            closeDialog();
          }
        });
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
    getRootPane()
        .getActionMap()
        .put(
            "close",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent event) {
                closeDialog();
              }
            });
    getRootPane().setDefaultButton(startButton);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowOpened(WindowEvent event) {
            startButton.requestFocusInWindow();
          }
        });
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public void selectFromCurrentPosition() {
    form.selectFromCurrentPosition();
  }

  private String text(String key, String fallback) {
    try {
      return resources.getString(key);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static String formatMegabytes(long bytes) {
    return String.format(java.util.Locale.US, "%.1f MB", Math.max(0L, bytes) / 1024.0 / 1024.0);
  }
}
