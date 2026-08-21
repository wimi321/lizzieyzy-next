package featurecat.lizzie.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.Map;

public final class CorrelationConverter extends ClassicConverter {
  @Override
  public String convert(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    if (mdc == null || mdc.isEmpty()) {
      return "";
    }
    StringBuilder rendered = new StringBuilder();
    append(rendered, "session", mdc.get(CorrelationContext.APP_SESSION));
    append(rendered, "engine", mdc.get(CorrelationContext.ENGINE_ID));
    append(rendered, "command", mdc.get(CorrelationContext.COMMAND_ID));
    append(rendered, "request", mdc.get(CorrelationContext.REQUEST_ID));
    append(rendered, "gma", mdc.get(CorrelationContext.GMA_ID));
    append(rendered, "yike", mdc.get(CorrelationContext.SYNC_SESSION));
    append(rendered, "trace", mdc.get(CorrelationContext.TRACE_SESSION));
    return rendered.toString();
  }

  private static void append(StringBuilder rendered, String label, String value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    if (rendered.length() > 0) {
      rendered.append(' ');
    }
    rendered.append(label).append('=').append(value);
  }
}
