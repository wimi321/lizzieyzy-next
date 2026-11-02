package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class NameWinratesTest {
  @Test
  void hiddenUntilANameIsClickedAndHiddenAgainByTheNextClick() {
    JSONObject settings = new JSONObject();
    assertFalse(NameWinrates.shown(settings));
    assertFalse(NameWinrates.shown(null));

    NameWinrates.toggle(settings);
    assertTrue(NameWinrates.shown(settings));
    assertTrue(settings.getBoolean(NameWinrates.SHOWN_KEY), "kept in the UI settings");

    NameWinrates.toggle(settings);
    assertFalse(NameWinrates.shown(settings));
  }

  @Test
  void eachNameGetsItsSidesWinRateNextToTheStones() {
    WinrateReading reading = new WinrateReading(56.34, true);

    assertEquals("Shusaku  56.3%", NameWinrates.black("Shusaku", reading));
    assertEquals("43.7%  Gennan", NameWinrates.white("Gennan", reading));
  }

  @Test
  void theTwoWinRatesAlwaysMakeAHundred() {
    WinrateReading reading = new WinrateReading(33.35, true);

    assertEquals("B  33.4%", NameWinrates.black("B", reading));
    assertEquals("66.6%  W", NameWinrates.white("W", reading));
    assertEquals("B  100.0%", NameWinrates.black("B", new WinrateReading(100, true)));
    assertEquals("0.0%  W", NameWinrates.white("W", new WinrateReading(100, true)));
  }

  @Test
  void beforeAnyAnalysisTheWinRatesAreDashes() {
    assertEquals("B  --", NameWinrates.black("B", WinrateReading.UNKNOWN));
    assertEquals("--  W", NameWinrates.white("W", WinrateReading.UNKNOWN));
  }
}
