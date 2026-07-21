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

  private AutoAnalyzeMenu() {}

  static JPopupMenu create(ResourceBundle resources) {
    return create(resources, () -> Lizzie.frame.openWholeGameDeepAnalysis());
  }

  static JPopupMenu create(ResourceBundle resources, Runnable wholeGameAction) {
    JPopupMenu popup = new JPopupMenu();

    JFontMenuItem wholeGame =
        item(
            resources,
            "Menu.flashAnalyzeAllGame",
            WHOLE_GAME_ACTION,
            wholeGameAction);
    wholeGame.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK));
    popup.add(wholeGame);
    popup.add(
        item(resources, "Menu.autoAnalyze", "auto-analysis", AutoAnalyzeMenu::openAutoAnalyze));

    popup.addSeparator();
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
