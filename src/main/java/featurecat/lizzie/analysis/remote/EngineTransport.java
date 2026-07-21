package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface EngineTransport extends AutoCloseable {
  void start() throws IOException;

  InputStream stdout();

  OutputStream stdin();

  InputStream stderr();

  boolean isOpen();

  default void setUnresponsiveListener(Runnable listener) {}

  default void markAnalysisProgressAccepted(long totalPlayouts) {}

  String description();

  @Override
  void close();
}
