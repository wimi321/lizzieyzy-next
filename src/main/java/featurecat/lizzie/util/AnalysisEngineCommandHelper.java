package featurecat.lizzie.util;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;

public final class AnalysisEngineCommandHelper {
  static final String TEMPLATE_RESOURCE = "katago/analysis_example.cfg";
  public static final String DEFAULT_ANALYSIS_COMMAND =
      "katago analysis -model model.bin.gz -config analysis.cfg -quit-without-waiting";

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
