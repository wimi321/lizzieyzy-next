package featurecat.lizzie.logging;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public final class WorkDirectoryResolver {
  static final String USER_WORK_DIR_NAME = ".lizzieyzy-next";
  static final String LEGACY_USER_WORK_DIR_NAME = ".lizzieyzy-next-foxuid";
  static final String WINDOWS_SHARED_WORK_DIR_NAME = "LizzieYzyNext";
  static final String WINDOWS_PORTABLE_MARKER_NAME = ".lizzie-portable";
  static final String WINDOWS_PORTABLE_WORK_DIR_NAME = "user-data";
  static final String WINDOWS_PORTABLE_STATE_MIGRATION_KEY =
      "migrated-windows-portable-user-state-v2";
  static final String WINDOWS_PORTABLE_RECOVERY_BACKUP_NAME = "config.before-portable-recovery.txt";
  private static final String BUNDLED_ENGINE_NAME = "KataGo Bundled";
  private static final String BUNDLED_ENGINE_ROOT = "engines";
  private static final String BUNDLED_WEIGHT_ROOT = "weights";

  private static final Object LOCK = new Object();
  private static WorkDirectoryResolution cached;

  private WorkDirectoryResolver() {}

  public static WorkDirectoryResolution resolve() {
    synchronized (LOCK) {
      if (cached == null) {
        cached = resolve(WorkDirectoryEnvironment.system());
      }
      return cached;
    }
  }

  public static WorkDirectoryResolution resolve(WorkDirectoryEnvironment environment) {
    List<WorkDirectoryDiagnostic> diagnostics = new ArrayList<>();
    Path directory = resolveDirectory(environment, diagnostics);
    return new WorkDirectoryResolution(directory, diagnostics);
  }

  static void resetCacheForTests() {
    synchronized (LOCK) {
      cached = null;
    }
  }

  public static Path resolveWritableFallbackDir() throws IOException {
    List<WorkDirectoryDiagnostic> diagnostics = new ArrayList<>();
    return resolveWritableFallbackDir(WorkDirectoryEnvironment.system(), diagnostics);
  }

  public static Optional<Path> findWindowsPortablePackageRootForTests(Path seedPath) {
    if (seedPath == null) {
      return Optional.empty();
    }
    return findWindowsPortablePackageRoot(Collections.singleton(seedPath), new ArrayList<>());
  }

  public static Path prepareWindowsPortableWorkDirForTests(Path portableRoot) throws IOException {
    return prepareWindowsPortableWorkDir(
        portableRoot, Collections.singletonList(portableRoot), new ArrayList<>());
  }

  public static Path prepareWindowsPortableWorkDirWithSourcesForTests(
      Path portableRoot, Path... migrationSources) throws IOException {
    return prepareWindowsPortableWorkDir(
        portableRoot, Arrays.asList(migrationSources), new ArrayList<>());
  }

  public static List<Path> windowsPortableMigrationSourcesForTests(Path portableRoot) {
    return windowsPortableMigrationSources(
        portableRoot, WorkDirectoryEnvironment.system(), new ArrayList<>());
  }

  public static List<Path> windowsPortableCredentialDirectoriesForTests(Path portableRoot) {
    return windowsPortableCredentialDirectories(
        portableRoot, WorkDirectoryEnvironment.system(), new ArrayList<>());
  }

  public static List<Path> legacyWindowsCredentialDirectories() {
    WorkDirectoryEnvironment environment = WorkDirectoryEnvironment.system();
    List<WorkDirectoryDiagnostic> diagnostics = new ArrayList<>();
    Optional<Path> portableRoot =
        findWindowsPortablePackageRoot(environment.portableSeedPaths(), diagnostics);
    if (portableRoot.isPresent()) {
      return windowsPortableCredentialDirectories(portableRoot.get(), environment, diagnostics);
    }
    Path credentials =
        resolve().directory().resolve("secure-credentials").toAbsolutePath().normalize();
    return Files.isDirectory(credentials)
        ? Collections.singletonList(credentials)
        : Collections.emptyList();
  }

  private static Path resolveDirectory(
      WorkDirectoryEnvironment environment, List<WorkDirectoryDiagnostic> diagnostics) {
    try {
      Path explicitWorkDir = resolveExplicitWorkDir(environment);
      if (explicitWorkDir != null) {
        return explicitWorkDir;
      }
    } catch (Exception e) {
      addError(diagnostics, "explicit-work-dir", e);
    }

    if (environment.windows()) {
      try {
        Optional<Path> portableRoot =
            findWindowsPortablePackageRoot(environment.portableSeedPaths(), diagnostics);
        if (portableRoot.isPresent()) {
          Path portableWorkDir =
              prepareWindowsPortableWorkDir(
                  portableRoot.get(),
                  windowsPortableMigrationSources(portableRoot.get(), environment, diagnostics),
                  diagnostics);
          if (portableWorkDir != null) {
            return portableWorkDir;
          }
        }
      } catch (Exception e) {
        addError(diagnostics, "windows-portable", e);
      }
      try {
        Path cwd = Path.of(environment.userDir()).toAbsolutePath().normalize();
        if (shouldUsePortableWindowsWorkDir(cwd)) {
          return cwd;
        }
      } catch (Exception e) {
        addError(diagnostics, "windows-cwd", e);
      }
      try {
        Path fallback = resolveWritableFallbackDir(environment, diagnostics);
        diagnostics.add(
            new WorkDirectoryDiagnostic(
                WorkDirectoryDiagnostic.Kind.INFO, "fallback", "Config dir fallback: " + fallback));
        return fallback;
      } catch (Exception e) {
        addError(diagnostics, "windows-fallback", e);
      }
    }

    try {
      Path cwd = Path.of(environment.userDir()).toAbsolutePath();
      if (Files.isWritable(cwd)) {
        return cwd;
      }
    } catch (Exception e) {
      addError(diagnostics, "cwd", e);
    }

    try {
      Path fallback = resolveWritableFallbackDir(environment, diagnostics);
      diagnostics.add(
          new WorkDirectoryDiagnostic(
              WorkDirectoryDiagnostic.Kind.INFO, "fallback", "Config dir fallback: " + fallback));
      return fallback;
    } catch (Exception e) {
      addError(diagnostics, "fallback", e);
      return Path.of(environment.userHome());
    }
  }

  private static Path resolveExplicitWorkDir(WorkDirectoryEnvironment environment)
      throws IOException {
    String configured = environment.explicitWorkDir().trim();
    if (configured.isEmpty()) {
      return null;
    }
    Path path = Path.of(configured).toAbsolutePath().normalize();
    Files.createDirectories(path.resolve("save"));
    return path;
  }

  private static Path resolveWritableFallbackDir(
      WorkDirectoryEnvironment environment, List<WorkDirectoryDiagnostic> diagnostics)
      throws IOException {
    if (environment.windows()) {
      return resolveWindowsWorkDir(environment, diagnostics);
    }

    Path preferred = Path.of(environment.userHome(), USER_WORK_DIR_NAME);
    Path legacy = Path.of(environment.userHome(), LEGACY_USER_WORK_DIR_NAME);

    if (!Files.exists(preferred) && Files.isDirectory(legacy)) {
      try {
        Files.move(legacy, preferred);
        diagnostics.add(
            new WorkDirectoryDiagnostic(
                WorkDirectoryDiagnostic.Kind.INFO,
                "migrated",
                "Migrated config dir to " + preferred));
      } catch (Exception moveError) {
        diagnostics.add(
            new WorkDirectoryDiagnostic(
                WorkDirectoryDiagnostic.Kind.WARNING,
                "migration-skipped",
                "Config dir migration skipped: " + moveError.getMessage()));
        Files.createDirectories(legacy.resolve("save"));
        return legacy;
      }
    }

    Files.createDirectories(preferred.resolve("save"));
    return preferred;
  }

  private static Path resolveWindowsWorkDir(
      WorkDirectoryEnvironment environment, List<WorkDirectoryDiagnostic> diagnostics)
      throws IOException {
    Path preferred = Path.of(environment.userHome(), USER_WORK_DIR_NAME);
    Path legacy = Path.of(environment.userHome(), LEGACY_USER_WORK_DIR_NAME);
    Path target = resolveWindowsSharedWorkDirCandidate(environment, diagnostics);

    migrateWorkDirIfNeeded(target, diagnostics, preferred, legacy);

    try {
      Path cwd = Path.of(environment.userDir()).toAbsolutePath().normalize();
      if (!target.equals(cwd)) {
        migrateWorkDirIfNeeded(target, diagnostics, cwd);
      }
    } catch (Exception e) {
      addError(diagnostics, "windows-cwd-migrate", e);
    }

    Files.createDirectories(target.resolve("save"));
    return target;
  }

  private static Path resolveWindowsSharedWorkDirCandidate(
      WorkDirectoryEnvironment environment, List<WorkDirectoryDiagnostic> diagnostics)
      throws IOException {
    for (Path candidate : windowsSharedWorkDirCandidates(environment)) {
      if (candidate == null || !isAsciiSafePath(candidate)) {
        continue;
      }
      try {
        Files.createDirectories(candidate.resolve("save"));
        if (Files.isWritable(candidate)) {
          return candidate;
        }
      } catch (IOException e) {
        addError(diagnostics, "windows-shared-candidate", e);
      }
    }

    Path preferred = Path.of(environment.userHome(), USER_WORK_DIR_NAME);
    Files.createDirectories(preferred.resolve("save"));
    return preferred;
  }

  private static List<Path> windowsSharedWorkDirCandidates(WorkDirectoryEnvironment environment) {
    List<Path> candidates = new ArrayList<>();
    addWindowsWorkDirCandidate(
        candidates, environment.publicDirectory(), "Documents", WINDOWS_SHARED_WORK_DIR_NAME);
    addWindowsWorkDirCandidate(
        candidates, environment.publicDirectory(), WINDOWS_SHARED_WORK_DIR_NAME);
    addWindowsWorkDirCandidate(
        candidates, environment.programDataDirectory(), WINDOWS_SHARED_WORK_DIR_NAME);
    return candidates;
  }

  private static void addWindowsWorkDirCandidate(
      List<Path> candidates, String root, String... children) {
    if (root == null || root.trim().isEmpty()) {
      return;
    }
    try {
      candidates.add(Path.of(root, children).toAbsolutePath().normalize());
    } catch (Exception ignored) {
    }
  }

  private static Optional<Path> findWindowsPortablePackageRoot(
      Collection<Path> seedPaths, List<WorkDirectoryDiagnostic> diagnostics) {
    if (seedPaths == null) {
      return Optional.empty();
    }
    for (Path seedPath : seedPaths) {
      if (seedPath == null) {
        continue;
      }
      Path current = seedPath.toAbsolutePath().normalize();
      for (int depth = 0; current != null && depth < 8; depth++) {
        if (Files.isRegularFile(current.resolve(WINDOWS_PORTABLE_MARKER_NAME))) {
          return Optional.of(current);
        }
        current = current.getParent();
      }
    }
    return Optional.empty();
  }

  private static Path prepareWindowsPortableWorkDir(
      Path portableRoot,
      Collection<Path> migrationSources,
      List<WorkDirectoryDiagnostic> diagnostics)
      throws IOException {
    if (portableRoot == null || !Files.isDirectory(portableRoot)) {
      return null;
    }
    Path workDir =
        portableRoot.resolve(WINDOWS_PORTABLE_WORK_DIR_NAME).toAbsolutePath().normalize();
    Files.createDirectories(workDir.resolve("save"));
    if (!Files.isWritable(workDir)) {
      return null;
    }
    Collection<Path> safeSources =
        migrationSources == null ? Collections.emptyList() : migrationSources;
    migrateWorkDirIfNeeded(workDir, diagnostics, safeSources.toArray(new Path[0]));
    return workDir;
  }

  private static List<Path> windowsPortableMigrationSources(
      Path portableRoot,
      WorkDirectoryEnvironment environment,
      List<WorkDirectoryDiagnostic> diagnostics) {
    LinkedHashSet<Path> sources = new LinkedHashSet<>();
    addMigrationSource(sources, portableRoot);
    addMigrationSource(sources, portableRoot.resolve("app"));

    Path parent = portableRoot.getParent();
    if (parent != null && Files.isDirectory(parent)) {
      try (Stream<Path> stream = Files.list(parent)) {
        for (Path sibling : (Iterable<Path>) stream::iterator) {
          if (!Files.isDirectory(sibling)
              || sibling.toAbsolutePath().normalize().equals(portableRoot)) {
            continue;
          }
          if (hasAppRootMarker(sibling)) {
            addMigrationSource(sources, sibling.resolve(WINDOWS_PORTABLE_WORK_DIR_NAME));
            addMigrationSource(sources, sibling);
            addMigrationSource(
                sources, sibling.resolve("app").resolve(WINDOWS_PORTABLE_WORK_DIR_NAME));
            addMigrationSource(sources, sibling.resolve("app"));
          }
        }
      } catch (IOException e) {
        diagnostics.add(
            new WorkDirectoryDiagnostic(
                WorkDirectoryDiagnostic.Kind.WARNING,
                "portable-sibling-scan",
                "Portable sibling scan skipped: " + e.getMessage()));
      }
    }

    for (Path shared : windowsSharedWorkDirCandidates(environment)) {
      addMigrationSource(sources, shared);
    }
    String userHome = environment.userHome().trim();
    if (!userHome.isEmpty()) {
      addMigrationSource(sources, Path.of(userHome, USER_WORK_DIR_NAME));
      addMigrationSource(sources, Path.of(userHome, LEGACY_USER_WORK_DIR_NAME));
    }
    return new ArrayList<>(sources);
  }

  private static List<Path> windowsPortableCredentialDirectories(
      Path portableRoot,
      WorkDirectoryEnvironment environment,
      List<WorkDirectoryDiagnostic> diagnostics) {
    LinkedHashSet<Path> directories = new LinkedHashSet<>();
    addExistingCredentialDirectory(
        directories, portableRoot.resolve(WINDOWS_PORTABLE_WORK_DIR_NAME));
    for (Path source : windowsPortableMigrationSources(portableRoot, environment, diagnostics)) {
      addExistingCredentialDirectory(directories, source);
    }
    return new ArrayList<>(directories);
  }

  private static void addExistingCredentialDirectory(Collection<Path> directories, Path source) {
    if (directories == null || source == null) {
      return;
    }
    try {
      Path credentialDirectory = source.resolve("secure-credentials").toAbsolutePath().normalize();
      if (Files.isDirectory(credentialDirectory)) {
        directories.add(credentialDirectory);
      }
    } catch (Exception ignored) {
    }
  }

  private static void addMigrationSource(Collection<Path> sources, Path source) {
    if (sources == null || source == null) {
      return;
    }
    try {
      sources.add(source.toAbsolutePath().normalize());
    } catch (Exception ignored) {
    }
  }

  private static boolean shouldUsePortableWindowsWorkDir(Path cwd) {
    if (cwd == null || !Files.isDirectory(cwd) || !Files.isWritable(cwd) || !isAsciiSafePath(cwd)) {
      return false;
    }
    return hasBundledAssets(cwd) || hasExistingWorkDirData(cwd);
  }

  private static boolean hasBundledAssets(Path dir) {
    return dir != null
        && Files.isDirectory(dir.resolve(BUNDLED_ENGINE_ROOT))
        && Files.isDirectory(dir.resolve(BUNDLED_WEIGHT_ROOT));
  }

  private static boolean hasExistingWorkDirData(Path dir) {
    if (dir == null) {
      return false;
    }
    if (Files.isRegularFile(dir.resolve("config.txt"))
        || Files.isRegularFile(dir.resolve("persist"))) {
      return true;
    }
    Path saveDir = dir.resolve("save");
    if (!Files.isDirectory(saveDir)) {
      return false;
    }
    try (Stream<Path> stream = Files.list(saveDir)) {
      return stream.findFirst().isPresent();
    } catch (IOException e) {
      return false;
    }
  }

  private static void migrateWorkDirIfNeeded(
      Path target, List<WorkDirectoryDiagnostic> diagnostics, Path... sources) throws IOException {
    if (target == null) {
      return;
    }
    Path normalizedTarget = target.toAbsolutePath().normalize();
    Files.createDirectories(normalizedTarget);
    List<Path> candidates = normalizedMigrationSources(normalizedTarget, sources);
    Path configSource = selectBestConfigSource(candidates);
    Path targetConfigFile = normalizedTarget.resolve("config.txt");
    boolean targetHadConfig = hasUsableConfig(targetConfigFile);
    boolean recovered = false;

    if (targetHadConfig && configSource != null) {
      recovered = recoverPortableUserStateIfNeeded(normalizedTarget, configSource);
    } else if (!targetHadConfig && configSource != null) {
      backupUnreadableConfig(targetConfigFile, diagnostics);
      copyIfExists(configSource.resolve("config.txt"), targetConfigFile, true);
    }

    Path primarySource =
        configSource != null ? configSource : candidates.stream().findFirst().orElse(null);
    if (primarySource != null) {
      copyIfExists(primarySource.resolve("persist"), normalizedTarget.resolve("persist"), false);
      Path sourceSave = primarySource.resolve("save");
      if (Files.isDirectory(sourceSave)) {
        copyDirectoryContents(sourceSave, normalizedTarget.resolve("save"), false);
      }
    }

    if (configSource != null && (!targetHadConfig || recovered)) {
      diagnostics.add(
          new WorkDirectoryDiagnostic(
              WorkDirectoryDiagnostic.Kind.INFO,
              "migrated",
              "Migrated config dir to " + normalizedTarget + " from " + configSource));
    }
  }

  private static List<Path> normalizedMigrationSources(Path target, Path... sources) {
    LinkedHashSet<Path> candidates = new LinkedHashSet<>();
    if (sources == null) {
      return new ArrayList<>();
    }
    for (Path source : sources) {
      if (source == null) {
        continue;
      }
      try {
        Path normalized = source.toAbsolutePath().normalize();
        if (!normalized.equals(target) && hasMigratableWorkDirData(normalized)) {
          candidates.add(normalized);
        }
      } catch (Exception ignored) {
      }
    }
    return new ArrayList<>(candidates);
  }

  private static boolean hasMigratableWorkDirData(Path directory) {
    return hasExistingWorkDirData(directory);
  }

  private static Path selectBestConfigSource(List<Path> candidates) {
    Path selected = null;
    int selectedTier = Integer.MIN_VALUE;
    long selectedModified = Long.MIN_VALUE;
    for (Path candidate : candidates) {
      Path configFile = candidate.resolve("config.txt");
      if (!hasUsableConfig(configFile)) {
        continue;
      }
      int tier;
      try {
        tier = configUserStateTier(readJsonObject(configFile));
      } catch (Exception e) {
        continue;
      }
      long modified = Long.MIN_VALUE;
      try {
        modified = Files.getLastModifiedTime(configFile).toMillis();
      } catch (IOException ignored) {
      }
      if (selected == null
          || tier > selectedTier
          || (tier == selectedTier && modified > selectedModified)) {
        selected = candidate;
        selectedTier = tier;
        selectedModified = modified;
      }
    }
    return selected;
  }

  private static int configUserStateTier(JSONObject root) {
    JSONObject leelaz = root.optJSONObject("leelaz");
    if (leelaz == null) {
      return 0;
    }
    if (hasConfiguredRemoteProvider(leelaz)) {
      return 2;
    }
    JSONArray engines = leelaz.optJSONArray("engine-settings-list");
    if (engines != null) {
      for (int i = 0; i < engines.length(); i++) {
        JSONObject engine = engines.optJSONObject(i);
        if (engine != null && !looksLikeManagedBundledEngine(engine)) {
          return 2;
        }
      }
    }
    JSONArray legacyCommands = leelaz.optJSONArray("engine-command-list");
    if (legacyCommands != null && legacyCommands.length() > 0) {
      return 2;
    }
    return engines != null && engines.length() > 0 ? 1 : 0;
  }

  private static boolean hasUsableConfig(Path configFile) {
    if (!Files.isRegularFile(configFile)) {
      return false;
    }
    try {
      JSONObject parsed = readJsonObject(configFile);
      return parsed.optJSONObject("ui") != null || parsed.optJSONObject("leelaz") != null;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean recoverPortableUserStateIfNeeded(Path target, Path source)
      throws IOException {
    Path targetConfigFile = target.resolve("config.txt");
    Path sourceConfigFile = source.resolve("config.txt");
    try {
      JSONObject targetConfig = readJsonObject(targetConfigFile);
      JSONObject sourceConfig = readJsonObject(sourceConfigFile);
      JSONObject targetUi = targetConfig.optJSONObject("ui");
      if (targetUi != null && targetUi.optBoolean(WINDOWS_PORTABLE_STATE_MIGRATION_KEY, false)) {
        return false;
      }
      if (!looksLikeFreshPortableConfig(targetConfig)
          || !containsRecoverableUserState(sourceConfig, targetConfig)) {
        return false;
      }

      Path backup = target.resolve(WINDOWS_PORTABLE_RECOVERY_BACKUP_NAME);
      copyIfExists(targetConfigFile, backup, false);
      JSONObject recovered = new JSONObject(sourceConfig.toString());
      mergeMissingJsonValues(recovered, targetConfig);
      JSONObject recoveredUi = recovered.optJSONObject("ui");
      if (recoveredUi == null) {
        recoveredUi = new JSONObject();
        recovered.put("ui", recoveredUi);
      }
      recoveredUi.put(WINDOWS_PORTABLE_STATE_MIGRATION_KEY, true);
      writeJsonAtomically(targetConfigFile, recovered);
      return true;
    } catch (JSONException e) {
      return false;
    }
  }

  private static boolean looksLikeFreshPortableConfig(JSONObject root) {
    JSONObject leelaz = root.optJSONObject("leelaz");
    if (leelaz == null || hasConfiguredRemoteProvider(leelaz)) {
      return false;
    }
    JSONArray engines = leelaz.optJSONArray("engine-settings-list");
    if (engines == null || engines.length() == 0) {
      return true;
    }
    if (engines.length() != 1) {
      return false;
    }
    JSONObject engine = engines.optJSONObject(0);
    if (engine == null) {
      return true;
    }
    return looksLikeManagedBundledEngine(engine);
  }

  private static boolean looksLikeManagedBundledEngine(JSONObject engine) {
    String name = engine.optString("name", "").trim();
    String command = engine.optString("command", "").replace('\\', '/').toLowerCase(Locale.ROOT);
    return (BUNDLED_ENGINE_NAME.equals(name) || "KataGo Auto Setup".equals(name))
        && (command.isEmpty()
            || command.contains("weights/default.bin.gz")
            || command.contains("engines/katago/"));
  }

  private static boolean containsRecoverableUserState(JSONObject source, JSONObject target) {
    JSONObject sourceLeelaz = source.optJSONObject("leelaz");
    JSONObject targetLeelaz = target.optJSONObject("leelaz");
    if (sourceLeelaz == null) {
      return false;
    }
    if (hasConfiguredRemoteProvider(sourceLeelaz)
        && (targetLeelaz == null || !hasConfiguredRemoteProvider(targetLeelaz))) {
      return true;
    }
    Set<String> targetEngines = engineIdentities(targetLeelaz);
    for (String sourceEngine : engineIdentities(sourceLeelaz)) {
      if (!targetEngines.contains(sourceEngine)) {
        return true;
      }
    }
    JSONArray legacyCommands = sourceLeelaz.optJSONArray("engine-command-list");
    return legacyCommands != null && legacyCommands.length() > 0;
  }

  private static boolean hasConfiguredRemoteProvider(JSONObject leelaz) {
    if (leelaz == null) {
      return false;
    }
    JSONObject remote = leelaz.optJSONObject("remote-compute");
    if (remote == null) {
      return false;
    }
    return !"local".equalsIgnoreCase(remote.optString("provider", "local"))
        || !remote.optString("zhizi-identifier", "").isBlank()
        || !remote.optString("custom-remote-code", "").isBlank();
  }

  private static Set<String> engineIdentities(JSONObject leelaz) {
    LinkedHashSet<String> identities = new LinkedHashSet<>();
    if (leelaz == null) {
      return identities;
    }
    JSONArray engines = leelaz.optJSONArray("engine-settings-list");
    if (engines == null) {
      return identities;
    }
    for (int i = 0; i < engines.length(); i++) {
      JSONObject engine = engines.optJSONObject(i);
      if (engine == null) {
        continue;
      }
      String command = engine.optString("command", "").trim().toLowerCase(Locale.ROOT);
      String name = engine.optString("name", "").trim().toLowerCase(Locale.ROOT);
      identities.add(command.isEmpty() ? "name:" + name : "command:" + command);
    }
    return identities;
  }

  private static void mergeMissingJsonValues(JSONObject target, JSONObject fallback) {
    for (String key : fallback.keySet()) {
      Object fallbackValue = fallback.get(key);
      if (!target.has(key)) {
        target.put(key, fallbackValue);
      } else if (target.opt(key) instanceof JSONObject && fallbackValue instanceof JSONObject) {
        mergeMissingJsonValues(target.getJSONObject(key), (JSONObject) fallbackValue);
      }
    }
  }

  private static void writeJsonAtomically(Path target, JSONObject value) throws IOException {
    Files.createDirectories(target.getParent());
    Path temporary = Files.createTempFile(target.getParent(), ".config-recovery-", ".tmp");
    try {
      Files.writeString(temporary, value.toString(2));
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void copyDirectoryContents(Path source, Path target, boolean replace)
      throws IOException {
    Files.createDirectories(target);
    try (Stream<Path> stream = Files.list(source)) {
      for (Path child : (Iterable<Path>) stream::iterator) {
        Path destination = target.resolve(child.getFileName().toString());
        if (Files.isDirectory(child)) {
          copyDirectoryContents(child, destination, replace);
        } else {
          copyIfExists(child, destination, replace);
        }
      }
    }
  }

  private static void copyIfExists(Path source, Path destination, boolean replace)
      throws IOException {
    if (!Files.exists(source) || Files.isDirectory(source)) {
      return;
    }
    if (!replace && Files.exists(destination)) {
      return;
    }
    Files.createDirectories(destination.getParent());
    if (replace) {
      Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.copy(source, destination);
    }
  }

  private static boolean isAsciiSafePath(Path path) {
    if (path == null) {
      return false;
    }
    String text = path.toAbsolutePath().normalize().toString();
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) > 127) {
        return false;
      }
    }
    return true;
  }

  static boolean hasAppRootMarker(Path directory) {
    return directory != null
        && (Files.isRegularFile(directory.resolve(WINDOWS_PORTABLE_MARKER_NAME))
            || Files.isRegularFile(directory.resolve("PROJECT_INFO.txt"))
            || Files.isRegularFile(directory.resolve("lizzieyzy-next-installed-manifest.json"))
            || Files.isRegularFile(directory.resolve("app").resolve("PROJECT_INFO.txt"))
            || Files.isRegularFile(
                directory.resolve("app").resolve("lizzieyzy-next-installed-manifest.json")));
  }

  private static void backupUnreadableConfig(
      Path configFile, List<WorkDirectoryDiagnostic> diagnostics) {
    if (configFile == null || !Files.isRegularFile(configFile)) {
      return;
    }
    Path source = configFile.toAbsolutePath().normalize();
    Path parent = source.getParent();
    if (parent == null) {
      return;
    }
    String fileName = configFile.getFileName() + ".unreadable-backup";
    Path backup = parent.resolve(fileName);
    for (int suffix = 1; Files.exists(backup); suffix++) {
      backup = parent.resolve(fileName + "." + suffix);
    }
    try {
      Files.copy(source, backup);
      diagnostics.add(
          new WorkDirectoryDiagnostic(
              WorkDirectoryDiagnostic.Kind.INFO,
              "unreadable-backup",
              "Saved unreadable config backup to " + backup));
    } catch (IOException backupError) {
      addError(diagnostics, "unreadable-backup", backupError);
    }
  }

  private static JSONObject readJsonObject(Path file) throws IOException {
    try (Reader reader = openUtf8JsonReader(file)) {
      return new JSONObject(new JSONTokener(reader));
    }
  }

  private static Reader openUtf8JsonReader(Path file) throws IOException {
    PushbackReader reader =
        new PushbackReader(
            new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8), 1);
    try {
      int first = reader.read();
      if (first != -1 && first != '\uFEFF') {
        reader.unread(first);
      }
      return reader;
    } catch (IOException | RuntimeException e) {
      reader.close();
      throw e;
    }
  }

  private static void addError(
      List<WorkDirectoryDiagnostic> diagnostics, String code, Exception error) {
    diagnostics.add(
        new WorkDirectoryDiagnostic(
            WorkDirectoryDiagnostic.Kind.ERROR,
            code,
            error.getClass().getSimpleName()
                + ": "
                + (error.getMessage() == null ? "" : error.getMessage())));
  }
}
