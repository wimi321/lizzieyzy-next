package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.SyncDecisionTrace;
import featurecat.lizzie.analysis.SyncDiagnosticsEnvironment;
import featurecat.lizzie.analysis.SyncDiagnosticsExporter;
import featurecat.lizzie.analysis.SyncDiagnosticsRecorder;
import featurecat.lizzie.analysis.SyncDiagnosticsReport;
import featurecat.lizzie.analysis.SyncDiagnosticsSnapshot;
import featurecat.lizzie.analysis.YikeSessionDiagnosticsSnapshot;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;

public class SyncDiagnosticsDialog extends JDialog {
  private static final long serialVersionUID = -5489515334083853604L;
  private static final int DEFAULT_WIDTH = 760;
  private static final int DEFAULT_HEIGHT = 520;
  private static final Insets FOOTER_BUTTON_INSETS = new Insets(4, 14, 4, 14);
  private static final int FOOTER_BUTTON_EXTRA_WIDTH = 16;
  private static final int FOOTER_BUTTON_EXTRA_HEIGHT = 6;

  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private final JTextArea overviewTextArea = createSectionTextArea(2);
  private final JTextArea readBoardTextArea = createSectionTextArea(9);
  private final JTextArea yikeTextArea = createSectionTextArea(9);
  private final JTextArea latestDecisionTextArea = createSectionTextArea(9);
  private final JTextArea analysisTextArea = createSectionTextArea(6);
  private final JTextArea statusTextArea = createStatusTextArea();
  private String copySummaryText = "";

  public SyncDiagnosticsDialog(Window owner) {
    super(owner);
    setTitle(text("SyncDiagnostics.title", "Sync diagnostics"));
    setModalityType(ModalityType.MODELESS);
    if (Lizzie.frame != null) {
      setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    }
    setDefaultCloseOperation(HIDE_ON_CLOSE);

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
    JButton refreshButton = new JButton(text("SyncDiagnostics.refresh", "Refresh"));
    JButton copyButton = new JButton(text("SyncDiagnostics.copy", "Copy summary"));
    JButton exportButton = new JButton(text("SyncDiagnostics.export", "Export package"));
    JButton closeButton = new JButton(text("SyncDiagnostics.close", "Close"));
    configureFooterButton(refreshButton);
    configureFooterButton(copyButton);
    configureFooterButton(exportButton);
    configureFooterButton(closeButton);
    buttonPanel.add(refreshButton);
    buttonPanel.add(copyButton);
    buttonPanel.add(exportButton);
    buttonPanel.add(closeButton);

    refreshButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            refreshSummary();
          }
        });
    copyButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            copySummary();
          }
        });
    exportButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            exportDiagnosticsPackage();
          }
        });
    closeButton.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            setVisible(false);
          }
        });

    JPanel sectionsPanel =
        createSectionsPanel(
            readBoardTextArea,
            yikeTextArea,
            latestDecisionTextArea,
            analysisTextArea,
            text("SyncDiagnostics.section.readBoard", "ReadBoard / sync status"),
            text("SyncDiagnostics.section.yike", "Yike session / placement geometry"),
            text("SyncDiagnostics.section.latestDecision", "Latest sync decision"),
            text("SyncDiagnostics.section.analysis", "Analysis resume / snapshot state"));

    JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
    mainPanel.add(
        sectionPanel(text("SyncDiagnostics.section.overview", "Overview"), overviewTextArea),
        BorderLayout.NORTH);
    mainPanel.add(sectionsPanel, BorderLayout.CENTER);

    JPanel footerPanel = new JPanel(new BorderLayout(4, 4));
    footerPanel.add(statusTextArea, BorderLayout.CENTER);
    footerPanel.add(buttonPanel, BorderLayout.SOUTH);

    JPanel content = new JPanel(new BorderLayout(8, 8));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    content.add(new JScrollPane(mainPanel), BorderLayout.CENTER);
    content.add(footerPanel, BorderLayout.SOUTH);
    setContentPane(content);

    refreshSummary();
    setSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    setLocationRelativeTo(owner);
  }

  private void refreshSummary() {
    SectionTexts sections = sectionTexts(SyncDiagnosticsRecorder.getDefault().snapshot());
    copySummaryText = sections.copySummary;
    overviewTextArea.setText(sections.overview);
    readBoardTextArea.setText(sections.readBoard);
    yikeTextArea.setText(sections.yike);
    latestDecisionTextArea.setText(sections.latestDecision);
    analysisTextArea.setText(sections.analysis);
    resetCaret(overviewTextArea);
    resetCaret(readBoardTextArea);
    resetCaret(yikeTextArea);
    resetCaret(latestDecisionTextArea);
    resetCaret(analysisTextArea);
    setStatus("");
  }

  private void copySummary() {
    Toolkit.getDefaultToolkit()
        .getSystemClipboard()
        .setContents(new StringSelection(copySummaryText), null);
  }

  private void exportDiagnosticsPackage() {
    try {
      Path zip =
          new SyncDiagnosticsExporter(SyncDiagnosticsExporter.defaultOutputDirectory())
              .export(SyncDiagnosticsRecorder.getDefault().exportSnapshot());
      refreshSummary();
      setStatus(exportSuccessStatus(text("SyncDiagnostics.exportSuccess", "Exported to:"), zip));
    } catch (IOException | RuntimeException ex) {
      setStatus(
          text("SyncDiagnostics.exportFailure", "Export failed:")
              + " "
              + sanitizeFailureMessage(ex.getMessage()));
    }
  }

  private void setStatus(String status) {
    statusTextArea.setText(status == null || status.isEmpty() ? " " : status);
    resetCaret(statusTextArea);
  }

  private static String sanitizeFailureMessage(String message) {
    return SyncDiagnosticsEnvironment.sanitizePath(message);
  }

  static String exportSuccessStatus(String label, Path zip) {
    return label + " " + displayExportPath(zip);
  }

  static SectionTexts sectionTexts(SyncDiagnosticsReport report) {
    SyncDiagnosticsReport value = report == null ? SyncDiagnosticsReport.empty() : report;
    SyncDiagnosticsSnapshot sync = value.getSyncSnapshot();
    YikeSessionDiagnosticsSnapshot yike = value.getYikeSnapshot();
    SyncDecisionTrace decision = value.getLatestDecisionTrace();
    return new SectionTexts(
        overviewText(value),
        readBoardText(sync),
        yike.toSummaryText(),
        decision.toSummaryText(),
        analysisText(sync, decision),
        value.toSummaryText());
  }

  private static String overviewText(SyncDiagnosticsReport report) {
    return "capturedAtMillis: "
        + report.getCapturedAtMillis()
        + '\n'
        + "source: "
        + report.getSource();
  }

  private static String readBoardText(SyncDiagnosticsSnapshot sync) {
    StringBuilder text = new StringBuilder();
    text.append("attached: ").append(sync.isReadBoardAttached()).append('\n');
    text.append("connected: ").append(sync.isReadBoardConnected()).append('\n');
    text.append("javaReadBoard: ").append(sync.isJavaReadBoard()).append('\n');
    text.append("usePipe: ").append(sync.isUsePipe()).append('\n');
    text.append("syncing: ").append(sync.isSyncing()).append('\n');
    text.append("awaitingFirstSyncFrame: ").append(sync.isAwaitingFirstSyncFrame()).append('\n');
    text.append("pendingRemoteContext: ")
        .append(sync.getPendingRemoteContextSummary())
        .append('\n');
    text.append("lastProtocolLine: ").append(sync.getLastProtocolLineSummary()).append('\n');
    text.append("lastProtocolTimestampMillis: ")
        .append(sync.getLastProtocolTimestampMillis())
        .append('\n');
    text.append("source: ").append(sync.getSource()).append('\n');
    text.append("timestampMillis: ").append(sync.getTimestampMillis()).append('\n');
    text.append("summary: ").append(sync.getSummary());
    return text.toString();
  }

  private static String analysisText(SyncDiagnosticsSnapshot sync, SyncDecisionTrace decision) {
    StringBuilder text = new StringBuilder();
    text.append("hasResumeState: ").append(sync.hasResumeState()).append('\n');
    text.append("hasLastResolvedSnapshotNode: ")
        .append(sync.hasLastResolvedSnapshotNode())
        .append('\n');
    text.append("lastResolvedSnapshot: ")
        .append(sync.getLastResolvedSnapshotSummary())
        .append('\n');
    text.append("syncAnalysisEpoch: ").append(sync.getSyncAnalysisEpoch()).append('\n');
    text.append("shouldResumeAnalysis: ").append(decision.shouldResumeAnalysis()).append('\n');
    text.append("resolvedSnapshotMoveNumber: ")
        .append(decision.getResolvedSnapshotMoveNumber())
        .append('\n');
    text.append("resolvedSnapshotKind: ").append(decision.getResolvedSnapshotKind());
    return text.toString();
  }

  private static JTextArea createSectionTextArea(int rows) {
    JTextArea area = new JTextArea(rows, 32);
    area.setEditable(false);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    area.setLineWrap(true);
    area.setWrapStyleWord(false);
    area.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
    return area;
  }

  private static JTextArea createStatusTextArea() {
    JTextArea area = createSectionTextArea(1);
    area.setText(" ");
    return area;
  }

  private static JPanel sectionPanel(String title, JTextArea textArea) {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(BorderFactory.createTitledBorder(title));
    panel.add(textArea, BorderLayout.CENTER);
    return panel;
  }

  static JPanel createSectionsPanel(
      JTextArea readBoard,
      JTextArea yike,
      JTextArea latestDecision,
      JTextArea analysis,
      String readBoardTitle,
      String yikeTitle,
      String latestDecisionTitle,
      String analysisTitle) {
    JPanel sectionsPanel = new JPanel(new GridLayout(0, 2, 8, 8));
    sectionsPanel.add(sectionPanel(readBoardTitle, readBoard));
    sectionsPanel.add(sectionPanel(yikeTitle, yike));
    sectionsPanel.add(sectionPanel(latestDecisionTitle, latestDecision));
    sectionsPanel.add(sectionPanel(analysisTitle, analysis));
    return sectionsPanel;
  }

  private static void resetCaret(JTextArea textArea) {
    textArea.setCaretPosition(0);
  }

  static void configureFooterButton(JButton button) {
    button.setUI(new StableFooterButtonUI());
    button.setFocusable(true);
    button.setFocusPainted(true);
    button.setRolloverEnabled(false);
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setOpaque(false);
    button.setMargin(FOOTER_BUTTON_INSETS);
    button.setBorder(new EmptyBorder(FOOTER_BUTTON_INSETS));
    Dimension size = footerButtonSize(button);
    button.setMinimumSize(size);
    button.setPreferredSize(size);
  }

  private static Dimension footerButtonSize(JButton button) {
    FontMetrics metrics = button.getFontMetrics(button.getFont());
    Insets margin = button.getMargin();
    Dimension preferred = button.getPreferredSize();
    int width =
        metrics.stringWidth(button.getText())
            + margin.left
            + margin.right
            + FOOTER_BUTTON_EXTRA_WIDTH;
    int height = metrics.getHeight() + margin.top + margin.bottom + FOOTER_BUTTON_EXTRA_HEIGHT;
    return new Dimension(Math.max(preferred.width, width), Math.max(preferred.height, height));
  }

  static final class StableFooterButtonUI extends BasicButtonUI {
    @Override
    public void update(Graphics graphics, JComponent component) {
      paint(graphics, component);
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
      g.setColor(buttonBackground(button));
      g.fillRect(0, 0, component.getWidth(), component.getHeight());
      g.setColor(buttonBorder(button));
      g.drawRect(0, 0, component.getWidth() - 1, component.getHeight() - 1);
      if (button.isFocusPainted() && button.hasFocus()) {
        g.setColor(focusBorder());
        g.drawRect(2, 2, component.getWidth() - 5, component.getHeight() - 5);
      }
      g.dispose();
      super.paint(graphics, component);
    }

    @Override
    protected void paintButtonPressed(Graphics graphics, AbstractButton button) {}

    private Color buttonBackground(AbstractButton button) {
      Color base = UIManager.getColor("Button.background");
      if (base == null) {
        base = new Color(240, 240, 240);
      }
      if (!button.isEnabled()) {
        return blend(base, Color.WHITE, 0.45f);
      }
      if (button.getModel().isArmed() || button.getModel().isPressed()) {
        return blend(base, Color.GRAY, 0.16f);
      }
      return base;
    }

    private Color buttonBorder(AbstractButton button) {
      Color border = UIManager.getColor("Button.shadow");
      if (border == null) {
        border = new Color(150, 150, 150);
      }
      return button.isEnabled() ? border : blend(border, Color.WHITE, 0.45f);
    }

    private Color focusBorder() {
      Color focus = UIManager.getColor("Focus.color");
      return focus == null ? new Color(80, 130, 210) : focus;
    }

    private Color blend(Color a, Color b, float ratio) {
      float inverse = 1.0f - ratio;
      return new Color(
          Math.round(a.getRed() * inverse + b.getRed() * ratio),
          Math.round(a.getGreen() * inverse + b.getGreen() * ratio),
          Math.round(a.getBlue() * inverse + b.getBlue() * ratio));
    }
  }

  static final class SectionTexts {
    final String overview;
    final String readBoard;
    final String yike;
    final String latestDecision;
    final String analysis;
    final String copySummary;

    private SectionTexts(
        String overview,
        String readBoard,
        String yike,
        String latestDecision,
        String analysis,
        String copySummary) {
      this.overview = overview;
      this.readBoard = readBoard;
      this.yike = yike;
      this.latestDecision = latestDecision;
      this.analysis = analysis;
      this.copySummary = copySummary;
    }
  }

  private static String displayExportPath(Path zip) {
    String projectRelative = projectRelativeExportPath(zip);
    return projectRelative == null ? sanitizeExportPath(zip) : projectRelative;
  }

  private static String projectRelativeExportPath(Path zip) {
    if (zip == null) {
      return null;
    }
    Path normalized = zip.toAbsolutePath().normalize();
    int nameCount = normalized.getNameCount();
    for (int i = 0; i < nameCount; i++) {
      String name = normalized.getName(i).toString();
      if ("lizzieyzy-next".equalsIgnoreCase(name)) {
        return normalized.subpath(i, nameCount).toString();
      }
      if ("sync-diagnostics".equalsIgnoreCase(name) && i > 0) {
        String parentName = normalized.getName(i - 1).toString();
        if (parentName.toLowerCase().startsWith("lizzieyzy-next")) {
          return normalized.subpath(i - 1, nameCount).toString();
        }
      }
    }
    return null;
  }

  private static String sanitizeExportPath(Path zip) {
    if (zip == null) {
      return "unknown";
    }
    String sanitized = SyncDiagnosticsEnvironment.sanitizePath(zip.toString());
    Path fileName = zip.getFileName();
    if (fileName == null
        || fileName.toString().isEmpty()
        || sanitized.endsWith(fileName.toString())) {
      return sanitized;
    }
    String separator = sanitized.contains("\\") ? "\\" : "/";
    return sanitized + separator + fileName;
  }

  private String text(String key, String fallback) {
    try {
      if (resourceBundle != null) {
        return resourceBundle.getString(key);
      }
    } catch (MissingResourceException ignored) {
    }
    return fallback;
  }
}
