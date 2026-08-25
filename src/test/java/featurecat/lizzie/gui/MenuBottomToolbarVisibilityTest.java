package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JCheckBoxMenuItem;
import org.junit.jupiter.api.Test;

class MenuBottomToolbarVisibilityTest {

  @Test
  void shownContractIsTwentySixPixelsAndVisible() {
    assertEquals(26, Menu.BOTTOM_TOOLBAR_SHOWN_HEIGHT);
    assertEquals(0, Menu.BOTTOM_TOOLBAR_HIDDEN_HEIGHT);
  }

  @Test
  void heightZeroSelectsInvisibleEvenIfVisibleFlagIsStale() {
    assertEquals(
        Menu.BottomToolbarMenuMark.INVISIBLE, Menu.bottomToolbarMenuMark(0, true));
    assertEquals(
        Menu.BottomToolbarMenuMark.INVISIBLE, Menu.bottomToolbarMenuMark(0, false));
  }

  @Test
  void shownHeightSelectsVisibleOnlyWhenTheToolbarIsShowing() {
    assertEquals(
        Menu.BottomToolbarMenuMark.VISIBLE, Menu.bottomToolbarMenuMark(26, true));
  }

  @Test
  void hideThatLeavesStaleShownHeightStillSelectsInvisible() {
    assertEquals(
        Menu.BottomToolbarMenuMark.INVISIBLE, Menu.bottomToolbarMenuMark(26, false));
  }

  @Test
  void detailedHeightSelectsDetailedOnlyWhileVisible() {
    assertEquals(
        Menu.BottomToolbarMenuMark.DETAILED, Menu.bottomToolbarMenuMark(70, true));
    assertEquals(
        Menu.BottomToolbarMenuMark.INVISIBLE, Menu.bottomToolbarMenuMark(70, false));
  }

  @Test
  void hideClickClearsShowCheckmarkWithoutWaitingForMenuSelected() {
    JCheckBoxMenuItem show = new JCheckBoxMenuItem("Show");
    JCheckBoxMenuItem hide = new JCheckBoxMenuItem("Invisible");
    JCheckBoxMenuItem detailed = new JCheckBoxMenuItem("Detailed");
    show.setState(true);
    hide.setState(true);

    Menu.syncBottomToolbarMenuMarks(show, hide, detailed, 0, false);

    assertFalse(show.getState());
    assertTrue(hide.getState());
    assertFalse(detailed.getState());
  }

  @Test
  void showClickChecksShowEvenIfSwingUncheckedTheAlreadySelectedItem() {
    JCheckBoxMenuItem show = new JCheckBoxMenuItem("Show");
    JCheckBoxMenuItem hide = new JCheckBoxMenuItem("Invisible");
    show.setState(false);
    hide.setState(false);

    Menu.syncBottomToolbarMenuMarks(show, hide, null, 26, true);

    assertTrue(show.getState());
    assertFalse(hide.getState());
  }

  @Test
  void menuSelectedUsesHeightAndVisibleTogether() {
    JCheckBoxMenuItem show = new JCheckBoxMenuItem("Show");
    JCheckBoxMenuItem hide = new JCheckBoxMenuItem("Invisible");
    show.setState(true);
    hide.setState(false);

    Menu.syncBottomToolbarMenuMarks(show, hide, null, 26, false);

    assertFalse(show.getState());
    assertTrue(hide.getState());
  }
}
