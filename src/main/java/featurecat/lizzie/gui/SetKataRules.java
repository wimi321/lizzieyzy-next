package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.analysis.EngineRulesResult;
import featurecat.lizzie.analysis.GameInfo;
import featurecat.lizzie.analysis.KataGoRules;
import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.util.Utils;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.OptionalDouble;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import org.json.JSONObject;

public class SetKataRules extends JDialog {
  // private JDialog thisFrame = this;
  private ResourceBundle resourceBundle = Lizzie.resourceBundle;
  final KataRulesEditor editor;
  JFontCheckBox chkbxAutoLoadRules;
  JSONObject jo;
  // private JFontLabel lblNewLabel_3;
  private JFontLabel lblStatus;
  private JFontLabel lblKomiHint;
  private final Leelaz engine;
  private final boolean composeOnly;
  private final KataGoRules composeBaseline;
  private KataGoRules composed;
  private volatile long statusWatchGeneration;
  private volatile Leelaz.EngineRulesOperation rulesOperation;
  private volatile KataGoRules requestedManualRules;
  private volatile Leelaz.EngineRulesOperation manualRulesOperation;
  private volatile Leelaz.EngineRulesOperation manualMismatchOperation;
  private Border areaOriginalBorder;
  private Border territoryOriginalBorder;
  private boolean areaOriginalBorderPainted;
  private boolean territoryOriginalBorderPainted;
  private Timer rulesHighlightTimer;

  public SetKataRules() {
    this(Lizzie.leelaz, false, null);
  }

  SetKataRules(Leelaz engine) {
    this(engine, false, null);
  }

  static java.util.Optional<KataGoRules> composeMatchRules(
      java.awt.Window owner, KataGoRules initial) {
    SetKataRules dialog = new SetKataRules(Lizzie.leelaz, true, initial);
    dialog.setModal(true);
    if (owner != null) {
      dialog.setLocationRelativeTo(owner);
    }
    dialog.setVisible(true);
    return java.util.Optional.ofNullable(dialog.composed);
  }

  SetKataRules(Leelaz engine, boolean composeOnly, KataGoRules initial) {
    this.engine = engine;
    this.composeOnly = composeOnly;
    this.composeBaseline = composeOnly ? initial : null;
    // this.setModal(true);
    // setType(Type.POPUP);
    setResizable(false);
    setTitle(resourceBundle.getString("SetKataRules.title")); // ("KataGo规则设置(仅支持KataGo v1.3引擎)");
    setAlwaysOnTop(true);
    setLocationRelativeTo(Lizzie.frame);
    getContentPane().setLayout(null);

    this.addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            closeDialog();
          }

          public void windowActivated(WindowEvent e) {
            if (!composeOnly) refreshKomiHint();
          }
        });
    AccessibilitySupport.installEscapeAction(getRootPane(), this, this::closeDialog);

    editor = new KataRulesEditor(getContentPane(), resourceBundle);
    int below = editor.bottom();

    JFontButton btnCancel =
        new JFontButton(resourceBundle.getString("SetKataRules.btnCancel")); // ("取消");
    btnCancel.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            closeDialog();
          }
        });
    btnCancel.setBounds(292, below + 53, 93, 23);
    getContentPane().add(btnCancel);

    JFontButton btnApply =
        new JFontButton(resourceBundle.getString("SetKataRules.btnApply")); // ("确定");
    btnApply.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            applySelectedRules();
          }
        });
    btnApply.setBounds(184, below + 53, 93, 23);
    getContentPane().add(btnApply);

    chkbxAutoLoadRules =
        new JFontCheckBox(
            resourceBundle.getString(
                "SetKataRules.chkbxAutoLoadRules")); // ("自动设置为此规则(勾选则所有Katago引擎启动时自动设置为此规则,仅支持KataGo v1.3引擎)");
    chkbxAutoLoadRules.setBounds(18, below, 897, 23);
    getContentPane().add(chkbxAutoLoadRules);
    if (Lizzie.config.autoLoadKataRules) {
      chkbxAutoLoadRules.setSelected(true);
    }
    // Size the row for the widest komi KataGo accepts (-400..400) so any real value fits.
    lblKomiHint = new JFontLabel(formatKomiHint(-400.0, 7.5));
    int width =
        Math.max(
            editor.width(),
            composeOnly
                ? 0
                : Math.max(
                        chkbxAutoLoadRules.getPreferredSize().width,
                        lblKomiHint.getPreferredSize().width)
                    + 36);
    lblKomiHint.setText("");
    int checkHeight = chkbxAutoLoadRules.getPreferredSize().height;
    chkbxAutoLoadRules.setBounds(18, below, width - 36, checkHeight);
    int buttonHeight =
        Math.max(btnApply.getPreferredSize().height, btnCancel.getPreferredSize().height);
    int buttonWidth =
        Math.max(
            93, Math.max(btnApply.getPreferredSize().width, btnCancel.getPreferredSize().width));
    int buttonY = below + (composeOnly ? 0 : checkHeight + 2 * configuredStatusHeight());
    btnApply.setBounds(width / 2 - buttonWidth - 6, buttonY, buttonWidth, buttonHeight);
    btnCancel.setBounds(width / 2 + 6, buttonY, buttonWidth, buttonHeight);
    getContentPane().setPreferredSize(new Dimension(width, buttonY + buttonHeight + 16));
    pack();
    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
      Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
      int x = (int) screensize.getWidth() / 2 - this.getWidth() / 2;
      int y = (int) screensize.getHeight() / 2 - this.getHeight() / 2;
      setLocation(x, y);
    } catch (IOException e1) {
      // TODO Auto-generated catch block
      e1.printStackTrace();
    }
    if (!composeOnly && engine != null && !engine.isKatago) {
      Utils.showMsg(resourceBundle.getString("SetKataRules.notKataGoHint"));
    }
    lblStatus = new JFontLabel("");
    lblStatus.setBounds(18, below + checkHeight, width - 36, configuredStatusHeight());
    getContentPane().add(lblStatus);
    lblKomiHint.setBounds(
        18, below + checkHeight + configuredStatusHeight(), width - 36, configuredStatusHeight());
    getContentPane().add(lblKomiHint);
    if (composeOnly) {
      chkbxAutoLoadRules.setVisible(false);
      lblStatus.setVisible(false);
      lblKomiHint.setVisible(false);
      if (initial != null) {
        applyJsonToEditor(initial.toJson());
      }
    } else {
      editor.onChange(this::refreshKomiHint);
      refreshKomiHint();
      if (rejectEngineGameInteraction()) return;
      rulesOperation = engine.queryEngineRulesOperation();
      refreshStatus();
      watchEngineRulesStatus();
    }
  }

  private static int configuredStatusHeight() {
    return Math.max(27, featurecat.lizzie.Config.frameFontSize + 12);
  }

  void locateRulesEditor() {
    clearRulesHighlight();
    JFontRadioButton area = editor.rdoArea;
    JFontRadioButton territory = editor.rdoTerritory;
    areaOriginalBorder = area.getBorder();
    territoryOriginalBorder = territory.getBorder();
    areaOriginalBorderPainted = area.isBorderPainted();
    territoryOriginalBorderPainted = territory.isBorderPainted();
    area.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new java.awt.Color(185, 156, 93), 2),
            areaOriginalBorder));
    territory.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new java.awt.Color(185, 156, 93), 2),
            territoryOriginalBorder));
    area.setBorderPainted(true);
    territory.setBorderPainted(true);
    rulesHighlightTimer = new Timer(2400, event -> clearRulesHighlight());
    rulesHighlightTimer.setRepeats(false);
    rulesHighlightTimer.start();
    SwingUtilities.invokeLater(
        () -> {
          JFontRadioButton target = territory.isSelected() ? territory : area;
          if (isDisplayable()) target.requestFocusInWindow();
        });
  }

  private void clearRulesHighlight() {
    if (rulesHighlightTimer != null) rulesHighlightTimer.stop();
    rulesHighlightTimer = null;
    if (editor == null) return;
    if (areaOriginalBorder != null) {
      editor.rdoArea.setBorder(areaOriginalBorder);
      editor.rdoArea.setBorderPainted(areaOriginalBorderPainted);
    }
    if (territoryOriginalBorder != null) {
      editor.rdoTerritory.setBorder(territoryOriginalBorder);
      editor.rdoTerritory.setBorderPainted(territoryOriginalBorderPainted);
    }
    areaOriginalBorder = null;
    territoryOriginalBorder = null;
    editor.rdoArea.repaint();
    editor.rdoTerritory.repaint();
  }

  @Override
  public void setVisible(boolean visible) {
    if (!visible) clearRulesHighlight();
    super.setVisible(visible);
  }

  @Override
  public void dispose() {
    clearRulesHighlight();
    super.dispose();
  }

  private void closeDialog() {
    statusWatchGeneration++;
    if (!composeOnly
        && !rejectEngineGameInteraction()
        && isCurrentEngine()
        && engine.isPondering()) {
      engine.ponder();
    }
    setVisible(false);
  }

  void applySelectedRules() {
    if (composeOnly) {
      composed = rulesFromEditor();
      setVisible(false);
      return;
    }
    if (rejectEngineGameInteraction()) return;
    if (!isCurrentEngine() || !engine.isLoaded() || !engine.isStarted()) {
      Utils.showMsg(resourceBundle.getString("LizzieFrame.setParamNoEngineHint"));
      setVisible(false);
      return;
    }
    if (engine.isRulesMutationOccupied()) {
      Utils.showMsg(resourceBundle.getString("AnalysisSettings.reuseStatus.existing_lease"));
      refreshStatus();
      return;
    }
    KataGoRules requested = rulesFromEditor();
    ManualRulesSelection selection =
        new ManualRulesSelection(Lizzie.config, () -> Lizzie.board, () -> Lizzie.frame);
    try {
      selection.saveStartupRules(requested, chkbxAutoLoadRules.isSelected());
    } catch (IOException failure) {
      Utils.showMsg(resourceBundle.getString("Lizzie.save.error") + failure.getLocalizedMessage());
      return;
    }
    synchronized (this) {
      requestedManualRules = requested;
    }
    manualMismatchOperation = null;
    rulesOperation = selection.apply(engine, requested, this, () -> {});
    manualRulesOperation = rulesOperation;
    watchEngineRulesStatus();
    refreshStatus();
  }

  private KataGoRules rulesFromEditor() {
    KataGoRules base = null;
    if (composeOnly) {
      base = composeBaseline;
    } else {
      Leelaz.EngineRulesOperation operation = rulesOperation;
      EngineRulesResult result = operation == null ? null : operation.snapshot();
      if (result != null && result.isConfirmed()) {
        base = result.observed();
      }
    }
    return editor.overlay(base);
  }

  public boolean hasRulesResponse() {
    Leelaz.EngineRulesOperation operation = rulesOperation;
    return operation != null && operation.isDone();
  }

  public boolean getRules() {
    Leelaz.EngineRulesOperation operation = rulesOperation;
    if (operation != null && operation == manualMismatchOperation) {
      lblStatus.setText(resourceBundle.getString("SetKataRules.status.setFailed"));
      return false;
    }
    EngineRulesResult result = operation == null ? null : operation.result();
    refreshStatus(result == null && operation != null ? operation.snapshot() : result);
    if (result == null || !result.isConfirmed() || result.observed() == null) {
      return false;
    }
    KataGoRules manual = requestedManualRules;
    Leelaz.EngineRulesOperation manualOperation = manualRulesOperation;
    if (manual != null
        && operation == manualOperation
        && !result.observed().semanticallyEquals(manual)) {
      lblStatus.setText(resourceBundle.getString("SetKataRules.status.setFailed"));
      manualMismatchOperation = operation;
      clearManualRulesRequest();
      jo = result.observed().toJson();
      applyJsonToEditor(jo);
      return false;
    }
    if (operation == manualOperation) {
      clearManualRulesRequest();
    }
    jo = result.observed().toJson();
    applyJsonToEditor(jo);
    return true;
  }

  private void clearManualRulesRequest() {
    requestedManualRules = null;
    manualRulesOperation = null;
  }

  private void applyJsonToEditor(JSONObject jo) {
    this.jo = jo;
    editor.apply(jo);
  }

  String statusText() {
    return lblStatus.getText();
  }

  String komiHintText() {
    return lblKomiHint.getText();
  }

  /** Hints when the current game's komi differs from KataGo's default for the edited rules. */
  private void refreshKomiHint() {
    lblKomiHint.setText(currentKomiHint());
  }

  private String currentKomiHint() {
    if (Lizzie.board == null) return "";
    GameInfo game = Lizzie.board.getHistory().getGameInfo();
    if (game.getHandicap() > 0) return "";
    OptionalDouble conventional = rulesFromEditor().defaultKomi();
    if (conventional.isEmpty() || conventional.getAsDouble() == game.getKomi()) return "";
    return formatKomiHint(game.getKomi(), conventional.getAsDouble());
  }

  private String formatKomiHint(double current, double conventional) {
    return MessageFormat.format(
        resourceBundle.getString("SetKataRules.komiConventionHint"),
        String.valueOf(current),
        String.valueOf(conventional));
  }

  void refreshStatus() {
    Leelaz.EngineRulesOperation operation = rulesOperation;
    refreshStatus(operation == null ? null : operation.snapshot());
  }

  private void refreshStatus(EngineRulesResult result) {
    if (result == null) {
      lblStatus.setText("");
      return;
    }
    String key;
    switch (result.status()) {
      case PENDING:
        key = "SetKataRules.status.pending";
        break;
      case CONFIRMED:
        lblStatus.setText("");
        return;
      case UNCONFIRMED:
        key = "SetKataRules.status.unconfirmed";
        break;
      case SET_FAILED:
        key = "SetKataRules.status.setFailed";
        break;
      case QUERY_FAILED:
        key = "SetKataRules.status.queryFailed";
        break;
      case CAPABILITY_FAILED:
        key = "SetKataRules.status.capabilityFailed";
        break;
      default:
        lblStatus.setText("");
        return;
    }
    String text = resourceBundle.getString(key);
    if (result.lastKnownStale()) {
      text = text + " " + resourceBundle.getString("SetKataRules.lastKnownStale");
    }
    lblStatus.setText(text);
  }

  private void watchEngineRulesStatus() {
    final long generation = ++statusWatchGeneration;
    Thread thread =
        new Thread(
            () -> {
              EngineRulesResult.Status lastStatus = null;
              long lastResultGeneration = -1L;
              while (generation == statusWatchGeneration) {
                Leelaz.EngineRulesOperation operation = rulesOperation;
                if (requestedManualRules == null) {
                  Leelaz.EngineRulesOperation currentOperation = engine.engineRulesOperation();
                  if (currentOperation != null && currentOperation != operation) {
                    rulesOperation = currentOperation;
                    operation = currentOperation;
                    lastStatus = null;
                    lastResultGeneration = -1L;
                  }
                }
                if (operation == null) {
                  return;
                }
                EngineRulesResult snapshot = operation.snapshot();
                EngineRulesResult.Status status = snapshot.status();
                // The engine publishes a settled snapshot just before it completes the operation.
                // Until then result() is null, so getRules() would fail and this confirmation
                // would be consumed without closing the editor; look again on the next poll.
                boolean completionPending = snapshot.isSettled() && !operation.isDone();
                if (!completionPending
                    && (status != lastStatus || snapshot.generation() != lastResultGeneration)) {
                  lastStatus = status;
                  lastResultGeneration = snapshot.generation();
                  boolean confirmed = snapshot.isConfirmed();
                  Leelaz.EngineRulesOperation watchedOperation = operation;
                  SwingUtilities.invokeLater(
                      () -> {
                        if (generation != statusWatchGeneration) {
                          return;
                        }
                        if (confirmed) {
                          boolean manualApplication = watchedOperation == manualRulesOperation;
                          if (getRules() && manualApplication) {
                            closeDialog();
                          }
                        } else {
                          refreshStatus(snapshot);
                          if (snapshot.isSettled() && watchedOperation == manualRulesOperation) {
                            clearManualRulesRequest();
                          }
                        }
                      });
                }
                try {
                  Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            },
            "lizzie-set-kata-rules-status");
    thread.setDaemon(true);
    thread.start();
  }

  static boolean rejectEngineGameInteraction() {
    if (!EngineManager.occupiesEngineGameAdmission()) return false;
    Utils.showMsg(Lizzie.resourceBundle.getString("AnalysisSettings.reuseStatus.engine_game"));
    return true;
  }

  private boolean isCurrentEngine() {
    return engine != null && engine == Lizzie.leelaz;
  }
}
