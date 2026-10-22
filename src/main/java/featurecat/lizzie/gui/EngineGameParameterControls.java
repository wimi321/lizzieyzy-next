package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGameSnapshot;
import featurecat.lizzie.enginegame.EngineGameView;
import java.awt.Component;
import java.awt.Insets;
import java.awt.Window;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/**
 * The views of the engine-game parameters outside the panel: the "game parameters" button next to
 * pause, the same entry in the game menu (the way in when the top toolbar is hidden or combined
 * into the menu bar), and the explanation on the ordinary breadth/aggressiveness controls, which a
 * running engine game locks because its participants take parameters only through the game panel.
 */
final class EngineGameParameterControls {
  private EngineGameParameterControls() {}

  /** The "game parameters" button; hidden until {@link #refreshEntry} sees an analysis game. */
  static JFontButton createEntry() {
    JFontButton button =
        new JFontButton(Lizzie.resourceBundle.getString("EngineGameParameters.entry"));
    button.setFocusable(false);
    button.setVisible(false);
    button.setMargin(new Insets(0, 0, 0, 0));
    button.addActionListener(
        event -> EngineGameParametersDialog.open(SwingUtilities.getWindowAncestor(button)));
    return button;
  }

  /**
   * Adds the "game parameters" entry to {@code menu}. It is refreshed from {@code snapshot} each
   * time the menu opens, with the toolbar button's visibility and enablement rules.
   */
  static JMenuItem addMenuEntry(JMenu menu, Supplier<EngineGameSnapshot> snapshot) {
    JFontMenuItem item =
        new JFontMenuItem(Lizzie.resourceBundle.getString("EngineGameParameters.entry"));
    item.setVisible(false);
    item.addActionListener(event -> EngineGameParametersDialog.open(ownerWindow(item)));
    menu.add(item);
    menu.addMenuListener(
        new MenuListener() {
          @Override
          public void menuSelected(MenuEvent event) {
            refreshEntry(item, snapshot.get());
          }

          @Override
          public void menuDeselected(MenuEvent event) {}

          @Override
          public void menuCanceled(MenuEvent event) {}
        });
    return item;
  }

  /**
   * The window a menu item was chosen in: popups are followed back to the menu that opened them.
   */
  private static Window ownerWindow(Component component) {
    for (Component c = component; c != null; ) {
      if (c instanceof Window window) return window;
      c = c instanceof JPopupMenu popup ? popup.getInvoker() : c.getParent();
    }
    return null;
  }

  /**
   * Shows the button for an analysis-mode engine game and enables it while a game is running or
   * paused. Between games the snapshot carries no play mode, so the batch keeps its visibility.
   */
  static void refreshEntry(JComponent button, EngineGameSnapshot snapshot) {
    if (button == null) return;
    EngineGameView view = snapshot.view();
    if (view != null) button.setVisible(!view.genmove());
    else if (!snapshot.betweenGames()) button.setVisible(false);
    button.setEnabled(button.isVisible() && snapshot.playing());
  }

  /**
   * Points the toolbar's breadth and aggressiveness controls at the game panel while an engine game
   * locks them, and restores their own tooltips afterwards. Enabled state is the caller's.
   */
  static void explainToolbarLock(
      boolean locked,
      JComponent chkWrn,
      JComponent txtWrn,
      JComponent chkPda,
      JComponent txtPda,
      JComponent customPda) {
    String lockedTip =
        locked ? Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel") : null;
    String wrnTip = locked ? lockedTip : Lizzie.resourceBundle.getString("Menu.chkWRN.toolTopText");
    String pdaTip = locked ? lockedTip : Lizzie.resourceBundle.getString("Menu.chkPDA.toolTopText");
    chkWrn.setToolTipText(wrnTip);
    txtWrn.setToolTipText(wrnTip);
    chkPda.setToolTipText(pdaTip);
    txtPda.setToolTipText(pdaTip);
    customPda.setToolTipText(lockedTip);
  }

  /**
   * Points the advanced-parameter dialog's breadth/aggressiveness editors at the game panel while
   * an engine game locks them, and clears that explanation afterwards. Enabled state is the
   * caller's.
   */
  static void explainAdvancedLock(boolean locked, JComponent... controls) {
    String tip =
        locked ? Lizzie.resourceBundle.getString("EngineGameParameters.useGamePanel") : null;
    for (JComponent control : controls) control.setToolTipText(tip);
  }
}
