package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JMenu;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolbarParameterTogglesTest {
  @TempDir Path workDirectory;

  @Test
  void pdaAndBreadthAreShownOrHiddenOneAtATimeAndRemembered() {
    Config config = ConfigTestHelper.createForTests(workDirectory);
    config.uiConfig = new JSONObject();
    config.showPDAInMenu = true;
    config.showWRNInMenu = true;
    AtomicInteger refreshes = new AtomicInteger();
    ToolbarParameterToggles toggles =
        new ToolbarParameterToggles(
            config,
            ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE),
            refreshes::incrementAndGet);
    JMenu menu = new JMenu();
    toggles.addTo(menu);
    toggles.sync();

    assertEquals("激进度", menu.getItem(0).getText());
    assertEquals("广度", menu.getItem(1).getText());
    assertTrue(toggles.pda.isSelected() && toggles.wrn.isSelected());

    toggles.pda.doClick();

    assertFalse(config.showPDAInMenu);
    assertFalse(config.uiConfig.getBoolean("show-pda-in-menu"));
    assertTrue(config.showWRNInMenu, "the breadth field stays");
    assertFalse(config.uiConfig.has("show-wrn-in-menu"));
    assertEquals(1, refreshes.get());

    toggles.wrn.doClick();
    assertFalse(config.showWRNInMenu);
    assertFalse(config.uiConfig.getBoolean("show-wrn-in-menu"));
    assertEquals(2, refreshes.get());

    config.showPDAInMenu = true; // as the engine parameter dialog may set it
    toggles.sync();
    assertTrue(toggles.pda.isSelected());
    assertFalse(toggles.wrn.isSelected());
  }

  @Test
  void theBreadthFieldIsNotCalledAWarning() {
    for (Locale locale : new Locale[] {Locale.JAPAN, Locale.KOREAN}) {
      ResourceBundle strings = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      for (String key : new String[] {"Menu.lblWRN", "Menu.separateLblWrn"}) {
        String label = strings.getString(key);
        assertFalse(label.contains("警告") || label.contains("경고"), locale + " " + label);
      }
    }
  }
}
