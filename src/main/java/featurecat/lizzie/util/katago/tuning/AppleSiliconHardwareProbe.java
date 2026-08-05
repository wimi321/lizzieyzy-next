package featurecat.lizzie.util.katago.tuning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Collects the small, non-identifying host profile used to scope a KataGo tuning result. */
public final class AppleSiliconHardwareProbe {
  private static final String SYSCTL = "/usr/sbin/sysctl";
  private static final String SW_VERS = "/usr/bin/sw_vers";
  private static final long COMMAND_TIMEOUT_SECONDS = 3L;

  private final CommandRunner commandRunner;

  public AppleSiliconHardwareProbe() {
    this(new ProcessCommandRunner());
  }

  public AppleSiliconHardwareProbe(CommandRunner commandRunner) {
    this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
  }

  /**
   * Probes the current Mac without collecting a device serial number or platform UUID.
   *
   * <p>Individual command failures degrade to conservative values instead of preventing startup.
   */
  public HardwareProfile probe() {
    String hardwareModel = readSysctl("hw.model");
    String chipOrBrand = readSysctl("machdep.cpu.brand_string");
    if (chipOrBrand.isEmpty()) {
      chipOrBrand = hardwareModel;
    }

    String architecture = readSysctl("hw.machine");
    if (architecture.isEmpty()) {
      architecture = normalized(System.getProperty("os.arch", ""));
    }

    int logicalCpuCount = parsePositiveInt(readSysctl("hw.logicalcpu"));
    if (logicalCpuCount == 0) {
      logicalCpuCount = Math.max(0, Runtime.getRuntime().availableProcessors());
    }

    long memoryBytes = parsePositiveLong(readSysctl("hw.memsize"));
    String macOsBuild = readCommand(List.of(SW_VERS, "-buildVersion"), "BuildVersion");
    boolean rosettaTranslated = "1".equals(readSysctl("sysctl.proc_translated"));

    return new HardwareProfile(
        hardwareModel,
        chipOrBrand,
        architecture,
        logicalCpuCount,
        memoryBytes,
        macOsBuild,
        rosettaTranslated);
  }

  private String readSysctl(String key) {
    return readCommand(List.of(SYSCTL, "-n", key), key);
  }

  private String readCommand(List<String> command, String optionalLabel) {
    if (Thread.currentThread().isInterrupted()) {
      return "";
    }
    try {
      CommandResult result = commandRunner.run(command);
      if (result == null || result.exitCode() != 0) {
        return "";
      }
      return firstValueLine(result.stdout(), optionalLabel);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return "";
    } catch (IOException | RuntimeException ignored) {
      return "";
    }
  }

  private static String firstValueLine(String output, String optionalLabel) {
    if (output == null) {
      return "";
    }
    String value = output.strip();
    int newline = value.indexOf('\n');
    if (newline >= 0) {
      value = value.substring(0, newline).strip();
    }
    if (optionalLabel != null && !optionalLabel.isBlank()) {
      String colonPrefix = optionalLabel + ":";
      String equalsPrefix = optionalLabel + "=";
      if (value.startsWith(colonPrefix)) {
        value = value.substring(colonPrefix.length()).strip();
      } else if (value.startsWith(equalsPrefix)) {
        value = value.substring(equalsPrefix.length()).strip();
      }
    }
    return value;
  }

  private static int parsePositiveInt(String value) {
    try {
      return Math.max(0, Integer.parseInt(value));
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static long parsePositiveLong(String value) {
    try {
      return Math.max(0L, Long.parseLong(value));
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim();
  }

  /** The deliberately non-identifying host characteristics that affect tuning validity. */
  public record HardwareProfile(
      String hardwareModel,
      String chipOrBrand,
      String architecture,
      int logicalCpuCount,
      long memoryBytes,
      String macOsBuild,
      boolean rosettaTranslated) {
    public HardwareProfile {
      hardwareModel = normalized(hardwareModel);
      chipOrBrand = normalized(chipOrBrand);
      architecture = normalized(architecture);
      logicalCpuCount = Math.max(0, logicalCpuCount);
      memoryBytes = Math.max(0L, memoryBytes);
      macOsBuild = normalized(macOsBuild);
    }
  }

  @FunctionalInterface
  public interface CommandRunner {
    CommandResult run(List<String> command) throws IOException, InterruptedException;
  }

  /** A process result abstraction kept small so hardware-probe tests never invoke host commands. */
  public record CommandResult(int exitCode, String stdout, String stderr) {
    public CommandResult {
      stdout = stdout == null ? "" : stdout;
      stderr = stderr == null ? "" : stderr;
    }
  }

  private static final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(List<String> command) throws IOException, InterruptedException {
      List<String> safeCommand = new ArrayList<String>(Objects.requireNonNull(command, "command"));
      if (safeCommand.isEmpty() || safeCommand.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("command must contain non-null arguments");
      }

      Process process = new ProcessBuilder(safeCommand).redirectErrorStream(true).start();
      boolean completed;
      try {
        completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        process.destroyForcibly();
        throw interrupted;
      }
      if (!completed) {
        process.destroyForcibly();
        process.waitFor();
        return new CommandResult(-1, "", "command timed out");
      }
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return new CommandResult(process.exitValue(), output, "");
    }
  }
}
