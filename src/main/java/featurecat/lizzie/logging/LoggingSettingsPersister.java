package featurecat.lizzie.logging;

import java.io.IOException;

@FunctionalInterface
public interface LoggingSettingsPersister {
  void save(LoggingSettings settings) throws IOException;
}
