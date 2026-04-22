package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.RemoteWeightInfo;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

public class KataGoAutoSetupDialog extends JDialog {
  private static final Color OK_COLOR = new Color(0, 120, 64);
  private static final Color WARN_COLOR = new Color(190, 105, 0);
  private static final Color ERROR_COLOR = new Color(170, 42, 42);
  private static final Color INFO_BG = new Color(248, 249, 251);
  private static final Color INFO_BORDER = new Color(224, 228, 234);

  private SetupSnapshot snapshot;
  private List<RemoteWeightInfo> remoteWeightInfos = Collections.emptyList();
  private volatile DownloadSession activeDownloadSession;
  private volatile Thread activeWorkerThread;

  private final JLabel lblEngineValue = new JFontLabel();
  private final JLabel lblWeightValue = new JFontLabel();
  private final JLabel lblWeightModelValue = new JFontLabel();
  private final JLabel lblConfigValue = new JFontLabel();
  private final JLabel lblNvidiaRuntimeValue = new JFontLabel();
  private final JLabel lblBenchmarkValue = new JFontLabel();
  private final JLabel lblRemoteDetailValue = new JFontLabel();
  private final JLabel lblStatus = new JFontLabel();
  private final JPanel progressPanel = new JPanel(new BorderLayout(0, 6));
  private final JLabel progressStatusLabel = new JFontLabel();
  private final JFontComboBox<RemoteWeightInfo> cmbRemoteWeights =
      new JFontComboBox<RemoteWeightInfo>();
  private final JProgressBar progressBar = new JProgressBar();
  private final JFontButton btnRefresh = new JFontButton();
  private final JFontButton btnAutoSetup = new JFontButton();
  private final JFontButton btnDownloadWeight = new JFontButton();
  private final JFontButton btnInstallNvidiaRuntime = new JFontButton();
  private final JFontButton btnOptimizePerformance = new JFontButton();
  private final JFontButton btnStopDownload = new JFontButton();
  private final JFontButton btnClose = new JFontButton();

  public KataGoAutoSetupDialog(Window owner) {
    super(owner);
    setModal(false);
    setTitle(text("AutoSetup.title"));
    setSize(920, 620);
    setMinimumSize(new Dimension(880, 560));
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setLocationRelativeTo(owner);
    setAlwaysOnTop(owner instanceof LizzieFrame && ((LizzieFrame) owner).isAlwaysOnTop());
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            closeOrCancelActiveTask();
          }
        });

    JPanel content = new JPanel(new BorderLayout(0, 12));
    content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
    setContentPane(content);

    JFontLabel description =
        new JFontLabel("<html>" + text("AutoSetup.description").replace("\n", "<br>") + "</html>");
    content.add(description, BorderLayout.NORTH);

    JPanel infoPanel = new JPanel(new GridBagLayout());
    JScrollPane infoScrollPane = new JScrollPane(infoPanel);
    infoScrollPane.setBorder(null);
    infoScrollPane.getViewport().setOpaque(false);
    infoScrollPane.setOpaque(false);
    content.add(infoScrollPane, BorderLayout.CENTER);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.anchor = GridBagConstraints.NORTHWEST;
    gbc.insets = new Insets(0, 0, 8, 10);

    addInfoRow(infoPanel, gbc, text("AutoSetup.localEngine"), lblEngineValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.localWeight"), lblWeightValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.localWeightModel"), lblWeightModelValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.localConfig"), lblConfigValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.nvidiaRuntime"), lblNvidiaRuntimeValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.performance"), lblBenchmarkValue);

    cmbRemoteWeights.setMaximumRowCount(18);
    cmbRemoteWeights.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label =
                (JLabel)
                    super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
            if (value instanceof RemoteWeightInfo) {
              label.setText(formatRemoteChoice((RemoteWeightInfo) value));
            }
            return label;
          }
        });
    cmbRemoteWeights.addActionListener(e -> updateSelectedRemoteWeightInfo());
    addComponentRow(infoPanel, gbc, text("AutoSetup.officialWeights"), cmbRemoteWeights);
    addInfoRow(infoPanel, gbc, text("AutoSetup.selectedWeightInfo"), lblRemoteDetailValue);
    addInfoRow(infoPanel, gbc, text("AutoSetup.currentStatus"), lblStatus);

    JPanel bottomPanel = new JPanel(new BorderLayout(0, 10));
    content.add(bottomPanel, BorderLayout.SOUTH);

    progressPanel.setOpaque(true);
    progressPanel.setBackground(new Color(255, 248, 232));
    progressPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(230, 190, 122)),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    progressPanel.setVisible(false);
    progressStatusLabel.setForeground(WARN_COLOR);
    progressStatusLabel.setText("");
    progressBar.setStringPainted(true);
    progressBar.setPreferredSize(new Dimension(10, 24));
    progressBar.setMinimumSize(new Dimension(10, 22));
    progressPanel.add(progressStatusLabel, BorderLayout.NORTH);
    progressPanel.add(progressBar, BorderLayout.CENTER);
    bottomPanel.add(progressPanel, BorderLayout.NORTH);

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    bottomPanel.add(buttonPanel, BorderLayout.SOUTH);

    btnRefresh.setText(text("AutoSetup.refresh"));
    btnAutoSetup.setText(text("AutoSetup.autoSetup"));
    btnDownloadWeight.setText(text("AutoSetup.downloadWeight"));
    btnInstallNvidiaRuntime.setText(text("AutoSetup.installNvidiaRuntime"));
    btnOptimizePerformance.setText(text("AutoSetup.optimizePerformance"));
    btnStopDownload.setText(text("AutoSetup.stopDownload"));
    btnClose.setText(text("AutoSetup.close"));

    btnRefresh.addActionListener(e -> refreshState());
    btnAutoSetup.addActionListener(e -> autoSetupOrDownload());
    btnDownloadWeight.addActionListener(e -> startRecommendedWeightDownload(false));
    btnInstallNvidiaRuntime.addActionListener(e -> startNvidiaRuntimeInstall());
    btnOptimizePerformance.addActionListener(e -> startPerformanceBenchmark());
    btnStopDownload.addActionListener(e -> stopActiveDownload());
    btnClose.addActionListener(e -> closeOrCancelActiveTask());

    buttonPanel.add(btnRefresh);
    buttonPanel.add(btnInstallNvidiaRuntime);
    buttonPanel.add(btnDownloadWeight);
    buttonPanel.add(btnOptimizePerformance);
    buttonPanel.add(btnStopDownload);
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
    styleInfoLabel(valueLabel);
    addComponentRow(panel, gbc, title, valueLabel);
  }

  private void addComponentRow(
      JPanel panel, GridBagConstraints gbc, String title, JComponent valueComponent) {
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
    panel.add(valueComponent, valueConstraints);

    gbc.gridy += 1;
  }

  private void styleInfoLabel(JLabel valueLabel) {
    valueLabel.setOpaque(true);
    valueLabel.setBackground(INFO_BG);
    valueLabel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(INFO_BORDER),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
  }

  private void renderSnapshot() {
    setInfoValue(lblEngineValue, snapshot.hasEngine(), formatPath(snapshot.enginePath));
    setInfoValue(lblWeightValue, snapshot.hasWeight(), formatWeight(snapshot));
    setInfoValue(lblWeightModelValue, snapshot.hasWeight(), formatWeightModel(snapshot));
    setInfoValue(lblConfigValue, snapshot.hasConfigs(), formatConfig(snapshot));
    updateNvidiaRuntimeInfo();
    updateBenchmarkInfo();
    lblRemoteDetailValue.setText(text("AutoSetup.loadingRemote"));
    lblRemoteDetailValue.setToolTipText(null);
    lblRemoteDetailValue.setForeground(Color.DARK_GRAY);

    if (snapshot.hasEngine() && snapshot.hasConfigs() && snapshot.hasWeight()) {
      lblStatus.setText(text("AutoSetup.ready"));
      lblStatus.setForeground(OK_COLOR);
    } else if (!snapshot.hasWeight()) {
      lblStatus.setText(text("AutoSetup.needWeight"));
      lblStatus.setForeground(WARN_COLOR);
    } else {
      lblStatus.setText(text("AutoSetup.needSetup"));
      lblStatus.setForeground(ERROR_COLOR);
    }
  }

  private void setInfoValue(JLabel label, boolean ok, String value) {
    label.setText(value);
    label.setToolTipText(value);
    label.setForeground(ok ? OK_COLOR : ERROR_COLOR);
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

  private String formatWeightModel(SetupSnapshot state) {
    if (state.activeWeightPath == null) {
      return text("AutoSetup.notFound");
    }
    String actualModelName = KataGoAutoSetupHelper.resolveActiveWeightModelName(state);
    if (actualModelName == null || actualModelName.trim().isEmpty()) {
      return state.activeWeightPath.getFileName().toString();
    }
    String activeFile = state.activeWeightPath.getFileName().toString();
    if (actualModelName.equalsIgnoreCase(activeFile)) {
      return actualModelName;
    }
    return actualModelName;
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

  private void updateNvidiaRuntimeInfo() {
    KataGoRuntimeHelper.NvidiaRuntimeStatus status =
        snapshot == null ? null : KataGoRuntimeHelper.inspectNvidiaRuntime(snapshot);
    if (status == null || !status.applicable) {
      lblNvidiaRuntimeValue.setText(text("AutoSetup.nvidiaRuntimeNotApplicable"));
      lblNvidiaRuntimeValue.setToolTipText(null);
      lblNvidiaRuntimeValue.setForeground(Color.DARK_GRAY);
      btnInstallNvidiaRuntime.setEnabled(false);
      return;
    }
    lblNvidiaRuntimeValue.setText(status.detailText);
    lblNvidiaRuntimeValue.setToolTipText(status.detailText);
    lblNvidiaRuntimeValue.setForeground(status.ready ? OK_COLOR : WARN_COLOR);
    btnInstallNvidiaRuntime.setEnabled(activeDownloadSession == null && !status.ready);
  }

  private void updateBenchmarkInfo() {
    if (snapshot == null
        || !snapshot.hasEngine()
        || !snapshot.hasConfigs()
        || !snapshot.hasWeight()) {
      lblBenchmarkValue.setText(text("AutoSetup.benchmarkUnavailable"));
      lblBenchmarkValue.setToolTipText(null);
      lblBenchmarkValue.setForeground(ERROR_COLOR);
      btnOptimizePerformance.setEnabled(false);
      return;
    }
    KataGoRuntimeHelper.BenchmarkResult result = KataGoRuntimeHelper.getStoredBenchmarkResult();
    if (result == null) {
      lblBenchmarkValue.setText(text("AutoSetup.benchmarkMissing"));
      lblBenchmarkValue.setToolTipText(null);
      lblBenchmarkValue.setForeground(WARN_COLOR);
    } else {
      lblBenchmarkValue.setText(KataGoRuntimeHelper.formatBenchmarkResult(result));
      lblBenchmarkValue.setToolTipText(result.summary);
      lblBenchmarkValue.setForeground(OK_COLOR);
    }
    btnOptimizePerformance.setEnabled(activeWorkerThread == null && activeDownloadSession == null);
  }

  private void loadRemoteWeightInfo() {
    new Thread(
            () -> {
              try {
                List<RemoteWeightInfo> fetched = KataGoAutoSetupHelper.fetchOfficialWeights();
                SwingUtilities.invokeLater(() -> showRemoteWeightInfo(fetched));
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      remoteWeightInfos = Collections.emptyList();
                      cmbRemoteWeights.setModel(new DefaultComboBoxModel<RemoteWeightInfo>());
                      cmbRemoteWeights.setEnabled(false);
                      lblRemoteDetailValue.setText(text("AutoSetup.remoteUnavailable"));
                      lblRemoteDetailValue.setToolTipText(e.getMessage());
                      lblRemoteDetailValue.setForeground(ERROR_COLOR);
                      btnDownloadWeight.setEnabled(false);
                      btnStopDownload.setEnabled(false);
                    });
              }
            },
            "katago-remote-weight-info")
        .start();
  }

  private void showRemoteWeightInfo(List<RemoteWeightInfo> infos) {
    remoteWeightInfos = infos == null ? Collections.<RemoteWeightInfo>emptyList() : infos;
    DefaultComboBoxModel<RemoteWeightInfo> model = new DefaultComboBoxModel<RemoteWeightInfo>();
    for (RemoteWeightInfo info : remoteWeightInfos) {
      model.addElement(info);
    }
    cmbRemoteWeights.setModel(model);
    cmbRemoteWeights.setEnabled(model.getSize() > 0);
    RemoteWeightInfo preferred = choosePreferredRemoteWeight();
    if (preferred != null) {
      cmbRemoteWeights.setSelectedItem(preferred);
    }
    updateSelectedRemoteWeightInfo();
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
    final RemoteWeightInfo targetInfo = getSelectedRemoteWeight();
    if (targetInfo == null) {
      Utils.showMsg(text("AutoSetup.noRemoteWeights"), this);
      return;
    }

    final DownloadSession session = new DownloadSession();
    activeDownloadSession = session;
    setBusy(true, text("AutoSetup.downloading"), 0, -1);
    Thread worker =
        new Thread(
            () -> {
              try {
                Path downloadedWeight =
                    KataGoAutoSetupHelper.downloadWeight(
                        targetInfo,
                        (statusText, downloadedBytes, totalBytes) ->
                            SwingUtilities.invokeLater(
                                () ->
                                    setBusy(
                                        true,
                                        text("AutoSetup.downloading") + " " + statusText,
                                        downloadedBytes,
                                        totalBytes)),
                        session);
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
                      selectRemoteWeightByModelName(
                          KataGoAutoSetupHelper.resolveActiveWeightModelName(refreshed));
                      updateSelectedRemoteWeightInfo();
                      Utils.showMsg(
                          text("AutoSetup.downloadDoneMessage") + "\n" + downloadedWeight, this);
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(() -> onDownloadCancelled());
              } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onBackgroundError(e));
              } finally {
                clearActiveDownload(session, Thread.currentThread());
              }
            },
            autoApplyAfterDownload ? "katago-download-and-setup" : "katago-download-weight");
    activeWorkerThread = worker;
    worker.start();
  }

  private void startNvidiaRuntimeInstall() {
    if (snapshot == null || !snapshot.hasEngine()) {
      Utils.showMsg(text("AutoSetup.missingEngine"), this);
      return;
    }
    KataGoRuntimeHelper.NvidiaRuntimeStatus status =
        KataGoRuntimeHelper.inspectNvidiaRuntime(snapshot);
    if (!status.applicable) {
      Utils.showMsg(text("AutoSetup.nvidiaRuntimeNotApplicable"), this);
      return;
    }
    if (status.ready) {
      Utils.showMsg(text("AutoSetup.nvidiaRuntimeAlreadyReady"), this);
      return;
    }
    try {
      KataGoRuntimeHelper.ensureBundledRuntimeReady(snapshot.enginePath, this);
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
      renderSnapshot();
      updateSelectedRemoteWeightInfo();
      Utils.showMsg(text("AutoSetup.installNvidiaRuntimeDoneMessage"), this);
    } catch (IOException e) {
      onBackgroundError(e);
    }
  }

  private void startPerformanceBenchmark() {
    if (snapshot == null
        || !snapshot.hasEngine()
        || !snapshot.hasConfigs()
        || !snapshot.hasWeight()) {
      Utils.showMsg(text("AutoSetup.benchmarkUnavailable"), this);
      return;
    }

    final DownloadSession session = new DownloadSession();
    activeDownloadSession = session;
    final boolean analysisWasPondering = KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
    btnStopDownload.setText(text("AutoSetup.stopBenchmark"));
    setBusy(true, text("AutoSetup.benchmarkPreparing"), 0, 1000);
    Thread worker =
        new Thread(
            () -> {
              try {
                SetupSnapshot currentSnapshot = snapshot;
                KataGoRuntimeHelper.NvidiaRuntimeStatus runtimeStatus =
                    KataGoRuntimeHelper.inspectNvidiaRuntime(currentSnapshot);
                if (runtimeStatus.applicable && !runtimeStatus.ready) {
                  KataGoRuntimeHelper.ensureBundledRuntimeReady(currentSnapshot.enginePath, this);
                  currentSnapshot = KataGoAutoSetupHelper.inspectLocalSetup();
                }
                SetupSnapshot benchmarkSnapshot = currentSnapshot;
                KataGoRuntimeHelper.BenchmarkResult result =
                    KataGoRuntimeHelper.runBenchmarkAndApply(
                        benchmarkSnapshot,
                        (statusText, downloadedBytes, totalBytes) ->
                            SwingUtilities.invokeLater(
                                () ->
                                    setBusy(
                                        true,
                                        text("AutoSetup.benchmarking") + " " + statusText,
                                        downloadedBytes,
                                        totalBytes)),
                        session);
                applyBenchmarkToRunningEngine(result);
                SwingUtilities.invokeLater(
                    () -> {
                      setBusy(false, text("AutoSetup.benchmarkDone"), 0, 0);
                      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
                      renderSnapshot();
                      updateSelectedRemoteWeightInfo();
                      Utils.showMsg(
                          text("AutoSetup.benchmarkDoneMessage")
                              + "\n"
                              + KataGoRuntimeHelper.formatBenchmarkResult(result),
                          this);
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(() -> onBenchmarkCancelled());
              } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onBackgroundError(e));
              } finally {
                KataGoRuntimeHelper.restoreAnalysisAfterBenchmark(analysisWasPondering);
                clearActiveDownload(session, Thread.currentThread());
                SwingUtilities.invokeLater(
                    () -> btnStopDownload.setText(text("AutoSetup.stopDownload")));
              }
            },
            "katago-performance-benchmark");
    activeWorkerThread = worker;
    worker.start();
  }

  private void startAutoSetup(SetupSnapshot state) {
    setBusy(true, text("AutoSetup.settingUp"), 0, -1);
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
    selectRemoteWeightByModelName(KataGoAutoSetupHelper.resolveActiveWeightModelName(snapshot));
    updateSelectedRemoteWeightInfo();
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
    renderSnapshot();
    Utils.showMsg(text("AutoSetup.failed") + "\n" + e.getMessage(), this);
  }

  private void onDownloadCancelled() {
    setBusy(false, text("AutoSetup.downloadCancelled"), 0, 0);
    renderSnapshot();
    lblStatus.setText(text("AutoSetup.downloadCancelled"));
    lblStatus.setForeground(WARN_COLOR);
  }

  private void onBenchmarkCancelled() {
    setBusy(false, text("AutoSetup.benchmarkCancelled"), 0, 0);
    renderSnapshot();
    lblStatus.setText(text("AutoSetup.benchmarkCancelled"));
    lblStatus.setForeground(WARN_COLOR);
  }

  private void closeOrCancelActiveTask() {
    if (activeDownloadSession != null) {
      stopActiveDownload();
    }
    setVisible(false);
  }

  private void applyBenchmarkToRunningEngine(KataGoRuntimeHelper.BenchmarkResult result) {
    KataGoRuntimeHelper.applyBenchmarkResultToRunningEngines(result);
  }

  private void setBusy(boolean busy, String statusText, long downloadedBytes, long totalBytes) {
    if (statusText == null || statusText.trim().isEmpty()) {
      statusText = busy ? text("AutoSetup.benchmarking") : "";
    }
    lblStatus.setText(statusText);
    lblStatus.setForeground(busy ? WARN_COLOR : Color.DARK_GRAY);
    progressStatusLabel.setText(statusText);
    progressStatusLabel.setForeground(busy ? WARN_COLOR : Color.DARK_GRAY);
    btnRefresh.setEnabled(!busy);
    btnAutoSetup.setEnabled(!busy);
    btnDownloadWeight.setEnabled(!busy && getSelectedRemoteWeight() != null);
    btnInstallNvidiaRuntime.setEnabled(!busy && canInstallNvidiaRuntime());
    btnOptimizePerformance.setEnabled(!busy && canRunBenchmark());
    btnStopDownload.setEnabled(busy && activeDownloadSession != null);
    btnClose.setEnabled(true);

    progressPanel.setVisible(busy);
    progressBar.setIndeterminate(busy && totalBytes <= 0);
    if (!busy) {
      progressBar.setIndeterminate(false);
      progressBar.setValue(0);
      progressBar.setString("");
    } else if (totalBytes > 0) {
      progressBar.setMaximum(1000);
      progressBar.setValue((int) Math.min(1000, (downloadedBytes * 1000L) / totalBytes));
      long percent = Math.min(100, (downloadedBytes * 100L) / totalBytes);
      if (isBenchmarkPermilleProgress(statusText, downloadedBytes, totalBytes)) {
        progressBar.setString(statusText + "  " + percent + "%");
      } else {
        progressBar.setString(
            statusText
                + "  "
                + percent
                + "%  "
                + formatSize(downloadedBytes)
                + " / "
                + formatSize(totalBytes));
      }
    } else if (downloadedBytes > 0) {
      progressBar.setValue(0);
      progressBar.setString(statusText + "  " + formatSize(downloadedBytes));
    } else {
      progressBar.setValue(0);
      progressBar.setString(statusText);
    }

    progressPanel.revalidate();
    progressPanel.repaint();
    progressStatusLabel.repaint();
    progressBar.repaint();
    Container parent = progressPanel.getParent();
    if (parent != null) {
      parent.revalidate();
      parent.repaint();
    }
    getContentPane().revalidate();
    getContentPane().repaint();
    if (busy && isShowing()) {
      progressPanel.paintImmediately(progressPanel.getVisibleRect());
      lblStatus.paintImmediately(lblStatus.getVisibleRect());
      progressBar.paintImmediately(progressBar.getVisibleRect());
    }
  }

  private boolean canInstallNvidiaRuntime() {
    if (snapshot == null || !snapshot.hasEngine()) {
      return false;
    }
    KataGoRuntimeHelper.NvidiaRuntimeStatus status =
        KataGoRuntimeHelper.inspectNvidiaRuntime(snapshot);
    return status.applicable && !status.ready;
  }

  private boolean canRunBenchmark() {
    return snapshot != null
        && snapshot.hasEngine()
        && snapshot.hasConfigs()
        && snapshot.hasWeight();
  }

  private boolean isBenchmarkPermilleProgress(
      String statusText, long downloadedBytes, long totalBytes) {
    return totalBytes == 1000L && downloadedBytes >= 0L && downloadedBytes <= 1000L;
  }

  private String formatSize(long bytes) {
    if (bytes <= 0) {
      return "0 MB";
    }
    double mb = bytes / 1024.0 / 1024.0;
    if (mb >= 100) {
      return String.format("%.0f MB", mb);
    }
    return String.format("%.1f MB", mb);
  }

  private String formatRemoteChoice(RemoteWeightInfo info) {
    if (info == null) {
      return text("AutoSetup.remoteUnavailable");
    }
    return info.typeLabel + "  |  " + info.modelName;
  }

  private void updateSelectedRemoteWeightInfo() {
    RemoteWeightInfo info = getSelectedRemoteWeight();
    if (info == null) {
      lblRemoteDetailValue.setText(text("AutoSetup.remoteUnavailable"));
      lblRemoteDetailValue.setToolTipText(null);
      lblRemoteDetailValue.setForeground(ERROR_COLOR);
      btnDownloadWeight.setEnabled(false);
      return;
    }
    StringBuilder detail = new StringBuilder();
    detail.append(info.fileName());
    if (info.uploadedAt != null && !info.uploadedAt.trim().isEmpty()) {
      detail.append("  |  ").append(info.uploadedAt.trim());
    }
    if (info.eloRating != null && !info.eloRating.trim().isEmpty()) {
      detail.append("  |  ").append(info.eloRating.trim());
    }
    if (matchesCurrentWeight(info)) {
      detail.append("  |  ").append(text("AutoSetup.currentlyUsing"));
    }
    lblRemoteDetailValue.setText(detail.toString());
    lblRemoteDetailValue.setToolTipText(info.downloadUrl);
    lblRemoteDetailValue.setForeground(matchesCurrentWeight(info) ? OK_COLOR : Color.DARK_GRAY);
    btnDownloadWeight.setEnabled(activeDownloadSession == null);
  }

  private RemoteWeightInfo getSelectedRemoteWeight() {
    Object selected = cmbRemoteWeights.getSelectedItem();
    return selected instanceof RemoteWeightInfo ? (RemoteWeightInfo) selected : null;
  }

  private RemoteWeightInfo choosePreferredRemoteWeight() {
    if (remoteWeightInfos.isEmpty()) {
      return null;
    }
    String currentModel = KataGoAutoSetupHelper.resolveActiveWeightModelName(snapshot);
    if (currentModel != null && !currentModel.trim().isEmpty()) {
      for (RemoteWeightInfo info : remoteWeightInfos) {
        if (matchesModelName(info, currentModel)) {
          return info;
        }
      }
    }
    for (RemoteWeightInfo info : remoteWeightInfos) {
      if (info.recommended) {
        return info;
      }
    }
    return remoteWeightInfos.get(0);
  }

  private void selectRemoteWeightByModelName(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) {
      return;
    }
    for (int i = 0; i < cmbRemoteWeights.getItemCount(); i++) {
      RemoteWeightInfo item = cmbRemoteWeights.getItemAt(i);
      if (matchesModelName(item, modelName)) {
        cmbRemoteWeights.setSelectedIndex(i);
        return;
      }
    }
  }

  private boolean matchesCurrentWeight(RemoteWeightInfo info) {
    return matchesModelName(info, KataGoAutoSetupHelper.resolveActiveWeightModelName(snapshot));
  }

  private boolean matchesModelName(RemoteWeightInfo info, String modelName) {
    if (info == null || modelName == null || modelName.trim().isEmpty()) {
      return false;
    }
    String normalizedModel = modelName.trim();
    return info.modelName.equalsIgnoreCase(normalizedModel)
        || info.fileName().equalsIgnoreCase(normalizedModel);
  }

  private void stopActiveDownload() {
    DownloadSession session = activeDownloadSession;
    if (session == null) {
      return;
    }
    btnStopDownload.setEnabled(false);
    setBusy(true, text("AutoSetup.cancelling"), 0, -1);
    session.cancel();
    Thread worker = activeWorkerThread;
    if (worker != null) {
      worker.interrupt();
    }
  }

  private void clearActiveDownload(DownloadSession session, Thread workerThread) {
    if (activeDownloadSession == session) {
      activeDownloadSession = null;
    }
    if (activeWorkerThread == workerThread) {
      activeWorkerThread = null;
    }
  }

  private String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }
}
