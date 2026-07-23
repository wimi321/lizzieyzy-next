package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ResourceBundle;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;

/** Builds the shared automatic and whole-game analysis menu used by both toolbars. */
final class AutoAnalyzeMenu {
  static final String ACTION_PROPERTY = "lizzie.autoAnalyzeAction";
  static final String WHOLE_GAME_ACTION = "whole-game-deep-analysis";
  static final String WHOLE_GAME_LIGHTNING_ACTION = "whole-game-lightning-overview";

  private AutoAnalyzeMenu() {}

  static JPopupMenu create(ResourceBundle resources) {
    return create(resources, () -> Lizzie.frame.openWholeGameDeepAnalysis());
  }

  static JPopupMenu create(ResourceBundle resources, Runnable wholeGameAction) {
    return create(resources, wholeGameAction, () -> Lizzie.frame.flashAnalyzeGame(true, false));
  }

  static JPopupMenu create(
      ResourceBundle resources, Runnable wholeGameAction, Runnable wholeGameLightningAction) {
    JPopupMenu popup = new JPopupMenu();

    popup.add(wholeGameDeepAnalysisItem(resources, wholeGameAction));
    popup.add(
        item(resources, "Menu.autoAnalyze", "auto-analysis", AutoAnalyzeMenu::openAutoAnalyze));

    popup.addSeparator();
    popup.add(wholeGameLightningItem(resources, wholeGameLightningAction));
    popup.add(
        item(
            resources,
            "Menu.flashAnalyzePartGame",
            "partial-deep-analysis",
            () -> Lizzie.frame.flashAnalyzePart()));
    popup.add(
        item(
            resources,
            "Menu.flashAnalyzeAllBranches",
            "branch-deep-analysis",
            () -> Lizzie.frame.flashAnalyzeGame(false, true)));
    popup.add(
        item(
            resources,
            "Menu.flashAnalyzeSettings",
            "deep-analysis-settings",
            () -> Lizzie.frame.flashAnalyzeSettings()));

    popup.addSeparator();
    popup.add(
        item(
            resources,
            "Menu.batchAnalyze",
            "batch-analysis",
            () -> Lizzie.frame.openFileWithAna(false)));
    popup.add(
        item(
            resources,
            "Menu.batchAnalysisMode",
            "batch-deep-analysis",
            () -> Lizzie.frame.openFileWithAna(true)));

    popup.addSeparator();
    popup.add(
        item(
            resources,
            "Menu.stopAutoAnalyze",
            "stop-analysis",
            () -> {
              if (LizzieFrame.toolbar != null) {
                LizzieFrame.toolbar.stopAutoAna(true, true);
              }
            }));
    popup.add(
        item(
            resources,
            "Menu.batchAnalyzeTable",
            "batch-progress",
            () -> Lizzie.frame.openAnalysisTable()));

    AppleStyleSupport.installPopupStyle(popup);
    return popup;
  }

  static JFontMenuItem wholeGameDeepAnalysisItem(
      ResourceBundle resources, Runnable wholeGameAction) {
    JFontMenuItem wholeGame =
        item(resources, "Menu.wholeGameDeepAnalysis", WHOLE_GAME_ACTION, wholeGameAction);
    wholeGame.setAccelerator(
        KeyStroke.getKeyStroke(
            KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
    return wholeGame;
  }

  static JFontMenuItem wholeGameLightningItem(
      ResourceBundle resources, Runnable wholeGameLightningAction) {
    JFontMenuItem lightning =
        item(
            resources,
            "Menu.flashAnalyzeAllGame",
            WHOLE_GAME_LIGHTNING_ACTION,
            wholeGameLightningAction);
    lightning.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
    return lightning;
  }

  private static JFontMenuItem item(
      ResourceBundle resources, String key, String actionId, Runnable action) {
    JFontMenuItem item = new JFontMenuItem(resources.getString(key));
    item.putClientProperty(ACTION_PROPERTY, actionId);
    item.addActionListener(e -> action.run());
    return item;
  }

  private static void openAutoAnalyze() {
    StartAnaDialog dialog = new StartAnaDialog(false, Lizzie.frame);
    dialog.setVisible(true);
    if (dialog.isCancelled() && LizzieFrame.toolbar != null) {
      LizzieFrame.toolbar.resetAutoAna();
    }
  }
}
