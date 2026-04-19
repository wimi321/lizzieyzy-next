package featurecat.lizzie;

import java.nio.file.Path;
import java.util.Objects;

public final class ConfigTestHelper {
  private ConfigTestHelper() {}

  public static Config createForTests(Path runtimeWorkDirectory) {
    return Config.createForTests(
        Objects.requireNonNull(runtimeWorkDirectory, "runtimeWorkDirectory").toFile());
  }
}
