package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

public class KataGoAutoSetupDialog extends JDialog {
  private SetupSnapshot snapshot;
  private RemoteWeightInfo remoteWeightInfo;

  private final JLabel lblEngineValue = new JFontLabel();
  private final JLabel lblWeightValue = new JFontLabel();
  private final JLabel lblConfigValue = new JFontLabel();
  private final JLabel lblRemoteValue = new JFontLabel();
  private final JLabel lblStatus = new JFontLabel();
  private final JProgressBar progressBar = new JProgressBar();
  private final JFontButton btnRefresh = new JFontButton();
  private final JFontButton btnAutoSetup = new JFontButton();
  private final JFontButton btnDownloadWeight = new JFontButton();
  private final JFontButton btnClose = new JFontButton();

  public KataGoAutoSetupDialog(Window owner) {
    super(owner);
    setModal(false);
    setTitle(text("AutoSetup.title"));
    setSize(720, 340);
    setMinimumSize(new Dimension(680, 320));
    setLocationRelativeTo(owner);
    setAlwaysOnTop(owner instanceof LizzieFrame && ((LizzieFrame) owner).isAlwaysOnTop());

    JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
    setContentPane(content);

    JFontLabel description =
        new JFontLabel("<html>" + text("AutoSetup.description").replace("\n", "<br>") + "</html>");
    content.add(description, BorderLayout.NORTH);

    JPanel infoPanel = new JPanel(new GridBagLayout());
    content.add(infoPanel, BorderLayout.CENTER);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.anchor = GridBagConstraints.NORTHWEST;
    gbc.insets = new Insets(0, 0, 8, 10);

    addInfoRow(infoPanel, gbc, text("AutoSetup.localEngine"), lblEngineValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.localWeight"), lblWeightValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.localConfig"), lblConfigValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.recommendedWeight"), lblRemoteValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.currentStatus"), lblStatus);

    JPanel bottomPanel = new JPanel(new BorderLayout(0, 10));
    content.add(bottomPanel, BorderLayout.SOUTH);

    progressBar.setStringPainted(true);
    progressBar.setVisible(false);
    bottomPanel.add(progressBar, BorderLayout.NORTH);

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

    btnRefresh.setText(text("AutoSetup.refresh"));
    btnAutoSetup.setText(text("AutoSetup.autoSetup"));
    btnDownloadWeight.setText(text("AutoSetup.downloadWeight"));
    btnClose.setText(text("AutoSetup.close"));

    btnRefresh.addActionListener(e -> refreshState());
    btnAutoSetup.addActionListener(e -> autoSetupOrDownload());
    btnDownloadWeight.addActionListener(e -> startRecommendedWeightDownload(false));
    btnClose.addActionListener(e -> setVisible(false));

    buttonPanel.add(btnRefresh);
    buttonPanel.add(btnDownloadWeight);
    buttonPanel.add(btnAutoSetup);
    buttonPanel.add(btnClose);

    refreshState();
  }

  public void refreshState() {
    snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    renderSnapshot();
    loadRemoteWeightInfo();
  }

  public void startRecommendedWeightDownload() {
    startRecommendedWeightDownload(false);
  }

  private void addInfoRow(JPanel panel, GridBagConstraints gbc, String title, JLabel valueLabel) {
    GridBagConstraints labelConstraints = (GridBagConstraints) gbc.clone();
    labelConstraints.gridx = 0;
    labelConstraints.weightx = 0;
    labelConstraints.fill = GridBagConstraints.NONE;
    JFontLabel titleLabel = new JFontLabel(title);
    panel.add(titleLabel, labelConstraints);

    GridBagConstraints valueConstraints = (GridBagConstraints) gbc.clone();
    valueConstraints.gridx = 1;
    valueConstraints.weightx = 1;
    valueConstraints.fill = GridBagConstraints.HORIZONTAL;
    valueLabel.setOpaque(true);
    valueLabel.setBackground(new Color(248, 249, 251));
    valueLabel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(224, 228, 234)),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
    panel.add(valueLabel, valueConstraints);

    gbc.gridy += 1;
  }

  private void renderSnapshot() {
    setInfoValue(lblEngineValue, snapshot.hasEngine(), formatPath(snapshot.enginePath));
    setInfoValue(lblWeightValue, snapshot.hasWeight(), formatWeight(snapshot));
    setInfoValue(lblConfigValue, snapshot.hasConfigs(), formatConfig(snapshot));
    lblRemoteValue.setText(text("AutoSetup.loadingRemote"));
    lblRemoteValue.setToolTipText(null);
    lblRemoteValue.setForeground(Color.DARK_GRAY);

    if (snapshot.hasEngine() && snapshot.hasConfigs() && snapshot.hasWeight()) {
      lblStatus.setText(text("AutoSetup.ready"));
      lblStatus.setForeground(new Color(0, 120, 64));
    } else if (!snapshot.hasWeight()) {
      lblStatus.setText(text("AutoSetup.needWeight"));
      lblStatus.setForeground(new Color(190, 105, 0));
    } else {
      lblStatus.setText(text("AutoSetup.needSetup"));
      lblStatus.setForeground(new Color(170, 42, 42));
    }
  }

  private void setInfoValue(JLabel label, boolean ok, String value) {
    label.setText(value);
    label.setToolTipText(value);
    label.setForeground(ok ? new Color(0, 120, 64) : new Color(170, 42, 42));
  }

  private String formatPath(Path path) {
    if (path == null) {
      return text("AutoSetup.notFound");
    }
    return path.getFileName() + "  |  " + path.toAbsolutePath().normalize();
  }

  private String formatWeight(SetupSnapshot state) {
    if (state.activeWeightPath == null) {
      return text("AutoSetup.notFound");
    }
    String extra =
        state.weightCandidates.size() > 1
            ? text("AutoSetup.weightCandidates") + state.weightCandidates.size()
            : text("AutoSetup.weightCandidates") + 1;
    return formatPath(state.activeWeightPath) + "  |  " + extra;
  }

  private String formatConfig(SetupSnapshot state) {
    if (state.gtpConfigPath == null) {
      return text("AutoSetup.notFound");
    }
    return state.gtpConfigPath.getFileName()
        + " / "
        + (state.analysisConfigPath != null
            ? state.analysisConfigPath.getFileName()
            : state.gtpConfigPath.getFileName())
        + "  |  "
        + state.gtpConfigPath.getParent();
  }

  private void loadRemoteWeightInfo() {
    new Thread(
            () -> {
              try {
                RemoteWeightInfo fetched = KataGoAutoSetupHelper.fetchRecommendedWeight();
                SwingUtilities.invokeLater(() -> showRemoteWeightInfo(fetched));
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      remoteWeightInfo = null;
                      lblRemoteValue.setText(text("AutoSetup.remoteUnavailable"));
                      lblRemoteValue.setToolTipText(e.getMessage());
                      lblRemoteValue.setForeground(new Color(170, 42, 42));
                    });
              }
            },
            "katago-remote-weight-info")
        .start();
  }

  private void showRemoteWeightInfo(RemoteWeightInfo info) {
    remoteWeightInfo = info;
    String uploadedAt =
        info.uploadedAt == null || info.uploadedAt.trim().isEmpty()
            ? ""
            : "  |  " + info.uploadedAt;
    lblRemoteValue.setText(info.typeLabel + "  |  " + info.fileName() + uploadedAt);
    lblRemoteValue.setToolTipText(info.downloadUrl);
    lblRemoteValue.setForeground(new Color(0, 120, 64));
  }

  private void autoSetupOrDownload() {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    if (!snapshot.hasWeight()) {
      int result =
          JOptionPane.showConfirmDialog(
              this,
              text("AutoSetup.askDownloadWeight"),
              text("AutoSetup.title"),
              JOptionPane.YES_NO_OPTION,
              JOptionPane.QUESTION_MESSAGE);
      if (result == JOptionPane.YES_OPTION) {
        startRecommendedWeightDownload(true);
      }
      return;
    }
    startAutoSetup(snapshot.withActiveWeight(snapshot.activeWeightPath));
  }

  private void startRecommendedWeightDownload(boolean autoApplyAfterDownload) {
    setBusy(true, text("AutoSetup.downloading"), -1, -1);
    new Thread(
            () -> {
              try {
                RemoteWeightInfo info =
                    remoteWeightInfo != null
                        ? remoteWeightInfo
                        : KataGoAutoSetupHelper.fetchRecommendedWeight();
                Path downloadedWeight =
                    KataGoAutoSetupHelper.downloadWeight(
                        info,
                        (statusText, downloadedBytes, totalBytes) ->
                            SwingUtilities.invokeLater(
                                () -> setBusy(true, statusText, downloadedBytes, totalBytes)));
                SetupSnapshot refreshed =
                    KataGoAutoSetupHelper.inspectLocalSetup().withActiveWeight(downloadedWeight);
                if (autoApplyAfterDownload) {
                  SetupResult result = KataGoAutoSetupHelper.applyAutoSetup(refreshed);
                  SwingUtilities.invokeLater(
                      () -> {
                        setBusy(false, text("AutoSetup.downloadDone"), 0, 0);
                        onSetupApplied(result, text("AutoSetup.downloadAndSetupDone"));
                      });
                  return;
                }
                SwingUtilities.invokeLater(
                    () -> {
                      setBusy(false, text("AutoSetup.downloadDone"), 0, 0);
                      snapshot = refreshed;
                      renderSnapshot();
                      Utils.showMsg(
                          text("AutoSetup.downloadDoneMessage") + "\n" + downloadedWeight, this);
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onBackgroundError(e));
              }
            },
            autoApplyAfterDownload ? "katago-download-and-setup" : "katago-download-weight")
        .start();
  }

  private void startAutoSetup(SetupSnapshot state) {
    setBusy(true, text("AutoSetup.settingUp"), -1, -1);
    new Thread(
            () -> {
              try {
                SetupResult result = KataGoAutoSetupHelper.applyAutoSetup(state);
                SwingUtilities.invokeLater(
                    () -> {
                      setBusy(false, text("AutoSetup.setupDone"), 0, 0);
                      onSetupApplied(result, text("AutoSetup.setupDoneMessage"));
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onBackgroundError(e));
              }
            },
            "katago-auto-setup")
        .start();
  }

  private void onSetupApplied(SetupResult result, String message) {
    snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    renderSnapshot();
    reloadRunningEngine(result.engineIndex);
    Utils.showMsg(message + "\n" + result.snapshot.activeWeightPath, this);
  }

  private void reloadRunningEngine(int engineIndex) {
    if (Lizzie.engineManager == null) {
      return;
    }
    try {
      Lizzie.engineManager.updateEngines();
      if (engineIndex >= 0
          && (EngineManager.isEmpty || EngineManager.currentEngineNo != engineIndex)) {
        Lizzie.engineManager.switchEngine(engineIndex, true);
      }
      if (Lizzie.frame != null) {
        Lizzie.frame.refresh();
      }
    } catch (Exception e) {
      Utils.showMsg(text("AutoSetup.reloadFailed") + "\n" + e.getMessage(), this);
    }
  }

  private void onBackgroundError(Exception e) {
    setBusy(false, text("AutoSetup.failed"), 0, 0);
    Utils.showMsg(text("AutoSetup.failed") + "\n" + e.getMessage(), this);
  }

  private void setBusy(boolean busy, String statusText, long downloadedBytes, long totalBytes) {
    btnRefresh.setEnabled(!busy);
    btnAutoSetup.setEnabled(!busy);
    btnDownloadWeight.setEnabled(!busy);
    btnClose.setEnabled(!busy);
    progressBar.setVisible(busy || downloadedBytes > 0 || totalBytes > 0);
    progressBar.setIndeterminate(busy && totalBytes <= 0);
    if (totalBytes > 0) {
      progressBar.setMaximum(1000);
      progressBar.setValue((int) Math.min(1000, (downloadedBytes * 1000L) / totalBytes));
      progressBar.setString(
          statusText
              + "  "
              + Math.max(0, downloadedBytes / 1024 / 1024)
              + " / "
              + Math.max(0, totalBytes / 1024 / 1024)
              + " MB");
    } else {
      progressBar.setValue(0);
      progressBar.setString(statusText);
    }
  }

  private String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }
}
