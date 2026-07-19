package featurecat.lizzie.analysis.gtpconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GtpConfigurationSchemaTest {
  @Test
  void parsesV1SchemaAndEvaluatesModeDependencies() {
    GtpConfigurationSchema schema = GtpConfigurationSchema.parse(schemaPayload());

    assertEquals("zengtp-config", schema.protocol());
    assertEquals(1, schema.version());
    assertEquals(4, schema.fields().size());
    assertTrue(schema.field("rankPreset").isActive(new JSONObject().put("mode", "rank")));
    assertFalse(schema.field("rankPreset").isActive(new JSONObject().put("mode", "fixed-time")));
    assertTrue(schema.field("maxTimeSeconds").isActive(new JSONObject().put("mode", "advanced")));
  }

  @Test
  void savedProfileOnlyOverridesKnownValidValues() {
    GtpConfigurationSchema schema = GtpConfigurationSchema.parse(schemaPayload());
    JSONObject saved =
        new JSONObject()
            .put("mode", "advanced")
            .put("rankPreset", "not-a-rank")
            .put("threads", 0)
            .put("unknown", 12);

    JSONObject values = schema.valuesWithSavedProfile(saved);

    assertEquals("advanced", values.getString("mode"));
    assertEquals("9d", values.getString("rankPreset"));
    assertEquals(4, values.getInt("threads"));
    assertFalse(values.has("unknown"));
  }

  @Test
  void rejectsUnsafeOrAmbiguousSchemas() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GtpConfigurationSchema.parse(
                new JSONObject(schemaPayload().toString()).put("batchSemantics", "partial")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GtpConfigurationSchema.parse(
                new JSONObject(schemaPayload().toString()).put("persistenceOwner", "engine")));

    JSONObject duplicate = new JSONObject(schemaPayload().toString());
    duplicate.getJSONArray("fields").put(new JSONObject(field("mode", "string", "rank")));
    assertThrows(IllegalArgumentException.class, () -> GtpConfigurationSchema.parse(duplicate));

    JSONObject unsupportedApply = new JSONObject(schemaPayload().toString());
    unsupportedApply.getJSONArray("fields").getJSONObject(0).put("apply", "restart");
    assertThrows(
        IllegalArgumentException.class, () -> GtpConfigurationSchema.parse(unsupportedApply));

    JSONObject restartRequired = new JSONObject(schemaPayload().toString());
    restartRequired.getJSONArray("fields").getJSONObject(0).put("requiresRestart", true);
    assertThrows(
        IllegalArgumentException.class, () -> GtpConfigurationSchema.parse(restartRequired));
  }

  static JSONObject schemaPayload() {
    JSONArray fields =
        new JSONArray()
            .put(enumField("mode", "basic", "rank", "rank", "fixed-time", "advanced"))
            .put(
                enumField("rankPreset", "basic", "9d", "6k", "1d", "9d")
                    .put("activeWhen", "mode=rank"))
            .put(
                field("maxTimeSeconds", "number", 60.0)
                    .put("minimum", 0)
                    .put("activeWhen", "mode=fixed-time|advanced"))
            .put(field("threads", "integer", 4).put("minimum", 1));
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
        .put("fields", fields)
        .put(
            "state",
            new JSONObject()
                .put("selected", selected)
                .put("effective", new JSONObject(selected.toString())));
  }

  static JSONObject field(String name, String type, Object defaultValue) {
    return new JSONObject()
        .put("name", name)
        .put("type", type)
        .put("group", "advanced")
        .put("defaultValue", defaultValue)
        .put("apply", "next-search")
        .put("requiresRestart", false);
  }

  private static JSONObject enumField(
      String name, String group, String defaultValue, String... values) {
    return field(name, "string", defaultValue)
        .put("group", group)
        .put("enumValues", new JSONArray(values));
  }
}
