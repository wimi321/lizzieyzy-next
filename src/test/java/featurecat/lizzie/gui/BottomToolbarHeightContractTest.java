package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BottomToolbarHeightContractTest {

  @Test
  void layoutMustNotResurrectAHiddenToolbarHeight() {
    assertEquals(0, BottomToolbar.reconcilePersistedToolbarHeight(0, 26));
    assertEquals(0, BottomToolbar.reconcilePersistedToolbarHeight(0, 70));
  }

  @Test
  void shownAndDetailedHeightsStillFollowLayout() {
    assertEquals(26, BottomToolbar.reconcilePersistedToolbarHeight(26, 26));
    assertEquals(70, BottomToolbar.reconcilePersistedToolbarHeight(26, 70));
    assertEquals(26, BottomToolbar.reconcilePersistedToolbarHeight(70, 26));
  }
}
