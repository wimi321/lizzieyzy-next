package featurecat.lizzie.util.katago.tuning;

import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.NvidiaGpuDetector.GpuInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.SwingUtilities;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fresh content identity for one measured launch; never hashes model files on the event thread. */
public final class KataGoMeasuredFingerprint {
  private static final Set<String> MANAGED =
      Set.of(
          "numSearchThreads",
          "numAnalysisThreads",
          "numSearchThreadsPerAnalysisThread",
          "nnMaxBatchSize",
          "homeDataDir");

  private KataGoMeasuredFingerprint() {}

  public static JSONObject capture(SetupSnapshot snapshot, GpuInfo gpu) throws IOException {
    return capture(snapshot, gpu, snapshot == null ? List.of() : snapshot.sourceArguments);
  }

  public static JSONObject capture(
      SetupSnapshot snapshot, GpuInfo gpu, List<String> effectiveCommand) throws IOException {
    if (SwingUtilities.isEventDispatchThread())
      throw new IOException("Fingerprint requires a worker thread");
    if (snapshot == null
        || gpu == null
        || gpu.name.isBlank()
        || gpu.driverVersion.isBlank()
        || gpu.memoryMiB <= 0) throw new IOException("Incomplete local engine or GPU identity");
    List<Path> configs = configPaths(snapshot);
    if (configs.isEmpty()) throw new IOException("No configuration file in the engine command");
    List<String> included = new ArrayList<>();
    Set<Path> visited = new HashSet<>();
    for (int index = 0; index < configs.size(); index++) {
      appendIncludes(configs.get(index), visited, included, index > 0);
    }
    Map<String, String> semantics = new TreeMap<>();
    KataGoCommandSpec.parse(effectiveCommand)
        .effectiveOverrides()
        .forEach(
            (key, value) -> {
              if (!MANAGED.contains(key)) semantics.put(key, value);
            });
    validateArguments(effectiveCommand);
    return new JSONObject()
        .put("engineSha256", sha256(snapshot.enginePath))
        .put("modelSha256", sha256(snapshot.activeWeightPath))
        .put("configSha256", sha256(configs.get(0)))
        .put("configIncludes", new JSONArray(included))
        .put("gpuName", gpu.name)
        .put("driverVersion", gpu.driverVersion)
        .put("memoryMiB", gpu.memoryMiB)
        .put("commandSemantics", new JSONObject(semantics));
  }

  private static List<Path> configPaths(SetupSnapshot snapshot) throws IOException {
    List<Path> paths = new ArrayList<>();
    List<String> args = snapshot.sourceArguments;
    for (int index = 2; index < args.size(); index++) {
      if ("-config".equals(args.get(index)) || "--config".equals(args.get(index))) {
        if (++index >= args.size()) throw new IOException("Missing configuration argument");
        Path path = Path.of(args.get(index));
        paths.add(path.isAbsolute() ? path : snapshot.executionDirectory.resolve(path));
      }
    }
    return paths;
  }

  private static void validateArguments(List<String> arguments) throws IOException {
    if (arguments.size() < 2
        || !("gtp".equals(arguments.get(1)) || "analysis".equals(arguments.get(1)))) {
      throw new IOException("Only direct local GTP or analysis commands support measured profiles");
    }
    Set<String> valued =
        Set.of(
            "-model",
            "--model",
            "-weights",
            "--weights",
            "-config",
            "--config",
            "-override-config",
            "--override-config");
    for (int index = 2; index < arguments.size(); index++) {
      String token = arguments.get(index);
      if (valued.contains(token)) {
        if (++index >= arguments.size()) throw new IOException("Missing command argument");
      } else if (!"-quit-without-waiting".equals(token)) {
        throw new IOException("Unmeasured command argument: " + token);
      }
    }
  }

  private static void appendIncludes(
      Path path, Set<Path> visited, List<String> hashes, boolean includeSelf) throws IOException {
    Path real = path.toRealPath();
    if (visited.size() >= 64 || !visited.add(real))
      throw new IOException("Repeated or excessive config includes");
    if (includeSelf) hashes.add(sha256(real));
    if (Files.size(real) > 4 * 1024 * 1024) throw new IOException("Configuration file too large");
    for (String raw : Files.readAllLines(real)) {
      String line = raw.split("#", 2)[0].trim();
      if (!line.startsWith("@")) continue;
      String[] directive = line.split("[\\s=]+", 2);
      if (directive.length != 2 || !"@include".equals(directive[0])) {
        throw new IOException("Unsupported config directive");
      }
      String value = directive[1].trim();
      if (value.length() >= 2
          && ((value.startsWith("\"") && value.endsWith("\""))
              || (value.startsWith("'") && value.endsWith("'"))))
        value = value.substring(1, value.length() - 1);
      Path child = Path.of(value);
      if (value.isBlank() || child.isAbsolute())
        throw new IOException("Unsupported config include path");
      appendIncludes(real.getParent().resolve(child), visited, hashes, true);
    }
  }

  /** Intentionally fresh: same-length replacements with preserved timestamps must invalidate. */
  public static String sha256(Path path) throws IOException {
    if (path == null) throw new IOException("Missing fingerprint input");
    BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
    if (!before.isRegularFile()) throw new IOException("Fingerprint input is not a regular file");
    MessageDigest hash;
    try {
      hash = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[64 * 1024];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Fingerprint cancelled");
        hash.update(buffer, 0, count);
      }
    }
    BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
    if (before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !Objects.equals(before.fileKey(), after.fileKey()))
      throw new IOException("Fingerprint input changed while reading");
    return HexFormat.of().formatHex(hash.digest());
  }
}
