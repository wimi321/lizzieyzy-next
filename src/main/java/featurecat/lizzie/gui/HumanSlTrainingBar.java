package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ResourceBundle;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;

/** Main-window controls for an active AI coaching game. */
public final class HumanSlTrainingBar extends JPanel {
  private static final long serialVersionUID = 1L;

  private final ResourceBundle resources = Lizzie.resourceBundle;
  private final JLabel opponentLabel = new JFontLabel();
  private final JLabel turnLabel = new JFontLabel();
  private final JLabel humanClockLabel = new JFontLabel();
  private final JLabel aiClockLabel = new JFontLabel();
  private final JProgressBar thinkingBar = new JProgressBar();
  private final JFontButton passButton = new JFontButton();
  private final JFontButton retryButton = new JFontButton();
  private final JFontButton finishButton = new JFontButton();
  private final Timer refreshTimer;
  private HumanSlGameController controller;

  public HumanSlTrainingBar() {
    setName("humanSlTrainingBar");
    setLayout(new BorderLayout(16, 0));
    setBackground(new Color(30, 42, 42));
    setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
    setVisible(false);
    add(buildIdentity(), BorderLayout.WEST);
    add(buildStatus(), BorderLayout.CENTER);
    add(buildActions(), BorderLayout.EAST);
    refreshTimer = new Timer(500, event -> refreshFromController());
    refreshTimer.setRepeats(true);
    AccessibilitySupport.applyToTree(this);
  }

  public void attach(HumanSlGameController value) {
    controller = value;
    setVisible(value != null && !value.isFinished());
    if (isVisible()) {
      refreshFromController();
      refreshTimer.start();
    } else {
      refreshTimer.stop();
    }
  }

  public void detach(HumanSlGameController value) {
    if (value != null && controller != value) {
      return;
    }
    controller = null;
    refreshTimer.stop();
    setVisible(false);
  }

  public void requestPrimaryFocus() {
    finishButton.requestFocusInWindow();
  }

  private JPanel buildIdentity() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setOpaque(false);
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.WEST;
    String captionText = text("HumanSlTraining.bar.opponent", "Opponent");
    JLabel caption = new JFontLabel(captionText);
    caption.setForeground(new Color(174, 193, 190));
    caption.setFont(font(captionText, Font.PLAIN, 11));
    panel.add(caption, c);
    c.gridy = 1;
    opponentLabel.setForeground(Color.WHITE);
    opponentLabel.setFont(font("", Font.BOLD, 14));
    panel.add(opponentLabel, c);
    return panel;
  }

  private JPanel buildStatus() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setOpaque(false);
    GridBagConstraints c = new GridBagConstraints();
    c.gridy = 0;
    c.anchor = GridBagConstraints.CENTER;
    c.insets = new Insets(0, 12, 0, 12);
    c.gridx = 0;
    humanClockLabel.setForeground(Color.WHITE);
    humanClockLabel.setFont(font("", Font.BOLD, 13));
    panel.add(humanClockLabel, c);
    c.gridx = 1;
    c.weightx = 1.0;
    c.fill = GridBagConstraints.HORIZONTAL;
    turnLabel.setHorizontalAlignment(JLabel.CENTER);
    turnLabel.setForeground(new Color(213, 236, 230));
    turnLabel.setFont(font("", Font.BOLD, 13));
    panel.add(turnLabel, c);
    c.gridx = 2;
    c.weightx = 0.0;
    c.fill = GridBagConstraints.NONE;
    aiClockLabel.setForeground(Color.WHITE);
    aiClockLabel.setFont(font("", Font.BOLD, 13));
    panel.add(aiClockLabel, c);
    c.gridx = 0;
    c.gridy = 1;
    c.gridwidth = 3;
    c.weightx = 1.0;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(3, 12, 0, 12);
    thinkingBar.setIndeterminate(true);
    thinkingBar.setBorderPainted(false);
    thinkingBar.setForeground(HumanSlTrainingStyle.ACCENT);
    thinkingBar.setBackground(new Color(64, 82, 80));
    thinkingBar.setPreferredSize(new Dimension(240, 3));
    panel.add(thinkingBar, c);
    return panel;
  }

  private JPanel buildActions() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    panel.setOpaque(false);
    passButton.setText(text("HumanSlGame.btn.pass", "Pass"));
    retryButton.setText(text("HumanSlTraining.retryAi", "Retry AI"));
    finishButton.setText(text("HumanSlTraining.finishReview", "Finish and review on board"));
    HumanSlTrainingStyle.styleSecondary(passButton);
    HumanSlTrainingStyle.stylePrimary(retryButton);
    HumanSlTrainingStyle.styleDanger(finishButton);
    passButton.addActionListener(event -> withController(HumanSlGameController::humanPass));
    retryButton.addActionListener(event -> withController(HumanSlGameController::retryAiMove));
    finishButton.addActionListener(
        event ->
            withController(
                active -> {
                  if (active.isStopRequested()) {
                    active.abort();
                  } else if (active.isReviewing()) {
                    active.abort();
                  } else {
                    active.finishAndReturnToBoard();
                  }
                }));
    panel.add(passButton);
    panel.add(retryButton);
    panel.add(finishButton);
    return panel;
  }

  private void refreshFromController() {
    HumanSlGameController active = controller;
    if (active == null || active.isFinished()) {
      attach(null);
      return;
    }
    setLabelText(opponentLabel, active.opponentLabel(), Font.BOLD, 14);
    String status;
    if (active.isExitRecoveryPending()) {
      status =
          text(
              "HumanSlTraining.bar.cleanupRetry",
              "Cleanup needs attention. Retry before starting another mode.");
    } else if (active.isStopRequested()) {
      status = text("HumanSlTraining.bar.stopping", "Stopping AI Coach safely...");
    } else if (active.isReviewing()) {
      status = text("HumanSlTraining.bar.reviewing", "Preparing the training report...");
    } else if (active.hasAiFailure()) {
      status = text("HumanSlTraining.bar.aiFailed", "AI did not respond. Retry or finish.");
    } else if (active.isAiThinking()) {
      status = text("HumanSlTraining.bar.aiThinking", "AI is thinking");
    } else {
      status = text("HumanSlTraining.bar.yourTurn", "Your turn");
    }
    setLabelText(turnLabel, status, Font.BOLD, 13);
    setLabelText(
        humanClockLabel,
        text("HumanSlTraining.bar.you", "You") + "  " + format(active.humanElapsedMillis()),
        Font.BOLD,
        13);
    setLabelText(
        aiClockLabel,
        text("HumanSlTraining.bar.ai", "AI") + "  " + format(active.aiElapsedMillis()),
        Font.BOLD,
        13);
    thinkingBar.setVisible(
        active.isAiThinking() || active.isReviewing() || active.isExitInProgress());
    retryButton.setVisible(
        !active.isStopRequested() && active.hasAiFailure() && !active.isReviewing());
    passButton.setEnabled(
        !active.isStopRequested() && active.isHumanTurn() && !active.isReviewing());
    String finishKey;
    String finishFallback;
    if (active.isExitRecoveryPending()) {
      finishKey = "HumanSlTraining.retryCleanup";
      finishFallback = "Retry cleanup";
    } else if (active.isStopRequested()) {
      finishKey = "HumanSlTraining.stopping";
      finishFallback = "Stopping...";
    } else if (active.isReviewing()) {
      finishKey = "HumanSlTraining.stopReview";
      finishFallback = "Stop and return";
    } else {
      finishKey = "HumanSlTraining.finishReview";
      finishFallback = "Finish and review on board";
    }
    finishButton.setText(text(finishKey, finishFallback));
    finishButton.setEnabled(!active.isExitInProgress());
    revalidate();
    repaint();
  }

  private void withController(java.util.function.Consumer<HumanSlGameController> action) {
    HumanSlGameController active = controller;
    if (active != null && !active.isFinished()) {
      action.accept(active);
    }
  }

  private void setLabelText(JLabel label, String value, int style, int size) {
    label.setText(value);
    label.setFont(font(value, style, size));
  }

  private Font font(String value, int style, int size) {
    return HumanSlTrainingStyle.fontForText(value, style, size);
  }

  private String text(String key, String fallback) {
    try {
      return resources.getString(key);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static String format(long millis) {
    long seconds = Math.max(0L, millis / 1000L);
    return String.format("%02d:%02d", seconds / 60L, seconds % 60L);
  }
}
