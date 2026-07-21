package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.Test;

class AutoAnalyzeMenuTest {

  @Test
  void wholeGameDeepAnalysisIsTheFirstRecommendedAction() {
    ResourceBundle resources =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
    AtomicInteger activations = new AtomicInteger();

    JPopupMenu popup = AutoAnalyzeMenu.create(resources, activations::incrementAndGet);

    assertEquals(12, popup.getComponentCount());
    JMenuItem first = assertInstanceOf(JMenuItem.class, popup.getComponent(0));
    assertEquals("整盘精析（推荐）", first.getText());
    assertEquals("ctrl pressed B", first.getAccelerator().toString());
    assertEquals(
        AutoAnalyzeMenu.WHOLE_GAME_ACTION,
        first.getClientProperty(AutoAnalyzeMenu.ACTION_PROPERTY));
    first.doClick();
    assertEquals(1, activations.get());
    assertEquals(resources.getString("Menu.autoAnalyze"), menuItem(popup, 1).getText());
    assertInstanceOf(JPopupMenu.Separator.class, popup.getComponent(2));
  }

  @Test
  void supportedLocalesExplainTheConsolidatedAnalysisEntry() {
    List<Locale> locales =
        List.of(
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
            Locale.US,
            Locale.JAPAN,
            Locale.KOREA,
            Locale.forLanguageTag("th-TH"));

    for (Locale locale : locales) {
      ResourceBundle resources = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      assertFalse(resources.getString("BottomToolbar.autoAnalyzeTip").isBlank(), locale.toString());
      assertFalse(resources.getString("Menu.flashAnalyzeAllGame").isBlank(), locale.toString());
      assertFalse(resources.getString("Menu.flashAnalyzePartGame").isBlank(), locale.toString());
      assertFalse(resources.getString("Menu.flashAnalyzeAllBranches").isBlank(), locale.toString());
      assertFalse(resources.getString("Menu.flashAnalyzeSettings").isBlank(), locale.toString());
    }
  }

  private static JMenuItem menuItem(JPopupMenu popup, int index) {
    return assertInstanceOf(JMenuItem.class, popup.getComponent(index));
  }
}
