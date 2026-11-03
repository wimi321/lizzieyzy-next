package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JToolBar;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuggestionLimitFieldTest {
  @TempDir Path workDirectory;
  private final AtomicInteger relayouts = new AtomicInteger();
  private final AtomicInteger repaints = new AtomicInteger();

  @Test
  void theFieldIsHiddenUntilTheCustomToolbarMenuShowsIt() {
    Config config = config();
    SuggestionLimitField limit = field(config);
    assertFalse(limit.shown(), "hidden by default");

    limit.toggle.doClick();

    assertTrue(limit.shown());
    assertTrue(config.uiConfig.getBoolean(SuggestionLimitField.SHOWN_KEY), "remembered");
    assertEquals(1, relayouts.get());
    JToolBar toolbar = new JToolBar();
    limit.addTo(toolbar);
    assertArrayEquals(new Component[] {limit.label, limit.field}, toolbar.getComponents());
    assertEquals("候选点", limit.label.getText());
    assertEquals("10", limit.field.getText());
    assertEquals(0, repaints.get(), "showing the field changes nothing on the board");
  }

  @Test
  void anEditSetsHowManyCandidatesTheBoardShowsAtOnce() {
    Config config = config();
    SuggestionLimitField limit = field(config);
    limit.sync();

    limit.field.setText("3");
    assertEquals(3, config.limitMaxSuggestion);
    assertEquals(3, config.leelazConfig.getInt("limit-max-suggestion"));
    assertEquals(1, repaints.get());

    limit.field.setText("");
    assertEquals(3, config.limitMaxSuggestion, "an emptied field keeps the limit");
    limit.field.setText("0");
    assertEquals(0, config.limitMaxSuggestion, "0 shows every candidate");
    assertEquals(2, repaints.get());
  }

  @Test
  void theFieldShowsWhatTheSettingsDialogChanged() {
    Config config = config();
    SuggestionLimitField limit = field(config);
    limit.sync();

    config.limitMaxSuggestion = 5;
    config.uiConfig.put(SuggestionLimitField.SHOWN_KEY, true);
    limit.sync();

    assertEquals("5", limit.field.getText());
    assertTrue(limit.toggle.isSelected());
    assertEquals(0, repaints.get(), "showing the dialog's number is not an edit");
  }

  @Test
  void everyLanguageNamesTheFieldAndExplainsZero() {
    for (Locale locale :
        new Locale[] {
          Locale.US,
          Locale.SIMPLIFIED_CHINESE,
          Locale.TRADITIONAL_CHINESE,
          Locale.forLanguageTag("zh-HK"),
          Locale.JAPAN,
          Locale.KOREAN,
          Locale.forLanguageTag("th-TH")
        }) {
      ResourceBundle strings = ResourceBundle.getBundle("l10n.DisplayStrings", locale);
      for (String key : new String[] {"Menu.showSuggestionLimit", "Menu.lblSuggestionLimit"}) {
        assertFalse(strings.getString(key).isBlank(), key + " in " + locale);
      }
      assertTrue(
          strings.getString("Menu.txtSuggestionLimit.toolTip").contains("0"), locale.toString());
    }
  }

  private SuggestionLimitField field(Config config) {
    return new SuggestionLimitField(
        config,
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE),
        relayouts::incrementAndGet,
        repaints::incrementAndGet);
  }

  private Config config() {
    Config config = ConfigTestHelper.createForTests(workDirectory);
    config.uiConfig = new JSONObject();
    config.leelazConfig = new JSONObject();
    config.limitMaxSuggestion = 10;
    return config;
  }
}
