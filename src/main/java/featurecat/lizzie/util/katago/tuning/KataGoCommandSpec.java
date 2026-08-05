package featurecat.lizzie.util.katago.tuning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * An immutable, token-based KataGo command with normalized access to {@code override-config}
 * entries.
 *
 * <p>Parsing keeps every non-override argument in its original order. Override entries are keyed in
 * first-seen order, while a later occurrence of the same key supplies the effective value. Commands
 * produced by this class contain at most one {@code -override-config} option.
 */
public final class KataGoCommandSpec {
  private static final String OVERRIDE_CONFIG = "-override-config";
  private static final String LONG_OVERRIDE_CONFIG = "--override-config";

  private final List<String> arguments;
  private final int overrideInsertionIndex;
  private final LinkedHashMap<String, OverrideEntry> overrides;

  private KataGoCommandSpec(
      List<String> arguments,
      int overrideInsertionIndex,
      LinkedHashMap<String, OverrideEntry> overrides) {
    this.arguments = List.copyOf(arguments);
    this.overrideInsertionIndex = overrideInsertionIndex;
    this.overrides = new LinkedHashMap<>(overrides);
  }

  /** Parses command tokens without interpreting or rewriting non-override arguments. */
  public static KataGoCommandSpec parse(List<String> command) {
    Objects.requireNonNull(command, "command");

    List<String> arguments = new ArrayList<>(command.size());
    LinkedHashMap<String, OverrideEntry> overrides = new LinkedHashMap<>();
    int overrideInsertionIndex = -1;

    for (int index = 0; index < command.size(); index++) {
      String token = Objects.requireNonNull(command.get(index), "command token");
      if (!isOverrideOption(token)) {
        arguments.add(token);
        continue;
      }

      if (overrideInsertionIndex < 0) {
        overrideInsertionIndex = arguments.size();
      }
      if (index + 1 >= command.size()) {
        throw new IllegalArgumentException("Missing value after " + token);
      }
      parseOverrides(command.get(++index), overrides);
    }

    return new KataGoCommandSpec(arguments, overrideInsertionIndex, overrides);
  }

  /** Returns the effective value for an exact, case-sensitive override key. */
  public Optional<String> overrideValue(String key) {
    Objects.requireNonNull(key, "key");
    OverrideEntry entry = overrides.get(key);
    return entry == null ? Optional.empty() : Optional.of(entry.value);
  }

  /** Returns whether any effective override key satisfies {@code keyPredicate}. */
  public boolean hasOverrideMatching(Predicate<String> keyPredicate) {
    Objects.requireNonNull(keyPredicate, "keyPredicate");
    for (String key : overrides.keySet()) {
      if (keyPredicate.test(key)) {
        return true;
      }
    }
    return false;
  }

  /** Returns the effective override map after applying KataGo's last-value-wins semantics. */
  public Map<String, String> effectiveOverrides() {
    LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
    for (OverrideEntry entry : overrides.values()) {
      result.put(entry.key, entry.value);
    }
    return Map.copyOf(result);
  }

  /**
   * Adds defaults for managed keys that have no explicit value in the parsed command.
   *
   * <p>Existing values always win. Unknown arguments and overrides are retained, duplicate keys are
   * collapsed to their effective values, and newly added keys are ordered lexicographically for
   * deterministic output.
   */
  public List<String> withManagedOverrides(Map<String, String> managedOverrides) {
    LinkedHashMap<String, OverrideEntry> merged = copyOverrides();
    for (Map.Entry<String, String> entry : sortedEntries(managedOverrides)) {
      if (!merged.containsKey(entry.getKey())) {
        merged.put(entry.getKey(), OverrideEntry.assigned(entry.getKey(), entry.getValue()));
      }
    }
    return render(merged);
  }

  /**
   * Forces the supplied values while retaining unknown arguments and overrides.
   *
   * <p>Existing keys keep their original position but receive the forced value. Newly added keys
   * are ordered lexicographically for deterministic output.
   */
  public List<String> withForcedOverrides(Map<String, String> forcedOverrides) {
    LinkedHashMap<String, OverrideEntry> merged = copyOverrides();
    for (Map.Entry<String, String> entry : sortedEntries(forcedOverrides)) {
      merged.put(entry.getKey(), OverrideEntry.assigned(entry.getKey(), entry.getValue()));
    }
    return render(merged);
  }

  private static boolean isOverrideOption(String token) {
    return OVERRIDE_CONFIG.equals(token) || LONG_OVERRIDE_CONFIG.equals(token);
  }

  private static void parseOverrides(
      String argument, LinkedHashMap<String, OverrideEntry> overrides) {
    Objects.requireNonNull(argument, "override-config value");
    for (String candidate : argument.split(",", -1)) {
      String trimmed = candidate.trim();
      if (trimmed.isEmpty()) {
        continue;
      }

      int separator = trimmed.indexOf('=');
      String key = separator < 0 ? trimmed : trimmed.substring(0, separator).trim();
      String value = separator < 0 ? "" : trimmed.substring(separator + 1).trim();
      if (key.isEmpty()) {
        continue;
      }
      overrides.put(key, new OverrideEntry(key, value, separator >= 0));
    }
  }

  private LinkedHashMap<String, OverrideEntry> copyOverrides() {
    return new LinkedHashMap<>(overrides);
  }

  private List<String> render(LinkedHashMap<String, OverrideEntry> effectiveOverrides) {
    List<String> result = new ArrayList<>(arguments);
    if (effectiveOverrides.isEmpty()) {
      return result;
    }

    StringBuilder value = new StringBuilder();
    for (OverrideEntry entry : effectiveOverrides.values()) {
      if (value.length() > 0) {
        value.append(',');
      }
      value.append(entry.key);
      if (entry.assigned) {
        value.append('=').append(entry.value);
      }
    }

    int insertionIndex = overrideInsertionIndex < 0 ? result.size() : overrideInsertionIndex;
    result.add(insertionIndex, OVERRIDE_CONFIG);
    result.add(insertionIndex + 1, value.toString());
    return result;
  }

  private static List<Map.Entry<String, String>> sortedEntries(Map<String, String> entries) {
    Objects.requireNonNull(entries, "overrides");
    List<Map.Entry<String, String>> sorted = new ArrayList<>(entries.size());
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      String key = Objects.requireNonNull(entry.getKey(), "override key").trim();
      String value = Objects.requireNonNull(entry.getValue(), "override value").trim();
      if (key.isEmpty()) {
        throw new IllegalArgumentException("Override key must not be empty");
      }
      sorted.add(Map.entry(key, value));
    }
    sorted.sort(Comparator.comparing(Map.Entry::getKey));
    return sorted;
  }

  private static final class OverrideEntry {
    private final String key;
    private final String value;
    private final boolean assigned;

    private OverrideEntry(String key, String value, boolean assigned) {
      this.key = key;
      this.value = value;
      this.assigned = assigned;
    }

    private static OverrideEntry assigned(String key, String value) {
      return new OverrideEntry(key, value, true);
    }
  }
}
