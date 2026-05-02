/*
 * Created by JFormDesigner on Wed Apr 04 22:17:33 CEST 2018
 */

package featurecat.lizzie.gui;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * @author unknown
 */
public class NewEngineGameDialog extends JDialog {
  // create formatters
  public static final DecimalFormat FORMAT_KOMI = new DecimalFormat("#0.0");
  public static final DecimalFormat FORMAT_HANDICAP = new DecimalFormat("0");

  // public static final JFontLabel PLACEHOLDER = new JFontLabel("");

  static {
    FORMAT_HANDICAP.setMaximumIntegerDigits(99999);
  }

  private PanelWithToolTips dialogPane = new PanelWithToolTips();
  private PanelWithToolTips contentPanel = new PanelWithToolTips();
  private PanelWithToolTips buttonBar = new PanelWithToolTips();
  private JFontButton okButton = new JFontButton();
  private JFontButton btnConfig;

  // private JCheckBox checkBoxPlayerIsBlack;
  private JFontTextField textFieldKomi;
  private JTextField textFieldHandicap;
  //  private JFontTextField textFieldDelay;

  private JFontTextField txtresignSettingBlackMinMove;
  private JFontTextField txtresignSettingBlack;
  private JFontTextField txtresignSettingBlack2;

  private JFontTextField txtresignSettingWhiteMinMove;
  private JFontTextField txtresignSettingWhite;
  private JFontTextField txtresignSettingWhite2;

  public JComboBox<String> enginePkBlack;
  public JComboBox<String> enginePkWhite;
  private JComboBox<String> cbxRandomSgf;

  private JCheckBox chkDisableWRNInGame;
  private JCheckBox chkUseAdvanceTime;
  private JCheckBox chkSGFstart;
  private JFontCheckBox chkContinuePlay;
  private JFontButton btnSGFstart;
  // private ActionListener chkEnginePkContinueListener;
  // private ActionListener chkBatchGameListener;
  private JFontLabel lblsgf =
      new JFontLabel(Lizzie.resourceBundle.getString("NewEngineGameDialog.lblsgf").trim());

  private boolean cancelled = true;
  private GameInfo gameInfo;

  public NewEngineGameDialog(Window owner) {
    super(owner);
    // Lizzie.frame.removeInput();
    initComponents();
  }

  // private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  private JFontTextField txtBlackAdvanceTime;
  private JFontTextField txtWhiteAdvanceTime;
  private Window thisDialog = this;

  private int dialogWidth() {
    if (Lizzie.config.isFrameFontSmall()) return 680;
    if (Lizzie.config.isFrameFontMiddle()) return 720;
    return 760;
  }

  private int dialogHeight() {
    if (Lizzie.config.isFrameFontSmall()) return 520;
    if (Lizzie.config.isFrameFontMiddle()) return 560;
    return 600;
  }

  private int controlHeight() {
    if (Lizzie.config.isFrameFontSmall()) return 24;
    if (Lizzie.config.isFrameFontMiddle()) return 26;
    return 28;
  }

  private void initComponents() {
    // if (Lizzie.config.showHiddenYzy) setMinimumSize(new Dimension(380, 330));
    // else
    // setMinimumSize(new Dimension(580, 530));
    setTitle(Lizzie.resourceBundle.getString("NewEngineGameDialog.title")); // "引擎对战");
    setResizable(true);
    setModal(true);
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
    } catch (IOException e) {
      e.printStackTrace();
    }
    Container contentPane = getContentPane();
    contentPane.setLayout(new BorderLayout());

    initDialogPane(contentPane);
    setAlwaysOnTop(Lizzie.frame.isAlwaysOnTop());
    pack();
    Rectangle usableBounds = getUsableScreenBounds();
    Dimension targetSize =
        new Dimension(
            Math.min(dialogWidth(), Math.max(720, usableBounds.width - 40)),
            Math.min(dialogHeight(), Math.max(500, usableBounds.height - 40)));
    setMinimumSize(
        new Dimension(Math.min(720, targetSize.width), Math.min(500, targetSize.height)));
    setSize(targetSize);
    setLocationRelativeTo(getOwner());
    adjustLocationForScaledDisplay();
    keepDialogOnScreen(usableBounds);
  }

  private Rectangle getUsableScreenBounds() {
    GraphicsConfiguration configuration =
        getOwner() != null ? getOwner().getGraphicsConfiguration() : getGraphicsConfiguration();
    if (configuration == null) {
      return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }
    Rectangle bounds = configuration.getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
    return new Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom);
  }

  private void keepDialogOnScreen(Rectangle usableBounds) {
    int maxX = usableBounds.x + usableBounds.width - getWidth();
    int maxY = usableBounds.y + usableBounds.height - getHeight();
    int x = Math.max(usableBounds.x, Math.min(getX(), maxX));
    int y = Math.max(usableBounds.y, Math.min(getY(), maxY));
    setLocation(x, y);
  }

  private void adjustLocationForScaledDisplay() {
    GraphicsConfiguration configuration = getGraphicsConfiguration();
    if (configuration == null) {
      return;
    }
    java.awt.geom.AffineTransform transform = configuration.getDefaultTransform();
    double scaleX = transform.getScaleX();
    double scaleY = transform.getScaleY();
    if (scaleX <= 1.0 && scaleY <= 1.0) {
      return;
    }
    int xOffset = (int) Math.round(getWidth() * (scaleX - 1.0) / 2.0);
    int yOffset = (int) Math.round(getHeight() * (scaleY - 1.0) / 2.0);
    setLocation(getX() - xOffset, getY() - yOffset);
  }

  private void initDialogPane(Container contentPane) {
    dialogPane.setBorder(new EmptyBorder(0, 0, 0, 0));
    dialogPane.setLayout(new BorderLayout());

    initContentPanel();
    initButtonBar();

    contentPane.add(dialogPane, BorderLayout.CENTER);
  }

  private void initContentPanel() {
    contentPanel.setLayout(new BorderLayout());
    contentPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

    JPanel mainPanel = new ViewportWidthPanel(new GridBagLayout());
    mainPanel.setBorder(new EmptyBorder(16, 18, 12, 18));

    JScrollPane scrollPane = new JScrollPane(mainPanel);
    scrollPane.setBorder(null);
    scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scrollPane.getVerticalScrollBar().setUnitIncrement(Math.max(16, controlHeight()));
    scrollPane.setPreferredSize(new Dimension(dialogWidth() - 28, dialogHeight() - 112));
    contentPanel.add(scrollPane, BorderLayout.CENTER);
    dialogPane.add(contentPanel, BorderLayout.CENTER);

    JTextArea resignThresoldHint =
        new JTextArea(Lizzie.resourceBundle.getString("EnginePkConfig.lblresignGenmove"));
    resignThresoldHint.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    resignThresoldHint.setLineWrap(true);
    resignThresoldHint.setWrapStyleWord(true);
    resignThresoldHint.setFocusable(false);
    resignThresoldHint.setEditable(false);
    resignThresoldHint.setOpaque(false);

    JFontLabel lblresignSettingBlack =
        new JFontLabel(
            Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSettingBlack")); // ("认输阈值:");
    JFontLabel lblresignSettingBlackConsistent =
        new JFontLabel(
            Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSettingConsistent"));
    JFontLabel lblresignSettingBlack2 =
        new JFontLabel(
            Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSetting2")); // ("手胜率低于");
    JFontLabel lblresignSettingBlack3 =
        new JFontLabel(Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSetting3"));
    JFontLabel lblresignSettingBlack4 = new JFontLabel("%");

    txtresignSettingBlack = new JFontTextField();
    txtresignSettingBlack.setDocument(new IntDocument());
    txtresignSettingBlack2 = new JFontTextField();
    txtresignSettingBlack2.setDocument(new DoubleDocument());
    txtresignSettingBlackMinMove = new JFontTextField();
    txtresignSettingBlackMinMove.setDocument(new IntDocument());

    JFontLabel lblresignSettingWhite =
        new JFontLabel(Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSettingWhite"));
    JFontLabel lblresignSettingWhiteConsistent =
        new JFontLabel(
            Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSettingConsistent"));
    JFontLabel lblresignSettingWhite2 =
        new JFontLabel(Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSetting2"));
    JFontLabel lblresignSettingWhite3 =
        new JFontLabel(Lizzie.resourceBundle.getString("EnginePkConfig.lblresignSetting3"));
    JFontLabel lblresignSettingWhite4 = new JFontLabel("%");

    txtresignSettingWhite = new JFontTextField();
    txtresignSettingWhite.setDocument(new IntDocument());
    txtresignSettingWhite2 = new JFontTextField();
    txtresignSettingWhite2.setDocument(new DoubleDocument());
    txtresignSettingWhiteMinMove = new JFontTextField();
    txtresignSettingWhiteMinMove.setDocument(new IntDocument());

    txtresignSettingBlack.setText(String.valueOf(Lizzie.config.firstEngineResignMoveCounts));
    txtresignSettingBlack2.setText(String.valueOf(Lizzie.config.firstEngineResignWinrate));
    txtresignSettingBlackMinMove.setText(String.valueOf(Lizzie.config.firstEngineMinMove));

    txtresignSettingWhite.setText(String.valueOf(Lizzie.config.secondEngineResignMoveCounts));
    txtresignSettingWhite2.setText(String.valueOf(Lizzie.config.secondEngineResignWinrate));
    txtresignSettingWhiteMinMove.setText(String.valueOf(Lizzie.config.secondEngineMinMove));

    textFieldKomi = new JFontTextField();
    textFieldKomi.setDocument(new KomiDocument(true));
    NumberFormat nf = NumberFormat.getIntegerInstance();
    nf.setGroupingUsed(false);
    textFieldHandicap = new JTextField();
    textFieldHandicap.setDocument(new IntDocument());
    textFieldHandicap.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    textFieldHandicap.addPropertyChangeListener(evt -> modifyHandicap());

    enginePkBlack = LizzieFrame.toolbar.enginePkBlack;
    enginePkBlack.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    enginePkWhite = LizzieFrame.toolbar.enginePkWhite;
    enginePkWhite.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    btnConfig =
        new JFontButton(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.btnConfig")); // ("更多设置");
    btnConfig.setMargin(new Insets(0, 0, 0, 0));

    JFontLabel lblB =
        new JFontLabel(Lizzie.resourceBundle.getString("NewEngineGameDialog.lblB")); // ("黑方设置");
    lblB.setHorizontalAlignment(JLabel.CENTER);
    JFontLabel lblW =
        new JFontLabel(Lizzie.resourceBundle.getString("NewEngineGameDialog.lblW")); // ("白方设置");
    lblW.setHorizontalAlignment(JLabel.CENTER);
    lblB.setFont(lblB.getFont().deriveFont(Font.BOLD));
    lblW.setFont(lblW.getFont().deriveFont(Font.BOLD));

    JFontLabel lblengine =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblengine")); // ("选择引擎");
    if (enginePkBlack.getSelectedIndex() == enginePkWhite.getSelectedIndex())
      enginePkWhite.setSelectedIndex(
          enginePkBlack.getSelectedIndex() <= enginePkWhite.getItemCount() - 2
              ? (enginePkWhite.getItemCount() > enginePkBlack.getSelectedIndex() + 1
                  ? enginePkBlack.getSelectedIndex() + 1
                  : enginePkBlack.getSelectedIndex())
              : (enginePkBlack.getSelectedIndex() - 1 >= 0
                  ? enginePkBlack.getSelectedIndex() - 1
                  : enginePkBlack.getSelectedIndex()));

    JFontLabel lblTime =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblTime")); // ("每手时间(秒)");
    if (!LizzieFrame.toolbar.chkenginePkTime.isSelected()) {
      LizzieFrame.toolbar.txtenginePkTime.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkTimeWhite.setEnabled(false);
    }

    JFontLabel lblPlayout =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblPlayout")); // ("总计算量");
    if (!LizzieFrame.toolbar.chkenginePkPlayouts.isSelected()) {
      LizzieFrame.toolbar.txtenginePkPlayputs.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkPlayputsWhite.setEnabled(false);
    }

    JFontLabel lblFirstPlayout =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblFirstPlayout")); // ("首位计算量");
    if (!LizzieFrame.toolbar.chkenginePkFirstPlayputs.isSelected()) {
      LizzieFrame.toolbar.txtenginePkFirstPlayputs.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite.setEnabled(false);
    }

    JFontLabel komi = new JFontLabel(Lizzie.resourceBundle.getString("NewEngineGameDialog.komi"));
    JFontLabel handicap =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.handicap")); // ("让子(仅支持19路棋盘)");

    chkContinuePlay =
        new JFontCheckBox(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblContinue")); // ("当前局面续弈");
    // LizzieFrame.toolbar.chkenginePkContinue.setBounds(5, 270, 20, 20);
    // contentPanel.add(LizzieFrame.toolbar.chkenginePkContinue);
    chkContinuePlay.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            LizzieFrame.toolbar.chkenginePkContinue.setSelected(chkContinuePlay.isSelected());
            if (chkContinuePlay.isSelected()) {
              chkSGFstart.setSelected(false);
              Lizzie.config.chkEngineSgfStart = false;
              btnSGFstart.setEnabled(false);
              cbxRandomSgf.setEnabled(false);
              handicap.setEnabled(false);
              textFieldHandicap.setEnabled(false);
            } else {
              handicap.setEnabled(true);
              textFieldHandicap.setEnabled(true);
              if (chkSGFstart.isSelected()) {
                btnSGFstart.setEnabled(true);
                cbxRandomSgf.setEnabled(true);
              } else {
                btnSGFstart.setEnabled(false);
                cbxRandomSgf.setEnabled(false);
              }
            }
          }
        });

    //    chkBatchGameListener =
    //        new ActionListener() {
    //          public void actionPerformed(ActionEvent e) {
    //            if (LizzieFrame.toolbar.chkenginePkBatch.isSelected()) {
    //              chkSGFstart.setEnabled(true);
    //              lblsgf.setEnabled(true);
    //            } else {
    //              chkSGFstart.setEnabled(false);
    //              lblsgf.setEnabled(false);
    //            }
    //          }
    //        };
    // LizzieFrame.toolbar.chkenginePkContinue.addActionListener(chkEnginePkContinueListener);
    // LizzieFrame.toolbar.chkenginePkBatch.addActionListener(chkBatchGameListener);

    JFontLabel lblBatchGame =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblBatchGame").trim()); // ("多盘");

    textFieldKomi.setEnabled(true);

    JFontLabel lblAdvanceTime =
        new JFontLabel(
            Lizzie.resourceBundle.getString(
                "NewEngineGameDialog.lblAdvanceTime")); // ("高级时间设置"); // $NON-NLS-1$

    txtBlackAdvanceTime = new JFontTextField();
    txtBlackAdvanceTime.setColumns(10);

    txtWhiteAdvanceTime = new JFontTextField();
    txtWhiteAdvanceTime.setColumns(10);

    txtBlackAdvanceTime.setText(Lizzie.config.advanceBlackTimeTxt);
    txtWhiteAdvanceTime.setText(Lizzie.config.advanceWhiteTimeTxt);
    ImageIcon iconSettings = new ImageIcon();
    try {
      iconSettings.setImage(ImageIO.read(getClass().getResourceAsStream("/assets/settings.png")));
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    JFontButton aboutAdvanceTimeSettings = new JFontButton(iconSettings); // $NON-NLS-1$
    aboutAdvanceTimeSettings.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            Utils.showHtmlMessageModal(
                Lizzie.resourceBundle.getString("AdvanceTimeSettings.title"),
                Lizzie.resourceBundle.getString("AdvanceTimeSettings.describe"),
                thisDialog);
          }
        });
    aboutAdvanceTimeSettings.setPreferredSize(new Dimension(controlHeight(), controlHeight()));
    aboutAdvanceTimeSettings.setFocusable(false);

    chkUseAdvanceTime = new JFontCheckBox(); // $NON-NLS-1$
    if (!LizzieFrame.toolbar.isGenmoveToolbar) chkUseAdvanceTime.setEnabled(false);

    chkUseAdvanceTime.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            // TODO Auto-generated method stub
            Lizzie.config.pkAdvanceTimeSettings = chkUseAdvanceTime.isSelected();
            if (Lizzie.config.pkAdvanceTimeSettings) {
              LizzieFrame.toolbar.chkenginePkTime.setEnabled(false);
              LizzieFrame.toolbar.txtenginePkTime.setEnabled(false);
              LizzieFrame.toolbar.txtenginePkTimeWhite.setEnabled(false);
              txtBlackAdvanceTime.setEnabled(true);
              txtWhiteAdvanceTime.setEnabled(true);
            } else {
              LizzieFrame.toolbar.chkenginePkTime.setEnabled(true);
              if (LizzieFrame.toolbar.chkenginePkTime.isSelected()) {
                LizzieFrame.toolbar.txtenginePkTime.setEnabled(true);
                LizzieFrame.toolbar.txtenginePkTimeWhite.setEnabled(true);
              }
              txtBlackAdvanceTime.setEnabled(false);
              txtWhiteAdvanceTime.setEnabled(false);
            }
            Lizzie.config.uiConfig.put(
                "pk-advance-time-settings", Lizzie.config.pkAdvanceTimeSettings);
          }
        });
    chkUseAdvanceTime.setSelected(Lizzie.config.pkAdvanceTimeSettings);

    JCheckBox checkBoxAllowPonder =
        new JFontCheckBox(
            Lizzie.resourceBundle.getString(
                "NewEngineGameDialog.checkBoxAllowPonder")); // ("允许后台计算(同一台电脑对战时不可勾选)");
    checkBoxAllowPonder.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (checkBoxAllowPonder.isSelected()) Lizzie.config.enginePkPonder = true;
            else Lizzie.config.enginePkPonder = false;
            Lizzie.config.uiConfig.put("engine-pk-ponder", Lizzie.config.enginePkPonder);
          }
        });
    checkBoxAllowPonder.setSelected(Lizzie.config.enginePkPonder);

    chkDisableWRNInGame =
        new JFontCheckBox(Lizzie.resourceBundle.getString("NewAnaGameDialog.lblDisableWRNInGame"));
    chkDisableWRNInGame.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            Lizzie.config.disableWRNInGame = chkDisableWRNInGame.isSelected();
            Lizzie.config.uiConfig.put("disable-wrn-in-game", Lizzie.config.disableWRNInGame);
          }
        });
    chkDisableWRNInGame.setSelected(Lizzie.config.disableWRNInGame);
    chkDisableWRNInGame.setSelected(Lizzie.config.disableWRNInGame);
    chkDisableWRNInGame.setVisible(!LizzieFrame.toolbar.isGenmoveToolbar);

    chkSGFstart = new JFontCheckBox();
    chkSGFstart.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (chkSGFstart.isSelected()) {
              chkContinuePlay.setSelected(false);
              Lizzie.config.chkEngineSgfStart = true;
              btnSGFstart.setEnabled(true);
              cbxRandomSgf.setEnabled(true);
              handicap.setEnabled(false);
              textFieldHandicap.setEnabled(false);
            } else {
              Lizzie.config.chkEngineSgfStart = false;
              btnSGFstart.setEnabled(false);
              cbxRandomSgf.setEnabled(false);
              handicap.setEnabled(true);
              textFieldHandicap.setEnabled(true);
            }
          }
        });
    // chkSGFstart.setEnabled(LizzieFrame.toolbar.chkenginePkBatch.isSelected());

    btnSGFstart =
        new JFontButton(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.btnSGFstart")); // ("多选SGF");
    btnSGFstart.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            Lizzie.frame.openSgfStart();
          }
        });
    btnSGFstart.setMargin(new Insets(0, 0, 0, 0));

    cbxRandomSgf = new JFontComboBox<String>();
    cbxRandomSgf.addItem(
        Lizzie.resourceBundle.getString("NewEngineGameDialog.cbxRandomSgf1")); // ("顺序");
    cbxRandomSgf.addItem(
        Lizzie.resourceBundle.getString("NewEngineGameDialog.cbxRandomSgf2")); // ("随机");
    cbxRandomSgf.addItemListener(
        new ItemListener() {
          public void itemStateChanged(final ItemEvent e) {
            if (cbxRandomSgf.getSelectedIndex() == 0) {
              Lizzie.config.engineSgfStartRandom = false;
            } else {
              Lizzie.config.engineSgfStartRandom = true;
            }
            Lizzie.config.uiConfig.put("engine-sgf-random", Lizzie.config.engineSgfStartRandom);
          }
        });
    cbxRandomSgf.setSelectedIndex(Lizzie.config.engineSgfStartRandom ? 1 : 0);

    int engineColumnWidth =
        Lizzie.config.isFrameFontSmall() ? 150 : (Lizzie.config.isFrameFontMiddle() ? 170 : 190);
    setPreferredControlWidth(enginePkBlack, engineColumnWidth);
    setPreferredControlWidth(enginePkWhite, engineColumnWidth);
    setPreferredControlWidth(LizzieFrame.toolbar.txtenginePkTime, engineColumnWidth);
    setPreferredControlWidth(LizzieFrame.toolbar.txtenginePkTimeWhite, engineColumnWidth);
    setPreferredControlWidth(txtBlackAdvanceTime, engineColumnWidth);
    setPreferredControlWidth(txtWhiteAdvanceTime, engineColumnWidth);
    setPreferredControlWidth(LizzieFrame.toolbar.txtenginePkPlayputs, engineColumnWidth);
    setPreferredControlWidth(LizzieFrame.toolbar.txtenginePkPlayputsWhite, engineColumnWidth);
    setPreferredControlWidth(LizzieFrame.toolbar.txtenginePkFirstPlayputs, engineColumnWidth);
    setPreferredControlWidth(LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite, engineColumnWidth);

    JPanel enginePanel = createSectionPanel();
    addEngineHeader(enginePanel, lblB, lblW);
    addEngineRow(enginePanel, 1, lblengine, null, enginePkBlack, enginePkWhite);
    addEngineRow(
        enginePanel,
        2,
        lblTime,
        LizzieFrame.toolbar.chkenginePkTime,
        LizzieFrame.toolbar.txtenginePkTime,
        LizzieFrame.toolbar.txtenginePkTimeWhite);
    addEngineRow(
        enginePanel,
        3,
        inlinePanel(lblAdvanceTime, aboutAdvanceTimeSettings),
        chkUseAdvanceTime,
        txtBlackAdvanceTime,
        txtWhiteAdvanceTime);
    addEngineRow(
        enginePanel,
        4,
        lblPlayout,
        LizzieFrame.toolbar.chkenginePkPlayouts,
        LizzieFrame.toolbar.txtenginePkPlayputs,
        LizzieFrame.toolbar.txtenginePkPlayputsWhite);
    addEngineRow(
        enginePanel,
        5,
        lblFirstPlayout,
        LizzieFrame.toolbar.chkenginePkFirstPlayputs,
        LizzieFrame.toolbar.txtenginePkFirstPlayputs,
        LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite);
    addMainSection(mainPanel, enginePanel, 0);

    JPanel resignSection = createSectionPanel();
    resignSection.setLayout(new BorderLayout());
    JPanel resignControls = plainPanel(new GridBagLayout());
    addResignRow(
        resignControls,
        0,
        lblresignSettingBlack,
        lblresignSettingBlackConsistent,
        txtresignSettingBlackMinMove,
        lblresignSettingBlack2,
        txtresignSettingBlack,
        lblresignSettingBlack3,
        txtresignSettingBlack2,
        lblresignSettingBlack4);
    addResignRow(
        resignControls,
        1,
        lblresignSettingWhite,
        lblresignSettingWhiteConsistent,
        txtresignSettingWhiteMinMove,
        lblresignSettingWhite2,
        txtresignSettingWhite,
        lblresignSettingWhite3,
        txtresignSettingWhite2,
        lblresignSettingWhite4);
    resignSection.add(resignControls, BorderLayout.CENTER);
    resignSection.add(resignThresoldHint, BorderLayout.NORTH);
    addMainSection(mainPanel, resignSection, 1);

    JPanel gamePanel = createSectionPanel();
    addGameSetupRows(gamePanel, komi, handicap, lblBatchGame);
    addMainSection(mainPanel, gamePanel, 2);

    JPanel optionsPanel = createSectionPanel();
    addFullWidth(optionsPanel, checkBoxAllowPonder, 0, new Insets(0, 0, 8, 0));
    addFullWidth(optionsPanel, chkDisableWRNInGame, 1, new Insets(0, 0, 0, 0));
    addMainSection(mainPanel, optionsPanel, 3);

    addMainFiller(mainPanel, 4);

    btnConfig.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            EnginePkConfig engineconfig = new EnginePkConfig(false);
            engineconfig.setVisible(true);
            engineconfig.setAlwaysOnTop(true);
            updateModeDependentControls(resignControls, resignThresoldHint);
          }
        });

    updateModeDependentControls(resignControls, resignThresoldHint);

    chkSGFstart.setSelected(Lizzie.config.chkEngineSgfStart);
    if (LizzieFrame.toolbar.chkenginePkContinue.isSelected()) {
      chkContinuePlay.setSelected(true);
      chkSGFstart.setSelected(false);
      btnSGFstart.setEnabled(false);
      cbxRandomSgf.setEnabled(false);
      textFieldHandicap.setEnabled(false);
    } else {
      chkContinuePlay.setSelected(false);
      textFieldHandicap.setEnabled(true);
      if (chkSGFstart.isSelected()) {
        btnSGFstart.setEnabled(true);
        cbxRandomSgf.setEnabled(true);
      } else {
        btnSGFstart.setEnabled(false);
        cbxRandomSgf.setEnabled(false);
      }
    }

    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            //  LizzieFrame.toolbar.chkenginePkContinue.removeActionListener(
            //      chkEnginePkContinueListener);
            //  LizzieFrame.toolbar.chkenginePkBatch.removeActionListener(chkBatchGameListener);
            resetFont();
          }
        });
    LizzieFrame.toolbar.txtenginePkPlayputs.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    LizzieFrame.toolbar.txtenginePkPlayputsWhite.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    LizzieFrame.toolbar.txtenginePkFirstPlayputs.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    LizzieFrame.toolbar.txtenginePkBatch.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    LizzieFrame.toolbar.txtenginePkTime.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    LizzieFrame.toolbar.txtenginePkTimeWhite.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
  }

  private JPanel createSectionPanel() {
    JPanel panel = plainPanel(new GridBagLayout());
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(220, 224, 230)),
            new EmptyBorder(12, 12, 12, 12)));
    return panel;
  }

  private JPanel plainPanel(LayoutManager layout) {
    JPanel panel = new JPanel(layout);
    panel.setOpaque(false);
    return panel;
  }

  private JPanel inlinePanel(Component... components) {
    JPanel panel = plainPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    for (Component component : components) {
      panel.add(component);
    }
    return panel;
  }

  private void addMainSection(JPanel mainPanel, JComponent section, int row) {
    GridBagConstraints constraints = baseConstraints(0, row);
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1.0;
    constraints.insets = new Insets(row == 0 ? 0 : 12, 0, 0, 0);
    mainPanel.add(section, constraints);
  }

  private void addMainFiller(JPanel mainPanel, int row) {
    GridBagConstraints constraints = baseConstraints(0, row);
    constraints.fill = GridBagConstraints.BOTH;
    constraints.weightx = 1.0;
    constraints.weighty = 1.0;
    mainPanel.add(Box.createGlue(), constraints);
  }

  private void addEngineHeader(JPanel panel, JComponent blackHeader, JComponent whiteHeader) {
    panel.add(Box.createHorizontalStrut(1), engineConstraints(0, 0, 1, 0.0));
    panel.add(Box.createHorizontalStrut(1), engineConstraints(1, 0, 1, 0.0));
    panel.add(blackHeader, engineConstraints(2, 0, 1, 0.5));
    panel.add(whiteHeader, engineConstraints(3, 0, 1, 0.5));
  }

  private void addEngineRow(
      JPanel panel,
      int row,
      JComponent label,
      JComponent enableControl,
      JComponent blackControl,
      JComponent whiteControl) {
    GridBagConstraints labelConstraints = engineConstraints(0, row, 1, 0.0);
    labelConstraints.anchor = GridBagConstraints.WEST;
    panel.add(label, labelConstraints);

    JComponent enableCell =
        enableControl != null ? enableControl : (JComponent) Box.createHorizontalStrut(24);
    GridBagConstraints enableConstraints = engineConstraints(1, row, 1, 0.0);
    enableConstraints.fill = GridBagConstraints.NONE;
    enableConstraints.anchor = GridBagConstraints.CENTER;
    panel.add(enableCell, enableConstraints);

    panel.add(blackControl, engineConstraints(2, row, 1, 0.5));
    panel.add(whiteControl, engineConstraints(3, row, 1, 0.5));
  }

  private GridBagConstraints engineConstraints(int x, int y, int width, double weightx) {
    GridBagConstraints constraints = baseConstraints(x, y);
    constraints.gridwidth = width;
    constraints.weightx = weightx;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.insets = new Insets(y == 0 ? 0 : 8, x == 0 ? 0 : 10, y == 0 ? 8 : 0, 0);
    return constraints;
  }

  private void addResignRow(
      JPanel panel,
      int row,
      JComponent title,
      JComponent minMoveLabel,
      JComponent minMoveField,
      JComponent consistentLabel,
      JComponent consistentField,
      JComponent belowLabel,
      JComponent belowField,
      JComponent percentLabel) {
    setPreferredControlWidth(minMoveField, 58);
    setPreferredControlWidth(consistentField, 58);
    setPreferredControlWidth(belowField, 68);

    panel.add(title, resignConstraints(0, row, 0.0, GridBagConstraints.HORIZONTAL));
    panel.add(minMoveLabel, resignConstraints(1, row, 0.0, GridBagConstraints.NONE));
    panel.add(minMoveField, resignConstraints(2, row, 0.0, GridBagConstraints.HORIZONTAL));
    panel.add(consistentLabel, resignConstraints(3, row, 0.0, GridBagConstraints.NONE));
    panel.add(consistentField, resignConstraints(4, row, 0.0, GridBagConstraints.HORIZONTAL));
    panel.add(belowLabel, resignConstraints(5, row, 0.0, GridBagConstraints.NONE));
    panel.add(belowField, resignConstraints(6, row, 0.0, GridBagConstraints.HORIZONTAL));
    panel.add(percentLabel, resignConstraints(7, row, 0.0, GridBagConstraints.NONE));
    panel.add(
        Box.createHorizontalGlue(), resignConstraints(8, row, 1.0, GridBagConstraints.HORIZONTAL));
  }

  private GridBagConstraints resignConstraints(int x, int y, double weightx, int fill) {
    GridBagConstraints constraints = baseConstraints(x, y);
    constraints.weightx = weightx;
    constraints.fill = fill;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(y == 0 ? 0 : 8, x == 0 ? 0 : 8, 0, 0);
    return constraints;
  }

  private void addGameSetupRows(
      JPanel panel, JComponent komi, JComponent handicap, JComponent batchLabel) {
    setPreferredControlWidth(textFieldKomi, 72);
    setPreferredControlWidth(textFieldHandicap, 72);
    setPreferredControlWidth(LizzieFrame.toolbar.txtenginePkBatch, 72);
    setPreferredControlWidth(cbxRandomSgf, 90);
    setPreferredControlWidth(btnSGFstart, 104);

    panel.add(komi, compactConstraints(0, 0, 0.0));
    panel.add(textFieldKomi, compactConstraints(1, 0, 0.0));
    panel.add(handicap, compactConstraints(2, 0, 0.0));
    panel.add(textFieldHandicap, compactConstraints(3, 0, 0.0));

    panel.add(batchLabel, compactConstraints(0, 1, 0.0));
    panel.add(LizzieFrame.toolbar.chkenginePkBatch, compactConstraints(1, 1, 0.0));
    GridBagConstraints batchFieldConstraints = compactConstraints(2, 1, 1.0);
    batchFieldConstraints.gridwidth = 2;
    panel.add(LizzieFrame.toolbar.txtenginePkBatch, batchFieldConstraints);

    GridBagConstraints continueConstraints = compactConstraints(0, 2, 0.0);
    continueConstraints.gridwidth = 2;
    panel.add(chkContinuePlay, continueConstraints);

    GridBagConstraints sgfToggleConstraints = compactConstraints(2, 2, 0.0);
    sgfToggleConstraints.gridwidth = 2;
    panel.add(inlinePanel(chkSGFstart, lblsgf), sgfToggleConstraints);

    panel.add(cbxRandomSgf, compactConstraints(0, 3, 0.0));
    GridBagConstraints buttonConstraints = compactConstraints(1, 3, 1.0);
    buttonConstraints.gridwidth = 3;
    panel.add(btnSGFstart, buttonConstraints);
  }

  private GridBagConstraints compactConstraints(int x, int y, double weightx) {
    GridBagConstraints constraints = baseConstraints(x, y);
    constraints.weightx = weightx;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(y == 0 ? 0 : 10, x == 0 ? 0 : 12, 0, 0);
    return constraints;
  }

  private void addFullWidth(JPanel panel, JComponent component, int row, Insets insets) {
    GridBagConstraints constraints = baseConstraints(0, row);
    constraints.fill = GridBagConstraints.HORIZONTAL;
    constraints.weightx = 1.0;
    constraints.insets = insets;
    panel.add(component, constraints);
  }

  private GridBagConstraints baseConstraints(int x, int y) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = x;
    constraints.gridy = y;
    constraints.anchor = GridBagConstraints.WEST;
    return constraints;
  }

  private void setPreferredControlWidth(JComponent component, int width) {
    Dimension preferred = component.getPreferredSize();
    component.setPreferredSize(new Dimension(width, Math.max(controlHeight(), preferred.height)));
    component.setMinimumSize(new Dimension(Math.min(width, 48), Math.max(22, preferred.height)));
  }

  private void updateModeDependentControls(JPanel resignControls, JTextArea resignThresoldHint) {
    boolean genmoveMode = LizzieFrame.toolbar.isGenmoveToolbar;
    chkDisableWRNInGame.setVisible(!genmoveMode);
    resignControls.setVisible(!genmoveMode);
    resignThresoldHint.setVisible(genmoveMode);
    chkUseAdvanceTime.setEnabled(genmoveMode);

    if (genmoveMode && Lizzie.config.pkAdvanceTimeSettings) {
      LizzieFrame.toolbar.chkenginePkTime.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkTime.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkTimeWhite.setEnabled(false);
      txtBlackAdvanceTime.setEnabled(true);
      txtWhiteAdvanceTime.setEnabled(true);
    } else {
      LizzieFrame.toolbar.chkenginePkTime.setEnabled(true);
      boolean standardTimeEnabled = LizzieFrame.toolbar.chkenginePkTime.isSelected();
      LizzieFrame.toolbar.txtenginePkTime.setEnabled(standardTimeEnabled);
      LizzieFrame.toolbar.txtenginePkTimeWhite.setEnabled(standardTimeEnabled);
      txtBlackAdvanceTime.setEnabled(false);
      txtWhiteAdvanceTime.setEnabled(false);
    }
    if (!genmoveMode) {
      chkUseAdvanceTime.setEnabled(false);
    }
    contentPanel.revalidate();
    contentPanel.repaint();
  }

  private static class ViewportWidthPanel extends JPanel implements Scrollable {
    ViewportWidthPanel(LayoutManager layout) {
      super(layout);
      setOpaque(false);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
      return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
      return Math.max(120, visibleRect.height - 48);
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

  //  private void togglePlayerIsBlack() {
  //    JFontTextField humanTextField = playerIsBlack() ? textFieldBlack : textFieldWhite;
  //    JFontTextField computerTextField = playerIsBlack() ? textFieldWhite : textFieldBlack;
  //
  //    humanTextField.setEnabled(true);
  //    humanTextField.setText(GameInfo.DEFAULT_NAME_HUMAN_PLAYER);
  //    computerTextField.setEnabled(false);
  //    computerTextField.setText(GameInfo.DEFAULT_NAME_CPU_PLAYER);
  //  }

  private void modifyHandicap() {
    try {
      int handicap = FORMAT_HANDICAP.parse(textFieldHandicap.getText()).intValue();
      if (handicap < 0) throw new IllegalArgumentException();

      // textFieldKomi.setText(FORMAT_KOMI.format(GameInfo.DEFAULT_KOMI));
    } catch (ParseException | RuntimeException e) {
      // do not correct user mistakes
    }
  }

  private void initButtonBar() {
    buttonBar.setBorder(new EmptyBorder(10, 18, 14, 18));
    buttonBar.setLayout(new GridBagLayout());
    ((GridBagLayout) buttonBar.getLayout()).columnWidths = new int[] {120, 0, 104};
    ((GridBagLayout) buttonBar.getLayout()).columnWeights = new double[] {0.0, 1.0, 0.0};

    // ---- okButton ----
    okButton.setText(Lizzie.resourceBundle.getString("NewEngineGameDialog.okButton")); // ("确定");
    okButton.setPreferredSize(new Dimension(104, controlHeight() + 4));
    okButton.addActionListener(e -> apply());

    int center = GridBagConstraints.CENTER;
    int both = GridBagConstraints.BOTH;
    if (btnConfig != null) {
      btnConfig.setPreferredSize(new Dimension(120, controlHeight() + 4));
      buttonBar.add(
          btnConfig,
          new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0, center, both, new Insets(0, 0, 0, 0), 0, 0));
    }
    buttonBar.add(
        okButton,
        new GridBagConstraints(2, 0, 1, 1, 0.0, 0.0, center, both, new Insets(0, 0, 0, 0), 0, 0));

    dialogPane.add(buttonBar, BorderLayout.SOUTH);
  }

  public void apply() {
    try {
      // validate data
      if (Lizzie.config.chkEngineSgfStart) {
        if (Lizzie.frame.enginePKSgfString == null || Lizzie.frame.enginePKSgfString.isEmpty()) {
          Utils.showMsg(Lizzie.resourceBundle.getString("NewEngineGameDialog.message"));
          return;
        } else if (Lizzie.frame.enginePKSgfString.size() > 1
            && !LizzieFrame.toolbar.chkenginePkBatch.isSelected())
          Utils.showMsg(Lizzie.resourceBundle.getString("NewEngineGameDialog.multiSgfNotBatch"));
      }
      //
      // LizzieFrame.toolbar.chkenginePkContinue.removeActionListener(chkEnginePkContinueListener);
      //  LizzieFrame.toolbar.chkenginePkBatch.removeActionListener(chkBatchGameListener);
      String playerBlack =
          Lizzie.engineManager.engineList.get(LizzieFrame.toolbar.engineBlackToolbar)
              .currentEnginename;
      String playerWhite =
          Lizzie.engineManager.engineList.get(LizzieFrame.toolbar.engineWhiteToolbar)
              .currentEnginename;
      double komi = 7.5;
      try {
        komi = FORMAT_KOMI.parse(textFieldKomi.getText()).doubleValue();
      } catch (NumberFormatException e) {
        e.printStackTrace();
      }
      int handicap =
          !textFieldHandicap.isEnabled()
              ? 0
              : FORMAT_HANDICAP.parse(textFieldHandicap.getText()).intValue();
      try {
        Lizzie.config.firstEngineResignMoveCounts =
            Integer.parseInt(txtresignSettingBlack.getText());
      } catch (NumberFormatException err) {
      }
      try {
        Lizzie.config.firstEngineResignWinrate =
            Double.parseDouble(txtresignSettingBlack2.getText());
      } catch (NumberFormatException err) {
      }
      try {
        Lizzie.config.firstEngineMinMove = Integer.parseInt(txtresignSettingBlackMinMove.getText());
      } catch (NumberFormatException err) {
      }

      try {
        Lizzie.config.secondEngineResignMoveCounts =
            Integer.parseInt(txtresignSettingWhite.getText());
      } catch (NumberFormatException err) {
      }
      try {
        Lizzie.config.secondEngineResignWinrate =
            Double.parseDouble(txtresignSettingWhite2.getText());
      } catch (NumberFormatException err) {
      }
      try {
        Lizzie.config.secondEngineMinMove =
            Integer.parseInt(txtresignSettingWhiteMinMove.getText());
      } catch (NumberFormatException err) {
      }
      Lizzie.config.newEngineGameHandicap = handicap;
      Lizzie.config.newEngineGameKomi = komi;
      Lizzie.config.uiConfig.put("new-engine-game-komi", Lizzie.config.newEngineGameKomi);
      Lizzie.config.uiConfig.put("new-engine-game-handicap", Lizzie.config.newEngineGameHandicap);

      Lizzie.config.uiConfig.put(
          "first-engine-resign-move-counts", Lizzie.config.firstEngineResignMoveCounts);
      Lizzie.config.uiConfig.put(
          "first-engine-resign-winrate", Lizzie.config.firstEngineResignWinrate);
      Lizzie.config.uiConfig.put("first-engine-min-move", Lizzie.config.firstEngineMinMove);

      Lizzie.config.uiConfig.put(
          "second-engine-resign-move-counts", Lizzie.config.secondEngineResignMoveCounts);
      Lizzie.config.uiConfig.put(
          "second-engine-resign-winrate", Lizzie.config.secondEngineResignWinrate);
      Lizzie.config.uiConfig.put("second-engine-min-move", Lizzie.config.secondEngineMinMove);

      if (Lizzie.config.pkAdvanceTimeSettings) {
        Lizzie.config.advanceBlackTimeTxt = txtBlackAdvanceTime.getText().trim();
        Lizzie.config.advanceWhiteTimeTxt = txtWhiteAdvanceTime.getText().trim();
        Lizzie.config.uiConfig.put("advance-black-time-txt", txtBlackAdvanceTime.getText().trim());
        Lizzie.config.uiConfig.put("advance-white-time-txt", txtWhiteAdvanceTime.getText().trim());
      }
      // apply new values
      gameInfo.setPlayerBlack(playerBlack);
      gameInfo.setPlayerWhite(playerWhite);
      gameInfo.setKomi(komi);
      // gameInfo.changeKomi();
      gameInfo.setHandicap(handicap);

      // close window
      cancelled = false;

      resetFont();

      if (handicap >= 2 && Board.boardWidth == 19 && Board.boardHeight == 19) {
        Lizzie.board.getHistory().clear();
        placeHandicap(handicap);
        LizzieFrame.toolbar.isEngineGameHandicapToolbar = true;
      } else LizzieFrame.toolbar.isEngineGameHandicapToolbar = false;

      Lizzie.board.getHistory().setGameInfo(gameInfo);

      LizzieFrame.toolbar.chkenginePk.setSelected(true);
      if (LizzieFrame.toolbar.startEngineGame()) setVisible(false);
    } catch (ParseException e) {
      // hide input mistakes.
    }
  }

  private void resetFont() {
    LizzieFrame.toolbar.enginePkBlack.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.enginePkWhite.setFont(new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.txtenginePkPlayputs.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.txtenginePkPlayputsWhite.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.txtenginePkFirstPlayputs.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.txtenginePkBatch.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.txtenginePkTime.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
    LizzieFrame.toolbar.txtenginePkTimeWhite.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, 12));
  }

  private void placeHandicap(int handicap) {
    // TODO Auto-generated method stub
    switch (handicap) {
      case 2:
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        break;
      case 3:
        Lizzie.board.getHistory().place(3, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        break;
      case 4:
        Lizzie.board.getHistory().place(3, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 15, Stone.BLACK);
        break;
      case 5:
        Lizzie.board.getHistory().place(3, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(9, 9, Stone.BLACK);
        break;
      case 6:
        Lizzie.board.getHistory().place(3, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 9, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 9, Stone.BLACK);
        break;
      case 7:
        Lizzie.board.getHistory().place(3, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 9, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 9, Stone.BLACK);
        Lizzie.board.getHistory().place(9, 9, Stone.BLACK);
        break;
      case 8:
        Lizzie.board.getHistory().place(3, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(9, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(9, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 9, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 9, Stone.BLACK);
        break;
      case 9:
        Lizzie.board.getHistory().place(3, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(9, 3, Stone.BLACK);
        Lizzie.board.getHistory().place(9, 15, Stone.BLACK);
        Lizzie.board.getHistory().place(3, 9, Stone.BLACK);
        Lizzie.board.getHistory().place(15, 9, Stone.BLACK);
        Lizzie.board.getHistory().place(9, 9, Stone.BLACK);
        break;
    }
  }

  public void setGameInfo(GameInfo gameInfo) {
    this.gameInfo = gameInfo;

    textFieldHandicap.setText(String.valueOf(Lizzie.config.newEngineGameHandicap));
    textFieldKomi.setText(String.valueOf(Lizzie.config.newEngineGameKomi));

    // update player names
    // togglePlayerIsBlack();
  }

  //  public boolean playerIsBlack() {
  //    return checkBoxPlayerIsBlack.isSelected();
  //  }

  public boolean isCancelled() {
    return cancelled;
  }
}
