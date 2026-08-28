package featurecat.lizzie.util;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import featurecat.lizzie.logging.LogCategories;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AnalysisEngineCommandHelper {
  static final String TEMPLATE_RESOURCE = "katago/analysis_example.cfg";
  public static final String DEFAULT_ANALYSIS_COMMAND =
      "katago analysis -model model.bin.gz -config analysis.cfg -quit-without-waiting";
  private static final Logger LOG = LoggerFactory.getLogger(LogCategories.ENGINE);

  private AnalysisEngineCommandHelper() {}

  public static Result fromSavedEngine(EngineData engineData) {
    if (engineData == null) {
      return Result.failure(message("AnalysisEngineCommandHelper.noEngineSelected"));
    }
    if (engineData.useJavaSSH) {
      return Result.failure(message("AnalysisEngineCommandHelper.remoteUnsupported"));
    }
    List<String> command = Utils.splitCommand(engineData.commands);
    if (command.isEmpty()) {
      return Result.failure(message("AnalysisEngineCommandHelper.emptyCommand"));
    }

    int gtpIndex = findToken(command, "gtp");
    if (gtpIndex < 0) {
      return Result.failure(message("AnalysisEngineCommandHelper.missingGtp"));
    }

    int configIndex = findConfigValueIndex(command);
    if (configIndex < 0) {
      return Result.failure(message("AnalysisEngineCommandHelper.missingConfig"));
    }

    List<String> analysisCommand = new ArrayList<String>(command);
    analysisCommand.set(gtpIndex, "analysis");
    Path analysisConfig = siblingAnalysisConfig(Path.of(analysisCommand.get(configIndex)));
    boolean generated = false;
    if (!Files.exists(analysisConfig)) {
      Path configDirectory = analysisConfig.getParent();
      if (configDirectory != null && !Files.isDirectory(configDirectory)) {
        return Result.failure(
            message("AnalysisEngineCommandHelper.generateConfigFailed", configDirectory));
      }
      try {
        copyTemplate(analysisConfig);
        generated = true;
      } catch (IOException e) {
        return Result.failure(
            message("AnalysisEngineCommandHelper.generateConfigFailed", e.getLocalizedMessage()));
      }
    }
    analysisCommand.set(configIndex, analysisConfig.toString());
    if (!containsToken(analysisCommand, "-quit-without-waiting")) {
      analysisCommand.add("-quit-without-waiting");
    }

    String message =
        generated
            ? message("AnalysisEngineCommandHelper.generatedConfig", analysisConfig)
            : message("AnalysisEngineCommandHelper.generatedCommand");
    return Result.success(buildCommandLine(analysisCommand), message, generated, analysisConfig);
  }

  public static Result fromDefaultEngine(List<EngineData> engines) {
    if (engines == null || engines.isEmpty()) {
      return Result.failure(message("AnalysisEngineCommandHelper.noDefaultEngine"));
    }
    for (EngineData engine : engines) {
      if (engine != null && engine.isDefault) {
        return fromSavedEngine(engine);
      }
    }
    return Result.failure(message("AnalysisEngineCommandHelper.noDefaultEngine"));
  }

  public static Result fromCurrentEngine(List<EngineData> engines, int currentEngineIndex) {
    if (engines != null && currentEngineIndex >= 0 && currentEngineIndex < engines.size()) {
      return fromSavedEngine(engines.get(currentEngineIndex));
    }
    return fromDefaultEngine(engines);
  }

  /**
   * Returns a usable local analysis command for HumanSL without changing the user's saved engine
   * choice. Release upgrades can relocate the bundled KataGo executable while an older absolute
   * command remains in the profile. In that case HumanSL should use the current bundle rather than
   * fail with the stale path. Valid external commands are intentionally left untouched.
   */
  public static Result resolveHumanSlCommand(String configuredCommand) {
    KataGoAutoSetupHelper.SetupSnapshot snapshot = KataGoAutoSetupHelper.inspectLocalSetup();
    Result result =
        resolveHumanSlCommand(
            configuredCommand,
            snapshot == null ? null : snapshot.enginePath,
            snapshot == null ? null : snapshot.analysisConfigPath,
            snapshot == null ? null : snapshot.activeWeightPath);
    if (!result.isSuccess()) {
      logHumanSlResolutionFailure(configuredCommand, snapshot);
    }
    return result;
  }

  static Result resolveHumanSlCommand(
      String configuredCommand, Path enginePath, Path analysisConfigPath, Path weightPath) {
    String command = configuredCommand == null ? "" : configuredCommand.trim();
    if (!needsBundledRecovery(command)) {
      return command.isEmpty()
          ? Result.failure(message("AnalysisEngineCommandHelper.noEngineSelected"))
          : Result.success(command, "", false, null);
    }
    if (!isRegularFile(enginePath)
        || !isRegularFile(analysisConfigPath)
        || !isRegularFile(weightPath)) {
      return Result.failure(message("AnalysisEngineCommandHelper.noEngineSelected"));
    }

    List<String> parts = Utils.splitCommand(command);
    if (parts.isEmpty()) {
      parts.add(enginePath.toAbsolutePath().normalize().toString());
      parts.add("analysis");
    } else {
      parts.set(0, enginePath.toAbsolutePath().normalize().toString());
      int modeIndex = findAnalysisModeIndex(parts);
      if (modeIndex >= 0) {
        parts.set(modeIndex, "analysis");
      } else {
        parts.add(1, "analysis");
      }
    }
    setOrAppendOption(parts, "-model", "--model", weightPath);
    setOrAppendOption(parts, "-config", "--config", analysisConfigPath);
    if (!containsToken(parts, "-quit-without-waiting")) {
      parts.add("-quit-without-waiting");
    }
    return Result.success(buildCommandLine(parts), "", false, analysisConfigPath);
  }

  static void logHumanSlResolutionFailure(
      String configuredCommand, KataGoAutoSetupHelper.SetupSnapshot snapshot) {
    try {
      LOG.warn("{}", formatHumanSlResolutionFailure(configuredCommand, snapshot));
    } catch (RuntimeException | Error ignored) {
      // A logging backend failure must not change resolve success or failure.
    }
  }

  static String formatHumanSlResolutionFailure(
      String configuredCommand, KataGoAutoSetupHelper.SetupSnapshot snapshot) {
    boolean configuredCommandPresent =
        configuredCommand != null && !configuredCommand.trim().isEmpty();
    String source = KataGoAutoSetupHelper.DiscoverySource.NONE.name();
    String packageFlavor = KataGoAutoSetupHelper.PackageFlavor.UNKNOWN.name();
    boolean engine = false;
    boolean analysisConfig = false;
    boolean weight = false;
    String missingComponents = "[]";
    String diagnostics = "[]";
    if (snapshot != null) {
      engine = isRegularFile(snapshot.enginePath);
      analysisConfig = isRegularFile(snapshot.analysisConfigPath);
      weight = isRegularFile(snapshot.activeWeightPath);
      KataGoAutoSetupHelper.LocalKataGoDiscoveryResult discovery = snapshot.discovery;
      if (discovery != null) {
        source = discovery.source.name();
        packageFlavor = discovery.packageFlavor.name();
        missingComponents = formatSafeList(discovery.missingComponents, snapshot);
        diagnostics = formatSafeList(discovery.diagnostics, snapshot);
      }
    }
    return "HumanSL analysis engine resolution failed:"
        + " configuredCommandPresent="
        + configuredCommandPresent
        + " source="
        + source
        + " packageFlavor="
        + packageFlavor
        + " engine="
        + engine
        + " analysisConfig="
        + analysisConfig
        + " weight="
        + weight
        + " missingComponents="
        + missingComponents
        + " diagnostics="
        + diagnostics;
  }

  private static String formatSafeList(
      List<?> values, KataGoAutoSetupHelper.SetupSnapshot snapshot) {
    if (values == null || values.isEmpty()) {
      return "[]";
    }
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      Object value = values.get(i);
      builder.append(redactSnapshotPaths(value == null ? "" : String.valueOf(value), snapshot));
    }
    builder.append(']');
    return builder.toString();
  }

  private static String redactSnapshotPaths(
      String text, KataGoAutoSetupHelper.SetupSnapshot snapshot) {
    if (text == null || text.isEmpty() || snapshot == null) {
      return text == null ? "" : text;
    }
    String safe = text;
    safe = replacePathWithFileName(safe, snapshot.enginePath);
    safe = replacePathWithFileName(safe, snapshot.gtpConfigPath);
    safe = replacePathWithFileName(safe, snapshot.analysisConfigPath);
    safe = replacePathWithFileName(safe, snapshot.activeWeightPath);
    safe = replacePathWithFileName(safe, snapshot.workingDir);
    safe = replacePathWithFileName(safe, snapshot.appRoot);
    if (snapshot.discovery != null) {
      safe = replacePathWithFileName(safe, snapshot.discovery.sourceCommand);
    }
    return safe;
  }

  private static String replacePathWithFileName(String text, Path path) {
    if (text == null || text.isEmpty() || path == null) {
      return text == null ? "" : text;
    }
    return replaceLiteralWithFileName(text, path.toString(), path.getFileName());
  }

  private static String replacePathWithFileName(String text, String pathText) {
    if (text == null || text.isEmpty() || pathText == null || pathText.isEmpty()) {
      return text == null ? "" : text;
    }
    Path fileName;
    try {
      fileName = Path.of(pathText).getFileName();
    } catch (RuntimeException e) {
      fileName = null;
    }
    return replaceLiteralWithFileName(text, pathText, fileName);
  }

  private static String replaceLiteralWithFileName(String text, String literal, Path fileName) {
    if (literal == null || literal.isEmpty() || !text.contains(literal)) {
      return text;
    }
    String replacement = fileName == null ? "file" : fileName.toString();
    if (replacement.isEmpty() || replacement.equals(literal)) {
      return text.replace(literal, "file");
    }
    return text.replace(literal, replacement);
  }

  public static Path ensureAnalysisConfig(Path gtpConfigPath) throws IOException {
    if (gtpConfigPath == null || !Files.isRegularFile(gtpConfigPath)) {
      throw new IOException(message("AnalysisEngineCommandHelper.missingConfig"));
    }
    Path analysisConfig = siblingAnalysisConfig(gtpConfigPath.toAbsolutePath().normalize());
    if (!Files.isRegularFile(analysisConfig)) {
      copyTemplate(analysisConfig);
    }
    return analysisConfig;
  }

  public static boolean isAnalysisCommandCustomized(
      boolean hasCustomizedFlag, boolean customizedFlag, String command) {
    if (hasCustomizedFlag) {
      return customizedFlag;
    }
    return isLegacyAnalysisCommandCustomized(command);
  }

  static boolean isLegacyAnalysisCommandCustomized(String command) {
    String normalized = normalizeCommand(command);
    return !normalized.isEmpty() && !normalized.equals(normalizeCommand(DEFAULT_ANALYSIS_COMMAND));
  }

  private static int findToken(List<String> command, String token) {
    for (int i = 0; i < command.size(); i++) {
      String part = command.get(i);
      if (part != null && part.toLowerCase(Locale.ROOT).equals(token)) {
        return i;
      }
    }
    return -1;
  }

  private static int findAnalysisModeIndex(List<String> command) {
    for (int i = 1; i < command.size(); i++) {
      String token = command.get(i);
      if ("analysis".equalsIgnoreCase(token) || "gtp".equalsIgnoreCase(token)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean needsBundledRecovery(String command) {
    if (command == null || command.trim().isEmpty()) {
      return true;
    }
    if (normalizeCommand(command).equals(normalizeCommand(DEFAULT_ANALYSIS_COMMAND))) {
      return true;
    }
    if (!Config.isBundledKataGoCommand(command)) {
      return false;
    }
    List<String> parts = Utils.splitCommand(command);
    if (parts.isEmpty()) {
      return true;
    }
    Path executable = KataGoRuntimeHelper.resolveCommandExecutable(parts);
    return !isRegularFile(executable)
        || !isRegularFile(optionPath(parts, "-model", "--model"))
        || !isRegularFile(optionPath(parts, "-config", "--config"));
  }

  private static Path optionPath(List<String> command, String shortOption, String longOption) {
    for (int i = 0; i < command.size() - 1; i++) {
      String token = command.get(i);
      if (shortOption.equals(token) || longOption.equals(token)) {
        try {
          Path path = Path.of(command.get(i + 1));
          return path.isAbsolute()
              ? path.toAbsolutePath().normalize()
              : Path.of(System.getProperty("user.dir", "."))
                  .resolve(path)
                  .toAbsolutePath()
                  .normalize();
        } catch (RuntimeException e) {
          return null;
        }
      }
    }
    return null;
  }

  private static void setOrAppendOption(
      List<String> command, String shortOption, String longOption, Path value) {
    String normalizedValue = value.toAbsolutePath().normalize().toString();
    for (int i = 0; i < command.size() - 1; i++) {
      String token = command.get(i);
      if (shortOption.equals(token) || longOption.equals(token)) {
        command.set(i + 1, normalizedValue);
        return;
      }
    }
    command.add(shortOption);
    command.add(normalizedValue);
  }

  private static boolean isRegularFile(Path path) {
    return path != null && Files.isRegularFile(path);
  }

  private static String normalizeCommand(String command) {
    List<String> parts = Utils.splitCommand(command == null ? "" : command.trim());
    return buildCommandLine(parts);
  }

  private static boolean containsToken(List<String> command, String token) {
    return findToken(command, token) >= 0;
  }

  private static int findConfigValueIndex(List<String> command) {
    for (int i = 0; i < command.size() - 1; i++) {
      String token = command.get(i);
      if ("-config".equals(token) || "--config".equals(token)) {
        return i + 1;
      }
    }
    return -1;
  }

  private static Path siblingAnalysisConfig(Path originalConfig) {
    Path parent = originalConfig.getParent();
    return parent == null ? Path.of("analysis.cfg") : parent.resolve("analysis.cfg");
  }

  private static void copyTemplate(Path analysisConfig) throws IOException {
    Path parent = analysisConfig.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (InputStream input =
        AnalysisEngineCommandHelper.class.getClassLoader().getResourceAsStream(TEMPLATE_RESOURCE)) {
      if (input == null) {
        throw new IOException(
            message("AnalysisEngineCommandHelper.missingTemplate", TEMPLATE_RESOURCE));
      }
      Files.copy(input, analysisConfig);
    }
  }

  private static String message(String key, Object... args) {
    String pattern = fallbackMessage(key);
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        pattern = Lizzie.resourceBundle.getString(key);
      }
    } catch (MissingResourceException | ExceptionInInitializerError ignored) {
    }
    return MessageFormat.format(pattern, args);
  }

  private static String fallbackMessage(String key) {
    switch (key) {
      case "AnalysisEngineCommandHelper.noEngineSelected":
        return "未选择引擎。";
      case "AnalysisEngineCommandHelper.remoteUnsupported":
        return "暂不支持从远程引擎生成闪电分析命令。";
      case "AnalysisEngineCommandHelper.emptyCommand":
        return "引擎命令为空。";
      case "AnalysisEngineCommandHelper.missingGtp":
        return "引擎命令中没有独立的 gtp 子命令。";
      case "AnalysisEngineCommandHelper.missingConfig":
        return "引擎命令中没有 -config 或 --config 参数。";
      case "AnalysisEngineCommandHelper.generateConfigFailed":
        return "无法生成 analysis.cfg：{0}";
      case "AnalysisEngineCommandHelper.generatedConfig":
        return "缺少 analysis.cfg，已自动生成：{0}";
      case "AnalysisEngineCommandHelper.generatedCommand":
        return "已生成闪电分析命令。";
      case "AnalysisEngineCommandHelper.noDefaultEngine":
        return "没有已保存的默认引擎。";
      case "AnalysisEngineCommandHelper.missingTemplate":
        return "缺少内置模板 {0}";
      default:
        return key;
    }
  }

  static String buildCommandLine(List<String> command) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < command.size(); i++) {
      if (i > 0) {
        builder.append(' ');
      }
      builder.append(quoteCommandToken(command.get(i)));
    }
    return builder.toString();
  }

  private static String quoteCommandToken(String token) {
    if (token == null) {
      return "\"\"";
    }
    String trimmed = token.trim();
    if (trimmed.isEmpty()) {
      return "\"\"";
    }
    if (trimmed.indexOf(' ') >= 0 || trimmed.indexOf('\t') >= 0 || trimmed.indexOf('"') >= 0) {
      return "\"" + trimmed.replace("\"", "\\\"") + "\"";
    }
    return trimmed;
  }

  public static final class Result {
    private final boolean success;
    private final String command;
    private final String message;
    private final boolean generatedConfig;
    private final Path analysisConfigPath;

    private Result(
        boolean success,
        String command,
        String message,
        boolean generatedConfig,
        Path analysisConfigPath) {
      this.success = success;
      this.command = command;
      this.message = message;
      this.generatedConfig = generatedConfig;
      this.analysisConfigPath = analysisConfigPath;
    }

    private static Result success(
        String command, String message, boolean generatedConfig, Path generatedConfigPath) {
      return new Result(true, command, message, generatedConfig, generatedConfigPath);
    }

    private static Result failure(String message) {
      return new Result(false, "", message, false, null);
    }

    public boolean isSuccess() {
      return success;
    }

    public String getCommand() {
      return command;
    }

    public String getMessage() {
      return message;
    }

    public boolean generatedConfig() {
      return generatedConfig;
    }

    public Path getAnalysisConfigPath() {
      return analysisConfigPath;
    }
  }
}
