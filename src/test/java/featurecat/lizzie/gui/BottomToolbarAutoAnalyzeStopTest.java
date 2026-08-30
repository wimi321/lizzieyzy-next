package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class BottomToolbarAutoAnalyzeStopTest {

  @Test
  void manualStopNeverLooksLikeSuccessfulCompletion() {
    assertNotEquals(
        BottomToolbar.autoAnalyzeStatusKey(true),
        BottomToolbar.autoAnalyzeStatusKey(false));
    assertNotEquals(
        BottomToolbar.autoAnalyzeSavedStatusKey(true),
        BottomToolbar.autoAnalyzeSavedStatusKey(false));
    assertNotEquals(
        BottomToolbar.batchAutoAnalyzeStatusKey(true),
        BottomToolbar.batchAutoAnalyzeStatusKey(false));
  }

  @Test
  void manualBatchStopNeverStartsTheNextFile() {
    assertTrue(BottomToolbar.shouldContinueBatchAutoAnalysis(true, 3, 0));
    assertFalse(BottomToolbar.shouldContinueBatchAutoAnalysis(false, 3, 0));
    assertFalse(BottomToolbar.shouldContinueBatchAutoAnalysis(true, 3, 2));
    assertFalse(BottomToolbar.shouldContinueBatchAutoAnalysis(true, 0, 0));
    assertFalse(BottomToolbar.shouldContinueBatchAutoAnalysis(true, 3, -1));
  }

  @Test
  void supportedLocalesDescribeStoppedAndCompletedAsDifferentStates() {
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
      assertDistinctAndPresent(
          resources,
          BottomToolbar.autoAnalyzeStatusKey(true),
          BottomToolbar.autoAnalyzeStatusKey(false),
          locale);
      assertDistinctAndPresent(
          resources,
          BottomToolbar.autoAnalyzeSavedStatusKey(true),
          BottomToolbar.autoAnalyzeSavedStatusKey(false),
          locale);
      assertDistinctAndPresent(
          resources,
          BottomToolbar.batchAutoAnalyzeStatusKey(true),
          BottomToolbar.batchAutoAnalyzeStatusKey(false),
          locale);
    }
  }

  private static void assertDistinctAndPresent(
      ResourceBundle resources, String completedKey, String stoppedKey, Locale locale) {
    String completed = resources.getString(completedKey);
    String stopped = resources.getString(stoppedKey);
    assertFalse(completed.isBlank(), locale.toString());
    assertFalse(stopped.isBlank(), locale.toString());
    assertNotEquals(completed, stopped, locale.toString());
  }
}
