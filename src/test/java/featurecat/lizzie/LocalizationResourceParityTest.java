package featurecat.lizzie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LocalizationResourceParityTest {
  private static final Path ROOT = Path.of("src/main/resources/l10n");
  private static final List<String> FILES =
      List.of(
          "DisplayStrings.properties",
          "DisplayStrings_en_US.properties",
          "DisplayStrings_zh_CN.properties",
          "DisplayStrings_zh_TW.properties",
          "DisplayStrings_zh_HK.properties",
          "DisplayStrings_ja_JP.properties",
          "DisplayStrings_ko.properties",
          "DisplayStrings_th_TH.properties");
  private static final Pattern PLACEHOLDER =
      Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]|\\{\\d+(?:,[^}]*)?}");
  private static final Pattern HAN_CHARACTER = Pattern.compile("[\\p{IsHan}]");

  @Test
  void allMaintainedBundlesHaveIdenticalKeysOrderAndPlaceholders() throws IOException {
    ResourceFile base = read(ROOT.resolve(FILES.get(0)));
    assertTrue(base.duplicates.isEmpty(), "duplicate base keys: " + base.duplicates);
    for (Map.Entry<String, String> entry : base.values.entrySet()) {
      assertFalse(
          HAN_CHARACTER.matcher(entry.getValue()).find(),
          "English fallback contains untranslated text: " + entry.getKey());
    }

    for (String name : FILES) {
      ResourceFile candidate = read(ROOT.resolve(name));
      assertTrue(candidate.duplicates.isEmpty(), name + " duplicate keys: " + candidate.duplicates);
      assertEquals(base.values.keySet(), candidate.values.keySet(), name + " key set/order");
      for (Map.Entry<String, String> entry : base.values.entrySet()) {
        String key = entry.getKey();
        assertFalse(candidate.values.get(key).isBlank(), name + " blank value for " + key);
        assertEquals(
            placeholders(entry.getValue()),
            placeholders(candidate.values.get(key)),
            name + " placeholders for " + key);
      }
    }
  }

  @Test
  void simplifiedChineseRemoteComputeUsesCanonicalZhiziBrandAndNaturalCopy()
      throws IOException {
    ResourceFile simplified = read(ROOT.resolve("DisplayStrings_zh_CN.properties"));
    assertEquals("智子云算力", simplified.values.get("RemoteCompute.zhizi"));

    List<String> forbiddenFragments =
        List.of("知子", "直子", "-click", "顶部-up", "KaTrain-compatible", "VIP worker");
    for (Map.Entry<String, String> entry : simplified.values.entrySet()) {
      if (!entry.getKey().startsWith("RemoteCompute.")) {
        continue;
      }
      for (String fragment : forbiddenFragments) {
        assertFalse(
            entry.getValue().contains(fragment),
            entry.getKey() + " contains an invalid Chinese localization fragment: " + fragment);
      }
    }
  }

  private static ResourceFile read(Path path) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    List<String> duplicates = new ArrayList<>();
    int lineNumber = 0;
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      lineNumber++;
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
        continue;
      }
      int separator = line.indexOf('=');
      if (separator < 0) {
        continue;
      }
      String key = line.substring(0, separator).trim();
      String value = line.substring(separator + 1);
      if (values.put(key, value) != null) {
        duplicates.add(key + "@" + lineNumber);
      }
    }
    return new ResourceFile(values, duplicates);
  }

  private static List<String> placeholders(String value) {
    List<String> result = new ArrayList<>();
    Matcher matcher = PLACEHOLDER.matcher(value);
    while (matcher.find()) {
      result.add(matcher.group());
    }
    result.sort(String::compareTo);
    return result;
  }

  private static final class ResourceFile {
    private final Map<String, String> values;
    private final List<String> duplicates;

    private ResourceFile(Map<String, String> values, List<String> duplicates) {
      this.values = values;
      this.duplicates = duplicates;
    }
  }
}
