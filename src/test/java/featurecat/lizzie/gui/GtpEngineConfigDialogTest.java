package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.analysis.gtpconfig.GtpConfigurationSchema;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GtpEngineConfigDialogTest {
  @Test
  void modeChangesEnableOnlyRelevantControlsAndKeepCompleteProfile() throws Exception {
    AtomicReference<GtpEngineConfigDialog.EditorPanel> panelRef = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () ->
            panelRef.set(
                new GtpEngineConfigDialog.EditorPanel(
                    GtpConfigurationSchema.parse(schema()),
                    new JSONObject().put("rankPreset", "6d"),
                    ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE))));
    GtpEngineConfigDialog.EditorPanel panel = panelRef.get();

    assertTrue(panel.isFieldEnabled("rankPreset"));
    assertFalse(panel.isFieldEnabled("maxTimeSeconds"));
    assertTrue(panel.hasAccessibleEditorNames());
    assertEquals("6d", panel.profile().getString("rankPreset"));
    assertTrue(GtpEngineConfigDialog.sameValue(60, 60.0));
    assertFalse(GtpEngineConfigDialog.sameValue(60, 59.999));

    SwingUtilities.invokeAndWait(() -> panel.setFieldValue("mode", "fixed-time"));
    assertFalse(panel.isFieldEnabled("rankPreset"));
    assertTrue(panel.isFieldEnabled("maxTimeSeconds"));
    assertEquals(4, panel.profile().length());

    SwingUtilities.invokeAndWait(panel::restoreDefaults);
    assertEquals("rank", panel.profile().getString("mode"));
    assertEquals("9d", panel.profile().getString("rankPreset"));
  }

  @Test
  void engineCommandChangesDoNotCarryAProfileToAnotherProgram() {
    EngineData previous = new EngineData();
    previous.commands = "ZenGTPX.exe";
    previous.gtpConfigurationProtocol = "zengtp-config";
    previous.gtpConfigurationProfile = new JSONObject().put("mode", "rank");

    EngineData unchanged = new EngineData();
    MoreEngines.copyGtpConfigurationForUnchangedCommand(unchanged, previous, "ZenGTPX.exe");
    EngineData changed = new EngineData();
    MoreEngines.copyGtpConfigurationForUnchangedCommand(changed, previous, "another-engine.exe");

    assertEquals("zengtp-config", unchanged.gtpConfigurationProtocol);
    assertEquals("rank", unchanged.gtpConfigurationProfile.getString("mode"));
    assertTrue(changed.gtpConfigurationProtocol.isEmpty());
    assertNull(changed.gtpConfigurationProfile);
  }

  private static JSONObject schema() {
    JSONObject mode = field("mode", "string", "basic", "rank");
    mode.put("enumValues", new JSONArray().put("rank").put("fixed-time").put("advanced"));
    JSONObject rank = field("rankPreset", "string", "basic", "9d");
    rank.put("enumValues", new JSONArray().put("6d").put("9d"));
    rank.put("activeWhen", "mode=rank");
    JSONObject time = field("maxTimeSeconds", "number", "basic", 60.0);
    time.put("minimum", 0);
    time.put("activeWhen", "mode=fixed-time|advanced");
    JSONObject threads = field("threads", "integer", "advanced", 4);
    threads.put("minimum", 1);
    JSONObject selected =
        new JSONObject()
            .put("mode", "rank")
            .put("rankPreset", "9d")
            .put("maxTimeSeconds", 60.0)
            .put("threads", 4);
    return new JSONObject()
        .put("protocol", "zengtp-config")
        .put("version", 1)
        .put("persistenceOwner", "client")
        .put("batchSemantics", "atomic")
        .put("fields", new JSONArray().put(mode).put(rank).put(time).put(threads))
        .put(
            "state",
            new JSONObject()
                .put("selected", selected)
                .put("effective", new JSONObject(selected.toString())));
  }

  private static JSONObject field(String name, String type, String group, Object defaultValue) {
    return new JSONObject()
        .put("name", name)
        .put("type", type)
        .put("group", group)
        .put("defaultValue", defaultValue)
        .put("apply", "next-search")
        .put("requiresRestart", false);
  }
}
