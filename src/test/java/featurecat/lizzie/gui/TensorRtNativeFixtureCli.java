package featurecat.lizzie.gui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Native jpackage entry point for the controlled TensorRT UI acceptance lane. */
public final class TensorRtNativeFixtureCli {
  static final String NVIDIA_SENTINEL = "LIZZIE_CONTROLLED_NVIDIA_8E4C";
  private static final String QUERY_WITH_COMPUTE =
      "--query-gpu=name,compute_cap,driver_version,memory.total";
  private static final String QUERY_WITHOUT_COMPUTE =
      "--query-gpu=name,driver_version,memory.total";
  private static final String CSV_FORMAT = "--format=csv,noheader,nounits";

  private TensorRtNativeFixtureCli() {}

  public static void main(String[] args) throws Exception {
    String mode = System.getProperty("fixture.mode", "").trim();
    if ("nvidia".equals(mode)) {
      runNvidiaSmi(args);
      return;
    }
    if ("gtp".equals(mode)) {
      runGtp();
      return;
    }
    System.err.println("unsupported fixture mode: " + mode);
    System.exit(64);
  }

  private static void runNvidiaSmi(String[] args) throws Exception {
    appendLedger(System.getenv("LIZZIE_NVIDIA_SMI_LEDGER"), String.join(" ", args));
    if (Arrays.equals(args, new String[] {QUERY_WITH_COMPUTE, CSV_FORMAT})) {
      System.out.println(NVIDIA_SENTINEL + " RTX 3060, 8.6, 570.65, 12288");
      return;
    }
    if (Arrays.equals(args, new String[] {QUERY_WITHOUT_COMPUTE, CSV_FORMAT})) {
      System.out.println(NVIDIA_SENTINEL + " RTX 3060, 570.65, 12288");
      return;
    }
    System.err.println("unsupported nvidia-smi arguments: " + Arrays.toString(args));
    System.exit(65);
  }

  private static void runGtp() throws Exception {
    String rootValue = System.getenv("LIZZIE_CONTROLLED_GTP_ROOT");
    if (rootValue == null || rootValue.isBlank()) {
      throw new IllegalStateException("LIZZIE_CONTROLLED_GTP_ROOT is required");
    }
    String backend = System.getProperty("fixture.backend", "unknown").trim();
    Path root = Path.of(rootValue).toAbsolutePath().normalize().resolve(backend);
    Files.createDirectories(root);
    ControlledGtpPeer.main(new String[] {root.toString()});
  }

  private static void appendLedger(String path, String line) throws Exception {
    if (path == null || path.isBlank()) {
      return;
    }
    Path ledger = Path.of(path).toAbsolutePath().normalize();
    Files.createDirectories(ledger.getParent());
    Files.writeString(
        ledger,
        ProcessHandle.current().pid() + " " + line + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
