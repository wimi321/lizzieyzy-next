package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.SyncDiagnosticsRecorder;
import featurecat.lizzie.logging.DiagnosticBundleExporter;
import featurecat.lizzie.logging.DiagnosticBundleRequest;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.LoggingStatus;
import featurecat.lizzie.logging.TraceScope;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

public class DiagnosticsDialog extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final Color PAPER = new Color(246, 247, 249);
  private static final Color INK = new Color(32, 36, 40);
  private static final Color MUTED = new Color(90, 96, 104);
  private static final Color HAIRLINE = new Color(221, 223, 227);

  private final LoggingRuntime runtime;
  private final Config config;
  private final DiagnosticBundleExporter exporter;
  private final Runnable titleRefresh;
  private final BooleanSupplier fullTraceConfirmer;
  private final Consumer<Path> folderOpener;
  private final JCheckBox diagnosticsEnabled = box();
  private final JCheckBox moduleEngine = box();
  private final JCheckBox moduleGtp = box();
  private final JCheckBox moduleReadBoard = box();
  private final JCheckBox moduleNetwork = box();
  private final JCheckBox fullLogsEnabled = box();
  private final JCheckBox scopeEngine = box();
  private final JCheckBox scopeReadBoard = box();
  private final JCheckBox scopeNetwork = box();
  private final JButton apply = new JFontButton(text("DiagnosticsDialog.apply", "Apply"));
  private final JButton exportDefault =
      new JFontButton(text("DiagnosticsDialog.exportDefault", "Export package"));
  private final JButton cancel =
      new JFontButton(text("DiagnosticsDialog.cancelExport", "Cancel export"));
  private final JTextArea statusArea = new JFontTextArea(2, 48);
  private final JLabel durationLabel = new JFontLabel("");
  private final JLabel estimateLabel = new JFontLabel("");
  private final JLabel logsPath = new JFontLabel("");
  private final JLabel persistenceLabel = new JFontLabel("");
  private final JPanel streamList = new JPanel();
  private final AtomicBoolean cancelExport = new AtomicBoolean();
  private final Timer durationClock = new Timer(1000, event -> refreshDuration());
  private static JDialog openDialog;
  private static DiagnosticsDialog openPanel;

  public static JDialog open(Window owner, LoggingRuntime runtime, Config config) {
    if (openDialog == null) {
      openPanel = new DiagnosticsDialog(runtime, config);
      openDialog = new JDialog(owner);
      openDialog.setTitle(text("DiagnosticsDialog.title", "Diagnostics and Logs"));
      openDialog.setModalityType(JDialog.ModalityType.MODELESS);
      openDialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
      openDialog.setContentPane(openPanel);
      openDialog.pack();
      openDialog.setMinimumSize(new Dimension(520, openDialog.getHeight()));
      openDialog.setLocationRelativeTo(owner);
    } else {
      openPanel.refreshFromRuntime();
    }
    openDialog.setVisible(true);
    openDialog.toFront();
    openPanel.refreshFromRuntime();
    return openDialog;
  }

  static void notifyRuntimeChanged() {
    if (openPanel != null) {
      openPanel.refreshFromRuntime();
    }
  }

  public DiagnosticsDialog(LoggingRuntime runtime, Config config) {
    this(
        runtime,
        config,
        new DiagnosticBundleExporter(
            DiagnosticBundleExporter.defaultOutputDirectory(runtime.logsDirectory().getParent())),
        DiagnosticsDialog::refreshFrameTitle,
        null,
        DiagnosticsDialog::openFolder);
  }

  DiagnosticsDialog(
      LoggingRuntime runtime,
      Config config,
      DiagnosticBundleExporter exporter,
      Runnable titleRefresh,
      BooleanSupplier fullTraceConfirmer,
      Consumer<Path> folderOpener) {
    super(new BorderLayout(0, 8));
    this.runtime = runtime;
    this.config = config;
    this.exporter = exporter;
    this.titleRefresh = titleRefresh == null ? () -> {} : titleRefresh;
    this.fullTraceConfirmer = fullTraceConfirmer;
    this.folderOpener = folderOpener == null ? path -> {} : folderOpener;

    setOpaque(true);
    setBackground(AppleStyleSupport.isAppleStyleEnabled() ? new Color(30, 33, 38) : PAPER);
    setBorder(new EmptyBorder(10, 12, 10, 12));

    durationLabel.setForeground(MUTED);
    estimateLabel.setForeground(MUTED);
    logsPath.setForeground(MUTED);
    persistenceLabel.setForeground(INK);
    streamList.setOpaque(false);
    streamList.setLayout(new BoxLayout(streamList, BoxLayout.Y_AXIS));

    statusArea.setEditable(false);
    statusArea.setLineWrap(true);
    statusArea.setWrapStyleWord(true);
    statusArea.setOpaque(false);
    statusArea.setBorder(new EmptyBorder(0, 2, 0, 2));
    statusArea.setForeground(MUTED);

    AppleStyleSupport.markPrimary(apply);
    AppleStyleSupport.markPrimary(exportDefault);
    cancel.setVisible(false);

    apply.addActionListener(e -> applyCurrentPlan());
    diagnosticsEnabled.addActionListener(e -> setModulesEnabled(diagnosticsEnabled.isSelected()));
    exportDefault.addActionListener(e -> exportPackageOffEdt());
    cancel.addActionListener(e -> cancelExport.set(true));

    JPanel recording = new JPanel();
    recording.setOpaque(false);
    recording.setLayout(new BoxLayout(recording, BoxLayout.Y_AXIS));
    addSection(
        recording,
        checkRow(
            text("DiagnosticsDialog.diagnosticsEnabled", "Diagnostic recording"),
            diagnosticsEnabled,
            false,
            null));
    addSection(
        recording, checkRow(text("DiagnosticsDialog.module.engine", "Engine"), moduleEngine, true, null));
    addSection(
        recording,
        checkRow(text("DiagnosticsDialog.module.gtpSummary", "GTP Summary"), moduleGtp, true, null));
    addSection(
        recording,
        checkRow(
            text("DiagnosticsDialog.module.readboardYike", "ReadBoard/Yike"),
            moduleReadBoard,
            true,
            null));
    addSection(
        recording,
        checkRow(
            text("DiagnosticsDialog.module.networkRemote", "Network/Remote"),
            moduleNetwork,
            true,
            null));

    JPanel fullLogs = new JPanel();
    fullLogs.setOpaque(false);
    fullLogs.setLayout(new BoxLayout(fullLogs, BoxLayout.Y_AXIS));
    addSection(
        fullLogs,
        checkRow(
            text("DiagnosticsDialog.fullTrace", "Full Logs"), fullLogsEnabled, false, durationLabel));
    addSection(
        fullLogs,
        checkRow(text("DiagnosticsDialog.scope.engineGtp", "Engine/GTP"), scopeEngine, true, null));
    addSection(
        fullLogs,
        checkRow(
            text("DiagnosticsDialog.scope.readboardYike", "ReadBoard/Yike"),
            scopeReadBoard,
            true,
            null));
    addSection(
        fullLogs,
        checkRow(
            text("DiagnosticsDialog.scope.networkWebsocket", "Network/WebSocket"),
            scopeNetwork,
            true,
            null));
    JLabel migration =
        new JFontLabel(
            "<html>"
                + text(
                    "DiagnosticsDialog.gtpMigrationNote",
                    "Legacy GTP file logging now provides GTP Summary. Raw GTP requires starting Full Logs.")
                + "</html>");
    migration.setForeground(MUTED);
    addSection(fullLogs, migration);

    JPanel applyRow = new JPanel(new BorderLayout());
    applyRow.setOpaque(false);
    applyRow.add(apply, BorderLayout.EAST);

    JPanel logs =
        pathRow(
            text("DiagnosticsDialog.logsFolder", "Logs"),
            logsPath,
            () -> folderOpener.accept(runtime.logsDirectory()));

    JPanel health = new JPanel(new BorderLayout(0, 4));
    health.setOpaque(false);
    health.add(persistenceLabel, BorderLayout.NORTH);
    health.add(streamList, BorderLayout.CENTER);

    JPanel north = new JPanel();
    north.setOpaque(false);
    north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
    addSection(north, recording);
    addSection(north, hairline());
    addSection(north, fullLogs);
    addSection(north, applyRow);
    addSection(north, hairline());
    addSection(north, logs);
    addSection(north, health);
    JPanel export = new JPanel(new BorderLayout(12, 6));
    export.setOpaque(false);
    export.add(estimateLabel, BorderLayout.WEST);
    export.add(wrap(exportDefault, cancel), BorderLayout.EAST);
    export.add(statusArea, BorderLayout.SOUTH);

    JPanel south = new JPanel(new BorderLayout(0, 10));
    south.setOpaque(false);
    south.add(hairline(), BorderLayout.NORTH);
    south.add(export, BorderLayout.CENTER);

    add(north, BorderLayout.NORTH);
    add(south, BorderLayout.SOUTH);
    refreshFromRuntime();
  }

  void applyCurrentPlan() {
    LoggingSettings next =
        runtime
            .settings()
            .withDiagnosticsEnabled(diagnosticsEnabled.isSelected())
            .withDiagnosticModules(selectedModules())
            .withPreferredTraceScopes(selectedScopes());
    try {
      if (config != null) {
        runtime.applySettings(next, config::saveLoggingSettings);
      } else {
        runtime.applySettings(next);
      }
      setStatus(text("DiagnosticsDialog.applied", "Applied"));
    } catch (RuntimeException e) {
      refreshFromRuntime();
      setStatus(text("DiagnosticsDialog.applyFailed", "Apply failed") + ": " + e.getMessage());
      return;
    }
    if (fullLogsEnabled.isSelected()) {
      if (!runtime.fullTraceActive()) {
        startFullTraceFromUi();
      }
    } else if (runtime.fullTraceActive()) {
      stopFullTraceFromUi();
    }
  }

  void startFullTraceFromUi() {
    if (!confirmStart()) {
      refreshFromRuntime();
      return;
    }
    LoggingSettings next = runtime.settings().withPreferredTraceScopes(selectedScopes());
    try {
      if (config != null) {
        runtime.applySettings(next, config::saveLoggingSettings);
      } else {
        runtime.applySettings(next);
      }
    } catch (RuntimeException e) {
      refreshFromRuntime();
      setStatus(text("DiagnosticsDialog.applyFailed", "Apply failed") + ": " + e.getMessage());
      return;
    }
    runtime.startFullTrace(selectedScopes());
    titleRefresh.run();
    refreshFromRuntime();
  }

  void stopFullTraceFromUi() {
    runtime.stopFullTrace();
    titleRefresh.run();
    refreshFromRuntime();
  }

  Path exportSynchronously() throws IOException {
    cancelExport.set(false);
    Path zip = exporter.export(currentRequest(), cancelExport::get);
    folderOpener.accept(zip.getParent());
    refreshEstimate();
    return zip;
  }

  DiagnosticBundleRequest currentRequest() {
    Set<TraceScope> raw =
        runtime.fullTraceActive()
            ? runtime.activeTraceScopes()
            : EnumSet.noneOf(TraceScope.class);
    return new DiagnosticBundleRequest(
        runtime,
        raw,
        config == null ? new org.json.JSONObject() : config.config,
        SyncDiagnosticsRecorder.getDefault().exportSnapshot(),
        Lizzie.nextVersion == null ? "unknown" : Lizzie.nextVersion);
  }

  String healthText() {
    return renderHealth();
  }

  String statusText() {
    return statusArea.getText();
  }

  String estimateText() {
    return estimateLabel.getText();
  }

  JCheckBox diagnosticsEnabledBox() {
    return diagnosticsEnabled;
  }

  JCheckBox fullLogsEnabledBox() {
    return fullLogsEnabled;
  }

  JButton cancelButton() {
    return cancel;
  }

  JCheckBox scopeEngineBox() {
    return scopeEngine;
  }

  String confirmBody() {
    return text(
            "DiagnosticsDialog.confirmMessage",
            "Selected scopes record game and protocol content. While this is on, exporting a package includes those full logs. Retention is 7 days and 100 MB per log class.")
        + "\n"
        + selectedScopeLabels();
  }

  void refreshFromRuntime() {
    LoggingSettings settings = runtime.settings();
    diagnosticsEnabled.setSelected(settings.diagnosticsEnabled());
    moduleEngine.setSelected(settings.diagnosticModules().contains(DiagnosticModule.ENGINE));
    moduleGtp.setSelected(settings.diagnosticModules().contains(DiagnosticModule.GTP_SUMMARY));
    moduleReadBoard.setSelected(
        settings.diagnosticModules().contains(DiagnosticModule.READBOARD_YIKE));
    moduleNetwork.setSelected(
        settings.diagnosticModules().contains(DiagnosticModule.NETWORK_REMOTE));
    fullLogsEnabled.setSelected(runtime.fullTraceActive());
    scopeEngine.setSelected(settings.preferredTraceScopes().contains(TraceScope.ENGINE_GTP));
    scopeReadBoard.setSelected(settings.preferredTraceScopes().contains(TraceScope.READBOARD_YIKE));
    scopeNetwork.setSelected(settings.preferredTraceScopes().contains(TraceScope.NETWORK_WEBSOCKET));
    setModulesEnabled(settings.diagnosticsEnabled());
    logsPath.setText(runtime.logsDirectory().toAbsolutePath().toString());
    logsPath.setToolTipText(logsPath.getText());
    LoggingStatus status = runtime.status();
    persistenceLabel.setText("persistenceEnabled=" + status.persistenceEnabled());
    streamList.removeAll();
    for (LoggingStatus.StreamStatus stream : status.streams()) {
      JFontLabel line = new JFontLabel(streamLine(stream));
      line.setForeground(stream.reason() == null ? INK : new Color(176, 64, 64));
      line.setAlignmentX(LEFT_ALIGNMENT);
      streamList.add(line);
    }
    streamList.revalidate();
    streamList.repaint();
    refreshDuration();
    refreshEstimate();
  }

  String durationText() {
    return durationLabel.getText();
  }

  private void refreshDuration() {
    Instant started = runtime.fullTraceStartedAt();
    boolean tracing = runtime.fullTraceActive() && started != null;
    durationLabel.setText(
        tracing
            ? text("DiagnosticsDialog.duration", "Duration")
                + " "
                + Duration.between(started, Instant.now()).toSeconds()
                + "s"
            : text("DiagnosticsDialog.traceOff", "Off"));
    if (tracing && isShowing()) {
      if (!durationClock.isRunning()) {
        durationClock.start();
      }
    } else if (durationClock.isRunning()) {
      durationClock.stop();
    }
  }

  private String renderHealth() {
    return text("DiagnosticsDialog.logsFolder", "Logs")
        + ": "
        + runtime.logsDirectory()
        + '\n'
        + text("DiagnosticsDialog.diagnosticsFolder", "Diagnostics")
        + ": "
        + DiagnosticBundleExporter.defaultOutputDirectory(runtime.logsDirectory().getParent())
        + '\n'
        + persistenceLabel.getText()
        + '\n'
        + renderStreams();
  }

  private String renderStreams() {
    StringBuilder body = new StringBuilder();
    for (LoggingStatus.StreamStatus stream : runtime.status().streams()) {
      body.append(streamLine(stream)).append('\n');
    }
    return body.toString();
  }

  private static String streamLine(LoggingStatus.StreamStatus stream) {
    if (stream.reason() == null) {
      return stream.stream() + "  healthy  dropped=" + stream.droppedCount();
    }
    return stream.stream()
        + "  reason="
        + stream.reason()
        + " dropped="
        + stream.droppedCount()
        + " recovered="
        + stream.recovered()
        + " first="
        + stream.firstOccurrence()
        + " last="
        + stream.lastOccurrence();
  }

  private void refreshEstimate() {
    try {
      long bytes = exporter.estimateUncompressedBytes(currentRequest());
      estimateLabel.setText(
          text("DiagnosticsDialog.estimate", "Estimated size")
              + ": "
              + String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)));
    } catch (IOException e) {
      estimateLabel.setText("");
    }
  }

  private Set<DiagnosticModule> selectedModules() {
    EnumSet<DiagnosticModule> modules = EnumSet.noneOf(DiagnosticModule.class);
    if (moduleEngine.isSelected()) {
      modules.add(DiagnosticModule.ENGINE);
    }
    if (moduleGtp.isSelected()) {
      modules.add(DiagnosticModule.GTP_SUMMARY);
    }
    if (moduleReadBoard.isSelected()) {
      modules.add(DiagnosticModule.READBOARD_YIKE);
    }
    if (moduleNetwork.isSelected()) {
      modules.add(DiagnosticModule.NETWORK_REMOTE);
    }
    return modules;
  }

  private Set<TraceScope> selectedScopes() {
    EnumSet<TraceScope> scopes = EnumSet.noneOf(TraceScope.class);
    if (scopeEngine.isSelected()) {
      scopes.add(TraceScope.ENGINE_GTP);
    }
    if (scopeReadBoard.isSelected()) {
      scopes.add(TraceScope.READBOARD_YIKE);
    }
    if (scopeNetwork.isSelected()) {
      scopes.add(TraceScope.NETWORK_WEBSOCKET);
    }
    if (scopes.isEmpty()) {
      return EnumSet.allOf(TraceScope.class);
    }
    return scopes;
  }

  private void exportPackageOffEdt() {
    cancelExport.set(false);
    DiagnosticBundleRequest request = currentRequest();
    exportDefault.setEnabled(false);
    cancel.setVisible(true);
    setStatus(text("DiagnosticsDialog.exporting", "Exporting..."));
    revalidate();
    Thread worker =
        new Thread(
            () -> {
              try {
                Path zip = exporter.export(request, cancelExport::get);
                SwingUtilities.invokeLater(
                    () -> {
                      folderOpener.accept(zip.getParent());
                      finishExport(
                          text("DiagnosticsDialog.exportSuccess", "Exported to:")
                              + " "
                              + zip.getFileName());
                    });
              } catch (Exception e) {
                SwingUtilities.invokeLater(
                    () ->
                        finishExport(
                            text("DiagnosticsDialog.exportFailure", "Export failed:")
                                + " "
                                + e.getMessage()));
              }
            },
            "diagnostic-export");
    worker.setDaemon(true);
    worker.start();
  }

  private void finishExport(String message) {
    exportDefault.setEnabled(true);
    cancel.setVisible(false);
    cancelExport.set(false);
    setStatus(message);
    refreshEstimate();
    revalidate();
  }

  private void setModulesEnabled(boolean enabled) {
    moduleEngine.setEnabled(enabled);
    moduleGtp.setEnabled(enabled);
    moduleReadBoard.setEnabled(enabled);
    moduleNetwork.setEnabled(enabled);
  }

  private void setStatus(String value) {
    statusArea.setText(value == null ? "" : value);
  }

  private static void refreshFrameTitle() {
    if (Lizzie.frame != null) {
      Lizzie.frame.updateTitle();
    }
  }

  private boolean confirmStart() {
    if (fullTraceConfirmer != null) {
      return fullTraceConfirmer.getAsBoolean();
    }
    int choice =
        JOptionPane.showConfirmDialog(
            Lizzie.frame,
            confirmBody(),
            text("DiagnosticsDialog.confirmTitle", "Start Full Logs?"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
    return choice == JOptionPane.OK_OPTION;
  }

  private String selectedScopeLabels() {
    StringBuilder labels = new StringBuilder();
    appendScopeLabel(labels, scopeEngine, "DiagnosticsDialog.scope.engineGtp", "Engine/GTP");
    appendScopeLabel(
        labels, scopeReadBoard, "DiagnosticsDialog.scope.readboardYike", "ReadBoard/Yike");
    appendScopeLabel(
        labels, scopeNetwork, "DiagnosticsDialog.scope.networkWebsocket", "Network/WebSocket");
    return labels.length() == 0
        ? text("DiagnosticsDialog.scope.engineGtp", "Engine/GTP")
            + ", "
            + text("DiagnosticsDialog.scope.readboardYike", "ReadBoard/Yike")
            + ", "
            + text("DiagnosticsDialog.scope.networkWebsocket", "Network/WebSocket")
        : labels.toString();
  }

  private static void appendScopeLabel(
      StringBuilder labels, JCheckBox box, String key, String fallback) {
    if (!box.isSelected()) {
      return;
    }
    if (labels.length() > 0) {
      labels.append(", ");
    }
    labels.append(text(key, fallback));
  }

  private static void openFolder(Path directory) {
    try {
      if (Desktop.isDesktopSupported()) {
        java.nio.file.Files.createDirectories(directory);
        Desktop.getDesktop().open(directory.toFile());
      }
    } catch (IOException ignored) {
    }
  }

  private static JCheckBox box() {
    return new JFontCheckBox("");
  }

  private static JPanel checkRow(String name, JCheckBox box, boolean child, JComponent extra) {
    JFontLabel label = new JFontLabel(name);
    if (!child) {
      label.setFont(label.getFont().deriveFont(Font.BOLD));
    }
    JPanel title = new JPanel(new BorderLayout(8, 0));
    title.setOpaque(false);
    title.add(label, BorderLayout.WEST);
    if (extra != null) {
      title.add(extra, BorderLayout.EAST);
    }
    JPanel row = new JPanel(new BorderLayout(12, 0));
    row.setOpaque(false);
    row.setBorder(new EmptyBorder(2, child ? 20 : 0, 2, 0));
    row.add(title, BorderLayout.CENTER);
    row.add(box, BorderLayout.EAST);
    return row;
  }

  private static JPanel wrap(JComponent... children) {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
    panel.setOpaque(false);
    for (JComponent child : children) {
      panel.add(child);
    }
    return panel;
  }

  private static JPanel pathRow(String name, JLabel path, Runnable open) {
    JFontButton openButton = new JFontButton(text("DiagnosticsDialog.open", "Open"));
    AppleStyleSupport.markPrimary(openButton);
    openButton.addActionListener(e -> open.run());
    JPanel top = new JPanel(new BorderLayout(8, 0));
    top.setOpaque(false);
    JFontLabel label = new JFontLabel(name);
    label.setFont(label.getFont().deriveFont(Font.BOLD));
    top.add(label, BorderLayout.WEST);
    top.add(openButton, BorderLayout.EAST);
    JPanel row = new JPanel(new BorderLayout(0, 2));
    row.setOpaque(false);
    row.add(top, BorderLayout.NORTH);
    row.add(path, BorderLayout.SOUTH);
    return row;
  }

  private static JPanel hairline() {
    JPanel line = new JPanel();
    line.setOpaque(true);
    line.setBackground(HAIRLINE);
    line.setPreferredSize(new Dimension(0, 1));
    line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
    line.setMinimumSize(new Dimension(0, 1));
    return line;
  }

  private static void addSection(JPanel parent, JComponent child) {
    child.setAlignmentX(LEFT_ALIGNMENT);
    if (parent.getComponentCount() > 0) {
      parent.add(Box.createVerticalStrut(4));
    }
    parent.add(child);
  }

  private static String text(String key, String fallback) {
    try {
      if (Lizzie.resourceBundle != null) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (MissingResourceException ignored) {
    }
    return fallback;
  }
}
