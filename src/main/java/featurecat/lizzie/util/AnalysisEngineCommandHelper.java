package featurecat.lizzie.util;

import featurecat.lizzie.gui.EngineData;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AnalysisEngineCommandHelper {
  static final String TEMPLATE_RESOURCE = "katago/analysis_example.cfg";
  public static final String DEFAULT_ANALYSIS_COMMAND =
      "katago analysis -model model.bin.gz -config analysis.cfg -quit-without-waiting";

  private AnalysisEngineCommandHelper() {}

  public static Result fromSavedEngine(EngineData engineData) {
    if (engineData == null) {
      return Result.failure("未选择引擎。");
    }
    if (engineData.useJavaSSH) {
      return Result.failure("暂不支持从远程引擎生成闪电分析命令。");
    }
    List<String> command = Utils.splitCommand(engineData.commands);
    if (command.isEmpty()) {
      return Result.failure("引擎命令为空。");
    }

    int gtpIndex = findToken(command, "gtp");
    if (gtpIndex < 0) {
      return Result.failure("引擎命令中没有独立的 gtp 子命令。");
    }

    int configIndex = findConfigValueIndex(command);
    if (configIndex < 0) {
      return Result.failure("引擎命令中没有 -config 或 --config 参数。");
    }

    List<String> analysisCommand = new ArrayList<String>(command);
    analysisCommand.set(gtpIndex, "analysis");
    Path analysisConfig = siblingAnalysisConfig(Path.of(analysisCommand.get(configIndex)));
    boolean generated = false;
    if (!Files.exists(analysisConfig)) {
      try {
        copyTemplate(analysisConfig);
        generated = true;
      } catch (IOException e) {
        return Result.failure("无法生成 analysis.cfg：" + e.getLocalizedMessage());
      }
    }
    analysisCommand.set(configIndex, analysisConfig.toString());
    if (!containsToken(analysisCommand, "-quit-without-waiting")) {
      analysisCommand.add("-quit-without-waiting");
    }

    String message = generated ? "缺少 analysis.cfg，已自动生成：" + analysisConfig : "已生成闪电分析命令。";
    return Result.success(buildCommandLine(analysisCommand), message, generated, analysisConfig);
  }

  public static Result fromDefaultEngine(List<EngineData> engines) {
    if (engines == null || engines.isEmpty()) {
      return Result.failure("没有已保存的默认引擎。");
    }
    for (EngineData engine : engines) {
      if (engine != null && engine.isDefault) {
        return fromSavedEngine(engine);
      }
    }
    return Result.failure("没有已保存的默认引擎。");
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
        throw new IOException("缺少内置模板 " + TEMPLATE_RESOURCE);
      }
      Files.copy(input, analysisConfig);
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
