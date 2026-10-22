package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlayMode;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameSnapshotFixtures;
import featurecat.lizzie.enginegame.RunState;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The game menu's "game parameters" entry is the way into the panel when the top toolbar is hidden
 * or combined into the menu bar; it follows the running engine game whenever the menu opens.
 */
class EngineGameParameterControlsTest {
  private ResourceBundle previousBundle;

  @BeforeEach
  void useBundle() {
    previousBundle = Lizzie.resourceBundle;
    Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings");
  }

  @AfterEach
  void restoreBundle() {
    Lizzie.resourceBundle = previousBundle;
  }

  @Test
  void gameMenuOffersThePanelWhileAnAnalysisGameIsRunningOrPaused() throws Exception {
    AtomicReference<EngineGameSnapshot> snapshot = new AtomicReference<>(idle());
    SwingUtilities.invokeAndWait(
        () -> {
          JMenu menu = new JMenu("game");
          JMenuItem entry = EngineGameParameterControls.addMenuEntry(menu, snapshot::get);
          assertEquals(
              Lizzie.resourceBundle.getString("EngineGameParameters.entry"), entry.getText());
          assertTrue(java.util.List.of(menu.getMenuComponents()).contains(entry));

          snapshot.set(EngineGameSnapshotFixtures.playingAnalysis());
          open(menu);
          assertTrue(entry.isVisible() && entry.isEnabled(), "running analysis game");

          snapshot.set(
              EngineGameSnapshotFixtures.playing(EngineGamePlayMode.ANALYSIS, RunState.PAUSED));
          open(menu);
          assertTrue(entry.isVisible() && entry.isEnabled(), "paused analysis game");
        });
  }

  @Test
  void gameMenuHidesThePanelOutsideAnAnalysisGame() throws Exception {
    AtomicReference<EngineGameSnapshot> snapshot =
        new AtomicReference<>(EngineGameSnapshotFixtures.playingAnalysis());
    SwingUtilities.invokeAndWait(
        () -> {
          JMenu menu = new JMenu("game");
          JMenuItem entry = EngineGameParameterControls.addMenuEntry(menu, snapshot::get);
          open(menu);
          assertTrue(entry.isVisible());

          snapshot.set(idle());
          open(menu);
          assertFalse(entry.isVisible(), "no engine game");

          snapshot.set(
              EngineGameSnapshotFixtures.playing(EngineGamePlayMode.GENMOVE, RunState.RUNNING));
          open(menu);
          assertFalse(entry.isVisible(), "genmove game has no game parameters");
        });
  }

  private static EngineGameSnapshot idle() {
    return new EngineGameSnapshot.Idle();
  }

  /** Opening a menu selects it, which is when Swing notifies its menu listeners. */
  private static void open(JMenu menu) {
    menu.setSelected(false);
    menu.setSelected(true);
  }
}
