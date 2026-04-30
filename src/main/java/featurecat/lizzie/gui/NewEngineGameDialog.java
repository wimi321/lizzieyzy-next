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
    if (Lizzie.config.isFrameFontSmall()) return 760;
    if (Lizzie.config.isFrameFontMiddle()) return 820;
    return 880;
  }

  private int dialogHeight() {
    if (Lizzie.config.isFrameFontSmall()) return 468;
    if (Lizzie.config.isFrameFontMiddle()) return 482;
    return 500;
  }

  private int controlHeight() {
    if (Lizzie.config.isFrameFontSmall()) return 24;
    if (Lizzie.config.isFrameFontMiddle()) return 26;
    return 28;
  }

  private void setGridBounds(Component component, int x, int y, int width) {
    component.setBounds(x, y, width, controlHeight());
  }

  private void initComponents() {
    // if (Lizzie.config.showHiddenYzy) setMinimumSize(new Dimension(380, 330));
    // else
    // setMinimumSize(new Dimension(580, 530));
    Lizzie.setFrameSize(this, dialogWidth(), dialogHeight());
    setResizable(false);
    setTitle(Lizzie.resourceBundle.getString("NewEngineGameDialog.title")); // "引擎对战");
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
    // pack();
    setLocationRelativeTo(getOwner());
  }

  private void initDialogPane(Container contentPane) {
    dialogPane.setBorder(new EmptyBorder(0, 0, 0, 0));
    dialogPane.setLayout(new BorderLayout());

    initContentPanel();
    initButtonBar();

    contentPane.add(dialogPane, BorderLayout.CENTER);
  }

  private void initContentPanel() {
    contentPanel.setLayout(null);
    int margin = 18;
    int labelX = margin;
    int labelW =
        Lizzie.config.isFrameFontSmall() ? 150 : (Lizzie.config.isFrameFontMiddle() ? 175 : 200);
    int checkX = labelX + labelW + 12;
    int checkW = 24;
    int blackX = checkX + checkW + 10;
    int fieldW =
        Lizzie.config.isFrameFontSmall() ? 200 : (Lizzie.config.isFrameFontMiddle() ? 215 : 230);
    int columnGap = Lizzie.config.isFrameFontSmall() ? 20 : 24;
    int whiteX = blackX + fieldW + columnGap;
    int headerY = 8;
    int engineY = 38;
    int timeY = 68;
    int advanceY = 98;
    int playoutY = 128;
    int firstPlayoutY = 158;
    int resignBlackY = 194;
    int resignWhiteY = 226;
    int komiY = 260;
    int continueY = 292;
    int optionY = 324;
    int option2Y = 354;
    int rightEdge = dialogWidth() - 44;
    int helpIconX = checkX - 26;
    int resignGap = Lizzie.config.isFrameFontSmall() ? 6 : 8;
    int resignMinLabelW =
        Lizzie.config.isFrameFontSmall() ? 64 : (Lizzie.config.isFrameFontMiddle() ? 72 : 82);
    int resignConsistentLabelW =
        Lizzie.config.isFrameFontSmall() ? 38 : (Lizzie.config.isFrameFontMiddle() ? 42 : 50);
    int resignBelowLabelW =
        Lizzie.config.isFrameFontSmall() ? 90 : (Lizzie.config.isFrameFontMiddle() ? 100 : 116);
    int resignFieldW = Lizzie.config.isFrameFontSmall() ? 46 : 50;
    int resignMinLabelX = labelX + labelW + resignGap;
    int resignMinFieldX = resignMinLabelX + resignMinLabelW;
    int resignConsistentLabelX = resignMinFieldX + resignFieldW + resignGap;
    int resignConsistentFieldX = resignConsistentLabelX + resignConsistentLabelW;
    int resignBelowLabelX = resignConsistentFieldX + resignFieldW + resignGap;
    int resignBelowFieldX = resignBelowLabelX + resignBelowLabelW;
    int resignPercentX = resignBelowFieldX + resignFieldW + 4;
    contentPanel.setPreferredSize(new Dimension(dialogWidth() - 28, dialogHeight() - 82));

    //    checkBoxPlayerIsBlack =
    //        new JCheckBox(resourceBundle.getString("NewGameDialog.PlayBlack"), true);
    //    checkBoxPlayerIsBlack.addChangeListener(evt -> togglePlayerIsBlack());

    JTextArea resignThresoldHint =
        new JTextArea(Lizzie.resourceBundle.getString("EnginePkConfig.lblresignGenmove"));
    resignThresoldHint.setFont(
        new Font(Config.sysDefaultFontName, Font.PLAIN, Config.frameFontSize));
    resignThresoldHint.setLineWrap(true);
    resignThresoldHint.setFocusable(false);
    resignThresoldHint.setBackground(this.getBackground());

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

    contentPanel.add(resignThresoldHint);
    contentPanel.add(lblresignSettingBlack);
    contentPanel.add(lblresignSettingBlackConsistent);
    contentPanel.add(lblresignSettingBlack2);
    contentPanel.add(lblresignSettingBlack3);
    contentPanel.add(lblresignSettingBlack4);
    contentPanel.add(txtresignSettingBlack);
    contentPanel.add(txtresignSettingBlack2);
    contentPanel.add(txtresignSettingBlackMinMove);

    resignThresoldHint.setBounds(labelX, resignBlackY, rightEdge - labelX, controlHeight() * 2 + 6);
    resignThresoldHint.setVisible(false);
    setGridBounds(lblresignSettingBlack, labelX, resignBlackY, labelW);
    setGridBounds(
        lblresignSettingBlackConsistent,
        resignConsistentLabelX,
        resignBlackY,
        resignConsistentLabelW);
    setGridBounds(lblresignSettingBlack2, resignMinLabelX, resignBlackY, resignMinLabelW);
    setGridBounds(lblresignSettingBlack3, resignBelowLabelX, resignBlackY, resignBelowLabelW);
    setGridBounds(lblresignSettingBlack4, resignPercentX, resignBlackY, 24);
    setGridBounds(txtresignSettingBlackMinMove, resignMinFieldX, resignBlackY, resignFieldW);
    setGridBounds(txtresignSettingBlack, resignConsistentFieldX, resignBlackY, resignFieldW);
    setGridBounds(txtresignSettingBlack2, resignBelowFieldX, resignBlackY, resignFieldW);

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

    setGridBounds(lblresignSettingWhite, labelX, resignWhiteY, labelW);
    setGridBounds(
        lblresignSettingWhiteConsistent,
        resignConsistentLabelX,
        resignWhiteY,
        resignConsistentLabelW);
    setGridBounds(lblresignSettingWhite2, resignMinLabelX, resignWhiteY, resignMinLabelW);
    setGridBounds(lblresignSettingWhite3, resignBelowLabelX, resignWhiteY, resignBelowLabelW);
    setGridBounds(lblresignSettingWhite4, resignPercentX, resignWhiteY, 24);
    setGridBounds(txtresignSettingWhiteMinMove, resignMinFieldX, resignWhiteY, resignFieldW);
    setGridBounds(txtresignSettingWhite, resignConsistentFieldX, resignWhiteY, resignFieldW);
    setGridBounds(txtresignSettingWhite2, resignBelowFieldX, resignWhiteY, resignFieldW);

    contentPanel.add(lblresignSettingWhite);
    contentPanel.add(lblresignSettingWhiteConsistent);
    contentPanel.add(lblresignSettingWhite3);
    contentPanel.add(lblresignSettingWhite2);
    contentPanel.add(lblresignSettingWhite4);
    contentPanel.add(txtresignSettingWhiteMinMove);
    contentPanel.add(txtresignSettingWhite);
    contentPanel.add(txtresignSettingWhite2);

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
    JFontButton btnConfig =
        new JFontButton(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.btnConfig")); // ("更多设置");
    btnConfig.setMargin(new Insets(0, 0, 0, 0));
    btnConfig.addActionListener(
        new ActionListener() {
          @Override
          public void actionPerformed(ActionEvent e) {
            // TBD未完成
            EnginePkConfig engineconfig = new EnginePkConfig(false);
            engineconfig.setVisible(true);
            engineconfig.setAlwaysOnTop(true);
            if (LizzieFrame.toolbar.isGenmoveToolbar) {
              chkDisableWRNInGame.setVisible(false);
              txtresignSettingBlack.setVisible(false);
              txtresignSettingBlack2.setVisible(false);
              txtresignSettingBlackMinMove.setVisible(false);
              resignThresoldHint.setVisible(true);
              lblresignSettingBlack.setVisible(false); // "Genmove模式下,认输由引擎控制,请在引擎参数中设置认输阈值");
              lblresignSettingBlackConsistent.setVisible(false);
              lblresignSettingBlack2.setVisible(false);
              lblresignSettingBlack3.setVisible(false);
              lblresignSettingBlack4.setVisible(false);

              txtresignSettingWhite.setVisible(false);
              txtresignSettingWhite2.setVisible(false);
              txtresignSettingWhiteMinMove.setVisible(false);
              lblresignSettingWhite.setVisible(false);
              lblresignSettingWhiteConsistent.setVisible(false);
              lblresignSettingWhite2.setVisible(false);
              lblresignSettingWhite3.setVisible(false);
              lblresignSettingWhite4.setVisible(false);
              txtBlackAdvanceTime.setEnabled(true);
              txtWhiteAdvanceTime.setEnabled(true);
              chkUseAdvanceTime.setEnabled(true);
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
            } else {
              chkDisableWRNInGame.setVisible(true);
              txtresignSettingBlack.setVisible(true);
              txtresignSettingBlack2.setVisible(true);
              txtresignSettingBlackMinMove.setVisible(true);
              resignThresoldHint.setVisible(false);
              lblresignSettingBlack.setVisible(true);
              lblresignSettingBlackConsistent.setVisible(true);
              lblresignSettingBlack2.setVisible(true);
              lblresignSettingBlack3.setVisible(true);
              lblresignSettingBlack4.setVisible(true);

              txtresignSettingWhite.setVisible(true);
              txtresignSettingWhite2.setVisible(true);
              txtresignSettingWhiteMinMove.setVisible(true);
              lblresignSettingWhite.setVisible(true);
              lblresignSettingWhiteConsistent.setVisible(true);
              lblresignSettingWhite2.setVisible(true);
              lblresignSettingWhite3.setVisible(true);
              lblresignSettingWhite4.setVisible(true);
              LizzieFrame.toolbar.chkenginePkTime.setEnabled(true);
              if (LizzieFrame.toolbar.chkenginePkTime.isSelected()) {
                LizzieFrame.toolbar.txtenginePkTime.setEnabled(true);
                LizzieFrame.toolbar.txtenginePkTimeWhite.setEnabled(true);
              }
              txtBlackAdvanceTime.setEnabled(false);
              txtWhiteAdvanceTime.setEnabled(false);
              chkUseAdvanceTime.setEnabled(false);
            }
          }
        });

    JFontLabel lblB =
        new JFontLabel(Lizzie.resourceBundle.getString("NewEngineGameDialog.lblB")); // ("黑方设置");
    lblB.setHorizontalAlignment(JLabel.CENTER);
    JFontLabel lblW =
        new JFontLabel(Lizzie.resourceBundle.getString("NewEngineGameDialog.lblW")); // ("白方设置");
    lblW.setHorizontalAlignment(JLabel.CENTER);
    setGridBounds(lblB, blackX, headerY, fieldW);
    setGridBounds(lblW, whiteX, headerY, fieldW);

    JFontLabel lblengine =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblengine")); // ("选择引擎");
    setGridBounds(lblengine, labelX, engineY, labelW);

    setGridBounds(enginePkBlack, blackX, engineY, fieldW);
    setGridBounds(enginePkWhite, whiteX, engineY, fieldW);
    contentPanel.add(lblengine);
    contentPanel.add(lblB);
    contentPanel.add(lblW);
    contentPanel.add(enginePkBlack);
    contentPanel.add(enginePkWhite);
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
    setGridBounds(lblTime, labelX, timeY, labelW);
    setGridBounds(LizzieFrame.toolbar.chkenginePkTime, checkX, timeY, checkW);
    setGridBounds(LizzieFrame.toolbar.txtenginePkTime, blackX, timeY, fieldW);
    setGridBounds(LizzieFrame.toolbar.txtenginePkTimeWhite, whiteX, timeY, fieldW);
    contentPanel.add(lblTime);
    contentPanel.add(LizzieFrame.toolbar.chkenginePkTime);
    contentPanel.add(LizzieFrame.toolbar.txtenginePkTime);
    contentPanel.add(LizzieFrame.toolbar.txtenginePkTimeWhite);
    if (!LizzieFrame.toolbar.chkenginePkTime.isSelected()) {
      LizzieFrame.toolbar.txtenginePkTime.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkTimeWhite.setEnabled(false);
    }

    JFontLabel lblPlayout =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblPlayout")); // ("总计算量");

    setGridBounds(lblPlayout, labelX, playoutY, labelW);
    setGridBounds(LizzieFrame.toolbar.chkenginePkPlayouts, checkX, playoutY, checkW);
    setGridBounds(LizzieFrame.toolbar.txtenginePkPlayputs, blackX, playoutY, fieldW);
    setGridBounds(LizzieFrame.toolbar.txtenginePkPlayputsWhite, whiteX, playoutY, fieldW);
    contentPanel.add(lblPlayout);
    contentPanel.add(LizzieFrame.toolbar.chkenginePkPlayouts);
    contentPanel.add(LizzieFrame.toolbar.txtenginePkPlayputs);
    contentPanel.add(LizzieFrame.toolbar.txtenginePkPlayputsWhite);
    if (!LizzieFrame.toolbar.chkenginePkPlayouts.isSelected()) {
      LizzieFrame.toolbar.txtenginePkPlayputs.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkPlayputsWhite.setEnabled(false);
    }

    JFontLabel lblFirstPlayout =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblFirstPlayout")); // ("首位计算量");
    setGridBounds(lblFirstPlayout, labelX, firstPlayoutY, labelW);
    setGridBounds(LizzieFrame.toolbar.chkenginePkFirstPlayputs, checkX, firstPlayoutY, checkW);
    setGridBounds(LizzieFrame.toolbar.txtenginePkFirstPlayputs, blackX, firstPlayoutY, fieldW);
    setGridBounds(LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite, whiteX, firstPlayoutY, fieldW);

    contentPanel.add(lblFirstPlayout);
    contentPanel.add(LizzieFrame.toolbar.chkenginePkFirstPlayputs);
    contentPanel.add(LizzieFrame.toolbar.txtenginePkFirstPlayputs);
    contentPanel.add(LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite);
    if (!LizzieFrame.toolbar.chkenginePkFirstPlayputs.isSelected()) {
      LizzieFrame.toolbar.txtenginePkFirstPlayputs.setEnabled(false);
      LizzieFrame.toolbar.txtenginePkFirstPlayputsWhite.setEnabled(false);
    }

    JFontLabel komi = new JFontLabel(Lizzie.resourceBundle.getString("NewEngineGameDialog.komi"));
    JFontLabel handicap =
        new JFontLabel(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.handicap")); // ("让子(仅支持19路棋盘)");
    int komiFieldX = labelX + 50;
    int handicapX = komiFieldX + 64;
    int handicapLabelW =
        Lizzie.config.isFrameFontSmall() ? 150 : (Lizzie.config.isFrameFontMiddle() ? 175 : 190);
    int handicapFieldX = handicapX + handicapLabelW + 8;
    setGridBounds(komi, labelX, komiY, 46);
    setGridBounds(textFieldKomi, komiFieldX, komiY, 52);
    setGridBounds(handicap, handicapX, komiY, handicapLabelW);
    setGridBounds(textFieldHandicap, handicapFieldX, komiY, 52);
    contentPanel.add(komi);
    contentPanel.add(textFieldKomi);
    contentPanel.add(handicap);
    contentPanel.add(textFieldHandicap);

    chkContinuePlay =
        new JFontCheckBox(
            Lizzie.resourceBundle.getString("NewEngineGameDialog.lblContinue")); // ("当前局面续弈");

    int sgfButtonW =
        Lizzie.config.isFrameFontSmall() ? 86 : (Lizzie.config.isFrameFontMiddle() ? 92 : 102);
    int sgfComboW =
        Lizzie.config.isFrameFontSmall() ? 76 : (Lizzie.config.isFrameFontMiddle() ? 82 : 88);
    int sgfButtonX = rightEdge - sgfButtonW;
    int sgfComboX = sgfButtonX - sgfComboW - 8;
    int sgfCheckX = sgfComboX - checkW - 6;
    int sgfLabelX = labelX + Math.min(210, labelW + 22);
    int sgfLabelW = Math.max(88, sgfCheckX - sgfLabelX - 8);

    setGridBounds(chkContinuePlay, labelX, continueY, sgfLabelX - labelX - 12);
    // LizzieFrame.toolbar.chkenginePkContinue.setBounds(5, 270, 20, 20);
    contentPanel.add(chkContinuePlay);
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
    int batchLabelW =
        Lizzie.config.isFrameFontSmall() ? 72 : (Lizzie.config.isFrameFontMiddle() ? 82 : 92);
    setGridBounds(lblBatchGame, whiteX, komiY, batchLabelW);
    setGridBounds(LizzieFrame.toolbar.chkenginePkBatch, whiteX + batchLabelW + 4, komiY, checkW);
    setGridBounds(
        LizzieFrame.toolbar.txtenginePkBatch, whiteX + batchLabelW + checkW + 12, komiY, 56);
    int configButtonW =
        Lizzie.config.isFrameFontSmall() ? 92 : (Lizzie.config.isFrameFontMiddle() ? 98 : 112);
    setGridBounds(btnConfig, rightEdge - configButtonW, option2Y, configButtonW);
    contentPanel.add(lblBatchGame);
    contentPanel.add(LizzieFrame.toolbar.chkenginePkBatch);
    contentPanel.add(LizzieFrame.toolbar.txtenginePkBatch);
    contentPanel.add(btnConfig);

    textFieldKomi.setEnabled(true);

    //    if (Lizzie.config.showHiddenYzy) {
    //      JFontLabel delay = new JFontLabel("延时(分):"); // $NON-NLS-1$
    //      delay.setBounds(269, 237, 60, 20);
    //      contentPanel.add(delay);
    //
    //      textFieldDelay = new JFormattedTextField(FORMAT_HANDICAP);
    //      textFieldDelay.setBounds(324, 237, 40, 20);
    //      contentPanel.add(textFieldDelay);
    //    }
    dialogPane.add(contentPanel, BorderLayout.CENTER);

    JFontLabel lblAdvanceTime =
        new JFontLabel(
            Lizzie.resourceBundle.getString(
                "NewEngineGameDialog.lblAdvanceTime")); // ("高级时间设置"); // $NON-NLS-1$
    setGridBounds(lblAdvanceTime, labelX, advanceY, labelW);
    contentPanel.add(lblAdvanceTime);

    txtBlackAdvanceTime = new JFontTextField();
    setGridBounds(txtBlackAdvanceTime, blackX, advanceY, fieldW);
    contentPanel.add(txtBlackAdvanceTime);
    txtBlackAdvanceTime.setColumns(10);

    txtWhiteAdvanceTime = new JFontTextField();
    setGridBounds(txtWhiteAdvanceTime, whiteX, advanceY, fieldW);
    contentPanel.add(txtWhiteAdvanceTime);
    txtWhiteAdvanceTime.setColumns(10);

    txtBlackAdvanceTime.setText(Lizzie.config.advanceBlackTimeTxt);
    txtWhiteAdvanceTime.setText(Lizzie.config.advanceWhiteTimeTxt);
    if (!LizzieFrame.toolbar.isGenmoveToolbar) {
      txtBlackAdvanceTime.setEnabled(false);
      txtWhiteAdvanceTime.setEnabled(false);
    } else {
      txtresignSettingBlack.setVisible(false);
      txtresignSettingBlack2.setVisible(false);
      txtresignSettingBlackMinMove.setVisible(false);
      resignThresoldHint.setVisible(true);
      lblresignSettingBlack.setVisible(false);
      lblresignSettingBlackConsistent.setVisible(false);
      lblresignSettingBlack2.setVisible(false);
      lblresignSettingBlack3.setVisible(false);
      lblresignSettingBlack4.setVisible(false);

      txtresignSettingWhite.setVisible(false);
      txtresignSettingWhite2.setVisible(false);
      txtresignSettingWhiteMinMove.setVisible(false);
      lblresignSettingWhiteConsistent.setVisible(false);
      lblresignSettingWhite.setVisible(false);
      lblresignSettingWhite2.setVisible(false);
      lblresignSettingWhite3.setVisible(false);
      lblresignSettingWhite4.setVisible(false);
    }
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
    aboutAdvanceTimeSettings.setBounds(helpIconX, advanceY + 3, 18, 18);
    aboutAdvanceTimeSettings.setFocusable(false);
    contentPanel.add(aboutAdvanceTimeSettings);

    chkUseAdvanceTime = new JFontCheckBox(); // $NON-NLS-1$
    setGridBounds(chkUseAdvanceTime, checkX, advanceY, checkW);
    contentPanel.add(chkUseAdvanceTime);
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
    setGridBounds(checkBoxAllowPonder, labelX, optionY, rightEdge - labelX);
    checkBoxAllowPonder.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (checkBoxAllowPonder.isSelected()) Lizzie.config.enginePkPonder = true;
            else Lizzie.config.enginePkPonder = false;
            Lizzie.config.uiConfig.put("engine-pk-ponder", Lizzie.config.enginePkPonder);
          }
        });
    checkBoxAllowPonder.setSelected(Lizzie.config.enginePkPonder);
    contentPanel.add(checkBoxAllowPonder);

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
    setGridBounds(chkDisableWRNInGame, labelX, option2Y, rightEdge - labelX - configButtonW - 12);
    chkDisableWRNInGame.setSelected(Lizzie.config.disableWRNInGame);
    contentPanel.add(chkDisableWRNInGame);
    chkDisableWRNInGame.setVisible(!LizzieFrame.toolbar.isGenmoveToolbar);

    chkSGFstart = new JFontCheckBox();
    setGridBounds(chkSGFstart, sgfCheckX, continueY, checkW);
    contentPanel.add(chkSGFstart);
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
    setGridBounds(btnSGFstart, sgfButtonX, continueY, sgfButtonW);
    contentPanel.add(btnSGFstart);
    btnSGFstart.setMargin(new Insets(0, 0, 0, 0));

    setGridBounds(lblsgf, sgfLabelX, continueY, sgfLabelW);
    contentPanel.add(lblsgf);
    // lblsgf.setEnabled(LizzieFrame.toolbar.chkenginePkBatch.isSelected());

    cbxRandomSgf = new JFontComboBox<String>();
    cbxRandomSgf.addItem(
        Lizzie.resourceBundle.getString("NewEngineGameDialog.cbxRandomSgf1")); // ("顺序");
    cbxRandomSgf.addItem(
        Lizzie.resourceBundle.getString("NewEngineGameDialog.cbxRandomSgf2")); // ("随机");
    setGridBounds(cbxRandomSgf, sgfComboX, continueY, sgfComboW);
    contentPanel.add(cbxRandomSgf);
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
    if (Lizzie.config.pkAdvanceTimeSettings && LizzieFrame.toolbar.isGenmoveToolbar) {
      LizzieFrame.toolbar.chkenginePkTime.setEnabled(false);
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
    buttonBar.setBorder(new EmptyBorder(7, 0, 0, 0));
    buttonBar.setLayout(new GridBagLayout());
    ((GridBagLayout) buttonBar.getLayout()).columnWidths = new int[] {0, 70};
    ((GridBagLayout) buttonBar.getLayout()).columnWeights = new double[] {1.0, 0.0};

    // ---- okButton ----
    okButton.setText(Lizzie.resourceBundle.getString("NewEngineGameDialog.okButton")); // ("确定");
    okButton.addActionListener(e -> apply());

    int center = GridBagConstraints.CENTER;
    int both = GridBagConstraints.BOTH;
    buttonBar.add(
        okButton,
        new GridBagConstraints(1, 0, 1, 1, 0.0, 0.0, center, both, new Insets(0, 0, 0, 0), 0, 0));

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
