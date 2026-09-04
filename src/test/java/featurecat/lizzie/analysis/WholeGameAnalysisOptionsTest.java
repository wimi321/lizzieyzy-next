package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WholeGameAnalysisOptionsTest {
  @Test
  void builtInPresetsMapToTheirExactSearchLimits() {
    assertPreset(500, WholeGameAnalysisOptions.Preset.QUICK);
    assertPreset(1_000, WholeGameAnalysisOptions.Preset.STANDARD);
    assertPreset(3_000, WholeGameAnalysisOptions.Preset.DEEP);
    assertPreset(10_000, WholeGameAnalysisOptions.Preset.PROFESSIONAL);
  }

  @Test
  void validCustomSearchLimitIsPreservedExactly() {
    WholeGameAnalysisOptions options = WholeGameAnalysisOptions.of(12_345);

    assertTrue(options.isValid());
    assertEquals(12_345, options.deepVisits());
    assertEquals(WholeGameAnalysisOptions.Preset.CUSTOM, options.preset());
    assertEquals(12_345, options.requireValidVisits());
  }

  @Test
  void validationRejectsValuesOutsideTheSupportedRange() {
    WholeGameAnalysisOptions tooSmall = WholeGameAnalysisOptions.of(499);
    WholeGameAnalysisOptions tooLarge = WholeGameAnalysisOptions.of(1_000_001);

    assertFalse(tooSmall.isValid());
    assertEquals(WholeGameAnalysisOptions.Validation.BELOW_MINIMUM, tooSmall.validation());
    assertThrows(IllegalArgumentException.class, tooSmall::requireValidVisits);
    assertFalse(tooLarge.isValid());
    assertEquals(WholeGameAnalysisOptions.Validation.ABOVE_MAXIMUM, tooLarge.validation());
    assertThrows(IllegalArgumentException.class, tooLarge::requireValidVisits);
  }

  @Test
  void storedInvalidValueFallsBackToTheFirstRunDefault() {
    assertEquals(
        WholeGameAnalysisOptions.DEFAULT_VISITS,
        WholeGameAnalysisOptions.fromStored(0).deepVisits());
    assertEquals(
        WholeGameAnalysisOptions.DEFAULT_VISITS,
        WholeGameAnalysisOptions.fromStored(Integer.MAX_VALUE).deepVisits());
  }

  private static void assertPreset(int visits, WholeGameAnalysisOptions.Preset preset) {
    WholeGameAnalysisOptions options = WholeGameAnalysisOptions.of(visits);

    assertTrue(options.isValid());
    assertEquals(visits, options.deepVisits());
    assertEquals(preset, options.preset());
  }
}
