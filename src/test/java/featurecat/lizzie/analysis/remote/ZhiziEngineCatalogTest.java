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
  void parsesLiveCatalogAndMarksEveryServerOptionAsConfirmed() throws Exception {
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
                    .put(option("20b", "20b,让子棋常用权重"))
                    .put(option("10b384t", "v1.17 small transformer"))
                    .put(option("10b512t", "v1.17 medium transformer"))
                    .put(option("11b768t", "v1.17 large transformer"))
                    .put(option("../../unsafe", "must be ignored"))
                    .put(option("28bnbt", "duplicate")));

    ZhiziEngineCatalog catalog = ZhiziEngineCatalog.fromServerCapabilities(json.toString());

    assertEquals("8.0.1", catalog.serverVersion());
    assertEquals("28bnbt", catalog.defaultWeight());
    assertEquals(
        List.of("18bnbt", "28bnbt", "fdx", "20b", "10b384t", "10b512t", "11b768t"), names(catalog));
    assertFalse(catalog.containsWeight("../../unsafe"));
    assertTrue(
        catalog.weights().stream()
            .allMatch(
                option ->
                    option.source() == ZhiziEngineCatalog.DiscoverySource.SERVER_CAPABILITIES));
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
  void fallbackContainsTheCurrentVerifiedCatalog() {
    ZhiziEngineCatalog catalog = ZhiziEngineCatalog.fallback();

    assertEquals("28bnbt", catalog.defaultWeight());
    assertEquals(
        List.of("18bnbt", "28bnbt", "fdx", "20b", "10b384t", "10b512t", "11b768t"), names(catalog));
    assertEquals("40B NBT extra-large weight", description(catalog, "fdx"));
    assertEquals(ZhiziEngineCatalog.DiscoverySource.BUILT_IN_CURRENT, source(catalog, "10b512t"));
  }

  @Test
  void documentedWeightsCompleteOldCachesWithoutMislabelingLegacyOptions() throws Exception {
    ZhiziEngineCatalog oldCache =
        new ZhiziEngineCatalog(
            "7.9.0",
            "40b",
            List.of(
                new ZhiziEngineCatalog.Option("28bnbt", "server description"),
                new ZhiziEngineCatalog.Option("40b", "legacy large network"),
                new ZhiziEngineCatalog.Option("20b", "legacy handicap network")));

    ZhiziEngineCatalog completed = oldCache.withDocumentedWeights();

    assertEquals(
        List.of("18bnbt", "28bnbt", "fdx", "20b", "10b384t", "10b512t", "11b768t", "40b"),
        names(completed));
    assertEquals("server description", description(completed, "28bnbt"));
    assertEquals(
        ZhiziEngineCatalog.DiscoverySource.OFFICIAL_DOCUMENTED, source(completed, "28bnbt"));
    assertEquals(ZhiziEngineCatalog.DiscoverySource.CACHED_LEGACY, source(completed, "40b"));
    assertEquals(ZhiziEngineCatalog.DiscoverySource.BUILT_IN_CURRENT, source(completed, "20b"));
  }

  @Test
  void oldCacheWithoutSourceMigratesToLegacyWhileStoredSourcesRoundTrip() throws Exception {
    JSONObject oldCache =
        new JSONObject()
            .put("defaultKataWeight", "40b")
            .put("supportKataWeights", new JSONArray().put(option("40b", "old cached option")));

    ZhiziEngineCatalog migrated = ZhiziEngineCatalog.fromJson(oldCache).withDocumentedWeights();
    assertEquals(ZhiziEngineCatalog.DiscoverySource.CACHED_LEGACY, source(migrated, "40b"));

    ZhiziEngineCatalog restored = ZhiziEngineCatalog.fromJson(migrated.toJson());
    assertEquals(ZhiziEngineCatalog.DiscoverySource.CACHED_LEGACY, source(restored, "40b"));
    assertEquals(
        ZhiziEngineCatalog.DiscoverySource.OFFICIAL_DOCUMENTED, source(restored, "28bnbt"));
  }

  @Test
  void safeFutureServerNamesAreSelectableButArgumentInjectionIsRejected() {
    assertTrue(ZhiziEngineCatalog.isDocumentedWeight("28bnbt"));
    assertTrue(ZhiziEngineCatalog.isLegacyCompatibleWeight("60b"));
    assertTrue(ZhiziEngineCatalog.isSelectableWeight("20b"));
    assertTrue(ZhiziEngineCatalog.isSelectableWeight("future-net"));
    assertTrue(ZhiziEngineCatalog.isSelectableWeight("11b768t"));
    assertFalse(ZhiziEngineCatalog.isSelectableWeight("28bnbt --gpu-type 24x"));
  }

  @Test
  void futureServerCapabilitiesMayConfirmAndSelectACompatibleLegacyWeight() throws Exception {
    ZhiziEngineCatalog serverCatalog =
        new ZhiziEngineCatalog(
            "future-api",
            "40b",
            List.of(
                new ZhiziEngineCatalog.Option(
                    "40b",
                    "server-confirmed large network",
                    ZhiziEngineCatalog.DiscoverySource.SERVER_CAPABILITIES)));

    ZhiziEngineCatalog completed = serverCatalog.withDocumentedWeights();

    assertEquals("40b", completed.defaultWeight());
    assertEquals(ZhiziEngineCatalog.DiscoverySource.SERVER_CAPABILITIES, source(completed, "40b"));
    assertEquals(List.of("40b"), names(completed));
  }

  @Test
  void stringWeightEntriesFromOlderServersRemainSupported() throws Exception {
    JSONObject json =
        new JSONObject()
            .put("defaultKataWeight", "28bnbt")
            .put("supportKataWeights", new JSONArray().put("18bnbt").put("28bnbt").put("fdx"));

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

  private static ZhiziEngineCatalog.DiscoverySource source(
      ZhiziEngineCatalog catalog, String name) {
    return catalog.weights().stream()
        .filter(option -> name.equals(option.name()))
        .findFirst()
        .orElseThrow()
        .source();
  }
}
