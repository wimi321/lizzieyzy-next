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
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
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
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
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
  private static final int MAX_INFO_TEXT_LENGTH = 104;
  private static final int DIALOG_WIDTH = 820;
  private static final int DIALOG_HEIGHT = 580;
  private static final int VALUE_COLUMN_WIDTH = 390;
  private static final String CARD_OVERVIEW = "overview";
  private static final String CARD_WEIGHTS = "weights";
  private static final String CARD_ACCELERATION = "acceleration";
  private static final String CARD_BENCHMARK = "benchmark";

  private SetupSnapshot snapshot;
  private List<RemoteWeightInfo> remoteWeightInfos = Collections.emptyList();
  private volatile DownloadSession activeDownloadSession;
  private volatile Thread activeWorkerThread;
  private long progressStartedAtMillis;

  private final JLabel lblEngineValue = new JFontLabel();
  private final JLabel lblWeightValue = new JFontLabel();
  private final JLabel lblWeightModelValue = new JFontLabel();
  private final JLabel lblConfigValue = new JFontLabel();
  private final JLabel lblNvidiaRuntimeValue = new JFontLabel();
  private final JLabel lblBenchmarkValue = new JFontLabel();
  private final JLabel lblRemoteDetailValue = new JFontLabel();
  private final JLabel lblLocalWeightDetailValue = new JFontLabel();
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
  private final JFontButton btnDownloadWeight = new JFontButton();
  private final JFontButton btnImportWeight = new JFontButton();
  private final JFontButton btnApplyWeight = new JFontButton();
  private final JFontButton btnInstallNvidiaRuntime = new JFontButton();
  private final JFontButton btnInstallTensorRt = new JFontButton();
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
    body.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
    body.add(createSidebarPanel(), BorderLayout.WEST);

    detailCards.setOpaque(false);
    detailCards.add(createOverviewSection(), CARD_OVERVIEW);
    detailCards.add(createWeightsSection(), CARD_WEIGHTS);
    detailCards.add(createAccelerationSection(), CARD_ACCELERATION);
    detailCards.add(createBenchmarkSection(), CARD_BENCHMARK);
    JScrollPane detailScrollPane = new JScrollPane(detailCards);
    detailScrollPane.setBorder(null);
    detailScrollPane.getViewport().setOpaque(false);
    detailScrollPane.setOpaque(false);
    body.add(detailScrollPane, BorderLayout.CENTER);
    content.add(body, BorderLayout.CENTER);

    content.add(createFooterPanel(), BorderLayout.SOUTH);
    wireActions();

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

  private void configureButtons() {
    btnRefresh.setText(text("AutoSetup.refresh"));
    btnAutoSetup.setText(text("AutoSetup.autoSetup"));
    btnDownloadWeight.setText(text("AutoSetup.downloadWeight"));
    btnImportWeight.setText(text("AutoSetup.importWeight"));
    btnApplyWeight.setText(text("AutoSetup.applyWeight"));
    btnInstallNvidiaRuntime.setText(text("AutoSetup.installNvidiaRuntime"));
    btnInstallTensorRt.setText(text("AutoSetup.installTensorRt"));
    btnOptimizePerformance.setText(text("AutoSetup.optimizePerformance"));
    btnStopDownload.setText(text("AutoSetup.stopDownload"));
    btnStopDownload.setEnabled(false);
    btnClose.setText(text("AutoSetup.close"));

    styleButton(btnAutoSetup, true);
    styleButton(btnDownloadWeight, true);
    styleButton(btnOptimizePerformance, true);
    styleButton(btnRefresh, false);
    styleButton(btnImportWeight, false);
    styleButton(btnApplyWeight, false);
    styleButton(btnInstallNvidiaRuntime, false);
    styleButton(btnInstallTensorRt, false);
    styleButton(btnStopDownload, false);
    styleButton(btnClose, false);
  }

  private void wireActions() {
    btnRefresh.addActionListener(e -> refreshState());
    btnAutoSetup.addActionListener(e -> autoSetupOrDownload());
    btnDownloadWeight.addActionListener(e -> startRecommendedWeightDownload(false));
    btnImportWeight.addActionListener(e -> importCustomWeight());
    btnApplyWeight.addActionListener(e -> applySelectedWeight());
    btnInstallNvidiaRuntime.addActionListener(e -> startNvidiaRuntimeInstall());
    btnInstallTensorRt.addActionListener(e -> startTensorRtInstall());
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
    button.setMargin(new Insets(0, 12, 0, 12));
    AppleStyleSupport.installButtonStyle(button);
    Dimension preferred = button.getPreferredSize();
    button.setPreferredSize(new Dimension(Math.max(preferred.width, primary ? 112 : 96), 36));
  }

  private JPanel createHeaderPanel() {
    JPanel header = new JPanel(new BorderLayout(12, 4));
    header.setBackground(SIDEBAR_BG);
    header.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

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
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
    header.add(modeBadge, BorderLayout.EAST);
    return header;
  }

  private JPanel createSidebarPanel() {
    JPanel sidebar = new JPanel(new BorderLayout(0, 12));
    sidebar.setPreferredSize(new Dimension(150, 10));
    sidebar.setBackground(SIDEBAR_BG);
    sidebar.setBorder(BorderFactory.createEmptyBorder(14, 12, 14, 12));

    JFontLabel sidebarTitle = new JFontLabel(text("AutoSetup.sidebarTitle"));
    sidebarTitle.setForeground(SIDEBAR_TEXT);
    sidebarTitle.setFont(sidebarTitle.getFont().deriveFont(Font.BOLD));
    sidebar.add(sidebarTitle, BorderLayout.NORTH);

    sectionNav.setListData(
        new String[] {
          text("AutoSetup.navOverview"),
          text("AutoSetup.navWeights"),
          text("AutoSetup.navAcceleration"),
          text("AutoSetup.navBenchmark")
        });
    sectionNav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    sectionNav.setSelectedIndex(0);
    sectionNav.setFixedCellHeight(40);
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
            label.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
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
          switch (sectionNav.getSelectedIndex()) {
            case 1:
              detailCardLayout.show(detailCards, CARD_WEIGHTS);
              break;
            case 2:
              detailCardLayout.show(detailCards, CARD_ACCELERATION);
              break;
            case 3:
              detailCardLayout.show(detailCards, CARD_BENCHMARK);
              break;
            default:
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

  private JPanel createWeightsSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addComponentRow(rows, gbc, text("AutoSetup.localWeights"), cmbLocalWeights);
    addInfoRow(rows, gbc, text("AutoSetup.selectedLocalWeightInfo"), lblLocalWeightDetailValue);
    addComponentRow(rows, gbc, text("AutoSetup.officialWeights"), cmbRemoteWeights);
    addInfoRow(rows, gbc, text("AutoSetup.selectedWeightInfo"), lblRemoteDetailValue);

    JPanel actions =
        createActionBar(FlowLayout.RIGHT, btnImportWeight, btnApplyWeight, btnDownloadWeight);
    return createSectionCard(
        text("AutoSetup.weightsTitle"), text("AutoSetup.weightsSubtitle"), rows, actions);
  }

  private JPanel createAccelerationSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addInfoRow(rows, gbc, text("AutoSetup.nvidiaRuntime"), lblNvidiaRuntimeValue);

    JFontLabel tensorRtHint =
        new JFontLabel("<html>" + text("AutoSetup.accelerationTensorRtHint") + "</html>");
    tensorRtHint.setForeground(TEXT_SECONDARY);
    addComponentRow(rows, gbc, text("AutoSetup.installTensorRt"), tensorRtHint);

    JPanel actions = createActionBar(FlowLayout.RIGHT, btnInstallNvidiaRuntime, btnInstallTensorRt);
    return createSectionCard(
        text("AutoSetup.accelerationTitle"), text("AutoSetup.accelerationSubtitle"), rows, actions);
  }

  private JPanel createBenchmarkSection() {
    JPanel rows = createRowsPanel();
    GridBagConstraints gbc = createRowConstraints();
    addInfoRow(rows, gbc, text("AutoSetup.performance"), lblBenchmarkValue);

    JFontLabel benchmarkHint =
        new JFontLabel("<html>" + text("AutoSetup.benchmarkHint") + "</html>");
    benchmarkHint.setForeground(TEXT_SECONDARY);
    addComponentRow(rows, gbc, text("AutoSetup.benchmarkRecommended"), benchmarkHint);

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
    JPanel card = new JPanel(new BorderLayout(0, 14));
    card.setOpaque(true);
    card.setBackground(CARD_BG);
    card.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(224, 217, 203)),
            BorderFactory.createEmptyBorder(16, 16, 16, 16)));

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

    JPanel contentWrap = new JPanel(new BorderLayout());
    contentWrap.setOpaque(false);
    contentWrap.add(content, BorderLayout.NORTH);
    card.add(contentWrap, BorderLayout.CENTER);
    if (actions != null) {
      card.add(actions, BorderLayout.SOUTH);
    }
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
    gbc.insets = new Insets(0, 0, 10, 10);
    return gbc;
  }

  private JPanel createActionBar(int alignment, JComponent... actions) {
    JPanel actionBar = new JPanel(new FlowLayout(alignment, 8, 0));
    actionBar.setOpaque(false);
    for (JComponent action : actions) {
      actionBar.add(action);
    }
    return actionBar;
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
    constrainValueComponent(valueComponent);
    panel.add(valueComponent, valueConstraints);

    gbc.gridy += 1;
  }

  private void constrainValueComponent(JComponent valueComponent) {
    Dimension preferred = valueComponent.getPreferredSize();
    int height = Math.max(preferred.height, 32);
    valueComponent.setPreferredSize(new Dimension(VALUE_COLUMN_WIDTH, height));
    valueComponent.setMinimumSize(new Dimension(260, height));
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
      updateTensorRtInfo();
      return;
    }
    lblNvidiaRuntimeValue.setText(status.detailText);
    lblNvidiaRuntimeValue.setToolTipText(status.detailText);
    lblNvidiaRuntimeValue.setForeground(status.ready ? OK_COLOR : WARN_COLOR);
    btnInstallNvidiaRuntime.setEnabled(activeDownloadSession == null && !status.ready);
    updateTensorRtInfo();
  }

  private void updateTensorRtInfo() {
    KataGoRuntimeHelper.TensorRtInstallStatus status =
        snapshot == null ? null : KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
    if (status == null) {
      btnInstallTensorRt.setText(text("AutoSetup.installTensorRt"));
      btnInstallTensorRt.setToolTipText(null);
      btnInstallTensorRt.setEnabled(false);
      return;
    }
    btnInstallTensorRt.setText(
        status.installed ? text("AutoSetup.tensorRtReady") : text("AutoSetup.installTensorRt"));
    btnInstallTensorRt.setToolTipText(status.detailText);
    btnInstallTensorRt.setEnabled(
        activeDownloadSession == null
            && activeWorkerThread == null
            && status.applicable
            && !status.installed);
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

  private void applySelectedWeight() {
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    Path selectedWeight = getSelectedLocalWeight();
    if (selectedWeight == null) {
      Utils.showMsg(text("AutoSetup.missingWeight"), this);
      return;
    }
    startAutoSetup(snapshot.withActiveWeight(selectedWeight));
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
    if (snapshot == null) {
      snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    }
    KataGoRuntimeHelper.TensorRtInstallStatus status =
        KataGoRuntimeHelper.inspectTensorRtInstall(snapshot);
    if (!status.applicable) {
      Utils.showMsg(status.detailText, this);
      return;
    }
    if (status.installed) {
      Utils.showMsg(text("AutoSetup.tensorRtAlreadyReady"), this);
      return;
    }
    int result =
        JOptionPane.showConfirmDialog(
            this,
            String.format(
                text("AutoSetup.tensorRtConfirmMessage"), formatSize(status.downloadBytes)),
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
                      onSetupApplied(setupResult, text("AutoSetup.tensorRtInstallDoneMessage"));
                    });
              } catch (DownloadCancelledException e) {
                SwingUtilities.invokeLater(() -> onDownloadCancelled());
              } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onBackgroundError(e));
              } finally {
                clearActiveDownload(session, Thread.currentThread());
              }
            },
            "katago-install-tensorrt");
    activeWorkerThread = worker;
    worker.start();
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
    btnImportWeight.setEnabled(!busy);
    btnApplyWeight.setEnabled(!busy && getSelectedLocalWeight() != null);
    cmbLocalWeights.setEnabled(!busy && cmbLocalWeights.getItemCount() > 0);
    cmbRemoteWeights.setEnabled(!busy && cmbRemoteWeights.getItemCount() > 0);
    btnInstallNvidiaRuntime.setEnabled(!busy && canInstallNvidiaRuntime());
    btnInstallTensorRt.setEnabled(!busy && canInstallTensorRt());
    btnOptimizePerformance.setEnabled(!busy && canRunBenchmark());
    btnStopDownload.setEnabled(busy && activeDownloadSession != null);
    btnClose.setEnabled(true);

    progressPanel.setVisible(busy);
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
      btnApplyWeight.setEnabled(false);
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
    btnApplyWeight.setEnabled(
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
    return info.typeLabel + "  |  " + info.modelName;
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
    if (value == null || value.length() <= MAX_INFO_TEXT_LENGTH) {
      return value;
    }
    int head = Math.max(32, MAX_INFO_TEXT_LENGTH / 2 - 6);
    int tail = Math.max(36, MAX_INFO_TEXT_LENGTH - head - 5);
    return value.substring(0, head) + " ... " + value.substring(value.length() - tail);
  }

  private void updateSelectedRemoteWeightInfo() {
    RemoteWeightInfo info = getSelectedRemoteWeight();
    if (info == null) {
      lblRemoteDetailValue.setText(text("AutoSetup.remoteUnavailable"));
      lblRemoteDetailValue.setToolTipText(null);
      lblRemoteDetailValue.setForeground(ERROR_COLOR);
      btnDownloadWeight.setEnabled(false);
      btnStopDownload.setEnabled(false);
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
    String detailText = detail.toString();
    lblRemoteDetailValue.setText(compactInfoText(detailText));
    lblRemoteDetailValue.setToolTipText(detailText + " | " + info.downloadUrl);
    lblRemoteDetailValue.setForeground(matchesCurrentWeight(info) ? OK_COLOR : Color.DARK_GRAY);
    btnDownloadWeight.setEnabled(activeDownloadSession == null && activeWorkerThread == null);
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
    btnDownloadWeight.setEnabled(getSelectedRemoteWeight() != null);
    btnImportWeight.setEnabled(true);
    btnApplyWeight.setEnabled(getSelectedLocalWeight() != null);
    cmbLocalWeights.setEnabled(cmbLocalWeights.getItemCount() > 0);
    cmbRemoteWeights.setEnabled(cmbRemoteWeights.getItemCount() > 0);
    btnInstallNvidiaRuntime.setEnabled(canInstallNvidiaRuntime());
    btnInstallTensorRt.setEnabled(canInstallTensorRt());
    btnOptimizePerformance.setEnabled(canRunBenchmark());
    btnStopDownload.setEnabled(false);
    btnClose.setEnabled(true);
  }

  private String text(String key) {
    return Lizzie.resourceBundle.getString(key);
  }
}
