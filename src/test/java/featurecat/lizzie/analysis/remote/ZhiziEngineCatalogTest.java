package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ZhiziEngineCatalogTest {
  @Test
  void parsesLiveCatalogAndFiltersUnsafeOrDuplicateOptions() throws Exception {
    JSONObject json =
        new JSONObject()
            .put("serverVersion", "8.0.1")
            .put("defaultKataWeight", "28bnbt")
            .put(
                "supportKataWeights",
                new JSONArray()
                    .put(option("18bnbt", "weight for 18bnbt"))
                    .put(option("28bnbt", "weight for 28bnbt"))
                    .put(option("fdx", "fdx超大权重"))
                    .put(option("60b", "60b权重"))
                    .put(option("40b", "40b权重"))
                    .put(option("20b", "20b,让子棋常用权重"))
                    .put(option("../../unsafe", "must be ignored"))
                    .put(option("28bnbt", "duplicate")));

    ZhiziEngineCatalog catalog = ZhiziEngineCatalog.fromJson(json);

    assertEquals("8.0.1", catalog.serverVersion());
    assertEquals("28bnbt", catalog.defaultWeight());
    assertEquals(List.of("18bnbt", "28bnbt", "fdx", "60b", "40b", "20b"), names(catalog));
    assertTrue(catalog.containsWeight("60b"));
    assertFalse(catalog.containsWeight("../../unsafe"));
  }

  @Test
  void cacheRoundTripPreservesServerOrderDescriptionsAndDefault() throws Exception {
    ZhiziEngineCatalog original =
        new ZhiziEngineCatalog(
            "8.0.1",
            "40b",
            List.of(
                new ZhiziEngineCatalog.Option("28bnbt", "default network"),
                new ZhiziEngineCatalog.Option("40b", "large network")));

    ZhiziEngineCatalog restored = ZhiziEngineCatalog.fromJson(original.toJson());

    assertEquals(original.serverVersion(), restored.serverVersion());
    assertEquals(original.defaultWeight(), restored.defaultWeight());
    assertEquals(original.weights(), restored.weights());
  }

  @Test
  void fallbackIncludesEveryConfirmedZhiziWeightInStableOrder() {
    ZhiziEngineCatalog catalog = ZhiziEngineCatalog.fallback();

    assertEquals("28bnbt", catalog.defaultWeight());
    assertEquals(
        List.of("18bnbt", "28bnbt", "fdx", "60b", "40b", "20b"), names(catalog));
    assertEquals("40B NBT extra-large weight", description(catalog, "fdx"));
    assertTrue(description(catalog, "20b").contains("handicap"));
  }

  @Test
  void confirmedWeightsCompleteOldCachesAndPreserveServerOnlyOptions() throws Exception {
    ZhiziEngineCatalog oldCache =
        new ZhiziEngineCatalog(
            "7.9.0",
            "28bnbt",
            List.of(
                new ZhiziEngineCatalog.Option("28bnbt", "server description"),
                new ZhiziEngineCatalog.Option("future-net", "future option")));

    ZhiziEngineCatalog completed = oldCache.withConfirmedWeights();

    assertEquals(
        List.of("18bnbt", "28bnbt", "fdx", "60b", "40b", "20b", "future-net"),
        names(completed));
    assertEquals("server description", description(completed, "28bnbt"));
    assertEquals("future option", description(completed, "future-net"));
  }

  @Test
  void stringWeightEntriesFromOlderServersRemainSupported() throws Exception {
    JSONObject json =
        new JSONObject()
            .put("defaultKataWeight", "28bnbt")
            .put(
                "supportKataWeights",
                new JSONArray().put("18bnbt").put("28bnbt").put("fdx"));

    ZhiziEngineCatalog catalog = ZhiziEngineCatalog.fromJson(json);

    assertEquals(List.of("18bnbt", "28bnbt", "fdx"), names(catalog));
  }

  @Test
  void emptyOrUntrustedCatalogIsRejected() {
    JSONObject json =
        new JSONObject()
            .put("defaultKataWeight", "../bad")
            .put("supportKataWeights", new JSONArray().put(option("bad value", "invalid")));

    assertThrows(IOException.class, () -> ZhiziEngineCatalog.fromJson(json));
  }

  private static JSONObject option(String name, String description) {
    return new JSONObject().put("name", name).put("description", description);
  }

  private static List<String> names(ZhiziEngineCatalog catalog) {
    return catalog.weights().stream().map(ZhiziEngineCatalog.Option::name).toList();
  }

  private static String description(ZhiziEngineCatalog catalog, String name) {
    return catalog.weights().stream()
        .filter(option -> name.equals(option.name()))
        .findFirst()
        .orElseThrow()
        .description();
  }
}
