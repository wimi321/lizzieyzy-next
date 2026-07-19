package featurecat.lizzie.analysis.gtpconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class GtpConfigurationProbeTest {
  @Test
  void reportsUnsupportedEngineWithoutRequestingSchema() throws Exception {
    ScriptedFactory factory = new ScriptedFactory(response(true, "false"));

    GtpConfigurationProbe.Inspection inspection =
        new GtpConfigurationProbe(factory).inspect("fake-engine", Duration.ofSeconds(1));

    assertFalse(inspection.supported());
    assertEquals(List.of("known_command zengtp_config_schema"), factory.commands);
    assertTrue(factory.closed);
  }

  @Test
  void discoversSchemaAndAppliesAtomicProfile() throws Exception {
    String schema = GtpConfigurationSchemaTest.schemaPayload().toString();
    ScriptedFactory inspectFactory =
        new ScriptedFactory(response(true, "true"), response(true, schema));
    GtpConfigurationProbe.Inspection inspection =
        new GtpConfigurationProbe(inspectFactory).inspect("fake-engine", Duration.ofSeconds(1));
    assertTrue(inspection.supported());
    assertEquals("zengtp-config", inspection.schema().protocol());

    JSONObject profile =
        new JSONObject()
            .put("mode", "fixed-time")
            .put("rankPreset", "9d")
            .put("maxTimeSeconds", 5.0)
            .put("threads", 8);
    JSONObject savePayload =
        new JSONObject()
            .put("profile", new JSONObject(profile.toString()))
            .put("state", new JSONObject().put("selected", new JSONObject(profile.toString())));
    ScriptedFactory applyFactory =
        new ScriptedFactory(
            response(true, "true"),
            response(true, schema),
            response(true, new JSONObject().put("operation", "set").toString()),
            response(true, savePayload.toString()));

    GtpConfigurationProbe.ApplyResult result =
        new GtpConfigurationProbe(applyFactory)
            .applyProfile("fake-engine", profile, Duration.ofSeconds(1));

    assertEquals("fixed-time", result.profile().getString("mode"));
    assertEquals(
        List.of(
            "known_command zengtp_config_schema",
            "zengtp_config_schema",
            "zengtp_config_set " + profile,
            "zengtp_config_save"),
        applyFactory.commands);
  }

  @Test
  void surfacesStructuredEngineErrorsAndRejectsInvalidClientValues() {
    String schema = GtpConfigurationSchemaTest.schemaPayload().toString();
    JSONObject error =
        new JSONObject()
            .put(
                "error",
                new JSONObject()
                    .put("code", "invalid_value")
                    .put("parameter", "threads")
                    .put("message", "threads must be positive"));
    ScriptedFactory rejectedFactory =
        new ScriptedFactory(
            response(true, "true"), response(true, schema), response(false, error.toString()));

    IOException rejected =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe(rejectedFactory)
                    .applyProfile(
                        "fake-engine", new JSONObject().put("threads", 2), Duration.ofSeconds(1)));
    assertTrue(rejected.getMessage().contains("invalid_value"));
    assertTrue(rejected.getMessage().contains("threads"));

    ScriptedFactory invalidFactory =
        new ScriptedFactory(response(true, "true"), response(true, schema));
    IOException invalid =
        assertThrows(
            IOException.class,
            () ->
                new GtpConfigurationProbe(invalidFactory)
                    .applyProfile(
                        "fake-engine", new JSONObject().put("threads", 0), Duration.ofSeconds(1)));
    assertTrue(invalid.getMessage().contains("threads"));
    assertEquals(2, invalidFactory.commands.size());
  }

  @Test
  void exchangesNumberedGtpMessagesWithARealChildProcessAndTimesOutCleanly() throws Exception {
    String command = fakeEngineCommand(false);
    GtpConfigurationProbe probe = new GtpConfigurationProbe();

    GtpConfigurationProbe.Inspection inspection = probe.inspect(command, Duration.ofSeconds(2));
    JSONObject profile =
        new JSONObject()
            .put("mode", "fixed-time")
            .put("rankPreset", "9d")
            .put("maxTimeSeconds", 3.5)
            .put("threads", 6);
    GtpConfigurationProbe.ApplyResult result =
        probe.applyProfile(command, profile, Duration.ofSeconds(2));

    assertTrue(inspection.supported());
    assertEquals("fixed-time", result.profile().getString("mode"));
    assertEquals(6, result.profile().getInt("threads"));

    IOException timeout =
        assertThrows(
            IOException.class,
            () -> probe.inspect(fakeEngineCommand(true), Duration.ofMillis(200)));
    assertTrue(timeout.getMessage().contains("Timed out"));
  }

  private static String fakeEngineCommand(boolean hangOnSchema) {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java")
            .toAbsolutePath()
            .toString();
    String classes = Path.of("target", "test-classes").toAbsolutePath().toString();
    return quote(executable)
        + " -cp "
        + quote(classes)
        + " "
        + FakeGtpEngine.class.getName()
        + (hangOnSchema ? " hang" : "");
  }

  private static String quote(String value) {
    return "\"" + value + "\"";
  }

  private static GtpConfigurationProbe.Response response(boolean success, String payload) {
    return new GtpConfigurationProbe.Response(success, payload);
  }

  private static final class ScriptedFactory implements GtpConfigurationProbe.SessionFactory {
    private final Queue<GtpConfigurationProbe.Response> responses;
    private final List<String> commands = new ArrayList<String>();
    private boolean closed;

    private ScriptedFactory(GtpConfigurationProbe.Response... responses) {
      this.responses = new ArrayDeque<GtpConfigurationProbe.Response>(Arrays.asList(responses));
    }

    @Override
    public GtpConfigurationProbe.Session open(String engineCommand, Duration timeout) {
      return new GtpConfigurationProbe.Session() {
        @Override
        public GtpConfigurationProbe.Response request(String command) throws IOException {
          commands.add(command);
          GtpConfigurationProbe.Response response = responses.poll();
          if (response == null) {
            throw new IOException("Unexpected command: " + command);
          }
          return response;
        }

        @Override
        public void close() {
          closed = true;
        }
      };
    }
  }

  public static final class FakeGtpEngine {
    private static final String SCHEMA =
        "{\"protocol\":\"zengtp-config\",\"version\":1,\"persistenceOwner\":\"client\","
            + "\"batchSemantics\":\"atomic\",\"fields\":["
            + "{\"name\":\"mode\",\"type\":\"string\",\"group\":\"basic\","
            + "\"defaultValue\":\"rank\",\"enumValues\":[\"rank\",\"fixed-time\",\"advanced\"],"
            + "\"apply\":\"next-search\",\"requiresRestart\":false},"
            + "{\"name\":\"rankPreset\",\"type\":\"string\",\"group\":\"basic\","
            + "\"defaultValue\":\"9d\",\"enumValues\":[\"6k\",\"1d\",\"9d\"],"
            + "\"activeWhen\":\"mode=rank\",\"apply\":\"next-search\",\"requiresRestart\":false},"
            + "{\"name\":\"maxTimeSeconds\",\"type\":\"number\",\"group\":\"advanced\","
            + "\"defaultValue\":60.0,\"minimum\":0,\"activeWhen\":\"mode=fixed-time|advanced\","
            + "\"apply\":\"next-search\",\"requiresRestart\":false},"
            + "{\"name\":\"threads\",\"type\":\"integer\",\"group\":\"advanced\","
            + "\"defaultValue\":4,\"minimum\":1,\"apply\":\"next-search\","
            + "\"requiresRestart\":false}],\"state\":{\"selected\":{\"mode\":\"rank\","
            + "\"rankPreset\":\"9d\",\"maxTimeSeconds\":60.0,\"threads\":4},"
            + "\"effective\":{\"mode\":\"rank\",\"rankPreset\":\"9d\","
            + "\"maxTimeSeconds\":60.0,\"threads\":4}}}";

    private FakeGtpEngine() {}

    public static void main(String[] args) throws Exception {
      boolean hangOnSchema = args.length > 0 && "hang".equals(args[0]);
      String profile = "{}";
      try (BufferedReader input =
              new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
          PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
        String line;
        while ((line = input.readLine()) != null) {
          if ("quit".equals(line.trim())) {
            return;
          }
          int separator = line.indexOf(' ');
          if (separator <= 0) {
            continue;
          }
          String id = line.substring(0, separator);
          String command = line.substring(separator + 1);
          if ("known_command zengtp_config_schema".equals(command)) {
            respond(output, id, "true");
          } else if ("zengtp_config_schema".equals(command)) {
            if (hangOnSchema) {
              Thread.sleep(10_000);
            } else {
              respond(output, id, SCHEMA);
            }
          } else if (command.startsWith("zengtp_config_set ")) {
            profile = command.substring("zengtp_config_set ".length());
            respond(output, id, "{\"operation\":\"set\"}");
          } else if ("zengtp_config_save".equals(command)) {
            respond(output, id, "{\"profile\":" + profile + ",\"state\":{}}");
          } else {
            output.println("?" + id + " unknown command");
            output.println();
          }
        }
      }
    }

    private static void respond(PrintWriter output, String id, String payload) {
      output.println("=" + id + " " + payload);
      output.println();
    }
  }
}
