package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NewEngineGameDialogHandicapApplyTest {
  // EngineManager only places handicap stones when startList == null && handicap >= 2.
  private static final int HANDICAP_STONE_PLACEMENT_THRESHOLD = 2;

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "   ", "\t", " \t "})
  void emptyOrBlankHandicapContinuesApplyAsEvenGameZero(String text) throws Exception {
    assertEquals(0, NewEngineGameDialog.parseEngineGameHandicap(true, text));

    NewEngineGameDialog.EngineGameHandicapApply apply =
        NewEngineGameDialog.EngineGameHandicapApply.fromField(true, text);

    assertTrue(
        apply.continues(),
        "empty/blank 让子 must continue apply() into startEngineGame(), not silently stall");
    assertFalse(apply.showsVisibleError(), "empty/blank 让子 must not show an error dialog");
    assertNull(apply.errorResourceKey());
    assertEquals(0, apply.handicap());
    assertTrue(
        apply.handicap() < HANDICAP_STONE_PLACEMENT_THRESHOLD,
        "0 is even game; EngineManager must not treat it as handicap placement");
  }

  @Test
  void zeroHandicapContinuesApplyAsEvenGame() throws Exception {
    assertEquals(0, NewEngineGameDialog.parseEngineGameHandicap(true, "0"));

    NewEngineGameDialog.EngineGameHandicapApply apply =
        NewEngineGameDialog.EngineGameHandicapApply.fromField(true, "0");

    assertTrue(apply.continues());
    assertFalse(apply.showsVisibleError());
    assertEquals(0, apply.handicap());
    assertTrue(apply.handicap() < HANDICAP_STONE_PLACEMENT_THRESHOLD);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "9"})
  void positiveIntegerHandicapContinuesApplyWithThatValue(String text) throws Exception {
    int expected = Integer.parseInt(text);
    assertEquals(expected, NewEngineGameDialog.parseEngineGameHandicap(true, text));

    NewEngineGameDialog.EngineGameHandicapApply apply =
        NewEngineGameDialog.EngineGameHandicapApply.fromField(true, text);

    assertTrue(apply.continues(), "legal positive handicap must not be silently blocked by parse");
    assertFalse(apply.showsVisibleError());
    assertEquals(expected, apply.handicap());
    assertTrue(apply.handicap() >= HANDICAP_STONE_PLACEMENT_THRESHOLD);
  }

  @Test
  void disabledHandicapFieldIsEvenGameZeroWithoutParsing() throws Exception {
    assertEquals(0, NewEngineGameDialog.parseEngineGameHandicap(false, ""));
    assertEquals(0, NewEngineGameDialog.parseEngineGameHandicap(false, "9"));
    assertEquals(0, NewEngineGameDialog.parseEngineGameHandicap(false, "not-a-number"));

    NewEngineGameDialog.EngineGameHandicapApply apply =
        NewEngineGameDialog.EngineGameHandicapApply.fromField(false, "not-a-number");
    assertTrue(apply.continues());
    assertEquals(0, apply.handicap());
  }

  @Test
  void illegalNonEmptyHandicapDoesNotStallSilently() {
    assertThrows(
        ParseException.class,
        () -> NewEngineGameDialog.parseEngineGameHandicap(true, "abc"));

    NewEngineGameDialog.EngineGameHandicapApply apply =
        NewEngineGameDialog.EngineGameHandicapApply.fromField(true, "abc");

    assertFalse(apply.continues(), "illegal non-empty 让子 must not continue apply()");
    assertTrue(apply.showsVisibleError(), "illegal non-empty 让子 must show a visible in-dialog error");
    assertEquals(
        NewEngineGameDialog.EngineGameHandicapApply.ERROR_RESOURCE_KEY, apply.errorResourceKey());
  }
}
