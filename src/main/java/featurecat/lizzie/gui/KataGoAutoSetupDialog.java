package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.AnalysisEngine;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DiscoverySource;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadCancelledException;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import featurecat.lizzie.util.KataGoAutoSetupHelper.EngineValidationResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.EngineValidationStatus;
import featurecat.lizzie.util.KataGoAutoSetupHelper.LocalKataGoDiscoveryResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.PackageFlavor;
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
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
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
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicScrollBarUI;

public class KataGoAutoSetupDialog extends JDialog {
  private static final Color OK_COLOR = new Color(28, 121, 82);
  private static final Color WARN_COLOR = new Color(184, 110, 27);
  private static final Color ERROR_COLOR = new Color(172, 56, 56);
  private static final Color APP_BG = new Color(248, 245, 238);
  private static final Color CARD_BG = new Color(255, 253, 248);
  private static final Color INFO_BG = new Color(252, 249, 242);
  private static final Color INFO_BORDER = new Color(224, 210, 184);
  private static final Color SIDEBAR_BG = new Color(8, 61, 58);
  private static final Color SIDEBAR_SELECTED_BG = new Color(20, 100, 94);
  private static final Color SIDEBAR_TEXT = new Color(236, 241, 232);
  private static final Color TEXT_PRIMARY = new Color(35, 39, 36);
  private static final Color TEXT_SECONDARY = new Color(101, 106, 100);
  private static final Color ACCENT_TEAL = new Color(10, 101, 94);
  private static final Color ACCENT_TEAL_HOVER = new Color(13, 116, 107);
  private static final Color ACCENT_GOLD = new Color(194, 139, 45);
  private static final String BENCHMARK_PROGRESS_KEY = "lizzie.benchmark.dialog.progress";
  private static final String WRAPPING_TEXT_KEY = "lizzie.autosetup.wrappingText";
  private static final String INFO_HTML_PREFIX = "<html><div style='width: 360px'>";
  private static final String INFO_HTML_SUFFIX = "</div></html>";
  private static final int MAX_INFO_TEXT_LENGTH = 104;
  private static final int GPU_INFO_TEXT_LENGTH = 132;
  private static final int DIALOG_WIDTH = 1200;
  private static final int DIALOG_HEIGHT = 900;
  private static final int VALUE_COLUMN_WIDTH = 390;
  private static final int WEIGHT_CATALOG_ROW_HEIGHT = 34;
  private static final int WEIGHT_CATALOG_VISIBLE_ROWS = 6;
  private static final int WEIGHT_CATALOG_HORIZONTAL_INSET = 14;
  private static final int WEIGHT_CATALOG_ELO_WIDTH = 84;
  private static final int WEIGHT_CATALOG_DATE_WIDTH = 116;
  private static final int WEIGHT_CATALOG_STATUS_WIDTH = 116;
  private static final int WEIGHT_CATALOG_MODEL_MAX_WIDTH = 330;
  private static final int WEIGHT_CATALOG_ELO_GAP = 8;
  private static final int WEIGHT_CATALOG_DATE_GAP = 20;
  private static final int WEIGHT_CATALOG_STATUS_GAP = 26;
  private static final int WEIGHT_RECOMMENDATION_CARD_HEIGHT = 150;
  private static final DateTimeFormatter LOCAL_WEIGHT_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final Pattern ELO_VALUE_PATTERN = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?");
  private static final long ERROR_POPUP_DEDUP_MILLIS = 5000L;
  private static final int WEIGHT_SWITCH_POLL_MILLIS = 100;
  private static final long WEIGHT_SWITCH_TIMEOUT_MILLIS = 35000L;
  private static final String CARD_OVERVIEW = "overview";
  private static final String CARD_WEIGHTS = "weights";
  private static final String CARD_ACCELERATION = "acceleration";
  private static final String CARD_BENCHMARK = "benchmark";

  private SetupSnapshot snapshot;
  private List<RemoteWeightInfo> remoteWeightInfos = Collections.emptyList();
  private volatile DownloadSession activeDownloadSession;
  private volatile Thread activeWorkerThread;
  private javax.swing.Timer pendingWeightSwitchTimer;
  private boolean pendingWeightSwitchShouldResumeQuickAnalysis;
  private volatile NvidiaGpuDetector.DetectionResult nvidiaGpuDetection;
  private volatile boolean nvidiaGpuDetectionRunning;
  private volatile EngineValidationResult engineValidationResult;
  private long engineValidationRequestId;
  private long progressStartedAtMillis;
  private String lastBackgroundErrorMessage = "";
  private long lastBackgroundErrorMillis = 0L;
  private int selectedSetupSectionIndex = 0;
  private int hoveredWeightDownloadRow = -1;

  private final JLabel lblEngineValue = new JFontLabel();
  private final JLabel lblWeightValue = new JFontLabel();
  private final JLabel lblWeightModelValue = new JFontLabel();
  private final JLabel lblGtpConfigValue = new JFontLabel();
  private final JLabel lblAnalysisConfigValue = new JFontLabel();
  private final JLabel lblDiscoverySourceValue = new JFontLabel();
  private final JLabel lblPackageFlavorValue = new JFontLabel();
  private final JLabel lblEngineValidationValue = new JFontLabel();
  private final JLabel lblNvidiaRuntimeValue = new JFontLabel();
  private final JLabel lblNvidiaGpuValue = new JFontLabel();
  private final JLabel lblTensorRtDownloadValue = new JFontLabel();
  private final JLabel lblTensorRtConfigValue = new JFontLabel();
  private final JLabel lblBenchmarkValue = new JFontLabel();
  private final JLabel lblSelectedWeightName = new JFontLabel();
  private final JLabel lblSelectedWeightMeta = new JFontLabel();
  private final JLabel lblCurrentWeightName = new JFontLabel();
  private final StatusPillLabel lblCurrentWeightStatus = new StatusPillLabel();
  private final JLabel lblHumanSlModelValue = new JFontLabel();
  private final WeightStatusTagLabel lblHumanSlStatus = new WeightStatusTagLabel();
  private final JLabel lblStatus = new JFontLabel();
  private final JList<String> sectionNav = new JList<String>();
  private final ActiveCardLayout detailCardLayout = new ActiveCardLayout();
  private final JPanel detailCards = new ViewportWidthPanel(detailCardLayout);
  private final JPanel footerPanel = new JPanel(new BorderLayout(0, 10));
  private final JPanel progressPanel = new JPanel(new BorderLayout(0, 6));
  private final JLabel progressStatusLabel = new JFontLabel();
  private final JLabel progressTitleLabel = new JFontLabel();
  private final DefaultListModel<WeightCatalogEntry> weightCatalogModel =
      new DefaultListModel<WeightCatalogEntry>();
  private final JList<WeightCatalogEntry> weightCatalogList =
      new JList<WeightCatalogEntry>(weightCatalogModel);
  private final JToggleButton btnOfficialWeightTab = new JToggleButton();
  private final JToggleButton btnCustomWeightTab = new JToggleButton();
  private final JPanel weightCatalogActionCards = new JPanel(new ActiveCardLayout());
  private final JPanel weightCatalogContentCards = new JPanel(new CardLayout());
  private final JScrollPane weightCatalogScrollPane = new JScrollPane(weightCatalogList);
  private final JPanel selectedWeightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
  private final JFontLabel lblWeightCatalogEmpty = new JFontLabel();
  private final WeightRecommendationCard balancedRecommendation =
      new WeightRecommendationCard(RecommendationTone.TEAL);
  private final WeightRecommendationCard strongestRecommendation =
      new WeightRecommendationCard(RecommendationTone.GOLD);
  private final WeightRecommendationCard lightweightRecommendation =
      new WeightRecommendationCard(RecommendationTone.SAGE);
  private final JProgressBar progressBar = new JProgressBar();
  private final JFontButton btnRefresh = new JFontButton();
  private final JFontButton btnChooseLocalEngine = new JFontButton();
  private final JFontButton btnRepairAnalysisConfig = new JFontButton();
  private final JFontButton btnOpenAppFolder = new JFontButton();
  private final JFontButton btnViewFullDownloads = new JFontButton();
  private final JFontButton btnReloadRemoteWeights = new JFontButton();
  private final JFontButton btnDownloadWeight = new JFontButton();
  private final JFontButton btnImportWeight = new JFontButton();
  private final JFontButton btnUseWeight = new JFontButton();
  private final JFontButton btnDownloadHumanSlModel = new JFontButton();
  private final JFontButton btnImportHumanSlModel = new JFontButton();
  private final JFontButton btnInstallNvidiaRuntime = new JFontButton();
  private final JFontButton btnInstallTensorRt = new JFontButton();
  private final JFontButton btnSwitchBackCuda = new JFontButton();
  private final JFontButton btnCleanTensorRtCache = new JFontButton();
  private final JFontButton btnOptimizePerformance = new JFontButton();
  private final JFontButton btnStopDownload = new JFontButton();
  private final JFontButton btnClose = new JFontButton();
  private WeightCatalogMode weightCatalogMode = WeightCatalogMode.OFFICIAL;

  public KataGoAutoSetupDialog(Window owner) {
    super(owner);
    setModal(false);
    setTitle(text("AutoSetup.title"));
    configureNativeWindowChrome();
    setSize(initialDialogSize());
    setMinimumSize(new Dimension(900, 620));
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

    configureWeightCatalog();

    content.add(createHeaderPanel(), BorderLayout.NORTH);

    JPanel body = new WarmBodyPanel();
    body.add(createSidebarPanel(), BorderLayout.WEST);

    detailCards.setOpaque(false);
    detailCards.setBorder(BorderFactory.createEmptyBorder(14, 28, 14, 30));
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
        weightCatalogList,
        text("Accessibility.downloadableWeights"),
        text("Accessibility.downloadableWeightsDescription"));
    AccessibilitySupport.named(
        btnOfficialWeightTab,
        text("AutoSetup.officialWeights"),
        text("Accessibility.downloadableWeightsDescription"));
    AccessibilitySupport.named(
        btnCustomWeightTab,
        text("AutoSetup.localWeights"),
        text("Accessibility.downloadedWeightsDescription"));
    AccessibilitySupport.named(
        btnDownloadWeight,
        text("AutoSetup.downloadWeight"),
        text("Accessibility.downloadableWeightsDescription"));
    AccessibilitySupport.named(
        btnUseWeight,
        text("AutoSetup.useWeight"),
        text("Accessibility.downloadedWeightsDescription"));
    AccessibilitySupport.named(
        btnImportHumanSlModel,
        text("AutoSetup.importHumanSlModel"),
        text("AutoSetup.importHumanSlModel"));
    AccessibilitySupport.named(
        btnChooseLocalEngine,
        text("AutoSetup.chooseExistingKataGo"),
        text("AutoSetup.chooseKataGoExecutable"));
    AccessibilitySupport.named(
        btnRepairAnalysisConfig,
        text("AutoSetup.repairAnalysisConfig"),
        text("AutoSetup.repairAnalysisConfig"));
    AccessibilitySupport.named(
        btnOpenAppFolder, text("AutoSetup.openAppFolder"), text("AutoSetup.openAppFolder"));
    AccessibilitySupport.named(
        btnViewFullDownloads,
        text("AutoSetup.viewFullDownloads"),
        text("AutoSetup.viewFullDownloads"));
    AccessibilitySupport.named(btnRefresh, text("AutoSetup.refresh"), text("AutoSetup.refresh"));
    AccessibilitySupport.named(
        lblCurrentWeightName, text("AutoSetup.currentWeight"), text("AutoSetup.currentlyUsing"));
    AccessibilitySupport.named(
        sectionNav, text("AutoSetup.sidebarTitle"), text("AutoSetup.expertSubtitle"));
    AccessibilitySupport.applyToTree(content);
    AccessibilitySupport.installEscapeAction(getRootPane(), this, this::closeOrCancelActiveTask);

    refreshState();
  }

  private void configureNativeWindowChrome() {
    if (!isMacOs()) {
      return;
    }
    getRootPane().putClientProperty("apple.awt.fullWindowContent", Boolean.TRUE);
    getRootPane().putClientProperty("apple.awt.transparentTitleBar", Boolean.TRUE);
    getRootPane().putClientProperty("apple.awt.windowTitleVisible", Boolean.FALSE);
  }

  private static boolean isMacOs() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
  }

  private static Dimension initialDialogSize() {
    Rectangle available =
        GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    int width = Math.max(900, Math.min(DIALOG_WIDTH, available.width - 72));
    int height = Math.max(620, Math.min(DIALOG_HEIGHT, available.height - 72));
    return new Dimension(width, height);
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
    engineValidationResult = null;
    renderSnapshot();
    validateDiscoveredEngineAsync();
    loadRemoteWeightInfo();
  }

  public void startRecommendedWeightDownload() {
    startRecommendedWeightDownloadInternal();
  }

  private void configureButtons() {
    btnRefresh.setText(text("AutoSetup.refresh"));
    btnChooseLocalEngine.setText(text("AutoSetup.chooseExistingKataGo"));
    btnRepairAnalysisConfig.setText(text("AutoSetup.repairAnalysisConfig"));
    btnOpenAppFolder.setText(text("AutoSetup.openAppFolder"));
    btnViewFullDownloads.setText(text("AutoSetup.viewFullDownloads"));
    btnReloadRemoteWeights.setText("");
    btnReloadRemoteWeights.setIcon(new RefreshIcon());
    btnReloadRemoteWeights.setToolTipText(text("AutoSetup.refreshOfficialWeights"));
    btnDownloadWeight.setText(text("AutoSetup.downloadWeight"));
    btnDownloadWeight.setIcon(new WeightActionIcon(WeightActionIcon.DOWNLOAD));
    btnImportWeight.setText(text("AutoSetup.importWeight"));
    btnImportWeight.setIcon(new WeightActionIcon(WeightActionIcon.IMPORT));
    btnUseWeight.setText(text("AutoSetup.useWeight"));
    btnUseWeight.setIcon(new WeightActionIcon(WeightActionIcon.USE));
    btnDownloadHumanSlModel.setText(text("AutoSetup.downloadOnDemand"));
    btnDownloadHumanSlModel.setIcon(new WeightActionIcon(WeightActionIcon.DOWNLOAD));
    btnImportHumanSlModel.setText("");
    btnImportHumanSlModel.setIcon(new WeightActionIcon(WeightActionIcon.IMPORT));
    btnImportHumanSlModel.setToolTipText(text("AutoSetup.importHumanSlModel"));
    btnInstallNvidiaRuntime.setText(text("AutoSetup.installNvidiaRuntime"));
    btnInstallTensorRt.setText(text("AutoSetup.installTensorRt"));
    btnSwitchBackCuda.setText(text("AutoSetup.switchBackCuda"));
    btnCleanTensorRtCache.setText(text("AutoSetup.cleanTensorRtCache"));
    btnOptimizePerformance.setText(text("AutoSetup.optimizePerformance"));
    btnStopDownload.setText(text("AutoSetup.stopDownload"));
    btnStopDownload.setEnabled(false);
    btnClose.setText(text("AutoSetup.close"));
    btnOfficialWeightTab.setText(text("AutoSetup.officialWeightTab"));
    btnCustomWeightTab.setText(text("AutoSetup.customWeightTab"));

    styleButton(btnDownloadWeight, true);
    styleButton(btnOptimizePerformance, true);
    styleButton(btnRefresh, false);
    styleButton(btnChooseLocalEngine, true);
    styleButton(btnRepairAnalysisConfig, false);
    styleButton(btnOpenAppFolder, false);
    styleButton(btnViewFullDownloads, false);
    styleIconButton(btnReloadRemoteWeights);
    styleButton(btnImportWeight, false);
    styleButton(btnUseWeight, true);
    styleButton(btnDownloadHumanSlModel, false);
    styleButton(btnImportHumanSlModel, false);
    styleButton(btnInstallNvidiaRuntime, false);
    styleButton(btnInstallTensorRt, false);
    styleButton(btnSwitchBackCuda, false);
    styleButton(btnCleanTensorRtCache, false);
    styleButton(btnStopDownload, false);
    styleButton(btnClose, false);

    styleWeightButton(btnDownloadWeight, WeightButtonStyle.PRIMARY);
    styleWeightButton(btnUseWeight, WeightButtonStyle.PRIMARY);
    styleWeightButton(btnImportWeight, WeightButtonStyle.OUTLINE);
    styleWeightButton(btnDownloadHumanSlModel, WeightButtonStyle.PRIMARY);
    styleWeightButton(btnImportHumanSlModel, WeightButtonStyle.ICON);
    styleWeightButton(btnReloadRemoteWeights, WeightButtonStyle.ICON);
    styleWeightCatalogTab(btnOfficialWeightTab);
    styleWeightCatalogTab(btnCustomWeightTab);
  }

  private void configureWeightCatalog() {
    ButtonGroup tabs = new ButtonGroup();
    tabs.add(btnOfficialWeightTab);
    tabs.add(btnCustomWeightTab);
    btnOfficialWeightTab.setSelected(true);

    weightCatalogList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    weightCatalogList.setFixedCellHeight(WEIGHT_CATALOG_ROW_HEIGHT);
    weightCatalogList.setVisibleRowCount(WEIGHT_CATALOG_VISIBLE_ROWS);
    weightCatalogList.setBackground(CARD_BG);
    weightCatalogList.setForeground(TEXT_PRIMARY);
    weightCatalogList.setSelectionBackground(new Color(230, 244, 239));
    weightCatalogList.setSelectionForeground(TEXT_PRIMARY);
    weightCatalogList.setBorder(BorderFactory.createEmptyBorder());
    weightCatalogList.setCellRenderer(new WeightCatalogRenderer());
    weightCatalogList.addListSelectionListener(
        event -> {
          if (!event.getValueIsAdjusting()) {
            updateSelectedWeightInfo();
          }
        });
    MouseAdapter downloadActionHandler =
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent event) {
            updateWeightDownloadHover(event);
          }

          @Override
          public void mouseExited(MouseEvent event) {
            setHoveredWeightDownloadRow(-1);
          }

          @Override
          public void mouseClicked(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event)) {
              return;
            }
            int row = downloadableWeightRowAt(event);
            if (row < 0) {
              return;
            }
            weightCatalogList.setSelectedIndex(row);
            weightCatalogList.ensureIndexIsVisible(row);
            startRecommendedWeightDownloadInternal();
          }
        };
    weightCatalogList.addMouseListener(downloadActionHandler);
    weightCatalogList.addMouseMotionListener(downloadActionHandler);
    weightCatalogList
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke("ENTER"), "activate-selected-weight");
    weightCatalogList
        .getActionMap()
        .put(
            "activate-selected-weight",
            new AbstractAction() {
              @Override
              public void actionPerformed(java.awt.event.ActionEvent event) {
                activateSelectedWeight();
              }
            });
  }

  private void wireActions() {
    btnRefresh.addActionListener(e -> refreshState());
    btnChooseLocalEngine.addActionListener(e -> chooseExistingKataGo());
    btnRepairAnalysisConfig.addActionListener(e -> repairAnalysisConfig());
    btnOpenAppFolder.addActionListener(e -> openAppFolder());
    btnViewFullDownloads.addActionListener(e -> openFullPackageDownloads());
    btnReloadRemoteWeights.addActionListener(e -> reloadRemoteWeightInfo());
    btnDownloadWeight.addActionListener(e -> startRecommendedWeightDownloadInternal());
    btnImportWeight.addActionListener(e -> importCustomWeight());
    btnUseWeight.addActionListener(e -> useSelectedWeight());
    btnDownloadHumanSlModel.addActionListener(e -> startHumanSlModelDownload());
    btnImportHumanSlModel.addActionListener(e -> importHumanSlModel());
    btnInstallNvidiaRuntime.addActionListener(e -> startNvidiaRuntimeInstall());
    btnInstallTensorRt.addActionListener(e -> startTensorRtInstall());
    btnSwitchBackCuda.addActionListener(e -> switchBackToCuda());
    btnCleanTensorRtCache.addActionListener(e -> cleanTensorRtCache());
    btnOptimizePerformance.addActionListener(e -> startPerformanceBenchmark());
    btnStopDownload.addActionListener(e -> stopActiveDownload());
    btnClose.addActionListener(e -> closeOrCancelActiveTask());
    btnOfficialWeightTab.addActionListener(
        e -> switchWeightCatalogMode(WeightCatalogMode.OFFICIAL));
    btnCustomWeightTab.addActionListener(e -> switchWeightCatalogMode(WeightCatalogMode.CUSTOM));
    balancedRecommendation.setActivation(() -> activateRecommendation(balancedRecommendation));
    strongestRecommendation.setActivation(() -> activateRecommendation(strongestRecommendation));
    lightweightRecommendation.setActivation(
        () -> activateRecommendation(lightweightRecommendation));
  }

  private void styleButton(JFontButton button, boolean primary) {
    if (primary) {
      AppleStyleSupport.markPrimary(button);
    }
    button.setFocusPainted(false);
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.setMargin(new Insets(0, 10, 0, 10));
    AppleStyleSupport.installButtonStyle(button);
    Dimension preferred = localizedButtonSize(button, primary ? 106 : 90, 32);
    button.setPreferredSize(preferred);
    button.setMinimumSize(preferred);
  }

  static Dimension localizedButtonSize(
      AbstractButton button, int minimumWidth, int preferredHeight) {
    FontMetrics metrics = button.getFontMetrics(button.getFont());
    Insets insets = button.getInsets();
    String label = button.getText() == null ? "" : button.getText();
    int contentWidth = SwingUtilities.computeStringWidth(metrics, label);
    if (button.getIcon() != null) {
      contentWidth += button.getIcon().getIconWidth();
      if (!label.isEmpty()) {
        contentWidth += button.getIconTextGap();
      }
    }
    int localizedWidth = contentWidth + insets.left + insets.right + 12;
    return new Dimension(Math.max(minimumWidth, localizedWidth), preferredHeight);
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

  private void styleWeightButton(JFontButton button, WeightButtonStyle style) {
    button.setUI(new WeightButtonUI(style));
    int horizontalPadding = style == WeightButtonStyle.ICON ? 0 : 14;
    button.setBorder(BorderFactory.createEmptyBorder(0, horizontalPadding, 0, horizontalPadding));
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setOpaque(false);
    button.setRolloverEnabled(true);
    button.setForeground(style == WeightButtonStyle.PRIMARY ? Color.WHITE : TEXT_PRIMARY);
    int height = style == WeightButtonStyle.ICON ? 42 : 40;
    int width = style == WeightButtonStyle.ICON ? 42 : button.getPreferredSize().width;
    Dimension size = new Dimension(width, height);
    button.setPreferredSize(size);
    button.setMinimumSize(size);
    if (style == WeightButtonStyle.ICON) {
      button.setMaximumSize(size);
    }
  }

  private void styleWeightCatalogTab(JToggleButton button) {
    button.setUI(new WeightCatalogTabUI());
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setFocusPainted(false);
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.setFont(button.getFont().deriveFont(Font.BOLD));
    button.setForeground(TEXT_SECONDARY);
    button.setMargin(new Insets(0, 12, 0, 12));
    button.setPreferredSize(new Dimension(180, 34));
  }

  private JPanel createHeaderPanel() {
    JPanel header = new JPanel(new BorderLayout(12, 0));
    header.setBackground(SIDEBAR_BG);
    header.setBorder(BorderFactory.createEmptyBorder(10, isMacOs() ? 82 : 20, 10, 20));
    header.setPreferredSize(new Dimension(10, 62));

    JFontLabel icon = new JFontLabel();
    icon.setIcon(new WeightStoneIcon(42));
    header.add(icon, BorderLayout.WEST);

    JFontLabel title = new JFontLabel(text("AutoSetup.title"));
    title.setForeground(new Color(243, 213, 158));
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 7f));
    header.add(title, BorderLayout.CENTER);
    return header;
  }

  private JPanel createSidebarPanel() {
    JPanel sidebar = new SidebarBackdropPanel();
    sidebar.setPreferredSize(new Dimension(218, 10));
    sidebar.setBorder(BorderFactory.createEmptyBorder(20, 12, 16, 12));

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
    sectionNav.setFixedCellHeight(58);
    sectionNav.setOpaque(false);
    sectionNav.setBackground(new Color(0, 0, 0, 0));
    sectionNav.setForeground(SIDEBAR_TEXT);
    sectionNav.setBorder(null);
    sectionNav.setCellRenderer(new SidebarNavRenderer());
    sectionNav.addListSelectionListener(
        e -> {
          if (e.getValueIsAdjusting()) {
            return;
          }
          int selectedIndex = sectionNav.getSelectedIndex();
          switch (selectedIndex) {
            case 1:
              selectedSetupSectionIndex = selectedIndex;
              showDetailCard(CARD_WEIGHTS);
              break;
            case 2:
              selectedSetupSectionIndex = selectedIndex;
              showDetailCard(CARD_BENCHMARK);
              break;
            case 3:
              selectedSetupSectionIndex = selectedIndex;
              showDetailCard(CARD_ACCELERATION);
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
              showDetailCard(CARD_OVERVIEW);
              break;
          }
        });
    sidebar.add(sectionNav, BorderLayout.CENTER);
    return sidebar;
  }

  private void showDetailCard(String cardName) {
    detailCardLayout.show(detailCards, cardName);
    detailCards.revalidate();
    detailCards.repaint();
  }

  private JPanel createOverviewSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addInfoRow(rows, gbc, text("AutoSetup.localEngine"), lblEngineValue);
    addInfoRow(rows, gbc, text("AutoSetup.localWeight"), lblWeightValue);
    addInfoRow(rows, gbc, text("AutoSetup.localWeightModel"), lblWeightModelValue);
    addInfoRow(rows, gbc, text("AutoSetup.gtpConfig"), lblGtpConfigValue);
    addInfoRow(rows, gbc, text("AutoSetup.analysisConfig"), lblAnalysisConfigValue);
    addInfoRow(rows, gbc, text("AutoSetup.discoverySource"), lblDiscoverySourceValue);
    addInfoRow(rows, gbc, text("AutoSetup.packageType"), lblPackageFlavorValue);
    addInfoRow(rows, gbc, text("AutoSetup.engineValidation"), lblEngineValidationValue);

    JPanel actions =
        createActionBar(
            FlowLayout.RIGHT,
            btnChooseLocalEngine,
            btnRepairAnalysisConfig,
            btnOpenAppFolder,
            btnViewFullDownloads,
            btnRefresh);
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
          this, e.getMessage(), text("NetworkProxy.settingsTitle"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    dialog.setVisible(true);
    dialog.toFront();
  }

  private JPanel createWeightsSection() {
    JPanel page = new JPanel(new BorderLayout(0, 10));
    page.setOpaque(false);
    page.add(
        createPageHeading(text("AutoSetup.weightsTitle"), text("AutoSetup.weightsSubtitle")),
        BorderLayout.NORTH);

    JPanel content = new JPanel(new GridBagLayout());
    content.setOpaque(false);
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.anchor = GridBagConstraints.NORTHWEST;
    content.add(createCurrentWeightBanner(), constraints);

    constraints.gridy++;
    constraints.insets = new Insets(8, 0, 0, 0);
    content.add(createWeightRecommendations(), constraints);

    constraints.gridy++;
    constraints.insets = new Insets(8, 0, 0, 0);
    content.add(createWeightCatalogBlock(), constraints);

    constraints.gridy++;
    constraints.insets = new Insets(8, 0, 0, 0);
    content.add(createHumanSlModelBlock(), constraints);

    JPanel contentWrap = new JPanel(new BorderLayout());
    contentWrap.setOpaque(false);
    contentWrap.add(content, BorderLayout.NORTH);
    page.add(contentWrap, BorderLayout.CENTER);
    return page;
  }

  private JPanel createWeightRecommendations() {
    JPanel section = new JPanel(new BorderLayout(0, 6));
    section.setOpaque(false);
    JFontLabel title = new JFontLabel(text("AutoSetup.recommendationTitle"));
    title.setForeground(TEXT_PRIMARY);
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
    section.add(title, BorderLayout.NORTH);

    JPanel cards = new JPanel(new GridBagLayout());
    cards.setOpaque(false);
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridy = 0;
    gbc.weightx = 1;
    gbc.fill = GridBagConstraints.BOTH;
    gbc.insets = new Insets(0, 0, 0, 7);
    cards.add(balancedRecommendation, gbc);
    gbc.gridx = 1;
    gbc.insets = new Insets(0, 7, 0, 7);
    cards.add(strongestRecommendation, gbc);
    gbc.gridx = 2;
    gbc.insets = new Insets(0, 7, 0, 0);
    cards.add(lightweightRecommendation, gbc);
    section.add(cards, BorderLayout.CENTER);
    return section;
  }

  private JPanel createPageHeading(String title, String subtitle) {
    JPanel heading = new JPanel(new BorderLayout(0, 4));
    heading.setOpaque(false);
    JFontLabel titleLabel = new JFontLabel(title);
    titleLabel.setForeground(TEXT_PRIMARY);
    titleLabel.setFont(
        titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 8f));
    JFontLabel subtitleLabel = new JFontLabel(subtitle);
    subtitleLabel.setForeground(TEXT_SECONDARY);
    subtitleLabel.setFont(
        subtitleLabel.getFont().deriveFont(subtitleLabel.getFont().getSize2D() + 1f));
    heading.add(titleLabel, BorderLayout.NORTH);
    heading.add(subtitleLabel, BorderLayout.CENTER);
    return heading;
  }

  private JPanel createCurrentWeightBanner() {
    JPanel banner = new RoundedSurfacePanel(CARD_BG, new Color(214, 190, 148), 16, true);
    banner.setLayout(new BorderLayout(16, 0));
    banner.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
    banner.setPreferredSize(new Dimension(10, 72));

    JFontLabel icon = new JFontLabel();
    icon.setIcon(new WeightStoneIcon(48, true));
    banner.add(icon, BorderLayout.WEST);

    JFontLabel caption = new JFontLabel(text("AutoSetup.currentWeight"));
    caption.setForeground(new Color(160, 109, 31));
    caption.setFont(caption.getFont().deriveFont(caption.getFont().getSize2D() + 1f));
    lblCurrentWeightName.setForeground(TEXT_PRIMARY);
    lblCurrentWeightName.setFont(
        lblCurrentWeightName
            .getFont()
            .deriveFont(Font.BOLD, lblCurrentWeightName.getFont().getSize2D() + 5f));
    JPanel labels = new JPanel(new BorderLayout(0, 2));
    labels.setOpaque(false);
    labels.add(caption, BorderLayout.NORTH);
    labels.add(lblCurrentWeightName, BorderLayout.CENTER);
    banner.add(labels, BorderLayout.CENTER);

    lblCurrentWeightStatus.setHorizontalAlignment(SwingConstants.CENTER);
    lblCurrentWeightStatus.setForeground(OK_COLOR);
    lblCurrentWeightStatus.setFont(lblCurrentWeightStatus.getFont().deriveFont(Font.BOLD));
    lblCurrentWeightStatus.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
    banner.add(lblCurrentWeightStatus, BorderLayout.EAST);
    return banner;
  }

  private JPanel createWeightCatalogBlock() {
    JPanel section = new JPanel(new BorderLayout(0, 5));
    section.setOpaque(false);
    JFontLabel title = new JFontLabel(text("AutoSetup.moreWeights"));
    title.setForeground(TEXT_PRIMARY);
    title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
    section.add(title, BorderLayout.NORTH);

    JPanel block = new RoundedSurfacePanel(CARD_BG, new Color(218, 205, 181), 13, false);
    block.setLayout(new BorderLayout(0, 0));
    block.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    block.add(createWeightCatalogTabs(), BorderLayout.NORTH);
    block.add(createWeightCatalogTable(), BorderLayout.CENTER);
    block.add(createSelectedWeightDetailBar(), BorderLayout.SOUTH);
    section.add(block, BorderLayout.CENTER);
    return section;
  }

  private JPanel createWeightCatalogTabs() {
    JPanel row = new JPanel(new BorderLayout(8, 0));
    row.setOpaque(false);
    JPanel tabs = new JPanel(new GridBagLayout());
    tabs.setOpaque(false);
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridy = 0;
    gbc.weightx = 1;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    tabs.add(btnOfficialWeightTab, gbc);
    gbc.gridx = 1;
    tabs.add(btnCustomWeightTab, gbc);
    row.add(tabs, BorderLayout.CENTER);

    weightCatalogActionCards.setOpaque(false);
    weightCatalogActionCards.add(btnReloadRemoteWeights, WeightCatalogMode.OFFICIAL.cardName);
    weightCatalogActionCards.add(btnImportWeight, WeightCatalogMode.CUSTOM.cardName);
    row.add(weightCatalogActionCards, BorderLayout.EAST);
    return row;
  }

  private JPanel createWeightCatalogTable() {
    JPanel table = new JPanel(new BorderLayout());
    table.setOpaque(false);
    table.setBorder(BorderFactory.createEmptyBorder(4, 0, 5, 0));
    table.add(createWeightCatalogColumnHeader(), BorderLayout.NORTH);

    weightCatalogScrollPane.setBorder(
        BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(225, 219, 207)));
    weightCatalogScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    weightCatalogScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
    weightCatalogScrollPane.getVerticalScrollBar().setUnitIncrement(WEIGHT_CATALOG_ROW_HEIGHT);
    weightCatalogScrollPane.getVerticalScrollBar().setUI(new WeightCatalogScrollBarUI());
    weightCatalogScrollPane.getViewport().setBackground(CARD_BG);
    weightCatalogScrollPane.setPreferredSize(
        new Dimension(10, weightCatalogVisibleHeight(WEIGHT_CATALOG_VISIBLE_ROWS)));

    lblWeightCatalogEmpty.setHorizontalAlignment(SwingConstants.CENTER);
    lblWeightCatalogEmpty.setForeground(TEXT_SECONDARY);
    lblWeightCatalogEmpty.setBorder(BorderFactory.createEmptyBorder(18, 12, 18, 12));
    JPanel empty = new JPanel(new BorderLayout());
    empty.setBackground(CARD_BG);
    empty.add(lblWeightCatalogEmpty, BorderLayout.CENTER);

    weightCatalogContentCards.setOpaque(false);
    weightCatalogContentCards.add(weightCatalogScrollPane, "list");
    weightCatalogContentCards.add(empty, "empty");
    weightCatalogContentCards.setPreferredSize(weightCatalogScrollPane.getPreferredSize());
    table.add(weightCatalogContentCards, BorderLayout.CENTER);
    return table;
  }

  private JPanel createWeightCatalogColumnHeader() {
    JFontLabel model = createWeightCatalogHeaderLabel(text("AutoSetup.weightColumnModel"));
    JFontLabel elo = createWeightCatalogHeaderLabel(text("AutoSetup.weightColumnElo"));
    JFontLabel date = createWeightCatalogHeaderLabel(text("AutoSetup.weightColumnReleaseDate"));
    JFontLabel status = createWeightCatalogHeaderLabel(text("AutoSetup.weightColumnStatus"));
    JPanel header = new JPanel();
    header.setOpaque(false);
    header.setBorder(BorderFactory.createEmptyBorder(1, 14, 3, 14));
    configureWeightCatalogColumns(header, model, elo, date, status);
    return header;
  }

  private JFontLabel createWeightCatalogHeaderLabel(String value) {
    JFontLabel label = new JFontLabel(value);
    label.setForeground(new Color(123, 121, 113));
    label.setFont(label.getFont().deriveFont(label.getFont().getSize2D() - 1f));
    return label;
  }

  private JPanel createSelectedWeightDetailBar() {
    JPanel detail =
        new RoundedSurfacePanel(new Color(253, 249, 241), new Color(225, 210, 184), 11, false);
    detail.setLayout(new BorderLayout(10, 0));
    detail.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 8));
    JFontLabel icon = new JFontLabel();
    icon.setIcon(new WeightStoneIcon(38, true));
    detail.add(icon, BorderLayout.WEST);

    lblSelectedWeightName.setForeground(TEXT_PRIMARY);
    lblSelectedWeightName.setFont(lblSelectedWeightName.getFont().deriveFont(Font.BOLD));
    lblSelectedWeightMeta.setForeground(TEXT_SECONDARY);
    lblSelectedWeightMeta.setFont(
        lblSelectedWeightMeta
            .getFont()
            .deriveFont(lblSelectedWeightMeta.getFont().getSize2D() - 1f));
    JPanel labels = new JPanel(new BorderLayout(0, 1));
    labels.setOpaque(false);
    labels.add(lblSelectedWeightName, BorderLayout.NORTH);
    labels.add(lblSelectedWeightMeta, BorderLayout.CENTER);
    detail.add(labels, BorderLayout.CENTER);

    selectedWeightActions.setOpaque(false);
    selectedWeightActions.add(btnDownloadWeight);
    selectedWeightActions.add(btnUseWeight);
    detail.add(selectedWeightActions, BorderLayout.EAST);
    return detail;
  }

  private JPanel createHumanSlModelBlock() {
    JPanel block = createWeightBlock();
    JFontLabel icon = new JFontLabel();
    icon.setIcon(new HumanSlIcon());
    block.add(icon, BorderLayout.WEST);
    JFontLabel title = new JFontLabel(text("AutoSetup.humanSlModel"));
    title.setForeground(TEXT_PRIMARY);
    title.setFont(title.getFont().deriveFont(Font.BOLD));
    lblHumanSlModelValue.setForeground(TEXT_SECONDARY);
    JPanel labels = new JPanel(new BorderLayout(0, 2));
    labels.setOpaque(false);
    labels.add(title, BorderLayout.NORTH);
    labels.add(lblHumanSlModelValue, BorderLayout.CENTER);
    block.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 12));
    block.add(labels, BorderLayout.CENTER);

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    actions.setOpaque(false);
    actions.add(lblHumanSlStatus);
    actions.add(btnDownloadHumanSlModel);
    actions.add(btnImportHumanSlModel);
    block.add(actions, BorderLayout.EAST);
    return block;
  }

  private JPanel createWeightBlock() {
    JPanel block = new RoundedSurfacePanel(CARD_BG, new Color(218, 205, 181), 13, false);
    block.setLayout(new BorderLayout(8, 6));
    block.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    return block;
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
    footerPanel.setOpaque(true);
    footerPanel.setBackground(APP_BG);
    footerPanel.setBorder(BorderFactory.createEmptyBorder(8, 28, 12, 30));
    footerPanel.setVisible(false);

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
    footerPanel.add(progressPanel, BorderLayout.NORTH);

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
    footerPanel.add(statusBar, BorderLayout.SOUTH);
    return footerPanel;
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
    int columns = actions.length > 2 ? 2 : Math.max(1, actions.length);
    return createActionBar(alignment, columns, actions);
  }

  private static JPanel createActionBar(int alignment, int columns, JComponent... actions) {
    JPanel actionBar = new JPanel(new BorderLayout());
    actionBar.setOpaque(false);

    JPanel actionGrid = new JPanel(new GridBagLayout());
    actionGrid.setOpaque(false);
    int safeColumns = Math.max(1, columns);
    for (int index = 0; index < actions.length; index++) {
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridx = index % safeColumns;
      constraints.gridy = index / safeColumns;
      constraints.anchor = GridBagConstraints.EAST;
      constraints.insets =
          new Insets(constraints.gridy == 0 ? 0 : 6, constraints.gridx == 0 ? 0 : 6, 0, 0);
      actionGrid.add(actions[index], constraints);
    }

    actionBar.add(
        actionGrid, alignment == FlowLayout.LEFT ? BorderLayout.LINE_START : BorderLayout.LINE_END);
    return actionBar;
  }

  static JPanel createResponsiveActionRow(JComponent mainComponent, JComponent... actions) {
    return new ResponsiveActionRow(mainComponent, actions);
  }

  static final class ViewportWidthPanel extends JPanel implements Scrollable {
    ViewportWidthPanel(CardLayout layout) {
      super(layout);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
      return 14;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
      return Math.max(14, visibleRect.height - 14);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
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
    renderCurrentWeightBanner();
    setInfoValue(lblEngineValue, snapshot.hasEngine(), formatPath(snapshot.enginePath));
    setInfoValue(lblWeightValue, snapshot.hasWeight(), formatWeight(snapshot));
    setInfoValue(lblWeightModelValue, snapshot.hasWeight(), formatWeightModel(snapshot));
    renderLocalWeights();
    renderHumanSlModel();
    setInfoValue(
        lblGtpConfigValue,
        snapshot.gtpConfigPath != null && Files.isRegularFile(snapshot.gtpConfigPath),
        formatPath(snapshot.gtpConfigPath));
    setInfoValue(
        lblAnalysisConfigValue,
        snapshot.analysisConfigPath != null && Files.isRegularFile(snapshot.analysisConfigPath),
        formatPath(snapshot.analysisConfigPath));
    renderDiscoveryDetails();
    updateNvidiaRuntimeInfo();
    updateBenchmarkInfo();
    renderWeightRecommendations();
    updateSelectedWeightInfo();

    renderSetupStatus();
  }

  private void renderDiscoveryDetails() {
    LocalKataGoDiscoveryResult discovery = snapshot == null ? null : snapshot.discovery;
    DiscoverySource source = discovery == null ? DiscoverySource.NONE : discovery.source;
    PackageFlavor flavor = discovery == null ? PackageFlavor.UNKNOWN : discovery.packageFlavor;
    String sourceText = discoverySourceText(source);
    if (discovery != null && !Utils.isBlank(discovery.sourceName)) {
      sourceText += " · " + discovery.sourceName;
    }
    setInfoValue(lblDiscoverySourceValue, source != DiscoverySource.NONE, sourceText);
    setInfoValue(
        lblPackageFlavorValue,
        flavor != PackageFlavor.INCOMPLETE_BUNDLE && flavor != PackageFlavor.CORE_UPDATE_ONLY,
        packageFlavorText(flavor));

    boolean hasGtp = snapshot != null && isRegularFile(snapshot.gtpConfigPath);
    boolean hasAnalysis = snapshot != null && isRegularFile(snapshot.analysisConfigPath);
    btnRepairAnalysisConfig.setVisible(hasGtp && !hasAnalysis);
    btnChooseLocalEngine.setVisible(
        snapshot == null
            || !snapshot.hasEngine()
            || !hasGtp
            || !snapshot.hasWeight()
            || flavor == PackageFlavor.WITHOUT_ENGINE
            || flavor == PackageFlavor.CORE_UPDATE_ONLY);
    boolean incompletePackage = flavor == PackageFlavor.INCOMPLETE_BUNDLE;
    btnOpenAppFolder.setVisible(incompletePackage || flavor == PackageFlavor.CORE_UPDATE_ONLY);
    btnViewFullDownloads.setVisible(incompletePackage || flavor == PackageFlavor.CORE_UPDATE_ONLY);
    if (snapshot == null || !snapshot.hasEngine()) {
      setInfoValue(lblEngineValidationValue, false, text("AutoSetup.validationNotAvailable"));
    } else {
      lblEngineValidationValue.setText(text("AutoSetup.validationChecking"));
      lblEngineValidationValue.setToolTipText(null);
      lblEngineValidationValue.setForeground(WARN_COLOR);
    }
  }

  static boolean isRegularFile(Path path) {
    return path != null && Files.isRegularFile(path);
  }

  private void renderSetupStatus() {
    lblStatus.setToolTipText(null);
    PackageFlavor flavor =
        snapshot == null || snapshot.discovery == null
            ? PackageFlavor.UNKNOWN
            : snapshot.discovery.packageFlavor;
    if (flavor == PackageFlavor.WITHOUT_ENGINE) {
      lblStatus.setText(text("AutoSetup.withoutEnginePackage"));
      lblStatus.setForeground(WARN_COLOR);
    } else if (flavor == PackageFlavor.CORE_UPDATE_ONLY) {
      lblStatus.setText(text("AutoSetup.coreUpdateStandalone"));
      lblStatus.setForeground(ERROR_COLOR);
    } else if (flavor == PackageFlavor.INCOMPLETE_BUNDLE) {
      lblStatus.setText(text("AutoSetup.incompletePackage"));
      lblStatus.setForeground(ERROR_COLOR);
    } else if (snapshot != null
        && snapshot.hasEngine()
        && snapshot.hasConfigs()
        && snapshot.hasWeight()) {
      lblStatus.setText(text("AutoSetup.ready"));
      lblStatus.setForeground(OK_COLOR);
    } else if (snapshot == null || !snapshot.hasWeight()) {
      lblStatus.setText(text("AutoSetup.needWeight"));
      lblStatus.setForeground(WARN_COLOR);
    } else {
      lblStatus.setText(text("AutoSetup.needSetup"));
      lblStatus.setForeground(ERROR_COLOR);
    }
  }

  private void renderCurrentWeightBanner() {
    if (snapshot != null && snapshot.hasWeight()) {
      String displayName = formatWeightModel(snapshot);
      lblCurrentWeightName.setText(displayName);
      lblCurrentWeightName.setToolTipText(
          snapshot.activeWeightPath.toAbsolutePath().normalize().toString());
      lblCurrentWeightName.setForeground(TEXT_PRIMARY);
      lblCurrentWeightStatus.setText(text("AutoSetup.currentlyUsingShort"));
      lblCurrentWeightStatus.setForeground(OK_COLOR);
      return;
    }
    lblCurrentWeightName.setText(text("AutoSetup.notFound"));
    lblCurrentWeightName.setToolTipText(null);
    lblCurrentWeightName.setForeground(ERROR_COLOR);
    lblCurrentWeightStatus.setText(text("AutoSetup.notReady"));
    lblCurrentWeightStatus.setForeground(ERROR_COLOR);
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
    rebuildWeightCatalog(null);
  }

  private void renderHumanSlModel() {
    KataGoAutoSetupHelper.HumanSlModelStatus status = KataGoAutoSetupHelper.inspectHumanSlModel();
    String release = text("AutoSetup.humanSlModelRelease");
    lblHumanSlModelValue.setText(text("AutoSetup.humanSlModelDescription") + "  ·  " + release);
    if (status.isInstalled()) {
      String path = status.modelPath.toAbsolutePath().normalize().toString();
      lblHumanSlModelValue.setToolTipText(path);
      lblHumanSlModelValue.setForeground(TEXT_SECONDARY);
      lblHumanSlStatus.setStatus(text("AutoSetup.humanSlModelDownloaded"), StatusTagTone.SUCCESS);
      btnDownloadHumanSlModel.setText(text("AutoSetup.humanSlModelDownloaded"));
      btnDownloadHumanSlModel.setEnabled(false);
      btnDownloadHumanSlModel.setVisible(false);
    } else {
      lblHumanSlModelValue.setToolTipText(text("AutoSetup.humanSlModelMissing"));
      lblHumanSlModelValue.setForeground(TEXT_SECONDARY);
      lblHumanSlStatus.setStatus(text("AutoSetup.notDownloaded"), StatusTagTone.GOLD);
      btnDownloadHumanSlModel.setText(text("AutoSetup.downloadOnDemand"));
      btnDownloadHumanSlModel.setVisible(true);
      btnDownloadHumanSlModel.setEnabled(
          activeDownloadSession == null && activeWorkerThread == null);
    }
    btnImportHumanSlModel.setEnabled(activeDownloadSession == null && activeWorkerThread == null);
  }

  private String discoverySourceText(DiscoverySource source) {
    if (source == null) {
      return text("AutoSetup.sourceNone");
    }
    switch (source) {
      case CURRENT_ENGINE:
        return text("AutoSetup.sourceCurrentEngine");
      case STARTUP_ENGINE:
        return text("AutoSetup.sourceStartupEngine");
      case DEFAULT_ENGINE:
        return text("AutoSetup.sourceDefaultEngine");
      case REMEMBERED_SETUP:
        return text("AutoSetup.sourceRememberedSetup");
      case ANALYSIS_COMMAND:
        return text("AutoSetup.sourceAnalysisCommand");
      case BUNDLED_PACKAGE:
        return text("AutoSetup.sourceBundledPackage");
      case MANUAL_SELECTION:
        return text("AutoSetup.sourceManualSelection");
      default:
        return text("AutoSetup.sourceNone");
    }
  }

  private String packageFlavorText(PackageFlavor flavor) {
    if (flavor == null) {
      return text("AutoSetup.packageUnknown");
    }
    switch (flavor) {
      case OPENCL:
        return text("AutoSetup.packageOpenCl");
      case NVIDIA:
        return text("AutoSetup.packageNvidia");
      case NVIDIA50_CUDA:
        return text("AutoSetup.packageNvidia50Cuda");
      case TENSORRT:
        return text("AutoSetup.packageTensorRt");
      case CPU:
        return text("AutoSetup.packageCpu");
      case WITH_KATAGO:
        return text("AutoSetup.packageWithKataGo");
      case WITHOUT_ENGINE:
        return text("AutoSetup.packageWithoutEngine");
      case CORE_UPDATE_ONLY:
        return text("AutoSetup.packageCoreUpdateOnly");
      case INCOMPLETE_BUNDLE:
        return text("AutoSetup.packageIncomplete");
      case EXTERNAL:
        return text("AutoSetup.packageExternal");
      default:
        return text("AutoSetup.packageUnknown");
    }
  }

  private void chooseExistingKataGo() {
    Path initialDirectory =
        snapshot != null && snapshot.enginePath != null
            ? snapshot.enginePath.getParent()
            : snapshot == null ? null : snapshot.appRoot;
    JFileChooser chooser = createLocalSetupFileChooser(initialDirectory);
    chooser.setDialogTitle(text("AutoSetup.chooseKataGoExecutable"));
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      chooser.setFileFilter(
          new FileNameExtensionFilter(text("AutoSetup.kataGoExecutableFilter"), "exe"));
    }
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return;
    }
    Path enginePath = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
    LocalKataGoDiscoveryResult selected =
        KataGoAutoSetupHelper.inspectSelectedLocalKataGo(enginePath, null, null);
    Path gtpConfigPath = selected.gtpConfigPath;
    if (gtpConfigPath == null || !Files.isRegularFile(gtpConfigPath)) {
      gtpConfigPath =
          chooseSupportingFile(
              text("AutoSetup.chooseGtpConfig"), enginePath.getParent(), "cfg", "gtp.cfg");
      if (gtpConfigPath == null) {
        return;
      }
      selected =
          KataGoAutoSetupHelper.inspectSelectedLocalKataGo(
              enginePath, gtpConfigPath, selected.activeWeightPath);
    }
    Path weightPath = selected.activeWeightPath;
    if (weightPath == null || !Files.isRegularFile(weightPath)) {
      weightPath =
          chooseSupportingFile(
              text("AutoSetup.chooseWeight"), enginePath.getParent(), "gz", ".bin.gz / .txt.gz");
      if (weightPath == null) {
        return;
      }
      selected =
          KataGoAutoSetupHelper.inspectSelectedLocalKataGo(
              enginePath, selected.gtpConfigPath, weightPath);
    }
    try {
      KataGoAutoSetupHelper.rememberSelectedLocalKataGo(selected);
      snapshot = selected.toSnapshot();
      engineValidationResult = null;
      renderSnapshot();
      validateDiscoveredEngineAsync();
    } catch (IOException e) {
      showLocalSetupError(e);
    }
  }

  private JFileChooser createLocalSetupFileChooser(Path initialDirectory) {
    JFileChooser chooser =
        initialDirectory != null && Files.isDirectory(initialDirectory)
            ? new JFileChooser(initialDirectory.toFile())
            : new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
    chooser.setAcceptAllFileFilterUsed(true);
    return chooser;
  }

  private Path chooseSupportingFile(
      String title, Path initialDirectory, String extension, String description) {
    JFileChooser chooser = createLocalSetupFileChooser(initialDirectory);
    chooser.setDialogTitle(title);
    chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
      return null;
    }
    return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
  }

  private void repairAnalysisConfig() {
    btnRepairAnalysisConfig.setEnabled(false);
    new Thread(
            () -> {
              try {
                Path repaired = KataGoAutoSetupHelper.repairAnalysisConfig(snapshot);
                LocalKataGoDiscoveryResult selected =
                    KataGoAutoSetupHelper.inspectSelectedLocalKataGo(
                        snapshot.enginePath, snapshot.gtpConfigPath, snapshot.activeWeightPath);
                KataGoAutoSetupHelper.rememberSelectedLocalKataGo(selected);
                SwingUtilities.invokeLater(
                    () -> {
                      refreshState();
                      lblStatus.setText(
                          text("AutoSetup.analysisConfigRepaired")
                              + " · "
                              + repaired.getFileName());
                      lblStatus.setForeground(OK_COLOR);
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      btnRepairAnalysisConfig.setEnabled(true);
                      showLocalSetupError(e);
                    });
              }
            },
            "katago-analysis-config-repair")
        .start();
  }

  private void openAppFolder() {
    Path directory = snapshot == null ? null : snapshot.appRoot;
    if (directory == null || !Files.isDirectory(directory)) {
      showLocalSetupError(new IOException(text("AutoSetup.appFolderUnavailable")));
      return;
    }
    try {
      if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        throw new IOException(text("AutoSetup.openFolderUnsupported"));
      }
      Desktop.getDesktop().open(directory.toFile());
    } catch (IOException e) {
      showLocalSetupError(e);
    }
  }

  private void openFullPackageDownloads() {
    try {
      if (!Desktop.isDesktopSupported()
          || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        throw new IOException(text("AutoSetup.openBrowserUnsupported"));
      }
      Desktop.getDesktop()
          .browse(URI.create("https://github.com/wimi321/lizzieyzy-next/releases/latest"));
    } catch (IOException | RuntimeException e) {
      showLocalSetupError(e);
    }
  }

  private void showLocalSetupError(Exception error) {
    String detail = error == null || error.getMessage() == null ? "" : error.getMessage().trim();
    lblStatus.setText(detail.isEmpty() ? text("AutoSetup.failed") : detail);
    lblStatus.setForeground(ERROR_COLOR);
    JOptionPane.showMessageDialog(
        this,
        detail.isEmpty() ? text("AutoSetup.failed") : detail,
        text("AutoSetup.title"),
        JOptionPane.ERROR_MESSAGE);
  }

  private void validateDiscoveredEngineAsync() {
    final long requestId = ++engineValidationRequestId;
    final Path enginePath = snapshot == null ? null : snapshot.enginePath;
    if (enginePath == null || !Files.isRegularFile(enginePath)) {
      engineValidationResult = null;
      return;
    }
    btnUseWeight.setEnabled(false);
    btnOptimizePerformance.setEnabled(false);
    new Thread(
            () -> {
              EngineValidationResult result =
                  KataGoAutoSetupHelper.validateLocalEngine(enginePath, 8L);
              SwingUtilities.invokeLater(
                  () -> {
                    if (requestId != engineValidationRequestId
                        || snapshot == null
                        || snapshot.enginePath == null
                        || !snapshot
                            .enginePath
                            .toAbsolutePath()
                            .normalize()
                            .equals(enginePath.toAbsolutePath().normalize())) {
                      return;
                    }
                    engineValidationResult = result;
                    renderEngineValidation(result);
                    refreshIdleControls();
                  });
            },
            "katago-version-validation")
        .start();
  }

  private void renderEngineValidation(EngineValidationResult result) {
    if (result == null || result.status == EngineValidationStatus.NOT_RUN) {
      setInfoValue(lblEngineValidationValue, false, text("AutoSetup.validationNotAvailable"));
      return;
    }
    String value;
    switch (result.status) {
      case VALID:
        value = text("AutoSetup.validationPassed");
        break;
      case MISSING_DEPENDENCY:
        value = text("AutoSetup.validationMissingDependency");
        break;
      case WRONG_ARCHITECTURE:
        value = text("AutoSetup.validationWrongArchitecture");
        break;
      case TIMED_OUT:
        value = text("AutoSetup.validationTimedOut");
        break;
      default:
        value = text("AutoSetup.validationStartFailed");
        break;
    }
    lblEngineValidationValue.setText(value);
    lblEngineValidationValue.setToolTipText(Utils.isBlank(result.detail) ? value : result.detail);
    lblEngineValidationValue.setForeground(result.isValid() ? OK_COLOR : ERROR_COLOR);
    if (!result.isValid()) {
      lblStatus.setText(value);
      lblStatus.setToolTipText(result.detail);
      lblStatus.setForeground(ERROR_COLOR);
    }
  }

  private void updateNvidiaRuntimeInfo() {
    KataGoRuntimeHelper.NvidiaRuntimeStatus status =
        snapshot == null ? null : KataGoRuntimeHelper.inspectNvidiaRuntime(snapshot);
    if (status == null || !status.applicable) {
      setWrappedInfoText(lblNvidiaRuntimeValue, text("AutoSetup.nvidiaRuntimeNotApplicable"));
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
      setTensorRtLabel(lblTensorRtDownloadValue, notApplicable, Color.DARK_GRAY, status.detailText);
      setTensorRtLabel(lblTensorRtConfigValue, notApplicable, Color.DARK_GRAY, status.detailText);
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
    int twoLineHeight = Math.max(32, label.getFontMetrics(label.getFont()).getHeight() * 2 + 4);
    int height = Math.max(twoLineHeight, label.getPreferredSize().height);
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
    btnOptimizePerformance.setEnabled(
        canRunBenchmark() && activeWorkerThread == null && activeDownloadSession == null);
  }

  private void loadRemoteWeightInfo() {
    btnReloadRemoteWeights.setEnabled(false);
    if (remoteWeightInfos.isEmpty()) {
      lblWeightCatalogEmpty.setText(text("AutoSetup.loadingRemote"));
      showWeightCatalogEmptyState(true);
    }
    new Thread(
            () -> {
              try {
                List<RemoteWeightInfo> fetched = KataGoAutoSetupHelper.fetchOfficialWeights();
                SwingUtilities.invokeLater(() -> showRemoteWeightInfo(fetched));
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      if (remoteWeightInfos.isEmpty()) {
                        rebuildWeightCatalog(null);
                        lblWeightCatalogEmpty.setText(text("AutoSetup.remoteUnavailable"));
                        lblWeightCatalogEmpty.setToolTipText(e.getMessage());
                        showWeightCatalogEmptyState(true);
                        btnDownloadWeight.setEnabled(false);
                      } else {
                        updateSelectedWeightInfo();
                        lblStatus.setText(text("AutoSetup.remoteRefreshFailedCached"));
                        lblStatus.setToolTipText(e.getMessage());
                        lblStatus.setForeground(WARN_COLOR);
                      }
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
    RemoteWeightInfo previousSelection = getSelectedRemoteWeight();
    String previousModel = previousSelection == null ? "" : previousSelection.modelName;
    remoteWeightInfos = infos == null ? Collections.<RemoteWeightInfo>emptyList() : infos;
    RemoteWeightInfo preferred = findRemoteWeight(previousModel);
    if (preferred == null) {
      preferred = choosePreferredRemoteWeight();
    }
    rebuildWeightCatalog(preferred == null ? null : WeightCatalogEntry.officialKey(preferred));
    renderWeightRecommendations();
    updateSelectedWeightInfo();
    lblStatus.setToolTipText(null);
    if (!hasActiveBackgroundTask()) {
      renderSetupStatus();
    }
    btnReloadRemoteWeights.setEnabled(true);
    btnStopDownload.setEnabled(activeDownloadSession != null);
  }

  private RemoteWeightInfo findRemoteWeight(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) {
      return null;
    }
    for (RemoteWeightInfo info : remoteWeightInfos) {
      if (matchesModelName(info, modelName)) {
        return info;
      }
    }
    return null;
  }

  private void useSelectedWeight() {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    Path selectedWeight = getSelectedLocalWeight();
    if (selectedWeight == null) {
      Utils.showMsg(text("AutoSetup.missingWeight"), this);
      return;
    }
    if (isCurrentLocalWeight(selectedWeight)) {
      updateSelectedLocalWeightInfo();
      return;
    }
    prepareWeightEngineSwitch(snapshot.withActiveWeight(selectedWeight));
  }

  private void prepareWeightEngineSwitch(SetupSnapshot requestedSnapshot) {
    if (hasActiveBackgroundTask()) {
      showBackgroundTaskAlreadyRunningNotice();
      return;
    }
    pendingWeightSwitchShouldResumeQuickAnalysis = false;
    LizzieFrame frame = Lizzie.frame;
    AnalysisEngine quickAnalysis = frame == null ? null : frame.analysisEngine;
    Leelaz foregroundEngine = Lizzie.leelaz;
    boolean analysisInProgress = quickAnalysis != null && quickAnalysis.isAnalysisInProgress();
    boolean silentAnalysis = quickAnalysis != null && quickAnalysis.isSilentAnalysisInProgress();
    boolean engineHasExclusiveWork =
        foregroundEngine != null && foregroundEngine.hasExclusiveGtpWorkInProgress();
    boolean foregroundAnalysisWork =
        foregroundEngine != null && foregroundEngine.hasForegroundAnalysisLeaseWorkInProgress();
    WeightSwitchPreparation preparation =
        decideWeightSwitchPreparation(
            quickAnalysis != null,
            analysisInProgress,
            silentAnalysis,
            engineHasExclusiveWork,
            foregroundAnalysisWork);

    if (preparation == WeightSwitchPreparation.BLOCKED_BY_ANALYSIS) {
      showWeightSwitchBlocked(text("AutoSetup.weightSwitchBlockedByAnalysis"));
      return;
    }
    if (preparation == WeightSwitchPreparation.BLOCKED_BY_ENGINE_TASK) {
      showWeightSwitchBlocked(text("AutoSetup.weightSwitchBlockedByTask"));
      return;
    }

    boolean resumeQuickAnalysis =
        silentAnalysis && Lizzie.config != null && Lizzie.config.autoQuickAnalyzeOnLoad;
    pendingWeightSwitchShouldResumeQuickAnalysis = resumeQuickAnalysis;
    if (quickAnalysis != null) {
      quickAnalysis.normalQuit();
      if (frame != null && frame.analysisEngine == quickAnalysis) {
        frame.analysisEngine = null;
      }
    }
    if (preparation == WeightSwitchPreparation.WAIT_FOR_QUICK_ANALYSIS) {
      waitForQuickAnalysisRelease(foregroundEngine, requestedSnapshot, resumeQuickAnalysis);
      return;
    }
    startWeightEngineSetup(requestedSnapshot, resumeQuickAnalysis);
  }

  static WeightSwitchPreparation decideWeightSwitchPreparation(
      boolean analysisEnginePresent,
      boolean analysisInProgress,
      boolean silentAnalysis,
      boolean engineHasExclusiveWork,
      boolean foregroundAnalysisWork) {
    if (analysisInProgress && !silentAnalysis) {
      return WeightSwitchPreparation.BLOCKED_BY_ANALYSIS;
    }
    if (engineHasExclusiveWork && !foregroundAnalysisWork) {
      return WeightSwitchPreparation.BLOCKED_BY_ENGINE_TASK;
    }
    if (foregroundAnalysisWork) {
      return WeightSwitchPreparation.WAIT_FOR_QUICK_ANALYSIS;
    }
    return analysisEnginePresent
        ? WeightSwitchPreparation.RESET_QUICK_ANALYSIS
        : WeightSwitchPreparation.READY;
  }

  private void waitForQuickAnalysisRelease(
      Leelaz foregroundEngine, SetupSnapshot requestedSnapshot, boolean resumeQuickAnalysis) {
    if (foregroundEngine == null) {
      startWeightEngineSetup(requestedSnapshot, resumeQuickAnalysis);
      return;
    }
    setBusy(true, text("AutoSetup.weightSwitchStoppingQuickAnalysis"), 0, -1);
    final long deadline = System.currentTimeMillis() + WEIGHT_SWITCH_TIMEOUT_MILLIS;
    javax.swing.Timer timer =
        new javax.swing.Timer(
            WEIGHT_SWITCH_POLL_MILLIS,
            event -> {
              javax.swing.Timer source = (javax.swing.Timer) event.getSource();
              if (source != pendingWeightSwitchTimer) {
                source.stop();
                return;
              }
              if (Lizzie.leelaz != foregroundEngine) {
                finishWeightSwitchWait(
                    source, text("AutoSetup.weightSwitchEngineChanged"), ERROR_COLOR);
                return;
              }
              if (!foregroundEngine.hasExclusiveGtpWorkInProgress()) {
                source.stop();
                pendingWeightSwitchTimer = null;
                startWeightEngineSetup(requestedSnapshot, resumeQuickAnalysis);
                return;
              }
              if (System.currentTimeMillis() >= deadline) {
                finishWeightSwitchWait(
                    source, text("AutoSetup.weightSwitchWaitTimeout"), ERROR_COLOR);
              }
            });
    timer.setInitialDelay(WEIGHT_SWITCH_POLL_MILLIS);
    timer.setRepeats(true);
    pendingWeightSwitchTimer = timer;
    timer.start();
  }

  private void finishWeightSwitchWait(javax.swing.Timer timer, String message, Color statusColor) {
    timer.stop();
    if (pendingWeightSwitchTimer == timer) {
      pendingWeightSwitchTimer = null;
    }
    setBusy(false, message, 0, 0);
    lblStatus.setText(message);
    lblStatus.setForeground(statusColor);
    refreshIdleControls();
    resumeQuickAnalysisAfterWeightSwitchIfNeeded();
  }

  private void showWeightSwitchBlocked(String message) {
    lblStatus.setText(message);
    lblStatus.setToolTipText(message);
    lblStatus.setForeground(WARN_COLOR);
    AccessibilitySupport.announce(lblStatus, "", message);
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
                      lblStatus.setText(text("AutoSetup.importReadyToUse"));
                      lblStatus.setToolTipText(
                          importedWeight.toAbsolutePath().normalize().toString());
                      lblStatus.setForeground(OK_COLOR);
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

  private void startRecommendedWeightDownloadInternal() {
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
                SwingUtilities.invokeLater(
                    () -> {
                      setBusy(false, text("AutoSetup.downloadDone"), 0, 0);
                      snapshot = refreshed;
                      renderSnapshot();
                      selectLocalWeight(downloadedWeight);
                      updateSelectedRemoteWeightInfo();
                      lblStatus.setText(text("AutoSetup.downloadReadyToUse"));
                      lblStatus.setToolTipText(
                          downloadedWeight.toAbsolutePath().normalize().toString());
                      lblStatus.setForeground(OK_COLOR);
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(() -> onDownloadCancelled());
              } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onBackgroundError(e));
              } finally {
                clearActiveDownload(session, Thread.currentThread());
              }
            },
            "katago-download-weight");
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

    KataGoRuntimeHelper.BenchmarkPauseResult pauseResult =
        KataGoRuntimeHelper.pauseCurrentAnalysisForBenchmark();
    if (!pauseResult.accepted()) {
      Utils.showMsg(
          Lizzie.resourceBundle.getString("AnalysisSettings.reuseStatus.existing_lease"), this);
      return;
    }
    final boolean analysisWasPondering = pauseResult.analysisWasPondering();
    final DownloadSession session = new DownloadSession();
    activeDownloadSession = session;
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

  private void startWeightEngineSetup(SetupSnapshot state) {
    startWeightEngineSetup(state, false);
  }

  private void startWeightEngineSetup(SetupSnapshot state, boolean resumeQuickAnalysis) {
    setBusy(true, text("AutoSetup.settingUp"), 0, -1);
    Thread worker =
        new Thread(
            () -> {
              try {
                SetupResult result = KataGoAutoSetupHelper.addWeightEngineProfile(state);
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      onSetupApplied(
                          result,
                          text("AutoSetup.weightInUseMessage") + " " + result.engineName,
                          false,
                          false,
                          resumeQuickAnalysis);
                    });
              } catch (IOException e) {
                SwingUtilities.invokeLater(
                    () -> {
                      activeWorkerThread = null;
                      onBackgroundError(e);
                      resumeQuickAnalysisAfterWeightSwitchIfNeeded();
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
    onSetupApplied(result, message, showSuccessPopup, includeWeightPathInPopup, false);
  }

  private void onSetupApplied(
      SetupResult result,
      String message,
      boolean showSuccessPopup,
      boolean includeWeightPathInPopup,
      boolean resumeQuickAnalysis) {
    pendingWeightSwitchShouldResumeQuickAnalysis |= resumeQuickAnalysis;
    String reloadWarning = reloadRunningEngine(result.engineIndex);
    if (reloadWarning != null && !reloadWarning.trim().isEmpty()) {
      finishSetupApplied(
          result, message, reloadWarning, showSuccessPopup, includeWeightPathInPopup);
      return;
    }
    if (Lizzie.engineManager != null
        && result.engineIndex >= 0
        && !isAppliedEngineReady(result.engineIndex)) {
      waitForAppliedEngine(result, message, showSuccessPopup, includeWeightPathInPopup);
      return;
    }
    finishSetupApplied(result, message, null, showSuccessPopup, includeWeightPathInPopup);
  }

  private void waitForAppliedEngine(
      SetupResult result,
      String message,
      boolean showSuccessPopup,
      boolean includeWeightPathInPopup) {
    setBusy(true, text("AutoSetup.weightSwitchActivating"), 0, -1);
    final long deadline = System.currentTimeMillis() + WEIGHT_SWITCH_TIMEOUT_MILLIS;
    javax.swing.Timer timer =
        new javax.swing.Timer(
            WEIGHT_SWITCH_POLL_MILLIS,
            event -> {
              javax.swing.Timer source = (javax.swing.Timer) event.getSource();
              if (source != pendingWeightSwitchTimer) {
                source.stop();
                return;
              }
              if (isAppliedEngineReady(result.engineIndex)) {
                source.stop();
                pendingWeightSwitchTimer = null;
                finishSetupApplied(
                    result, message, null, showSuccessPopup, includeWeightPathInPopup);
                return;
              }
              if (System.currentTimeMillis() >= deadline
                  || (Lizzie.leelaz != null
                      && Lizzie.leelaz.isDownWithError
                      && !Lizzie.leelaz.isStarted())) {
                source.stop();
                pendingWeightSwitchTimer = null;
                finishSetupApplied(
                    result,
                    message,
                    text("AutoSetup.weightSwitchActivationTimeout"),
                    showSuccessPopup,
                    includeWeightPathInPopup);
              }
            });
    timer.setInitialDelay(WEIGHT_SWITCH_POLL_MILLIS);
    timer.setRepeats(true);
    pendingWeightSwitchTimer = timer;
    timer.start();
  }

  private boolean isAppliedEngineReady(int engineIndex) {
    return !EngineManager.isEmpty
        && EngineManager.currentEngineNo == engineIndex
        && Lizzie.leelaz != null
        && Lizzie.leelaz.isStarted()
        && Lizzie.leelaz.isLoaded()
        && !Lizzie.leelaz.isCheckingName;
  }

  private void finishSetupApplied(
      SetupResult result,
      String message,
      String reloadWarning,
      boolean showSuccessPopup,
      boolean includeWeightPathInPopup) {
    setBusy(false, reloadWarning == null ? message : reloadWarning, 0, 0);
    snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    renderSnapshot();
    selectRemoteWeightByModelName(KataGoAutoSetupHelper.resolveActiveWeightModelName(snapshot));
    updateSelectedRemoteWeightInfo();
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
      if (showSuccessPopup || includeWeightPathInPopup) {
        Utils.showMsg(
            message
                + (includeWeightPathInPopup ? "\n" + result.snapshot.activeWeightPath : "")
                + "\n\n"
                + reloadWarning,
            this);
      }
    }
    resumeQuickAnalysisAfterWeightSwitchIfNeeded();
  }

  private void resumeQuickAnalysisAfterWeightSwitchIfNeeded() {
    boolean shouldResume = pendingWeightSwitchShouldResumeQuickAnalysis;
    pendingWeightSwitchShouldResumeQuickAnalysis = false;
    if (shouldResume && Lizzie.frame != null) {
      Lizzie.frame.scheduleQuickAnalysisContinuationAfterHistoryNavigation();
    }
  }

  private String reloadRunningEngine(int engineIndex) {
    if (Lizzie.engineManager == null) {
      return null;
    }
    try {
      // Adding a weight must not tear down every running engine. Refresh the catalog in place and
      // switch only after the interruptible quick-analysis lease has been released.
      Lizzie.engineManager.refreshEngineCatalog();
      if (engineIndex >= 0
          && (EngineManager.isEmpty || EngineManager.currentEngineNo != engineIndex)) {
        if (!Lizzie.engineManager.switchEngineIfAvailable(engineIndex, true)) {
          return text("AutoSetup.weightSwitchRetry");
        }
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
    if (pendingWeightSwitchTimer != null) {
      pendingWeightSwitchTimer.stop();
      pendingWeightSwitchTimer = null;
      setBusy(false, "", 0, 0);
      resumeQuickAnalysisAfterWeightSwitchIfNeeded();
    }
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
    btnChooseLocalEngine.setEnabled(!busy);
    btnRepairAnalysisConfig.setEnabled(!busy);
    btnOpenAppFolder.setEnabled(!busy);
    btnViewFullDownloads.setEnabled(!busy);
    btnReloadRemoteWeights.setEnabled(!busy);
    btnDownloadWeight.setEnabled(!busy && canDownloadSelectedRemoteWeight());
    btnImportWeight.setEnabled(!busy);
    btnUseWeight.setEnabled(!busy && canUseSelectedLocalWeight());
    btnDownloadHumanSlModel.setEnabled(!busy && canDownloadHumanSlModel());
    btnImportHumanSlModel.setEnabled(!busy);
    weightCatalogList.setEnabled(!busy && !weightCatalogModel.isEmpty());
    btnOfficialWeightTab.setEnabled(!busy);
    btnCustomWeightTab.setEnabled(!busy);
    balancedRecommendation.setEnabled(!busy && balancedRecommendation.getWeight() != null);
    strongestRecommendation.setEnabled(!busy && strongestRecommendation.getWeight() != null);
    lightweightRecommendation.setEnabled(!busy && lightweightRecommendation.getWeight() != null);
    btnInstallNvidiaRuntime.setEnabled(!busy && canInstallNvidiaRuntime());
    btnInstallTensorRt.setEnabled(!busy && canInstallTensorRt());
    btnSwitchBackCuda.setEnabled(!busy && canSwitchBackToCuda());
    btnCleanTensorRtCache.setEnabled(
        !busy && KataGoRuntimeHelper.tensorRtDownloadCacheBytes() > 0L);
    btnOptimizePerformance.setEnabled(!busy && canRunBenchmark());
    btnStopDownload.setEnabled(busy && activeDownloadSession != null);
    btnClose.setEnabled(true);

    progressPanel.setVisible(busy);
    footerPanel.setVisible(busy);
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
    return activeDownloadSession != null
        || activeWorkerThread != null
        || pendingWeightSwitchTimer != null;
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
        && snapshot.hasWeight()
        && isEngineValidationReady();
  }

  private boolean isEngineValidationReady() {
    return engineValidationResult != null && engineValidationResult.isValid();
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
    WeightCatalogEntry selected = weightCatalogList.getSelectedValue();
    return selected == null ? null : selected.localPath;
  }

  private boolean canUseSelectedLocalWeight() {
    Path selectedWeight = getSelectedLocalWeight();
    return selectedWeight != null
        && !isCurrentLocalWeight(selectedWeight)
        && snapshot != null
        && snapshot.hasEngine()
        && snapshot.hasConfigs()
        && isEngineValidationReady()
        && activeDownloadSession == null
        && activeWorkerThread == null;
  }

  private boolean isCurrentLocalWeight(Path weightPath) {
    return weightPath != null
        && snapshot != null
        && snapshot.activeWeightPath != null
        && snapshot
            .activeWeightPath
            .toAbsolutePath()
            .normalize()
            .equals(weightPath.toAbsolutePath().normalize());
  }

  private void updateSelectedLocalWeightInfo() {
    updateSelectedWeightInfo();
  }

  private void updateSelectedWeightInfo() {
    WeightCatalogEntry entry = weightCatalogList.getSelectedValue();
    if (entry == null) {
      String emptyText =
          weightCatalogMode == WeightCatalogMode.OFFICIAL
              ? text("AutoSetup.remoteUnavailable")
              : text("AutoSetup.noLocalWeights");
      lblSelectedWeightName.setText(emptyText);
      lblSelectedWeightName.setToolTipText(null);
      lblSelectedWeightMeta.setText("");
      btnDownloadWeight.setVisible(false);
      btnUseWeight.setVisible(false);
      selectedWeightActions.revalidate();
      selectedWeightActions.repaint();
      return;
    }

    if (entry.remoteInfo != null) {
      RemoteWeightInfo info = entry.remoteInfo;
      boolean downloaded = entry.localPath != null;
      boolean current = downloaded && isCurrentLocalWeight(entry.localPath);
      lblSelectedWeightName.setText(formatRemoteModelName(info));
      lblSelectedWeightMeta.setText(formatSelectedRemoteMetadata(info, downloaded, current));
      String tooltip =
          weightTooltip(
              formatRemoteModelName(info),
              formatRemoteMetadata(info),
              info.eloRating,
              info.fileName(),
              info.downloadUrl);
      lblSelectedWeightName.setToolTipText(tooltip);
      lblSelectedWeightMeta.setToolTipText(tooltip);
      btnDownloadWeight.setVisible(!downloaded);
      btnDownloadWeight.setText(text("AutoSetup.downloadWeight"));
      btnDownloadWeight.setEnabled(canDownloadSelectedRemoteWeight());
      btnUseWeight.setVisible(downloaded);
      btnUseWeight.setText(
          current ? text("AutoSetup.currentlyUsingShort") : text("AutoSetup.useWeight"));
      btnUseWeight.setEnabled(downloaded && !current && !hasActiveBackgroundTask());
    } else {
      Path path = entry.localPath;
      String displayName = KataGoAutoSetupHelper.resolveWeightDisplayName(path);
      boolean current = isCurrentLocalWeight(path);
      lblSelectedWeightName.setText(displayName);
      lblSelectedWeightMeta.setText(formatSelectedLocalMetadata(path, current));
      String tooltip = weightTooltip(displayName, path.toAbsolutePath().normalize().toString());
      lblSelectedWeightName.setToolTipText(tooltip);
      lblSelectedWeightMeta.setToolTipText(tooltip);
      btnDownloadWeight.setVisible(false);
      btnUseWeight.setVisible(true);
      btnUseWeight.setText(
          current ? text("AutoSetup.currentlyUsingShort") : text("AutoSetup.useWeight"));
      btnUseWeight.setEnabled(!current && !hasActiveBackgroundTask());
    }
    selectedWeightActions.revalidate();
    selectedWeightActions.repaint();
    weightCatalogList.repaint();
  }

  private void selectLocalWeight(Path weightPath) {
    if (weightPath == null) {
      return;
    }
    RemoteWeightInfo remote = findRemoteWeightForLocalPath(weightPath);
    if (remote != null) {
      switchWeightCatalogMode(WeightCatalogMode.OFFICIAL);
      selectWeightCatalogEntry(WeightCatalogEntry.officialKey(remote));
    } else {
      switchWeightCatalogMode(WeightCatalogMode.CUSTOM);
      selectWeightCatalogEntry(WeightCatalogEntry.customKey(weightPath));
    }
  }

  private void activateSelectedWeight() {
    WeightCatalogEntry entry = weightCatalogList.getSelectedValue();
    if (entry == null) {
      return;
    }
    if (entry.remoteInfo != null && entry.localPath == null) {
      startRecommendedWeightDownloadInternal();
      return;
    }
    if (canUseSelectedLocalWeight()) {
      useSelectedWeight();
    }
  }

  private void updateWeightDownloadHover(MouseEvent event) {
    int row = downloadableWeightRowAt(event);
    setHoveredWeightDownloadRow(row);
    weightCatalogList.setCursor(
        row >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
    weightCatalogList.setToolTipText(row >= 0 ? text("AutoSetup.downloadWeight") : null);
  }

  private int downloadableWeightRowAt(MouseEvent event) {
    if (event == null
        || hasActiveBackgroundTask()
        || !isWeightCatalogDownloadHit(event.getX(), weightCatalogList.getWidth())) {
      return -1;
    }
    int row = weightCatalogList.locationToIndex(event.getPoint());
    if (row < 0) {
      return -1;
    }
    Rectangle bounds = weightCatalogList.getCellBounds(row, row);
    if (bounds == null || !bounds.contains(event.getPoint())) {
      return -1;
    }
    WeightCatalogEntry entry = weightCatalogModel.get(row);
    return entry.remoteInfo != null && entry.localPath == null ? row : -1;
  }

  private void setHoveredWeightDownloadRow(int row) {
    if (hoveredWeightDownloadRow == row) {
      return;
    }
    int previous = hoveredWeightDownloadRow;
    hoveredWeightDownloadRow = row;
    repaintWeightCatalogRow(previous);
    repaintWeightCatalogRow(row);
  }

  private void repaintWeightCatalogRow(int row) {
    if (row < 0 || row >= weightCatalogModel.size()) {
      return;
    }
    Rectangle bounds = weightCatalogList.getCellBounds(row, row);
    if (bounds != null) {
      weightCatalogList.repaint(bounds);
    }
  }

  private void switchWeightCatalogMode(WeightCatalogMode mode) {
    if (mode == null) {
      return;
    }
    weightCatalogMode = mode;
    btnOfficialWeightTab.setSelected(mode == WeightCatalogMode.OFFICIAL);
    btnCustomWeightTab.setSelected(mode == WeightCatalogMode.CUSTOM);
    ((CardLayout) weightCatalogActionCards.getLayout())
        .show(weightCatalogActionCards, mode.cardName);
    rebuildWeightCatalog(null);
  }

  private void rebuildWeightCatalog(String preferredKey) {
    setHoveredWeightDownloadRow(-1);
    weightCatalogList.setCursor(Cursor.getDefaultCursor());
    weightCatalogList.setToolTipText(null);
    WeightCatalogEntry previous = weightCatalogList.getSelectedValue();
    String selectionKey =
        preferredKey != null ? preferredKey : previous == null ? null : previous.stableKey();
    weightCatalogModel.clear();
    if (weightCatalogMode == WeightCatalogMode.OFFICIAL) {
      for (RemoteWeightInfo info : remoteWeightInfos) {
        weightCatalogModel.addElement(
            WeightCatalogEntry.official(info, findDownloadedRemoteWeightPath(info)));
      }
    } else if (snapshot != null && snapshot.weightCandidates != null) {
      for (Path weight : snapshot.weightCandidates) {
        if (weight != null && findRemoteWeightForLocalPath(weight) == null) {
          weightCatalogModel.addElement(WeightCatalogEntry.custom(weight));
        }
      }
    }

    if (!selectWeightCatalogEntry(selectionKey)) {
      selectCurrentOrPreferredWeight();
    }
    boolean empty = weightCatalogModel.isEmpty();
    lblWeightCatalogEmpty.setText(
        weightCatalogMode == WeightCatalogMode.OFFICIAL
            ? text("AutoSetup.remoteUnavailable")
            : text("AutoSetup.noCustomWeights"));
    lblWeightCatalogEmpty.setToolTipText(null);
    showWeightCatalogEmptyState(empty);
    updateWeightCatalogViewportHeight(weightCatalogModel.size());
    weightCatalogList.setEnabled(!empty && !hasActiveBackgroundTask());
    updateSelectedWeightInfo();
  }

  private void updateWeightCatalogViewportHeight(int entryCount) {
    int visibleRows = weightCatalogViewportRows(entryCount);
    Dimension size = new Dimension(10, weightCatalogVisibleHeight(visibleRows));
    weightCatalogScrollPane.setPreferredSize(size);
    weightCatalogContentCards.setPreferredSize(size);
    weightCatalogContentCards.revalidate();
  }

  private void selectCurrentOrPreferredWeight() {
    if (weightCatalogModel.isEmpty()) {
      weightCatalogList.clearSelection();
      return;
    }
    for (int index = 0; index < weightCatalogModel.size(); index++) {
      WeightCatalogEntry entry = weightCatalogModel.get(index);
      if (entry.localPath != null && isCurrentLocalWeight(entry.localPath)) {
        weightCatalogList.setSelectedIndex(index);
        weightCatalogList.ensureIndexIsVisible(index);
        return;
      }
    }
    if (weightCatalogMode == WeightCatalogMode.OFFICIAL) {
      RemoteWeightInfo preferred = choosePreferredRemoteWeight();
      if (preferred != null
          && selectWeightCatalogEntry(WeightCatalogEntry.officialKey(preferred))) {
        return;
      }
    }
    weightCatalogList.setSelectedIndex(0);
  }

  private boolean selectWeightCatalogEntry(String stableKey) {
    if (stableKey == null || stableKey.trim().isEmpty()) {
      return false;
    }
    for (int index = 0; index < weightCatalogModel.size(); index++) {
      if (stableKey.equals(weightCatalogModel.get(index).stableKey())) {
        weightCatalogList.setSelectedIndex(index);
        weightCatalogList.ensureIndexIsVisible(index);
        return true;
      }
    }
    return false;
  }

  private void showWeightCatalogEmptyState(boolean empty) {
    CardLayout layout = (CardLayout) weightCatalogContentCards.getLayout();
    layout.show(weightCatalogContentCards, empty ? "empty" : "list");
  }

  private RemoteWeightInfo findRemoteWeightForLocalPath(Path path) {
    if (path == null || path.getFileName() == null) {
      return null;
    }
    String fileName = normalizeWeightName(path.getFileName().toString());
    String displayName = normalizeWeightName(KataGoAutoSetupHelper.resolveWeightDisplayName(path));
    for (RemoteWeightInfo info : remoteWeightInfos) {
      if (fileName.equals(normalizeWeightName(info.fileName()))
          || displayName.equals(normalizeWeightName(formatRemoteModelName(info)))) {
        return info;
      }
      if (isCurrentLocalWeight(path) && matchesCurrentWeight(info)) {
        return info;
      }
    }
    return null;
  }

  private Path findDownloadedRemoteWeightPath(RemoteWeightInfo info) {
    if (info == null || snapshot == null || snapshot.weightCandidates == null) {
      return null;
    }
    for (Path candidate :
        prioritizeActiveWeightCandidate(snapshot.activeWeightPath, snapshot.weightCandidates)) {
      RemoteWeightInfo matched = findRemoteWeightForLocalPath(candidate);
      if (matched != null && matchesModelName(info, matched.modelName)) {
        return candidate;
      }
    }
    return null;
  }

  static List<Path> prioritizeActiveWeightCandidate(Path activeWeight, List<Path> candidates) {
    List<Path> ordered = new ArrayList<Path>();
    if (activeWeight != null) {
      ordered.add(activeWeight.toAbsolutePath().normalize());
    }
    if (candidates == null) {
      return ordered;
    }
    for (Path candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      Path normalized = candidate.toAbsolutePath().normalize();
      if (!ordered.contains(normalized)) {
        ordered.add(normalized);
      }
    }
    return ordered;
  }

  private String formatSelectedRemoteMetadata(
      RemoteWeightInfo info, boolean downloaded, boolean current) {
    StringBuilder value = new StringBuilder();
    if (info.eloRating != null && !info.eloRating.trim().isEmpty()) {
      appendMetadata(value, "Elo " + info.eloRating.trim());
    }
    String releaseDate = formatRemoteReleaseDate(info);
    if (!releaseDate.isEmpty()) {
      appendMetadata(value, text("AutoSetup.releasedShort") + " " + releaseDate);
    }
    if (current) {
      appendMetadata(value, text("AutoSetup.currentlyUsingShort"));
    } else if (downloaded) {
      appendMetadata(value, text("AutoSetup.downloaded"));
    }
    return value.toString();
  }

  private String formatRecommendationMetadata(RemoteWeightInfo info) {
    if (info == null) {
      return "";
    }
    StringBuilder value = new StringBuilder("Elo ").append(compactEloRating(info.eloRating));
    String releaseDate = formatRemoteReleaseDate(info);
    if (!releaseDate.isEmpty()) {
      appendMetadata(value, releaseDate);
    }
    return value.toString();
  }

  private String formatSelectedLocalMetadata(Path path, boolean current) {
    StringBuilder value = new StringBuilder();
    appendMetadata(value, text("AutoSetup.importedWeight"));
    String date = formatLocalWeightDate(path);
    if (!date.isEmpty()) {
      appendMetadata(value, text("AutoSetup.importedShort") + " " + date);
    }
    if (current) {
      appendMetadata(value, text("AutoSetup.currentlyUsingShort"));
    }
    return value.toString();
  }

  private String formatLocalWeightDate(Path path) {
    if (path == null) {
      return "";
    }
    try {
      return LOCAL_WEIGHT_DATE_FORMAT.format(
          Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()));
    } catch (IOException e) {
      return "";
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

  private String formatRemoteMetadata(RemoteWeightInfo info) {
    StringBuilder metadata = new StringBuilder();
    appendMetadata(metadata, formatRemoteReleaseDate(info));
    if (info.recommended) {
      appendMetadata(metadata, text("AutoSetup.officialStrongestBadge"));
    }
    if (info.latest) {
      appendMetadata(metadata, text("AutoSetup.recommendedLatest"));
    }
    if (isDownloadedRemoteWeight(info)) {
      appendMetadata(metadata, text("AutoSetup.downloaded"));
    }
    if (matchesCurrentWeight(info)) {
      appendMetadata(metadata, text("AutoSetup.currentlyUsingShort"));
    }
    return metadata.toString();
  }

  private void appendMetadata(StringBuilder metadata, String value) {
    if (value == null || value.trim().isEmpty()) {
      return;
    }
    if (metadata.length() > 0) {
      metadata.append("  |  ");
    }
    metadata.append(value.trim());
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

  private String weightTooltip(String... lines) {
    StringBuilder html = new StringBuilder("<html><div style='width: 420px'>");
    boolean hasLine = false;
    for (String line : lines) {
      if (line == null || line.trim().isEmpty()) {
        continue;
      }
      if (hasLine) {
        html.append("<br>");
      }
      appendWrappedTooltipLine(html, line.trim());
      hasLine = true;
    }
    html.append("</div></html>");
    return hasLine ? html.toString() : null;
  }

  private void appendWrappedTooltipLine(StringBuilder html, String value) {
    final int lineLength = 68;
    for (int start = 0; start < value.length(); start += lineLength) {
      if (start > 0) {
        html.append("<br>");
      }
      int end = Math.min(value.length(), start + lineLength);
      html.append(escapeTooltipHtml(value.substring(start, end)));
    }
  }

  private String escapeTooltipHtml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private void updateSelectedRemoteWeightInfo() {
    updateSelectedWeightInfo();
  }

  private RemoteWeightInfo getSelectedRemoteWeight() {
    WeightCatalogEntry selected = weightCatalogList.getSelectedValue();
    return selected == null ? null : selected.remoteInfo;
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

  private void renderWeightRecommendations() {
    RemoteWeightInfo balanced = null;
    for (RemoteWeightInfo info : remoteWeightInfos) {
      if (KataGoAutoSetupHelper.isDefaultGeneralUseWeight(info)) {
        balanced = info;
        break;
      }
    }
    if (balanced == null) {
      balanced = chooseRemoteWeightByFamily("28B");
    }
    if (balanced == null) {
      balanced = choosePreferredRemoteWeight();
    }
    RemoteWeightInfo strongest = chooseStrongestRemoteWeight();
    RemoteWeightInfo lightweight = chooseRemoteWeightByFamily("20B");
    if (lightweight == null) {
      lightweight = chooseRemoteWeightByFamily("15B");
    }
    if (lightweight == null) {
      lightweight = chooseRemoteWeightByFamily("10B");
    }

    balancedRecommendation.configure(
        text("AutoSetup.recommendationBalanced"),
        text("AutoSetup.recommendationBalancedHint"),
        balanced,
        text("AutoSetup.recommendedBadge"));
    strongestRecommendation.configure(
        text("AutoSetup.recommendationStrongest"),
        text("AutoSetup.recommendationStrongestHint"),
        strongest,
        text("AutoSetup.officialStrongestBadge"));
    lightweightRecommendation.configure(
        text("AutoSetup.recommendationLightweight"),
        text("AutoSetup.recommendationLightweightHint"),
        lightweight,
        "");
  }

  private RemoteWeightInfo chooseStrongestRemoteWeight() {
    RemoteWeightInfo strongest = null;
    double highestElo = Double.NEGATIVE_INFINITY;
    for (RemoteWeightInfo info : remoteWeightInfos) {
      if (info.recommended) {
        return info;
      }
      double elo = parseElo(info.eloRating);
      if (strongest == null || elo > highestElo) {
        strongest = info;
        highestElo = elo;
      }
    }
    return strongest;
  }

  private RemoteWeightInfo chooseRemoteWeightByFamily(String family) {
    for (RemoteWeightInfo info : remoteWeightInfos) {
      if (family.equalsIgnoreCase(remoteWeightFamily(info))) {
        return info;
      }
    }
    return null;
  }

  static String compactEloRating(String value) {
    double elo = parseElo(value);
    if (!Double.isFinite(elo)) {
      return "-";
    }
    return String.format(Locale.ROOT, "%,.0f", elo);
  }

  private static double parseElo(String value) {
    if (value == null) {
      return Double.NEGATIVE_INFINITY;
    }
    Matcher matcher = ELO_VALUE_PATTERN.matcher(value);
    if (!matcher.find()) {
      return Double.NEGATIVE_INFINITY;
    }
    try {
      return Double.parseDouble(matcher.group().replace(",", ""));
    } catch (NumberFormatException e) {
      return Double.NEGATIVE_INFINITY;
    }
  }

  private void selectRecommendedWeight(RemoteWeightInfo info) {
    if (info == null) {
      return;
    }
    switchWeightCatalogMode(WeightCatalogMode.OFFICIAL);
    selectWeightCatalogEntry(WeightCatalogEntry.officialKey(info));
    weightCatalogList.requestFocusInWindow();
  }

  private void activateRecommendation(WeightRecommendationCard card) {
    if (card == null || card.getWeight() == null || hasActiveBackgroundTask()) {
      return;
    }
    selectRecommendedWeight(card.getWeight());
    if (card.getAction() == RecommendationAction.DOWNLOAD) {
      startRecommendedWeightDownloadInternal();
    } else if (card.getAction() == RecommendationAction.USE) {
      useSelectedWeight();
    }
  }

  private void selectRemoteWeightByModelName(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) {
      return;
    }
    for (RemoteWeightInfo info : remoteWeightInfos) {
      if (matchesModelName(info, modelName)) {
        switchWeightCatalogMode(WeightCatalogMode.OFFICIAL);
        selectWeightCatalogEntry(WeightCatalogEntry.officialKey(info));
        return;
      }
    }
    if (snapshot != null && snapshot.activeWeightPath != null) {
      selectLocalWeight(snapshot.activeWeightPath);
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
    return findDownloadedRemoteWeightPath(info) != null;
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
    btnChooseLocalEngine.setEnabled(true);
    btnRepairAnalysisConfig.setEnabled(true);
    btnOpenAppFolder.setEnabled(true);
    btnViewFullDownloads.setEnabled(true);
    btnReloadRemoteWeights.setEnabled(true);
    btnDownloadWeight.setEnabled(canDownloadSelectedRemoteWeight());
    btnImportWeight.setEnabled(true);
    btnUseWeight.setEnabled(canUseSelectedLocalWeight());
    btnDownloadHumanSlModel.setEnabled(canDownloadHumanSlModel());
    btnImportHumanSlModel.setEnabled(true);
    weightCatalogList.setEnabled(!weightCatalogModel.isEmpty());
    btnOfficialWeightTab.setEnabled(true);
    btnCustomWeightTab.setEnabled(true);
    balancedRecommendation.setEnabled(balancedRecommendation.getWeight() != null);
    strongestRecommendation.setEnabled(strongestRecommendation.getWeight() != null);
    lightweightRecommendation.setEnabled(lightweightRecommendation.getWeight() != null);
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

  private enum WeightCatalogMode {
    OFFICIAL("official"),
    CUSTOM("custom");

    private final String cardName;

    WeightCatalogMode(String cardName) {
      this.cardName = cardName;
    }
  }

  enum WeightSwitchPreparation {
    READY,
    RESET_QUICK_ANALYSIS,
    WAIT_FOR_QUICK_ANALYSIS,
    BLOCKED_BY_ANALYSIS,
    BLOCKED_BY_ENGINE_TASK
  }

  private static final class WeightCatalogEntry {
    private final RemoteWeightInfo remoteInfo;
    private final Path localPath;

    private WeightCatalogEntry(RemoteWeightInfo remoteInfo, Path localPath) {
      this.remoteInfo = remoteInfo;
      this.localPath = localPath == null ? null : localPath.toAbsolutePath().normalize();
    }

    private static WeightCatalogEntry official(RemoteWeightInfo info, Path downloadedPath) {
      return new WeightCatalogEntry(info, downloadedPath);
    }

    private static WeightCatalogEntry custom(Path path) {
      return new WeightCatalogEntry(null, path);
    }

    private String stableKey() {
      return remoteInfo == null ? customKey(localPath) : officialKey(remoteInfo);
    }

    private static String officialKey(RemoteWeightInfo info) {
      if (info == null) {
        return "";
      }
      return "official:"
          + (info.modelName == null ? info.fileName() : info.modelName)
              .trim()
              .toLowerCase(Locale.ROOT);
    }

    private static String customKey(Path path) {
      return path == null ? "" : "custom:" + path.toAbsolutePath().normalize();
    }
  }

  private enum RecommendationTone {
    TEAL(new Color(18, 105, 97), new Color(214, 235, 229)),
    GOLD(new Color(201, 133, 27), new Color(249, 232, 202)),
    SAGE(new Color(54, 125, 86), new Color(220, 237, 223));

    private final Color accent;
    private final Color wash;

    RecommendationTone(Color accent, Color wash) {
      this.accent = accent;
      this.wash = wash;
    }
  }

  enum RecommendationAction {
    NONE,
    DOWNLOAD,
    USE,
    CURRENT
  }

  static RecommendationAction recommendationAction(boolean downloaded, boolean current) {
    if (current) {
      return RecommendationAction.CURRENT;
    }
    return downloaded ? RecommendationAction.USE : RecommendationAction.DOWNLOAD;
  }

  static Rectangle centeredRecommendationIconBounds(
      int containerWidth, int containerHeight, Dimension iconSize) {
    int width = iconSize == null ? 0 : Math.max(0, iconSize.width);
    int height = iconSize == null ? 0 : Math.max(0, iconSize.height);
    return new Rectangle(
        Math.max(0, (containerWidth - width) / 2),
        Math.max(0, (containerHeight - height) / 2),
        width,
        height);
  }

  private static Color blendColor(Color first, Color second, float ratio) {
    float bounded = Math.max(0f, Math.min(1f, ratio));
    return new Color(
        Math.round(first.getRed() * (1f - bounded) + second.getRed() * bounded),
        Math.round(first.getGreen() * (1f - bounded) + second.getGreen() * bounded),
        Math.round(first.getBlue() * (1f - bounded) + second.getBlue() * bounded));
  }

  private final class WeightRecommendationCard extends JPanel {
    private final RecommendationTone tone;
    private final JFontLabel title = new JFontLabel();
    private final JTextArea description = new JTextArea();
    private final JFontLabel model = new JFontLabel();
    private final JFontLabel metadata = new JFontLabel();
    private final WeightStatusTagLabel badge = new WeightStatusTagLabel();
    private final JFontButton actionButton = new JFontButton();
    private RemoteWeightInfo weight;
    private RecommendationAction action = RecommendationAction.NONE;
    private Runnable activation;

    private WeightRecommendationCard(RecommendationTone tone) {
      this.tone = tone;
      setLayout(new GridBagLayout());
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
      setPreferredSize(new Dimension(10, WEIGHT_RECOMMENDATION_CARD_HEIGHT));

      JFontLabel icon = new JFontLabel();
      icon.setHorizontalAlignment(SwingConstants.CENTER);
      icon.setIcon(new RecommendationIcon(tone));
      badge.setStatus(
          "", tone == RecommendationTone.GOLD ? StatusTagTone.GOLD : StatusTagTone.TEAL);
      JPanel iconRow = new RecommendationHeaderPanel(icon, badge);

      GridBagConstraints gbc = new GridBagConstraints();
      gbc.gridx = 0;
      gbc.gridy = 0;
      gbc.weightx = 1;
      gbc.fill = GridBagConstraints.HORIZONTAL;
      add(iconRow, gbc);

      title.setForeground(TEXT_PRIMARY);
      title.setHorizontalAlignment(SwingConstants.CENTER);
      title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));
      gbc.gridy++;
      gbc.insets = new Insets(1, 0, 0, 0);
      add(title, gbc);

      description.setEditable(false);
      description.setFocusable(false);
      description.setLineWrap(true);
      description.setWrapStyleWord(true);
      description.setOpaque(false);
      description.setForeground(TEXT_SECONDARY);
      description.setFont(title.getFont().deriveFont(Font.PLAIN, title.getFont().getSize2D() - 3f));
      description.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
      description.setRows(2);
      gbc.gridy++;
      gbc.weighty = 0;
      gbc.fill = GridBagConstraints.HORIZONTAL;
      gbc.insets = new Insets(1, 0, 2, 0);
      add(description, gbc);

      JPanel separator = new JPanel();
      separator.setBackground(new Color(226, 221, 211));
      separator.setPreferredSize(new Dimension(10, 1));
      gbc.gridy++;
      gbc.weighty = 0;
      gbc.fill = GridBagConstraints.HORIZONTAL;
      gbc.insets = new Insets(0, 0, 4, 0);
      add(separator, gbc);

      model.setForeground(TEXT_PRIMARY);
      model.setFont(model.getFont().deriveFont(Font.BOLD));
      metadata.setForeground(TEXT_SECONDARY);
      metadata.setFont(metadata.getFont().deriveFont(metadata.getFont().getSize2D() - 1f));
      JPanel labels = new JPanel(new BorderLayout(0, 1));
      labels.setOpaque(false);
      labels.add(model, BorderLayout.NORTH);
      labels.add(metadata, BorderLayout.CENTER);

      styleWeightButton(actionButton, WeightButtonStyle.PRIMARY);
      actionButton.setIconTextGap(5);
      actionButton.addActionListener(
          event -> {
            if (activation != null) {
              activation.run();
            }
          });
      JPanel footer = new JPanel(new BorderLayout(8, 1));
      footer.setOpaque(false);
      footer.add(labels, BorderLayout.CENTER);
      footer.add(actionButton, BorderLayout.EAST);
      gbc.gridy++;
      gbc.insets = new Insets(0, 0, 0, 0);
      add(footer, gbc);
    }

    private void configure(
        String titleText, String descriptionText, RemoteWeightInfo remoteWeight, String badgeText) {
      weight = remoteWeight;
      title.setText(titleText);
      description.setText(descriptionText);
      badge.setStatus(
          remoteWeight == null ? "" : badgeText,
          tone == RecommendationTone.GOLD ? StatusTagTone.GOLD : StatusTagTone.TEAL);
      if (remoteWeight == null) {
        action = RecommendationAction.NONE;
        model.setText(text("AutoSetup.loadingRemote"));
        metadata.setText("");
        setToolTipText(null);
        setEnabled(false);
      } else {
        Path downloadedPath = findDownloadedRemoteWeightPath(remoteWeight);
        boolean current = matchesCurrentWeight(remoteWeight);
        action = recommendationAction(downloadedPath != null || current, current);
        model.setText(formatRemoteModelName(remoteWeight));
        metadata.setText(formatRecommendationMetadata(remoteWeight));
        setToolTipText(
            weightTooltip(
                formatRemoteModelName(remoteWeight),
                formatRemoteMetadata(remoteWeight),
                remoteWeight.eloRating,
                remoteWeight.fileName(),
                remoteWeight.downloadUrl));
        setEnabled(!hasActiveBackgroundTask());
      }
      configureActionButton();
      getAccessibleContext().setAccessibleName(titleText + " " + model.getText());
      getAccessibleContext()
          .setAccessibleDescription(
              descriptionText + (actionButton.isVisible() ? " · " + actionButton.getText() : ""));
      revalidate();
      repaint();
    }

    private void configureActionButton() {
      if (action == RecommendationAction.DOWNLOAD) {
        actionButton.setText(text("AutoSetup.downloadShort"));
        actionButton.setIcon(new WeightActionIcon(WeightActionIcon.DOWNLOAD));
      } else if (action == RecommendationAction.USE) {
        actionButton.setText(text("AutoSetup.useWeight"));
        actionButton.setIcon(new WeightActionIcon(WeightActionIcon.USE));
      } else if (action == RecommendationAction.CURRENT) {
        actionButton.setText(text("AutoSetup.currentlyUsingShort"));
        actionButton.setIcon(new WeightActionIcon(WeightActionIcon.USE));
      } else {
        actionButton.setText("");
        actionButton.setIcon(null);
      }
      boolean visible = action != RecommendationAction.NONE;
      actionButton.setVisible(visible);
      if (visible) {
        Dimension preferred = localizedButtonSize(actionButton, 78, 30);
        preferred = new Dimension(Math.min(142, preferred.width), 30);
        actionButton.setPreferredSize(preferred);
        actionButton.setMinimumSize(preferred);
        actionButton.setMaximumSize(preferred);
      }
      actionButton.setEnabled(
          isEnabled()
              && (action == RecommendationAction.DOWNLOAD || action == RecommendationAction.USE));
      actionButton.setToolTipText(actionButton.isVisible() ? actionButton.getText() : null);
      AccessibilitySupport.named(
          actionButton,
          actionButton.getText(),
          title.getText() + " · " + model.getText() + " · " + actionButton.getText());
    }

    private void setActivation(Runnable activation) {
      this.activation = activation;
    }

    @Override
    public void setEnabled(boolean enabled) {
      super.setEnabled(enabled);
      if (actionButton != null) {
        actionButton.setEnabled(
            enabled
                && (action == RecommendationAction.DOWNLOAD || action == RecommendationAction.USE));
      }
      repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean hover = actionButton != null && actionButton.getModel().isRollover();
        Color fill = hover ? blendColor(CARD_BG, tone.wash, 0.24f) : CARD_BG;
        g2.setColor(new Color(72, 56, 32, hover ? 23 : 16));
        g2.fillRoundRect(2, 3, getWidth() - 4, getHeight() - 4, 16, 16);
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 3, 16, 16);
        g2.setColor(isEnabled() ? tone.accent : new Color(199, 194, 183));
        g2.setStroke(new BasicStroke(hover ? 1.35f : 1f));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 3, 16, 16);
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }

    private RemoteWeightInfo getWeight() {
      return weight;
    }

    private RecommendationAction getAction() {
      return action;
    }
  }

  private static final class RecommendationHeaderPanel extends JPanel {
    private final JComponent centered;
    private final JComponent trailing;

    private RecommendationHeaderPanel(JComponent centered, JComponent trailing) {
      this.centered = centered;
      this.trailing = trailing;
      setLayout(null);
      setOpaque(false);
      add(centered);
      add(trailing);
    }

    @Override
    public void doLayout() {
      Rectangle centeredBounds =
          centeredRecommendationIconBounds(getWidth(), getHeight(), centered.getPreferredSize());
      centered.setBounds(centeredBounds);
      Dimension trailingSize = trailing.getPreferredSize();
      trailing.setBounds(
          Math.max(0, getWidth() - trailingSize.width),
          Math.max(0, (getHeight() - trailingSize.height) / 2),
          Math.min(getWidth(), trailingSize.width),
          trailingSize.height);
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension centeredSize = centered.getPreferredSize();
      Dimension trailingSize = trailing.getPreferredSize();
      return new Dimension(
          Math.max(centeredSize.width, trailingSize.width * 2),
          Math.max(centeredSize.height, trailingSize.height));
    }
  }

  static void configureWeightCatalogColumns(
      JPanel panel, JComponent identity, JComponent elo, JComponent date, JComponent status) {
    panel.removeAll();
    panel.setLayout(new WeightCatalogColumnsLayout(identity, elo, date, status));
    setHorizontalAlignment(identity, SwingConstants.LEFT);
    setHorizontalAlignment(elo, SwingConstants.CENTER);
    setHorizontalAlignment(date, SwingConstants.CENTER);
    setHorizontalAlignment(status, SwingConstants.CENTER);
    panel.add(identity);
    panel.add(elo);
    panel.add(date);
    panel.add(status);
  }

  static boolean isWeightCatalogDownloadHit(int x, int listWidth) {
    Rectangle statusBounds =
        weightCatalogStatusActionBounds(
            listWidth,
            WEIGHT_CATALOG_ROW_HEIGHT,
            new Insets(0, WEIGHT_CATALOG_HORIZONTAL_INSET, 0, WEIGHT_CATALOG_HORIZONTAL_INSET));
    return x >= statusBounds.x && x < statusBounds.x + statusBounds.width;
  }

  private static void setHorizontalAlignment(JComponent component, int alignment) {
    if (component instanceof JLabel) {
      ((JLabel) component).setHorizontalAlignment(alignment);
    }
  }

  static Rectangle[] weightCatalogColumnBounds(int width, int height, Insets insets) {
    Insets safeInsets = insets == null ? new Insets(0, 0, 0, 0) : insets;
    int contentWidth = Math.max(0, width - safeInsets.left - safeInsets.right);
    int gapWidth = WEIGHT_CATALOG_ELO_GAP + WEIGHT_CATALOG_DATE_GAP + WEIGHT_CATALOG_STATUS_GAP;
    int columnWidth = Math.max(0, contentWidth - gapWidth);
    int minimumTrailingWidth =
        WEIGHT_CATALOG_ELO_WIDTH + WEIGHT_CATALOG_DATE_WIDTH + WEIGHT_CATALOG_STATUS_WIDTH;
    int modelWidth =
        Math.min(WEIGHT_CATALOG_MODEL_MAX_WIDTH, Math.max(0, columnWidth - minimumTrailingWidth));
    int trailingWidth = Math.max(0, columnWidth - modelWidth);
    int eloWidth;
    int dateWidth;
    if (trailingWidth >= minimumTrailingWidth) {
      int extraWidth = trailingWidth - minimumTrailingWidth;
      eloWidth = WEIGHT_CATALOG_ELO_WIDTH + Math.round(extraWidth * 0.20f);
      dateWidth = WEIGHT_CATALOG_DATE_WIDTH + Math.round(extraWidth * 0.35f);
    } else {
      eloWidth =
          minimumTrailingWidth == 0
              ? 0
              : Math.round(
                  trailingWidth * (WEIGHT_CATALOG_ELO_WIDTH / (float) minimumTrailingWidth));
      dateWidth =
          minimumTrailingWidth == 0
              ? 0
              : Math.round(
                  trailingWidth * (WEIGHT_CATALOG_DATE_WIDTH / (float) minimumTrailingWidth));
    }
    int statusWidth = Math.max(0, trailingWidth - eloWidth - dateWidth);
    int contentHeight = Math.max(0, height - safeInsets.top - safeInsets.bottom);
    int x = safeInsets.left;
    Rectangle model = new Rectangle(x, safeInsets.top, modelWidth, contentHeight);
    x += modelWidth + WEIGHT_CATALOG_ELO_GAP;
    Rectangle elo = new Rectangle(x, safeInsets.top, eloWidth, contentHeight);
    x += eloWidth + WEIGHT_CATALOG_DATE_GAP;
    Rectangle date = new Rectangle(x, safeInsets.top, dateWidth, contentHeight);
    x += dateWidth + WEIGHT_CATALOG_STATUS_GAP;
    Rectangle status = new Rectangle(x, safeInsets.top, statusWidth, contentHeight);
    return new Rectangle[] {model, elo, date, status};
  }

  private static Rectangle weightCatalogStatusActionBounds(int width, int height, Insets insets) {
    Rectangle statusColumn = weightCatalogColumnBounds(width, height, insets)[3];
    int actionWidth = Math.min(statusColumn.width, WEIGHT_CATALOG_STATUS_WIDTH);
    return new Rectangle(
        statusColumn.x + Math.max(0, (statusColumn.width - actionWidth) / 2),
        statusColumn.y,
        actionWidth,
        statusColumn.height);
  }

  private static final class WeightCatalogColumnsLayout implements LayoutManager {
    private final Component identity;
    private final Component elo;
    private final Component date;
    private final Component status;

    private WeightCatalogColumnsLayout(
        Component identity, Component elo, Component date, Component status) {
      this.identity = identity;
      this.elo = elo;
      this.date = date;
      this.status = status;
    }

    @Override
    public void addLayoutComponent(String name, Component component) {}

    @Override
    public void removeLayoutComponent(Component component) {}

    @Override
    public Dimension preferredLayoutSize(Container parent) {
      Insets insets = parent.getInsets();
      int width =
          WEIGHT_CATALOG_MODEL_MAX_WIDTH
              + WEIGHT_CATALOG_ELO_GAP
              + WEIGHT_CATALOG_ELO_WIDTH
              + WEIGHT_CATALOG_DATE_GAP
              + WEIGHT_CATALOG_DATE_WIDTH
              + WEIGHT_CATALOG_STATUS_GAP
              + WEIGHT_CATALOG_STATUS_WIDTH
              + insets.left
              + insets.right;
      int height =
          Math.max(
              Math.max(identity.getPreferredSize().height, elo.getPreferredSize().height),
              Math.max(date.getPreferredSize().height, status.getPreferredSize().height));
      return new Dimension(width, height + insets.top + insets.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(Container parent) {
      Rectangle[] bounds =
          weightCatalogColumnBounds(parent.getWidth(), parent.getHeight(), parent.getInsets());
      Component[] components = {identity, elo, date, status};
      for (int index = 0; index < components.length; index++) {
        Dimension preferred = components[index].getPreferredSize();
        Rectangle column = bounds[index];
        int componentHeight = Math.min(column.height, preferred.height);
        components[index].setBounds(
            column.x,
            column.y + Math.max(0, (column.height - componentHeight) / 2),
            column.width,
            componentHeight);
      }
    }
  }

  private final class WeightCatalogRenderer extends JPanel
      implements ListCellRenderer<WeightCatalogEntry> {
    private final FamilyTagLabel family = new FamilyTagLabel();
    private final JFontLabel model = new JFontLabel();
    private final WeightStatusTagLabel recommendation = new WeightStatusTagLabel();
    private final JFontLabel elo = new JFontLabel();
    private final JFontLabel date = new JFontLabel();
    private final WeightStatusTagLabel status = new WeightStatusTagLabel();
    private final JPanel identity = new JPanel(new GridBagLayout());
    private boolean selected;
    private boolean focused;

    private WeightCatalogRenderer() {
      identity.setOpaque(false);
      GridBagConstraints gbc = new GridBagConstraints();
      gbc.gridy = 0;
      gbc.insets = new Insets(0, 0, 0, 8);
      identity.add(family, gbc);
      gbc.gridx = 1;
      gbc.weightx = 1;
      gbc.fill = GridBagConstraints.HORIZONTAL;
      identity.add(model, gbc);
      gbc.gridx = 2;
      gbc.weightx = 0;
      gbc.fill = GridBagConstraints.NONE;
      gbc.insets = new Insets(0, 5, 0, 4);
      identity.add(recommendation, gbc);

      elo.setHorizontalAlignment(SwingConstants.CENTER);
      date.setHorizontalAlignment(SwingConstants.RIGHT);
      status.setHorizontalAlignment(SwingConstants.CENTER);
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));
      configureWeightCatalogColumns(this, identity, elo, date, status);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends WeightCatalogEntry> list,
        WeightCatalogEntry value,
        int index,
        boolean isSelected,
        boolean cellHasFocus) {
      selected = isSelected;
      focused = cellHasFocus;
      Color primary = isSelected ? new Color(18, 80, 75) : TEXT_PRIMARY;
      RemoteWeightInfo remote = value == null ? null : value.remoteInfo;
      Path local = value == null ? null : value.localPath;
      family.setFamily(
          remote == null ? text("AutoSetup.customWeightShort") : remoteWeightFamily(remote),
          isSelected);
      model.setText(
          remote != null
              ? formatRemoteModelName(remote)
              : local == null
                  ? text("AutoSetup.noLocalWeights")
                  : KataGoAutoSetupHelper.resolveWeightDisplayName(local));
      model.setForeground(primary);
      Font baseFont = list.getFont() == null ? model.getFont() : list.getFont();
      model.setFont(baseFont.deriveFont(Font.PLAIN));
      model.setMinimumSize(new Dimension(0, model.getPreferredSize().height));
      identity.setMinimumSize(new Dimension(0, identity.getPreferredSize().height));
      elo.setFont(baseFont.deriveFont(Font.BOLD));
      date.setFont(baseFont.deriveFont(Font.PLAIN));
      recommendation.setStatus(remoteWeightRecommendation(remote), StatusTagTone.GOLD);
      elo.setText(remote == null ? "-" : compactEloRating(remote.eloRating));
      date.setText(remote == null ? formatLocalWeightDate(local) : formatRemoteReleaseDate(remote));
      boolean current = local != null && isCurrentLocalWeight(local);
      if (current) {
        status.setStatus(text("AutoSetup.currentlyUsingShort"), StatusTagTone.TEAL);
        status.setToolTipText(text("AutoSetup.currentlyUsingShort"));
      } else if (remote != null && local == null) {
        status.setDownloadAction(
            text("AutoSetup.downloadShort"), index == hoveredWeightDownloadRow);
        status.setToolTipText(text("AutoSetup.downloadWeight"));
      } else {
        status.setStatus(
            remote == null ? text("AutoSetup.importedWeight") : text("AutoSetup.downloaded"),
            StatusTagTone.SUCCESS);
        status.setToolTipText(
            remote == null ? text("AutoSetup.importedWeight") : text("AutoSetup.downloaded"));
      }
      Color secondary = isSelected ? new Color(29, 108, 89) : new Color(88, 91, 86);
      elo.setForeground(secondary);
      date.setForeground(secondary);
      String tooltip =
          value == null
              ? null
              : remote != null
                  ? weightTooltip(
                      formatRemoteModelName(remote),
                      formatRemoteMetadata(remote),
                      remote.eloRating,
                      remote.fileName(),
                      remote.downloadUrl)
                  : weightTooltip(
                      KataGoAutoSetupHelper.resolveWeightDisplayName(local),
                      local.toAbsolutePath().normalize().toString());
      setToolTipText(tooltip);
      model.setToolTipText(tooltip);
      elo.setToolTipText(tooltip);
      date.setToolTipText(tooltip);
      return this;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setColor(selected ? new Color(230, 244, 239) : new Color(255, 254, 250));
        g2.fillRect(0, 0, getWidth(), getHeight());
        if (selected) {
          g2.setColor(ACCENT_TEAL);
          g2.fillRoundRect(0, 2, 5, Math.max(1, getHeight() - 4), 5, 5);
        }
        g2.setColor(new Color(231, 227, 218));
        g2.drawLine(10, getHeight() - 1, getWidth() - 10, getHeight() - 1);
        if (focused) {
          g2.setColor(new Color(49, 125, 116, 145));
          g2.drawRoundRect(5, 2, Math.max(1, getWidth() - 11), Math.max(1, getHeight() - 5), 8, 8);
        }
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }
  }

  static int weightCatalogVisibleHeight(int rows) {
    return Math.max(0, rows) * WEIGHT_CATALOG_ROW_HEIGHT + 2;
  }

  static int weightCatalogViewportRows(int entryCount) {
    if (entryCount <= 0) {
      return 2;
    }
    return Math.min(WEIGHT_CATALOG_VISIBLE_ROWS, entryCount);
  }

  private String remoteWeightRecommendation(RemoteWeightInfo info) {
    if (info == null) {
      return "";
    }
    if (KataGoAutoSetupHelper.isDefaultGeneralUseWeight(info)) {
      return text("AutoSetup.recommendedBadge");
    }
    if (info.recommended) {
      return text("AutoSetup.officialStrongestBadge");
    }
    return info.latest ? text("AutoSetup.recommendedLatest") : "";
  }

  private String remoteWeightFamily(RemoteWeightInfo info) {
    if (info == null) {
      return "";
    }
    String model = info.modelName == null ? "" : info.modelName.toLowerCase(Locale.ROOT);
    int marker = model.indexOf("-b");
    while (marker >= 0 && marker + 2 < model.length()) {
      int end = marker + 2;
      while (end < model.length() && Character.isDigit(model.charAt(end))) {
        end++;
      }
      if (end > marker + 2) {
        return model.substring(marker + 2, end).toUpperCase(Locale.ROOT) + "B";
      }
      marker = model.indexOf("-b", marker + 2);
    }
    String display = formatRemoteModelName(info);
    for (String token : display.split("\\s+")) {
      if (token.toUpperCase(Locale.ROOT).matches("[0-9]{1,3}B")) {
        return token.toUpperCase(Locale.ROOT);
      }
    }
    return "AI";
  }

  private enum WeightButtonStyle {
    PRIMARY,
    OUTLINE,
    ICON
  }

  private static final class WeightCatalogTabUI extends BasicButtonUI {
    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean selected = button.isSelected();
        boolean hover = button.isEnabled() && button.getModel().isRollover();
        Color fill =
            selected ? ACCENT_TEAL : hover ? new Color(247, 244, 236) : new Color(251, 249, 244);
        Color border = selected ? ACCENT_TEAL : new Color(218, 211, 198);
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), 9, 9);
        g2.setColor(border);
        g2.drawRoundRect(0, 0, component.getWidth() - 1, component.getHeight() - 1, 9, 9);
        button.setForeground(selected ? Color.WHITE : TEXT_SECONDARY);
      } finally {
        g2.dispose();
      }
      super.paint(graphics, component);
    }
  }

  private static final class WeightCatalogScrollBarUI extends BasicScrollBarUI {
    @Override
    protected void configureScrollBarColors() {
      thumbColor = new Color(159, 156, 148);
      trackColor = new Color(245, 242, 235);
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
      return zeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
      return zeroButton();
    }

    private JButton zeroButton() {
      JButton button = new JButton();
      button.setPreferredSize(new Dimension(0, 0));
      button.setMinimumSize(new Dimension(0, 0));
      button.setMaximumSize(new Dimension(0, 0));
      return button;
    }

    @Override
    protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
      if (!component.isEnabled() || bounds.isEmpty()) {
        return;
      }
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(thumbColor);
        g2.fillRoundRect(
            bounds.x + 2,
            bounds.y + 2,
            Math.max(4, bounds.width - 4),
            Math.max(8, bounds.height - 4),
            8,
            8);
      } finally {
        g2.dispose();
      }
    }
  }

  private static final class RecommendationIcon implements Icon {
    private static final int SIZE = 40;
    private final RecommendationTone tone;

    private RecommendationIcon(RecommendationTone tone) {
      this.tone = tone;
    }

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
        g2.setColor(tone.wash);
        g2.fillOval(x, y, SIZE, SIZE);
        g2.setColor(tone.accent);
        g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawOval(x + 2, y + 2, SIZE - 5, SIZE - 5);
        int centerX = x + SIZE / 2;
        int centerY = y + SIZE / 2;
        if (tone == RecommendationTone.GOLD) {
          Polygon crown = new Polygon();
          crown.addPoint(x + 9, y + 17);
          crown.addPoint(x + 16, y + 25);
          crown.addPoint(centerX, y + 12);
          crown.addPoint(x + 30, y + 25);
          crown.addPoint(x + 37, y + 17);
          crown.addPoint(x + 34, y + 34);
          crown.addPoint(x + 12, y + 34);
          g2.drawPolygon(crown);
          g2.drawLine(x + 12, y + 29, x + 34, y + 29);
        } else if (tone == RecommendationTone.SAGE) {
          g2.drawArc(x + 12, y + 8, 23, 29, 100, 205);
          g2.drawLine(x + 14, y + 35, x + 33, y + 15);
          g2.drawLine(x + 19, y + 28, x + 28, y + 28);
        } else {
          Polygon star = new Polygon();
          for (int index = 0; index < 10; index++) {
            double angle = -Math.PI / 2 + index * Math.PI / 5;
            double radius = index % 2 == 0 ? 14 : 6;
            star.addPoint(
                centerX + (int) Math.round(Math.cos(angle) * radius),
                centerY + (int) Math.round(Math.sin(angle) * radius));
          }
          g2.fillPolygon(star);
        }
      } finally {
        g2.dispose();
      }
    }
  }

  private static final class WeightButtonUI extends BasicButtonUI {
    private final WeightButtonStyle style;

    private WeightButtonUI(WeightButtonStyle style) {
      this.style = style;
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
      AbstractButton button = (AbstractButton) component;
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean hover = button.isEnabled() && button.getModel().isRollover();
        boolean pressed = button.isEnabled() && button.getModel().isPressed();
        Color fill;
        Color border;
        if (!button.isEnabled()) {
          fill = new Color(232, 228, 218);
          border = new Color(218, 212, 200);
        } else if (style == WeightButtonStyle.PRIMARY) {
          fill = pressed ? new Color(7, 79, 74) : hover ? ACCENT_TEAL_HOVER : ACCENT_TEAL;
          border = fill.darker();
        } else {
          fill = hover ? new Color(255, 249, 237) : new Color(255, 253, 248);
          border = hover ? ACCENT_GOLD : new Color(213, 179, 119);
        }
        int arc = style == WeightButtonStyle.ICON ? 11 : 13;
        g2.setColor(fill);
        g2.fill(
            new RoundRectangle2D.Float(
                0.5f, 0.5f, component.getWidth() - 1f, component.getHeight() - 1f, arc, arc));
        g2.setColor(border);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(
            new RoundRectangle2D.Float(
                0.5f, 0.5f, component.getWidth() - 1f, component.getHeight() - 1f, arc, arc));
      } finally {
        g2.dispose();
      }
      super.paint(graphics, component);
    }
  }

  private static final class RoundedSurfacePanel extends JPanel {
    private final Color surface;
    private final Color outline;
    private final int arc;
    private final boolean shadow;

    private RoundedSurfacePanel(Color surface, Color outline, int arc, boolean shadow) {
      this.surface = surface;
      this.outline = outline;
      this.arc = arc;
      this.shadow = shadow;
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int bottomInset = shadow ? 3 : 1;
        if (shadow) {
          g2.setColor(new Color(73, 55, 30, 22));
          g2.fill(new RoundRectangle2D.Float(2f, 3f, getWidth() - 4f, getHeight() - 4f, arc, arc));
        }
        g2.setColor(surface);
        g2.fill(
            new RoundRectangle2D.Float(
                0.5f, 0.5f, getWidth() - 1f, getHeight() - bottomInset, arc, arc));
        g2.setColor(outline);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(
            new RoundRectangle2D.Float(
                0.5f, 0.5f, getWidth() - 1f, getHeight() - bottomInset, arc, arc));
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }
  }

  private static final class StatusPillLabel extends JFontLabel {
    private Color tone = OK_COLOR;

    private StatusPillLabel() {
      setOpaque(false);
    }

    @Override
    public void setForeground(Color color) {
      tone = color == null ? OK_COLOR : color;
      super.setForeground(OK_COLOR.equals(tone) ? Color.WHITE : ERROR_COLOR);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      boolean ready = OK_COLOR.equals(tone);
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(ready ? ACCENT_TEAL : new Color(253, 239, 235));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        if (!ready) {
          g2.setColor(new Color(224, 173, 162));
          g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
        }
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }
  }

  private static final class WarmBodyPanel extends JPanel {
    private WarmBodyPanel() {
      super(new BorderLayout(0, 0));
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setPaint(
            new GradientPaint(
                0, 0, new Color(250, 247, 240), getWidth(), getHeight(), new Color(246, 242, 233)));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(new Color(178, 139, 76, 8));
        for (int x = 24; x < getWidth(); x += 48) {
          g2.drawLine(x, 0, x, getHeight());
        }
        for (int y = 24; y < getHeight(); y += 48) {
          g2.drawLine(0, y, getWidth(), y);
        }
        g2.setColor(new Color(195, 158, 92, 14));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawArc(getWidth() - 320, -180, 420, 420, 202, 118);
        g2.drawArc(getWidth() - 250, -130, 310, 310, 195, 125);
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }
  }

  private static final class SidebarBackdropPanel extends JPanel {
    private SidebarBackdropPanel() {
      super(new BorderLayout(0, 12));
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setPaint(
            new GradientPaint(0, 0, new Color(6, 67, 63), 0, getHeight(), new Color(2, 45, 45)));
        g2.fillRect(0, 0, getWidth(), getHeight());
        int startY = Math.max(300, getHeight() - 190);
        g2.setColor(new Color(203, 170, 101, 24));
        for (int x = 18; x < getWidth(); x += 27) {
          g2.drawLine(x, startY, x, getHeight());
        }
        for (int y = startY; y < getHeight(); y += 27) {
          g2.drawLine(0, y, getWidth(), y);
        }
        int[][] stones = {
          {25, 45, 13, 0},
          {54, 73, 13, 0},
          {82, 100, 14, 1},
          {110, 74, 13, 1},
          {136, 48, 13, 0},
          {164, 99, 13, 0},
          {54, 128, 13, 0},
          {110, 154, 13, 1}
        };
        for (int[] stone : stones) {
          int y = startY + stone[1];
          g2.setColor(stone[3] == 0 ? new Color(5, 16, 17, 150) : new Color(221, 229, 219, 135));
          g2.fillOval(stone[0], y, stone[2], stone[2]);
        }
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }
  }

  static Font deriveSidebarNavFont(Font preferred, Font fallback, boolean selected) {
    Font base = preferred != null ? preferred : fallback;
    if (base == null) {
      base = new Font(Font.DIALOG, Font.PLAIN, 12);
    }
    return base.deriveFont(selected ? Font.BOLD : Font.PLAIN, base.getSize2D() + 1.5f);
  }

  private static final class SidebarNavRenderer extends JPanel implements ListCellRenderer<String> {
    private final JFontLabel label = new JFontLabel();
    private boolean selected;
    private boolean focused;

    private SidebarNavRenderer() {
      super(new BorderLayout());
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 10));
      label.setIconTextGap(15);
      add(label, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends String> list,
        String value,
        int index,
        boolean isSelected,
        boolean cellHasFocus) {
      selected = isSelected;
      focused = cellHasFocus;
      label.setText(value);
      label.setIcon(new NavIcon(index, isSelected));
      label.setForeground(isSelected ? Color.WHITE : SIDEBAR_TEXT);
      label.setFont(deriveSidebarNavFont(list.getFont(), label.getFont(), isSelected));
      return this;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (selected) {
          g2.setColor(SIDEBAR_SELECTED_BG);
          g2.fillRoundRect(2, 3, getWidth() - 4, getHeight() - 6, 14, 14);
          g2.setColor(ACCENT_GOLD);
          g2.fillRoundRect(2, 8, 5, getHeight() - 16, 5, 5);
        }
        if (focused) {
          g2.setColor(new Color(242, 210, 148, 120));
          g2.drawRoundRect(3, 4, getWidth() - 7, getHeight() - 9, 12, 12);
        }
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }
  }

  private static final class FamilyTagLabel extends JFontLabel {
    private Color tone = new Color(30, 135, 124);

    private FamilyTagLabel() {
      setHorizontalAlignment(SwingConstants.CENTER);
      setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
      setFont(getFont().deriveFont(Font.BOLD));
      setOpaque(false);
    }

    private void setFamily(String value, boolean selected) {
      setText(value);
      tone = familyTone(value, selected);
      setForeground(Color.WHITE);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(tone);
        g2.fillRoundRect(0, 2, getWidth(), Math.max(1, getHeight() - 4), 9, 9);
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }

    private static Color familyTone(String value, boolean selected) {
      String family = value == null ? "" : value.toUpperCase(Locale.ROOT);
      Color base;
      if (family.startsWith("40")) {
        base = new Color(58, 82, 143);
      } else if (family.startsWith("60")) {
        base = new Color(91, 68, 139);
      } else if (family.startsWith("28")) {
        base = new Color(22, 121, 111);
      } else if (family.startsWith("20")) {
        base = new Color(20, 126, 116);
      } else if (family.startsWith("18")) {
        base = new Color(48, 112, 150);
      } else if (family.startsWith("15")) {
        base = new Color(61, 115, 166);
      } else if (family.startsWith("10")) {
        base = new Color(55, 139, 132);
      } else if (family.startsWith("6")) {
        base = new Color(83, 139, 112);
      } else if (family.toLowerCase(Locale.ROOT).contains("custom")) {
        base = new Color(126, 105, 78);
      } else {
        base = new Color(30, 135, 124);
      }
      return selected ? base.darker() : base;
    }
  }

  private static final class WeightStatusTagLabel extends JFontLabel {
    private Color fill = new Color(247, 240, 222);
    private Color outline = new Color(221, 188, 122);
    private boolean actionStyle;

    private WeightStatusTagLabel() {
      setOpaque(false);
      setHorizontalAlignment(SwingConstants.CENTER);
      setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
      setFont(getFont().deriveFont(getFont().getSize2D() - 1f));
    }

    private void setStatus(String value, StatusTagTone tone) {
      String status = value == null ? "" : value.trim();
      setText(status);
      setVisible(!status.isEmpty());
      actionStyle = false;
      setIcon(null);
      setIconTextGap(0);
      setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
      if (tone == StatusTagTone.SUCCESS) {
        fill = new Color(236, 247, 239);
        outline = new Color(161, 203, 175);
        setForeground(new Color(24, 112, 76));
      } else if (tone == StatusTagTone.TEAL) {
        fill = new Color(231, 246, 242);
        outline = new Color(145, 198, 187);
        setForeground(new Color(19, 105, 95));
      } else {
        fill = new Color(253, 246, 229);
        outline = new Color(226, 190, 116);
        setForeground(new Color(168, 103, 18));
      }
    }

    private void setDownloadAction(String value, boolean hovered) {
      String label = value == null ? "" : value.trim();
      setText(label);
      setVisible(!label.isEmpty());
      actionStyle = true;
      setIcon(new WeightActionIcon(WeightActionIcon.DOWNLOAD));
      setIconTextGap(4);
      setBorder(BorderFactory.createEmptyBorder(1, 9, 1, 9));
      if (hovered) {
        fill = ACCENT_TEAL;
        outline = ACCENT_TEAL;
        setForeground(Color.WHITE);
      } else {
        fill = new Color(255, 250, 239);
        outline = new Color(218, 160, 61);
        setForeground(new Color(158, 91, 12));
      }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fill);
        int arc = actionStyle ? 10 : 8;
        int capsuleWidth = Math.min(getWidth(), WEIGHT_CATALOG_STATUS_WIDTH);
        int capsuleX = Math.max(0, (getWidth() - capsuleWidth) / 2);
        g2.fillRoundRect(
            capsuleX, 1, Math.max(1, capsuleWidth - 1), Math.max(1, getHeight() - 2), arc, arc);
        g2.setColor(outline);
        g2.drawRoundRect(
            capsuleX, 1, Math.max(1, capsuleWidth - 1), Math.max(1, getHeight() - 2), arc, arc);
      } finally {
        g2.dispose();
      }
      super.paintComponent(graphics);
    }
  }

  private enum StatusTagTone {
    GOLD,
    SUCCESS,
    TEAL
  }

  static final class ResponsiveActionRow extends JPanel {
    private static final int GAP = 7;
    private static final int MIN_MAIN_WIDTH = 220;
    private static final int DEFAULT_LAYOUT_WIDTH = 640;
    private final JComponent mainComponent;
    private final JComponent[] actions;
    private boolean lastHorizontal = true;

    private ResponsiveActionRow(JComponent mainComponent, JComponent... actions) {
      this.mainComponent = mainComponent;
      this.actions = actions.clone();
      setLayout(null);
      setOpaque(false);
      add(mainComponent);
      for (JComponent action : this.actions) {
        add(action);
      }
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
      boolean newHorizontal = horizontal(Math.max(1, width));
      boolean orientationChanged = newHorizontal != lastHorizontal;
      lastHorizontal = newHorizontal;
      super.setBounds(x, y, width, height);
      if (orientationChanged && getParent() != null) {
        SwingUtilities.invokeLater(getParent()::revalidate);
      }
    }

    private int actionWidth() {
      int width = 0;
      for (JComponent action : actions) {
        width += action.getPreferredSize().width;
      }
      return width + Math.max(0, actions.length - 1) * GAP;
    }

    private int tallestAction() {
      int height = 0;
      for (JComponent action : actions) {
        height = Math.max(height, action.getPreferredSize().height);
      }
      return height;
    }

    private boolean horizontal(int availableWidth) {
      return availableWidth >= MIN_MAIN_WIDTH + GAP + actionWidth();
    }

    @Override
    public Dimension getPreferredSize() {
      int width = getWidth() > 0 ? getWidth() : DEFAULT_LAYOUT_WIDTH;
      Dimension main = mainComponent.getPreferredSize();
      if (horizontal(width)) {
        return new Dimension(
            Math.max(main.width + GAP + actionWidth(), width),
            Math.max(main.height, tallestAction()));
      }
      return new Dimension(
          Math.max(main.width, actionWidth()),
          main.height + GAP + wrappedActionHeight(Math.max(1, width)));
    }

    private int wrappedActionHeight(int width) {
      int rows = 1;
      int used = 0;
      for (JComponent action : actions) {
        int actionWidth = action.getPreferredSize().width;
        if (used > 0 && used + GAP + actionWidth > width) {
          rows++;
          used = 0;
        }
        used += (used == 0 ? 0 : GAP) + actionWidth;
      }
      return rows * tallestAction() + Math.max(0, rows - 1) * GAP;
    }

    @Override
    public void doLayout() {
      int width = getWidth();
      Dimension main = mainComponent.getPreferredSize();
      if (horizontal(width)) {
        int actionX = width;
        int rowHeight = Math.max(main.height, tallestAction());
        for (int index = actions.length - 1; index >= 0; index--) {
          Dimension size = actions[index].getPreferredSize();
          actionX -= size.width;
          actions[index].setBounds(actionX, (rowHeight - size.height) / 2, size.width, size.height);
          actionX -= GAP;
        }
        mainComponent.setBounds(
            0, (rowHeight - main.height) / 2, Math.max(0, actionX), main.height);
        return;
      }

      mainComponent.setBounds(0, 0, width, main.height);
      int x = width;
      int y = main.height + GAP;
      int rowHeight = tallestAction();
      for (int index = actions.length - 1; index >= 0; index--) {
        Dimension size = actions[index].getPreferredSize();
        if (x < width && x - GAP - size.width < 0) {
          x = width;
          y += rowHeight + GAP;
        }
        x -= size.width;
        actions[index].setBounds(x, y, size.width, size.height);
        x -= GAP;
      }
    }
  }

  static final class ActiveCardLayout extends CardLayout {
    @Override
    public Dimension preferredLayoutSize(Container parent) {
      return activeSize(parent, false);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      return activeSize(parent, true);
    }

    private Dimension activeSize(Container parent, boolean minimum) {
      synchronized (parent.getTreeLock()) {
        Dimension size = new Dimension();
        for (Component component : parent.getComponents()) {
          if (!component.isVisible()) {
            continue;
          }
          Dimension candidate = minimum ? component.getMinimumSize() : component.getPreferredSize();
          size.width = candidate.width;
          size.height = candidate.height;
          break;
        }
        Insets insets = parent.getInsets();
        size.width += insets.left + insets.right + getHgap() * 2;
        size.height += insets.top + insets.bottom + getVgap() * 2;
        return size;
      }
    }
  }

  private static final class NavIcon implements Icon {
    private static final int SIZE = 25;
    private final int type;
    private final boolean selected;

    private NavIcon(int type, boolean selected) {
      this.type = type;
      this.selected = selected;
    }

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
        g2.translate(x, y);
        double scale = SIZE / 21.0;
        g2.scale(scale, scale);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(selected ? new Color(245, 218, 166) : new Color(205, 220, 211));
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (type) {
          case 0:
            for (int row = 0; row < 2; row++) {
              for (int column = 0; column < 2; column++) {
                g2.fillRoundRect(2 + column * 10, 2 + row * 10, 7, 7, 2, 2);
              }
            }
            break;
          case 1:
            g2.drawRoundRect(2, 5, 17, 12, 7, 7);
            g2.drawLine(10, 3, 10, 13);
            g2.drawLine(6, 10, 10, 14);
            g2.drawLine(14, 10, 10, 14);
            break;
          case 2:
            g2.drawOval(6, 2, 9, 12);
            g2.drawLine(6, 11, 3, 17);
            g2.drawLine(15, 11, 18, 17);
            g2.drawLine(8, 16, 10, 20);
            g2.drawLine(12, 16, 10, 20);
            break;
          case 3:
            g2.drawRoundRect(4, 4, 13, 13, 3, 3);
            g2.drawRect(7, 7, 7, 7);
            for (int offset = 5; offset <= 16; offset += 5) {
              g2.drawLine(offset, 1, offset, 4);
              g2.drawLine(offset, 17, offset, 20);
              g2.drawLine(1, offset, 4, offset);
              g2.drawLine(17, offset, 20, offset);
            }
            break;
          default:
            g2.drawOval(2, 2, 17, 17);
            g2.drawOval(6, 2, 9, 17);
            g2.drawLine(2, 10, 19, 10);
            break;
        }
      } finally {
        g2.dispose();
      }
    }
  }

  private static final class WeightActionIcon implements Icon {
    private static final int DOWNLOAD = 0;
    private static final int IMPORT = 1;
    private static final int USE = 2;
    private static final int SIZE = 16;
    private final int type;

    private WeightActionIcon(int type) {
      this.type = type;
    }

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
      AbstractButton button =
          component instanceof AbstractButton ? (AbstractButton) component : null;
      Color color =
          component != null && component.isEnabled()
              ? component.getForeground()
              : new Color(142, 142, 137);
      Graphics2D g2 = (Graphics2D) graphics.create();
      try {
        g2.translate(x, y);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (type == USE) {
          g2.drawOval(1, 1, 14, 14);
          g2.drawLine(4, 8, 7, 11);
          g2.drawLine(7, 11, 12, 5);
          return;
        }
        g2.drawLine(2, 12, 2, 15);
        g2.drawLine(2, 15, 14, 15);
        g2.drawLine(14, 15, 14, 12);
        if (type == DOWNLOAD) {
          g2.drawLine(8, 1, 8, 11);
          g2.drawLine(4, 7, 8, 11);
          g2.drawLine(12, 7, 8, 11);
        } else {
          g2.drawLine(8, 12, 8, 2);
          g2.drawLine(4, 6, 8, 2);
          g2.drawLine(12, 6, 8, 2);
        }
      } finally {
        g2.dispose();
      }
    }
  }

  private static final class HumanSlIcon implements Icon {
    private static final int SIZE = 38;

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
        g2.setColor(new Color(229, 244, 235));
        g2.fillOval(x, y, SIZE, SIZE);
        g2.setColor(OK_COLOR);
        g2.fillOval(x + 13, y + 7, 12, 12);
        g2.fillRoundRect(x + 8, y + 21, 22, 11, 9, 9);
      } finally {
        g2.dispose();
      }
    }
  }

  private static class WeightStoneIcon implements Icon {
    private final int size;
    private final boolean circular;

    private WeightStoneIcon(int size) {
      this(size, false);
    }

    private WeightStoneIcon(int size, boolean circular) {
      this.size = Math.max(32, size);
      this.circular = circular;
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
      try {
        Shape originalClip = g2.getClip();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(226, 196, 136));
        int arc = Math.max(10, size / 4);
        if (circular) {
          g2.fillOval(x, y, size, size);
          g2.setClip(new Ellipse2D.Double(x, y, size, size));
        } else {
          g2.fillRoundRect(x, y, size, size, arc, arc);
        }
        g2.setColor(new Color(153, 116, 61, 145));
        int margin = Math.max(6, size / 7);
        int step = (size - margin * 2) / 3;
        for (int index = 0; index < 4; index++) {
          int offset = margin + index * step;
          g2.drawLine(x + offset, y + margin, x + offset, y + size - margin);
          g2.drawLine(x + margin, y + offset, x + size - margin, y + offset);
        }
        int stone = Math.max(13, size / 3);
        g2.setColor(new Color(31, 34, 32));
        g2.fillOval(x + margin, y + margin, stone, stone);
        g2.setColor(new Color(250, 249, 245));
        int whiteX = x + size - margin - stone;
        int whiteY = y + size - margin - stone;
        g2.fillOval(whiteX, whiteY, stone, stone);
        g2.setColor(new Color(157, 153, 143));
        g2.drawOval(whiteX, whiteY, stone, stone);
        if (circular) {
          int smallStone = Math.max(9, stone - 4);
          g2.setColor(new Color(31, 34, 32));
          g2.fillOval(x + margin, y + size - margin - smallStone, smallStone, smallStone);
          g2.fillOval(x + size / 2, y + size / 2 - smallStone / 2, smallStone, smallStone);
          g2.setColor(new Color(250, 249, 245));
          g2.fillOval(x + size - margin - smallStone, y + margin, smallStone, smallStone);
          g2.setColor(new Color(157, 153, 143));
          g2.drawOval(x + size - margin - smallStone, y + margin, smallStone, smallStone);
          g2.setClip(originalClip);
          g2.setColor(new Color(194, 139, 45, 150));
          g2.drawOval(x, y, size - 1, size - 1);
        }
      } finally {
        g2.dispose();
      }
    }
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
