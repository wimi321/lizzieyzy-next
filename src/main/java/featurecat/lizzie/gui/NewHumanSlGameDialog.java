package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.HumanSlAnalysisRunner;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/** Lets the player configure a casual HumanSL human-vs-AI game (rank, handicap, color, time). */
public final class NewHumanSlGameDialog extends JDialog {
  private static final long serialVersionUID = 1L;

  private static final String[] RANK_PROFILES = buildRankProfiles();

  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private final JFontComboBox<String> rankBox = new JFontComboBox<String>();
  private final JFontComboBox<String> colorBox = new JFontComboBox<String>();
  private final JFontComboBox<Integer> handicapBox = new JFontComboBox<Integer>();
  private final JFontTextField komiField = new JFontTextField();
  private final JFontComboBox<String> timeBox = new JFontComboBox<String>();
  private boolean cancelled = true;

  public NewHumanSlGameDialog(Window owner) {
    super(owner);
    setTitle(resourceBundle.getString("HumanSlGame.dialog.title"));
    setModal(true);
    try {
      setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException ignored) {
    }
    setResizable(false);
    initComponents();
    pack();
    setLocationRelativeTo(owner);
  }

  private void initComponents() {
    JPanel content = new JPanel(new GridLayout(0, 2, 8, 8));
    content.setBorder(new EmptyBorder(12, 12, 12, 12));

    for (String profile : RANK_PROFILES) {
      rankBox.addItem(profile.replace("rank_", "").toUpperCase(java.util.Locale.ROOT));
    }
    rankBox.setSelectedItem("3K");

    colorBox.addItem(resourceBundle.getString("NewGameDialog.playBlack"));
    colorBox.addItem(resourceBundle.getString("NewGameDialog.playWhite"));
    colorBox.setSelectedIndex(0);

    for (int i = 0; i <= 9; i++) {
      handicapBox.addItem(i);
    }
    handicapBox.setSelectedItem(0);
    handicapBox.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            updateKomiForHandicap();
          }
        });

    komiField.setDocument(new KomiDocument(true));
    komiField.setText("7.5");

    timeBox.addItem("5");
    timeBox.addItem("10");
    timeBox.addItem("20");
    timeBox.addItem("30");
    timeBox.addItem("60");
    timeBox.setSelectedItem("10");

    content.add(new JFontLabel(resourceBundle.getString("HumanSlGame.dialog.rank")));
    content.add(rankBox);
    content.add(new JFontLabel(resourceBundle.getString("HumanSlGame.dialog.color")));
    content.add(colorBox);
    content.add(new JFontLabel(resourceBundle.getString("HumanSlGame.dialog.handicap")));
    content.add(handicapBox);
    content.add(new JFontLabel(resourceBundle.getString("HumanSlGame.dialog.komi")));
    content.add(komiField);
    content.add(new JFontLabel(resourceBundle.getString("HumanSlGame.dialog.time")));
    content.add(timeBox);

    JPanel buttons = new JPanel();
    JFontButton ok = new JFontButton(resourceBundle.getString("NewAnaGameDialog.okButton"));
    ok.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            onConfirm();
          }
        });
    JFontButton cancel = new JFontButton(resourceBundle.getString("LizzieFrame.cancel"));
    cancel.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            cancelled = true;
            setVisible(false);
          }
        });
    buttons.add(ok);
    buttons.add(cancel);

    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(content, BorderLayout.CENTER);
    getContentPane().add(buttons, BorderLayout.SOUTH);
    setMinimumSize(new Dimension(360, 260));
  }

  private void updateKomiForHandicap() {
    int handicap =
        handicapBox.getSelectedItem() == null ? 0 : (Integer) handicapBox.getSelectedItem();
    komiField.setText(handicap >= 2 ? "0" : "7.5");
  }

  private void onConfirm() {
    Path modelPath = HumanSlGameController.resolveDefaultHumanModel();
    if (modelPath == null) {
      promptDownloadMissingModel();
      return;
    }
    startConfiguredGame(modelPath);
  }

  private void promptDownloadMissingModel() {
    int choice =
        JOptionPane.showConfirmDialog(
            this,
            resourceBundle.getString("HumanSlGame.missingModelDownloadPrompt"),
            resourceBundle.getString("HumanSlGame.dialog.title"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (choice != JOptionPane.YES_OPTION) {
      return;
    }
    cancelled = true;
    setVisible(false);
    Window frame = Lizzie.frame;
    boolean wasPondering = Lizzie.leelaz != null && Lizzie.leelaz.isPondering();
    if (wasPondering) {
      // Force-stop the live analysis engine. Do NOT use togglePonder(): while a search is
      // underway (underPonder==true) it restarts analysis instead of stopping it.
      Lizzie.leelaz.notPondering();
      Lizzie.leelaz.nameCmd();
    }
    DownloadProgressDialog progressDialog = new DownloadProgressDialog(frame);
    KataGoAutoSetupHelper.DownloadSession downloadSession =
        new KataGoAutoSetupHelper.DownloadSession();
    Thread worker =
        new Thread(
            () -> {
              try {
                final Path downloadedModel =
                    KataGoAutoSetupHelper.downloadHumanSlModel(
                        (statusText, downloadedBytes, totalBytes) ->
                            SwingUtilities.invokeLater(
                                () ->
                                    progressDialog.updateProgress(
                                        statusText, downloadedBytes, totalBytes)),
                        downloadSession);
                SwingUtilities.invokeLater(
                    () -> {
                      progressDialog.dispose();
                      Utils.showMsg(resourceBundle.getString("HumanSlGame.downloadCompletePrompt"));
                      if (!startConfiguredGame(downloadedModel)) {
                        restorePondering(wasPondering);
                      }
                      dispose();
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      progressDialog.dispose();
                      restorePondering(wasPondering);
                      Utils.showMsg(e.getLocalizedMessage());
                      dispose();
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      progressDialog.dispose();
                      restorePondering(wasPondering);
                      Utils.showMsg(e.getLocalizedMessage());
                      dispose();
                    });
              }
            },
            "humansl-game-download-model");
    progressDialog.setDownloadSession(downloadSession);
    worker.start();
    progressDialog.setVisible(true);
  }

  private void restorePondering(boolean wasPondering) {
    if (wasPondering && Lizzie.leelaz != null && !Lizzie.leelaz.isPondering()) {
      Lizzie.leelaz.ponder();
    }
  }

  private boolean startConfiguredGame(Path modelPath) {
    String command = resolveAnalysisCommand();
    if (command.trim().isEmpty()) {
      Utils.showMsg(resourceBundle.getString("HumanSlGame.error.noEngine"));
      return false;
    }
    HumanSlAnalysisRunner runner = new HumanSlAnalysisRunner(command, modelPath);
    if (!runner.start()) {
      Utils.showMsg(
          java.text.MessageFormat.format(
              resourceBundle.getString("HumanSlGame.error.startFailed"),
              runner.getUnavailableReason()));
      try {
        runner.close();
      } catch (Exception ignored) {
      }
      return false;
    }

    String profile = RANK_PROFILES[Math.max(0, rankBox.getSelectedIndex())];
    boolean humanIsBlack = colorBox.getSelectedIndex() == 0;
    int handicap =
        handicapBox.getSelectedItem() == null ? 0 : (Integer) handicapBox.getSelectedItem();
    double komi = Utils.parseTextToDouble(komiField, handicap >= 2 ? 0.0 : 7.5);
    int timeSeconds = 10;
    try {
      timeSeconds = Integer.parseInt((String) timeBox.getSelectedItem());
    } catch (NumberFormatException ignored) {
    }

    HumanSlGameController controller =
        new HumanSlGameController(runner, profile, humanIsBlack, handicap, komi, timeSeconds);
    cancelled = false;
    setVisible(false);
    controller.start();
    return true;
  }

  private String resolveAnalysisCommand() {
    if (Lizzie.config == null) {
      return "";
    }
    if (!Lizzie.config.analysisEngineCommandCustomized) {
      featurecat.lizzie.util.AnalysisEngineCommandHelper.Result result =
          featurecat.lizzie.util.AnalysisEngineCommandHelper.fromDefaultEngine(
              Utils.getEngineData());
      if (result.isSuccess()) {
        Lizzie.config.analysisEngineCommand = result.getCommand();
        if (Lizzie.config.uiConfig != null) {
          Lizzie.config.uiConfig.put("analysis-engine-command", result.getCommand());
        }
        return result.getCommand();
      }
      return "";
    }
    String command = Lizzie.config.analysisEngineCommand;
    return command == null ? "" : command;
  }

  public boolean isCancelled() {
    return cancelled;
  }

  private static String[] buildRankProfiles() {
    java.util.List<String> profiles = new java.util.ArrayList<String>();
    for (int rank = 19; rank >= 1; rank--) {
      profiles.add("rank_" + rank + "k");
    }
    for (int rank = 1; rank <= 9; rank++) {
      profiles.add("rank_" + rank + "d");
    }
    return profiles.toArray(new String[0]);
  }

  private static final class DownloadProgressDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JLabel statusLabel = new JFontLabel();
    private final JProgressBar progressBar = new JProgressBar();
    private KataGoAutoSetupHelper.DownloadSession downloadSession;

    private DownloadProgressDialog(Window owner) {
      super(owner);
      setTitle(Lizzie.resourceBundle.getString("HumanSlGame.downloadProgressTitle"));
      setModal(true);
      setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
      JPanel panel = new JPanel(new BorderLayout(8, 8));
      panel.setBorder(new EmptyBorder(12, 12, 12, 12));
      statusLabel.setText(Lizzie.resourceBundle.getString("HumanSlGame.downloadProgressTitle"));
      progressBar.setStringPainted(true);
      progressBar.setIndeterminate(true);
      JPanel buttons = new JPanel();
      JFontButton backgroundButton =
          new JFontButton(Lizzie.resourceBundle.getString("HumanSlGame.downloadBackground"));
      backgroundButton.addActionListener(e -> setVisible(false));
      JFontButton cancelButton =
          new JFontButton(Lizzie.resourceBundle.getString("HumanSlGame.downloadCancel"));
      cancelButton.addActionListener(
          e -> {
            if (downloadSession != null) {
              downloadSession.cancel();
            }
            setVisible(false);
          });
      buttons.add(backgroundButton);
      buttons.add(cancelButton);
      panel.add(statusLabel, BorderLayout.NORTH);
      panel.add(progressBar, BorderLayout.CENTER);
      panel.add(buttons, BorderLayout.SOUTH);
      getContentPane().add(panel);
      setMinimumSize(new Dimension(420, 145));
      pack();
      setLocationRelativeTo(owner);
    }

    private void setDownloadSession(KataGoAutoSetupHelper.DownloadSession downloadSession) {
      this.downloadSession = downloadSession;
    }

    private void updateProgress(String statusText, long downloadedBytes, long totalBytes) {
      String base =
          (statusText == null || statusText.trim().isEmpty())
              ? Lizzie.resourceBundle.getString("HumanSlGame.downloadProgressTitle")
              : statusText;
      statusLabel.setText(base);
      if (totalBytes > 0L) {
        progressBar.setIndeterminate(false);
        progressBar.setMaximum(1000);
        progressBar.setValue((int) Math.min(1000L, downloadedBytes * 1000L / totalBytes));
        progressBar.setString(
            (downloadedBytes * 100L / totalBytes)
                + "%  "
                + formatBytes(downloadedBytes)
                + " / "
                + formatBytes(totalBytes));
      } else if (downloadedBytes > 0L) {
        progressBar.setIndeterminate(true);
        progressBar.setString(formatBytes(downloadedBytes));
      } else {
        progressBar.setIndeterminate(true);
        progressBar.setString("");
      }
    }

    private static String formatBytes(long bytes) {
      if (bytes < 1024L * 1024L) {
        return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
      }
      return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
  }
}
