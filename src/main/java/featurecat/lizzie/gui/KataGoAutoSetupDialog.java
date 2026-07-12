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
import featurecat.lizzie.util.NvidiaGpuDetector;
import featurecat.lizzie.util.Utils;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

public class KataGoAutoSetupDialog extends JDialog {
  private static final Color OK_COLOR = new Color(28, 121, 82);
  private static final Color WARN_COLOR = new Color(184, 110, 27);
  private static final Color ERROR_COLOR = new Color(172, 56, 56);
  private static final Color APP_BG = new Color(244, 241, 233);
  private static final Color CARD_BG = new Color(255, 253, 247);
  private static final Color INFO_BG = new Color(249, 247, 240);
  private static final Color INFO_BORDER = new Color(224, 218, 205);
  private static final Color SIDEBAR_BG = new Color(36, 52, 55);
  private static final Color SIDEBAR_SELECTED_BG = new Color(55, 83, 82);
  private static final Color SIDEBAR_TEXT = new Color(236, 241, 232);
  private static final Color TEXT_PRIMARY = new Color(35, 39, 36);
  private static final Color TEXT_SECONDARY = new Color(101, 106, 100);
  private static final String BENCHMARK_PROGRESS_KEY = "lizzie.benchmark.dialog.progress";
  private static final String WRAPPING_TEXT_KEY = "lizzie.autosetup.wrappingText";
  private static final String INFO_HTML_PREFIX = "<html><div style='width: 360px'>";
  private static final String INFO_HTML_SUFFIX = "</div></html>";
  private static final int MAX_INFO_TEXT_LENGTH = 104;
  private static final int GPU_INFO_TEXT_LENGTH = 132;
  private static final int DIALOG_WIDTH = 900;
  private static final int DIALOG_HEIGHT = 620;
  private static final int VALUE_COLUMN_WIDTH = 390;
  private static final long ERROR_POPUP_DEDUP_MILLIS = 5000L;
  private static final String CARD_OVERVIEW = "overview";
  private static final String CARD_WEIGHTS = "weights";
  private static final String CARD_ACCELERATION = "acceleration";
  private static final String CARD_BENCHMARK = "benchmark";

  private SetupSnapshot snapshot;
  private List<RemoteWeightInfo> remoteWeightInfos = Collections.emptyList();
  private volatile DownloadSession activeDownloadSession;
  private volatile Thread activeWorkerThread;
  private volatile NvidiaGpuDetector.DetectionResult nvidiaGpuDetection;
  private volatile boolean nvidiaGpuDetectionRunning;
  private long progressStartedAtMillis;
  private String lastBackgroundErrorMessage = "";
  private long lastBackgroundErrorMillis = 0L;
  private int selectedSetupSectionIndex = 0;

  private final JLabel lblEngineValue = new JFontLabel();
  private final JLabel lblWeightValue = new JFontLabel();
  private final JLabel lblWeightModelValue = new JFontLabel();
  private final JLabel lblConfigValue = new JFontLabel();
  private final JLabel lblNvidiaRuntimeValue = new JFontLabel();
  private final JLabel lblNvidiaGpuValue = new JFontLabel();
  private final JLabel lblTensorRtDownloadValue = new JFontLabel();
  private final JLabel lblTensorRtConfigValue = new JFontLabel();
  private final JLabel lblBenchmarkValue = new JFontLabel();
  private final JLabel lblRemoteDetailValue = new JFontLabel();
  private final JLabel lblLocalWeightDetailValue = new JFontLabel();
  private final JLabel lblHumanSlModelValue = new JFontLabel();
  private final JLabel lblStatus = new JFontLabel();
  private final JList<String> sectionNav = new JList<String>();
  private final CardLayout detailCardLayout = new CardLayout();
  private final JPanel detailCards = new JPanel(detailCardLayout);
  private final JPanel progressPanel = new JPanel(new BorderLayout(0, 6));
  private final JLabel progressStatusLabel = new JFontLabel();
  private final JLabel progressTitleLabel = new JFontLabel();
  private final JFontComboBox<RemoteWeightInfo> cmbRemoteWeights =
      new JFontComboBox<RemoteWeightInfo>();
  private final JFontComboBox<Path> cmbLocalWeights = new JFontComboBox<Path>();
  private final JProgressBar progressBar = new JProgressBar();
  private final JFontButton btnRefresh = new JFontButton();
  private final JFontButton btnAutoSetup = new JFontButton();
  private final JFontButton btnReloadRemoteWeights = new JFontButton();
  private final JFontButton btnDownloadWeight = new JFontButton();
  private final JFontButton btnImportWeight = new JFontButton();
  private final JFontButton btnAddWeight = new JFontButton();
  private final JFontButton btnDownloadHumanSlModel = new JFontButton();
  private final JFontButton btnImportHumanSlModel = new JFontButton();
  private final JFontButton btnInstallNvidiaRuntime = new JFontButton();
  private final JFontButton btnInstallTensorRt = new JFontButton();
  private final JFontButton btnSwitchBackCuda = new JFontButton();
  private final JFontButton btnCleanTensorRtCache = new JFontButton();
  private final JFontButton btnOptimizePerformance = new JFontButton();
  private final JFontButton btnStopDownload = new JFontButton();
  private final JFontButton btnClose = new JFontButton();

  public KataGoAutoSetupDialog(Window owner) {
    super(owner);
    setModal(false);
    setTitle(text("AutoSetup.title"));
    setSize(DIALOG_WIDTH, DIALOG_HEIGHT);
    setMinimumSize(new Dimension(760, 520));
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    setAlwaysOnTop(owner instanceof LizzieFrame && ((LizzieFrame) owner).isAlwaysOnTop());
    placeOnOwnerScreen(owner);
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            closeOrCancelActiveTask();
          }
        });

    configureButtons();

    JPanel content = new JPanel(new BorderLayout(0, 0));
    content.setBackground(APP_BG);
    setContentPane(content);

    cmbLocalWeights.setMaximumRowCount(12);
    cmbLocalWeights.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label =
                (JLabel)
                    super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
            if (value instanceof Path) {
              label.setText(formatLocalWeightChoice((Path) value));
            }
            return label;
          }
        });
    cmbLocalWeights.addActionListener(e -> updateSelectedLocalWeightInfo());

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

    content.add(createHeaderPanel(), BorderLayout.NORTH);

    JPanel body = new JPanel(new BorderLayout(14, 0));
    body.setOpaque(false);
    body.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
    body.add(createSidebarPanel(), BorderLayout.WEST);

    detailCards.setOpaque(false);
    detailCards.add(createOverviewSection(), CARD_OVERVIEW);
    detailCards.add(createWeightsSection(), CARD_WEIGHTS);
    detailCards.add(createBenchmarkSection(), CARD_BENCHMARK);
    detailCards.add(createAccelerationSection(), CARD_ACCELERATION);
    JScrollPane detailScrollPane = new JScrollPane(detailCards);
    detailScrollPane.setBorder(null);
    detailScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    detailScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
    detailScrollPane.getVerticalScrollBar().setUnitIncrement(14);
    detailScrollPane.getViewport().setOpaque(false);
    detailScrollPane.setOpaque(false);
    body.add(detailScrollPane, BorderLayout.CENTER);
    content.add(body, BorderLayout.CENTER);

    content.add(createFooterPanel(), BorderLayout.SOUTH);
    wireActions();

    AccessibilitySupport.progress(
        progressBar,
        text("Accessibility.autoSetupProgress"),
        text("Accessibility.autoSetupProgressDescription"));
    AccessibilitySupport.named(
        cmbRemoteWeights,
        text("Accessibility.downloadableWeights"),
        text("Accessibility.downloadableWeightsDescription"));
    AccessibilitySupport.named(
        cmbLocalWeights,
        text("Accessibility.downloadedWeights"),
        text("Accessibility.downloadedWeightsDescription"));
    AccessibilitySupport.applyToTree(content);
    AccessibilitySupport.installEscapeAction(getRootPane(), this, this::closeOrCancelActiveTask);

    refreshState();
  }

  /** Opens the dialog directly on the Weights card (nav index 1 maps to CARD_WEIGHTS). */
  public void showWeightsSection() {
    sectionNav.setSelectedIndex(1);
  }

  public void refreshState() {
    if (!nvidiaGpuDetectionRunning) {
      nvidiaGpuDetection = null;
    }
    snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    renderSnapshot();
    loadRemoteWeightInfo();
  }

  public void startRecommendedWeightDownload() {
    startRecommendedWeightDownload(false);
  }

  private void configureButtons() {
    btnRefresh.setText(text("AutoSetup.refresh"));
    btnAutoSetup.setText(text("AutoSetup.autoSetup"));
    btnReloadRemoteWeights.setText("");
    btnReloadRemoteWeights.setIcon(new RefreshIcon());
    btnReloadRemoteWeights.setToolTipText(text("AutoSetup.refreshOfficialWeights"));
    btnDownloadWeight.setText(text("AutoSetup.downloadWeight"));
    btnImportWeight.setText(text("AutoSetup.importWeight"));
    btnAddWeight.setText(text("AutoSetup.addWeight"));
    btnDownloadHumanSlModel.setText(text("AutoSetup.downloadHumanSlModel"));
    btnImportHumanSlModel.setText(text("AutoSetup.importHumanSlModel"));
    btnInstallNvidiaRuntime.setText(text("AutoSetup.installNvidiaRuntime"));
    btnInstallTensorRt.setText(text("AutoSetup.installTensorRt"));
    btnSwitchBackCuda.setText(text("AutoSetup.switchBackCuda"));
    btnCleanTensorRtCache.setText(text("AutoSetup.cleanTensorRtCache"));
    btnOptimizePerformance.setText(text("AutoSetup.optimizePerformance"));
    btnStopDownload.setText(text("AutoSetup.stopDownload"));
    btnStopDownload.setEnabled(false);
    btnClose.setText(text("AutoSetup.close"));

    styleButton(btnAutoSetup, true);
    styleButton(btnDownloadWeight, true);
    styleButton(btnOptimizePerformance, true);
    styleButton(btnRefresh, false);
    styleIconButton(btnReloadRemoteWeights);
    styleButton(btnImportWeight, false);
    styleButton(btnAddWeight, false);
    styleButton(btnDownloadHumanSlModel, false);
    styleButton(btnImportHumanSlModel, false);
    styleButton(btnInstallNvidiaRuntime, false);
    styleButton(btnInstallTensorRt, false);
    styleButton(btnSwitchBackCuda, false);
    styleButton(btnCleanTensorRtCache, false);
    styleButton(btnStopDownload, false);
    styleButton(btnClose, false);
  }

  private void wireActions() {
    btnRefresh.addActionListener(e -> refreshState());
    btnAutoSetup.addActionListener(e -> autoSetupOrDownload());
    btnReloadRemoteWeights.addActionListener(e -> reloadRemoteWeightInfo());
    btnDownloadWeight.addActionListener(e -> startRecommendedWeightDownload(false));
    btnImportWeight.addActionListener(e -> importCustomWeight());
    btnAddWeight.addActionListener(e -> addSelectedWeightEngine());
    btnDownloadHumanSlModel.addActionListener(e -> startHumanSlModelDownload());
    btnImportHumanSlModel.addActionListener(e -> importHumanSlModel());
    btnInstallNvidiaRuntime.addActionListener(e -> startNvidiaRuntimeInstall());
    btnInstallTensorRt.addActionListener(e -> startTensorRtInstall());
    btnSwitchBackCuda.addActionListener(e -> switchBackToCuda());
    btnCleanTensorRtCache.addActionListener(e -> cleanTensorRtCache());
    btnOptimizePerformance.addActionListener(e -> startPerformanceBenchmark());
    btnStopDownload.addActionListener(e -> stopActiveDownload());
    btnClose.addActionListener(e -> closeOrCancelActiveTask());
  }

  private void styleButton(JFontButton button, boolean primary) {
    if (primary) {
      AppleStyleSupport.markPrimary(button);
    }
    button.setFocusPainted(false);
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.setMargin(new Insets(0, 10, 0, 10));
    AppleStyleSupport.installButtonStyle(button);
    Dimension preferred = button.getPreferredSize();
    button.setPreferredSize(new Dimension(Math.max(preferred.width, primary ? 106 : 90), 32));
  }

  private void styleIconButton(JFontButton button) {
    button.setFocusPainted(false);
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.setMargin(new Insets(0, 0, 0, 0));
    button.setHorizontalAlignment(SwingConstants.CENTER);
    AppleStyleSupport.installButtonStyle(button);
    Dimension size = new Dimension(36, 32);
    button.setPreferredSize(size);
    button.setMinimumSize(size);
    button.setMaximumSize(size);
  }

  private JPanel createHeaderPanel() {
    JPanel header = new JPanel(new BorderLayout(12, 3));
    header.setBackground(SIDEBAR_BG);
    header.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

    JFontLabel title = new JFontLabel(text("AutoSetup.title"));
    title.setForeground(Color.WHITE);
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5f));

    JFontLabel subtitle =
        new JFontLabel(
            "<html>"
                + text("AutoSetup.expertHeader")
                + "<br>"
                + text("AutoSetup.expertSubtitle")
                + "</html>");
    subtitle.setForeground(new Color(207, 218, 205));

    JPanel titleBlock = new JPanel(new BorderLayout(0, 4));
    titleBlock.setOpaque(false);
    titleBlock.add(title, BorderLayout.NORTH);
    titleBlock.add(subtitle, BorderLayout.CENTER);
    header.add(titleBlock, BorderLayout.CENTER);

    JFontLabel modeBadge = new JFontLabel(text("AutoSetup.expertMode"));
    modeBadge.setHorizontalAlignment(SwingConstants.CENTER);
    modeBadge.setForeground(SIDEBAR_TEXT);
    modeBadge.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(103, 130, 121)),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)));
    header.add(modeBadge, BorderLayout.EAST);
    return header;
  }

  private JPanel createSidebarPanel() {
    JPanel sidebar = new JPanel(new BorderLayout(0, 12));
    sidebar.setPreferredSize(new Dimension(180, 10));
    sidebar.setBackground(SIDEBAR_BG);
    sidebar.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

    JFontLabel sidebarTitle = new JFontLabel(text("AutoSetup.sidebarTitle"));
    sidebarTitle.setForeground(SIDEBAR_TEXT);
    sidebarTitle.setFont(sidebarTitle.getFont().deriveFont(Font.BOLD));
    sidebar.add(sidebarTitle, BorderLayout.NORTH);

    sectionNav.setListData(
        new String[] {
          text("AutoSetup.navOverview"),
          text("AutoSetup.navWeights"),
          text("AutoSetup.navBenchmark"),
          text("AutoSetup.navAcceleration"),
          text("Menu.remoteCompute")
        });
    sectionNav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    sectionNav.setSelectedIndex(0);
    sectionNav.setFixedCellHeight(36);
    sectionNav.setBackground(SIDEBAR_BG);
    sectionNav.setForeground(SIDEBAR_TEXT);
    sectionNav.setBorder(null);
    sectionNav.setCellRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label =
                (JLabel)
                    super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            label.setForeground(SIDEBAR_TEXT);
            label.setBackground(isSelected ? SIDEBAR_SELECTED_BG : SIDEBAR_BG);
            label.setFont(label.getFont().deriveFont(isSelected ? Font.BOLD : Font.PLAIN));
            return label;
          }
        });
    sectionNav.addListSelectionListener(
        e -> {
          if (e.getValueIsAdjusting()) {
            return;
          }
          int selectedIndex = sectionNav.getSelectedIndex();
          switch (selectedIndex) {
            case 1:
              selectedSetupSectionIndex = selectedIndex;
              detailCardLayout.show(detailCards, CARD_WEIGHTS);
              break;
            case 2:
              selectedSetupSectionIndex = selectedIndex;
              detailCardLayout.show(detailCards, CARD_BENCHMARK);
              break;
            case 3:
              selectedSetupSectionIndex = selectedIndex;
              detailCardLayout.show(detailCards, CARD_ACCELERATION);
              break;
            case 4:
              SwingUtilities.invokeLater(
                  () -> {
                    sectionNav.setSelectedIndex(selectedSetupSectionIndex);
                    openRemoteComputeCenter();
                  });
              break;
            default:
              selectedSetupSectionIndex = 0;
              detailCardLayout.show(detailCards, CARD_OVERVIEW);
              break;
          }
        });
    sidebar.add(sectionNav, BorderLayout.CENTER);

    JFontLabel note = new JFontLabel("<html>" + text("AutoSetup.sidebarHint") + "</html>");
    note.setForeground(new Color(193, 203, 194));
    sidebar.add(note, BorderLayout.SOUTH);
    return sidebar;
  }

  private JPanel createOverviewSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addInfoRow(rows, gbc, text("AutoSetup.localEngine"), lblEngineValue);
    addInfoRow(rows, gbc, text("AutoSetup.localWeight"), lblWeightValue);
    addInfoRow(rows, gbc, text("AutoSetup.localWeightModel"), lblWeightModelValue);
    addInfoRow(rows, gbc, text("AutoSetup.localConfig"), lblConfigValue);

    JPanel actions = createActionBar(FlowLayout.RIGHT, btnRefresh, btnAutoSetup);
    return createSectionCard(
        text("AutoSetup.overviewTitle"), text("AutoSetup.overviewSubtitle"), rows, actions);
  }

  private void openRemoteComputeCenter() {
    if (Lizzie.frame != null) {
      Lizzie.frame.openRemoteComputeCenter();
      return;
    }
    RemoteComputeDialog dialog;
    try {
      dialog = new RemoteComputeDialog(JOptionPane.getFrameForComponent(this));
    } catch (IOException e) {
      JOptionPane.showMessageDialog(
          this,
          e.getMessage(),
          text("NetworkProxy.settingsTitle"),
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    dialog.setVisible(true);
    dialog.toFront();
  }

  private JPanel createWeightsSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addComponentRow(
        rows,
        gbc,
        text("AutoSetup.officialWeights"),
        createInlineActionRow(cmbRemoteWeights, btnReloadRemoteWeights, btnDownloadWeight));
    addInfoRow(rows, gbc, text("AutoSetup.selectedWeightInfo"), lblRemoteDetailValue);
    addComponentRow(
        rows,
        gbc,
        text("AutoSetup.localWeights"),
        createInlineActionRow(cmbLocalWeights, btnAddWeight, btnImportWeight));
    addInfoRow(rows, gbc, text("AutoSetup.selectedLocalWeightInfo"), lblLocalWeightDetailValue);
    addComponentRow(
        rows,
        gbc,
        text("AutoSetup.humanSlModel"),
        createInlineActionRow(
            lblHumanSlModelValue, btnDownloadHumanSlModel, btnImportHumanSlModel));

    return createSectionCard(
        text("AutoSetup.weightsTitle"), text("AutoSetup.weightsSubtitle"), rows, null);
  }

  private JPanel createAccelerationSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addInfoRow(rows, gbc, text("AutoSetup.nvidiaRuntime"), lblNvidiaRuntimeValue);
    addInfoRow(rows, gbc, text("AutoSetup.nvidiaGpu"), lblNvidiaGpuValue);
    addInfoRow(rows, gbc, text("AutoSetup.tensorRtDownloadStatus"), lblTensorRtDownloadValue);
    addInfoRow(rows, gbc, text("AutoSetup.tensorRtConfigStatus"), lblTensorRtConfigValue);

    JTextArea tensorRtHint = createHintText(text("AutoSetup.accelerationTensorRtHint"));
    addComponentRow(rows, gbc, text("AutoSetup.installTensorRt"), tensorRtHint);

    JPanel actions =
        createActionBar(
            FlowLayout.RIGHT,
            btnInstallNvidiaRuntime,
            btnInstallTensorRt,
            btnSwitchBackCuda,
            btnCleanTensorRtCache);
    return createSectionCard(
        text("AutoSetup.accelerationTitle"), text("AutoSetup.accelerationSubtitle"), rows, actions);
  }

  private JPanel createBenchmarkSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addInfoRow(rows, gbc, text("AutoSetup.performance"), lblBenchmarkValue);

    JTextArea benchmarkHint = createHintText(text("AutoSetup.benchmarkHint"));
    addComponentRow(rows, gbc, text("AutoSetup.benchmarkAbout"), benchmarkHint);

    JPanel actions = createActionBar(FlowLayout.RIGHT, btnOptimizePerformance);
    return createSectionCard(
        text("AutoSetup.benchmarkTitle"), text("AutoSetup.benchmarkSubtitle"), rows, actions);
  }

  private JPanel createFooterPanel() {
    JPanel footer = new JPanel(new BorderLayout(0, 10));
    footer.setOpaque(true);
    footer.setBackground(APP_BG);
    footer.setBorder(BorderFactory.createEmptyBorder(0, 16, 14, 16));

    progressPanel.setOpaque(true);
    progressPanel.setBackground(new Color(255, 249, 235));
    progressPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(228, 194, 127)),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    progressPanel.setVisible(false);
    progressTitleLabel.setText(text("AutoSetup.progressTitle"));
    progressTitleLabel.setForeground(TEXT_PRIMARY);
    progressTitleLabel.setFont(progressTitleLabel.getFont().deriveFont(Font.BOLD));
    progressStatusLabel.setForeground(WARN_COLOR);
    progressStatusLabel.setText("");
    progressBar.setStringPainted(true);
    progressBar.setPreferredSize(new Dimension(10, 24));
    progressBar.setMinimumSize(new Dimension(10, 22));
    JPanel progressHeader = new JPanel(new BorderLayout(10, 0));
    progressHeader.setOpaque(false);
    progressHeader.add(progressTitleLabel, BorderLayout.WEST);
    progressHeader.add(progressStatusLabel, BorderLayout.CENTER);
    progressPanel.add(progressHeader, BorderLayout.NORTH);
    progressPanel.add(progressBar, BorderLayout.CENTER);
    footer.add(progressPanel, BorderLayout.NORTH);

    JPanel statusBar = new JPanel(new BorderLayout(12, 0));
    statusBar.setOpaque(false);
    lblStatus.setOpaque(true);
    lblStatus.setBackground(CARD_BG);
    lblStatus.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(INFO_BORDER),
            BorderFactory.createEmptyBorder(7, 10, 7, 10)));
    statusBar.add(lblStatus, BorderLayout.CENTER);
    statusBar.add(createActionBar(FlowLayout.RIGHT, btnStopDownload, btnClose), BorderLayout.EAST);
    footer.add(statusBar, BorderLayout.SOUTH);
    return footer;
  }

  private JPanel createSectionCard(
      String title, String subtitle, JComponent content, JComponent actions) {
    JPanel card = new JPanel(new BorderLayout(0, 10));
    card.setOpaque(true);
    card.setBackground(CARD_BG);
    card.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(224, 217, 203)),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)));

    JPanel heading = new JPanel(new BorderLayout(0, 4));
    heading.setOpaque(false);
    JFontLabel titleLabel = new JFontLabel(title);
    titleLabel.setForeground(TEXT_PRIMARY);
    titleLabel.setFont(
        titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 2f));
    JFontLabel subtitleLabel = new JFontLabel(subtitle);
    subtitleLabel.setForeground(TEXT_SECONDARY);
    heading.add(titleLabel, BorderLayout.NORTH);
    heading.add(subtitleLabel, BorderLayout.CENTER);
    card.add(heading, BorderLayout.NORTH);

    JPanel contentStack = new JPanel(new BorderLayout(0, 10));
    contentStack.setOpaque(false);
    contentStack.add(content, BorderLayout.NORTH);
    if (actions != null) {
      contentStack.add(actions, BorderLayout.CENTER);
    }
    JPanel contentWrap = new JPanel(new BorderLayout());
    contentWrap.setOpaque(false);
    contentWrap.add(contentStack, BorderLayout.NORTH);
    card.add(contentWrap, BorderLayout.CENTER);
    return card;
  }

  private JPanel createRowsPanel() {
    JPanel rows = new JPanel(new GridBagLayout());
    rows.setOpaque(false);
    return rows;
  }

  private GridBagConstraints createRowConstraints() {
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.anchor = GridBagConstraints.NORTHWEST;
    gbc.insets = new Insets(0, 0, 7, 10);
    return gbc;
  }

  static JPanel createActionBar(int alignment, JComponent... actions) {
    JPanel actionBar = new JPanel(new BorderLayout());
    actionBar.setOpaque(false);

    JPanel actionGrid = new JPanel(new GridBagLayout());
    actionGrid.setOpaque(false);
    int columns = actions.length > 2 ? 2 : Math.max(1, actions.length);
    for (int index = 0; index < actions.length; index++) {
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridx = index % columns;
      constraints.gridy = index / columns;
      constraints.anchor = GridBagConstraints.EAST;
      constraints.insets =
          new Insets(constraints.gridy == 0 ? 0 : 6, constraints.gridx == 0 ? 0 : 6, 0, 0);
      actionGrid.add(actions[index], constraints);
    }

    actionBar.add(
        actionGrid, alignment == FlowLayout.LEFT ? BorderLayout.LINE_START : BorderLayout.LINE_END);
    return actionBar;
  }

  private JPanel createInlineActionRow(JComponent mainComponent, JComponent... actions) {
    JPanel row = new JPanel(new BorderLayout(6, 0));
    row.setOpaque(false);
    row.add(mainComponent, BorderLayout.CENTER);
    row.add(createActionBar(FlowLayout.RIGHT, actions), BorderLayout.EAST);
    return row;
  }

  private JTextArea createHintText(String message) {
    JTextArea textArea = new JTextArea(message == null ? "" : message);
    textArea.setEditable(false);
    textArea.setFocusable(false);
    textArea.setOpaque(false);
    textArea.setLineWrap(true);
    textArea.setWrapStyleWord(true);
    textArea.setForeground(TEXT_SECONDARY);
    textArea.setFont(new JFontLabel().getFont());
    textArea.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    textArea.putClientProperty(WRAPPING_TEXT_KEY, Boolean.TRUE);
    return textArea;
  }

  private void addInfoRow(JPanel panel, GridBagConstraints gbc, String title, JLabel valueLabel) {
    styleInfoLabel(valueLabel);
    addComponentRow(panel, gbc, title, valueLabel);
  }

  private void addComponentRow(
      JPanel panel, GridBagConstraints gbc, String title, JComponent valueComponent) {
    constrainValueComponent(valueComponent);
    boolean wrappingText = Boolean.TRUE.equals(valueComponent.getClientProperty(WRAPPING_TEXT_KEY));
    int rowHeight = Math.max(32, valueComponent.getPreferredSize().height);

    GridBagConstraints labelConstraints = (GridBagConstraints) gbc.clone();
    labelConstraints.gridx = 0;
    labelConstraints.weightx = 0;
    labelConstraints.fill = GridBagConstraints.HORIZONTAL;
    labelConstraints.anchor = wrappingText ? GridBagConstraints.NORTHWEST : GridBagConstraints.WEST;
    JFontLabel titleLabel = new JFontLabel(title);
    titleLabel.setForeground(TEXT_PRIMARY);
    titleLabel.setVerticalAlignment(wrappingText ? SwingConstants.TOP : SwingConstants.CENTER);
    int titleWidth = localizedRowLabelWidth(titleLabel);
    titleLabel.setPreferredSize(new Dimension(titleWidth, rowHeight));
    titleLabel.setMinimumSize(new Dimension(titleWidth, Math.min(rowHeight, 32)));
    panel.add(titleLabel, labelConstraints);

    GridBagConstraints valueConstraints = (GridBagConstraints) gbc.clone();
    valueConstraints.gridx = 1;
    valueConstraints.weightx = 1;
    valueConstraints.fill = GridBagConstraints.HORIZONTAL;
    panel.add(valueComponent, valueConstraints);

    gbc.gridy += 1;
  }

  static int localizedRowLabelWidth(JLabel label) {
    return Math.max(132, Math.min(240, label.getPreferredSize().width + 8));
  }

  private void constrainValueComponent(JComponent valueComponent) {
    Dimension preferred = valueComponent.getPreferredSize();
    int height = Math.max(preferred.height, 30);
    if (Boolean.TRUE.equals(valueComponent.getClientProperty(WRAPPING_TEXT_KEY))
        && valueComponent instanceof JTextArea) {
      height =
          Math.max(
              height,
              estimateWrappedTextHeight(
                  ((JTextArea) valueComponent).getText(),
                  valueComponent.getFontMetrics(valueComponent.getFont()),
                  VALUE_COLUMN_WIDTH));
    }
    valueComponent.setPreferredSize(new Dimension(VALUE_COLUMN_WIDTH, height));
    valueComponent.setMinimumSize(new Dimension(260, height));
  }

  private int estimateWrappedTextHeight(String text, FontMetrics metrics, int width) {
    if (metrics == null) {
      return 64;
    }
    int availableWidth = Math.max(120, width - 4);
    int lineCount = 0;
    String[] paragraphs = (text == null ? "" : text).split("\\R", -1);
    for (String paragraph : paragraphs) {
      lineCount += Math.max(1, estimateWrappedLineCount(paragraph, metrics, availableWidth));
    }
    return lineCount * metrics.getHeight() + 8;
  }

  private int estimateWrappedLineCount(String text, FontMetrics metrics, int availableWidth) {
    if (text == null || text.trim().isEmpty()) {
      return 1;
    }
    int lines = 1;
    int lineWidth = 0;
    for (int offset = 0; offset < text.length(); ) {
      int codePoint = text.codePointAt(offset);
      offset += Character.charCount(codePoint);
      int charWidth = Math.max(1, metrics.charWidth(codePoint));
      if (lineWidth > 0 && lineWidth + charWidth > availableWidth) {
        lines += 1;
        lineWidth = charWidth;
      } else {
        lineWidth += charWidth;
      }
    }
    return lines;
  }

  public void ensureVisibleOnScreen() {
    placeOnOwnerScreen(getOwner());
  }

  private void placeOnOwnerScreen(Window owner) {
    GraphicsConfiguration graphicsConfiguration =
        owner != null ? owner.getGraphicsConfiguration() : getGraphicsConfiguration();
    if (graphicsConfiguration == null) {
      graphicsConfiguration =
          GraphicsEnvironment.getLocalGraphicsEnvironment()
              .getDefaultScreenDevice()
              .getDefaultConfiguration();
    }
    Rectangle bounds = graphicsConfiguration.getBounds();
    int x = bounds.x + Math.max(24, Math.min(48, bounds.width / 20));
    int y = bounds.y + Math.max(24, Math.min(48, bounds.height / 20));
    setLocation(x, y);
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
    renderLocalWeights();
    renderHumanSlModel();
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
    label.setText(compactInfoText(value));
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
    String displayName = KataGoAutoSetupHelper.resolveActiveWeightDisplayName(state);
    if (displayName == null || displayName.trim().isEmpty()) {
      return state.activeWeightPath.getFileName().toString();
    }
    return displayName;
  }

  private void renderLocalWeights() {
    DefaultComboBoxModel<Path> model = new DefaultComboBoxModel<Path>();
    if (snapshot != null) {
      for (Path weight : snapshot.weightCandidates) {
        model.addElement(weight);
      }
    }
    cmbLocalWeights.setModel(model);
    cmbLocalWeights.setEnabled(model.getSize() > 0);
    if (snapshot != null && snapshot.activeWeightPath != null) {
      selectLocalWeight(snapshot.activeWeightPath);
    }
    updateSelectedLocalWeightInfo();
  }

  private void renderHumanSlModel() {
    KataGoAutoSetupHelper.HumanSlModelStatus status = KataGoAutoSetupHelper.inspectHumanSlModel();
    if (status.isInstalled()) {
      String value =
          status.modelPath.getFileName() + "  |  " + status.modelPath.toAbsolutePath().normalize();
      lblHumanSlModelValue.setText(compactInfoText(value));
      lblHumanSlModelValue.setToolTipText(value);
      lblHumanSlModelValue.setForeground(OK_COLOR);
      btnDownloadHumanSlModel.setText(text("AutoSetup.humanSlModelDownloaded"));
      btnDownloadHumanSlModel.setEnabled(false);
    } else {
      lblHumanSlModelValue.setText(text("AutoSetup.humanSlModelMissing"));
      lblHumanSlModelValue.setToolTipText(null);
      lblHumanSlModelValue.setForeground(WARN_COLOR);
      btnDownloadHumanSlModel.setText(text("AutoSetup.downloadHumanSlModel"));
      btnDownloadHumanSlModel.setEnabled(
          activeDownloadSession == null && activeWorkerThread == null);
    }
    btnImportHumanSlModel.setEnabled(activeDownloadSession == null && activeWorkerThread == null);
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
      setWrappedInfoText(
          lblNvidiaRuntimeValue, text("AutoSetup.nvidiaRuntimeNotApplicable"));
      lblNvidiaRuntimeValue.setToolTipText(null);
      lblNvidiaRuntimeValue.setForeground(Color.DARK_GRAY);
      btnInstallNvidiaRuntime.setEnabled(false);
      updateTensorRtInfo();
      return;
    }
    setWrappedInfoText(lblNvidiaRuntimeValue, status.detailText);
    lblNvidiaRuntimeValue.setToolTipText(status.detailText);
    lblNvidiaRuntimeValue.setForeground(status.ready ? OK_COLOR : WARN_COLOR);
    btnInstallNvidiaRuntime.setEnabled(activeDownloadSession == null && !status.ready);
    updateTensorRtInfo();
  }

  private void updateTensorRtInfo() {
    KataGoRuntimeHelper.TensorRtInstallStatus status =
        snapshot == null
            ? null
            : KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, nvidiaGpuDetection);
    if (status == null) {
      setTensorRtLabel(lblTensorRtDownloadValue, text("AutoSetup.notFound"), Color.DARK_GRAY, null);
      setTensorRtLabel(lblTensorRtConfigValue, text("AutoSetup.notFound"), Color.DARK_GRAY, null);
      btnInstallTensorRt.setText(text("AutoSetup.installTensorRt"));
      btnInstallTensorRt.setToolTipText(null);
      btnInstallTensorRt.setEnabled(false);
      btnSwitchBackCuda.setToolTipText(null);
      btnSwitchBackCuda.setEnabled(false);
      updateTensorRtCacheButton();
      updateNvidiaGpuInfo(null);
      return;
    }
    updateNvidiaGpuInfo(status);
    if (!status.applicable) {
      String notApplicable = text("AutoSetup.tensorRtNotApplicable");
      setTensorRtLabel(
          lblTensorRtDownloadValue, notApplicable, Color.DARK_GRAY, status.detailText);
      setTensorRtLabel(
          lblTensorRtConfigValue, notApplicable, Color.DARK_GRAY, status.detailText);
      btnInstallTensorRt.setText(text("AutoSetup.installTensorRt"));
      btnInstallTensorRt.setToolTipText(tensorRtButtonTooltip(status));
      btnInstallTensorRt.setEnabled(false);
      btnSwitchBackCuda.setToolTipText(text("AutoSetup.switchBackCudaTooltip"));
      btnSwitchBackCuda.setEnabled(false);
      updateTensorRtCacheButton();
      return;
    }
    maybeStartNvidiaGpuDetection(status);
    setTensorRtLabel(
        lblTensorRtDownloadValue,
        status.downloaded
            ? text("AutoSetup.tensorRtDownloaded")
            : text("AutoSetup.tensorRtNotDownloaded"),
        status.downloaded ? OK_COLOR : WARN_COLOR,
        status.detailText);
    setTensorRtLabel(
        lblTensorRtConfigValue,
        status.active
            ? text("AutoSetup.tensorRtConfigured")
            : text("AutoSetup.tensorRtNotConfigured"),
        status.active ? OK_COLOR : (status.downloaded ? WARN_COLOR : Color.DARK_GRAY),
        status.detailText);
    if (status.installed && status.active) {
      btnInstallTensorRt.setText(text("AutoSetup.tensorRtEnabled"));
    } else if (status.installed) {
      btnInstallTensorRt.setText(text("AutoSetup.enableTensorRt"));
    } else {
      btnInstallTensorRt.setText(tensorRtInstallButtonText(status));
    }
    btnInstallTensorRt.setToolTipText(tensorRtButtonTooltip(status));
    btnInstallTensorRt.setEnabled(
        activeDownloadSession == null
            && activeWorkerThread == null
            && status.applicable
            && (!status.installed || !status.active));
    btnSwitchBackCuda.setToolTipText(text("AutoSetup.switchBackCudaTooltip"));
    btnSwitchBackCuda.setEnabled(
        activeDownloadSession == null
            && activeWorkerThread == null
            && status.applicable
            && status.active
            && canSwitchBackToCuda());
    updateTensorRtCacheButton();
  }

  private void updateTensorRtCacheButton() {
    long cacheBytes = KataGoRuntimeHelper.tensorRtDownloadCacheBytes();
    btnCleanTensorRtCache.setEnabled(
        activeDownloadSession == null && activeWorkerThread == null && cacheBytes > 0L);
    btnCleanTensorRtCache.setToolTipText(
        cacheBytes > 0L
            ? String.format(text("AutoSetup.cleanTensorRtCacheTooltip"), formatSize(cacheBytes))
            : text("AutoSetup.cleanTensorRtCacheEmpty"));
  }

  private void setTensorRtLabel(JLabel label, String value, Color color, String tooltip) {
    setWrappedInfoText(label, value);
    label.setForeground(color);
    label.setToolTipText(tooltip);
  }

  static void setWrappedInfoText(JLabel label, String value) {
    String plainText = value == null ? "" : value;
    String escaped =
        plainText
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>");
    label.setText(INFO_HTML_PREFIX + escaped + INFO_HTML_SUFFIX);
    label.getAccessibleContext().setAccessibleName(plainText);
    int height = Math.max(30, label.getPreferredSize().height);
    label.setPreferredSize(new Dimension(VALUE_COLUMN_WIDTH, height));
    label.setMinimumSize(new Dimension(260, height));
    label.revalidate();
  }

  private void maybeStartNvidiaGpuDetection(KataGoRuntimeHelper.TensorRtInstallStatus status) {
    if (status == null
        || !status.applicable
        || nvidiaGpuDetection != null
        || nvidiaGpuDetectionRunning) {
      return;
    }
    nvidiaGpuDetectionRunning = true;
    updateNvidiaGpuInfo(status);
    Thread worker =
        new Thread(
            () -> {
              NvidiaGpuDetector.DetectionResult result = NvidiaGpuDetector.detectBestGpu();
              SwingUtilities.invokeLater(
                  () -> {
                    nvidiaGpuDetection = result;
                    nvidiaGpuDetectionRunning = false;
                    updateTensorRtInfo();
                  });
            },
            "katago-nvidia-gpu-detection");
    worker.setDaemon(true);
    worker.start();
  }

  private void updateNvidiaGpuInfo(KataGoRuntimeHelper.TensorRtInstallStatus status) {
    if (nvidiaGpuDetectionRunning) {
      lblNvidiaGpuValue.setText(text("AutoSetup.gpuDetecting"));
      lblNvidiaGpuValue.setToolTipText(null);
      lblNvidiaGpuValue.setForeground(TEXT_SECONDARY);
      return;
    }
    if (status == null || status.gpuDetection == null) {
      lblNvidiaGpuValue.setText(text("AutoSetup.gpuDetectNotFound"));
      lblNvidiaGpuValue.setToolTipText(null);
      lblNvidiaGpuValue.setForeground(Color.DARK_GRAY);
      return;
    }
    String summary = status.gpuDetection.summaryText;
    lblNvidiaGpuValue.setText(compactInfoText(summary, GPU_INFO_TEXT_LENGTH));
    lblNvidiaGpuValue.setToolTipText(summary);
    lblNvidiaGpuValue.setForeground(tensorRtGpuStatusColor(status.gpuRecommendation));
  }

  private Color tensorRtGpuStatusColor(NvidiaGpuDetector.TensorRtRecommendation recommendation) {
    if (recommendation == NvidiaGpuDetector.TensorRtRecommendation.RECOMMENDED) {
      return OK_COLOR;
    }
    if (recommendation == NvidiaGpuDetector.TensorRtRecommendation.ALLOWED) {
      return WARN_COLOR;
    }
    if (recommendation == NvidiaGpuDetector.TensorRtRecommendation.NOT_RECOMMENDED) {
      return ERROR_COLOR;
    }
    return Color.DARK_GRAY;
  }

  private String tensorRtInstallButtonText(KataGoRuntimeHelper.TensorRtInstallStatus status) {
    if (status == null || nvidiaGpuDetectionRunning) {
      return text("AutoSetup.installTensorRt");
    }
    if (status.gpuRecommendation == NvidiaGpuDetector.TensorRtRecommendation.NOT_RECOMMENDED) {
      return text("AutoSetup.installTensorRtAdvanced");
    }
    if (status.gpuRecommendation == NvidiaGpuDetector.TensorRtRecommendation.UNKNOWN) {
      return text("AutoSetup.installTensorRtManual");
    }
    return text("AutoSetup.installTensorRt");
  }

  private String tensorRtButtonTooltip(KataGoRuntimeHelper.TensorRtInstallStatus status) {
    if (status == null) {
      return null;
    }
    String tooltip = status.detailText == null ? "" : status.detailText;
    if (!Utils.isBlank(status.gpuRecommendationText)
        && !tooltip.contains(status.gpuRecommendationText)) {
      tooltip =
          tooltip.isEmpty()
              ? status.gpuRecommendationText
              : tooltip + " | " + status.gpuRecommendationText;
    }
    return tooltip.isEmpty() ? null : tooltip;
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
    btnReloadRemoteWeights.setEnabled(false);
    lblRemoteDetailValue.setText(text("AutoSetup.loadingRemote"));
    lblRemoteDetailValue.setToolTipText(null);
    lblRemoteDetailValue.setForeground(WARN_COLOR);
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
                      btnReloadRemoteWeights.setEnabled(true);
                      btnStopDownload.setEnabled(false);
                    });
              }
            },
            "katago-remote-weight-info")
        .start();
  }

  private void reloadRemoteWeightInfo() {
    if (hasActiveBackgroundTask()) {
      showBackgroundTaskAlreadyRunningNotice();
      return;
    }
    loadRemoteWeightInfo();
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
    btnReloadRemoteWeights.setEnabled(true);
    btnStopDownload.setEnabled(activeDownloadSession != null);
  }

  private void autoSetupOrDownload() {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    Path selectedWeight = getSelectedLocalWeight();
    if (selectedWeight == null) {
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
    startAutoSetup(snapshot.withActiveWeight(selectedWeight));
  }

  private void addSelectedWeightEngine() {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    Path selectedWeight = getSelectedLocalWeight();
    if (selectedWeight == null) {
      Utils.showMsg(text("AutoSetup.missingWeight"), this);
      return;
    }
    startWeightEngineSetup(snapshot.withActiveWeight(selectedWeight));
  }

  private void importCustomWeight() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(text("AutoSetup.importWeightTitle"));
    chooser.setFileFilter(new FileNameExtensionFilter(text("AutoSetup.importWeightFilter"), "gz"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    final Path source = chooser.getSelectedFile().toPath();
    if (!isSupportedWeightFileName(source)) {
      Utils.showMsg(text("AutoSetup.importWeightInvalid"), this);
      return;
    }
    setBusy(true, text("AutoSetup.importingWeight"), 0, -1);
    Thread worker =
        new Thread(
            () -> {
              try {
                Path importedWeight = KataGoAutoSetupHelper.importWeight(source);
                SetupSnapshot refreshed = KataGoAutoSetupHelper.inspectLocalSetup();
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      setBusy(false, text("AutoSetup.importWeightDone"), 0, 0);
                      snapshot = refreshed;
                      renderSnapshot();
                      selectLocalWeight(importedWeight);
                      updateSelectedLocalWeightInfo();
                      Utils.showMsg(
                          text("AutoSetup.importWeightDoneMessage") + "\n" + importedWeight, this);
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      onBackgroundError(e);
                    });
              }
            },
            "katago-import-weight");
    activeWorkerThread = worker;
    worker.start();
  }

  private void importHumanSlModel() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle(text("AutoSetup.importHumanSlModelTitle"));
    chooser.setFileFilter(
        new FileNameExtensionFilter(text("AutoSetup.importHumanSlModelFilter"), "gz"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    final Path source = chooser.getSelectedFile().toPath();
    if (!isSupportedWeightFileName(source)) {
      Utils.showMsg(text("AutoSetup.importHumanSlModelInvalid"), this);
      return;
    }
    setBusy(true, text("AutoSetup.importingHumanSlModel"), 0, -1);
    Thread worker =
        new Thread(
            () -> {
              try {
                Path importedModel = KataGoAutoSetupHelper.importHumanSlModel(source);
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      setBusy(false, text("AutoSetup.importHumanSlModelDone"), 0, 0);
                      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
                      renderSnapshot();
                      Utils.showMsg(
                          text("AutoSetup.importHumanSlModelDoneMessage") + "\n" + importedModel,
                          this);
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      onBackgroundError(e);
                    });
              }
            },
            "katago-import-humansl-model");
    activeWorkerThread = worker;
    worker.start();
  }

  private void startHumanSlModelDownload() {
    final DownloadSession session = new DownloadSession();
    activeDownloadSession = session;
    setBusy(true, text("AutoSetup.downloadingHumanSlModel"), 0, -1);
    Thread worker =
        new Thread(
            () -> {
              try {
                Path downloadedModel =
                    KataGoAutoSetupHelper.downloadHumanSlModel(
                        (statusText, downloadedBytes, totalBytes) ->
                            SwingUtilities.invokeLater(
                                () ->
                                    setBusy(
                                        true,
                                        text("AutoSetup.downloadingHumanSlModel")
                                            + " "
                                            + statusText,
                                        downloadedBytes,
                                        totalBytes)),
                        session);
                SwingUtilities.invokeLater(
                    () -> {
                      setBusy(false, text("AutoSetup.humanSlModelDownloadDone"), 0, 0);
                      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
                      renderSnapshot();
                      Utils.showMsg(
                          text("AutoSetup.humanSlModelDownloadDoneMessage")
                              + "\n"
                              + downloadedModel,
                          this);
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(() -> onDownloadCancelled());
              } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onBackgroundError(e));
              } finally {
                clearActiveDownload(session, Thread.currentThread());
              }
            },
            "katago-download-humansl-model");
    activeWorkerThread = worker;
    worker.start();
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
                SetupSnapshot refreshed = KataGoAutoSetupHelper.inspectLocalSetup();
                if (autoApplyAfterDownload) {
                  SetupResult result =
                      KataGoAutoSetupHelper.applyAutoSetup(
                          refreshed.withActiveWeight(downloadedWeight));
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
                      selectLocalWeight(downloadedWeight);
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

  private void startTensorRtInstall() {
    if (hasActiveBackgroundTask()) {
      showBackgroundTaskAlreadyRunningNotice();
      return;
    }
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    KataGoRuntimeHelper.TensorRtInstallStatus status =
        KataGoRuntimeHelper.inspectTensorRtInstall(snapshot, nvidiaGpuDetection);
    if (!status.applicable) {
      Utils.showMsg(status.detailText, this);
      return;
    }
    if (status.installed && status.active) {
      lblStatus.setText(status.detailText);
      lblStatus.setForeground(OK_COLOR);
      updateTensorRtInfo();
      return;
    }
    if (status.installed) {
      applyInstalledTensorRt();
      return;
    }
    int result =
        JOptionPane.showConfirmDialog(
            this,
            formatTensorRtConfirmMessage(status),
            text("AutoSetup.tensorRtConfirmTitle"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (result != JOptionPane.OK_OPTION) {
      return;
    }

    final DownloadSession session = new DownloadSession();
    activeDownloadSession = session;
    btnStopDownload.setText(text("AutoSetup.stopDownload"));
    setBusy(true, text("AutoSetup.tensorRtPreparing"), 0, status.downloadBytes);
    Thread worker =
        new Thread(
            () -> {
              try {
                SetupSnapshot currentSnapshot = snapshot;
                SetupResult setupResult =
                    KataGoRuntimeHelper.downloadAndInstallTensorRt(
                        currentSnapshot,
                        (statusText, downloadedBytes, totalBytes) ->
                            SwingUtilities.invokeLater(
                                () -> setBusy(true, statusText, downloadedBytes, totalBytes)),
                        session);
                SwingUtilities.invokeLater(
                    () -> {
                      setBusy(false, text("AutoSetup.tensorRtInstallDone"), 0, 0);
                      onSetupApplied(
                          setupResult, text("AutoSetup.tensorRtInstallDoneMessage"), false);
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(() -> onDownloadCancelled());
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      if (!recoverInstalledTensorRtAfterError(e)) {
                        onBackgroundError(e);
                      }
                    });
              } finally {
                clearActiveDownload(session, Thread.currentThread());
              }
            },
            "katago-install-tensorrt");
    activeWorkerThread = worker;
    worker.start();
  }

  private void applyInstalledTensorRt() {
    try {
      SetupResult setupResult = KataGoRuntimeHelper.applyInstalledTensorRt(snapshot);
      setBusy(false, text("AutoSetup.tensorRtInstallDone"), 0, 0);
      onSetupApplied(setupResult, text("AutoSetup.tensorRtInstallDoneMessage"), false);
    } catch (IOException e) {
      onBackgroundError(e);
    }
  }

  private boolean recoverInstalledTensorRtAfterError(IOException originalError) {
    try {
      SetupSnapshot currentSnapshot = KataGoAutoSetupHelper.inspectLocalSetup();
      KataGoRuntimeHelper.TensorRtInstallStatus currentStatus =
          KataGoRuntimeHelper.inspectTensorRtInstall(currentSnapshot);
      if (!currentStatus.installed) {
        return false;
      }
      snapshot = currentSnapshot;
      SetupResult setupResult = KataGoRuntimeHelper.applyInstalledTensorRt(snapshot);
      setBusy(false, text("AutoSetup.tensorRtInstallDone"), 0, 0);
      onSetupApplied(setupResult, text("AutoSetup.tensorRtInstallDoneMessage"), false);
      return true;
    } catch (IOException recoveryError) {
      originalError.addSuppressed(recoveryError);
      return false;
    }
  }

  private void switchBackToCuda() {
    if (hasActiveBackgroundTask()) {
      showBackgroundTaskAlreadyRunningNotice();
      return;
    }
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    KataGoRuntimeHelper.TensorRtInstallStatus status =
        KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
    if (!status.active) {
      lblStatus.setText(text("AutoSetup.cudaAlreadyActive"));
      lblStatus.setForeground(OK_COLOR);
      updateTensorRtInfo();
      return;
    }
    try {
      SetupResult setupResult = KataGoRuntimeHelper.applyBundledCudaProfile(snapshot);
      setBusy(false, text("AutoSetup.cudaSwitchDone"), 0, 0);
      onSetupApplied(setupResult, text("AutoSetup.cudaSwitchDoneMessage"), false);
    } catch (IOException e) {
      onBackgroundError(e);
    }
  }

  private void cleanTensorRtCache() {
    if (hasActiveBackgroundTask()) {
      showBackgroundTaskAlreadyRunningNotice();
      return;
    }
    long cacheBytes = KataGoRuntimeHelper.tensorRtDownloadCacheBytes();
    if (cacheBytes <= 0L) {
      lblStatus.setText(text("AutoSetup.cleanTensorRtCacheEmpty"));
      lblStatus.setForeground(OK_COLOR);
      updateTensorRtInfo();
      return;
    }
    int result =
        JOptionPane.showConfirmDialog(
            this,
            String.format(text("AutoSetup.cleanTensorRtCacheConfirm"), formatSize(cacheBytes)),
            text("AutoSetup.cleanTensorRtCache"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (result != JOptionPane.OK_OPTION) {
      return;
    }
    try {
      long freedBytes = KataGoRuntimeHelper.cleanupTensorRtDownloadCache();
      String message =
          String.format(text("AutoSetup.cleanTensorRtCacheDone"), formatSize(freedBytes));
      lblStatus.setText(message);
      lblStatus.setForeground(OK_COLOR);
      updateTensorRtInfo();
      Utils.showMsg(message, this);
    } catch (IOException e) {
      onBackgroundError(e);
    }
  }

  private String formatTensorRtConfirmMessage(KataGoRuntimeHelper.TensorRtInstallStatus status) {
    String sizeText = status == null ? "0 MB" : formatSize(status.downloadBytes);
    String recommendation =
        status == null || Utils.isBlank(status.gpuRecommendationText)
            ? text("AutoSetup.tensorRtGpuHint")
            : status.gpuRecommendationText;
    String message;
    try {
      message = String.format(text("AutoSetup.tensorRtConfirmMessage"), sizeText, recommendation);
    } catch (IllegalFormatException e) {
      message = String.format(text("AutoSetup.tensorRtConfirmMessage"), sizeText);
    }
    if (!Utils.isBlank(recommendation) && !message.contains(recommendation)) {
      message = message + "\n\n" + recommendation;
    }
    return message;
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
    setBusy(true, text("AutoSetup.benchmarkPreparing"), 30, 1000);
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
    Thread worker =
        new Thread(
            () -> {
              try {
                SetupResult result = KataGoAutoSetupHelper.applyAutoSetup(state);
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      setBusy(false, text("AutoSetup.setupDone"), 0, 0);
                      onSetupApplied(result, text("AutoSetup.setupDoneMessage"));
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      onBackgroundError(e);
                    });
              }
            },
            "katago-auto-setup");
    activeWorkerThread = worker;
    worker.start();
  }

  private void startWeightEngineSetup(SetupSnapshot state) {
    setBusy(true, text("AutoSetup.settingUp"), 0, -1);
    Thread worker =
        new Thread(
            () -> {
              try {
                SetupResult result = KataGoAutoSetupHelper.addWeightEngineProfile(state);
                String messageKey =
                    result.createdEngine
                        ? "AutoSetup.weightEngineAddedMessage"
                        : "AutoSetup.weightEngineUpdatedMessage";
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      setBusy(false, text("AutoSetup.setupDone"), 0, 0);
                      onSetupApplied(
                          result, text(messageKey) + " " + result.engineName, true, false);
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      onBackgroundError(e);
                    });
              }
            },
            "katago-add-weight-engine");
    activeWorkerThread = worker;
    worker.start();
  }

  private void onSetupApplied(SetupResult result, String message) {
    onSetupApplied(result, message, true);
  }

  private void onSetupApplied(SetupResult result, String message, boolean showSuccessPopup) {
    onSetupApplied(result, message, showSuccessPopup, true);
  }

  private void onSetupApplied(
      SetupResult result,
      String message,
      boolean showSuccessPopup,
      boolean includeWeightPathInPopup) {
    snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    renderSnapshot();
    selectRemoteWeightByModelName(KataGoAutoSetupHelper.resolveActiveWeightModelName(snapshot));
    updateSelectedRemoteWeightInfo();
    String reloadWarning = reloadRunningEngine(result.engineIndex);
    if (reloadWarning == null || reloadWarning.trim().isEmpty()) {
      lblStatus.setText(message);
      lblStatus.setForeground(OK_COLOR);
      if (showSuccessPopup) {
        Utils.showMsg(
            includeWeightPathInPopup ? message + "\n" + result.snapshot.activeWeightPath : message,
            this);
      }
    } else {
      lblStatus.setText(reloadWarning);
      lblStatus.setForeground(WARN_COLOR);
      Utils.showMsg(
          message
              + (includeWeightPathInPopup ? "\n" + result.snapshot.activeWeightPath : "")
              + "\n\n"
              + reloadWarning,
          this);
    }
  }

  private String reloadRunningEngine(int engineIndex) {
    if (Lizzie.engineManager == null) {
      return null;
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
      return null;
    } catch (Exception e) {
      return text("AutoSetup.reloadFailed") + "\n" + e.getMessage();
    }
  }

  private void onBackgroundError(Exception e) {
    setBusy(false, text("AutoSetup.failed"), 0, 0);
    renderSnapshot();
    String detail = e == null || e.getMessage() == null ? "" : e.getMessage();
    String message = text("AutoSetup.failed") + "\n" + detail;
    lblStatus.setText(detail.trim().isEmpty() ? text("AutoSetup.failed") : detail);
    lblStatus.setForeground(ERROR_COLOR);
    if (shouldShowBackgroundErrorPopup(detail)) {
      Utils.showMsg(message, this);
    }
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
    String previousStatus = progressStatusLabel.getText();
    if (statusText == null || statusText.trim().isEmpty()) {
      statusText = busy ? text("AutoSetup.benchmarking") : "";
    }
    lblStatus.setText(statusText);
    lblStatus.setForeground(busy ? WARN_COLOR : Color.DARK_GRAY);
    progressStatusLabel.setText(statusText);
    progressStatusLabel.setForeground(busy ? WARN_COLOR : Color.DARK_GRAY);
    btnRefresh.setEnabled(!busy);
    btnAutoSetup.setEnabled(!busy);
    btnReloadRemoteWeights.setEnabled(!busy);
    btnDownloadWeight.setEnabled(!busy && canDownloadSelectedRemoteWeight());
    btnImportWeight.setEnabled(!busy);
    btnAddWeight.setEnabled(!busy && getSelectedLocalWeight() != null);
    btnDownloadHumanSlModel.setEnabled(!busy && canDownloadHumanSlModel());
    btnImportHumanSlModel.setEnabled(!busy);
    cmbLocalWeights.setEnabled(!busy && cmbLocalWeights.getItemCount() > 0);
    cmbRemoteWeights.setEnabled(!busy && cmbRemoteWeights.getItemCount() > 0);
    btnInstallNvidiaRuntime.setEnabled(!busy && canInstallNvidiaRuntime());
    btnInstallTensorRt.setEnabled(!busy && canInstallTensorRt());
    btnSwitchBackCuda.setEnabled(!busy && canSwitchBackToCuda());
    btnCleanTensorRtCache.setEnabled(
        !busy && KataGoRuntimeHelper.tensorRtDownloadCacheBytes() > 0L);
    btnOptimizePerformance.setEnabled(!busy && canRunBenchmark());
    btnStopDownload.setEnabled(busy && activeDownloadSession != null);
    btnClose.setEnabled(true);

    progressPanel.setVisible(busy);
    AccessibilitySupport.announce(progressStatusLabel, previousStatus, statusText);
    progressBar.setIndeterminate(busy && totalBytes <= 0);
    if (!busy) {
      progressStartedAtMillis = 0L;
      progressBar.setIndeterminate(false);
      progressBar.setValue(0);
      progressBar.putClientProperty(BENCHMARK_PROGRESS_KEY, Integer.valueOf(0));
      progressBar.setString("");
    } else if (totalBytes > 0) {
      if (downloadedBytes > 0L && progressStartedAtMillis <= 0L) {
        progressStartedAtMillis = System.currentTimeMillis();
      }
      progressBar.setMaximum(1000);
      int progressValue = (int) Math.min(1000, (downloadedBytes * 1000L) / totalBytes);
      if (isBenchmarkPermilleProgress(statusText, downloadedBytes, totalBytes)) {
        int previousProgress =
            progressBar.getClientProperty(BENCHMARK_PROGRESS_KEY) instanceof Integer
                ? ((Integer) progressBar.getClientProperty(BENCHMARK_PROGRESS_KEY)).intValue()
                : 0;
        progressValue = Math.max(previousProgress, progressValue);
        progressBar.putClientProperty(BENCHMARK_PROGRESS_KEY, Integer.valueOf(progressValue));
      } else {
        progressBar.putClientProperty(BENCHMARK_PROGRESS_KEY, Integer.valueOf(0));
      }
      progressBar.setValue(progressValue);
      if (isBenchmarkPermilleProgress(statusText, downloadedBytes, totalBytes)) {
        long percent = Math.min(100, progressValue / 10L);
        progressBar.setString(percent + "%");
      } else {
        long percent = Math.min(100, (downloadedBytes * 100L) / totalBytes);
        String etaText = formatEta(downloadedBytes, totalBytes);
        progressBar.setString(
            percent
                + "%  "
                + formatSize(downloadedBytes)
                + " / "
                + formatSize(totalBytes)
                + etaText);
      }
    } else if (downloadedBytes > 0) {
      progressBar.setValue(0);
      progressBar.setString(formatSize(downloadedBytes));
    } else {
      progressBar.setValue(0);
      progressBar.putClientProperty(BENCHMARK_PROGRESS_KEY, Integer.valueOf(0));
      progressBar.setString("");
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

  private boolean canInstallTensorRt() {
    if (snapshot == null) {
      return false;
    }
    return KataGoRuntimeHelper.canInstallTensorRt(snapshot);
  }

  private boolean canSwitchBackToCuda() {
    if (snapshot == null) {
      return false;
    }
    return KataGoRuntimeHelper.canSwitchBackToCuda(snapshot);
  }

  private boolean hasActiveBackgroundTask() {
    return activeDownloadSession != null || activeWorkerThread != null;
  }

  private void showBackgroundTaskAlreadyRunningNotice() {
    String message = text("AutoSetup.taskAlreadyRunning");
    lblStatus.setText(message);
    lblStatus.setForeground(WARN_COLOR);
  }

  private boolean shouldShowBackgroundErrorPopup(String detail) {
    String normalized = detail == null ? "" : detail.trim();
    if (normalized.equals(text("AutoSetup.tensorRtInstallAlreadyRunning"))) {
      return false;
    }
    long now = System.currentTimeMillis();
    if (normalized.equals(lastBackgroundErrorMessage)
        && now - lastBackgroundErrorMillis < ERROR_POPUP_DEDUP_MILLIS) {
      return false;
    }
    lastBackgroundErrorMessage = normalized;
    lastBackgroundErrorMillis = now;
    return true;
  }

  private boolean canRunBenchmark() {
    return snapshot != null
        && snapshot.hasEngine()
        && snapshot.hasConfigs()
        && snapshot.hasWeight();
  }

  private boolean canDownloadHumanSlModel() {
    return !KataGoAutoSetupHelper.inspectHumanSlModel().isInstalled()
        && activeDownloadSession == null
        && activeWorkerThread == null;
  }

  private boolean isBenchmarkPermilleProgress(
      String statusText, long downloadedBytes, long totalBytes) {
    return totalBytes == 1000L && downloadedBytes >= 0L && downloadedBytes <= 1000L;
  }

  private Path getSelectedLocalWeight() {
    Object selected = cmbLocalWeights.getSelectedItem();
    return selected instanceof Path ? (Path) selected : null;
  }

  private void updateSelectedLocalWeightInfo() {
    Path selectedWeight = getSelectedLocalWeight();
    if (selectedWeight == null) {
      lblLocalWeightDetailValue.setText(text("AutoSetup.noLocalWeights"));
      lblLocalWeightDetailValue.setToolTipText(null);
      lblLocalWeightDetailValue.setForeground(ERROR_COLOR);
      btnAddWeight.setEnabled(false);
      return;
    }
    String textValue =
        KataGoAutoSetupHelper.resolveWeightDisplayName(selectedWeight)
            + "  |  "
            + selectedWeight.toAbsolutePath().normalize();
    lblLocalWeightDetailValue.setText(compactInfoText(textValue));
    lblLocalWeightDetailValue.setToolTipText(textValue);
    boolean current =
        snapshot != null
            && snapshot.activeWeightPath != null
            && snapshot
                .activeWeightPath
                .toAbsolutePath()
                .normalize()
                .equals(selectedWeight.toAbsolutePath().normalize());
    lblLocalWeightDetailValue.setForeground(current ? OK_COLOR : Color.DARK_GRAY);
    btnAddWeight.setEnabled(
        activeDownloadSession == null && activeWorkerThread == null && selectedWeight != null);
  }

  private void selectLocalWeight(Path weightPath) {
    if (weightPath == null) {
      return;
    }
    Path normalized = weightPath.toAbsolutePath().normalize();
    for (int i = 0; i < cmbLocalWeights.getItemCount(); i++) {
      Path item = cmbLocalWeights.getItemAt(i);
      if (item != null && item.toAbsolutePath().normalize().equals(normalized)) {
        cmbLocalWeights.setSelectedIndex(i);
        return;
      }
    }
  }

  private String formatSize(long bytes) {
    if (bytes <= 0) {
      return "0 MB";
    }
    double gb = bytes / 1024.0 / 1024.0 / 1024.0;
    if (gb >= 1.0) {
      return String.format("%.1f GB", gb);
    }
    double mb = bytes / 1024.0 / 1024.0;
    if (mb >= 100) {
      return String.format("%.0f MB", mb);
    }
    return String.format("%.1f MB", mb);
  }

  private String formatEta(long downloadedBytes, long totalBytes) {
    if (progressStartedAtMillis <= 0L || downloadedBytes <= 0L || downloadedBytes >= totalBytes) {
      return "";
    }
    long elapsedMillis = Math.max(1000L, System.currentTimeMillis() - progressStartedAtMillis);
    long bytesPerSecond = Math.max(1L, (downloadedBytes * 1000L) / elapsedMillis);
    long remainingMillis = Math.max(0L, ((totalBytes - downloadedBytes) * 1000L) / bytesPerSecond);
    return "  ETA " + formatDuration(remainingMillis);
  }

  private String formatDuration(long millis) {
    long seconds = Math.max(0L, millis / 1000L);
    long minutes = seconds / 60L;
    long remainingSeconds = seconds % 60L;
    if (minutes >= 60L) {
      long hours = minutes / 60L;
      long remainingMinutes = minutes % 60L;
      return hours + "h " + remainingMinutes + "m";
    }
    if (minutes > 0L) {
      return minutes + "m " + remainingSeconds + "s";
    }
    return remainingSeconds + "s";
  }

  private String formatRemoteChoice(RemoteWeightInfo info) {
    if (info == null) {
      return text("AutoSetup.remoteUnavailable");
    }
    StringBuilder label = new StringBuilder();
    label.append(formatRemoteModelName(info));
    String releaseDate = formatRemoteReleaseDate(info);
    if (!releaseDate.isEmpty()) {
      label.append("  |  ").append(releaseDate);
    }
    if (info.recommended) {
      label.append("  |  ").append(text("AutoSetup.recommendedStrongest"));
    }
    if (info.latest) {
      label.append("  |  ").append(text("AutoSetup.recommendedLatest"));
    }
    if (isDownloadedRemoteWeight(info)) {
      label.append("  |  ").append(text("AutoSetup.downloaded"));
    }
    if (matchesCurrentWeight(info)) {
      label.append("  |  ").append(text("AutoSetup.currentlyUsingShort"));
    }
    return label.toString();
  }

  private String formatLocalWeightChoice(Path weightPath) {
    if (weightPath == null) {
      return text("AutoSetup.noLocalWeights");
    }
    String displayName = KataGoAutoSetupHelper.resolveWeightDisplayName(weightPath);
    String fileName =
        weightPath.getFileName() == null
            ? weightPath.toString()
            : weightPath.getFileName().toString();
    if (displayName == null || displayName.trim().isEmpty() || displayName.equals(fileName)) {
      return fileName;
    }
    return displayName + "  |  " + fileName;
  }

  private boolean isSupportedWeightFileName(Path path) {
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String fileName = path.getFileName().toString().toLowerCase();
    return fileName.endsWith(".bin.gz") || fileName.endsWith(".txt.gz");
  }

  private String compactInfoText(String value) {
    return compactInfoText(value, MAX_INFO_TEXT_LENGTH);
  }

  private String compactInfoText(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    int head = Math.max(32, maxLength / 2 - 6);
    int tail = Math.max(36, maxLength - head - 5);
    return value.substring(0, head) + " ... " + value.substring(value.length() - tail);
  }

  private void updateSelectedRemoteWeightInfo() {
    RemoteWeightInfo info = getSelectedRemoteWeight();
    if (info == null) {
      lblRemoteDetailValue.setText(text("AutoSetup.remoteUnavailable"));
      lblRemoteDetailValue.setToolTipText(null);
      lblRemoteDetailValue.setForeground(ERROR_COLOR);
      btnDownloadWeight.setText(text("AutoSetup.downloadWeight"));
      btnDownloadWeight.setEnabled(false);
      btnStopDownload.setEnabled(false);
      return;
    }
    boolean downloaded = isDownloadedRemoteWeight(info);
    boolean current = matchesCurrentWeight(info);
    StringBuilder detail = new StringBuilder();
    detail.append(formatRemoteModelName(info)).append("  |  ").append(info.fileName());
    String releaseDate = formatRemoteReleaseDate(info);
    if (!releaseDate.isEmpty()) {
      detail.append("  |  ").append(releaseDate);
    }
    if (info.eloRating != null && !info.eloRating.trim().isEmpty()) {
      detail.append("  |  ").append(info.eloRating.trim());
    }
    if (downloaded) {
      detail.append("  |  ").append(text("AutoSetup.downloaded"));
    }
    if (current) {
      detail.append("  |  ").append(text("AutoSetup.currentlyUsing"));
    }
    String detailText = detail.toString();
    lblRemoteDetailValue.setText(compactInfoText(detailText));
    lblRemoteDetailValue.setToolTipText(detailText + " | " + info.downloadUrl);
    lblRemoteDetailValue.setForeground(current || downloaded ? OK_COLOR : Color.DARK_GRAY);
    btnDownloadWeight.setText(
        downloaded ? text("AutoSetup.downloaded") : text("AutoSetup.downloadWeight"));
    btnDownloadWeight.setEnabled(canDownloadSelectedRemoteWeight());
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
      if (KataGoAutoSetupHelper.isDefaultGeneralUseWeight(info)) {
        return info;
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

  private boolean canDownloadSelectedRemoteWeight() {
    RemoteWeightInfo info = getSelectedRemoteWeight();
    return info != null
        && !isDownloadedRemoteWeight(info)
        && activeDownloadSession == null
        && activeWorkerThread == null;
  }

  private boolean isDownloadedRemoteWeight(RemoteWeightInfo info) {
    if (info == null) {
      return false;
    }
    if (matchesCurrentWeight(info)) {
      return true;
    }
    if (snapshot == null || snapshot.weightCandidates == null) {
      return false;
    }
    String remoteFileName = normalizeWeightName(info.fileName());
    for (Path candidate : snapshot.weightCandidates) {
      if (candidate == null || candidate.getFileName() == null) {
        continue;
      }
      String candidateFileName = normalizeWeightName(candidate.getFileName().toString());
      if (candidateFileName.equals(remoteFileName)) {
        return true;
      }
      String displayName = KataGoAutoSetupHelper.resolveWeightDisplayName(candidate);
      if (matchesModelName(info, displayName)) {
        return true;
      }
    }
    return false;
  }

  private String normalizeWeightName(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private String formatRemoteModelName(RemoteWeightInfo info) {
    if (info == null) {
      return "";
    }
    String displayName = KataGoAutoSetupHelper.resolveWeightDisplayName(info.modelName);
    return displayName == null || displayName.trim().isEmpty() ? info.modelName : displayName;
  }

  private String formatRemoteReleaseDate(RemoteWeightInfo info) {
    if (info == null || info.uploadedAt == null) {
      return "";
    }
    String value = info.uploadedAt.trim();
    return value.length() >= 10 ? value.substring(0, 10) : value;
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
    boolean stateChanged = false;
    if (activeDownloadSession == session) {
      activeDownloadSession = null;
      stateChanged = true;
    }
    if (activeWorkerThread == workerThread) {
      activeWorkerThread = null;
      stateChanged = true;
    }
    if (stateChanged) {
      SwingUtilities.invokeLater(this::refreshIdleControls);
    }
  }

  private void refreshIdleControls() {
    if (activeDownloadSession != null || activeWorkerThread != null) {
      return;
    }
    btnRefresh.setEnabled(true);
    btnAutoSetup.setEnabled(true);
    btnReloadRemoteWeights.setEnabled(true);
    btnDownloadWeight.setEnabled(getSelectedRemoteWeight() != null);
    btnImportWeight.setEnabled(true);
    btnAddWeight.setEnabled(getSelectedLocalWeight() != null);
    btnDownloadHumanSlModel.setEnabled(canDownloadHumanSlModel());
    btnImportHumanSlModel.setEnabled(true);
    cmbLocalWeights.setEnabled(cmbLocalWeights.getItemCount() > 0);
    cmbRemoteWeights.setEnabled(cmbRemoteWeights.getItemCount() > 0);
    btnInstallNvidiaRuntime.setEnabled(canInstallNvidiaRuntime());
    btnInstallTensorRt.setEnabled(canInstallTensorRt());
    btnSwitchBackCuda.setEnabled(canSwitchBackToCuda());
    updateTensorRtCacheButton();
    btnOptimizePerformance.setEnabled(canRunBenchmark());
    btnStopDownload.setEnabled(false);
    btnClose.setEnabled(true);
  }

  private String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }

  private static class RefreshIcon implements Icon {
    private static final int SIZE = 18;

    @Override
    public int getIconWidth() {
      return SIZE;
    }

    @Override
    public int getIconHeight() {
      return SIZE;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(52, 90, 88));
        g2.draw(new Arc2D.Double(x + 3, y + 3, 12, 12, 32, 292, Arc2D.OPEN));

        Polygon arrow = new Polygon();
        arrow.addPoint(x + 15, y + 2);
        arrow.addPoint(x + 16, y + 8);
        arrow.addPoint(x + 10, y + 5);
        g2.fillPolygon(arrow);
      } finally {
        g2.dispose();
      }
    }
  }
}
