package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryList;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.training.HumanSlTrainingConfig;
import featurecat.lizzie.training.HumanSlTrainingSession;
import featurecat.lizzie.training.OpponentPreset;
import featurecat.lizzie.training.TrainingMode;
import featurecat.lizzie.util.AnalysisEngineCommandHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.CardLayout;
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
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/** Product-focused setup for one HumanSL AI coaching game. */
public final class NewHumanSlGameDialog extends JDialog {
  private static final long serialVersionUID = 1L;
  private static final Duration ENGINE_READY_TIMEOUT = Duration.ofSeconds(180);

  private final ResourceBundle resources = Lizzie.resourceBundle;
  private final HumanSlTrainingSession session;
  private final JFontComboBox<String> trainingModeBox = new JFontComboBox<String>();
  private final JToggleButton rankPresetButton = new JToggleButton();
  private final JToggleButton proPresetButton = new JToggleButton();
  private final JToggleButton kyuButton = new JToggleButton();
  private final JToggleButton danButton = new JToggleButton();
  private final JSpinner rankSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 9, 1));
  private final JFontComboBox<String> proStyleBox = new JFontComboBox<String>();
  private final JPanel opponentCards = new JPanel(new CardLayout());
  private final JFontComboBox<String> colorBox = new JFontComboBox<String>();
  private final JFontComboBox<String> timeBox = new JFontComboBox<String>();
  private final JToggleButton moreButton = new JToggleButton();
  private final JCheckBox fromCurrentBox = new JCheckBox();
  private final JPanel advancedPanel = new JPanel(new GridBagLayout());
  private final JFontComboBox<Integer> handicapBox = new JFontComboBox<Integer>();
  private final JFontTextField komiField = new JFontTextField();
  private final JFontButton startButton = new JFontButton();
  private final JFontButton pauseDownloadButton = new JFontButton();
  private final JFontButton cancelDownloadButton = new JFontButton();
  private final JLabel modelStatusLabel = new JFontLabel();
  private final JLabel statusLabel = new JFontLabel();
  private final JProgressBar downloadProgress = new JProgressBar(0, 1000);
  private final JPanel downloadPanel = new JPanel(new GridBagLayout());

  private volatile KataGoAutoSetupHelper.DownloadSession downloadSession;
  private volatile HumanSlAnalysisRunner preparingRunner;
  private volatile boolean downloading;
  private volatile boolean closeRequested;
  private boolean downloadPaused;
  private boolean cancelled = true;
  private Timer startupElapsedTimer;
  private long startupStartedNanos;
  private String startupStageText = "";
  private ForegroundAnalysisPause foregroundAnalysisPause = ForegroundAnalysisPause.inactive();

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
    JScrollPane contentScroll = new JScrollPane(buildContent());
    contentScroll.setBorder(null);
    contentScroll.getViewport().setBackground(HumanSlTrainingStyle.BACKGROUND);
    contentScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    contentScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
    setContentPane(contentScroll);
    installBehavior();
    refreshModelStatus();
    pack();
    fitToScreen(null, 410);
  }

  private JComponent buildContent() {
    populateControls();
    JPanel root = new JPanel(new BorderLayout(0, 14));
    root.setName("humanSlTrainingSetup");
    root.setBackground(HumanSlTrainingStyle.BACKGROUND);
    root.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
    root.add(buildHeader(), BorderLayout.NORTH);
    root.add(buildForm(), BorderLayout.CENTER);
    root.add(buildFooter(), BorderLayout.SOUTH);
    AccessibilitySupport.applyToTree(root);
    return root;
  }

  private JComponent buildHeader() {
    JPanel header = new JPanel(new BorderLayout(12, 0));
    header.setOpaque(false);
    JLabel icon = new JLabel(HumanSlTrainingStyle.coachIcon(42, false));
    icon.setPreferredSize(new Dimension(46, 46));
    header.add(icon, BorderLayout.WEST);

    JPanel copy = new JPanel();
    copy.setOpaque(false);
    copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
    String titleText = text("HumanSlTraining.title", "AI Coach");
    JLabel title = new JFontLabel(titleText);
    title.setFont(
        HumanSlTrainingStyle.fontForText(
            titleText, Font.BOLD, Math.max(24, Config.frameFontSize + 8)));
    title.setForeground(HumanSlTrainingStyle.TEXT);
    String subtitleText =
        text(
            "HumanSlTraining.subtitle",
            "Choose an opponent that matches your level, then review the important positions.");
    JLabel subtitle = new JFontLabel(subtitleText);
    subtitle.setFont(
        HumanSlTrainingStyle.fontForText(
            subtitleText, Font.PLAIN, Math.max(12, Config.frameFontSize - 1)));
    subtitle.setForeground(HumanSlTrainingStyle.MUTED);
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
    copy.add(title);
    copy.add(Box.createVerticalStrut(3));
    copy.add(subtitle);
    header.add(copy, BorderLayout.CENTER);

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    actions.setOpaque(false);
    fromCurrentBox.setText(text("HumanSlTraining.fromCurrent", "Start from current position"));
    fromCurrentBox.setFont(
        HumanSlTrainingStyle.fontForText(
            fromCurrentBox.getText(), Font.PLAIN, Math.max(12, Config.frameFontSize - 1)));
    fromCurrentBox.setOpaque(false);
    fromCurrentBox.setForeground(HumanSlTrainingStyle.TEXT);
    fromCurrentBox.setEnabled(Lizzie.board.getHistory().getMoveNumber() > 0);
    actions.add(fromCurrentBox);
    actions.add(moreButton);
    header.add(actions, BorderLayout.EAST);
    return header;
  }

  private JComponent buildForm() {
    HumanSlTrainingStyle.RoundedPanel card =
        new HumanSlTrainingStyle.RoundedPanel(
            HumanSlTrainingStyle.CARD, HumanSlTrainingStyle.BORDER, 16);
    card.setLayout(new GridBagLayout());
    card.setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 18));

    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridy = 0;
    constraints.insets = new Insets(0, 0, 8, 14);
    constraints.anchor = GridBagConstraints.WEST;
    constraints.fill = GridBagConstraints.HORIZONTAL;

    constraints.gridx = 0;
    constraints.weightx = 0.18;
    card.add(field(text("HumanSlTraining.mode", "Training mode"), trainingModeBox), constraints);
    constraints.gridx = 1;
    constraints.weightx = 0.34;
    card.add(field(text("HumanSlTraining.opponent", "AI opponent"), buildOpponentControl()), constraints);
    constraints.gridx = 2;
    constraints.weightx = 0.16;
    card.add(field(text("HumanSlTraining.color", "Your color"), colorBox), constraints);
    constraints.gridx = 3;
    constraints.weightx = 0.15;
    card.add(field(text("HumanSlTraining.time", "Time"), timeBox), constraints);
    constraints.gridx = 4;
    constraints.weightx = 0.17;
    constraints.insets = new Insets(20, 0, 8, 0);
    card.add(startButton, constraints);

    constraints.gridx = 0;
    constraints.gridy = 1;
    constraints.gridwidth = 5;
    constraints.weightx = 1.0;
    constraints.insets = new Insets(5, 0, 0, 0);
    card.add(buildSummaryRow(), constraints);

    constraints.gridy = 2;
    constraints.insets = new Insets(10, 0, 0, 0);
    card.add(advancedPanel, constraints);

    constraints.gridy = 3;
    card.add(downloadPanel, constraints);

    constraints.gridy = 4;
    constraints.weighty = 1.0;
    constraints.fill = GridBagConstraints.BOTH;
    card.add(Box.createGlue(), constraints);
    return card;
  }

  private void populateControls() {
    trainingModeBox.addItem(text("HumanSlTraining.mode.review", "Post-game review"));
    trainingModeBox.addItem(text("HumanSlTraining.mode.live", "Coach while playing"));

    rankPresetButton.setText(text("HumanSlTraining.opponent.rank", "By rank"));
    proPresetButton.setText(text("HumanSlTraining.opponent.pro", "Pro style"));
    ButtonGroup opponentGroup = new ButtonGroup();
    opponentGroup.add(rankPresetButton);
    opponentGroup.add(proPresetButton);
    rankPresetButton.setSelected(true);
    styleSegment(rankPresetButton);
    styleSegment(proPresetButton);
    rankPresetButton.addActionListener(event -> showOpponentCard("rank"));
    proPresetButton.addActionListener(event -> showOpponentCard("pro"));

    kyuButton.setText(text("HumanSlTraining.rank.kyu", "Kyu"));
    danButton.setText(text("HumanSlTraining.rank.dan", "Dan"));
    ButtonGroup rankGroup = new ButtonGroup();
    rankGroup.add(kyuButton);
    rankGroup.add(danButton);
    danButton.setSelected(true);
    styleSegment(kyuButton);
    styleSegment(danButton);
    kyuButton.addActionListener(event -> updateRankModel(false));
    danButton.addActionListener(event -> updateRankModel(true));

    proStyleBox.addItem(text("HumanSlTraining.pro.modern", "Modern pro style"));
    proStyleBox.addItem(text("HumanSlTraining.pro.online9d", "Online 9 dan"));

    colorBox.addItem(text("HumanSlTraining.color.random", "Random"));
    colorBox.addItem(text("HumanSlTraining.color.black", "Black"));
    colorBox.addItem(text("HumanSlTraining.color.white", "White"));

    timeBox.addItem(text("HumanSlTraining.time.10", "10 sec"));
    timeBox.addItem(text("HumanSlTraining.time.30", "30 sec"));
    timeBox.addItem(text("HumanSlTraining.time.60", "1 min"));
    timeBox.addItem(text("HumanSlTraining.time.unlimited", "No limit"));

    for (int handicap = 0; handicap <= 9; handicap++) {
      handicapBox.addItem(handicap);
    }
    komiField.setDocument(new KomiDocument(true));
    komiField.setText("7.5");
    handicapBox.addActionListener(event -> updateKomiForHandicap());

    startButton.setName("humanSlTrainingStart");
    startButton.setText(text("HumanSlTraining.start", "Start training"));
    startButton.setIcon(HumanSlTrainingStyle.coachIcon(20, true));
    startButton.setIconTextGap(8);
    startButton.setFont(
        HumanSlTrainingStyle.fontForText(
            startButton.getText(), Font.BOLD, Math.max(14, Config.frameFontSize)));
    startButton.setPreferredSize(new Dimension(158, 44));
    startButton.setMinimumSize(new Dimension(142, 42));
    HumanSlTrainingStyle.stylePrimary(startButton);
    startButton.addActionListener(event -> onPrimaryAction());

    moreButton.setText(text("HumanSlTraining.more", "More settings"));
    HumanSlTrainingStyle.styleSecondary(moreButton);
    moreButton.addItemListener(event -> updateAdvancedVisibility());
    buildAdvancedPanel();
    buildDownloadPanel();
    trainingModeBox.addActionListener(event -> updateTrainingSummary());
    rankPresetButton.addActionListener(event -> updateTrainingSummary());
    proPresetButton.addActionListener(event -> updateTrainingSummary());
    kyuButton.addActionListener(event -> updateTrainingSummary());
    danButton.addActionListener(event -> updateTrainingSummary());
    rankSpinner.addChangeListener(event -> updateTrainingSummary());
    proStyleBox.addActionListener(event -> updateTrainingSummary());
    timeBox.addActionListener(event -> updateTrainingSummary());
    applyChoiceFont(trainingModeBox);
    applyChoiceFont(proStyleBox);
    applyChoiceFont(colorBox);
    applyChoiceFont(timeBox);
    applyChoiceFont(handicapBox);
    komiField.setFont(
        HumanSlTrainingStyle.fontForText(
            komiField.getText(), Font.PLAIN, Math.max(12, Config.frameFontSize)));
  }

  private JComponent buildOpponentControl() {
    JPanel container = new JPanel(new BorderLayout(0, 7));
    container.setOpaque(false);
    JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    tabs.setOpaque(false);
    tabs.add(rankPresetButton);
    tabs.add(proPresetButton);
    container.add(tabs, BorderLayout.NORTH);

    JPanel rankPanel = new JPanel(new BorderLayout(8, 0));
    rankPanel.setOpaque(false);
    JPanel kind = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    kind.setOpaque(false);
    kind.add(kyuButton);
    kind.add(danButton);
    rankPanel.add(kind, BorderLayout.WEST);
    rankSpinner.setName("humanSlRankSpinner");
    rankSpinner.setFont(
        HumanSlTrainingStyle.fontForText(
            rankSpinner.getValue().toString(), Font.BOLD, Math.max(14, Config.frameFontSize)));
    rankPanel.add(rankSpinner, BorderLayout.CENTER);
    String rangeText = text("HumanSlTraining.rank.range", "20 kyu - 9 dan");
    JLabel range = new JFontLabel(rangeText);
    range.setForeground(HumanSlTrainingStyle.MUTED);
    range.setFont(
        HumanSlTrainingStyle.fontForText(
            rangeText, Font.PLAIN, Math.max(11, Config.frameFontSize - 2)));
    rankPanel.add(range, BorderLayout.SOUTH);

    JPanel proPanel = new JPanel(new BorderLayout());
    proPanel.setOpaque(false);
    proPanel.add(proStyleBox, BorderLayout.CENTER);
    opponentCards.setOpaque(false);
    opponentCards.add(rankPanel, "rank");
    opponentCards.add(proPanel, "pro");
    container.add(opponentCards, BorderLayout.CENTER);
    return container;
  }

  private JComponent field(String label, JComponent control) {
    JPanel panel = new JPanel();
    panel.setOpaque(false);
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    JLabel title = new JFontLabel(label);
    title.setForeground(HumanSlTrainingStyle.TEXT);
    title.setFont(
        HumanSlTrainingStyle.fontForText(
            label, Font.BOLD, Math.max(12, Config.frameFontSize - 1)));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    control.setAlignmentX(Component.LEFT_ALIGNMENT);
    panel.add(title);
    panel.add(Box.createVerticalStrut(6));
    panel.add(control);
    return panel;
  }

  private JComponent buildSummaryRow() {
    JPanel row = new JPanel(new BorderLayout(12, 0));
    row.setOpaque(false);
    row.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, HumanSlTrainingStyle.BORDER),
            BorderFactory.createEmptyBorder(10, 0, 0, 0)));
    modelStatusLabel.setForeground(HumanSlTrainingStyle.ACCENT_DARK);
    modelStatusLabel.setFont(
        HumanSlTrainingStyle.fontForText(
            "HumanSL", Font.BOLD, Math.max(12, Config.frameFontSize - 1)));
    row.add(modelStatusLabel, BorderLayout.WEST);
    statusLabel.setForeground(HumanSlTrainingStyle.MUTED);
    statusLabel.setHorizontalAlignment(JLabel.CENTER);
    row.add(statusLabel, BorderLayout.CENTER);
    return row;
  }

  private void buildAdvancedPanel() {
    advancedPanel.setOpaque(false);
    advancedPanel.setVisible(false);
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = 0;
    c.anchor = GridBagConstraints.WEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1.0;
    c.insets = new Insets(0, 0, 0, 14);
    c.gridx = 0;
    advancedPanel.add(field(text("HumanSlTraining.handicap", "Handicap"), handicapBox), c);
    c.gridx = 1;
    advancedPanel.add(field(text("HumanSlTraining.komi", "Komi"), komiField), c);
    c.gridx = 2;
    c.insets = new Insets(20, 0, 0, 0);
    String tipText =
        text(
            "HumanSlTraining.moreHint",
            "Opponent era and variation are chosen automatically for the selected style.");
    JLabel tip = new JFontLabel(tipText);
    tip.setForeground(HumanSlTrainingStyle.MUTED);
    tip.setFont(
        HumanSlTrainingStyle.fontForText(
            tipText, Font.PLAIN, Math.max(11, Config.frameFontSize - 2)));
    advancedPanel.add(tip, c);
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
    downloadProgress.setBackground(HumanSlTrainingStyle.ACCENT_SOFT);
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

  private JComponent buildFooter() {
    JPanel footer = new JPanel(new BorderLayout());
    footer.setOpaque(false);
    String hintText =
        text(
            "HumanSlTraining.footer",
            "The AI imitates the selected human style; the displayed rank is a style reference, not an official certification.");
    JLabel hint = new JFontLabel(hintText);
    hint.setForeground(HumanSlTrainingStyle.MUTED);
    hint.setFont(
        HumanSlTrainingStyle.fontForText(
            hintText, Font.PLAIN, Math.max(11, Config.frameFontSize - 2)));
    footer.add(hint, BorderLayout.WEST);
    JFontButton cancel = new JFontButton(text("HumanSlTraining.close", "Cancel"));
    HumanSlTrainingStyle.styleSecondary(cancel);
    cancel.addActionListener(event -> closeDialog());
    footer.add(cancel, BorderLayout.EAST);
    return footer;
  }

  private void styleSegment(JToggleButton button) {
    button.setFont(
        HumanSlTrainingStyle.fontForText(
            button.getText(), Font.BOLD, Math.max(12, Config.frameFontSize - 1)));
    HumanSlTrainingStyle.styleSecondary(button);
    button.addItemListener(event -> refreshSegmentStyle(button));
    refreshSegmentStyle(button);
  }

  private void refreshSegmentStyle(JToggleButton button) {
    if (button.isSelected()) {
      HumanSlTrainingStyle.stylePrimary(button);
    } else {
      HumanSlTrainingStyle.styleSecondary(button);
    }
  }

  private void showOpponentCard(String card) {
    ((CardLayout) opponentCards.getLayout()).show(opponentCards, card);
    refreshSegmentStyle(rankPresetButton);
    refreshSegmentStyle(proPresetButton);
  }

  private void updateRankModel(boolean dan) {
    int current = ((Number) rankSpinner.getValue()).intValue();
    rankSpinner.setModel(new SpinnerNumberModel(Math.min(current, dan ? 9 : 20), 1, dan ? 9 : 20, 1));
    refreshSegmentStyle(kyuButton);
    refreshSegmentStyle(danButton);
  }

  private void updateAdvancedVisibility() {
    advancedPanel.setVisible(moreButton.isSelected());
    moreButton.setText(
        text(
            moreButton.isSelected() ? "HumanSlTraining.less" : "HumanSlTraining.more",
            moreButton.isSelected() ? "Fewer settings" : "More settings"));
    HumanSlTrainingStyle.styleSecondary(moreButton);
    packForContent();
  }

  private void packForContent() {
    Dimension current = getSize();
    pack();
    int contentHeight =
        moreButton.isSelected()
                || downloading
                || session.state() == HumanSlTrainingSession.State.PREPARING
            ? 500
            : 410;
    fitToScreen(current, contentHeight);
  }

  private void fitToScreen(Dimension current, int preferredHeight) {
    Rectangle usableBounds = HumanSlDialogBounds.usableBounds(getOwner(), this);
    Dimension target =
        HumanSlDialogBounds.fit(getSize(), current, usableBounds, 920, preferredHeight);
    setMinimumSize(HumanSlDialogBounds.minimum(target, 860, 390));
    setSize(target);
    setLocationRelativeTo(getOwner());
    HumanSlDialogBounds.keepOnScreen(this, usableBounds);
  }

  private void updateTrainingSummary() {
    if (downloading || session.state() == HumanSlTrainingSession.State.PREPARING) {
      return;
    }
    Object selectedTime = timeBox.getSelectedItem();
    String time = selectedTime == null ? "" : selectedTime.toString();
    String opponent;
    if (rankPresetButton.isSelected()) {
      int rank = ((Number) rankSpinner.getValue()).intValue();
      opponent =
          MessageFormat.format(
              text(
                  danButton.isSelected()
                      ? "HumanSlTraining.rank.danValue"
                      : "HumanSlTraining.rank.kyuValue",
                  danButton.isSelected() ? "{0} dan" : "{0} kyu"),
              rank);
    } else {
      Object selectedStyle = proStyleBox.getSelectedItem();
      opponent = selectedStyle == null ? "" : selectedStyle.toString();
    }
    statusLabel.setForeground(HumanSlTrainingStyle.MUTED);
    setStatusText(
        MessageFormat.format(
            text(
                trainingModeBox.getSelectedIndex() == 1
                    ? "HumanSlTraining.summary.live"
                    : "HumanSlTraining.summary.review",
                trainingModeBox.getSelectedIndex() == 1
                    ? "Play {0} at {1}; clear mistakes are shown without interrupting the game."
                    : "Play {0} at {1}; review the key positions after the game."),
            opponent,
            time));
  }

  private void updateKomiForHandicap() {
    int handicap = selectedHandicap();
    komiField.setText(handicap >= 2 ? "0" : "7.5");
  }

  private void onPrimaryAction() {
    if (downloading) {
      return;
    }
    Path modelPath = HumanSlGameController.resolveDefaultHumanModel();
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
      HumanSlTrainingStyle.stylePrimary(startButton);
      setStatusText(
          text("HumanSlTraining.download.paused", "Download paused; progress is kept."));
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
    HumanSlTrainingConfig config = selectedConfig();
    BoardHistoryNode readinessNode =
        config.fromCurrentPosition
            ? Lizzie.board.getHistory().getCurrentHistoryNode()
            : new BoardHistoryList(BoardData.empty(Board.boardWidth, Board.boardHeight)).root();
    AnalysisEngineCommandHelper.Result commandResult = resolveAnalysisCommand();
    if (!commandResult.isSuccess()) {
      String detail = commandResult.getMessage();
      showInlineError(
          Utils.isBlank(detail)
              ? text("HumanSlGame.error.noEngine", "No analysis engine command is available.")
              : detail);
      return;
    }
    String command = commandResult.getCommand();
    setFormEnabled(false);
    session.setState(HumanSlTrainingSession.State.PREPARING);
    foregroundAnalysisPause.restore();
    foregroundAnalysisPause = ForegroundAnalysisPause.pauseCurrent();
    HumanSlAnalysisRunner runner = new HumanSlAnalysisRunner(command, modelPath);
    preparingRunner = runner;
    beginEngineStartup(runner);
    Thread worker =
        new Thread(
            () -> {
              boolean started =
                  runner.start()
                      && runner.verifyReady(
                          readinessNode, config.humanSlProfile(), ENGINE_READY_TIMEOUT);
              SwingUtilities.invokeLater(
                  () -> {
                    if (preparingRunner == runner) {
                      preparingRunner = null;
                    }
                    if (closeRequested) {
                      try {
                        runner.close();
                      } catch (Exception ignored) {
                      }
                      return;
                    }
                    if (!started) {
                      String failureReason = runner.getUnavailableReason();
                      try {
                        runner.close();
                      } catch (Exception ignored) {
                      }
                      session.setState(HumanSlTrainingSession.State.IDLE);
                      foregroundAnalysisPause.restore();
                      foregroundAnalysisPause = ForegroundAnalysisPause.inactive();
                      finishEngineStartup(runner);
                      setFormEnabled(true);
                      showInlineError(
                          MessageFormat.format(
                              text(
                                  "HumanSlGame.error.startFailed",
                                  "Failed to start HumanSL engine: {0}"),
                              Utils.isBlank(failureReason)
                                  ? text(
                                      "HumanSlTraining.preparing.failedDetail",
                                      "The engine did not return a readiness response.")
                                  : failureReason));
                      return;
                    }
                    finishEngineStartup(runner);
                    HumanSlGameController controller =
                        new HumanSlGameController(runner, config, session);
                    boolean resumeAnalysisAfterGame =
                        foregroundAnalysisPause.transferRestoreResponsibility();
                    foregroundAnalysisPause = ForegroundAnalysisPause.inactive();
                    cancelled = false;
                    setVisible(false);
                    controller.start(resumeAnalysisAfterGame);
                  });
            },
            "humansl-coach-start");
    worker.setDaemon(true);
    worker.start();
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
            text("HumanSlTraining.preparing.cacheReady", "GPU cache is ready; verifying the model...");
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
    downloadProgress.getAccessibleContext().setAccessibleDescription(startupStageText);
  }

  private void finishEngineStartup(HumanSlAnalysisRunner runner) {
    if (runner != null) {
      runner.setStartupListener(null);
    }
    if (startupElapsedTimer != null) {
      startupElapsedTimer.stop();
      startupElapsedTimer = null;
    }
    startupStartedNanos = 0L;
    startupStageText = "";
    downloadProgress.setIndeterminate(false);
    downloadPanel.setVisible(false);
    pauseDownloadButton.setVisible(true);
    cancelDownloadButton.setVisible(true);
    packForContent();
  }

  private HumanSlTrainingConfig selectedConfig() {
    OpponentPreset preset;
    if (rankPresetButton.isSelected()) {
      preset = OpponentPreset.RANK;
    } else {
      preset = proStyleBox.getSelectedIndex() == 1 ? OpponentPreset.ONLINE_9D : OpponentPreset.MODERN_PRO;
    }
    HumanSlTrainingConfig.PlayerColor color;
    if (colorBox.getSelectedIndex() == 1) {
      color = HumanSlTrainingConfig.PlayerColor.BLACK;
    } else if (colorBox.getSelectedIndex() == 2) {
      color = HumanSlTrainingConfig.PlayerColor.WHITE;
    } else {
      color = HumanSlTrainingConfig.PlayerColor.RANDOM;
    }
    int time = timeBox.getSelectedIndex() == 0 ? 10 : timeBox.getSelectedIndex() == 1 ? 30 : timeBox.getSelectedIndex() == 2 ? 60 : 24 * 60 * 60;
    int handicap = selectedHandicap();
    double komi = Utils.parseTextToDouble(komiField, handicap >= 2 ? 0.0 : 7.5);
    return HumanSlTrainingConfig.builder()
        .mode(trainingModeBox.getSelectedIndex() == 1 ? TrainingMode.LIVE_CORRECTION : TrainingMode.POST_GAME_REVIEW)
        .opponentPreset(preset)
        .rank(((Number) rankSpinner.getValue()).intValue(), danButton.isSelected())
        .playerColor(color)
        .moveTimeSeconds(time)
        .handicap(handicap)
        .komi(komi)
        .fromCurrentPosition(fromCurrentBox.isSelected())
        .build();
  }

  private int selectedHandicap() {
    return handicapBox.getSelectedItem() == null ? 0 : (Integer) handicapBox.getSelectedItem();
  }

  private void refreshModelStatus() {
    boolean installed = HumanSlGameController.resolveDefaultHumanModel() != null;
    setModelStatusText(
        text(
            installed ? "HumanSlTraining.model.ready" : "HumanSlTraining.model.missing",
            installed ? "HumanSL model ready" : "HumanSL model not downloaded"));
    startButton.setText(
        text(
            installed ? "HumanSlTraining.start" : "HumanSlTraining.downloadAndStart",
            installed ? "Start training" : "Download and start"));
    HumanSlTrainingStyle.stylePrimary(startButton);
    startButton.setIcon(HumanSlTrainingStyle.coachIcon(20, true));
    updateTrainingSummary();
  }

  private void setFormEnabled(boolean enabled) {
    trainingModeBox.setEnabled(enabled);
    rankPresetButton.setEnabled(enabled);
    proPresetButton.setEnabled(enabled);
    kyuButton.setEnabled(enabled);
    danButton.setEnabled(enabled);
    rankSpinner.setEnabled(enabled);
    proStyleBox.setEnabled(enabled);
    colorBox.setEnabled(enabled);
    timeBox.setEnabled(enabled);
    moreButton.setEnabled(enabled);
    handicapBox.setEnabled(enabled);
    komiField.setEnabled(enabled);
    fromCurrentBox.setEnabled(enabled && Lizzie.board.getHistory().getMoveNumber() > 0);
    startButton.setEnabled(enabled);
  }

  private void showInlineError(String message) {
    statusLabel.setForeground(HumanSlTrainingStyle.WARNING);
    setStatusText(message == null ? "" : message);
  }

  private void setModelStatusText(String value) {
    modelStatusLabel.setText(value);
    modelStatusLabel.setFont(
        HumanSlTrainingStyle.fontForText(
            value, Font.BOLD, Math.max(12, Config.frameFontSize - 1)));
  }

  private void setStatusText(String value) {
    statusLabel.setText(value);
    statusLabel.setFont(
        HumanSlTrainingStyle.fontForText(
            value, Font.PLAIN, Math.max(11, Config.frameFontSize - 2)));
  }

  private void applyChoiceFont(JComponent component) {
    Object value =
        component instanceof javax.swing.JComboBox
            ? ((javax.swing.JComboBox<?>) component).getSelectedItem()
            : "";
    component.setFont(
        HumanSlTrainingStyle.fontForText(
            value == null ? "" : value.toString(),
            Font.PLAIN,
            Math.max(12, Config.frameFontSize)));
  }

  private AnalysisEngineCommandHelper.Result resolveAnalysisCommand() {
    String command = Lizzie.config == null ? "" : Lizzie.config.analysisEngineCommand;
    return AnalysisEngineCommandHelper.resolveHumanSlCommand(command);
  }

  private void closeDialog() {
    closeRequested = true;
    if (downloadSession != null) {
      downloadSession.cancel();
    }
    if (session.state() == HumanSlTrainingSession.State.PREPARING) {
      session.setState(HumanSlTrainingSession.State.IDLE);
    }
    HumanSlAnalysisRunner preparing = preparingRunner;
    preparingRunner = null;
    foregroundAnalysisPause.restore();
    foregroundAnalysisPause = ForegroundAnalysisPause.inactive();
    finishEngineStartup(preparing);
    if (preparing != null) {
      Thread closer =
          new Thread(
              () -> {
                try {
                  preparing.close();
                } catch (Exception ignored) {
                }
              },
              "humansl-coach-prepare-cancel");
      closer.setDaemon(true);
      closer.start();
    }
    cancelled = true;
    setVisible(false);
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
  }

  public boolean isCancelled() {
    return cancelled;
  }

  public void selectFromCurrentPosition() {
    if (fromCurrentBox.isEnabled()) {
      fromCurrentBox.setSelected(true);
    }
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
