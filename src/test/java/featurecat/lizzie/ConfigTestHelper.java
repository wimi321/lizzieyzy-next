package featurecat.lizzie;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class ConfigTestHelper {
  private ConfigTestHelper() {}

  public static Config createForTests(Path runtimeWorkDirectory) {
    return Config.createForTests(
        Objects.requireNonNull(runtimeWorkDirectory, "runtimeWorkDirectory").toFile());
  }

  public static Config createBootstrapped(Path runtimeWorkDirectory) throws IOException {
    return Config.createBootstrappedForTests(
        Objects.requireNonNull(runtimeWorkDirectory, "runtimeWorkDirectory").toFile());
  }
}
