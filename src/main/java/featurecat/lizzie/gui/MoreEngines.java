package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationProbe;
import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationSchema;
import featurecat.lizzie.util.Utils;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import org.json.JSONException;
import org.json.JSONObject;

public class MoreEngines extends JPanel {
  public static Config config;
  public TableModel dataModel;
  PanelWithToolTips tablepanel;
  PanelWithToolTips selectpanel;
  JScrollPane scrollpane;
  public static JTable table;
  Font headFont;
  Font winrateFont;
  static boolean needUpdateEngine = false;
  static JDialog engjf;
  // Timer timer;
  int sortnum = 3;
  public static int selectedorder = -1;
  boolean issorted = false;
  JTextArea command;
  JFontTextField txtName;
  JFontLabel engineName;
  JFontTextField txtInitialCommand;
  JFontTextField txtWidth;
  JFontTextField txtHeight;
  JFontTextField txtKomi;
  JFontButton exit;
  JFontButton scan;
  JFontButton delete;
  JFontButton add;
  JFontButton save;
  JFontButton cancel;
  JFontButton moveUp;
  JFontButton moveDown;
  JFontButton moveUp5;
  JFontButton moveDown5;
  JFontButton moveFirst;
  JFontButton moveLast;
  JFontButton gtpConfig;
  JFontCheckBox preload;
  JFontCheckBox chkDefault;
  JFontRadioButton rdoDefault;
  JFontRadioButton rdoLast;
  JFontRadioButton rdoMannul;
  JFontRadioButton rdoNone;
  int curIndex = -1;
  String keyGenPath = "";
  JFontCheckBox chkRemoteEngine;
  JFontRadioButton rdoUsePassword;
  JFontRadioButton rdoKeyGen;
  JFontTextField txtIP;
  JFontTextField txtPort;
  JFontTextField txtUserName;
  JPasswordField txtPassword;
  JFontButton scanKeygen;

  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;

  public MoreEngines() {
    setLayout((LayoutManager) null);
    this.dataModel = getTableModel();
    table = new JTable(this.dataModel);
    this.winrateFont = new Font(Config.sysDefaultFontName, 0, Math.max(Config.frameFontSize, 14));
    this.headFont = new Font(Config.sysDefaultFontName, 0, Math.max(Config.frameFontSize, 13));
    table.getTableHeader().setFont(this.headFont);
    table.setFont(this.winrateFont);
    AccessibilitySupport.named(
        table,
        this.resourceBundle.getString("MoreEngines.title"),
        this.resourceBundle.getString("MoreEngines.engineName"));
    table.getTableHeader().setReorderingAllowed(false);
    table.getTableHeader().setResizingAllowed(false);
    TableCellRenderer tcr = new ColorTableCellRenderer();
    table.setDefaultRenderer(Object.class, tcr);
    tablepanel = new PanelWithToolTips();
    tablepanel.setLayout(new BorderLayout());
    tablepanel.setBounds(0, 385, 885, 380);
    selectpanel = new PanelWithToolTips();
    selectpanel.setLayout((LayoutManager) null);
    add(this.tablepanel, "South");
    this.selectpanel.setBounds(0, 0, 900, 385);
    add(this.selectpanel, "North");
    this.scrollpane = new JScrollPane(table);
    this.tablepanel.add(this.scrollpane);
    table.setSelectionMode(0);
    table.setFillsViewportHeight(true);
    table.getColumnModel().getColumn(0).setPreferredWidth(30);
    table.getColumnModel().getColumn(1).setPreferredWidth(235);
    table.getColumnModel().getColumn(2).setPreferredWidth(305);
    table.getColumnModel().getColumn(3).setPreferredWidth(40);
    table.getColumnModel().getColumn(4).setPreferredWidth(20);
    table.getColumnModel().getColumn(5).setPreferredWidth(20);
    table.getColumnModel().getColumn(6).setPreferredWidth(30);
    table.getColumnModel().getColumn(7).setPreferredWidth(30);
    table.getColumnModel().getColumn(8).setPreferredWidth(30);
    table.setRowHeight(Config.menuHeight);
    table.getTableHeader().setFont(this.headFont);
    table
        .getTableHeader()
        .setPreferredSize(
            new Dimension(table.getColumnModel().getTotalColumnWidth(), Config.menuHeight));
    table.setFont(this.winrateFont);
    this.engineName = new JFontLabel(this.resourceBundle.getString("MoreEngines.engineName"));
    this.engineName.setForeground(Color.BLUE);
    this.engineName.setFont(new Font(Lizzie.config.uiFontName, 0, 14));
    JFontLabel lblName = new JFontLabel(this.resourceBundle.getString("MoreEngines.lblName"));
    this.txtName = new JFontTextField();
    this.txtName.setFont(new Font(Config.sysDefaultFontName, 0, Config.frameFontSize));
    this.txtKomi = new JFontTextField();
    JFontLabel lblInitialCommand =
        new JFontLabel(resourceBundle.getString("MoreEngines.lblInitialCommand"));
    txtInitialCommand = new JFontTextField();
    txtInitialCommand.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
    txtInitialCommand.setForeground(Color.GRAY);
    txtInitialCommand.setText(resourceBundle.getString("MoreEngines.initialCommandHint"));
    txtInitialCommand.addFocusListener(
        new FocusListener() {
          @Override
          public void focusGained(FocusEvent e) {
            if (resourceBundle
                .getString("MoreEngines.initialCommandHint")
                .equalsIgnoreCase(txtInitialCommand.getText())) {
              txtInitialCommand.setForeground(Color.BLACK);
              txtInitialCommand.setText("");
            }
          }

          @Override
          public void focusLost(FocusEvent e) {
            if ("".equals(txtInitialCommand.getText())) {
              txtInitialCommand.setForeground(Color.GRAY);
              txtInitialCommand.setText(resourceBundle.getString("MoreEngines.initialCommandHint"));
            }
          }
        });
    this.command = new JFontTextArea(5, 80);
    command.setBackground(this.getBackground());

    this.command.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
    this.txtName.setBorder(BorderFactory.createLineBorder(Color.BLACK, 1));
    JFontLabel lblCommand = new JFontLabel(this.resourceBundle.getString("MoreEngines.lblCommand"));
    this.preload = new JFontCheckBox(this.resourceBundle.getString("MoreEngines.lblpreload"));
    JFontLabel lblWidth = new JFontLabel(this.resourceBundle.getString("MoreEngines.lblWidth"));
    JFontLabel lblHeight = new JFontLabel(this.resourceBundle.getString("MoreEngines.lblHeight"));
    JFontLabel lblKomi = new JFontLabel(this.resourceBundle.getString("MoreEngines.lblKomi"));
    this.txtWidth = new JFontTextField();
    this.txtHeight = new JFontTextField();
    this.add = new JFontButton(this.resourceBundle.getString("MoreEngines.add"));
    this.save = new JFontButton(this.resourceBundle.getString("MoreEngines.save"));
    this.cancel = new JFontButton(this.resourceBundle.getString("MoreEngines.cancel"));
    this.exit = new JFontButton(this.resourceBundle.getString("MoreEngines.exit"));
    this.delete = new JFontButton(this.resourceBundle.getString("MoreEngines.delete"));
    this.scan = new JFontButton(this.resourceBundle.getString("MoreEngines.scan"));
    this.moveUp = new JFontButton(this.resourceBundle.getString("MoreEngines.moveUp"));
    this.moveDown = new JFontButton(this.resourceBundle.getString("MoreEngines.moveDown"));
    this.moveUp5 = new JFontButton(this.resourceBundle.getString("MoreEngines.moveUp5"));
    this.moveDown5 = new JFontButton(this.resourceBundle.getString("MoreEngines.moveDown5"));
    this.moveFirst = new JFontButton(this.resourceBundle.getString("MoreEngines.moveFirst"));
    this.moveLast = new JFontButton(this.resourceBundle.getString("MoreEngines.moveLast"));
    this.gtpConfig = new JFontButton(this.resourceBundle.getString("MoreEngines.gtpConfig"));
    this.moveUp.setMargin(new Insets(0, 0, 0, 0));
    this.moveDown.setMargin(new Insets(0, 0, 0, 0));
    this.moveUp5.setMargin(new Insets(0, 0, 0, 0));
    this.moveDown5.setMargin(new Insets(0, 0, 0, 0));
    this.moveFirst.setMargin(new Insets(0, 0, 0, 0));
    this.moveLast.setMargin(new Insets(0, 0, 0, 0));
    this.gtpConfig.setMargin(new Insets(0, 4, 0, 4));
    this.scan.setMargin(new Insets(0, 0, 0, 0));
    this.add.setMargin(new Insets(0, 0, 0, 0));
    this.save.setMargin(new Insets(0, 0, 0, 0));
    this.cancel.setMargin(new Insets(0, 0, 0, 0));
    this.exit.setMargin(new Insets(0, 0, 0, 0));
    this.delete.setMargin(new Insets(0, 0, 0, 0));
    this.chkDefault = new JFontCheckBox(this.resourceBundle.getString("MoreEngines.lbldefault"));
    JFontLabel lblchooseStart =
        new JFontLabel(this.resourceBundle.getString("ChooseMoreEngine.lblchooseStart"));
    this.rdoDefault =
        new JFontRadioButton(this.resourceBundle.getString("MoreEngines.lblrdoDefault"));
    this.rdoLast =
        new JFontRadioButton(this.resourceBundle.getString("ChooseMoreEngine.lblrdoLast"));
    this.rdoMannul =
        new JFontRadioButton(this.resourceBundle.getString("ChooseMoreEngine.lblrdoMannul"));
    rdoNone = new JFontRadioButton(resourceBundle.getString("ChooseMoreEngine.lblrdoNone"));
    this.chkRemoteEngine =
        new JFontCheckBox(this.resourceBundle.getString("MoreEngines.chkRemoteEngine"));
    this.chkRemoteEngine.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (chkRemoteEngine.isSelected()) {
              txtIP.setEnabled(true);
              txtPort.setEnabled(true);
              rdoUsePassword.setEnabled(true);
              rdoKeyGen.setEnabled(true);
              txtUserName.setEnabled(true);
              if (rdoUsePassword.isSelected()) txtPassword.setEnabled(true);
              if (rdoKeyGen.isSelected()) scanKeygen.setEnabled(true);
            } else {
              txtIP.setEnabled(false);
              txtPort.setEnabled(false);
              rdoUsePassword.setEnabled(false);
              rdoKeyGen.setEnabled(false);
              txtUserName.setEnabled(false);
              txtPassword.setEnabled(false);
              scanKeygen.setEnabled(false);
            }
          }
        });
    ImageIcon btnRemoteEngineIcon = new ImageIcon();
    try {
      btnRemoteEngineIcon.setImage(
          ImageIO.read(getClass().getResourceAsStream("/assets/settings.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    JFontButton btnRemoteEngine = new JFontButton(btnRemoteEngineIcon);
    AccessibilitySupport.button(
        btnRemoteEngine,
        resourceBundle.getString("MoreEngines.aboutRemoteEngineTitle"),
        resourceBundle.getString("MoreEngines.aboutRemoteEngine"));
    btnRemoteEngine.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            Discribe lizzieCacheDiscribe = new Discribe();
            lizzieCacheDiscribe.setInfo(
                resourceBundle.getString("MoreEngines.aboutRemoteEngine"),
                resourceBundle.getString("MoreEngines.aboutRemoteEngineTitle"),
                engjf);
          }
        });
    this.txtIP = new JFontTextField();
    this.txtPort = new JFontTextField();
    this.txtUserName = new JFontTextField();
    this.txtPassword = new JPasswordField();
    JFontLabel lblIp = new JFontLabel("IP");
    JFontLabel lblPort = new JFontLabel(this.resourceBundle.getString("MoreEngines.lblPort"));
    JFontLabel lblUserName =
        new JFontLabel(this.resourceBundle.getString("MoreEngines.rdoUserName"));
    this.rdoKeyGen = new JFontRadioButton(this.resourceBundle.getString("MoreEngines.rdoKeygen"));
    this.rdoUsePassword =
        new JFontRadioButton(this.resourceBundle.getString("MoreEngines.lblPassword"));
    this.scanKeygen = new JFontButton(this.resourceBundle.getString("MoreEngines.scanKeygen"));
    this.scanKeygen.setMargin(new Insets(0, 0, 0, 0));
    this.scanKeygen.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            MoreEngines.engjf.setAlwaysOnTop(false);
            FileDialog fileDialog =
                new FileDialog(
                    MoreEngines.engjf, resourceBundle.getString("MoreEngines.chooseKeygen"));
            fileDialog.setLocationRelativeTo(MoreEngines.engjf);
            fileDialog.setAlwaysOnTop(true);
            fileDialog.setModal(true);
            fileDialog.setMultipleMode(false);
            fileDialog.setMode(0);
            fileDialog.setVisible(true);
            File[] file = fileDialog.getFiles();
            if (file.length > 0) keyGenPath = file[0].getAbsolutePath();
            scanKeygen.setToolTipText(keyGenPath);
            rdoKeyGen.setToolTipText(keyGenPath);
            MoreEngines.engjf.setAlwaysOnTop(true);
          }
        });
    this.rdoUsePassword.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            scanKeygen.setEnabled(false);
            txtPassword.setEnabled(true);
          }
        });
    this.rdoKeyGen.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            scanKeygen.setEnabled(true);
            txtPassword.setEnabled(false);
          }
        });
    ButtonGroup gourpKeyPassword = new ButtonGroup();
    gourpKeyPassword.add(rdoUsePassword);
    gourpKeyPassword.add(rdoKeyGen);

    this.selectpanel.add(lblIp);
    this.selectpanel.add(lblPort);
    this.selectpanel.add(this.rdoUsePassword);
    this.selectpanel.add(lblUserName);
    this.selectpanel.add(this.chkRemoteEngine);
    this.selectpanel.add(btnRemoteEngine);
    this.selectpanel.add(this.txtIP);
    this.selectpanel.add(this.txtPort);
    this.selectpanel.add(this.txtUserName);
    this.selectpanel.add(this.txtPassword);
    this.selectpanel.add(this.rdoKeyGen);
    this.selectpanel.add(this.scanKeygen);
    AccessibilitySupport.labelFor(lblName, txtName, lblName.getText());
    AccessibilitySupport.labelFor(lblCommand, command, lblCommand.getText());
    AccessibilitySupport.labelFor(
        lblInitialCommand, txtInitialCommand, lblInitialCommand.getText());
    AccessibilitySupport.labelFor(lblWidth, txtWidth, lblWidth.getText());
    AccessibilitySupport.labelFor(lblHeight, txtHeight, lblHeight.getText());
    AccessibilitySupport.labelFor(lblKomi, txtKomi, lblKomi.getText());
    AccessibilitySupport.labelFor(lblIp, txtIP, lblIp.getText());
    AccessibilitySupport.labelFor(lblPort, txtPort, lblPort.getText());
    AccessibilitySupport.labelFor(lblUserName, txtUserName, lblUserName.getText());
    AccessibilitySupport.named(txtPassword, rdoUsePassword.getText(), rdoUsePassword.getText());
    this.engineName.setBounds(5, 3, 700, 24);
    int formControlX =
        Math.max(
            90,
            Math.max(
                    Math.max(lblName.getPreferredSize().width, lblCommand.getPreferredSize().width),
                    Math.max(
                        lblInitialCommand.getPreferredSize().width, scan.getPreferredSize().width))
                + 10);
    int formLabelWidth = formControlX - 10;
    int formControlWidth = 880 - formControlX;
    lblName.setBounds(5, 32, formLabelWidth, 24);
    lblCommand.setBounds(5, 60, formLabelWidth, 24);
    this.scan.setBounds(5, 83, formLabelWidth, 24);
    this.txtName.setBounds(formControlX, 35, formControlWidth, 24);
    this.command.setBounds(formControlX, 65, formControlWidth, 170);
    lblInitialCommand.setBounds(5, 240, formLabelWidth, 24);
    this.txtInitialCommand.setBounds(formControlX, 240, formControlWidth, 24);
    int boardSettingsX = 5;
    boardSettingsX = placeInRow(preload, boardSettingsX, 270, 4, 0);
    boardSettingsX = placeInRow(chkDefault, boardSettingsX, 270, 12, 0);
    boardSettingsX = placeInRow(lblWidth, boardSettingsX, 270, 4, 0);
    boardSettingsX = placeInRow(txtWidth, boardSettingsX, 270, 12, 40);
    boardSettingsX = placeInRow(lblHeight, boardSettingsX, 270, 4, 0);
    boardSettingsX = placeInRow(txtHeight, boardSettingsX, 270, 12, 40);
    boardSettingsX = placeInRow(lblKomi, boardSettingsX, 270, 4, 0);
    placeInRow(txtKomi, boardSettingsX, 270, 0, 48);
    int remoteX = 5;
    remoteX = placeInRow(chkRemoteEngine, remoteX, 296, 6, 0);
    remoteX = placeInRow(btnRemoteEngine, remoteX, 296, 8, 24);
    remoteX = placeInRow(lblIp, remoteX, 296, 4, 0);
    remoteX = placeInRow(txtIP, remoteX, 296, 8, 150);
    remoteX = placeInRow(lblPort, remoteX, 296, 4, 0);
    remoteX = placeInRow(txtPort, remoteX, 296, 8, 58);
    remoteX = placeInRow(lblUserName, remoteX, 296, 4, 0);
    placeInRow(txtUserName, remoteX, 296, 0, 150);

    int authX = 5;
    authX = placeInRow(rdoUsePassword, authX, 324, 4, 0);
    authX = placeInRow(txtPassword, authX, 324, 14, 150);
    authX = placeInRow(rdoKeyGen, authX, 324, 4, 0);
    placeInRow(scanKeygen, authX, 324, 0, 100);

    int orderingX = 5;
    orderingX = placeInRow(moveUp, orderingX, 354, 2, 55);
    orderingX = placeInRow(moveDown, orderingX, 354, 2, 55);
    orderingX = placeInRow(moveUp5, orderingX, 354, 2, 55);
    orderingX = placeInRow(moveDown5, orderingX, 354, 2, 55);
    orderingX = placeInRow(moveFirst, orderingX, 354, 2, 55);
    placeInRow(moveLast, orderingX, 354, 0, 55);
    int gtpConfigWidth =
        Math.min(200, AccessibilitySupport.localizedControlWidth(this.gtpConfig, 150));
    this.gtpConfig.setBounds(360, 354, gtpConfigWidth, 24);

    int actionRight = 885;
    actionRight = placeFromRight(exit, actionRight, 354, 2, 60);
    actionRight = placeFromRight(save, actionRight, 354, 18, 60);
    actionRight = placeFromRight(cancel, actionRight, 354, 2, 54);
    actionRight = placeFromRight(delete, actionRight, 354, 2, 54);
    placeFromRight(add, actionRight, 354, 0, 54);

    int startupX = 5;
    startupX = placeInRow(lblchooseStart, startupX, 382, 6, 0);
    startupX = placeInRow(rdoDefault, startupX, 382, 4, 0);
    startupX = placeInRow(rdoLast, startupX, 382, 4, 0);
    startupX = placeInRow(rdoMannul, startupX, 382, 4, 0);
    placeInRow(rdoNone, startupX, 382, 0, 0);
    //    this.rdoDefault.setBounds(
    //        Lizzie.config.isFrameFontSmall() ? 70 : (Lizzie.config.isFrameFontMiddle() ? 90 :
    // 110),
    //        360,
    //        (Lizzie.config.isFrameFontSmall() ? 130 : (Lizzie.config.isFrameFontMiddle() ? 160 :
    // 190))
    //            + (Lizzie.config.isChinese ? 0 : 15),
    //        24);
    //    this.rdoLast.setBounds(
    //        (Lizzie.config.isFrameFontSmall() ? 210 : (Lizzie.config.isFrameFontMiddle() ? 250 :
    // 310))
    //            + (Lizzie.config.isChinese ? 0 : 15),
    //        360,
    //        Lizzie.config.isFrameFontSmall() ? 160 : (Lizzie.config.isFrameFontMiddle() ? 215 :
    // 250),
    //        24);
    //    this.rdoMannul.setBounds(
    //        (Lizzie.config.isFrameFontSmall() ? 368 : (Lizzie.config.isFrameFontMiddle() ? 465 :
    // 570))
    //            + (Lizzie.config.isChinese ? 0 : 15),
    //        360,
    //        145,
    //        24);
    JFontButton btnEncrypt =
        new JFontButton(this.resourceBundle.getString("MoreEngines.btnEncrypt"));
    btnEncrypt.setMargin(new Insets(0, 0, 0, 0));
    btnEncrypt.setBounds(765, 382, 120, 24);
    this.selectpanel.add(btnEncrypt);
    btnEncrypt.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (command.getText().startsWith("encryption||")) return;
            command.setText("encryption||" + Utils.doEncrypt2(command.getText().trim()));
          }
        });
    ButtonGroup startGroup = new ButtonGroup();
    startGroup.add(this.rdoDefault);
    startGroup.add(this.rdoLast);
    startGroup.add(this.rdoMannul);
    startGroup.add(this.rdoNone);
    if (Lizzie.config.uiConfig.optBoolean("autoload-default", false)) rdoDefault.setSelected(true);
    else if (Lizzie.config.uiConfig.optBoolean("autoload-last", false)) rdoLast.setSelected(true);
    else if (Lizzie.config.uiConfig.optBoolean("autoload-empty", false)) rdoNone.setSelected(true);
    else rdoMannul.setSelected(true);

    setEnable(false);
    this.selectpanel.setBounds(0, 0, 900, 412);
    this.tablepanel.setBounds(0, 412, 885, 353);
    this.selectpanel.add(this.engineName);
    this.selectpanel.add(lblName);
    this.selectpanel.add(this.txtName);
    this.selectpanel.add(this.command);
    this.selectpanel.add(lblCommand);
    this.selectpanel.add(lblInitialCommand);
    this.selectpanel.add(this.txtInitialCommand);
    this.selectpanel.add(this.preload);
    this.selectpanel.add(lblWidth);
    this.selectpanel.add(this.txtWidth);
    this.selectpanel.add(lblHeight);
    this.selectpanel.add(this.txtHeight);
    this.selectpanel.add(lblKomi);
    this.selectpanel.add(this.txtKomi);
    this.selectpanel.add(this.scan);
    this.selectpanel.add(this.add);
    this.selectpanel.add(this.save);
    this.selectpanel.add(this.cancel);
    this.selectpanel.add(this.exit);
    this.selectpanel.add(this.moveUp);
    this.selectpanel.add(this.moveUp5);
    this.selectpanel.add(this.moveFirst);
    this.selectpanel.add(this.moveLast);
    this.selectpanel.add(this.moveDown);
    this.selectpanel.add(this.moveDown5);
    this.selectpanel.add(this.gtpConfig);
    this.selectpanel.add(this.chkDefault);
    this.selectpanel.add(lblchooseStart);
    this.selectpanel.add(this.rdoDefault);
    this.selectpanel.add(this.rdoLast);
    this.selectpanel.add(this.rdoMannul);
    this.selectpanel.add(this.rdoNone);
    this.selectpanel.add(this.delete);
    AccessibilitySupport.button(
        this.gtpConfig,
        this.resourceBundle.getString("MoreEngines.gtpConfig"),
        this.resourceBundle.getString("MoreEngines.gtpConfigDescription"));
    AccessibilitySupport.applyToTree(this);
    this.scan.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            GetEngineLine getEngineLine = new GetEngineLine();
            String el = getEngineLine.getEngineLine(MoreEngines.engjf, false, false, false, false);
            if (!el.isEmpty()) command.setText(el);
            setVisible(true);
          }
        });
    this.gtpConfig.addActionListener(event -> configureSelectedEngine());
    this.add.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            checkSave();
            ArrayList<EngineData> engData = Utils.getEngineData();
            EngineData newEng = new EngineData();
            newEng.commands = "";
            newEng.height = 19;
            newEng.index = 0;
            newEng.isDefault = false;
            newEng.komi = 7.5F;
            newEng.name = resourceBundle.getString("ChooseMoreEngine.newEngine");
            newEng.preload = false;
            newEng.width = 19;
            engData.add(0, newEng);
            Utils.saveEngineSettings(engData);
            markEngineCatalogChangedAndRefresh();
            handleTableClick(0);
          }
        });
    this.delete.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            SwingUtilities.invokeLater(
                new Runnable() {
                  public void run() {
                    Object[] options1 = {
                      resourceBundle.getString("MoreEngines.deleteHint"),
                      resourceBundle.getString("MoreEngines.deleteHint2")
                    };
                    int ret1 =
                        JOptionPane.showOptionDialog(
                            MoreEngines.engjf,
                            resourceBundle.getString("MoreEngines.deleteHint5"),
                            resourceBundle.getString("MoreEngines.deleteHint6"),
                            0,
                            3,
                            null,
                            options1,
                            options1[0]);
                    if (ret1 != 0) return;
                    ArrayList<EngineData> engineData = Utils.getEngineData();
                    engineData.remove(curIndex);
                    Utils.saveEngineSettings(engineData);
                    table.validate();
                    table.updateUI();
                    table.getSelectionModel().clearSelection();
                    markEngineCatalogChangedAndRefresh();
                    handleTableClick(curIndex);
                  }
                });
          }
        });
    this.exit.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            checkSave();
            MoreEngines.engjf.setVisible(false);
            if (needUpdateEngine)
              try {
                Lizzie.engineManager.updateEngines();
              } catch (JSONException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
              } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
              }
          }
        });
    this.cancel.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            command.setText("");
            engineName.setText(resourceBundle.getString("MoreEngines.engineName"));
            txtName.setText("");
            txtInitialCommand.setText("");
            txtInitialCommand.setForeground(Color.GRAY);
            txtInitialCommand.setText(resourceBundle.getString("MoreEngines.initialCommandHint"));
            preload.setSelected(false);
            txtWidth.setText("");
            txtHeight.setText("");
            chkDefault.setSelected(false);
            txtIP.setText("");
            txtPort.setText("");
            rdoUsePassword.setSelected(false);
            rdoKeyGen.setSelected(false);
            keyGenPath = "";
            txtUserName.setText("");
            txtPassword.setText("");
            scanKeygen.setToolTipText("");
            scanKeygen.setToolTipText("");
            curIndex = -1;
            setEnable(false);
            table.getSelectionModel().clearSelection();
          }
        });
    this.save.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            saveDefaultEngine();
            boolean empty = false;
            if (command.getText().equals("")) {
              empty = true;
              command.setText(" ");
            }
            if (curIndex >= 0) saveCurrentEngineConfig();
            if (empty) command.setText("");
            table.validate();
            table.updateUI();
          }
        });
    table.addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            int row = table.rowAtPoint(e.getPoint());
            checkSave();
            handleTableClick(row);
          }
        });
    this.moveFirst.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            ArrayList<EngineData> engData = Utils.getEngineData();
            if (curIndex < 1 || curIndex > engData.size() - 1) return;
            EngineData enginedt = engData.get(curIndex);
            engData.remove(curIndex);
            engData.add(0, enginedt);
            Utils.saveEngineSettings(engData);
            markEngineCatalogChangedAndRefresh();
            table.validate();
            table.updateUI();
            curIndex = 0;
          }
        });
    this.moveLast.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            ArrayList<EngineData> engData = Utils.getEngineData();
            if (curIndex < 0 || curIndex > engData.size() - 1) return;
            EngineData enginedt = engData.get(curIndex);
            engData.remove(curIndex);
            engData.add(engData.size(), enginedt);
            Utils.saveEngineSettings(engData);
            markEngineCatalogChangedAndRefresh();
            table.validate();
            table.updateUI();
            curIndex = engData.size() - 1;
          }
        });
    this.moveUp.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            ArrayList<EngineData> engData = Utils.getEngineData();
            if (curIndex < 1 || curIndex > engData.size() - 1) return;
            EngineData enginedt = engData.get(curIndex);
            engData.remove(curIndex);
            engData.add(curIndex - 1, enginedt);
            Utils.saveEngineSettings(engData);
            markEngineCatalogChangedAndRefresh();
            table.validate();
            table.updateUI();
            curIndex--;
          }
        });
    this.moveUp5.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            ArrayList<EngineData> engData = Utils.getEngineData();
            if (curIndex < 5 || curIndex > engData.size() - 1) return;
            EngineData enginedt = engData.get(curIndex);
            engData.remove(curIndex);
            engData.add(curIndex - 5, enginedt);
            Utils.saveEngineSettings(engData);
            markEngineCatalogChangedAndRefresh();
            table.validate();
            table.updateUI();
            curIndex -= 5;
          }
        });
    this.moveDown.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            ArrayList<EngineData> engData = Utils.getEngineData();
            if (curIndex < 0 || curIndex > engData.size() - 2) return;
            EngineData enginedt = engData.get(curIndex);
            engData.remove(curIndex);
            engData.add(curIndex + 1, enginedt);
            Utils.saveEngineSettings(engData);
            markEngineCatalogChangedAndRefresh();
            table.validate();
            table.updateUI();
            curIndex++;
          }
        });
    this.moveDown5.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            ArrayList<EngineData> engData = Utils.getEngineData();
            if (curIndex < 0 || curIndex > engData.size() - 6) return;
            EngineData enginedt = engData.get(curIndex);
            engData.remove(curIndex);
            engData.add(curIndex + 5, enginedt);
            Utils.saveEngineSettings(engData);
            markEngineCatalogChangedAndRefresh();
            table.validate();
            table.updateUI();
            curIndex += 5;
          }
        });
  }

  private static int placeInRow(
      Component component, int x, int y, int trailingGap, int minimumWidth) {
    int preferredWidth =
        component.getPreferredSize() == null ? 0 : component.getPreferredSize().width;
    int width = Math.max(minimumWidth, preferredWidth);
    if (component instanceof AbstractButton) {
      width += 8;
    } else if (component instanceof JLabel) {
      width += 2;
    }
    component.setBounds(x, y, width, 24);
    return x + width + trailingGap;
  }

  private static int placeFromRight(
      Component component, int right, int y, int leadingGap, int minimumWidth) {
    int preferredWidth =
        component.getPreferredSize() == null ? 0 : component.getPreferredSize().width;
    int width = Math.max(minimumWidth, preferredWidth);
    if (component instanceof AbstractButton) {
      width += 8;
    }
    int x = right - width;
    component.setBounds(x, y, width, 24);
    return x - leadingGap;
  }

  class ColorTableCellRenderer extends DefaultTableCellRenderer {
    DefaultTableCellRenderer renderer;

    ColorTableCellRenderer() {
      this.renderer = new DefaultTableCellRenderer();
    }

    public Component getTableCellRendererComponent(
        JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      if (column == 2) {
        JLabel label =
            (JLabel)
                super.getTableCellRendererComponent(
                    table, value, row == curIndex, hasFocus, row, column);
        label.setToolTipText(value.toString());
        return label;
      }
      return this.renderer.getTableCellRendererComponent(
          table, value, row == curIndex, false, row, column);
    }
  }

  public void saveDefaultEngine() {
    // if (this.chkDefault.isSelected()) Lizzie.config.uiConfig.put("default-engine",
    // this.curIndex);
    if (updateStartupMode(
        Lizzie.config.uiConfig,
        rdoDefault.isSelected(),
        rdoLast.isSelected(),
        rdoNone.isSelected())) {
      try {
        Lizzie.config.save();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  static boolean updateStartupMode(
      JSONObject uiConfig, boolean autoloadDefault, boolean autoloadLast, boolean autoloadEmpty) {
    boolean oldDefault = uiConfig.optBoolean("autoload-default", false);
    boolean oldLast = uiConfig.optBoolean("autoload-last", false);
    boolean oldEmpty = uiConfig.optBoolean("autoload-empty", false);
    boolean newDefault = autoloadDefault;
    boolean newLast = !newDefault && autoloadLast;
    boolean newEmpty = !newDefault && !newLast && autoloadEmpty;

    uiConfig.put("autoload-default", newDefault);
    uiConfig.put("autoload-last", newLast);
    uiConfig.put("autoload-empty", newEmpty);
    return oldDefault != newDefault || oldLast != newLast || oldEmpty != newEmpty;
  }

  private void checkSave() {
    if (this.curIndex < 0) return;
    boolean isChanged = false;
    ArrayList<EngineData> engData = Utils.getEngineData();
    if (this.curIndex >= engData.size()) {
      isChanged = true;
    } else {
      EngineData engDt = engData.get(this.curIndex);
      if (!this.command.getText().startsWith("encryption||")
          && !this.command.getText().equals(engDt.commands)) isChanged = true;
      if (!this.txtName.getText().equals(engDt.name)) isChanged = true;
      if (!this.txtInitialCommand
              .getText()
              .equals(resourceBundle.getString("MoreEngines.initialCommandHint"))
          && !this.txtInitialCommand.getText().equals(engDt.initialCommand)) isChanged = true;
      if (this.preload.isSelected() != engDt.preload) isChanged = true;
      if (!this.txtWidth
          .getText()
          .equals((new StringBuilder(String.valueOf(engDt.width))).toString())) isChanged = true;
      if (!this.txtHeight
          .getText()
          .equals((new StringBuilder(String.valueOf(engDt.height))).toString())) isChanged = true;
      if (!this.txtKomi
          .getText()
          .equals((new StringBuilder(String.valueOf(engDt.komi))).toString())) isChanged = true;
      if (this.chkDefault.isSelected() != engDt.isDefault) isChanged = true;
      if (this.chkRemoteEngine.isSelected() != engDt.useJavaSSH) isChanged = true;
      if (this.chkRemoteEngine.isSelected()) {
        if (!this.txtIP.getText().equals(engDt.ip)) isChanged = true;
        if (!this.txtPort.getText().equals(engDt.port)) isChanged = true;
        if (!this.txtUserName.getText().equals(engDt.userName)) isChanged = true;
        if (this.rdoKeyGen.isSelected() != engDt.useKeyGen) isChanged = true;
        if (this.rdoKeyGen.isSelected()) {
          if (!this.keyGenPath.equals(engDt.keyGenPath)) isChanged = true;
        } else if (!Utils.doEncrypt(new String(this.txtPassword.getPassword()))
            .equals(engDt.password)) {
          isChanged = true;
        }
      }
      if (isChanged)
        if (this.command.getText().equals("")) {
          Object[] options1 = {
            this.resourceBundle.getString("MoreEngines.deleteHint"),
            this.resourceBundle.getString("MoreEngines.deleteHint2")
          };
          int ret1 =
              JOptionPane.showOptionDialog(
                  this,
                  this.resourceBundle.getString("MoreEngines.deleteHint3"),
                  this.resourceBundle.getString("MoreEngines.deleteHint4"),
                  0,
                  3,
                  null,
                  options1,
                  options1[0]);
          if (ret1 == 0) saveCurrentEngineConfig();
        } else {
          Object[] options = {
            this.resourceBundle.getString("MoreEngines.saveHint"),
            this.resourceBundle.getString("MoreEngines.saveHint2")
          };
          int ret =
              JOptionPane.showOptionDialog(
                  this,
                  this.resourceBundle.getString("MoreEngines.saveHint3"),
                  this.resourceBundle.getString("MoreEngines.saveHint4"),
                  0,
                  3,
                  null,
                  options,
                  options[0]);
          if (ret == 0) saveCurrentEngineConfig();
        }
    }
  }

  private void setEnable(boolean isEnable) {
    if (isEnable) {
      this.txtName.setEnabled(true);
      txtInitialCommand.setEnabled(true);
      this.command.setEnabled(true);
      command.setBackground(AppleStyleSupport.validFieldBackground());
      this.preload.setEnabled(true);
      this.txtWidth.setEnabled(true);
      this.txtHeight.setEnabled(true);
      this.txtKomi.setEnabled(true);
      this.chkDefault.setEnabled(true);
      this.chkRemoteEngine.setEnabled(true);
      if (this.chkRemoteEngine.isSelected()) {
        this.txtIP.setEnabled(true);
        this.txtPort.setEnabled(true);
        this.rdoUsePassword.setEnabled(true);
        this.rdoKeyGen.setEnabled(true);
        if (this.rdoUsePassword.isSelected()) this.txtPassword.setEnabled(true);
        if (this.rdoKeyGen.isSelected()) this.scanKeygen.setEnabled(true);
      }
      this.delete.setEnabled(true);
      this.save.setEnabled(true);
      this.cancel.setEnabled(true);
      this.scan.setEnabled(true);
      this.moveUp.setEnabled(true);
      this.moveUp5.setEnabled(true);
      this.moveFirst.setEnabled(true);
      this.moveLast.setEnabled(true);
      this.moveDown.setEnabled(true);
      this.moveDown5.setEnabled(true);
      this.gtpConfig.setEnabled(true);
    } else {
      this.txtName.setEnabled(false);
      txtInitialCommand.setEnabled(false);
      this.command.setEnabled(false);
      command.setBackground(getBackground());
      this.preload.setEnabled(false);
      this.txtWidth.setEnabled(false);
      this.txtHeight.setEnabled(false);
      this.txtKomi.setEnabled(false);
      this.chkRemoteEngine.setEnabled(false);
      this.txtIP.setEnabled(false);
      this.txtPort.setEnabled(false);
      this.txtUserName.setEnabled(false);
      this.txtPassword.setEnabled(false);
      this.moveUp.setEnabled(false);
      this.moveDown.setEnabled(false);
      this.moveUp5.setEnabled(false);
      this.moveDown5.setEnabled(false);
      this.chkDefault.setEnabled(false);
      this.delete.setEnabled(false);
      this.moveFirst.setEnabled(false);
      this.moveLast.setEnabled(false);
      this.scan.setEnabled(false);
      this.cancel.setEnabled(false);
      this.rdoUsePassword.setEnabled(false);
      this.rdoKeyGen.setEnabled(false);
      this.scanKeygen.setEnabled(false);
      this.gtpConfig.setEnabled(false);
    }
  }

  private void handleTableClick(int row) {
    ArrayList<EngineData> engineDatas = Utils.getEngineData();
    if (row < engineDatas.size()) {
      EngineData engineData = engineDatas.get(row);
      this.command.setText(engineData.commands);
      this.engineName.setText(
          String.valueOf(this.resourceBundle.getString("MoreEngines.editEngine"))
              + (engineData.index + 1));
      this.txtName.setText(engineData.name);
      this.txtInitialCommand.setText(engineData.initialCommand);
      if (engineData.initialCommand.equals("")) {
        txtInitialCommand.setForeground(Color.GRAY);
        txtInitialCommand.setText(resourceBundle.getString("MoreEngines.initialCommandHint"));
      } else {
        txtInitialCommand.setForeground(Color.BLACK);
      }
      this.preload.setSelected(engineData.preload);
      this.txtWidth.setText((new StringBuilder(String.valueOf(engineData.width))).toString());
      this.txtHeight.setText((new StringBuilder(String.valueOf(engineData.height))).toString());
      this.chkDefault.setSelected(engineData.isDefault);
      this.txtKomi.setText((new StringBuilder(String.valueOf(engineData.komi))).toString());
      this.chkRemoteEngine.setSelected(engineData.useJavaSSH);
      if (engineData.useJavaSSH) {
        this.txtIP.setText(engineData.ip);
        this.txtPort.setText(engineData.port);
        this.rdoUsePassword.setSelected(!engineData.useKeyGen);
        this.rdoKeyGen.setSelected(engineData.useKeyGen);
        this.txtUserName.setText(engineData.userName);
        if (engineData.useKeyGen) {
          this.keyGenPath = engineData.keyGenPath;
          this.scanKeygen.setToolTipText(this.keyGenPath);
          this.rdoKeyGen.setToolTipText(this.keyGenPath);
        } else {
          this.txtPassword.setText(Utils.doDecrypt(engineData.password));
        }
      } else {
        this.txtIP.setText("");
        this.txtPort.setText("");
        this.rdoUsePassword.setSelected(false);
        this.rdoKeyGen.setSelected(false);
        this.keyGenPath = "";
        this.txtUserName.setText("");
        this.txtPassword.setText("");
        this.scanKeygen.setToolTipText("");
        this.scanKeygen.setToolTipText("");
      }
    } else {
      this.command.setText("");
      this.txtName.setText("");
      this.txtInitialCommand.setText("");
      txtInitialCommand.setForeground(Color.GRAY);
      txtInitialCommand.setText(resourceBundle.getString("MoreEngines.initialCommandHint"));
      this.engineName.setText(
          String.valueOf(this.resourceBundle.getString("MoreEngines.editEngine")) + (row + 1));
      this.preload.setSelected(false);
      this.txtWidth.setText("19");
      this.txtHeight.setText("19");
      this.chkDefault.setSelected(false);
      this.txtKomi.setText("7.5");
      this.chkRemoteEngine.setSelected(false);
      this.txtIP.setText("");
      this.txtPort.setText("");
      this.rdoUsePassword.setSelected(false);
      this.rdoKeyGen.setSelected(false);
      this.keyGenPath = "";
      this.txtUserName.setText("");
      this.txtPassword.setText("");
      this.scanKeygen.setToolTipText("");
      this.scanKeygen.setToolTipText("");
    }
    if (this.chkRemoteEngine.isSelected()) {
      this.txtIP.setEnabled(true);
      this.txtPort.setEnabled(true);
      this.rdoUsePassword.setEnabled(true);
      this.rdoKeyGen.setEnabled(true);
      this.txtUserName.setEnabled(true);
      if (this.rdoUsePassword.isSelected()) this.txtPassword.setEnabled(true);
      if (this.rdoKeyGen.isSelected()) this.scanKeygen.setEnabled(true);
    } else {
      this.txtIP.setEnabled(false);
      this.txtPort.setEnabled(false);
      this.rdoUsePassword.setEnabled(false);
      this.rdoKeyGen.setEnabled(false);
      this.txtUserName.setEnabled(false);
      this.txtPassword.setEnabled(false);
      this.scanKeygen.setEnabled(false);
    }
    this.curIndex = Integer.parseInt(table.getModel().getValueAt(row, 0).toString()) - 1;
    setEnable(true);
    table.validate();
    table.updateUI();
  }

  private void saveCurrentEngineConfig() {
    needUpdateEngine = true;
    ArrayList<EngineData> engineData = Utils.getEngineData();
    EngineData engineDt = new EngineData();
    String editedCommand = this.command.getText().trim();
    if (this.curIndex >= 0 && this.curIndex < engineData.size()) {
      copyGtpConfigurationForUnchangedCommand(
          engineDt, engineData.get(this.curIndex), editedCommand);
    }
    engineDt.index = this.curIndex;
    engineDt.commands = editedCommand;
    engineDt.name = this.txtName.getText();
    if (txtInitialCommand
        .getText()
        .equals(resourceBundle.getString("MoreEngines.initialCommandHint")))
      engineDt.initialCommand = "";
    else engineDt.initialCommand = this.txtInitialCommand.getText();
    engineDt.preload = this.preload.isSelected();
    engineDt.width = Utils.parseTextToInt(this.txtWidth, 19);
    engineDt.height = Utils.parseTextToInt(this.txtHeight, 19);
    engineDt.isDefault = this.chkDefault.isSelected();
    engineDt.komi = Utils.parseTextToFloat(this.txtKomi, Float.valueOf(7.5F)).floatValue();
    engineDt.useJavaSSH = this.chkRemoteEngine.isSelected();
    if (engineDt.useJavaSSH) {
      engineDt.ip = this.txtIP.getText();
      engineDt.port = this.txtPort.getText();
      engineDt.useKeyGen = this.rdoKeyGen.isSelected();
      engineDt.userName = this.txtUserName.getText();
      if (engineDt.useKeyGen) {
        engineDt.keyGenPath = this.keyGenPath;
      } else {
        engineDt.password = Utils.doEncrypt(new String(this.txtPassword.getPassword()));
      }
    }
    if (engineDt.isDefault) {
      for (EngineData engine : engineData) {
        engine.isDefault = false;
      }
    }
    if (this.curIndex + 1 > engineData.size()) {
      engineData.add(engineDt);
    } else {
      engineData.remove(this.curIndex);
      engineData.add(this.curIndex, engineDt);
    }
    Utils.saveEngineSettings(engineData);
    refreshEngineCatalogIfReady();
  }

  static void copyGtpConfigurationForUnchangedCommand(
      EngineData target, EngineData previous, String editedCommand) {
    if (target != null
        && previous != null
        && editedCommand != null
        && editedCommand.equals(previous.commands)) {
      target.copyGtpConfigurationFrom(previous);
    }
  }

  private void configureSelectedEngine() {
    if (curIndex < 0) {
      return;
    }
    saveCurrentEngineConfig();
    ArrayList<EngineData> engines = Utils.getEngineData();
    if (curIndex >= engines.size()) {
      return;
    }
    EngineData selected = engines.get(curIndex);
    if (selected.useJavaSSH) {
      showGtpConfigurationMessage(
          resourceBundle.getString("GtpEngineConfig.remoteUnsupported"),
          JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    int selectedIndex = curIndex;
    String selectedCommand = selected.commands;
    JSONObject savedProfile =
        selected.gtpConfigurationProfile == null
            ? null
            : new JSONObject(selected.gtpConfigurationProfile.toString());
    setGtpConfigurationBusy(resourceBundle.getString("GtpEngineConfig.detecting"));
    new SwingWorker<GtpConfigurationProbe.Inspection, Void>() {
      @Override
      protected GtpConfigurationProbe.Inspection doInBackground() throws Exception {
        return new GtpConfigurationProbe().inspect(selectedCommand);
      }

      @Override
      protected void done() {
        restoreGtpConfigurationButton();
        try {
          GtpConfigurationProbe.Inspection inspection = get();
          if (!inspection.supported()) {
            showGtpConfigurationMessage(
                resourceBundle.getString("GtpEngineConfig.unsupported"),
                JOptionPane.INFORMATION_MESSAGE);
            return;
          }
          Optional<JSONObject> profile =
              GtpEngineConfigDialog.showDialog(
                  MoreEngines.this, inspection.schema(), savedProfile);
          profile.ifPresent(
              value ->
                  applyGtpConfiguration(
                      selectedIndex, selectedCommand, inspection.schema(), value));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          showGtpConfigurationError(error);
        } catch (ExecutionException error) {
          showGtpConfigurationError(error.getCause());
        }
      }
    }.execute();
  }

  private void applyGtpConfiguration(
      int selectedIndex,
      String selectedCommand,
      GtpConfigurationSchema schema,
      JSONObject requestedProfile) {
    setGtpConfigurationBusy(resourceBundle.getString("GtpEngineConfig.applying"));
    new SwingWorker<GtpConfigurationProbe.ApplyResult, Void>() {
      @Override
      protected GtpConfigurationProbe.ApplyResult doInBackground() throws Exception {
        return new GtpConfigurationProbe().applyProfile(selectedCommand, requestedProfile);
      }

      @Override
      protected void done() {
        try {
          GtpConfigurationProbe.ApplyResult result = get();
          persistGtpConfiguration(selectedIndex, selectedCommand, schema.protocol(), result.profile());
          applyProfileToRunningEngine(selectedIndex, result.profile());
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          restoreGtpConfigurationButton();
          showGtpConfigurationError(error);
        } catch (ExecutionException | IllegalStateException error) {
          restoreGtpConfigurationButton();
          Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
          showGtpConfigurationError(cause);
        }
      }
    }.execute();
  }

  private void persistGtpConfiguration(
      int selectedIndex, String expectedCommand, String protocol, JSONObject profile) {
    ArrayList<EngineData> engines = Utils.getEngineData();
    if (selectedIndex < 0 || selectedIndex >= engines.size()) {
      throw new IllegalStateException(resourceBundle.getString("GtpEngineConfig.engineChanged"));
    }
    EngineData engine = engines.get(selectedIndex);
    if (!expectedCommand.equals(engine.commands)) {
      throw new IllegalStateException(resourceBundle.getString("GtpEngineConfig.engineChanged"));
    }
    engine.gtpConfigurationProtocol = protocol;
    engine.gtpConfigurationProfile = new JSONObject(profile.toString());
    Utils.saveEngineSettings(engines);
    markEngineCatalogChangedAndRefresh();
  }

  private void applyProfileToRunningEngine(int selectedIndex, JSONObject profile) {
    if (Lizzie.engineManager == null
        || selectedIndex < 0
        || selectedIndex >= Lizzie.engineManager.engineList.size()) {
      showGtpConfigurationSavedStatus();
      return;
    }
    Leelaz engine = Lizzie.engineManager.engineList.get(selectedIndex);
    engine.gtpConfigurationProtocol = GtpConfigurationProbe.ZENGTP_PROTOCOL;
    engine.gtpConfigurationProfile = new JSONObject(profile.toString());
    if (!engine.started || !engine.supportsGtpConfiguration()) {
      showGtpConfigurationSavedStatus();
      return;
    }
    engine.applyGtpConfigurationProfile(
        profile,
        response -> SwingUtilities.invokeLater(this::showGtpConfigurationSavedStatus),
        error ->
            SwingUtilities.invokeLater(
                () -> {
                  restoreGtpConfigurationButton();
                  showGtpConfigurationMessage(
                      resourceBundle.getString("GtpEngineConfig.savedForNextStart"),
                      JOptionPane.WARNING_MESSAGE);
                }));
  }

  private void setGtpConfigurationBusy(String text) {
    gtpConfig.setText(text);
    gtpConfig.setEnabled(false);
    table.setEnabled(false);
  }

  private void showGtpConfigurationSavedStatus() {
    gtpConfig.setText(resourceBundle.getString("GtpEngineConfig.saved"));
    gtpConfig.setEnabled(false);
    table.setEnabled(true);
    Timer timer = new Timer(1600, event -> restoreGtpConfigurationButton());
    timer.setRepeats(false);
    timer.start();
  }

  private void restoreGtpConfigurationButton() {
    gtpConfig.setText(resourceBundle.getString("MoreEngines.gtpConfig"));
    gtpConfig.setEnabled(curIndex >= 0);
    table.setEnabled(true);
  }

  private void showGtpConfigurationError(Throwable error) {
    String detail = error == null || error.getMessage() == null ? "" : error.getMessage();
    showGtpConfigurationMessage(
        String.format(resourceBundle.getString("GtpEngineConfig.loadFailed"), detail),
        JOptionPane.ERROR_MESSAGE);
  }

  private void showGtpConfigurationMessage(String message, int messageType) {
    JOptionPane.showMessageDialog(
        engjf, message, resourceBundle.getString("GtpEngineConfig.title"), messageType);
  }

  private static void markEngineCatalogChangedAndRefresh() {
    needUpdateEngine = true;
    refreshEngineCatalogIfReady();
  }

  private static void refreshEngineCatalogIfReady() {
    if (!needUpdateEngine || Lizzie.engineManager == null) {
      return;
    }
    try {
      Lizzie.engineManager.refreshEngineCatalog();
      needUpdateEngine = false;
    } catch (JSONException | IOException e) {
      e.printStackTrace();
    }
  }

  public AbstractTableModel getTableModel() {
    return new AbstractTableModel() {
      public int getColumnCount() {
        return 9;
      }

      public int getRowCount() {
        return 500;
      }

      public String getColumnName(int column) {
        if (column == 0) return resourceBundle.getString("MoreEngines.column0");
        if (column == 1) return resourceBundle.getString("MoreEngines.column1");
        if (column == 2) return resourceBundle.getString("MoreEngines.column2");
        if (column == 3) return resourceBundle.getString("MoreEngines.column3");
        if (column == 4) return resourceBundle.getString("MoreEngines.column4");
        if (column == 5) return resourceBundle.getString("MoreEngines.column5");
        if (column == 6) return resourceBundle.getString("MoreEngines.column6");
        if (column == 7) return resourceBundle.getString("MoreEngines.column7");
        if (column == 8) return resourceBundle.getString("MoreEngines.column8");
        return "";
      }

      public Object getValueAt(int row, int col) {
        ArrayList<EngineData> EngineDatas = Utils.getEngineData();
        if (row > EngineDatas.size() - 1) {
          if (col == 0) return Integer.valueOf(row + 1);
          return "";
        }
        EngineData data = EngineDatas.get(row);
        switch (col) {
          case 0:
            return Integer.valueOf(row + 1);
          case 1:
            return data.name;
          case 2:
            return data.commands;
          case 3:
            if (data.preload) return resourceBundle.getString("MoreEngines.yes");
            return resourceBundle.getString("MoreEngines.no");
          case 4:
            return Integer.valueOf(data.width);
          case 5:
            return Integer.valueOf(data.height);
          case 6:
            return Float.valueOf(data.komi);
          case 7:
            if (data.isDefault) return resourceBundle.getString("MoreEngines.yes");
            return resourceBundle.getString("MoreEngines.no");
          case 8:
            if (data.useJavaSSH) return resourceBundle.getString("MoreEngines.yes");
            return resourceBundle.getString("MoreEngines.no");
        }
        return "";
      }
    };
  }

  public static JDialog createDialog() {
    engjf = new JDialog();
    needUpdateEngine = false;
    engjf.setTitle(Lizzie.resourceBundle.getString("MoreEngines.title"));
    engjf.setModal(true);
    engjf.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            MoreEngines.engjf.setVisible(false);
            if (needUpdateEngine)
              try {
                Lizzie.engineManager.updateEngines();
              } catch (JSONException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
              } catch (IOException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
              }
          }
        });
    MoreEngines newContentPane = new MoreEngines();
    newContentPane.setOpaque(true);
    engjf.setContentPane(newContentPane);
    Lizzie.setFrameSize(engjf, 891, 794);
    engjf.setResizable(false);
    try {
      engjf.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    engjf.setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    engjf.setLocationRelativeTo(engjf.getOwner());
    LizzieFrame.constrainWindowToAvailableWorkArea(engjf);
    AccessibilitySupport.installEscapeToClose(engjf.getRootPane(), engjf);
    return engjf;
  }
}
