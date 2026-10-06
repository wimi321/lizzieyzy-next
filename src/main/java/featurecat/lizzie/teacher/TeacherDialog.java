package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** Non-modal AI commentary window backed only by existing KataGo analysis evidence. */
public final class TeacherDialog extends JDialog {
  private static TeacherDialog activeDialog;

  private final TeacherSettings settings;
  private final java.util.function.Supplier<BoardHistoryNode> currentNodeProvider;
  private final java.util.function.Supplier<BoardHistoryNode> rootNodeProvider;
  private final TeacherRequestController requests = new TeacherRequestController();
  private final ConcurrentLinkedQueue<String> pendingText = new ConcurrentLinkedQueue<>();
  private final Timer textFlushTimer;
  private final Timer gameGuardTimer;
  private Object requestGame;
  private Object displayedGame;
  private long uiGeneration;

  private final TeacherDialogView view = new TeacherDialogView();
  private final JEditorPane output = view.output();
  private final StringBuilder rawOutput = new StringBuilder();
  private final JToggleButton explainNext = view.explainNext();
  private final JToggleButton explainRange = view.explainRange();
  private final JToggleButton explainWhole = view.explainWhole();
  private final JButton stop = view.stop();
  private final JButton start = view.start();
  private final JButton settingsButton = view.settingsButton();
  private final JButton ask = view.ask();
  private final JCheckBox writeToSgf = view.writeToSgf();
  private final JTextField followUp = view.followUp();
  private final JSpinner rangeStart = view.rangeStart();
  private final JSpinner rangeEnd = view.rangeEnd();

  private BoardHistoryNode requestTarget;
  private List<TeacherLlmClient.Message> lastEvidenceContext = List.of();
  private List<TeacherEvidence.Position> lastEvidencePositions = List.of();
  private List<TeacherEvidence.Position> requestPositions = List.of();
  private String requestModel = "";
  private boolean requestRunning;
  private boolean settingsLoaded;
  private boolean settingsUsable;
  private String requestQuestion = "";
  private String lastCompletedOutput = "";

  public static void show(Window owner) {
    if (activeDialog != null && activeDialog.isDisplayable()) {
      activeDialog.refreshFromBoard();
      activeDialog.setVisible(true);
      activeDialog.toFront();
      SwingUtilities.invokeLater(activeDialog::focusPrimaryControl);
      return;
    }
    activeDialog = new TeacherDialog(owner);
    activeDialog.setVisible(true);
    SwingUtilities.invokeLater(activeDialog::focusPrimaryControl);
  }

  private void focusPrimaryControl() {
    if (isDisplayable() && start.isEnabled()) {
      start.requestFocusInWindow();
    }
  }

  private TeacherDialog(Window owner) {
    this(
        owner,
        TeacherSettings.createDefault(),
        () ->
            Lizzie.board == null || Lizzie.board.getHistory() == null
                ? null
                : Lizzie.board.getHistory().getCurrentHistoryNode(),
        () ->
            Lizzie.board == null || Lizzie.board.getHistory() == null
                ? null
                : Lizzie.board.getHistory().getStart());
  }

  TeacherDialog(
      Window owner,
      TeacherSettings settings,
      java.util.function.Supplier<BoardHistoryNode> currentNodeProvider,
      java.util.function.Supplier<BoardHistoryNode> rootNodeProvider) {
    super(owner, TeacherStrings.get("Teacher.title", "AI commentary"), ModalityType.MODELESS);
    this.settings = settings;
    this.currentNodeProvider = currentNodeProvider;
    this.rootNodeProvider = rootNodeProvider;
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setContentPane(view);
    bindActions();
    setMinimumSize(new Dimension(760, 540));
    setSize(new Dimension(900, 680));
    java.awt.Rectangle screen =
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    setMinimumSize(new Dimension(Math.min(760, screen.width), Math.min(540, screen.height)));
    setSize(Math.min(900, screen.width - 24), Math.min(680, screen.height - 24));
    setLocationRelativeTo(owner);
    getRootPane()
        .registerKeyboardAction(
            event -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

    textFlushTimer = new Timer(140, event -> flushPendingText());
    textFlushTimer.setRepeats(false);
    gameGuardTimer =
        new Timer(
            200,
            event -> {
              if (displayedGame != currentGame()) {
                requestQuestion = "";
                stopRequest();
                uiGeneration++;
                lastEvidenceContext = List.of();
                lastEvidencePositions = List.of();
                requestPositions = List.of();
                view.setEvidence(List.of());
                requestTarget = null;
                pendingText.clear();
                followUp.setText("");
                refreshFromBoard();
                updateControlState();
              } else if (!requestRunning && rawOutput.isEmpty()) {
                BoardHistoryNode node = currentNodeProvider.get();
                if (node != null) view.setCurrentMove(node.getData().moveNumber);
              }
            });
    gameGuardTimer.start();

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            requests.close();
            uiGeneration++;
            gameGuardTimer.stop();
            textFlushTimer.stop();
            if (activeDialog == TeacherDialog.this) {
              activeDialog = null;
            }
          }
        });
    refreshFromBoard();
    setRunning(false);
    refreshSettingsStatus();
  }

  private void bindActions() {
    view.manageChatGptUsage()
        .addActionListener(
            event -> {
              new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                  ChatGptSettingsPanel.browse(
                      java.net.URI.create("https://chatgpt.com/settings/usage"));
                  return null;
                }

                @Override
                protected void done() {
                  try {
                    get();
                  } catch (Exception failed) {
                    setStatus(
                        ChatGptSettingsPanel.text(
                            "error.browser", "Open ChatGPT usage settings in your browser."),
                        TeacherDialogView.StatusTone.WARNING);
                  }
                }
              }.execute();
            });
    // Keep the effective mode in sync with keyboard selection as well as mouse clicks.
    explainNext.addItemListener(
        event -> {
          if (event.getStateChange() == ItemEvent.SELECTED) {
            view.selectMode(TeacherDialogView.Mode.NEXT);
          }
        });
    explainRange.addItemListener(
        event -> {
          if (event.getStateChange() == ItemEvent.SELECTED) {
            view.selectMode(TeacherDialogView.Mode.RANGE);
          }
        });
    explainWhole.addItemListener(
        event -> {
          if (event.getStateChange() == ItemEvent.SELECTED) {
            view.selectMode(TeacherDialogView.Mode.WHOLE);
          }
        });
    start.addActionListener(
        event -> {
          if (requestRunning || !settingsLoaded) return;
          if (!settingsUsable || !connectionReady()) {
            TeacherSettingsDialog.show(this, settings);
            refreshSettingsStatus();
            return;
          }
          switch (view.mode()) {
            case RANGE:
              explainRange();
              break;
            case WHOLE:
              explainWholeGame();
              break;
            default:
              explainNextMove();
              break;
          }
        });
    stop.addActionListener(event -> stopRequest());
    settingsButton.addActionListener(
        event -> {
          stopRequest();
          TeacherSettingsDialog.show(this, settings);
          refreshSettingsStatus();
        });
    ask.addActionListener(event -> askFollowUp());
    followUp.addActionListener(event -> askFollowUp());
  }

  private void refreshFromBoard() {
    if (requestRunning && requestGame != currentGame()) stopRequest();
    if (displayedGame == currentGame() && (requestRunning || !rawOutput.isEmpty())) return;
    displayedGame = currentGame();
    if (currentNodeProvider.get() == null || rootNodeProvider.get() == null) {
      if (!requests.isRunning()) {
        clearOutputForEmptyState();
      }
      view.setCurrentMove(-1);
      setStatus(
          TeacherStrings.get("Teacher.status.noGame", "No game is loaded."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    BoardHistoryNode current = currentNodeProvider.get();
    int currentMove = current.getData() == null ? 0 : current.getData().moveNumber;
    view.setCurrentMove(currentMove);
    int lastMove = Math.max(1, rootNodeProvider.get().getLast().getData().moveNumber);
    rangeStart.setModel(new SpinnerNumberModel(1, 1, lastMove, 1));
    rangeEnd.setModel(new SpinnerNumberModel(lastMove, 1, lastMove, 1));
    TeacherDialogStyle.styleSpinner(rangeStart);
    TeacherDialogStyle.styleSpinner(rangeEnd);
    Optional<String> saved = TeacherCommentCodec.extract(current.getData().comment);
    if (!requests.isRunning() && saved.isPresent()) {
      requestTarget = current;
      view.setCommentaryMove(currentMove);
      lastEvidenceContext = List.of();
      lastEvidencePositions = List.of();
      rawOutput.setLength(0);
      rawOutput.append(saved.get());
      lastCompletedOutput = saved.get();
      output.setText(markdownToHtml(rawOutput.toString()));
      output.setCaretPosition(0);
      view.showOutput();
      setStatus(
          TeacherStrings.get(
              "Teacher.status.savedLoaded", "Loaded saved commentary from this SGF node."),
          TeacherDialogView.StatusTone.SUCCESS);
    } else if (!requests.isRunning()) {
      lastEvidenceContext = List.of();
      lastEvidencePositions = List.of();
      clearOutputForEmptyState();
      boolean hasEvidence = TeacherEvidence.position(current).isPresent();
      setStatus(
          evidenceStatus(current),
          hasEvidence
              ? TeacherDialogView.StatusTone.NEUTRAL
              : TeacherDialogView.StatusTone.WARNING);
    }
  }

  private void clearOutputForEmptyState() {
    rawOutput.setLength(0);
    lastCompletedOutput = "";
    output.setText("<html><body></body></html>");
    view.resetEmptyTitle();
    view.showEmpty();
  }

  private void refreshSettingsStatus() {
    settingsLoaded = false;
    settingsUsable = false;
    updateControlState();
    view.setModelStatus(
        TeacherStrings.get("Teacher.status.loadingSettings", "Loading secure settings..."));
    new SwingWorker<TeacherSettings.Snapshot, Void>() {
      @Override
      protected TeacherSettings.Snapshot doInBackground() throws Exception {
        TeacherSettings.Snapshot snapshot = settings.load();
        if (snapshot.provider == TeacherSettings.Provider.CHATGPT) settings.refreshChatGptAccount();
        return snapshot;
      }

      @Override
      protected void done() {
        if (!isDisplayable()) return;
        settingsLoaded = true;
        try {
          TeacherSettings.Snapshot snapshot = get();
          settingsUsable = true;
          view.setChatGptUsageVisible(
              snapshot.provider == TeacherSettings.Provider.CHATGPT
                  && settings.chatGptAccount() != null
                  && settings.chatGptAccount().signedIn
                  && settings.chatGptAccount().authorized);
          view.setModelStatus(
              snapshot.provider == TeacherSettings.Provider.CHATGPT
                  ? chatGptStatus()
                  : snapshot.provider == TeacherSettings.Provider.UNSELECTED
                      ? ChatGptSettingsPanel.text(
                          "choose", "Choose a connection method and finish setup.")
                      : snapshot.hasApiKey
                          ? TeacherStrings.format(
                              "Teacher.status.modelReady", "Model: {0}", snapshot.model)
                          : TeacherStrings.get(
                              "Teacher.status.needsKey", "Configure an API key before use"));
        } catch (Exception error) {
          settingsUsable = false;
          view.setModelStatus(localError(error));
        }
        updateControlState();
        if (rawOutput.isEmpty()) {
          if (!connectionReady()) {
            view.setEmptyDetail(
                TeacherStrings.get(
                    "Teacher.empty.connect",
                    "Click Connect AI to choose ChatGPT or API Key. Then start commentary."));
          } else {
            BoardHistoryNode node = currentNodeProvider.get();
            if (node != null)
              setStatus(
                  evidenceStatus(node),
                  TeacherEvidence.position(node).isPresent()
                      ? TeacherDialogView.StatusTone.NEUTRAL
                      : TeacherDialogView.StatusTone.WARNING);
          }
        }
        SwingUtilities.invokeLater(TeacherDialog.this::focusPrimaryControl);
      }
    }.execute();
  }

  private void explainNextMove() {
    BoardHistoryNode current = currentNode();
    if (current == null) {
      return;
    }
    Optional<TeacherEvidence.Position> position = TeacherEvidence.current(current);
    if (position.isEmpty()) {
      setStatus(
          TeacherStrings.get(
              "Teacher.status.needsAnalysis",
              "This position has no KataGo candidates yet. Analyze it first."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    List<TeacherLlmClient.Message> context =
        TeacherPromptBuilder.forPosition(
            position.get(), TeacherStrings.locale(), settings.snapshot());
    startRequest(context, current, requestingStatus(), context, List.of(position.get()), "");
  }

  private void explainRange() {
    BoardHistoryNode root = rootNode();
    if (root == null) {
      return;
    }
    try {
      rangeStart.commitEdit();
      rangeEnd.commitEdit();
    } catch (java.text.ParseException invalid) {
      setStatus(
          TeacherStrings.get(
              "Teacher.status.invalidRange", "Enter valid move numbers before starting."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    int first = ((Number) rangeStart.getValue()).intValue();
    int last = ((Number) rangeEnd.getValue()).intValue();
    if (first > last) {
      int temporary = first;
      first = last;
      last = temporary;
    }
    TeacherEvidence.Range evidence = TeacherEvidence.mainLine(root, first, last);
    if (evidence.isEmpty()) {
      setStatus(
          TeacherStrings.get(
              "Teacher.status.rangeNeedsAnalysis",
              "No analyzed positions were found in this range."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    List<TeacherLlmClient.Message> context =
        TeacherPromptBuilder.forRange(
            evidence,
            TeacherPromptBuilder.Mode.RANGE,
            TeacherStrings.locale(),
            settings.snapshot());
    startRequest(
        context,
        currentNode(),
        TeacherStrings.format(
            "Teacher.status.evidenceReady",
            "{0} key positions selected ({1} analyzed, {2} omitted). Generating commentary...",
            evidence.positions.size(),
            evidence.analyzedPositions,
            evidence.omittedPositions),
        context,
        evidence.positions,
        "");
  }

  private void explainWholeGame() {
    BoardHistoryNode root = rootNode();
    if (root == null) {
      return;
    }
    TeacherEvidence.Range evidence = TeacherEvidence.wholeGame(root);
    if (evidence.isEmpty()) {
      setStatus(
          TeacherStrings.get(
              "Teacher.status.rangeNeedsAnalysis",
              "No analyzed positions were found in this game."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    List<TeacherLlmClient.Message> context =
        TeacherPromptBuilder.forRange(
            evidence,
            TeacherPromptBuilder.Mode.WHOLE_GAME,
            TeacherStrings.locale(),
            settings.snapshot());
    startRequest(
        context,
        root,
        TeacherStrings.format(
            "Teacher.status.evidenceReady",
            "{0} key positions selected ({1} analyzed, {2} omitted). Generating commentary...",
            evidence.positions.size(),
            evidence.analyzedPositions,
            evidence.omittedPositions),
        context,
        evidence.positions,
        "");
  }

  private void askFollowUp() {
    if (requestRunning || !settingsLoaded || !settingsUsable) return;
    if (displayedGame != currentGame()) {
      stopRequest();
      lastEvidenceContext = List.of();
      lastEvidencePositions = List.of();
      followUp.setText("");
      refreshFromBoard();
      return;
    }
    String question = followUp.getText().trim();
    if (question.isEmpty()) {
      return;
    }
    List<TeacherLlmClient.Message> context = lastEvidenceContext;
    List<TeacherEvidence.Position> positions = lastEvidencePositions;
    BoardHistoryNode target = requestTarget;
    if (context.isEmpty()) {
      BoardHistoryNode current =
          target != null && !lastCompletedOutput.isBlank() ? target : currentNode();
      if (current == null) {
        return;
      }
      Optional<TeacherEvidence.Position> position = TeacherEvidence.current(current);
      if (position.isEmpty()) {
        setStatus(
            TeacherStrings.get(
                "Teacher.status.needsAnalysis",
                "This position has no KataGo candidates yet. Analyze it first."),
            TeacherDialogView.StatusTone.WARNING);
        return;
      }
      context =
          TeacherPromptBuilder.forPosition(
              position.get(), TeacherStrings.locale(), settings.snapshot());
      positions = List.of(position.get());
      target = current;
    }
    if (startRequest(
        TeacherPromptBuilder.forFollowUp(
            context, lastCompletedOutput, question, TeacherStrings.locale(), settings.snapshot()),
        target,
        requestingStatus(),
        context,
        positions,
        question)) {
      followUp.setText("");
    }
  }

  private String requestingStatus() {
    return TeacherStrings.get("Teacher.status.requesting", "Generating commentary...");
  }

  private boolean startRequest(
      List<TeacherLlmClient.Message> messages,
      BoardHistoryNode targetNode,
      String runningStatus,
      List<TeacherLlmClient.Message> context,
      List<TeacherEvidence.Position> positions,
      String question) {
    if (requestRunning) return false;
    Object evidenceGame = currentGame();
    CommentaryClient client = configuredClient();
    if (client == null) {
      return false;
    }
    if (evidenceGame != currentGame()) {
      refreshFromBoard();
      return false;
    }
    TeacherSettings.Snapshot snapshot = settings.snapshot();
    requestModel =
        snapshot.provider == TeacherSettings.Provider.CHATGPT
            ? settings.chatGptAccount().model
            : snapshot.model;
    requestGame = currentGame();
    long currentGeneration = ++uiGeneration;
    requestTarget = targetNode;
    lastEvidenceContext = List.copyOf(context);
    lastEvidencePositions = List.copyOf(positions);
    requestPositions = lastEvidencePositions;
    view.setEvidence(requestPositions);
    requestQuestion = question;
    if (question.isEmpty() || lastCompletedOutput.isEmpty()) {
      if (question.isEmpty()) lastCompletedOutput = "";
      if (question.isEmpty() && view.mode() == TeacherDialogView.Mode.WHOLE) {
        view.setCommentaryScope(
            TeacherStrings.get("Teacher.position.whole", "Explaining the whole game"));
      } else if (question.isEmpty() && view.mode() == TeacherDialogView.Mode.RANGE) {
        int first = ((Number) rangeStart.getValue()).intValue();
        int last = ((Number) rangeEnd.getValue()).intValue();
        view.setCommentaryScope(
            TeacherStrings.format(
                "Teacher.position.range",
                "Explaining moves {0} to {1}",
                Math.min(first, last),
                Math.max(first, last)));
      } else if (targetNode != null) view.setCommentaryMove(targetNode.getData().moveNumber);
    }
    pendingText.clear();
    rawOutput.setLength(0);
    output.setText("<html><body></body></html>");
    view.showLoading(runningStatus);
    setRunning(true);
    setStatus(runningStatus, TeacherDialogView.StatusTone.RUNNING);
    requests.start(
        new GroundedCommentaryClient(client, positions),
        messages,
        new TeacherRequestController.Listener() {
          @Override
          public void onText(String text) {
            SwingUtilities.invokeLater(
                () -> {
                  if (acceptCallback(currentGeneration)) queuePendingText(text);
                });
          }

          @Override
          public void onComplete(String fullText) {
            SwingUtilities.invokeLater(
                () -> {
                  if (acceptCallback(currentGeneration)) completeRequest(fullText);
                });
          }

          @Override
          public void onFailure(Throwable error) {
            SwingUtilities.invokeLater(
                () -> {
                  if (acceptCallback(currentGeneration)) failRequest(error);
                });
          }

          @Override
          public void onCancelled() {
            SwingUtilities.invokeLater(
                () -> {
                  if (acceptCallback(currentGeneration)) cancelledRequest();
                });
          }
        });
    return true;
  }

  private CommentaryClient configuredClient() {
    try {
      TeacherSettings.Snapshot snapshot = settings.snapshot();
      if (snapshot.provider == TeacherSettings.Provider.UNSELECTED
          || (snapshot.provider == TeacherSettings.Provider.API_KEY && !snapshot.hasApiKey)
          || (snapshot.provider == TeacherSettings.Provider.CHATGPT
              && (settings.chatGptAccount() == null
                  || !settings.chatGptAccount().signedIn
                  || !settings.chatGptAccount().authorized
                  || settings.chatGptAccount().model.isBlank()))) {
        if (!TeacherSettingsDialog.show(this, settings)) {
          return null;
        }
        snapshot = settings.snapshot();
      }
      view.setChatGptUsageVisible(snapshot.provider == TeacherSettings.Provider.CHATGPT);
      if (snapshot.provider == TeacherSettings.Provider.CHATGPT) {
        ChatGptSessions.Account account = settings.chatGptAccount();
        if (account == null || !account.signedIn || !account.authorized || account.model.isBlank())
          return null;
        view.setModelStatus(chatGptStatus());
        return new ChatGptCommentaryClient(settings.chatGpt(), account.id, account.model);
      }
      Optional<String> apiKey = settings.apiKey();
      if (apiKey.isEmpty()) {
        setStatus(
            TeacherStrings.get("Teacher.status.needsKey", "Configure an API key before use"),
            TeacherDialogView.StatusTone.WARNING);
        return null;
      }
      return new TeacherLlmClient(snapshot.baseUrl, apiKey.get(), snapshot.model);
    } catch (Exception error) {
      setStatus(localError(error));
      return null;
    }
  }

  private String chatGptStatus() {
    ChatGptSessions.Account account = settings.chatGptAccount();
    return account != null && account.signedIn && account.authorized
        ? ChatGptSettingsPanel.text("usingPlan", "Using ChatGPT plan") + " · " + account.model
        : ChatGptSettingsPanel.text("notConnected", "Connect ChatGPT to use your plan.");
  }

  private Object currentGame() {
    return rootNodeProvider.get();
  }

  private boolean acceptCallback(long generation) {
    return isDisplayable() && uiGeneration == generation && requestGame == currentGame();
  }

  private void completeRequest(String fullText) {
    flushPendingText();
    String result = fullText == null ? "" : fullText.trim();
    if (result.isEmpty()) {
      failRequest(new IllegalStateException("AI service returned an empty response."));
      return;
    }
    rawOutput.setLength(0);
    rawOutput.append(result);
    lastCompletedOutput = result;
    output.setText(markdownToHtml(result));
    output.setCaretPosition(0);
    view.showOutput();
    appendVerifierNotes(result);
    requestQuestion = "";
    if (writeToSgf.isSelected() && requestTarget != null && requestTarget.getData() != null) {
      requestTarget.getData().comment =
          TeacherCommentCodec.upsert(requestTarget.getData().comment, result, requestModel);
      if (Lizzie.frame != null) {
        Lizzie.frame.refresh();
      }
      setStatus(
          TeacherStrings.get(
              "Teacher.status.completedSaved",
              "Commentary completed and added to the SGF comment."),
          TeacherDialogView.StatusTone.SUCCESS);
    } else {
      setStatus(
          TeacherStrings.get("Teacher.status.completed", "Commentary completed."),
          TeacherDialogView.StatusTone.SUCCESS);
    }
    setRunning(false);
  }

  /** 防编造校验：轻量 TeacherVerifier + 重型 QualityGate（claim 级核对），附到输出末尾（不阻断显示）。 */
  private void appendVerifierNotes(String result) {
    try {
      TeacherVerifier.Result verification = TeacherVerifier.verify(result, requestPositions);
      java.util.ArrayList<String> notes = new java.util.ArrayList<>(verification.violations);
      notes.addAll(verification.warnings);
      // The old quality gate rebuilt mutable live evidence after generation and omitted PVs.
      // Verify against the same frozen positions that were sent, including board-group references.
      if (notes.isEmpty()) {
        return;
      }
      java.util.ArrayList<String> shown = new java.util.ArrayList<>();
      for (String note : notes) {
        shown.add(note);
        if (shown.size() >= 4) {
          break;
        }
      }
      StringBuilder builder =
          new StringBuilder("\n\n> ")
              .append(TeacherStrings.get("Teacher.verify.note", "Verifier notes"))
              .append(": ")
              .append(String.join("; ", shown));
      rawOutput.append(builder);
      output.setText(markdownToHtml(rawOutput.toString()));
    } catch (Exception ignored) {
      // 校验失败不阻断解说显示
    }
  }

  private void failRequest(Throwable error) {
    flushPendingText();
    if (rawOutput.length() == 0) {
      view.resetEmptyTitle();
      view.showEmpty();
    }
    setStatus(
        TeacherStrings.format("Teacher.status.failed", "Commentary failed: {0}", localError(error)),
        TeacherDialogView.StatusTone.ERROR);
    setRunning(false);
    restoreQuestion();
  }

  private void cancelledRequest() {
    flushPendingText();
    if (rawOutput.length() == 0) {
      view.resetEmptyTitle();
      view.showEmpty();
    }
    setStatus(
        TeacherStrings.get("Teacher.status.cancelled", "Commentary stopped."),
        TeacherDialogView.StatusTone.WARNING);
    setRunning(false);
    restoreQuestion();
  }

  private void restoreQuestion() {
    if (requestGame == currentGame() && followUp.getText().isBlank())
      followUp.setText(requestQuestion);
    requestQuestion = "";
  }

  private void stopRequest() {
    if (!requestRunning) {
      return;
    }
    uiGeneration++;
    requests.cancel();
    cancelledRequest();
  }

  private void flushPendingText() {
    StringBuilder addition = new StringBuilder();
    String text;
    while ((text = pendingText.poll()) != null) {
      addition.append(text);
    }
    if (addition.length() > 0) {
      rawOutput.append(addition);
      output.setText(markdownToHtml(rawOutput.toString()));
      output.setCaretPosition(output.getDocument().getLength());
      view.showOutput();
    }
  }

  private void setRunning(boolean running) {
    requestRunning = running;
    view.setRunning(running);
    if (!running) {
      textFlushTimer.stop();
    }
    updateControlState();
  }

  private void queuePendingText(String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    pendingText.add(text);
    SwingUtilities.invokeLater(
        () -> {
          if (isDisplayable() && requestRunning && !textFlushTimer.isRunning()) {
            textFlushTimer.start();
          }
        });
  }

  private void updateControlState() {
    boolean ready = settingsLoaded && settingsUsable && !requestRunning;
    explainNext.setEnabled(ready);
    explainRange.setEnabled(ready);
    explainWhole.setEnabled(ready);
    start.setEnabled(settingsLoaded && !requestRunning);
    start.setText(
        !requestRunning && (!settingsUsable || !connectionReady())
            ? TeacherStrings.get("Teacher.action.connect", "Connect AI")
            : TeacherStrings.get("Teacher.action.start", "Start commentary"));
    start.getAccessibleContext().setAccessibleDescription(start.getText());
    settingsButton.setEnabled(settingsLoaded && !requestRunning);
    ask.setEnabled(ready);
    followUp.setEnabled(ready);
    rangeStart.setEnabled(ready);
    rangeEnd.setEnabled(ready);
    stop.setEnabled(requestRunning);
  }

  private BoardHistoryNode currentNode() {
    BoardHistoryNode node = currentNodeProvider.get();
    if (node == null) {
      setStatus(TeacherStrings.get("Teacher.status.noGame", "No game is loaded."));
      return null;
    }
    return node;
  }

  private boolean connectionReady() {
    if (!settingsLoaded || !settingsUsable) return false;
    TeacherSettings.Snapshot snapshot = settings.snapshot();
    if (snapshot.provider == TeacherSettings.Provider.API_KEY)
      return snapshot.hasApiKey && !snapshot.model.isBlank() && !snapshot.baseUrl.isBlank();
    ChatGptSessions.Account account = settings.chatGptAccount();
    return snapshot.provider == TeacherSettings.Provider.CHATGPT
        && account != null
        && account.signedIn
        && account.authorized
        && !account.model.isBlank()
        && !account.credentialsUnavailable;
  }

  private BoardHistoryNode rootNode() {
    BoardHistoryNode root = rootNodeProvider.get();
    if (root == null) {
      setStatus(TeacherStrings.get("Teacher.status.noGame", "No game is loaded."));
      return null;
    }
    return root;
  }

  private String evidenceStatus(BoardHistoryNode node) {
    Optional<TeacherEvidence.Position> position = TeacherEvidence.position(node);
    if (position.isEmpty()) {
      return TeacherStrings.get(
          "Teacher.status.needsAnalysis",
          "This position has no KataGo candidates yet. Analyze it first.");
    }
    return TeacherStrings.format(
        "Teacher.status.ready",
        "Ready: move {0}, {1} KataGo candidates.",
        position.get().moveNumber,
        position.get().candidates.size());
  }

  private void setStatus(String message) {
    setStatus(message, TeacherDialogView.StatusTone.NEUTRAL);
  }

  private void setStatus(String message, TeacherDialogView.StatusTone tone) {
    view.setStatus(message, tone);
  }

  private static String localError(Throwable error) {
    Throwable cause = error;
    while (cause != null && cause.getCause() != null) {
      cause = cause.getCause();
    }
    String message = cause == null ? "" : cause.getMessage();
    return message == null || message.isBlank()
        ? TeacherStrings.get("Teacher.error.generic", "The operation failed.")
        : message;
  }

  static String markdownToHtml(String markdown) {
    return SafeMarkdownRenderer.toHtml(markdown);
  }
}
