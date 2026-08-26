package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class EnginePkIdentityTest {

  @Test
  void missingIdentityFallsBackToFirstEngineNotAStaleIndex() {
    List<EngineData> engines = List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b"));

    assertEquals(0, EnginePkIdentity.resolveIndex(engines, "", "beta"));
    assertEquals(0, EnginePkIdentity.resolveIndex(engines, null, "beta"));
    assertEquals(0, EnginePkIdentity.resolveIndex(engines, "cmd-a", "alpha"));
  }

  @Test
  void restoreMatchesCommandsAfterReorderInsteadOfComboIndex() {
    JSONObject ui = new JSONObject();
    EnginePkIdentity.persistSelection(
        ui, List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b")), 0, 1);

    List<EngineData> reordered = List.of(engine("beta", "cmd-b"), engine("alpha", "cmd-a"));
    int[] restored = EnginePkIdentity.restoreIndexes(reordered, ui);

    assertEquals(1, restored[0], "black must follow cmd-a after it moved off index 0");
    assertEquals(0, restored[1], "white must follow cmd-b after it moved off index 1");
  }

  @Test
  void missingSavedEngineFallsBackSafelyWithoutPickingTheWrongSlot() {
    JSONObject ui = new JSONObject();
    List<EngineData> original =
        List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b"), engine("gamma", "cmd-g"));
    EnginePkIdentity.persistSelection(ui, original, 2, 1);

    List<EngineData> remaining = List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b"));
    int[] restored = EnginePkIdentity.restoreIndexes(remaining, ui);

    assertEquals(
        0,
        restored[0],
        "deleted black engine must not restore as whatever now occupies old index 2");
    assertEquals(1, restored[1], "white identity that still exists must still restore");
  }

  @Test
  void duplicateCommandsPreferMatchingName() {
    List<EngineData> engines =
        List.of(engine("first", "same-cmd"), engine("second", "same-cmd"));

    assertEquals(1, EnginePkIdentity.resolveIndex(engines, "same-cmd", "second"));
    assertEquals(0, EnginePkIdentity.resolveIndex(engines, "same-cmd", "missing-name"));
  }

  @Test
  void emptyEngineListDoesNotThrow() {
    assertEquals(-1, EnginePkIdentity.resolveIndex(List.of(), "cmd-a", "alpha"));
    assertEquals(-1, EnginePkIdentity.resolveIndex(null, "cmd-a", "alpha"));
  }

  @Test
  void persistDoesNotWriteComboIndexes() {
    JSONObject ui = new JSONObject();
    EnginePkIdentity.persistSelection(
        ui, List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b")), 1, 0);

    assertEquals("cmd-b", ui.getString(EnginePkIdentity.BLACK_COMMANDS_KEY));
    assertEquals("beta", ui.getString(EnginePkIdentity.BLACK_NAME_KEY));
    assertEquals("cmd-a", ui.getString(EnginePkIdentity.WHITE_COMMANDS_KEY));
    assertEquals("alpha", ui.getString(EnginePkIdentity.WHITE_NAME_KEY));
    assertFalse(ui.has("engine-pk-black-index"));
    assertFalse(ui.has("engine-pk-white-index"));
  }

  @Test
  void invalidIndexDoesNotWipeAPreviouslySavedIdentity() {
    JSONObject ui = new JSONObject();
    List<EngineData> engines = List.of(engine("alpha", "cmd-a"), engine("beta", "cmd-b"));
    EnginePkIdentity.persistSelection(ui, engines, 1, 0);

    EnginePkIdentity.persistSelection(ui, engines, -1, 99);

    assertEquals("cmd-b", ui.getString(EnginePkIdentity.BLACK_COMMANDS_KEY));
    assertEquals("cmd-a", ui.getString(EnginePkIdentity.WHITE_COMMANDS_KEY));
  }

  private static EngineData engine(String name, String commands) {
    EngineData engine = new EngineData();
    engine.name = name;
    engine.commands = commands;
    return engine;
  }
}
