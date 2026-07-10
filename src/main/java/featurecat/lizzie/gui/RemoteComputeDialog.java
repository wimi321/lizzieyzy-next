package featurecat.lizzie.gui;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.RemoteComputeConfig;
import featurecat.lizzie.analysis.remote.ZhiziApiClient;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class RemoteComputeDialog extends JDialog {
  private static final String ZHIZI_OFFICIAL_URL = "http://www.zhizigo.cn/";
  private static final Color BG_TOP = new Color(251, 247, 238);
  private static final Color BG_BOTTOM = new Color(240, 248, 243);
  private static final Color CARD = new Color(255, 253, 248);
  private static final Color CARD_SOFT = new Color(248, 244, 234);
  private static final Color BORDER = new Color(221, 211, 190);
  private static final Color TEXT = new Color(43, 39, 31);
  private static final Color MUTED = new Color(122, 113, 96);
  private static final Color GREEN = new Color(43, 139, 90);
  private static final Color GOLD = new Color(193, 132, 42);
  private static final Color ERROR = new Color(190, 69, 56);

  private final ZhiziApiClient apiClient;
  private final CardLayout pageLayout = new CardLayout();
  private final JPanel pageCards = transparent(pageLayout);
  private final JButton zhiziTab = tabButton("智子云算力");
  private final JButton customTab = tabButton("自建算力");
  private final JLabel currentStatusLabel = new JLabel("当前使用：本地引擎");
  private final JLabel statusLabel = new JLabel("选择一种远程算力方式，需要时也可以随时切回本地引擎。");
  private final StatusDot statusDot = new StatusDot();

  private final JTextField accountField = new JTextField();
  private final JPasswordField passwordField = new JPasswordField();
  private final JTextField codeField = new JTextField();
  private final JTextField linkCodeField = new JTextField();
  private final JCheckBox rememberToken = new MemoryCheckBox("记住登录");
  private final JToggleButton showPasswordButton = new EyeToggleButton();
  private final JCheckBox rememberPassword = new MemoryCheckBox("记住密码");
  private final JComboBox<PresetItem> presetBox = new JComboBox<>();

  private final JButton passwordLoginButton = segmentButton("密码登录");
  private final JButton codeLoginButton = segmentButton("验证码登录");
  private final JButton sendCodeButton = secondaryButton("发送验证码");
  private final JButton loginButton = primaryButton("登录智子账号");
  private final JButton zhiziWebsiteButton = secondaryButton("打开智子官网");
  private final JButton useZhiziButton = primaryButton("一键启用智子云算力");
  private final JButton logoutButton = secondaryButton("更换账号");
  private final JButton localFromZhiziButton = secondaryButton("切回本地引擎");
  private final JButton importQrButton = secondaryButton("导入二维码");
  private final JButton useCustomButton = primaryButton("一键启用自建算力");
  private final JButton localFromCustomButton = secondaryButton("切回本地引擎");

  private JPanel loginFormPanel;
  private JPanel loggedInPanel;
  private JPanel passwordRowPanel;
  private JPanel passwordOptionsPanel;
  private JPanel codeRowPanel;
  private JLabel loggedInAccountLabel;
  private boolean codeLoginMode;
  private String activePage = RemoteComputeConfig.PROVIDER_ZHIZI;
  private char passwordEchoChar;
  private boolean busy;

  public RemoteComputeDialog(Frame owner) throws IOException {
    super(owner, "远程算力", false);
    apiClient = new ZhiziApiClient();
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setMinimumSize(new Dimension(1040, 660));
    setPreferredSize(new Dimension(1120, 700));
    setContentPane(buildContent());
    passwordEchoChar = passwordField.getEchoChar();
    initActions();
    loadState();
    pack();
    setLocationRelativeTo(owner);
  }

  private JPanel buildContent() {
    RemoteRootPanel root = new RemoteRootPanel();
    root.setLayout(new BorderLayout(28, 22));
    root.setBorder(new EmptyBorder(30, 34, 24, 34));
    root.add(buildHeader(), BorderLayout.NORTH);
    pageCards.add(buildZhiziPage(), RemoteComputeConfig.PROVIDER_ZHIZI);
    pageCards.add(buildCustomPage(), RemoteComputeConfig.PROVIDER_CUSTOM);
    root.add(pageCards, BorderLayout.CENTER);
    root.add(buildFooter(), BorderLayout.SOUTH);
    return root;
  }

  private JPanel buildHeader() {
    JPanel header = transparent(new BorderLayout(24, 0));
    JPanel titleBox = transparent();
    titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
    JLabel eyebrow = new JLabel("REMOTE COMPUTE");
    eyebrow.setForeground(GREEN);
    eyebrow.setFont(eyebrow.getFont().deriveFont(Font.BOLD, 12F));
    JLabel title = new JLabel("远程算力");
    title.setForeground(TEXT);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 36F));
    titleBox.add(eyebrow);
    titleBox.add(Box.createVerticalStrut(5));
    titleBox.add(title);
    header.add(titleBox, BorderLayout.CENTER);

    JPanel tabs = new RoundPanel(22, new Color(255, 253, 248, 230), BORDER);
    tabs.setLayout(new GridLayout(1, 2, 8, 0));
    tabs.setBorder(new EmptyBorder(6, 6, 6, 6));
    tabs.setPreferredSize(new Dimension(292, 58));
    tabs.add(zhiziTab);
    tabs.add(customTab);
    header.add(tabs, BorderLayout.EAST);
    return header;
  }

  private JPanel buildZhiziPage() {
    JPanel page = transparent(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridy = 0;
    gbc.fill = GridBagConstraints.BOTH;
    gbc.weighty = 1;
    gbc.insets = new Insets(0, 0, 0, 18);
    gbc.gridx = 0;
    gbc.weightx = 0.52;
    page.add(buildZhiziLoginCard(), gbc);
    gbc.gridx = 1;
    gbc.weightx = 0.48;
    gbc.insets = new Insets(0, 0, 0, 0);
    page.add(buildZhiziActionCard(), gbc);
    return page;
  }

  private JPanel buildZhiziLoginCard() {
    JPanel card = card("智子云算力", "手机号/邮箱登录后，就可以把云端 KataGo 设为当前引擎。");
    loginFormPanel = transparent();
    loginFormPanel.setLayout(new BoxLayout(loginFormPanel, BoxLayout.Y_AXIS));
    loginFormPanel.add(buildLoginSegments());
    loginFormPanel.add(Box.createVerticalStrut(18));
    loginFormPanel.add(fieldRow("账号", accountField, "手机号或邮箱"));
    loginFormPanel.add(Box.createVerticalStrut(12));
    passwordRowPanel = passwordFieldRow("密码", passwordField, "请输入密码");
    loginFormPanel.add(passwordRowPanel);
    loginFormPanel.add(Box.createVerticalStrut(12));
    codeRowPanel = buildCodeRow();
    loginFormPanel.add(codeRowPanel);
    loginFormPanel.add(Box.createVerticalStrut(12));
    passwordOptionsPanel = buildRememberOptionsRow();
    loginFormPanel.add(passwordOptionsPanel);
    loginFormPanel.add(Box.createVerticalStrut(18));
    loginFormPanel.add(fullWidth(loginButton, 54));
    card.add(loginFormPanel);

    loggedInPanel = new RoundPanel(24, new Color(242, 250, 245), new Color(193, 222, 203));
    loggedInPanel.setLayout(new BoxLayout(loggedInPanel, BoxLayout.Y_AXIS));
    loggedInPanel.setBorder(new EmptyBorder(24, 24, 22, 24));
    loggedInPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel loggedInTitle = new JLabel("账号已登录");
    loggedInTitle.setForeground(GREEN);
    loggedInTitle.setFont(loggedInTitle.getFont().deriveFont(Font.BOLD, 24F));
    loggedInTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
    loggedInPanel.add(loggedInTitle);
    loggedInPanel.add(Box.createVerticalStrut(10));
    loggedInAccountLabel = smallText("已保存本次登录状态。密码不会保存在本地。");
    loggedInAccountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    loggedInPanel.add(loggedInAccountLabel);
    loggedInPanel.add(Box.createVerticalStrut(22));
    loggedInPanel.add(fullWidth(logoutButton, 48));
    loggedInPanel.setVisible(false);
    card.add(loggedInPanel);
    return card;
  }

  private JPanel buildZhiziActionCard() {
    JPanel card = card("一键启用", "");
    card.add(planPanel());
    card.add(Box.createVerticalStrut(18));
    card.add(fullWidth(useZhiziButton, 58));
    card.add(Box.createVerticalStrut(12));
    card.add(fullWidth(localFromZhiziButton, 50));
    card.add(Box.createVerticalStrut(22));
    card.add(buildZhiziWebsiteCard());
    card.add(Box.createVerticalGlue());
    return card;
  }

  private JPanel buildCustomPage() {
    JPanel page = transparent(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridy = 0;
    gbc.fill = GridBagConstraints.BOTH;
    gbc.weighty = 1;
    gbc.insets = new Insets(0, 0, 0, 18);
    gbc.gridx = 0;
    gbc.weightx = 0.55;
    page.add(buildCustomConnectCard(), gbc);
    gbc.gridx = 1;
    gbc.weightx = 0.45;
    gbc.insets = new Insets(0, 0, 0, 0);
    page.add(buildCustomActionCard(), gbc);
    return page;
  }

  private JPanel buildCustomConnectCard() {
    JPanel card = card("自建算力", "粘贴 KaTrain 可用的 ws/wss 链接，或导入二维码。");
    card.add(fieldRow("链接", linkCodeField, "粘贴 ws:// 或 wss:// 开头的链接"));
    card.add(Box.createVerticalStrut(14));
    JPanel importRow = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
    importRow.setAlignmentX(Component.LEFT_ALIGNMENT);
    importRow.add(importQrButton);
    JLabel hint = new JLabel("支持导入二维码图片，自动读取里面的远程链接。");
    hint.setForeground(MUTED);
    hint.setFont(hint.getFont().deriveFont(Font.BOLD, 13F));
    importRow.add(hint);
    card.add(importRow);
    card.add(Box.createVerticalStrut(22));
    card.add(infoBox("兼容 KaTrain", "可直接粘贴 KaTrain 远程引擎的 KataGo Analysis WebSocket 链接，支持 ws 和 wss。"));
    card.add(Box.createVerticalGlue());
    return card;
  }

  private JPanel buildCustomActionCard() {
    JPanel card = card("一键启用", "");
    card.add(fullWidth(useCustomButton, 58));
    card.add(Box.createVerticalStrut(12));
    card.add(fullWidth(localFromCustomButton, 50));
    card.add(Box.createVerticalStrut(22));
    card.add(infoBox("说明", "启用后会把这个链接设为当前引擎；主界面、快速曲线和形势判断都会走自建算力。"));
    card.add(Box.createVerticalGlue());
    return card;
  }

  private JPanel buildFooter() {
    JPanel footer = new RoundPanel(20, new Color(255, 253, 248, 230), BORDER);
    footer.setLayout(new BorderLayout(14, 0));
    footer.setBorder(new EmptyBorder(12, 18, 12, 18));
    footer.add(statusDot, BorderLayout.WEST);
    currentStatusLabel.setForeground(TEXT);
    currentStatusLabel.setFont(currentStatusLabel.getFont().deriveFont(Font.BOLD, 15F));
    footer.add(currentStatusLabel, BorderLayout.CENTER);
    statusLabel.setForeground(MUTED);
    statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13F));
    footer.add(statusLabel, BorderLayout.EAST);
    return footer;
  }

  private JPanel buildLoginSegments() {
    JPanel panel = new RoundPanel(18, CARD_SOFT, BORDER);
    panel.setLayout(new GridLayout(1, 2, 5, 0));
    panel.setBorder(new EmptyBorder(4, 4, 4, 4));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
    panel.add(passwordLoginButton);
    panel.add(codeLoginButton);
    return panel;
  }

  private JPanel buildCodeRow() {
    JPanel row = transparent(new BorderLayout(10, 0));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
    row.add(fieldRow("验证码", codeField, "请输入验证码"), BorderLayout.CENTER);
    row.add(sendCodeButton, BorderLayout.EAST);
    return row;
  }

  private JPanel buildRememberOptionsRow() {
    JPanel row = transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
    rememberPassword.setToolTipText("仅保存在本机配置中。");
    rememberToken.setToolTipText("保存本次登录 token，下次打开不用重新登录。");
    row.add(rememberToken);
    row.add(Box.createHorizontalStrut(10));
    row.add(rememberPassword);
    return row;
  }

  private JPanel planPanel() {
    initPresetOptions();
    styleCombo(presetBox);
    JPanel panel = new RoundPanel(24, new Color(255, 250, 241), new Color(231, 213, 181));
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBorder(new EmptyBorder(18, 18, 18, 18));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel label = new JLabel("连接方式");
    label.setForeground(MUTED);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 13F));
    label.setAlignmentX(Component.LEFT_ALIGNMENT);
    panel.add(label);
    panel.add(Box.createVerticalStrut(8));
    panel.add(fullWidth(presetBox, 46));
    return panel;
  }

  private JPanel infoBox(String title, String body) {
    JPanel panel = new RoundPanel(22, new Color(248, 244, 234), new Color(231, 219, 197));
    panel.setLayout(new BorderLayout(14, 0));
    panel.setBorder(new EmptyBorder(16, 16, 16, 16));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel icon = new JLabel("!");
    icon.setHorizontalAlignment(JLabel.CENTER);
    icon.setForeground(GOLD);
    icon.setFont(icon.getFont().deriveFont(Font.BOLD, 26F));
    panel.add(icon, BorderLayout.WEST);
    JLabel text = smallText("<html><b>" + title + "</b><br>" + body + "</html>");
    panel.add(text, BorderLayout.CENTER);
    return panel;
  }

  private JPanel buildZhiziWebsiteCard() {
    JPanel panel = new RoundPanel(24, new Color(246, 252, 247), new Color(188, 222, 200));
    panel.setLayout(new BorderLayout(16, 0));
    panel.setBorder(new EmptyBorder(18, 18, 18, 18));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    panel.add(new WebsiteGlyph(), BorderLayout.WEST);

    JPanel copy = transparent();
    copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
    copy.setAlignmentX(Component.LEFT_ALIGNMENT);
    JLabel title = new JLabel("智子官网与充值");
    title.setForeground(TEXT);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 18F));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);
    copy.add(title);
    copy.add(Box.createVerticalStrut(6));
    JLabel body = smallText("<html>充值需前往智子官网下载 App，<br>在智子 App 内完成。</html>");
    body.setAlignmentX(Component.LEFT_ALIGNMENT);
    copy.add(body);
    copy.add(Box.createVerticalStrut(10));
    JLabel url = new JLabel("www.zhizigo.cn");
    url.setForeground(GREEN);
    url.setFont(url.getFont().deriveFont(Font.BOLD, 14F));
    url.setAlignmentX(Component.LEFT_ALIGNMENT);
    copy.add(url);
    panel.add(copy, BorderLayout.CENTER);

    JPanel action = transparent(new BorderLayout());
    action.setPreferredSize(new Dimension(138, 42));
    action.add(zhiziWebsiteButton, BorderLayout.CENTER);
    panel.add(action, BorderLayout.EAST);
    return panel;
  }

  private void initActions() {
    zhiziTab.addActionListener(e -> showPage(RemoteComputeConfig.PROVIDER_ZHIZI));
    customTab.addActionListener(e -> showPage(RemoteComputeConfig.PROVIDER_CUSTOM));
    passwordLoginButton.addActionListener(
        e -> {
          codeLoginMode = false;
          updateLoginMode();
        });
    codeLoginButton.addActionListener(
        e -> {
          codeLoginMode = true;
          updateLoginMode();
        });
    showPasswordButton.addActionListener(e -> updatePasswordEcho());
    rememberPassword.addActionListener(
        e -> {
          if (!rememberPassword.isSelected()) {
            clearSavedPasswordPreference();
          }
        });
    sendCodeButton.addActionListener(e -> sendCode());
    zhiziWebsiteButton.addActionListener(e -> openZhiziOfficialWebsite());
    presetBox.addActionListener(e -> updateZhiziActionButtonState());
    linkCodeField
        .getDocument()
        .addDocumentListener(newChangeListener(this::updateCustomActionButtonState));
    loginButton.addActionListener(
        e -> {
          if (codeLoginMode) {
            loginWithCode();
          } else {
            loginWithPassword();
          }
        });
    logoutButton.addActionListener(e -> logout());
    useZhiziButton.addActionListener(e -> useZhiziEngine());
    localFromZhiziButton.addActionListener(e -> switchToLocalProvider());
    importQrButton.addActionListener(e -> importQrCode());
    useCustomButton.addActionListener(e -> useCustomCompute());
    localFromCustomButton.addActionListener(e -> switchToLocalProvider());
  }

  private void initPresetOptions() {
    if (presetBox.getItemCount() > 0) {
      return;
    }
    presetBox.addItem(new PresetItem("VIP 包月（推荐）", RemoteComputeConfig.DEFAULT_ZHIZI_ARGS));
    presetBox.addItem(new PresetItem("按量 1x", RemoteComputeConfig.ON_DEMAND_1X_ZHIZI_ARGS));
    presetBox.addItem(new PresetItem("按量 3x", RemoteComputeConfig.FASTER_ZHIZI_ARGS));
    presetBox.addItem(new PresetItem("按量 6x", RemoteComputeConfig.FASTEST_ZHIZI_ARGS));
    presetBox.addItem(new PresetItem("按量 12x", RemoteComputeConfig.TWELVE_X_ZHIZI_ARGS));
    presetBox.addItem(new PresetItem("按量 24x", RemoteComputeConfig.TWENTY_FOUR_X_ZHIZI_ARGS));
    presetBox.addItem(new PresetItem("按量 1x · CUDA", RemoteComputeConfig.QUICK_START_ZHIZI_ARGS));
  }

  private void loadState() {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    rememberToken.setSelected(state.rememberZhiziToken);
    rememberPassword.setSelected(state.rememberZhiziPassword);
    accountField.setText(state.zhiziIdentifier == null ? "" : state.zhiziIdentifier);
    passwordField.setText(state.rememberZhiziPassword ? state.zhiziPassword : "");
    showPasswordButton.setSelected(false);
    updatePasswordEcho();
    linkCodeField.setText(state.customRemoteCode == null ? "" : state.customRemoteCode);
    selectPresetForArgs(state.zhiziArgs);
    showPage(
        RemoteComputeConfig.PROVIDER_CUSTOM.equals(state.provider)
            ? RemoteComputeConfig.PROVIDER_CUSTOM
            : RemoteComputeConfig.PROVIDER_ZHIZI);
    updateLoginMode();
    updateCurrentStatus();
    updateZhiziActionButtonState();
    updateCustomActionButtonState();
  }

  private void showPage(String page) {
    activePage = page;
    pageLayout.show(pageCards, page);
    zhiziTab.setSelected(RemoteComputeConfig.PROVIDER_ZHIZI.equals(page));
    customTab.setSelected(RemoteComputeConfig.PROVIDER_CUSTOM.equals(page));
    zhiziTab.repaint();
    customTab.repaint();
  }

  private void updateLoginMode() {
    boolean loggedIn = isZhiziLoggedIn();
    loginFormPanel.setVisible(!loggedIn);
    loggedInPanel.setVisible(loggedIn);
    passwordLoginButton.setSelected(!codeLoginMode);
    codeLoginButton.setSelected(codeLoginMode);
    passwordRowPanel.setVisible(!codeLoginMode);
    passwordOptionsPanel.setVisible(true);
    rememberPassword.setVisible(!codeLoginMode);
    codeRowPanel.setVisible(codeLoginMode);
    if (loggedIn) {
      RemoteComputeConfig.State state = RemoteComputeConfig.load();
      boolean passwordSaved =
          state.rememberZhiziPassword
              && state.zhiziPassword != null
              && !state.zhiziPassword.isEmpty();
      String account =
          state.zhiziIdentifier == null || state.zhiziIdentifier.trim().isEmpty()
              ? "账号已登录"
              : "账号：" + state.zhiziIdentifier;
      account += passwordSaved ? "，密码已保存在本机。" : "，密码没有保存在本地。";
      loggedInAccountLabel.setText(account);
    }
    revalidate();
    repaint();
  }

  private boolean isZhiziLoggedIn() {
    return !RemoteComputeConfig.load().zhiziAccountToken.trim().isEmpty();
  }

  private void selectPresetForArgs(String args) {
    for (int i = 0; i < presetBox.getItemCount(); i++) {
      if (presetBox.getItemAt(i).args.equals(args)) {
        presetBox.setSelectedIndex(i);
        return;
      }
    }
    presetBox.setSelectedIndex(0);
  }

  private String currentArgs() {
    Object selected = presetBox.getSelectedItem();
    return selected instanceof PresetItem
        ? ((PresetItem) selected).args
        : RemoteComputeConfig.DEFAULT_ZHIZI_ARGS;
  }

  private void loginWithPassword() {
    String identifier = accountField.getText().trim();
    String password = new String(passwordField.getPassword()).trim();
    if (identifier.isEmpty() || password.isEmpty()) {
      updateStatus("请输入账号和密码。", false);
      return;
    }
    runBackground(
        "正在登录智子云算力...",
        () -> apiClient.login(identifier, password),
        token -> onZhiziPasswordLoggedIn(identifier, password, token));
  }

  private void loginWithCode() {
    String identifier = accountField.getText().trim();
    String code = codeField.getText().trim();
    if (identifier.isEmpty() || code.isEmpty()) {
      updateStatus("请输入账号和验证码。", false);
      return;
    }
    runBackground(
        "正在用验证码登录...",
        () -> apiClient.fastLogin(identifier, code),
        token -> onZhiziLoggedIn(identifier, token));
  }

  private void onZhiziPasswordLoggedIn(String identifier, String password, String token) {
    RemoteComputeConfig.saveZhiziToken(
        token,
        rememberToken.isSelected(),
        currentArgs(),
        identifier,
        password,
        rememberPassword.isSelected());
    if (!rememberPassword.isSelected()) {
      passwordField.setText("");
    }
    codeField.setText("");
    updateLoginMode();
    updateCurrentStatus();
    updateStatus("登录成功，可以一键启用智子云算力。", true);
  }

  private void onZhiziLoggedIn(String identifier, String token) {
    RemoteComputeConfig.saveZhiziToken(
        token, rememberToken.isSelected(), currentArgs(), identifier);
    codeField.setText("");
    updateLoginMode();
    updateCurrentStatus();
    updateStatus("登录成功，可以一键启用智子云算力。", true);
  }

  private void sendCode() {
    String identifier = accountField.getText().trim();
    if (identifier.isEmpty()) {
      updateStatus("请先填写手机号或邮箱。", false);
      return;
    }
    runBackground(
        "正在发送验证码...",
        () -> {
          apiClient.sendCode(identifier);
          return "ok";
        },
        ignored -> updateStatus("验证码已发送，请查看手机或邮箱。", true));
  }

  private void openZhiziOfficialWebsite() {
    try {
      if (!Desktop.isDesktopSupported()
          || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        throw new UnsupportedOperationException("系统不支持自动打开浏览器。");
      }
      Desktop.getDesktop().browse(URI.create(ZHIZI_OFFICIAL_URL));
      updateStatus("已打开智子官网，请在官网下载 App 后完成充值。", true);
    } catch (Exception e) {
      Toolkit.getDefaultToolkit()
          .getSystemClipboard()
          .setContents(new StringSelection(ZHIZI_OFFICIAL_URL), null);
      updateStatus("未能自动打开浏览器，已复制智子官网链接。", false);
      JOptionPane.showMessageDialog(
          this,
          "未能自动打开浏览器，已复制智子官网链接：\n"
              + ZHIZI_OFFICIAL_URL
              + "\n\n请前往官网下载智子 App，并在智子 App 内完成充值。",
          "智子官网链接已复制",
          JOptionPane.INFORMATION_MESSAGE);
    }
  }

  private void useZhiziEngine() {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    if (state.zhiziAccountToken.trim().isEmpty()) {
      updateStatus("请先登录智子云算力。", false);
      showPage(RemoteComputeConfig.PROVIDER_ZHIZI);
      return;
    }
    state.provider = RemoteComputeConfig.PROVIDER_ZHIZI;
    state.zhiziArgs = currentArgs();
    state.rememberZhiziToken = rememberToken.isSelected();
    RemoteComputeConfig.save(state);
    int index = RemoteComputeConfig.createOrUpdateZhiziEngine(true);
    if (Lizzie.engineManager != null) {
      SwingUtilities.invokeLater(
          () -> {
            Lizzie.engineManager.switchEngine(index, true);
            warmQuickAnalysisAfterRemoteSwitch();
          });
    } else {
      warmQuickAnalysisAfterRemoteSwitch();
    }
    updateCurrentStatus();
    updateStatus("已启用智子云算力：" + RemoteComputeConfig.displayNameForZhiziArgs(currentArgs()), true);
  }

  private void warmQuickAnalysisAfterRemoteSwitch() {
    if (Lizzie.frame != null) {
      Lizzie.frame.preloadQuickAnalysisEngineForKifuBrowsing();
    }
  }

  private void logout() {
    RemoteComputeConfig.clearZhiziToken();
    passwordField.setText("");
    rememberPassword.setSelected(false);
    showPasswordButton.setSelected(false);
    updatePasswordEcho();
    updateLoginMode();
    updateCurrentStatus();
    updateStatus("已退出智子云算力登录。", true);
  }

  private void updatePasswordEcho() {
    boolean visible = showPasswordButton.isSelected();
    passwordField.setEchoChar(visible ? (char) 0 : passwordEchoChar);
    showPasswordButton.setToolTipText(visible ? "隐藏密码" : "显示密码");
  }

  private void clearSavedPasswordPreference() {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    if (!state.rememberZhiziPassword
        && (state.zhiziPassword == null || state.zhiziPassword.isEmpty())) {
      return;
    }
    state.rememberZhiziPassword = false;
    state.zhiziPassword = "";
    RemoteComputeConfig.save(state);
  }

  private void importQrCode() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("选择自建算力二维码图片");
    int result = chooser.showOpenDialog(this);
    if (result != JFileChooser.APPROVE_OPTION) {
      return;
    }
    File file = chooser.getSelectedFile();
    try {
      BufferedImage image = ImageIO.read(file);
      if (image == null) {
        throw new IllegalArgumentException("不是有效图片。");
      }
      BinaryBitmap bitmap =
          new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
      Result decoded = new MultiFormatReader().decode(bitmap);
      linkCodeField.setText(decoded.getText());
      updateStatus("已读取二维码中的远程链接。", true);
    } catch (Exception e) {
      updateStatus("没有识别到二维码，请复制 ws/wss 链接后手动粘贴。", false);
      JOptionPane.showMessageDialog(
          this,
          "没有识别到二维码，请复制 ws/wss 链接后手动粘贴。\n\n" + e.getLocalizedMessage(),
          "二维码识别失败",
          JOptionPane.WARNING_MESSAGE);
    }
  }

  private void useCustomCompute() {
    String code = RemoteComputeConfig.normalizeCustomWebSocketUrl(linkCodeField.getText());
    if (code.isEmpty()) {
      updateStatus("请先输入 ws:// 或 wss:// 链接，或导入二维码。", false);
      return;
    }
    if (!RemoteComputeConfig.isCustomWebSocketUrl(code)) {
      updateStatus("自建算力链接需要以 ws:// 或 wss:// 开头。", false);
      JOptionPane.showMessageDialog(
          this,
          "请粘贴 KaTrain 兼容的 ws:// 或 wss:// 远程引擎链接。",
          "自建算力链接无效",
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    state.provider = RemoteComputeConfig.PROVIDER_CUSTOM;
    state.customRemoteCode = code;
    RemoteComputeConfig.save(state);
    linkCodeField.setText(code);
    int index = RemoteComputeConfig.createOrUpdateCustomWebSocketEngine(true);
    if (Lizzie.engineManager != null) {
      SwingUtilities.invokeLater(
          () -> {
            Lizzie.engineManager.switchEngine(index, true);
            warmQuickAnalysisAfterRemoteSwitch();
          });
    } else {
      warmQuickAnalysisAfterRemoteSwitch();
    }
    showPage(RemoteComputeConfig.PROVIDER_CUSTOM);
    updateCurrentStatus();
    updateStatus("已启用自建算力：" + RemoteComputeConfig.displayNameForCustomWebSocketUrl(code), true);
  }

  private void switchToLocalProvider() {
    int localEngineIndex = RemoteComputeConfig.saveLocalProviderAndDefaultEngine();
    if (localEngineIndex >= 0 && Lizzie.engineManager != null) {
      SwingUtilities.invokeLater(() -> Lizzie.engineManager.switchEngine(localEngineIndex, true));
      updateStatus("已切回本地引擎，下次启动也会继续使用本地引擎。", true);
    } else {
      updateStatus("暂未找到本地引擎，请先在引擎设置里添加本机 KataGo。", false);
    }
    updateCurrentStatus();
  }

  private void updateCurrentStatus() {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    if (RemoteComputeConfig.PROVIDER_ZHIZI.equals(state.provider)) {
      String fullName = RemoteComputeConfig.displayNameForZhiziArgs(state.zhiziArgs);
      currentStatusLabel.setText("当前使用：智子云算力");
      currentStatusLabel.setToolTipText("当前使用：" + fullName);
      statusDot.setColor(GREEN);
    } else if (RemoteComputeConfig.PROVIDER_CUSTOM.equals(state.provider)) {
      String fullName = RemoteComputeConfig.displayNameForCustomWebSocketUrl(state.customRemoteCode);
      currentStatusLabel.setText("当前使用：自建算力");
      currentStatusLabel.setToolTipText("当前使用：" + fullName);
      statusDot.setColor(GREEN);
    } else {
      currentStatusLabel.setText("当前使用：本地引擎");
      currentStatusLabel.setToolTipText("当前使用：本地引擎");
      statusDot.setColor(GREEN);
    }
    updateZhiziActionButtonState();
    updateCustomActionButtonState();
  }

  private void updateZhiziActionButtonState() {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    boolean loggedIn = state.zhiziAccountToken != null && !state.zhiziAccountToken.trim().isEmpty();
    boolean usingZhizi = RemoteComputeConfig.PROVIDER_ZHIZI.equals(state.provider);
    boolean sameArgs = currentArgs().equals(state.zhiziArgs);
    if (!loggedIn) {
      useZhiziButton.setText("登录后启用智子云算力");
      useZhiziButton.setToolTipText("请先登录智子账号，再启用云端算力。");
      useZhiziButton.setEnabled(false);
    } else if (usingZhizi && sameArgs) {
      useZhiziButton.setText("已启用智子算力");
      useZhiziButton.setToolTipText("当前连接方式已经生效；更改连接方式后可重新启用。");
      useZhiziButton.setEnabled(false);
    } else if (usingZhizi) {
      useZhiziButton.setText("更换智子云算力连接方式");
      useZhiziButton.setToolTipText("将当前引擎切换到新选择的智子连接方式。");
      useZhiziButton.setEnabled(!busy);
    } else {
      useZhiziButton.setText("一键启用智子云算力");
      useZhiziButton.setToolTipText("把智子云端 KataGo 设置为当前引擎。");
      useZhiziButton.setEnabled(!busy);
    }
  }

  private void updateCustomActionButtonState() {
    RemoteComputeConfig.State state = RemoteComputeConfig.load();
    String code = RemoteComputeConfig.normalizeCustomWebSocketUrl(linkCodeField.getText());
    String savedCode = RemoteComputeConfig.normalizeCustomWebSocketUrl(state.customRemoteCode);
    boolean validCode = RemoteComputeConfig.isCustomWebSocketUrl(code);
    boolean usingCustom = RemoteComputeConfig.PROVIDER_CUSTOM.equals(state.provider);
    boolean sameCode = usingCustom && validCode && code.equals(savedCode);
    if (code.isEmpty()) {
      useCustomButton.setText("输入链接后启用自建算力");
      useCustomButton.setToolTipText("请先粘贴 ws:// 或 wss:// 远程算力链接，或导入二维码。");
      useCustomButton.setEnabled(false);
    } else if (!validCode) {
      useCustomButton.setText(usingCustom ? "输入有效链接后更换自建算力" : "输入有效链接后启用自建算力");
      useCustomButton.setToolTipText("自建算力链接需要以 ws:// 或 wss:// 开头。");
      useCustomButton.setEnabled(false);
    } else if (sameCode) {
      useCustomButton.setText("已启用自建算力");
      useCustomButton.setToolTipText("当前自建算力链接已经生效；更改链接后可重新启用。");
      useCustomButton.setEnabled(false);
    } else if (usingCustom) {
      useCustomButton.setText("更换自建算力连接方式");
      useCustomButton.setToolTipText("将当前引擎切换到新输入的自建算力链接。");
      useCustomButton.setEnabled(!busy);
    } else {
      useCustomButton.setText("一键启用自建算力");
      useCustomButton.setToolTipText("把这个远程链接设置为当前引擎。");
      useCustomButton.setEnabled(!busy);
    }
  }

  private void updateStatus(String message, boolean ok) {
    statusLabel.setText(message == null ? "" : message);
    statusLabel.setForeground(ok ? new Color(77, 113, 82) : ERROR);
  }

  private <T> void runBackground(String message, BackgroundTask<T> task, SuccessHandler<T> success) {
    setBusy(true);
    updateStatus(message, true);
    new SwingWorker<T, Void>() {
      @Override
      protected T doInBackground() throws Exception {
        return task.run();
      }

      @Override
      protected void done() {
        setBusy(false);
        try {
          success.accept(get());
        } catch (Exception e) {
          Throwable cause = e.getCause() == null ? e : e.getCause();
          String message =
              cause.getLocalizedMessage() == null ? cause.toString() : cause.getLocalizedMessage();
          message = RemoteComputeConfig.friendlyZhiziErrorMessage(message, currentArgs());
          updateStatus(message, false);
          JOptionPane.showMessageDialog(
              RemoteComputeDialog.this, message, "远程算力连接失败", JOptionPane.WARNING_MESSAGE);
        }
      }
    }.execute();
  }

  private void setBusy(boolean busy) {
    this.busy = busy;
    accountField.setEnabled(!busy);
    passwordField.setEnabled(!busy);
    codeField.setEnabled(!busy);
    linkCodeField.setEnabled(!busy);
    rememberToken.setEnabled(!busy);
    showPasswordButton.setEnabled(!busy && !codeLoginMode);
    rememberPassword.setEnabled(!busy && !codeLoginMode);
    presetBox.setEnabled(!busy);
    passwordLoginButton.setEnabled(!busy);
    codeLoginButton.setEnabled(!busy);
    sendCodeButton.setEnabled(!busy);
    zhiziWebsiteButton.setEnabled(!busy);
    loginButton.setEnabled(!busy);
    updateZhiziActionButtonState();
    logoutButton.setEnabled(!busy);
    localFromZhiziButton.setEnabled(!busy);
    importQrButton.setEnabled(!busy);
    updateCustomActionButtonState();
    localFromCustomButton.setEnabled(!busy);
  }

  private static DocumentListener newChangeListener(Runnable action) {
    return new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        action.run();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        action.run();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        action.run();
      }
    };
  }

  private JPanel card(String title, String subtitle) {
    JPanel card = new RoundPanel(30, CARD, BORDER);
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
    card.setBorder(new EmptyBorder(28, 30, 28, 30));
    JLabel titleLabel = new JLabel(title);
    titleLabel.setForeground(TEXT);
    titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 28F));
    titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
    card.add(titleLabel);
    if (subtitle != null && !subtitle.trim().isEmpty()) {
      card.add(Box.createVerticalStrut(8));
      JLabel subtitleLabel = smallText("<html>" + subtitle + "</html>");
      subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
      card.add(subtitleLabel);
      card.add(Box.createVerticalStrut(24));
    } else {
      card.add(Box.createVerticalStrut(20));
    }
    return card;
  }

  private JPanel fieldRow(String label, JTextField field, String placeholder) {
    styleTextField(field);
    field.putClientProperty("placeholder", placeholder);
    JPanel row = transparent(new BorderLayout(12, 0));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
    JLabel labelView = new JLabel(label);
    labelView.setForeground(MUTED);
    labelView.setFont(labelView.getFont().deriveFont(Font.BOLD, 14F));
    labelView.setPreferredSize(new Dimension(58, 44));
    row.add(labelView, BorderLayout.WEST);
    row.add(field, BorderLayout.CENTER);
    return row;
  }

  private JPanel passwordFieldRow(String label, JPasswordField field, String placeholder) {
    field.putClientProperty("placeholder", placeholder);
    field.setForeground(TEXT);
    field.setCaretColor(TEXT);
    field.setBackground(new Color(255, 255, 252));
    field.setOpaque(false);
    field.setBorder(new EmptyBorder(10, 0, 10, 8));
    field.setFont(field.getFont().deriveFont(Font.BOLD, 14F));

    JPanel inputShell = new RoundPanel(16, new Color(255, 255, 252), BORDER);
    inputShell.setLayout(new BorderLayout(4, 0));
    inputShell.setBorder(new EmptyBorder(0, 12, 0, 6));
    inputShell.add(field, BorderLayout.CENTER);
    inputShell.add(showPasswordButton, BorderLayout.EAST);

    JPanel row = transparent(new BorderLayout(12, 0));
    row.setAlignmentX(Component.LEFT_ALIGNMENT);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
    JLabel labelView = new JLabel(label);
    labelView.setForeground(MUTED);
    labelView.setFont(labelView.getFont().deriveFont(Font.BOLD, 14F));
    labelView.setPreferredSize(new Dimension(58, 44));
    row.add(labelView, BorderLayout.WEST);
    row.add(inputShell, BorderLayout.CENTER);
    return row;
  }

  private void styleTextField(JTextField field) {
    field.setForeground(TEXT);
    field.setCaretColor(TEXT);
    field.setBackground(new Color(255, 255, 252));
    field.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER), new EmptyBorder(10, 12, 10, 12)));
    field.setFont(field.getFont().deriveFont(Font.BOLD, 14F));
  }

  private void styleCombo(JComboBox<?> comboBox) {
    comboBox.setOpaque(true);
    comboBox.setForeground(TEXT);
    comboBox.setBackground(new Color(255, 255, 252));
    comboBox.setBorder(BorderFactory.createLineBorder(BORDER));
    comboBox.setFont(comboBox.getFont().deriveFont(Font.BOLD, 14F));
    comboBox.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            Component component =
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            component.setForeground(TEXT);
            component.setBackground(isSelected ? new Color(228, 241, 232) : new Color(255, 255, 252));
            if (component instanceof JComponent) {
              ((JComponent) component).setBorder(new EmptyBorder(8, 10, 8, 10));
            }
            return component;
          }
        });
  }

  private JLabel smallText(String text) {
    JLabel label = new JLabel(text);
    label.setForeground(MUTED);
    label.setFont(label.getFont().deriveFont(Font.BOLD, 14F));
    return label;
  }

  private JButton primaryButton(String text) {
    return new RoundedButton(text, GREEN, new Color(34, 121, 77), Color.WHITE, 18);
  }

  private JButton secondaryButton(String text) {
    return new RoundedButton(text, new Color(255, 253, 248), BORDER, TEXT, 16);
  }

  private static JButton tabButton(String text) {
    JButton button = new TabButton(text);
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return button;
  }

  private static JButton segmentButton(String text) {
    JButton button = new TabButton(text);
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.setFont(button.getFont().deriveFont(Font.BOLD, 13F));
    return button;
  }

  private Component fullWidth(JComponent component, int height) {
    JPanel wrapper = transparent(new BorderLayout());
    wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
    wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    wrapper.add(component, BorderLayout.CENTER);
    return wrapper;
  }

  private static JPanel transparent() {
    return transparent(new FlowLayout());
  }

  private static JPanel transparent(java.awt.LayoutManager layout) {
    JPanel panel = new JPanel(layout);
    panel.setOpaque(false);
    return panel;
  }

  private interface BackgroundTask<T> {
    T run() throws Exception;
  }

  private interface SuccessHandler<T> {
    void accept(T value);
  }

  private static final class PresetItem {
    final String label;
    final String args;

    PresetItem(String label, String args) {
      this.label = label;
      this.args = args;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private static final class RemoteRootPanel extends JPanel {
    RemoteRootPanel() {
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setPaint(new GradientPaint(0, 0, BG_TOP, 0, getHeight(), BG_BOTTOM));
      g2.fillRect(0, 0, getWidth(), getHeight());
      g2.setColor(new Color(205, 184, 138, 22));
      for (int x = 20; x < getWidth(); x += 48) {
        g2.drawLine(x, 0, x, getHeight());
      }
      for (int y = 20; y < getHeight(); y += 48) {
        g2.drawLine(0, y, getWidth(), y);
      }
      g2.setColor(new Color(56, 140, 103, 22));
      g2.fillOval(-130, getHeight() - 190, 360, 260);
      g2.setColor(new Color(193, 132, 42, 18));
      g2.fillOval(getWidth() - 300, -160, 460, 340);
      g2.dispose();
      super.paintComponent(g);
    }
  }

  private static final class RoundPanel extends JPanel {
    private final int arc;
    private final Color fill;
    private final Color border;

    RoundPanel(int arc, Color fill, Color border) {
      this.arc = arc;
      this.fill = fill;
      this.border = border;
      setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setColor(fill);
      g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      g2.setColor(border);
      g2.setStroke(new BasicStroke(1.2F));
      g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      g2.dispose();
      super.paintComponent(g);
    }
  }

  private static class RoundedButton extends JButton {
    private final Color fill;
    private final Color border;
    private final int arc;

    RoundedButton(String text, Color fill, Color border, Color foreground, int arc) {
      super(text);
      this.fill = fill;
      this.border = border;
      this.arc = arc;
      setForeground(foreground);
      setBorder(new EmptyBorder(11, 20, 11, 20));
      setBorderPainted(false);
      setContentAreaFilled(false);
      setFocusPainted(false);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setFont(getFont().deriveFont(Font.BOLD, 15F));
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      Color paint = isEnabled() ? fill : new Color(224, 216, 202);
      if (isEnabled() && getModel().isPressed()) {
        paint = paint.darker();
      } else if (isEnabled() && getModel().isRollover()) {
        paint =
            new Color(
                Math.min(255, paint.getRed() + 8),
                Math.min(255, paint.getGreen() + 8),
                Math.min(255, paint.getBlue() + 8));
      }
      g2.setColor(paint);
      g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      g2.setColor(isEnabled() ? border : new Color(204, 195, 178));
      g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      g2.dispose();
      super.paintComponent(g);
    }
  }

  private static final class TabButton extends JButton {
    TabButton(String text) {
      super(text);
      setBorder(new EmptyBorder(10, 16, 10, 16));
      setBorderPainted(false);
      setContentAreaFilled(false);
      setFocusPainted(false);
      setOpaque(false);
      setForeground(TEXT);
      setFont(getFont().deriveFont(Font.BOLD, 15F));
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      if (isSelected()) {
        g2.setColor(new Color(255, 253, 248));
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
        g2.setColor(new Color(228, 204, 163));
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
      }
      g2.dispose();
      super.paintComponent(g);
    }
  }

  private static final class EyeToggleButton extends JToggleButton {
    EyeToggleButton() {
      setBorder(new EmptyBorder(0, 0, 0, 0));
      setBorderPainted(false);
      setContentAreaFilled(false);
      setFocusPainted(false);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setRolloverEnabled(true);
      setToolTipText("显示密码");
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(36, 36);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      boolean active = isSelected();
      boolean hover = getModel().isRollover();
      Color accent = active ? GREEN : MUTED;
      if (hover || active) {
        Color fill = active ? new Color(222, 242, 229) : new Color(244, 238, 226);
        g2.setColor(fill);
        g2.fillRoundRect(3, 3, getWidth() - 6, getHeight() - 6, 16, 16);
      }
      int centerX = getWidth() / 2;
      int centerY = getHeight() / 2;
      g2.setStroke(new BasicStroke(1.8F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.setColor(accent);
      g2.drawArc(centerX - 12, centerY - 8, 24, 16, 20, 140);
      g2.drawArc(centerX - 12, centerY - 8, 24, 16, 200, 140);
      g2.fillOval(centerX - 4, centerY - 4, 8, 8);
      if (!active) {
        g2.setStroke(new BasicStroke(2.1F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(centerX - 11, centerY + 10, centerX + 11, centerY - 10);
      }
      g2.dispose();
    }
  }

  private static final class MemoryCheckBox extends JCheckBox {
    MemoryCheckBox(String text) {
      super(text);
      setBorder(new EmptyBorder(8, 13, 8, 14));
      setBorderPainted(false);
      setContentAreaFilled(false);
      setFocusPainted(false);
      setOpaque(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setRolloverEnabled(true);
      setFont(getFont().deriveFont(Font.BOLD, 13F));
    }

    @Override
    public Dimension getPreferredSize() {
      Font font = getFont();
      int textWidth = getFontMetrics(font).stringWidth(getText());
      return new Dimension(textWidth + 46, 36);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      boolean selected = isSelected();
      boolean hover = getModel().isRollover();
      Color fill =
          selected
              ? new Color(226, 243, 232)
              : hover ? new Color(249, 245, 236) : new Color(255, 253, 248);
      Color border = selected ? new Color(140, 191, 159) : BORDER;
      Color text = isEnabled() ? (selected ? new Color(36, 107, 72) : MUTED) : new Color(166, 157, 142);
      g2.setColor(fill);
      g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 18, 18);
      g2.setColor(border);
      g2.setStroke(new BasicStroke(1.15F));
      g2.drawRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 18, 18);

      int dotSize = 10;
      int dotX = 14;
      int dotY = (getHeight() - dotSize) / 2;
      if (selected) {
        g2.setColor(GREEN);
        g2.fillOval(dotX, dotY, dotSize, dotSize);
      } else {
        g2.setColor(new Color(174, 164, 145));
        g2.drawOval(dotX, dotY, dotSize, dotSize);
      }

      FontMetrics metrics = g2.getFontMetrics(getFont());
      int textX = dotX + dotSize + 10;
      int textY = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
      g2.setFont(getFont());
      g2.setColor(text);
      g2.drawString(getText(), textX, textY);
      g2.dispose();
    }
  }

  private static final class WebsiteGlyph extends JComponent {
    @Override
    public Dimension getPreferredSize() {
      return new Dimension(50, 50);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int size = Math.min(getWidth(), getHeight()) - 4;
      int x = (getWidth() - size) / 2;
      int y = (getHeight() - size) / 2;
      g2.setColor(new Color(43, 139, 90, 34));
      g2.fillOval(x, y, size, size);
      g2.setColor(GREEN);
      g2.setStroke(new BasicStroke(2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.drawOval(x + 7, y + 7, size - 14, size - 14);
      g2.drawArc(x + 14, y + 7, size - 28, size - 14, 90, 180);
      g2.drawArc(x + 14, y + 7, size - 28, size - 14, -90, 180);
      g2.drawLine(x + 10, y + size / 2, x + size - 10, y + size / 2);
      g2.setColor(GOLD);
      g2.fillOval(x + size - 16, y + 8, 9, 9);
      g2.dispose();
    }
  }

  private static final class StatusDot extends JComponent {
    private Color color = GREEN;

    void setColor(Color color) {
      this.color = color;
      repaint();
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(18, 18);
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int size = Math.min(getWidth(), getHeight()) - 4;
      g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 45));
      g2.fillOval((getWidth() - size) / 2 - 3, (getHeight() - size) / 2 - 3, size + 6, size + 6);
      g2.setColor(color);
      g2.fillOval((getWidth() - size) / 2, (getHeight() - size) / 2, size, size);
      g2.dispose();
    }
  }
}
