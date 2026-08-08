package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.rules.BoardHistoryNode;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.accessibility.AccessibleContext;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Non-modal AI commentary window backed only by existing KataGo analysis evidence. */
public final class TeacherDialog extends JDialog {
  private static TeacherDialog activeDialog;

  private final TeacherSettings settings = TeacherSettings.createDefault();
  private final TeacherRequestController requests = new TeacherRequestController();
  private final ConcurrentLinkedQueue<String> pendingText = new ConcurrentLinkedQueue<>();
  private final Timer textFlushTimer;

  private final JTextArea output = new JTextArea();
  private final JLabel status = new JLabel(" ");
  private final JLabel modelStatus = new JLabel(" ", SwingConstants.RIGHT);
  private final JButton explainNext =
      new JButton(TeacherStrings.get("Teacher.action.next", "Explain next move"));
  private final JButton explainRange =
      new JButton(TeacherStrings.get("Teacher.action.range", "Explain range"));
  private final JButton explainWhole =
      new JButton(TeacherStrings.get("Teacher.action.whole", "Explain whole game"));
  private final JButton stop = new JButton(TeacherStrings.get("Teacher.action.stop", "Stop"));
  private final JButton settingsButton =
      new JButton(TeacherStrings.get("Teacher.action.settings", "Settings"));
  private final JButton ask = new JButton(TeacherStrings.get("Teacher.action.ask", "Ask"));
  private final JCheckBox writeToSgf =
      new JCheckBox(
          TeacherStrings.get("Teacher.writeToSgf", "Write result to the SGF comment"), true);
  private final JTextField followUp = new JTextField();
  private final JSpinner rangeStart = new JSpinner();
  private final JSpinner rangeEnd = new JSpinner();

  private BoardHistoryNode requestTarget;
  private List<TeacherLlmClient.Message> lastEvidenceContext = List.of();
  private String requestModel = "";
  private boolean requestRunning;
  private boolean settingsLoaded;
  private boolean settingsUsable;

  public static void show(Window owner) {
    if (activeDialog != null && activeDialog.isDisplayable()) {
      activeDialog.refreshFromBoard();
      activeDialog.setVisible(true);
      activeDialog.toFront();
      activeDialog.requestFocus();
      return;
    }
    activeDialog = new TeacherDialog(owner);
    activeDialog.setVisible(true);
  }

  private TeacherDialog(Window owner) {
    super(owner, TeacherStrings.get("Teacher.title", "AI commentary"), ModalityType.MODELESS);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setContentPane(buildContent());
    setMinimumSize(new Dimension(760, 540));
    setSize(new Dimension(900, 680));
    setLocationRelativeTo(owner);
    getRootPane()
        .registerKeyboardAction(
            event -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);

    textFlushTimer = new Timer(60, event -> flushPendingText());
    textFlushTimer.setRepeats(true);
    textFlushTimer.start();

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            requests.close();
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

  private JPanel buildContent() {
    JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));

    JLabel title = new JLabel(TeacherStrings.get("Teacher.title", "AI commentary"));
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 7f));
    JLabel subtitle =
        new JLabel(
            TeacherStrings.get(
                "Teacher.subtitle",
                "Uses existing KataGo analysis; missing evidence is never invented."));
    subtitle.setForeground(mutedText());
    JPanel headingText = new JPanel(new GridBagLayout());
    GridBagConstraints headingConstraints = new GridBagConstraints();
    headingConstraints.gridx = 0;
    headingConstraints.gridy = 0;
    headingConstraints.weightx = 1.0;
    headingConstraints.anchor = GridBagConstraints.WEST;
    headingConstraints.fill = GridBagConstraints.HORIZONTAL;
    headingText.add(title, headingConstraints);
    headingConstraints.gridy = 1;
    headingConstraints.insets = new Insets(4, 0, 0, 0);
    headingText.add(subtitle, headingConstraints);

    JPanel heading = new JPanel(new BorderLayout(12, 0));
    heading.add(headingText, BorderLayout.CENTER);
    heading.add(settingsButton, BorderLayout.EAST);

    JPanel rangePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    JLabel from = new JLabel(TeacherStrings.get("Teacher.range.from", "From"));
    JLabel to = new JLabel(TeacherStrings.get("Teacher.range.to", "to"));
    from.setLabelFor(rangeStart);
    to.setLabelFor(rangeEnd);
    rangePanel.add(from);
    rangePanel.add(rangeStart);
    rangePanel.add(to);
    rangePanel.add(rangeEnd);

    JPanel actions = new JPanel(new BorderLayout(10, 0));
    JPanel primaryActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    primaryActions.add(explainNext);
    primaryActions.add(explainRange);
    primaryActions.add(explainWhole);
    primaryActions.add(stop);
    actions.add(primaryActions, BorderLayout.WEST);
    actions.add(rangePanel, BorderLayout.EAST);

    JPanel header = new JPanel(new BorderLayout(0, 14));
    header.add(heading, BorderLayout.NORTH);
    header.add(actions, BorderLayout.SOUTH);
    content.add(header, BorderLayout.NORTH);

    output.setEditable(false);
    output.setLineWrap(true);
    output.setWrapStyleWord(true);
    output.setMargin(new Insets(14, 14, 14, 14));
    output
        .getAccessibleContext()
        .setAccessibleName(TeacherStrings.get("Teacher.output", "AI commentary result"));
    JScrollPane outputScroll = new JScrollPane(output);
    outputScroll.setBorder(BorderFactory.createLineBorder(borderColor()));
    content.add(outputScroll, BorderLayout.CENTER);

    JPanel statusRow = new JPanel(new BorderLayout(12, 0));
    status.setForeground(mutedText());
    modelStatus.setForeground(mutedText());
    status
        .getAccessibleContext()
        .setAccessibleName(TeacherStrings.get("Teacher.status.accessible", "Commentary status"));
    modelStatus
        .getAccessibleContext()
        .setAccessibleName(TeacherStrings.get("Teacher.model.accessible", "Selected AI model"));
    statusRow.add(status, BorderLayout.CENTER);
    statusRow.add(modelStatus, BorderLayout.EAST);

    JLabel followUpLabel = new JLabel(TeacherStrings.get("Teacher.followUp", "Follow-up question"));
    followUpLabel.setLabelFor(followUp);
    JPanel followUpRow = new JPanel(new BorderLayout(8, 0));
    followUpRow.add(followUpLabel, BorderLayout.WEST);
    followUpRow.add(followUp, BorderLayout.CENTER);
    followUpRow.add(ask, BorderLayout.EAST);

    JPanel footer = new JPanel(new BorderLayout(0, 8));
    footer.add(statusRow, BorderLayout.NORTH);
    footer.add(followUpRow, BorderLayout.CENTER);
    footer.add(writeToSgf, BorderLayout.SOUTH);
    content.add(footer, BorderLayout.SOUTH);

    explainNext.addActionListener(event -> explainNextMove());
    explainRange.addActionListener(event -> explainRange());
    explainWhole.addActionListener(event -> explainWholeGame());
    stop.addActionListener(event -> stopRequest());
    settingsButton.addActionListener(
        event -> {
          if (TeacherSettingsDialog.show(this, settings)) {
            refreshSettingsStatus();
          }
        });
    ask.addActionListener(event -> askFollowUp());
    followUp.addActionListener(event -> askFollowUp());

    explainNext
        .getAccessibleContext()
        .setAccessibleDescription(
            TeacherStrings.get(
                "Teacher.action.next.description",
                "Compare the recorded next move with KataGo's top candidates."));
    stop.getAccessibleContext()
        .setAccessibleDescription(
            TeacherStrings.get(
                "Teacher.action.stop.description", "Cancel the active network request."));
    return content;
  }

  private void refreshFromBoard() {
    if (Lizzie.board == null || Lizzie.board.getHistory() == null) {
      setStatus(TeacherStrings.get("Teacher.status.noGame", "No game is loaded."));
      return;
    }
    BoardHistoryNode current = Lizzie.board.getHistory().getCurrentHistoryNode();
    int lastMove = Math.max(1, Lizzie.board.getHistory().getStart().getLast().getData().moveNumber);
    rangeStart.setModel(new SpinnerNumberModel(1, 1, lastMove, 1));
    rangeEnd.setModel(new SpinnerNumberModel(lastMove, 1, lastMove, 1));
    Optional<String> saved = TeacherCommentCodec.extract(current.getData().comment);
    if (!requests.isRunning() && saved.isPresent()) {
      lastEvidenceContext = List.of();
      output.setText(saved.get());
      output.setCaretPosition(0);
      setStatus(
          TeacherStrings.get(
              "Teacher.status.savedLoaded", "Loaded saved commentary from this SGF node."));
    } else if (!requests.isRunning()) {
      lastEvidenceContext = List.of();
      setStatus(evidenceStatus(current));
    }
  }

  private void refreshSettingsStatus() {
    settingsLoaded = false;
    settingsUsable = false;
    updateControlState();
    modelStatus.setText(
        TeacherStrings.get("Teacher.status.loadingSettings", "Loading secure settings..."));
    new SwingWorker<TeacherSettings.Snapshot, Void>() {
      @Override
      protected TeacherSettings.Snapshot doInBackground() throws Exception {
        return settings.load();
      }

      @Override
      protected void done() {
        settingsLoaded = true;
        try {
          TeacherSettings.Snapshot snapshot = get();
          settingsUsable = true;
          modelStatus.setText(
              snapshot.hasApiKey
                  ? TeacherStrings.format("Teacher.status.modelReady", "Model: {0}", snapshot.model)
                  : TeacherStrings.get(
                      "Teacher.status.needsKey", "Configure an API key before use"));
        } catch (Exception error) {
          settingsUsable = false;
          modelStatus.setText(localError(error));
        }
        updateControlState();
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
              "This position has no KataGo candidates yet. Analyze it first."));
      return;
    }
    lastEvidenceContext =
        TeacherPromptBuilder.forPosition(
            position.get(), TeacherStrings.locale(), settings.snapshot());
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
              "No analyzed positions were found in this range."));
      return;
    }
    lastEvidenceContext =
        TeacherPromptBuilder.forRange(
            evidence,
            TeacherPromptBuilder.Mode.RANGE,
            TeacherStrings.locale(),
            settings.snapshot());
    startRequest(lastEvidenceContext, currentNode());
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
              "No analyzed positions were found in this game."));
      return;
    }
    lastEvidenceContext =
        TeacherPromptBuilder.forRange(
            evidence,
            TeacherPromptBuilder.Mode.WHOLE_GAME,
            TeacherStrings.locale(),
            settings.snapshot());
    startRequest(lastEvidenceContext, root);
  }

  private void askFollowUp() {
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
                "This position has no KataGo candidates yet. Analyze it first."));
        return;
      }
      lastEvidenceContext =
          TeacherPromptBuilder.forPosition(
              position.get(), TeacherStrings.locale(), settings.snapshot());
    }
    startRequest(
        TeacherPromptBuilder.forFollowUp(
            lastEvidenceContext,
            output.getText(),
            question,
            TeacherStrings.locale(),
            settings.snapshot()),
        currentNode());
    followUp.setText("");
  }

  private void startRequest(List<TeacherLlmClient.Message> messages, BoardHistoryNode targetNode) {
    messages = appendKnowledge(messages, targetNode);
    TeacherLlmClient client = configuredClient();
    if (client == null) {
      return;
    }
    TeacherSettings.Snapshot snapshot = settings.snapshot();
    requestModel = snapshot.model;
    requestTarget = targetNode;
    pendingText.clear();
    output.setText("");
    setRunning(true);
    setStatus(TeacherStrings.get("Teacher.status.requesting", "Generating commentary..."));
    requests.start(
        client,
        messages,
        new TeacherRequestController.Listener() {
          @Override
          public void onText(String text) {
            pendingText.add(text);
          }

          @Override
          public void onComplete(String fullText) {
            SwingUtilities.invokeLater(() -> completeRequest(fullText));
          }

          @Override
          public void onFailure(Throwable error) {
            SwingUtilities.invokeLater(() -> failRequest(error));
          }

          @Override
          public void onCancelled() {
            SwingUtilities.invokeLater(() -> cancelledRequest());
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
    java.util.ArrayList<TeacherLlmClient.Message> out =
        new java.util.ArrayList<>(messages);
    int last = out.size() - 1;
    TeacherLlmClient.Message message = out.get(last);
    if ("user".equals(message.role)) {
      out.set(
          last,
          new TeacherLlmClient.Message(
              message.role,
              message.content + "\n\n【Knowledge】\n" + knowledge));
    }
    return out;
  }

  private TeacherLlmClient configuredClient() {
    try {
      TeacherSettings.Snapshot snapshot = settings.load();
      if (!snapshot.hasApiKey) {
        if (!TeacherSettingsDialog.show(this, settings)) {
          return null;
        }
        snapshot = settings.snapshot();
      }
      Optional<String> apiKey = settings.apiKey();
      if (apiKey.isEmpty()) {
        setStatus(TeacherStrings.get("Teacher.status.needsKey", "Configure an API key before use"));
        return null;
      }
      return new TeacherLlmClient(snapshot.baseUrl, apiKey.get(), snapshot.model);
    } catch (Exception error) {
      setStatus(localError(error));
      return null;
    }
  }

  private void completeRequest(String fullText) {
    flushPendingText();
    String result = fullText == null ? "" : fullText.trim();
    if (result.isEmpty()) {
      failRequest(new IllegalStateException("AI service returned an empty response."));
      return;
    }
    output.setText(result);
    output.setCaretPosition(0);
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
              "Commentary completed and added to the SGF comment."));
    } else {
      setStatus(TeacherStrings.get("Teacher.status.completed", "Commentary completed."));
    }
    setRunning(false);
  }

  /** 防编造校验：轻量 TeacherVerifier + 重型 QualityGate（claim 级核对），附到输出末尾（不阻断显示）。 */
  private void appendVerifierNotes(String result) {
    try {
      java.util.Optional<TeacherEvidence.Position> position =
          requestTarget == null
              ? java.util.Optional.empty()
              : TeacherEvidence.current(requestTarget);
      TeacherVerifier.Result verification =
          TeacherVerifier.verify(result, position.orElse(null));
      java.util.ArrayList<String> notes =
          new java.util.ArrayList<>(verification.violations);
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
              .append(
                  TeacherStrings.get(
                      "Teacher.verify.note", "Verifier notes"))
              .append(": ")
              .append(String.join("; ", shown));
      output.append(builder.toString());
    } catch (Exception ignored) {
      // 校验失败不阻断解说显示
    }
  }

  /** 重型校验链：构建 MoveAnalysis → TeachingEvidence → QualityGate（结构化/claim 级核对）。 */
  private void appendQualityGateNotes(String result, java.util.ArrayList<String> notes) {
    if (requestTarget == null) {
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
      if (gate.note != null && !gate.note.isBlank()) {
        notes.add(gate.note);
      }
    } catch (Exception ignored) {
      // 重型校验失败不阻断解说显示
    }
  }

  private void failRequest(Throwable error) {
    flushPendingText();
    setStatus(
        TeacherStrings.format(
            "Teacher.status.failed", "Commentary failed: {0}", localError(error)));
    setRunning(false);
  }

  private void cancelledRequest() {
    flushPendingText();
    setStatus(TeacherStrings.get("Teacher.status.cancelled", "Commentary stopped."));
    setRunning(false);
  }

  private void stopRequest() {
    if (!requests.isRunning()) {
      return;
    }
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
      output.append(addition.toString());
      output.setCaretPosition(output.getDocument().getLength());
    }
  }

  private void setRunning(boolean running) {
    requestRunning = running;
    updateControlState();
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
    String previous = status.getText();
    String next = message == null || message.isBlank() ? " " : message;
    status.setText(next);
    status.setToolTipText(status.getText());
    status
        .getAccessibleContext()
        .firePropertyChange(AccessibleContext.ACCESSIBLE_DESCRIPTION_PROPERTY, previous, next);
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

  private static Color mutedText() {
    Color color = UIManager.getColor("Label.disabledForeground");
    return color == null ? Color.GRAY : color;
  }

  private static Color borderColor() {
    Color color = UIManager.getColor("Separator.foreground");
    return color == null ? new Color(190, 190, 190) : color;
  }
}
