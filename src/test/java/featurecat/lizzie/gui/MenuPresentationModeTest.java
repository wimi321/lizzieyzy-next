package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MenuPresentationModeTest {
  @Test
  void linuxWaylandUsesNativeSwingMenuBar() {
    MenuPresentationMode mode =
        MenuPresentationMode.detect("Linux", Map.of("XDG_SESSION_TYPE", "wayland"), "auto");

    assertEquals(MenuPresentationMode.NATIVE_MENU_BAR, mode);
    assertTrue(mode.usesNativeMenuBar());
    assertEquals(0, mode.contentOffset(34));
  }

  @Test
  void waylandDisplayIsUsedWhenSessionTypeIsMissing() {
    assertTrue(
        MenuPresentationMode.isLinuxWayland("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0")));
  }

  @Test
  void x11AndNonLinuxDesktopsKeepExistingCustomStrip() {
    assertEquals(
        MenuPresentationMode.CUSTOM_STRIP,
        MenuPresentationMode.detect("Linux", Map.of("XDG_SESSION_TYPE", "x11"), "auto"));
    assertEquals(
        MenuPresentationMode.CUSTOM_STRIP,
        MenuPresentationMode.detect("Windows 11", Map.of("WAYLAND_DISPLAY", "wayland-0"), "auto"));
    assertFalse(
        MenuPresentationMode.isLinuxWayland("macOS", Map.of("XDG_SESSION_TYPE", "wayland")));
  }

  @Test
  void explicitOverrideSupportsTroubleshootingBothRenderers() {
    assertEquals(
        MenuPresentationMode.CUSTOM_STRIP,
        MenuPresentationMode.detect("Linux", Map.of("XDG_SESSION_TYPE", "wayland"), "custom"));
    assertEquals(
        MenuPresentationMode.NATIVE_MENU_BAR,
        MenuPresentationMode.detect("Linux", Map.of("XDG_SESSION_TYPE", "x11"), "native"));
  }
}
