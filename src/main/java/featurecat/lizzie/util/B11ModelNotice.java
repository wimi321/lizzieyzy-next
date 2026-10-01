package featurecat.lizzie.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Model identity for presentation only. Never changes engine parameters or judges measured speed.
 */
public final class B11ModelNotice {
  private static final Executor READER =
      Executors.newFixedThreadPool(
          2,
          task -> {
            Thread thread = new Thread(task, "model-speed-notice");
            thread.setDaemon(true);
            return thread;
          });

  private B11ModelNotice() {}

  public static boolean isB11(String modelName) {
    String name = modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
    name = name.replaceFirst("\\.(?:bin|txt)(?:\\.gz)?$", "");
    return name.matches("kata1-tf[23]-b11c768-[a-z0-9-]+")
        || name.matches("b11c768h12nbt3tflrs(?:-[a-z0-9-]+)?");
  }

  public static boolean isZhiziB11(String modelIdentifier) {
    return "11b768t".equalsIgnoreCase(modelIdentifier == null ? "" : modelIdentifier.trim());
  }

  public static Lookup local(List<String> command, Path workingDirectory) {
    if (!hasHostModelPaths(command)) return known(false);
    List<String> frozenCommand = List.copyOf(command);
    Path directory =
        (workingDirectory == null ? Path.of("") : workingDirectory).toAbsolutePath().normalize();
    return lookup(() -> readLocal(frozenCommand, directory), READER);
  }

  static boolean readLocal(List<String> command, Path workingDirectory) {
    // An indirect launcher's arguments do not prove a mapping to host model files.
    // Keep that identity unknown, even if a matching B11 file happens to exist locally.
    if (!hasHostModelPaths(command)) return false;
    String model = "";
    for (int i = 1; i < command.size(); i++) {
      String part = command.get(i);
      for (String flag : List.of("-model", "--model", "-weights", "--weights")) {
        if (part.equals(flag)) {
          if (++i >= command.size()) return false;
          model = command.get(i);
          break;
        }
        if (part.startsWith(flag + "=")) {
          model = part.substring(flag.length() + 1);
          break;
        }
      }
    }
    if (model.isBlank()) return false;
    Path path = Path.of(model);
    if (!path.isAbsolute()) path = workingDirectory.resolve(path);
    // A filename or engine nickname alone is not evidence of the loaded model's identity.
    return isB11(KataGoAutoSetupHelper.readWeightModelName(path.normalize()));
  }

  public static Lookup known(boolean b11) {
    return new Lookup(CompletableFuture.completedFuture(b11));
  }

  private static boolean hasHostModelPaths(List<String> command) {
    return command != null
        && !command.isEmpty()
        && command.get(0) != null
        && !command.get(0).isBlank()
        && !CommandLaunchHelper.isIndirectLauncher(command.get(0));
  }

  static Lookup lookup(Supplier<Boolean> reader, Executor executor) {
    return new Lookup(
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return Boolean.TRUE.equals(reader.get());
              } catch (RuntimeException unavailable) {
                return false;
              }
            },
            executor));
  }

  /** Owned by one engine incarnation; late completion cannot publish into a replacement lookup. */
  public static final class Lookup {
    private final CompletableFuture<Boolean> result;

    private Lookup(CompletableFuture<Boolean> result) {
      this.result = result;
    }

    public boolean isB11() {
      return result.getNow(false);
    }
  }
}
