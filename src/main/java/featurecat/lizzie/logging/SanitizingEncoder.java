package featurecat.lizzie.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;

final class SanitizingEncoder extends EncoderBase<ILoggingEvent> {
  private final PatternLayout layout = new PatternLayout();
  private PersistenceSanitizer sanitizer = new PersistenceSanitizer();
  private LogStream stream = LogStream.APP;
  private String pattern =
      "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%logger] %corr %msg%n%ex";

  void setPattern(String pattern) {
    this.pattern = pattern;
  }

  void setSanitizer(PersistenceSanitizer sanitizer) {
    this.sanitizer = sanitizer == null ? new PersistenceSanitizer() : sanitizer;
  }

  void setLogStream(LogStream stream) {
    this.stream = stream == null ? LogStream.APP : stream;
  }

  LogStream logStream() {
    return stream;
  }

  @Override
  public void start() {
    layout.setContext(context);
    layout.getInstanceConverterMap().put("corr", CorrelationConverter::new);
    layout.setPattern(pattern);
    layout.start();
    super.start();
  }

  @Override
  public void stop() {
    super.stop();
    layout.stop();
  }

  @Override
  public byte[] headerBytes() {
    return new byte[0];
  }

  @Override
  public byte[] footerBytes() {
    return new byte[0];
  }

  @Override
  public byte[] encode(ILoggingEvent event) {
    try {
      String formatted = layout.doLayout(event);
      return sanitizer.sanitize(formatted).getBytes(StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      addError("encoder redaction failed", e);
      return (PersistenceSanitizer.FAILURE_MARKER + System.lineSeparator())
          .getBytes(StandardCharsets.UTF_8);
    }
  }
}
