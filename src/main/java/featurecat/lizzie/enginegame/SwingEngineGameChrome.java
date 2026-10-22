package featurecat.lizzie.enginegame;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.EngineManager;
import featurecat.lizzie.gui.BottomToolbar;
import featurecat.lizzie.gui.EngineGameParametersDialog;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.Menu;
import featurecat.lizzie.gui.SetKataEngines;
import featurecat.lizzie.util.Utils;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import javax.swing.SwingUtilities;

/**
 * Production PK chrome adapter. Applies existing toolbar/menu control state on the EDT and owns no
 * product state.
 */
public class SwingEngineGameChrome implements EngineGameChrome {
  public static final SwingEngineGameChrome INSTANCE = new SwingEngineGameChrome();

  /** Last parameter failure shown as a popup, with the game it belonged to; EDT only. */
  private String shownParameterFailure;

  private EngineGameTransaction shownParameterFailureGame;

  @Override
  public void publish(EngineGameChromeTransition transition) {
    if (transition == null) {
      return;
    }
    Runnable apply = () -> applyOnEdt(transition);
    if (SwingUtilities.isEventDispatchThread()) {
      apply.run();
      return;
    }
    dispatch(apply);
  }

  void dispatch(Runnable apply) {
    SwingUtilities.invokeLater(apply);
  }

  void applyOnEdt(EngineGameChromeTransition transition) {
    BottomToolbar toolbar = LizzieFrame.toolbar;
    Menu menu = LizzieFrame.menu;
    switch (transition.kind()) {
      case STARTING -> applyStarting(toolbar, menu);
      case PLAYING, RESUMED -> applyRunning(toolbar, menu);
      case PAUSED -> applyPaused(toolbar, menu);
      case KOMI_CHANGED -> {
        if (!Lizzie.engineGame.current().playing()) applyIdle(toolbar, menu);
      }
      case BETWEEN_GAMES -> {
        if (menu != null) {
          menu.toggleDoubleMenuGameStatus();
        }
      }
      case START_FAILED, USER_STOPPED, BATCH_ENDED, LATER_GAME_FAILED -> applyIdle(toolbar, menu);
      case PARAMETERS_CHANGED -> {
        // The panel refresh and failure notice below cover it.
      }
      case PARAMETERS_RESUME_BLOCKED -> showMessage("EngineGameParameters.resumeBlocked", null);
      case PARAMETERS_RESTORE_FAILED ->
          showMessage(
              "EngineGameParameters.restoreFailed", Lizzie.engineGame.parameterRestoreFailure());
    }
    if (menu != null) menu.refreshEngineGameKomi();
    EngineGameParametersDialog.refreshIfOpen();
    SetKataEngines.refreshOpenDialogs();
    showParameterFailureOnce();
  }

  /** Pops up a parameter failure once per distinct failure, unless the panel shows it inline. */
  private void showParameterFailureOnce() {
    EngineGameParameterState state = Lizzie.engineGame.parameterState();
    String failure = state == null ? null : state.failure();
    if (failure == null) {
      shownParameterFailure = null;
      shownParameterFailureGame = null;
      return;
    }
    EngineGameTransaction game = Lizzie.engineGame.transaction();
    if (failure.equals(shownParameterFailure) && game == shownParameterFailureGame) {
      return;
    }
    shownParameterFailure = failure;
    shownParameterFailureGame = game;
    if (!EngineGameParametersDialog.isOpen()) {
      showMessage("EngineGameParameters.failed", failure);
    }
  }

  private static void showMessage(String key, String detail) {
    String pattern = text(key, null);
    if (pattern == null) {
      return;
    }
    Utils.showMsg(MessageFormat.format(pattern, detail == null ? "" : detail));
  }

  private static void applyStarting(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      toolbar.enableDisabelForEngineGame(false);
      setPauseButtonText(toolbar, false);
      if (toolbar.lblenginePkResult != null) {
        toolbar.lblenginePkResult.setText("0:0");
      }
    }
    if (Menu.engineMenu != null) {
      ResourceBundle bundle = Lizzie.resourceBundle;
      if (bundle != null && bundle.containsKey("EngineManager.engineGamePlaying")) {
        Menu.engineMenu.setText(bundle.getString("EngineManager.engineGamePlaying"));
      }
    }
  }

  private static void applyRunning(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      setPauseButtonText(toolbar, false);
    }
    if (menu != null) {
      menu.toggleDoubleMenuGameStatus();
    }
  }

  private static void applyPaused(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      setPauseButtonText(toolbar, true);
    }
    if (menu != null) {
      menu.toggleDoubleMenuGameStatus();
    }
  }

  private static void applyIdle(BottomToolbar toolbar, Menu menu) {
    if (toolbar != null) {
      toolbar.enableDisabelForEngineGame(true);
      setPauseButtonText(toolbar, false);
    }
    if (Menu.engineMenu != null) {
      Menu.engineMenu.setEnabled(true);
      restoreEngineMenuTitle();
    }
    if (menu != null) {
      menu.toggleDoubleMenuGameStatus();
    }
  }

  private static void restoreEngineMenuTitle() {
    if (EngineManager.isEmpty || Lizzie.leelaz == null) {
      Menu.engineMenu.setText(text("Menu.noEngine", "无引擎"));
      return;
    }
    int number = Lizzie.leelaz.currentEngineN() + 1;
    String name = Lizzie.leelaz.oriEnginename;
    if (name == null || name.isEmpty()) {
      name = Lizzie.leelaz.currentEnginename;
    }
    if (name == null) {
      name = "";
    }
    Menu.engineMenu.setText(text("EngineManager.engine", "引擎") + number + ": " + name);
  }

  private static void setPauseButtonText(BottomToolbar toolbar, boolean paused) {
    if (toolbar == null || toolbar.btnEnginePkStop == null) {
      return;
    }
    toolbar.btnEnginePkStop.setText(
        paused
            ? text("BottomToolbar.detail.continue", "继续")
            : text("BottomToolbar.detail.pause", "暂停"));
  }

  private static String text(String key, String fallback) {
    ResourceBundle bundle = Lizzie.resourceBundle;
    if (bundle == null || key == null) {
      return fallback;
    }
    try {
      return bundle.containsKey(key) ? bundle.getString(key) : fallback;
    } catch (RuntimeException unavailable) {
      return fallback;
    }
  }
}
