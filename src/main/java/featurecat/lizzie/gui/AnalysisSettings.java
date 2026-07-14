package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.gui.LizzieFrame.HtmlKit;
import featurecat.lizzie.util.AnalysisEngineCommandHelper;
import featurecat.lizzie.util.Utils;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.StyleSheet;

public class AnalysisSettings extends JDialog {
  public enum Context {
    NORMAL,
    BATCH
  }

  enum FontScale {
    SMALL,
    MIDDLE,
    LARGE
  }

  static final class EngineCommandToolbarBounds {
    final Rectangle generate;
    final Rectangle savedEngine;
    final Rectangle remoteEngine;
    final Rectangle remoteSettings;

    private EngineCommandToolbarBounds(
        Rectangle generate,
        Rectangle savedEngine,
        Rectangle remoteEngine,
        Rectangle remoteSettings) {
      this.generate = generate;
      this.savedEngine = savedEngine;
      this.remoteEngine = remoteEngine;
      this.remoteSettings = remoteSettings;
    }
  }

  private JTextField txtMaxVisits;
  private JRadioButton rdoUseCurrentRules;
  private JRadioButton rdoUseSpecificRules;
  private JFontTextArea engineCmd;
  private JFontCheckBox chkPreLoad;
  private JFontCheckBox chkAlwaysOverride;
  private JFontCheckBox chkAutoExit;
  private JDialog dialog = this;
  private JFontCheckBox chkUseJavaSSH;
  private final Context context;
  private String originalEngineCommand = "";
  private boolean engineCommandExplicitlyChanged = false;

  public AnalysisSettings(boolean isDuringAnalyze, boolean fromError) {
    this(
        isDuringAnalyze,
        fromError,
        Lizzie.frame != null && Lizzie.frame.isBatchAnalysisMode ? Context.BATCH : Context.NORMAL);
  }

  public AnalysisSettings(boolean isDuringAnalyze, boolean fromError, Context context) {
    this.context = context == null ? Context.NORMAL : context;
    this.setModal(true);
    this.setAlwaysOnTop(Lizzie.frame != null && Lizzie.frame.isAlwaysOnTop());
    setResizable(false);
    setTitle(Lizzie.resourceBundle.getString("AnalysisSettings.title")); // ("闪电分析设置");
    // setSize(609, 367);
    Lizzie.setFrameSize(this, 592, 411);
    PanelWithToolTips contentPane = new PanelWithToolTips();
    contentPane.setLayout(null);
    getContentPane().add(contentPane);

    JLabel lblEngineCmd =
        new JFontLabel(
            Lizzie.resourceBundle.getString("AnalysisSettings.lblEngineCmd")); // ("分析引擎命令:");
    lblEngineCmd.setBounds(10, 1, lblEngineCmd.getPreferredSize().width + 2, 22);
    contentPane.add(lblEngineCmd);

    engineCmd = new JFontTextArea();
    engineCmd.setBounds(10, 52, 566, 130);
    engineCmd.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    contentPane.add(engineCmd);
    AccessibilitySupport.labelFor(lblEngineCmd, engineCmd, lblEngineCmd.getText());

    JLabel example =
        new JLabel(
            Lizzie.resourceBundle.getString(
                "AnalysisSettings.example")); // ("例:katago analysis -model model.bin.gz -config
    // analysis.cfg -quit-without-waiting");
    example.setBounds(10, 184, 567, 20);
    contentPane.add(example);

    JLabel lblMaxVisits =
        new JFontLabel(
            Lizzie.resourceBundle.getString("AnalysisSettings.lblMaxVisits")); // ("单步计算量:");
    lblMaxVisits.setBounds(10, 233, 136, 20);
    contentPane.add(lblMaxVisits);

    txtMaxVisits = new JFontTextField();
    txtMaxVisits.setBounds(
        Lizzie.config.isFrameFontSmall() ? 80 : (Lizzie.config.isFrameFontMiddle() ? 100 : 120),
        231,
        66,
        24);
    contentPane.add(txtMaxVisits);
    AccessibilitySupport.labelFor(lblMaxVisits, txtMaxVisits, lblMaxVisits.getText());

    JLabel lblRules =
        new JFontLabel(Lizzie.resourceBundle.getString("AnalysisSettings.lblRules")); // ("规则:");
    lblRules.setBounds(10, 258, 54, 20);
    contentPane.add(lblRules);

    rdoUseCurrentRules =
        new JFontRadioButton(
            Lizzie.resourceBundle.getString(
                "AnalysisSettings.rdoUseCurrentRules")); // ("使用当前引擎规则(未指定规则将使用中国规则)");
    rdoUseCurrentRules.setBounds(59, 257, 510, 23);
    contentPane.add(rdoUseCurrentRules);

    rdoUseSpecificRules =
        new JFontRadioButton(
            Lizzie.resourceBundle.getString("AnalysisSettings.rdoUseSpecificRules")); // ("使用指定规则");
    rdoUseSpecificRules.setBounds(
        59,
        282,
        Lizzie.config.isFrameFontSmall() ? 131 : (Lizzie.config.isFrameFontMiddle() ? 131 : 150),
        23);
    contentPane.add(rdoUseSpecificRules);

    ButtonGroup group = new ButtonGroup();
    group.add(rdoUseSpecificRules);
    group.add(rdoUseCurrentRules);

    chkAlwaysOverride =
        new JFontCheckBox(
            Lizzie.resourceBundle.getString(
                "AnalysisSettings.chkAlwaysOverride")); // ("总是覆盖已有分析结果");
    chkAlwaysOverride.setBounds(10, 307, 370, 23);
    contentPane.add(chkAlwaysOverride);

    chkPreLoad =
        new JFontCheckBox(
            Lizzie.resourceBundle.getString("AnalysisSettings.chkPreLoad")); // ("启动Lizzie时预加载引擎");
    chkPreLoad.setBounds(10, 332, 304, 23);
    contentPane.add(chkPreLoad);

    chkAutoExit =
        new JFontCheckBox(
            Lizzie.resourceBundle.getString("AnalysisSettings.chkAutoExit")); // ("分析完毕后关闭引擎");
    chkAutoExit.setBounds(10, 357, 304, 23);
    contentPane.add(chkAutoExit);

    JButton btnSetRules =
        new JFontButton(
            Lizzie.resourceBundle.getString("AnalysisSettings.btnSetRules")); // ("设置规则");
    btnSetRules.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            SetAnalysisRules setAnalysisRules = new SetAnalysisRules();
            setAnalysisRules.setVisible(true);
          }
        });
    btnSetRules.setMargin(new Insets(0, 0, 0, 0));
    btnSetRules.setBounds(
        Lizzie.config.isFrameFontSmall() ? 191 : (Lizzie.config.isFrameFontMiddle() ? 191 : 211),
        282,
        Lizzie.config.isFrameFontSmall() ? 93 : (Lizzie.config.isFrameFontMiddle() ? 105 : 121),
        23);
    contentPane.add(btnSetRules);

    JButton btnConfirmAndRedo =
        new JFontButton(
            Lizzie.resourceBundle.getString("AnalysisSettings.btnConfirmAndRedo")); // ("确定并重新计算");
    btnConfirmAndRedo.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            {
              if (Lizzie.frame.analysisEngine != null
                  && Lizzie.frame.analysisEngine.waitFrame != null)
                Lizzie.frame.analysisEngine.waitFrame.setVisible(false);
              saveConfig();
              setVisible(false);
              Lizzie.frame.destroyAnalysisEngine();
              Lizzie.frame.flashAnalyzeGame(
                  Lizzie.config.analysisRecentIsPartGame,
                  Lizzie.config.analysisRecentIsAllBranches);
            }
          }
        });
    btnConfirmAndRedo.setMargin(new Insets(0, 0, 0, 0));
    btnConfirmAndRedo.setBounds(
        Lizzie.config.isFrameFontSmall() ? 375 : (Lizzie.config.isFrameFontMiddle() ? 355 : 325),
        347,
        Lizzie.config.isFrameFontSmall() ? 99 : (Lizzie.config.isFrameFontMiddle() ? 120 : 150),
        31);
    btnConfirmAndRedo.setVisible(isDuringAnalyze);
    contentPane.add(btnConfirmAndRedo);

    JButton btnConfirm =
        new JFontButton(Lizzie.resourceBundle.getString("AnalysisSettings.btnConfirm")); // ("确定");
    btnConfirm.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (fromError) {
              if (Lizzie.frame.analysisEngine != null
                  && Lizzie.frame.analysisEngine.waitFrame != null)
                Lizzie.frame.analysisEngine.waitFrame.setVisible(false);
              saveConfig();
              setVisible(false);
              Lizzie.frame.destroyAnalysisEngine();
              Lizzie.frame.flashAnalyzeGame(
                  Lizzie.config.analysisRecentIsPartGame,
                  Lizzie.config.analysisRecentIsAllBranches);
            } else {
              saveConfig();
              setVisible(false);
            }
          }
        });
    btnConfirm.setBounds(484, 347, 93, 31);
    contentPane.add(btnConfirm);

    LinkLabel lblHint2 =
        new LinkLabel(Lizzie.resourceBundle.getString("AnalysisSettings.lblHint2"));
    lblHint2.setBounds(7, 203, 633, 20);
    contentPane.add(lblHint2);

    txtMaxVisits.setText(
        (this.context == Context.BATCH
            ? String.valueOf(Lizzie.config.batchAnalysisPlayouts)
            : String.valueOf(Lizzie.config.analysisMaxVisits)));
    DisplayCommand displayCommand = resolveAnalysisEngineCommandForDisplay();
    originalEngineCommand = displayCommand.command;
    engineCmd.setText(originalEngineCommand);

    if (Lizzie.config.analysisUseCurrentRules) rdoUseCurrentRules.setSelected(true);
    else rdoUseSpecificRules.setSelected(true);

    chkAutoExit.setSelected(Lizzie.config.analysisAutoQuit);
    chkPreLoad.setSelected(Lizzie.config.analysisEnginePreLoad);
    chkAlwaysOverride.setSelected(Lizzie.config.analysisAlwaysOverride);

    JButton btnGenerate =
        new JFontButton(
            Lizzie.resourceBundle.getString("SetEstimateParam.btnGenerate")); // "自动生成");
    btnGenerate.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            GetEngineLine getEngineLine = new GetEngineLine();
            String el = getEngineLine.getEngineLine(dialog, true, true, false, false);
            if (!el.isEmpty()) {
              engineCmd.setText(el);
              engineCommandExplicitlyChanged = true;
            }
          }
        });
    btnGenerate.setMargin(new Insets(0, 0, 0, 0));
    btnGenerate.setFocusable(false);
    contentPane.add(btnGenerate);

    JButton btnSavedEngine =
        new JFontButton(Lizzie.resourceBundle.getString("NewAnaGameDialog.chooseEngine"));
    btnSavedEngine.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            chooseSavedEngineCommand();
          }
        });
    btnSavedEngine.setMargin(new Insets(0, 0, 0, 0));
    btnSavedEngine.setFocusable(false);
    contentPane.add(btnSavedEngine);

    chkUseJavaSSH =
        new JFontCheckBox(Lizzie.resourceBundle.getString("MoreEngines.chkRemoteEngine"));
    JFontButton setRemoteEngine =
        new JFontButton(Lizzie.resourceBundle.getString("SetEstimateParam.setRemoteEngine"));
    setRemoteEngine.setMargin(new Insets(0, 0, 0, 0));
    chkUseJavaSSH.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            setRemoteEngine.setEnabled(chkUseJavaSSH.isSelected());
          }
        });
    setRemoteEngine.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            RemoteEngineSettings remoteEngineSettings =
                new RemoteEngineSettings(dialog, true, false);
            remoteEngineSettings.setVisible(true);
          }
        });
    chkUseJavaSSH.setSelected(Utils.getAnalysisEngineRemoteEngineData().useJavaSSH);
    setRemoteEngine.setEnabled(chkUseJavaSSH.isSelected());

    EngineCommandToolbarBounds toolbarBounds =
        engineCommandToolbarBounds(
            currentFontScale(),
            lblEngineCmd.getPreferredSize().width,
            btnGenerate.getPreferredSize().width,
            btnSavedEngine.getPreferredSize().width,
            chkUseJavaSSH.getPreferredSize().width,
            setRemoteEngine.getPreferredSize().width);
    btnGenerate.setBounds(toolbarBounds.generate);
    btnSavedEngine.setBounds(toolbarBounds.savedEngine);
    chkUseJavaSSH.setBounds(toolbarBounds.remoteEngine);
    setRemoteEngine.setBounds(toolbarBounds.remoteSettings);

    contentPane.add(chkUseJavaSSH);
    contentPane.add(setRemoteEngine);
    setLocationRelativeTo(Lizzie.frame != null ? Lizzie.frame : null);
    AccessibilitySupport.applyToTree(this);
    AccessibilitySupport.installEscapeToClose(getRootPane(), this);
    if (!displayCommand.message.isEmpty()) {
      String message = displayCommand.message;
      SwingUtilities.invokeLater(() -> Utils.showMsg(message));
    }
  }

  private static FontScale currentFontScale() {
    if (Lizzie.config.isFrameFontSmall()) {
      return FontScale.SMALL;
    }
    if (Lizzie.config.isFrameFontMiddle()) {
      return FontScale.MIDDLE;
    }
    return FontScale.LARGE;
  }

  static EngineCommandToolbarBounds engineCommandToolbarBounds(FontScale fontScale) {
    return engineCommandToolbarBounds(fontScale, 0, 0, 0, 0, 0);
  }

  static EngineCommandToolbarBounds engineCommandToolbarBounds(
      FontScale fontScale,
      int labelPreferredWidth,
      int generatePreferredWidth,
      int savedEnginePreferredWidth,
      int remoteEnginePreferredWidth,
      int remoteSettingsPreferredWidth) {
    int baseStart;
    int generateMinimum;
    int savedEngineMinimum;
    int remoteEngineMinimum;
    int remoteSettingsMinimum;
    switch (fontScale) {
      case SMALL:
        baseStart = 93;
        generateMinimum = 68;
        savedEngineMinimum = 66;
        remoteEngineMinimum = 80;
        remoteSettingsMinimum = 74;
        break;
      case MIDDLE:
        baseStart = 110;
        generateMinimum = 78;
        savedEngineMinimum = 82;
        remoteEngineMinimum = 90;
        remoteSettingsMinimum = 78;
        break;
      case LARGE:
      default:
        baseStart = 120;
        generateMinimum = 92;
        savedEngineMinimum = 98;
        remoteEngineMinimum = 110;
        remoteSettingsMinimum = 94;
        break;
    }

    int generateWidth = Math.max(generateMinimum, generatePreferredWidth + 8);
    int savedEngineWidth = Math.max(savedEngineMinimum, savedEnginePreferredWidth + 8);
    int remoteEngineWidth = Math.max(remoteEngineMinimum, remoteEnginePreferredWidth + 8);
    int remoteSettingsWidth = Math.max(remoteSettingsMinimum, remoteSettingsPreferredWidth + 8);
    int gap = 6;
    int firstRowWidth = generateWidth + savedEngineWidth + gap;
    int startX = Math.max(baseStart, 10 + labelPreferredWidth + 8);
    startX = Math.min(startX, Math.max(10, 576 - firstRowWidth));

    Rectangle generate = new Rectangle(startX, 1, generateWidth, 23);
    Rectangle savedEngine =
        new Rectangle(generate.x + generate.width + gap, 1, savedEngineWidth, 23);
    Rectangle remoteEngine = new Rectangle(10, 27, remoteEngineWidth, 22);
    Rectangle remoteSettings =
        new Rectangle(remoteEngine.x + remoteEngine.width + gap, 27, remoteSettingsWidth, 23);
    return new EngineCommandToolbarBounds(generate, savedEngine, remoteEngine, remoteSettings);
  }

  private DisplayCommand resolveAnalysisEngineCommandForDisplay() {
    if (!Lizzie.config.analysisEngineCommandCustomized) {
      AnalysisEngineCommandHelper.Result result =
          AnalysisEngineCommandHelper.fromCurrentEngine(
              Utils.getEngineData(), currentMainEngineIndex());
      if (result.isSuccess()) {
        return new DisplayCommand(
            result.getCommand(), result.generatedConfig() ? result.getMessage() : "");
      }
    }
    return new DisplayCommand(Lizzie.config.analysisEngineCommand, "");
  }

  private static int currentMainEngineIndex() {
    if (Lizzie.leelaz != null && Lizzie.leelaz.currentEngineN() >= 0) {
      return Lizzie.leelaz.currentEngineN();
    }
    return EngineManager.currentEngineNo;
  }

  private void chooseSavedEngineCommand() {
    ArrayList<EngineData> engines = Utils.getEngineData();
    if (engines.isEmpty()) {
      Utils.showMsg(Lizzie.resourceBundle.getString("AnalysisSettings.noSavedEngines"));
      return;
    }
    SavedEngineChoice[] choices = new SavedEngineChoice[engines.size()];
    for (int i = 0; i < engines.size(); i++) {
      choices[i] = new SavedEngineChoice(engines.get(i), i + 1);
    }
    SavedEngineChoice selected =
        (SavedEngineChoice)
            JOptionPane.showInputDialog(
                dialog,
                Lizzie.resourceBundle.getString("NewAnaGameDialog.chooseEngine"),
                Lizzie.resourceBundle.getString("NewAnaGameDialog.chooseEngine"),
                JOptionPane.PLAIN_MESSAGE,
                null,
                choices,
                choices[0]);
    if (selected == null) {
      return;
    }
    AnalysisEngineCommandHelper.Result result =
        AnalysisEngineCommandHelper.fromSavedEngine(selected.engine);
    if (!result.isSuccess()) {
      Utils.showMsg(result.getMessage());
      return;
    }
    engineCmd.setText(result.getCommand());
    engineCommandExplicitlyChanged = true;
    if (result.generatedConfig()) {
      Utils.showMsg(result.getMessage());
    }
  }

  private static final class DisplayCommand {
    final String command;
    final String message;

    private DisplayCommand(String command, String message) {
      this.command = command;
      this.message = message;
    }
  }

  private static final class SavedEngineChoice {
    final EngineData engine;
    final String displayName;

    private SavedEngineChoice(EngineData engine, int index) {
      this.engine = engine;
      this.displayName =
          engine.name == null || engine.name.trim().isEmpty() ? "Engine " + index : engine.name;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }

  private class LinkLabel extends JTextPane {
    public LinkLabel(String text) {
      super();

      HTMLDocument htmlDoc;
      HtmlKit htmlKit;
      StyleSheet htmlStyle;

      htmlKit = new HtmlKit();
      htmlDoc = (HTMLDocument) htmlKit.createDefaultDocument();
      htmlStyle = htmlKit.getStyleSheet();
      String style =
          "body {background:#"
              + String.format(
                  "%02x%02x%02x",
                  Lizzie.config.commentBackgroundColor.getRed(),
                  Lizzie.config.commentBackgroundColor.getGreen(),
                  Lizzie.config.commentBackgroundColor.getBlue())
              + "; color:#"
              + String.format(
                  "%02x%02x%02x",
                  Lizzie.config.commentFontColor.getRed(),
                  Lizzie.config.commentFontColor.getGreen(),
                  Lizzie.config.commentFontColor.getBlue())
              + "; font-family:"
              + Lizzie.config.fontName
              + ", Consolas, Menlo, Monaco, 'Ubuntu Mono', monospace;"
              + ("font-size:" + Config.frameFontSize)
              + "}";
      htmlStyle.addRule(style);
      // setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
      setEditorKit(htmlKit);
      setDocument(htmlDoc);
      setText(text);
      setEditable(false);
      setOpaque(false);
      putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
      addHyperlinkListener(
          new HyperlinkListener() {
            public void hyperlinkUpdate(HyperlinkEvent e) {
              if (HyperlinkEvent.EventType.ACTIVATED.equals(e.getEventType())) {
                if (Desktop.isDesktopSupported()) {
                  try {
                    Desktop.getDesktop().browse(e.getURL().toURI());
                  } catch (Exception ex) {
                  }
                }
              }
            }
          });
    }
  }

  public void saveConfig() {
    String previousCommand = Lizzie.config.analysisEngineCommand;
    RemoteEngineData remoteEngineData = Utils.getAnalysisEngineRemoteEngineData();
    remoteEngineData.useJavaSSH = chkUseJavaSSH.isSelected();
    Utils.saveAnalysisEngineRemoteEngineData(remoteEngineData);
    String newCommand = engineCmd.getText().trim();
    Lizzie.config.analysisEngineCommand = newCommand;
    if (engineCommandExplicitlyChanged || !newCommand.equals(originalEngineCommand)) {
      Lizzie.config.analysisEngineCommandCustomized = true;
    }
    Lizzie.config.uiConfig.put(
        "analysis-engine-command-customized", Lizzie.config.analysisEngineCommandCustomized);
    if (context == Context.BATCH) {
      Lizzie.config.batchAnalysisPlayouts =
          Utils.parseTextToInt(txtMaxVisits, Lizzie.config.batchAnalysisPlayouts);
      Lizzie.config.uiConfig.put("batch-analysis-playouts", Lizzie.config.batchAnalysisPlayouts);
    } else {
      Lizzie.config.analysisMaxVisits =
          Utils.parseTextToInt(txtMaxVisits, Lizzie.config.analysisMaxVisits);
      Lizzie.config.uiConfig.put("analysis-max-visits", Lizzie.config.analysisMaxVisits);
    }
    //    if (Lizzie.config.analysisMaxVisits == 1)
    //      Utils.showMsg(
    //          Lizzie.resourceBundle.getString(
    //              "AnalysisSettings.maxVisits1Hint")); // ("单步计算量最小为2,当前设置为1,将自动调整为2");
    if (Lizzie.config.analysisMaxVisits <= 1) Lizzie.config.analysisMaxVisits = 1;
    Lizzie.config.analysisAutoQuit = chkAutoExit.isSelected();
    Lizzie.config.analysisEnginePreLoad = chkPreLoad.isSelected();
    Lizzie.config.analysisAlwaysOverride = chkAlwaysOverride.isSelected();
    Lizzie.config.analysisUseCurrentRules = rdoUseCurrentRules.isSelected();
    Lizzie.config.uiConfig.put("analysis-auto-quit", Lizzie.config.analysisAutoQuit);
    Lizzie.config.uiConfig.put("analysis-engine-preload", Lizzie.config.analysisEnginePreLoad);
    Lizzie.config.uiConfig.put("analysis-always-override", Lizzie.config.analysisAlwaysOverride);
    Lizzie.config.uiConfig.put("analysis-use-current-rules", Lizzie.config.analysisUseCurrentRules);
    Lizzie.config.uiConfig.put("analysis-engine-command", Lizzie.config.analysisEngineCommand);
    if (!previousCommand.equals(Lizzie.config.analysisEngineCommand)
        && Lizzie.frame != null
        && Lizzie.frame.analysisEngine != null
        && !Lizzie.frame.analysisEngine.isAnalysisInProgress()) {
      Lizzie.frame.destroyAnalysisEngine();
    }
  }
}
