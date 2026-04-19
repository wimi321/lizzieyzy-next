package featurecat.lizzie.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdesktop.swingx.util.OS;

public final class CommandLaunchHelper {
  private static final String PATH_KEY = "PATH";
  private static final String PATH_KEY_MIXED = "Path";
  private static final String WINDOWS_PATH_SEPARATOR = ";";
  private static final String MACHINE_ENVIRONMENT_KEY =
      "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment";
  private static final String USER_ENVIRONMENT_KEY = "HKCU\\Environment";
  private static final int MAX_ENV_EXPANSION_DEPTH = 8;
  private static final Pattern WINDOWS_DRIVE_PATH_PATTERN = Pattern.compile("^[A-Za-z]:\\\\.*");
  private static final Pattern WINDOWS_VARIABLE_PATTERN = Pattern.compile("%([^%]+)%");
  private static final ConcurrentMap<String, String> WINDOWS_REGISTRY_PATH_CACHE =
      new ConcurrentHashMap<String, String>();
  private static final WindowsRegistryProcessStarter DEFAULT_WINDOWS_REGISTRY_PROCESS_STARTER =
      ProcessBuilder::start;
  private static final WindowsRegistryPathReader DEFAULT_WINDOWS_REGISTRY_PATH_READER =
      new ProcessWindowsRegistryPathReader(DEFAULT_WINDOWS_REGISTRY_PROCESS_STARTER);
  private static volatile WindowsRegistryPathReader windowsRegistryPathReader =
      DEFAULT_WINDOWS_REGISTRY_PATH_READER;
  private static final String[] FILE_PATH_OPTIONS = {
    "-model",
    "--model",
    "-config",
    "--config",
    "-override-config",
    "--override-config",
    "-human-model",
    "--human-model",
    "-analysis-model",
    "--analysis-model",
    "-evaluation-model",
    "--evaluation-model",
    "-cacerts",
    "--cacerts"
  };

  private CommandLaunchHelper() {}

  public static LaunchSpec prepare(List<String> commandParts) {
    List<String> resolvedCommand = new ArrayList<String>();
    if (commandParts != null) {
      resolvedCommand.addAll(commandParts);
    }
    Path workingDirectory = detectWorkingDirectory(resolvedCommand);
    if (workingDirectory != null) {
      resolveExecutableToken(resolvedCommand, workingDirectory);
      resolveOptionPaths(resolvedCommand, workingDirectory);
      resolveJavaJarPath(resolvedCommand, workingDirectory);
    }
    return new LaunchSpec(
        resolvedCommand, workingDirectory == null ? null : workingDirectory.toFile());
  }

  public static void applyWorkingDirectory(ProcessBuilder processBuilder, LaunchSpec launchSpec) {
    configureProcessBuilder(processBuilder, launchSpec);
  }

  public static void configureProcessBuilder(ProcessBuilder processBuilder, LaunchSpec launchSpec) {
    if (processBuilder == null || launchSpec == null) {
      return;
    }
    File workingDirectory = launchSpec.getWorkingDirectory();
    if (workingDirectory != null) {
      processBuilder.directory(workingDirectory);
    }
    if (!OS.isWindows()) {
      return;
    }
    replaceWindowsPath(
        processBuilder.environment(),
        buildWindowsPath(
            launchSpec.getCommandParts(),
            workingDirectory,
            processBuilder.environment(),
            System.getenv(),
            getWindowsRegistryPath(MACHINE_ENVIRONMENT_KEY),
            getWindowsRegistryPath(USER_ENVIRONMENT_KEY)));
  }

  private static Path detectWorkingDirectory(List<String> commandParts) {
    if (commandParts == null || commandParts.isEmpty()) {
      return null;
    }

    Path bestRoot = null;
    int bestScore = -1;
    for (Path candidateRoot : collectCandidateRoots()) {
      int score = scoreCandidate(commandParts, candidateRoot);
      if (score > bestScore) {
        bestScore = score;
        bestRoot = candidateRoot;
      }
    }
    if (bestScore > 0) {
      return bestRoot;
    }
    return inferWorkingDirectoryFromAbsolutePaths(commandParts);
  }

  private static Path inferWorkingDirectoryFromAbsolutePaths(List<String> commandParts) {
    List<Path> referencedDirectories = new ArrayList<Path>();
    addAbsoluteReferenceDirectory(referencedDirectories, commandParts.get(0));

    for (int i = 0; i < commandParts.size() - 1; i++) {
      if (matchesOption(commandParts.get(i), FILE_PATH_OPTIONS)
          || "-jar".equals(commandParts.get(i))) {
        addAbsoluteReferenceDirectory(referencedDirectories, commandParts.get(i + 1));
      }
    }

    if (referencedDirectories.isEmpty()) {
      return null;
    }

    Path commonDirectory = referencedDirectories.get(0);
    for (int i = 1; i < referencedDirectories.size(); i++) {
      commonDirectory = commonAncestor(commonDirectory, referencedDirectories.get(i));
      if (commonDirectory == null) {
        break;
      }
    }

    if (commonDirectory != null
        && Files.isDirectory(commonDirectory)
        && !isFilesystemRoot(commonDirectory)) {
      return commonDirectory;
    }
    return referencedDirectories.get(0);
  }

  private static void addAbsoluteReferenceDirectory(List<Path> directories, String token) {
    Path directory = toAbsoluteReferenceDirectory(token);
    if (directory != null && Files.isDirectory(directory) && !directories.contains(directory)) {
      directories.add(directory);
    }
  }

  private static Path toAbsoluteReferenceDirectory(String token) {
    if (token == null) {
      return null;
    }
    try {
      Path path = Paths.get(token.trim());
      if (!path.isAbsolute()) {
        return null;
      }
      Path normalized = path.toAbsolutePath().normalize();
      if (Files.isDirectory(normalized)) {
        return normalized;
      }
      Path parent = normalized.getParent();
      if (parent != null && Files.isDirectory(parent)) {
        return parent;
      }
    } catch (Exception e) {
      return null;
    }
    return null;
  }

  private static Path commonAncestor(Path left, Path right) {
    if (left == null || right == null) {
      return null;
    }
    Path current = left.toAbsolutePath().normalize();
    Path other = right.toAbsolutePath().normalize();
    while (current != null) {
      if (other.startsWith(current)) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  private static boolean isFilesystemRoot(Path path) {
    if (path == null) {
      return false;
    }
    Path normalized = path.toAbsolutePath().normalize();
    return normalized.getParent() == null;
  }

  private static int scoreCandidate(List<String> commandParts, Path root) {
    if (root == null) {
      return -1;
    }
    int score = 0;
    String executableToken = commandParts.get(0);
    if (looksLikeRelativeFileToken(executableToken)) {
      Path executablePath = resolveCandidate(root, executableToken);
      if (!Files.isRegularFile(executablePath)) {
        return -1;
      }
      score += 6;
    }

    for (int i = 0; i < commandParts.size() - 1; i++) {
      if (matchesOption(commandParts.get(i), FILE_PATH_OPTIONS)) {
        String value = commandParts.get(i + 1);
        if (looksLikeRelativeFileToken(value) && Files.exists(resolveCandidate(root, value))) {
          score += 3;
        }
      }
    }

    if (Utils.isJavaCommand(executableToken)) {
      for (int i = 0; i < commandParts.size() - 1; i++) {
        if ("-jar".equals(commandParts.get(i))) {
          String value = commandParts.get(i + 1);
          if (looksLikeRelativeFileToken(value)
              && Files.isRegularFile(resolveCandidate(root, value))) {
            score += 4;
          }
        }
      }
    }
    return score;
  }

  private static void resolveExecutableToken(List<String> commandParts, Path root) {
    if (commandParts.isEmpty()) {
      return;
    }
    String executableToken = commandParts.get(0);
    if (!looksLikeRelativeFileToken(executableToken)) {
      return;
    }
    Path executablePath = resolveCandidate(root, executableToken);
    if (Files.isRegularFile(executablePath)) {
      commandParts.set(0, executablePath.toAbsolutePath().normalize().toString());
    }
  }

  private static void resolveOptionPaths(List<String> commandParts, Path root) {
    for (int i = 0; i < commandParts.size() - 1; i++) {
      if (!matchesOption(commandParts.get(i), FILE_PATH_OPTIONS)) {
        continue;
      }
      String value = commandParts.get(i + 1);
      if (!looksLikeRelativeFileToken(value)) {
        continue;
      }
      Path candidate = resolveCandidate(root, value);
      if (Files.exists(candidate)) {
        commandParts.set(i + 1, candidate.toAbsolutePath().normalize().toString());
      }
    }
  }

  private static void resolveJavaJarPath(List<String> commandParts, Path root) {
    if (commandParts.isEmpty() || !Utils.isJavaCommand(commandParts.get(0))) {
      return;
    }
    for (int i = 0; i < commandParts.size() - 1; i++) {
      if (!"-jar".equals(commandParts.get(i))) {
        continue;
      }
      String value = commandParts.get(i + 1);
      if (!looksLikeRelativeFileToken(value)) {
        continue;
      }
      Path candidate = resolveCandidate(root, value);
      if (Files.isRegularFile(candidate)) {
        commandParts.set(i + 1, candidate.toAbsolutePath().normalize().toString());
      }
    }
  }

  private static LinkedHashSet<Path> collectCandidateRoots() {
    LinkedHashSet<Path> roots = new LinkedHashSet<Path>();
    addCandidateRoot(roots, System.getProperty("user.dir", "."));

    try {
      Path codePath =
          Paths.get(
                  CommandLaunchHelper.class
                      .getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI())
              .toAbsolutePath()
              .normalize();
      Path current = Files.isDirectory(codePath) ? codePath : codePath.getParent();
      for (int depth = 0; current != null && depth < 8; depth++) {
        roots.add(current);
        current = current.getParent();
      }
    } catch (URISyntaxException | RuntimeException e) {
      e.printStackTrace();
    }
    return roots;
  }

  static List<String> buildWindowsPathEntries(
      List<String> commandParts,
      File workingDirectory,
      Map<String, String> processEnvironment,
      Map<String, String> systemEnvironment,
      String machineRegistryPath,
      String userRegistryPath) {
    List<String> entries = new ArrayList<String>();
    LinkedHashSet<String> seenEntries = new LinkedHashSet<String>();
    Map<String, String> expansionVariables =
        buildExpansionVariables(processEnvironment, systemEnvironment);
    addWindowsPathEntry(
        entries, seenEntries, getWindowsExecutableDirectory(commandParts), expansionVariables);
    addWindowsPathEntry(
        entries,
        seenEntries,
        workingDirectory == null ? null : workingDirectory.getPath(),
        expansionVariables);
    addEnvironmentPathEntries(
        entries, seenEntries, processEnvironment, PATH_KEY, expansionVariables);
    addEnvironmentPathEntries(
        entries, seenEntries, processEnvironment, PATH_KEY_MIXED, expansionVariables);
    addEnvironmentPathEntries(
        entries, seenEntries, systemEnvironment, PATH_KEY, expansionVariables);
    addEnvironmentPathEntries(
        entries, seenEntries, systemEnvironment, PATH_KEY_MIXED, expansionVariables);
    addWindowsPathEntries(entries, seenEntries, machineRegistryPath, expansionVariables);
    addWindowsPathEntries(entries, seenEntries, userRegistryPath, expansionVariables);
    return entries;
  }

  private static String buildWindowsPath(
      List<String> commandParts,
      File workingDirectory,
      Map<String, String> processEnvironment,
      Map<String, String> systemEnvironment,
      String machineRegistryPath,
      String userRegistryPath) {
    return String.join(
        WINDOWS_PATH_SEPARATOR,
        buildWindowsPathEntries(
            commandParts,
            workingDirectory,
            processEnvironment,
            systemEnvironment,
            machineRegistryPath,
            userRegistryPath));
  }

  private static Map<String, String> buildExpansionVariables(
      Map<String, String> processEnvironment, Map<String, String> systemEnvironment) {
    Map<String, String> variables = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    putExpansionVariables(variables, systemEnvironment);
    putExpansionVariables(variables, processEnvironment);
    return variables;
  }

  private static void putExpansionVariables(
      Map<String, String> target, Map<String, String> source) {
    if (source == null) {
      return;
    }
    for (Map.Entry<String, String> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      target.put(entry.getKey(), entry.getValue());
    }
  }

  private static void addEnvironmentPathEntries(
      List<String> entries,
      LinkedHashSet<String> seenEntries,
      Map<String, String> environment,
      String key,
      Map<String, String> expansionVariables) {
    addWindowsPathEntries(
        entries,
        seenEntries,
        environment == null ? null : environment.get(key),
        expansionVariables);
  }

  private static void addWindowsPathEntries(
      List<String> entries,
      LinkedHashSet<String> seenEntries,
      String rawPathText,
      Map<String, String> expansionVariables) {
    if (rawPathText == null || rawPathText.trim().isEmpty()) {
      return;
    }
    for (String rawEntry : rawPathText.split(Pattern.quote(WINDOWS_PATH_SEPARATOR))) {
      addWindowsPathEntry(entries, seenEntries, rawEntry, expansionVariables);
    }
  }

  private static void addWindowsPathEntry(
      List<String> entries,
      LinkedHashSet<String> seenEntries,
      String rawEntry,
      Map<String, String> expansionVariables) {
    if (rawEntry == null) {
      return;
    }
    String normalized = normalizeWindowsPath(expandWindowsVariables(rawEntry, expansionVariables));
    if (normalized == null) {
      return;
    }
    String dedupeKey = normalized.toLowerCase(Locale.ROOT);
    if (seenEntries.add(dedupeKey)) {
      entries.add(normalized);
    }
  }

  private static String getWindowsExecutableDirectory(List<String> commandParts) {
    if (commandParts == null || commandParts.isEmpty()) {
      return null;
    }
    return getWindowsAbsoluteParent(commandParts.get(0));
  }

  private static String getWindowsAbsoluteParent(String rawPathText) {
    String normalized = normalizeWindowsPath(rawPathText);
    if (normalized == null || !isWindowsAbsolutePath(normalized)) {
      return null;
    }
    int separator = normalized.lastIndexOf('\\');
    if (separator <= 0) {
      return null;
    }
    return normalized.substring(0, separator);
  }

  private static boolean isWindowsAbsolutePath(String rawPathText) {
    return rawPathText.startsWith("\\\\")
        || WINDOWS_DRIVE_PATH_PATTERN.matcher(rawPathText).matches();
  }

  private static String normalizeWindowsPath(String rawPathText) {
    if (rawPathText == null) {
      return null;
    }
    String trimmed = rawPathText.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.replace('/', '\\');
  }

  private static String expandWindowsVariables(
      String rawPathText, Map<String, String> expansionVariables) {
    String expanded = rawPathText;
    for (int i = 0; i < MAX_ENV_EXPANSION_DEPTH; i++) {
      Matcher matcher = WINDOWS_VARIABLE_PATTERN.matcher(expanded);
      StringBuffer rebuilt = new StringBuffer();
      boolean changed = false;
      while (matcher.find()) {
        String replacement = expansionVariables.get(matcher.group(1));
        if (replacement == null) {
          matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(matcher.group(0)));
          continue;
        }
        matcher.appendReplacement(rebuilt, Matcher.quoteReplacement(replacement));
        changed = true;
      }
      matcher.appendTail(rebuilt);
      expanded = rebuilt.toString();
      if (!changed) {
        return expanded;
      }
    }
    return expanded;
  }

  private static void replaceWindowsPath(
      Map<String, String> environment, String rebuiltWindowsPath) {
    environment.remove(PATH_KEY);
    environment.remove(PATH_KEY_MIXED);
    environment.put(PATH_KEY, rebuiltWindowsPath);
  }

  static String getWindowsRegistryPath(String environmentKey) {
    if (environmentKey == null || environmentKey.trim().isEmpty()) {
      return "";
    }
    return WINDOWS_REGISTRY_PATH_CACHE.computeIfAbsent(
        environmentKey, CommandLaunchHelper::loadWindowsRegistryPath);
  }

  static void setWindowsRegistryPathReaderForTest(WindowsRegistryPathReader reader) {
    windowsRegistryPathReader = reader == null ? DEFAULT_WINDOWS_REGISTRY_PATH_READER : reader;
    resetWindowsRegistryPathCacheForTest();
  }

  static void resetWindowsRegistryPathReaderForTest() {
    windowsRegistryPathReader = DEFAULT_WINDOWS_REGISTRY_PATH_READER;
    resetWindowsRegistryPathCacheForTest();
  }

  static void resetWindowsRegistryPathCacheForTest() {
    WINDOWS_REGISTRY_PATH_CACHE.clear();
  }

  private static String loadWindowsRegistryPath(String environmentKey) {
    String path = windowsRegistryPathReader.readPath(environmentKey);
    return path == null ? "" : path;
  }

  static String readWindowsRegistryPathForTest(
      String environmentKey, WindowsRegistryProcessStarter processStarter) {
    return readWindowsRegistryPath(environmentKey, processStarter);
  }

  private static String readWindowsRegistryPath(String environmentKey) {
    return readWindowsRegistryPath(environmentKey, DEFAULT_WINDOWS_REGISTRY_PROCESS_STARTER);
  }

  private static String readWindowsRegistryPath(
      String environmentKey, WindowsRegistryProcessStarter processStarter) {
    WindowsRegistryProcessStarter starter =
        processStarter == null ? DEFAULT_WINDOWS_REGISTRY_PROCESS_STARTER : processStarter;
    ProcessBuilder query = new ProcessBuilder("reg", "query", environmentKey, "/v", PATH_KEY_MIXED);
    query.redirectErrorStream(true);
    try {
      Process process = starter.start(query);
      String output = readProcessOutput(process);
      int exitCode = waitForProcess(process);
      if (exitCode != 0) {
        return "";
      }
      return extractRegistryPath(output);
    } catch (IOException e) {
      return "";
    }
  }

  private static String readProcessOutput(Process process) throws IOException {
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append('\n');
      }
    }
    return output.toString();
  }

  private static int waitForProcess(Process process) {
    try {
      return process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  private static String extractRegistryPath(String output) {
    if (output == null || output.trim().isEmpty()) {
      return "";
    }
    String[] lines = output.split("\\R");
    for (String line : lines) {
      String path = extractRegistryPathFromLine(line);
      if (path != null) {
        return path;
      }
    }
    return "";
  }

  private static String extractRegistryPathFromLine(String line) {
    if (line == null) {
      return null;
    }
    String trimmed = line.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    String[] columns = trimmed.split("\\s+", 3);
    if (columns.length < 3 || !PATH_KEY_MIXED.equalsIgnoreCase(columns[0])) {
      return null;
    }
    if (!columns[1].toUpperCase(Locale.ROOT).startsWith("REG_")) {
      return null;
    }
    return columns[2].trim();
  }

  private static void addCandidateRoot(LinkedHashSet<Path> roots, String rootText) {
    if (rootText == null || rootText.trim().isEmpty()) {
      return;
    }
    try {
      roots.add(Paths.get(rootText).toAbsolutePath().normalize());
    } catch (Exception e) {
    }
  }

  private static boolean matchesOption(String token, String[] options) {
    if (token == null) {
      return false;
    }
    for (String option : options) {
      if (option.equals(token)) {
        return true;
      }
    }
    return false;
  }

  private static boolean looksLikeRelativeFileToken(String token) {
    if (token == null) {
      return false;
    }
    String trimmed = token.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    try {
      if (Paths.get(trimmed).isAbsolute()) {
        return false;
      }
    } catch (Exception e) {
      return false;
    }
    if (trimmed.startsWith("-")) {
      return false;
    }
    if (trimmed.contains("/") || trimmed.contains("\\")) {
      return true;
    }
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return lower.endsWith(".exe")
        || lower.endsWith(".bat")
        || lower.endsWith(".cmd")
        || lower.endsWith(".jar")
        || lower.endsWith(".cfg")
        || lower.endsWith(".txt")
        || lower.endsWith(".pem")
        || lower.endsWith(".bin")
        || lower.endsWith(".bin.gz")
        || lower.endsWith(".dll");
  }

  private static Path resolveCandidate(Path root, String relativeToken) {
    return root.resolve(relativeToken).toAbsolutePath().normalize();
  }

  public static final class LaunchSpec {
    private final List<String> commandParts;
    private final File workingDirectory;

    private LaunchSpec(List<String> commandParts, File workingDirectory) {
      this.commandParts = commandParts;
      this.workingDirectory = workingDirectory;
    }

    public List<String> getCommandParts() {
      return commandParts;
    }

    public File getWorkingDirectory() {
      return workingDirectory;
    }
  }

  interface WindowsRegistryProcessStarter {
    Process start(ProcessBuilder processBuilder) throws IOException;
  }

  interface WindowsRegistryPathReader {
    String readPath(String environmentKey);
  }

  private static final class ProcessWindowsRegistryPathReader implements WindowsRegistryPathReader {
    private final WindowsRegistryProcessStarter processStarter;

    private ProcessWindowsRegistryPathReader(WindowsRegistryProcessStarter processStarter) {
      this.processStarter =
          processStarter == null ? DEFAULT_WINDOWS_REGISTRY_PROCESS_STARTER : processStarter;
    }

    @Override
    public String readPath(String environmentKey) {
      return readWindowsRegistryPath(environmentKey, processStarter);
    }
  }
}
