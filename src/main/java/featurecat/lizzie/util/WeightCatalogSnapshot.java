package featurecat.lizzie.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Immutable metadata for one explicit directory scan; accessors never touch the filesystem. */
public final class WeightCatalogSnapshot {
  public record Entry(Path path, String modelName, boolean regularFile, Instant modified) {
    public String displayName() {
      return modelName.isEmpty()
          ? path.getFileName().toString()
          : KataGoAutoSetupHelper.resolveWeightDisplayName(modelName);
    }
  }

  private final Map<Path, Entry> entries;
  private final List<Entry> orderedEntries;
  private final Map<String, Path> models;

  private WeightCatalogSnapshot(Map<Path, Entry> entries) {
    this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    orderedEntries = List.copyOf(entries.values());
    Map<String, Path> index = new LinkedHashMap<>();
    for (Entry entry : entries.values()) {
      if (!entry.modelName().isEmpty()) {
        index.putIfAbsent(entry.modelName().toLowerCase(Locale.ROOT), entry.path());
      }
    }
    models = Collections.unmodifiableMap(index);
  }

  public static WeightCatalogSnapshot scan(Path activeWeight, Collection<Path> candidates) {
    return scan(activeWeight, candidates, KataGoAutoSetupHelper::readWeightModelName);
  }

  static WeightCatalogSnapshot scan(
      Path activeWeight, Collection<Path> candidates, Function<Path, String> identityReader) {
    Map<Path, Entry> entries = new LinkedHashMap<>();
    if (activeWeight != null) entries.put(activeWeight.toAbsolutePath().normalize(), null);
    for (Path candidate : candidates) {
      if (candidate != null) entries.putIfAbsent(candidate.toAbsolutePath().normalize(), null);
    }
    for (Path path : entries.keySet()) {
      if (Thread.currentThread().isInterrupted()) throw new CancellationException();
      boolean regular = false;
      Instant modified = null;
      try {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        regular = attributes.isRegularFile();
        modified = attributes.lastModifiedTime().toInstant();
      } catch (IOException | SecurityException ignored) {
        // Deleted/inaccessible entries remain present with a filename fallback.
      }
      String modelName;
      try {
        modelName = identityReader.apply(path);
      } catch (SecurityException inaccessible) {
        modelName = "";
      }
      entries.put(path, new Entry(path, modelName, regular, modified));
    }
    return new WeightCatalogSnapshot(entries);
  }

  public List<Entry> entries() {
    return orderedEntries;
  }

  public Entry entry(Path path) {
    return path == null ? null : entries.get(path.toAbsolutePath().normalize());
  }

  public String modelName(Path path) {
    Entry entry = entry(path);
    return entry == null ? "" : entry.modelName();
  }

  public String displayName(Path path) {
    Entry entry = entry(path);
    return entry != null
        ? entry.displayName()
        : path == null || path.getFileName() == null ? "" : path.getFileName().toString();
  }

  public Path findModel(String modelName, Path preferredPath) {
    if (modelName == null || modelName.isEmpty()) return null;
    if (modelName.equalsIgnoreCase(modelName(preferredPath))) return preferredPath;
    return models.get(modelName.toLowerCase(Locale.ROOT));
  }
}
