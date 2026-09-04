package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.WholeGameAnalysisOptions;
import featurecat.lizzie.analysis.WholeGameAnalysisSession;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.MessageFormat;
import java.text.ParseException;
import java.util.ResourceBundle;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicButtonUI;

/** Modeless progress surface for whole-game analysis; the board remains fully interactive. */
public final class WholeGameAnalysisDialog extends JDialog
    implements WholeGameAnalysisSession.Listener {
  private static final long serialVersionUID = 1L;
  private static final Color BACKGROUND = new Color(247, 244, 236);
  private static final Color CARD = new Color(255, 253, 248);
  private static final Color BORDER = new Color(218, 208, 188);
  private static final Color TEXT = new Color(39, 47, 43);
  private static final Color MUTED = new Color(103, 107, 99);
  private static final Color ACCENT = new Color(20, 111, 91);
  private static final Color ACCENT_SOFT = new Color(225, 240, 234);
  private static final Color DISABLED_SURFACE = new Color(232, 229, 220);
  private static final int SCREEN_MARGIN = 36;

  private final ResourceBundle resources = Lizzie.resourceBundle;
  private final LizzieFrame ownerFrame;
  private final JLabel phaseLabel = new JLabel();
  private final JLabel progressLabel = new JLabel();
  private final JLabel modeLabel = new JLabel();
  private final JLabel remainingLabel = new JLabel();
  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JButton startButton = new JButton();
  private final JButton pauseButton = new JButton();
  private final JButton stopButton = new JButton();
  private final JComboBox<PresetChoice> visitsPreset = new JComboBox<>();
  private final JSpinner customVisits =
      new JSpinner(
          new SpinnerNumberModel(
              WholeGameAnalysisOptions.DEFAULT_VISITS,
              WholeGameAnalysisOptions.MINIMUM_VISITS,
              WholeGameAnalysisOptions.MAXIMUM_VISITS,
              100));
  private final JTextArea visitsHint = textArea();
  private WholeGameAnalysisSession session;
  private WholeGameAnalysisSession.State latestState = WholeGameAnalysisSession.State.IDLE;
  private int lastCustomVisits = WholeGameAnalysisOptions.DEFAULT_VISITS;
  private boolean adjustingVisits;

  public WholeGameAnalysisDialog(LizzieFrame owner) {
    super(owner, Lizzie.resourceBundle.getString("WholeGameAnalysis.title"), false);
    ownerFrame = owner;
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    setType(Window.Type.UTILITY);
    setResizable(true);
    setContentPane(buildContent());
    initializeVisitsSelection();
    pack();
    fitToUsableScreen();
    setLocationRelativeTo(owner);
    installWindowBehavior();
  }

  public void setSession(WholeGameAnalysisSession session) {
    this.session = session;
    setVisitControlsEnabled(session == null);
  }

  public void showOnScreen() {
    if (!isVisible()) {
      setVisible(true);
    }
    SwingUtilities.invokeLater(
        () -> {
          toFront();
          if (latestState == WholeGameAnalysisSession.State.IDLE) {
            startButton.requestFocusInWindow();
          }
        });
  }

  @Override
  public void onSnapshot(WholeGameAnalysisSession.Snapshot snapshot) {
    latestState = snapshot.state;
    phaseLabel.setText(resources.getString(snapshot.detailKey));
    progressBar.setValue(snapshot.overallPercent);
    progressBar.setString(snapshot.overallPercent + "%");
    progressLabel.setText(
        snapshot.state == WholeGameAnalysisSession.State.COMPLETE
            ? resources.getString("WholeGameAnalysis.resultsShown")
            : MessageFormat.format(
                resources.getString("WholeGameAnalysis.progress"),
                snapshot.completedPositions,
                snapshot.totalPositions,
                snapshot.targetVisits));
    modeLabel.setText(
        snapshot.state == WholeGameAnalysisSession.State.IDLE
                || snapshot.state == WholeGameAnalysisSession.State.PREPARING
            ? ""
            : resources.getString(
                snapshot.remoteBackend
                    ? "WholeGameAnalysis.mode.remote"
                    : "WholeGameAnalysis.mode.local"));
    remainingLabel.setText(remainingText(snapshot));
    applyControlState(controlState(snapshot.state));
    progressBar
        .getAccessibleContext()
        .setAccessibleDescription(phaseLabel.getText() + ". " + progressLabel.getText());
  }

  private JPanel buildContent() {
    JPanel root = new JPanel(new BorderLayout(0, 16));
    root.setBackground(BACKGROUND);
    root.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));

    JPanel body = new JPanel(new BorderLayout(0, 18));
    body.setBackground(BACKGROUND);
    body.add(buildHeader(), BorderLayout.NORTH);
    body.add(buildProgressCard(), BorderLayout.CENTER);

    JScrollPane scrollPane =
        new JScrollPane(
            body, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scrollPane.setName("wholeGameScrollPane");
    scrollPane.setBorder(null);
    scrollPane.setOpaque(false);
    scrollPane.getViewport().setOpaque(false);
    scrollPane.getVerticalScrollBar().setUnitIncrement(18);
    root.add(scrollPane, BorderLayout.CENTER);
    root.add(buildActions(), BorderLayout.SOUTH);
    return root;
  }

  private JComponent buildHeader() {
    JPanel header = new JPanel(new BorderLayout(14, 4));
    header.setOpaque(false);
    JLabel icon = new JLabel(new GoIcon(46));
    icon.setPreferredSize(new Dimension(46, 46));
    header.add(icon, BorderLayout.WEST);

    JPanel copy = new JPanel();
    copy.setOpaque(false);
    copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
    JLabel title = new JLabel(resources.getString("WholeGameAnalysis.title"));
    title.setForeground(TEXT);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    JTextArea description = textArea();
    description.setText(resources.getString("WholeGameAnalysis.description"));
    description.setRows(2);
    description.setColumns(47);
    description.setForeground(MUTED);
    description.setFont(title.getFont().deriveFont(Font.PLAIN, 13f));
    description.setAlignmentX(Component.LEFT_ALIGNMENT);
    copy.add(title);
    copy.add(Box.createVerticalStrut(5));
    copy.add(description);
    header.add(copy, BorderLayout.CENTER);
    return header;
  }

  private JComponent buildProgressCard() {
    JPanel card = new JPanel(new GridBagLayout());
    card.setName("wholeGameProgressCard");
    card.setBackground(CARD);
    card.setBorder(
        BorderFactory.createCompoundBorder(
            new RoundedBorder(BORDER, 16), BorderFactory.createEmptyBorder(20, 22, 18, 22)));

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.anchor = GridBagConstraints.WEST;

    card.add(buildVisitControls(), constraints);

    constraints.gridy++;
    constraints.insets = new Insets(18, 0, 0, 0);

    phaseLabel.setName("wholeGamePhase");
    phaseLabel.setForeground(TEXT);
    phaseLabel.setFont(phaseLabel.getFont().deriveFont(Font.BOLD, 18f));
    phaseLabel.setText(resources.getString("WholeGameAnalysis.ready"));
    card.add(phaseLabel, constraints);

    constraints.gridy++;
    constraints.insets = new Insets(14, 0, 0, 0);
    progressBar.setName("wholeGameProgressBar");
    progressBar.setValue(0);
    progressBar.setStringPainted(true);
    progressBar.setForeground(ACCENT);
    progressBar.setBackground(ACCENT_SOFT);
    progressBar.setBorderPainted(false);
    progressBar.setPreferredSize(new Dimension(520, 24));
    progressBar
        .getAccessibleContext()
        .setAccessibleName(resources.getString("WholeGameAnalysis.title"));
    card.add(progressBar, constraints);

    constraints.gridy++;
    constraints.insets = new Insets(12, 0, 0, 0);
    progressLabel.setName("wholeGameProgressText");
    progressLabel.setForeground(TEXT);
    progressLabel.setFont(progressLabel.getFont().deriveFont(13f));
    progressLabel.setText(
        MessageFormat.format(resources.getString("WholeGameAnalysis.progress"), 0, 0, 0));
    card.add(progressLabel, constraints);

    constraints.gridy++;
    constraints.insets = new Insets(10, 0, 0, 0);
    card.add(buildMetadataRow(), constraints);
    return card;
  }

  private JComponent buildVisitControls() {
    JPanel controls = new JPanel(new GridBagLayout());
    controls.setName("wholeGameVisitControls");
    controls.setOpaque(false);

    JLabel label = new JLabel(resources.getString("WholeGameAnalysis.visits.label"));
    label.setLabelFor(visitsPreset);
    label.setForeground(TEXT);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));

    GridBagConstraints labelConstraints = new GridBagConstraints();
    labelConstraints.gridx = 0;
    labelConstraints.gridy = 0;
    labelConstraints.anchor = GridBagConstraints.WEST;
    controls.add(label, labelConstraints);

    for (WholeGameAnalysisOptions.Preset preset : WholeGameAnalysisOptions.Preset.values()) {
      visitsPreset.addItem(new PresetChoice(preset, resources.getString(preset.resourceKey())));
    }
    visitsPreset.setName("wholeGameVisitsPreset");
    visitsPreset.setPreferredSize(new Dimension(230, 34));
    visitsPreset
        .getAccessibleContext()
        .setAccessibleName(resources.getString("WholeGameAnalysis.visits.label"));
    visitsPreset.addActionListener(event -> applySelectedPreset());

    GridBagConstraints presetConstraints = new GridBagConstraints();
    presetConstraints.gridx = 1;
    presetConstraints.gridy = 0;
    presetConstraints.weightx = 1;
    presetConstraints.fill = GridBagConstraints.HORIZONTAL;
    presetConstraints.insets = new Insets(0, 16, 0, 0);
    controls.add(visitsPreset, presetConstraints);

    customVisits.setName("wholeGameCustomVisits");
    customVisits.setPreferredSize(new Dimension(132, 34));
    customVisits
        .getAccessibleContext()
        .setAccessibleName(resources.getString("WholeGameAnalysis.visits.customValue"));
    JSpinner.NumberEditor editor = new JSpinner.NumberEditor(customVisits, "#");
    customVisits.setEditor(editor);
    customVisits.addChangeListener(
        event -> {
          if (selectedPreset() == WholeGameAnalysisOptions.Preset.CUSTOM) {
            lastCustomVisits = ((Number) customVisits.getValue()).intValue();
            updateVisitHint();
          }
        });

    GridBagConstraints customConstraints = new GridBagConstraints();
    customConstraints.gridx = 2;
    customConstraints.gridy = 0;
    customConstraints.insets = new Insets(0, 10, 0, 0);
    controls.add(customVisits, customConstraints);

    visitsHint.setName("wholeGameVisitsHint");
    visitsHint.setRows(2);
    visitsHint.setColumns(48);
    visitsHint.setForeground(MUTED);
    visitsHint.setFont(visitsHint.getFont().deriveFont(12f));
    visitsHint
        .getAccessibleContext()
        .setAccessibleName(resources.getString("WholeGameAnalysis.visits.label"));

    GridBagConstraints hintConstraints = new GridBagConstraints();
    hintConstraints.gridx = 0;
    hintConstraints.gridy = 1;
    hintConstraints.gridwidth = 3;
    hintConstraints.weightx = 1;
    hintConstraints.fill = GridBagConstraints.HORIZONTAL;
    hintConstraints.anchor = GridBagConstraints.WEST;
    hintConstraints.insets = new Insets(7, 0, 0, 0);
    controls.add(visitsHint, hintConstraints);
    return controls;
  }

  private void initializeVisitsSelection() {
    int configured =
        Lizzie.config == null
            ? WholeGameAnalysisOptions.DEFAULT_VISITS
            : Lizzie.config.wholeGameAnalysisDeepVisits;
    setSelectedVisits(configured);
  }

  private void applySelectedPreset() {
    if (adjustingVisits) {
      return;
    }
    WholeGameAnalysisOptions.Preset preset = selectedPreset();
    boolean custom = preset == WholeGameAnalysisOptions.Preset.CUSTOM;
    if (custom) {
      customVisits.setValue(lastCustomVisits);
    } else if (preset != null) {
      customVisits.setValue(preset.visits());
    }
    customVisits.setVisible(custom);
    customVisits.setEnabled(
        custom && latestState == WholeGameAnalysisSession.State.IDLE && session == null);
    updateVisitHint();
    revalidate();
    repaint();
  }

  private WholeGameAnalysisOptions.Preset selectedPreset() {
    Object selected = visitsPreset.getSelectedItem();
    return selected instanceof PresetChoice ? ((PresetChoice) selected).preset : null;
  }

  WholeGameAnalysisOptions selectedOptions() {
    WholeGameAnalysisOptions.Preset preset = selectedPreset();
    int visits =
        preset == null || preset == WholeGameAnalysisOptions.Preset.CUSTOM
            ? ((Number) customVisits.getValue()).intValue()
            : preset.visits();
    return WholeGameAnalysisOptions.of(visits);
  }

  WholeGameAnalysisOptions commitSelectedOptions() {
    if (selectedPreset() == WholeGameAnalysisOptions.Preset.CUSTOM) {
      try {
        customVisits.commitEdit();
      } catch (ParseException exception) {
        return null;
      }
    }
    WholeGameAnalysisOptions options = selectedOptions();
    if (options.isValid()) {
      lastCustomVisits = options.deepVisits();
    }
    return options;
  }

  void setSelectedVisits(int visits) {
    WholeGameAnalysisOptions options = WholeGameAnalysisOptions.fromStored(visits);
    adjustingVisits = true;
    try {
      lastCustomVisits = options.deepVisits();
      selectPresetItem(options.preset());
      customVisits.setValue(options.deepVisits());
      customVisits.setVisible(options.preset() == WholeGameAnalysisOptions.Preset.CUSTOM);
      customVisits.setEnabled(
          options.preset() == WholeGameAnalysisOptions.Preset.CUSTOM
              && latestState == WholeGameAnalysisSession.State.IDLE
              && session == null);
    } finally {
      adjustingVisits = false;
    }
    updateVisitHint();
  }

  private void selectPresetItem(WholeGameAnalysisOptions.Preset preset) {
    for (int index = 0; index < visitsPreset.getItemCount(); index++) {
      PresetChoice choice = visitsPreset.getItemAt(index);
      if (choice.preset == preset) {
        visitsPreset.setSelectedIndex(index);
        return;
      }
    }
  }

  private void updateVisitHint() {
    WholeGameAnalysisOptions options = selectedOptions();
    visitsHint.setForeground(options.isValid() ? MUTED : new Color(173, 52, 49));
    visitsHint.setText(
        resources.getString(
            options.isValid()
                ? "WholeGameAnalysis.visits.hint"
                : "WholeGameAnalysis.visits.invalid"));
    if (options.isValid() && ownerFrame != null) {
      ownerFrame.requestWholeGameAnalysisEstimate(this, options);
    }
  }

  void showPreStartEstimate(WholeGameAnalysisOptions options, long estimatedMillis) {
    if (latestState != WholeGameAnalysisSession.State.IDLE
        || options == null
        || selectedOptions().deepVisits() != options.deepVisits()) {
      return;
    }
    visitsHint.setForeground(MUTED);
    visitsHint.setText(
        estimatedMillis > 0
            ? MessageFormat.format(
                resources.getString("WholeGameAnalysis.visits.estimate"),
                formatDuration(estimatedMillis))
            : resources.getString("WholeGameAnalysis.visits.hint"));
  }

  private void setVisitControlsEnabled(boolean enabled) {
    visitsPreset.setEnabled(enabled);
    customVisits.setEnabled(enabled && selectedPreset() == WholeGameAnalysisOptions.Preset.CUSTOM);
  }

  private JComponent buildMetadataRow() {
    JPanel metadata = new MetadataPanel();
    metadata.setOpaque(false);

    GridBagConstraints left = new GridBagConstraints();
    left.gridx = 0;
    left.gridy = 0;
    left.weightx = 1;
    left.fill = GridBagConstraints.HORIZONTAL;
    left.anchor = GridBagConstraints.WEST;
    modeLabel.setName("wholeGameMode");
    modeLabel.setForeground(ACCENT);
    modeLabel.setFont(modeLabel.getFont().deriveFont(Font.BOLD, 12f));
    modeLabel
        .getAccessibleContext()
        .setAccessibleName(resources.getString("WholeGameAnalysis.title"));
    metadata.add(modeLabel, left);

    GridBagConstraints right = new GridBagConstraints();
    right.gridx = 1;
    right.gridy = 0;
    right.weightx = 1;
    right.fill = GridBagConstraints.HORIZONTAL;
    right.anchor = GridBagConstraints.EAST;
    right.insets = new Insets(0, 18, 0, 0);
    remainingLabel.setName("wholeGameRemaining");
    remainingLabel.setForeground(MUTED);
    remainingLabel.setHorizontalAlignment(SwingConstants.RIGHT);
    remainingLabel.setFont(remainingLabel.getFont().deriveFont(12f));
    remainingLabel
        .getAccessibleContext()
        .setAccessibleName(resources.getString("WholeGameAnalysis.remaining.calculating"));
    metadata.add(remainingLabel, right);
    return metadata;
  }

  private JComponent buildActions() {
    JPanel actions = new JPanel();
    actions.setOpaque(false);
    actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));

    startButton.setName("wholeGameStart");
    startButton.setText(resources.getString("WholeGameAnalysis.start"));
    styleButton(startButton, true);
    startButton.addActionListener(
        event -> {
          WholeGameAnalysisOptions options = commitSelectedOptions();
          if (options == null || !options.isValid()) {
            visitsHint.setForeground(new Color(173, 52, 49));
            visitsHint.setText(resources.getString("WholeGameAnalysis.visits.invalid"));
            return;
          }
          if (session == null && ownerFrame.startWholeGameDeepAnalysis(this, options)) {
            setVisitControlsEnabled(false);
            applyControlState(controlState(WholeGameAnalysisSession.State.PREPARING));
          }
        });

    pauseButton.setName("wholeGamePause");
    pauseButton.setText(resources.getString("WholeGameAnalysis.pause"));
    styleButton(pauseButton, false);
    pauseButton.addActionListener(
        event -> {
          if (session == null) {
            return;
          }
          if (latestState == WholeGameAnalysisSession.State.PAUSED) {
            session.resume();
          } else {
            session.pause();
          }
        });

    stopButton.setName("wholeGameStop");
    stopButton.setText(resources.getString("WholeGameAnalysis.cancel"));
    styleButton(stopButton, false);
    stopButton.addActionListener(
        event -> {
          if (session == null) {
            return;
          }
          if (isTerminal(latestState)) {
            ownerFrame.closeWholeGameAnalysisDialog(this, session);
          } else if (latestState != WholeGameAnalysisSession.State.IDLE) {
            session.cancel();
          }
        });

    actions.add(Box.createHorizontalGlue());
    actions.add(startButton);
    actions.add(Box.createHorizontalStrut(10));
    actions.add(pauseButton);
    actions.add(Box.createHorizontalStrut(10));
    actions.add(stopButton);
    applyControlState(controlState(WholeGameAnalysisSession.State.IDLE));
    return actions;
  }

  private void applyControlState(ControlState controls) {
    startButton.setEnabled(controls.startEnabled);
    pauseButton.setEnabled(controls.pauseEnabled);
    stopButton.setEnabled(controls.stopEnabled);
    setVisitControlsEnabled(controls.startEnabled && session == null);
    pauseButton.setText(
        resources.getString(
            controls.resumeLabel ? "WholeGameAnalysis.resume" : "WholeGameAnalysis.pause"));
    stopButton.setText(
        resources.getString(
            controls.closeLabel ? "WholeGameAnalysis.close" : "WholeGameAnalysis.cancel"));
    startButton.setBackground(controls.startEnabled ? ACCENT : DISABLED_SURFACE);
    startButton.setForeground(controls.startEnabled ? Color.WHITE : MUTED);
    pauseButton.setBackground(controls.resumeLabel ? ACCENT : CARD);
    pauseButton.setForeground(controls.resumeLabel ? Color.WHITE : TEXT);
    stopButton.setBackground(controls.closeLabel ? ACCENT : CARD);
    stopButton.setForeground(controls.closeLabel ? Color.WHITE : TEXT);
    updateButtonSize(startButton);
    updateButtonSize(pauseButton);
    updateButtonSize(stopButton);
    startButton.getAccessibleContext().setAccessibleName(startButton.getText());
    pauseButton.getAccessibleContext().setAccessibleName(pauseButton.getText());
    stopButton.getAccessibleContext().setAccessibleName(stopButton.getText());
    if (controls.startEnabled) {
      getRootPane().setDefaultButton(startButton);
    } else if (controls.pauseEnabled) {
      getRootPane().setDefaultButton(pauseButton);
    } else if (controls.closeLabel) {
      getRootPane().setDefaultButton(stopButton);
    } else {
      getRootPane().setDefaultButton(null);
    }
  }

  static ControlState controlState(WholeGameAnalysisSession.State state) {
    switch (state) {
      case IDLE:
        return new ControlState(true, false, false, false, false);
      case PREPARING:
      case BASELINE:
      case DEEP:
        return new ControlState(false, true, true, false, false);
      case PAUSING:
        return new ControlState(false, false, true, false, false);
      case PAUSED:
        return new ControlState(false, true, true, true, false);
      case COMPLETE:
      case CANCELLED:
      case FAILED:
      default:
        return new ControlState(false, false, true, false, true);
    }
  }

  private void styleButton(JButton button, boolean primary) {
    installPortableButtonFill(button);
    button.setFocusPainted(false);
    button.setOpaque(true);
    button.setBorder(new RoundedBorder(primary ? ACCENT : BORDER, 12));
    button.setBackground(primary ? ACCENT : CARD);
    button.setForeground(primary ? Color.WHITE : TEXT);
    updateButtonSize(button);
    button.getAccessibleContext().setAccessibleName(button.getText());
  }

  static void installPortableButtonFill(JButton button) {
    // Windows native LAF can ignore JButton background colors.
    button.setUI(new BasicButtonUI());
    button.setContentAreaFilled(true);
  }

  private static void updateButtonSize(JButton button) {
    Dimension natural = button.getUI().getPreferredSize(button);
    button.setPreferredSize(new Dimension(Math.max(112, natural.width + 22), 40));
    button.setMaximumSize(button.getPreferredSize());
  }

  private void fitToUsableScreen() {
    Rectangle bounds = getGraphicsConfiguration().getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(getGraphicsConfiguration());
    Dimension usable =
        new Dimension(
            Math.max(1, bounds.width - insets.left - insets.right - SCREEN_MARGIN),
            Math.max(1, bounds.height - insets.top - insets.bottom - SCREEN_MARGIN));
    Dimension target = clampDialogSize(getSize(), usable);
    setSize(target);
    setMinimumSize(target);
  }

  static Dimension clampDialogSize(Dimension packed, Dimension usable) {
    return new Dimension(
        Math.max(1, Math.min(packed.width, usable.width)),
        Math.max(1, Math.min(packed.height, usable.height)));
  }

  private void installWindowBehavior() {
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            handleCloseRequest();
          }
        });
    getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide");
    getRootPane()
        .getActionMap()
        .put(
            "hide",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent event) {
                handleCloseRequest();
              }
            });
    getRootPane().setDefaultButton(startButton);
  }

  private void handleCloseRequest() {
    if (session != null && session.isActive()) {
      setVisible(false);
      ownerFrame.setMainPanelFocus();
      return;
    }
    ownerFrame.closeWholeGameAnalysisDialog(this, session);
  }

  private String remainingText(WholeGameAnalysisSession.Snapshot snapshot) {
    if (snapshot.state == WholeGameAnalysisSession.State.IDLE
        || snapshot.state == WholeGameAnalysisSession.State.PAUSED
        || snapshot.state == WholeGameAnalysisSession.State.PAUSING
        || isTerminal(snapshot.state)) {
      return "";
    }
    if (snapshot.estimatedRemainingMillis < 0) {
      return resources.getString("WholeGameAnalysis.remaining.calculating");
    }
    return MessageFormat.format(
        resources.getString("WholeGameAnalysis.remaining"),
        formatDuration(snapshot.estimatedRemainingMillis));
  }

  static String formatDuration(long millis) {
    long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
    long hours = totalSeconds / 3600L;
    long minutes = (totalSeconds % 3600L) / 60L;
    long seconds = totalSeconds % 60L;
    if (hours > 0L) {
      return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }
    return String.format("%02d:%02d", minutes, seconds);
  }

  private static boolean isTerminal(WholeGameAnalysisSession.State state) {
    return state == WholeGameAnalysisSession.State.COMPLETE
        || state == WholeGameAnalysisSession.State.CANCELLED
        || state == WholeGameAnalysisSession.State.FAILED;
  }

  private static JTextArea textArea() {
    JTextArea area = new JTextArea();
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setBorder(null);
    return area;
  }

  static final class ControlState {
    final boolean startEnabled;
    final boolean pauseEnabled;
    final boolean stopEnabled;
    final boolean resumeLabel;
    final boolean closeLabel;

    private ControlState(
        boolean startEnabled,
        boolean pauseEnabled,
        boolean stopEnabled,
        boolean resumeLabel,
        boolean closeLabel) {
      this.startEnabled = startEnabled;
      this.pauseEnabled = pauseEnabled;
      this.stopEnabled = stopEnabled;
      this.resumeLabel = resumeLabel;
      this.closeLabel = closeLabel;
    }
  }

  private static final class PresetChoice {
    private final WholeGameAnalysisOptions.Preset preset;
    private final String label;

    private PresetChoice(WholeGameAnalysisOptions.Preset preset, String label) {
      this.preset = preset;
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  /** Reserves one current-font line even while running-state labels are empty. */
  private static final class MetadataPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private MetadataPanel() {
      super(new GridBagLayout());
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      int lineHeight = 0;
      for (Component child : getComponents()) {
        if (child.getFont() != null) {
          lineHeight =
              Math.max(lineHeight, child.getFontMetrics(child.getFont()).getHeight());
        }
      }
      return new Dimension(preferred.width, Math.max(preferred.height, lineHeight));
    }
  }

  private static final class RoundedBorder extends AbstractBorder {
    private static final long serialVersionUID = 1L;
    private final Color color;
    private final int radius;

    private RoundedBorder(Color color, int radius) {
      this.color = color;
      this.radius = radius;
    }

    @Override
    public void paintBorder(
        Component component, Graphics graphics, int x, int y, int width, int height) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(color);
      g2.setStroke(new BasicStroke(1f));
      g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
      g2.dispose();
    }
  }

  private static final class GoIcon implements Icon {
    private final int size;

    private GoIcon(int size) {
      this.size = size;
    }

    @Override
    public int getIconWidth() {
      return size;
    }

    @Override
    public int getIconHeight() {
      return size;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(new Color(226, 204, 158));
      g2.fillRoundRect(x, y, size, size, 14, 14);
      int stone = Math.round(size * 0.48f);
      g2.setColor(new Color(35, 39, 38));
      g2.fillOval(x + 5, y + 6, stone, stone);
      g2.setColor(new Color(247, 245, 238));
      g2.fillOval(x + size - stone - 5, y + size - stone - 5, stone, stone);
      g2.setColor(new Color(187, 178, 158));
      g2.drawOval(x + size - stone - 5, y + size - stone - 5, stone, stone);
      g2.dispose();
    }
  }
}
