package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.Dimension;
import java.awt.Window;
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

  private final TeacherSettings settings = TeacherSettings.createDefault();
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
    if (isDisplayable() && explainNext.isEnabled()) {
      explainNext.requestFocusInWindow();
    }
  }

  private TeacherDialog(Window owner) {
    super(owner, TeacherStrings.get("Teacher.title", "AI commentary"), ModalityType.MODELESS);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setContentPane(view);
    bindActions();
    setMinimumSize(new Dimension(760, 540));
    setSize(new Dimension(900, 680));
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
                stopRequest();
                uiGeneration++;
                lastEvidenceContext = List.of();
                lastEvidencePositions = List.of();
                requestPositions = List.of();
                requestTarget = null;
                pendingText.clear();
                refreshFromBoard();
                updateControlState();
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
    explainNext.addActionListener(
        event -> {
          view.selectMode(TeacherDialogView.Mode.NEXT);
          explainNextMove();
        });
    explainRange.addActionListener(
        event -> {
          view.selectMode(TeacherDialogView.Mode.RANGE);
          explainRange();
        });
    explainWhole.addActionListener(
        event -> {
          view.selectMode(TeacherDialogView.Mode.WHOLE);
          explainWholeGame();
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
    displayedGame = currentGame();
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      if (!requests.isRunning()) {
        clearOutputForEmptyState();
      }
      view.setCurrentMove(0);
      setStatus(
          TeacherStrings.get("Teacher.status.noGame", "No game is loaded."),
          TeacherDialogView.StatusTone.WARNING);
      return;
    }
    BoardHistoryNode current = Lizzie.board.getHistory().getCurrentHistoryNode();
    int currentMove = current.getData() == null ? 0 : current.getData().moveNumber;
    view.setCurrentMove(currentMove);
    int lastMove = Math.max(1, Lizzie.board.getHistory().getStart().getLast().getData().moveNumber);
    rangeStart.setModel(new SpinnerNumberModel(1, 1, lastMove, 1));
    rangeEnd.setModel(new SpinnerNumberModel(lastMove, 1, lastMove, 1));
    TeacherDialogStyle.styleSpinner(rangeStart);
    TeacherDialogStyle.styleSpinner(rangeEnd);
    Optional<String> saved = TeacherCommentCodec.extract(current.getData().comment);
    if (!requests.isRunning() && saved.isPresent()) {
      lastEvidenceContext = List.of();
      lastEvidencePositions = List.of();
      rawOutput.setLength(0);
      rawOutput.append(saved.get());
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
      boolean hasEvidence = TeacherEvidence.current(current).isPresent();
      setStatus(
          evidenceStatus(current),
          hasEvidence
              ? TeacherDialogView.StatusTone.NEUTRAL
              : TeacherDialogView.StatusTone.WARNING);
    }
  }

  private void clearOutputForEmptyState() {
    rawOutput.setLength(0);
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
    lastEvidenceContext =
        TeacherPromptBuilder.forPosition(
            position.get(), TeacherStrings.locale(), settings.snapshot());
    lastEvidencePositions = List.of(position.get());
    startRequest(lastEvidenceContext, current);
  }

  private void explainRange() {
    BoardHistoryNode root = rootNode();
    if (root == null) {
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
    lastEvidenceContext =
        TeacherPromptBuilder.forRange(
            evidence,
            TeacherPromptBuilder.Mode.RANGE,
            TeacherStrings.locale(),
            settings.snapshot());
    lastEvidencePositions = evidence.positions;
    startRequest(
        lastEvidenceContext,
        currentNode(),
        TeacherStrings.format(
            "Teacher.status.evidenceReady",
            "{0} key positions selected ({1} analyzed, {2} omitted). Generating commentary...",
            evidence.positions.size(),
            evidence.analyzedPositions,
            evidence.omittedPositions));
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
    lastEvidenceContext =
        TeacherPromptBuilder.forRange(
            evidence,
            TeacherPromptBuilder.Mode.WHOLE_GAME,
            TeacherStrings.locale(),
            settings.snapshot());
    lastEvidencePositions = evidence.positions;
    startRequest(
        lastEvidenceContext,
        root,
        TeacherStrings.format(
            "Teacher.status.evidenceReady",
            "{0} key positions selected ({1} analyzed, {2} omitted). Generating commentary...",
            evidence.positions.size(),
            evidence.analyzedPositions,
            evidence.omittedPositions));
  }

  private void askFollowUp() {
    if (displayedGame != currentGame()) {
      stopRequest();
      lastEvidenceContext = List.of();
      lastEvidencePositions = List.of();
      refreshFromBoard();
    }
    String question = followUp.getText().trim();
    if (question.isEmpty()) {
      return;
    }
    if (lastEvidenceContext.isEmpty()) {
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
      lastEvidenceContext =
          TeacherPromptBuilder.forPosition(
              position.get(), TeacherStrings.locale(), settings.snapshot());
      lastEvidencePositions = List.of(position.get());
    }
    startRequest(
        TeacherPromptBuilder.forFollowUp(
            lastEvidenceContext,
            rawOutput.toString(),
            question,
            TeacherStrings.locale(),
            settings.snapshot()),
        currentNode());
    followUp.setText("");
  }

  private void startRequest(List<TeacherLlmClient.Message> messages, BoardHistoryNode targetNode) {
    startRequest(
        messages,
        targetNode,
        TeacherStrings.get("Teacher.status.requesting", "Generating commentary..."));
  }

  private void startRequest(
      List<TeacherLlmClient.Message> messages, BoardHistoryNode targetNode, String runningStatus) {
    Object evidenceGame = currentGame();
    messages = appendKnowledge(messages, targetNode);
    CommentaryClient client = configuredClient();
    if (client == null) {
      return;
    }
    if (evidenceGame != currentGame()) {
      refreshFromBoard();
      return;
    }
    TeacherSettings.Snapshot snapshot = settings.snapshot();
    requestModel =
        snapshot.provider == TeacherSettings.Provider.CHATGPT
            ? settings.chatGptAccount().model
            : snapshot.model;
    requestGame = currentGame();
    long currentGeneration = ++uiGeneration;
    requestTarget = targetNode;
    requestPositions = List.copyOf(lastEvidencePositions);
    pendingText.clear();
    rawOutput.setLength(0);
    output.setText("<html><body></body></html>");
    view.showLoading(runningStatus);
    setRunning(true);
    setStatus(runningStatus, TeacherDialogView.StatusTone.RUNNING);
    requests.start(
        client,
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
  }

  /** 把知识库匹配结果（定式/棋形）拼到最后一条 user 消息；无匹配不改动。 */
  private static List<TeacherLlmClient.Message> appendKnowledge(
      List<TeacherLlmClient.Message> messages, BoardHistoryNode node) {
    if (messages == null || messages.isEmpty()) {
      return messages;
    }
    String knowledge = TeacherEvidence.knowledgeMatchText(node);
    if (knowledge.isEmpty()) {
      return messages;
    }
    java.util.ArrayList<TeacherLlmClient.Message> out = new java.util.ArrayList<>(messages);
    int last = out.size() - 1;
    TeacherLlmClient.Message message = out.get(last);
    if ("user".equals(message.role)) {
      out.set(
          last,
          new TeacherLlmClient.Message(
              message.role, message.content + "\n\n【Knowledge】\n" + knowledge));
    }
    return out;
  }

  private CommentaryClient configuredClient() {
    try {
      TeacherSettings.Snapshot snapshot = settings.load();
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
    return Lizzie.board == null || Lizzie.board.getHistory() == null
        ? null
        : Lizzie.board.getHistory().getStart();
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
    output.setText(markdownToHtml(result));
    output.setCaretPosition(0);
    view.showOutput();
    appendVerifierNotes(result);
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
      appendQualityGateNotes(result, notes);
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

  /** 重型校验链：构建 MoveAnalysis → TeachingEvidence → QualityGate（结构化/claim 级核对）。 */
  private void appendQualityGateNotes(String result, java.util.ArrayList<String> notes) {
    if (requestTarget == null
        || requestTarget.getData() == null
        || requestPositions.size() != 1
        || requestPositions.get(0).moveNumber != requestTarget.getData().moveNumber) {
      return;
    }
    try {
      MoveAnalysis analysis = TeacherEvidence.moveAnalysis(requestTarget);
      TeachingEvidenceBuilder.TeachingEvidence evidence =
          TeachingEvidenceBuilder.buildTeachingEvidence(
              analysis, "", java.util.List.of(), java.util.List.of(), java.util.List.of());
      featurecat.lizzie.teacher.analysis.QualityGate.TeacherQualityGateResult gate =
          featurecat.lizzie.teacher.analysis.QualityGate.runTeacherQualityGate(
              result, evidence, false);
      notes.addAll(gate.violations);
      notes.addAll(gate.warnings);
    } catch (Exception ignored) {
      // 重型校验失败不阻断解说显示
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
    settingsButton.setEnabled(settingsLoaded && !requestRunning);
    ask.setEnabled(ready);
    followUp.setEnabled(ready);
    rangeStart.setEnabled(ready);
    rangeEnd.setEnabled(ready);
    stop.setEnabled(requestRunning);
  }

  private BoardHistoryNode currentNode() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      setStatus(TeacherStrings.get("Teacher.status.noGame", "No game is loaded."));
      return null;
    }
    return Lizzie.board.getHistory().getCurrentHistoryNode();
  }

  private BoardHistoryNode rootNode() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      setStatus(TeacherStrings.get("Teacher.status.noGame", "No game is loaded."));
      return null;
    }
    return Lizzie.board.getHistory().getStart();
  }

  private String evidenceStatus(BoardHistoryNode node) {
    Optional<TeacherEvidence.Position> position = TeacherEvidence.current(node);
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
