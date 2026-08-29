package featurecat.lizzie.analysis.gtpconfig;

import featurecat.lizzie.analysis.AnalysisResourceCoordinator;
import featurecat.lizzie.logging.EngineObservation;
import featurecat.lizzie.logging.ObservationText;
import featurecat.lizzie.util.CommandLaunchHelper;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import featurecat.lizzie.util.Utils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Detects and updates optional engine-owned configuration through an isolated GTP process. */
public final class GtpConfigurationProbe {
  public static final String ZENGTP_PROTOCOL = "zengtp-config";
  public static final String ZENGTP_VERSION_COMMAND = "zengtp_config_version";
  public static final String ZENGTP_SCHEMA_COMMAND = "zengtp_config_schema";
  public static final String ZENGTP_SET_COMMAND = "zengtp_config_set";
  public static final String ZENGTP_SAVE_COMMAND = "zengtp_config_save";
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(12);

  private final SessionFactory sessionFactory;

  public GtpConfigurationProbe() {
    this(ProcessSession::open);
  }

  GtpConfigurationProbe(SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  public Inspection inspect(String engineCommand) throws IOException {
    return inspect(engineCommand, DEFAULT_TIMEOUT);
  }

  Inspection inspect(String engineCommand, Duration timeout) throws IOException {
    try (Session session = sessionFactory.open(engineCommand, timeout)) {
      try {
        Response capability = session.request("known_command " + ZENGTP_SCHEMA_COMMAND);
        boolean supported =
            capability.success() && "true".equalsIgnoreCase(capability.payload().trim());
        noteCapabilityCheck(session, supported);
        if (!supported) {
          return Inspection.unsupported();
        }
        Response schemaResponse = session.request(ZENGTP_SCHEMA_COMMAND);
        if (!schemaResponse.success()) {
          noteProbeFailed(session, "schema");
          throw protocolError(schemaResponse);
        }
        GtpConfigurationSchema schema =
            GtpConfigurationSchema.parse(new JSONObject(schemaResponse.payload()));
        if (!ZENGTP_PROTOCOL.equals(schema.protocol()) || schema.version() != 1) {
          noteProbeFailed(session, "schema");
          throw new IOException(
              "Unsupported GTP configuration protocol: "
                  + schema.protocol()
                  + " v"
                  + schema.version());
        }
        return Inspection.supported(schema);
      } catch (IllegalArgumentException error) {
        noteProbeFailed(session, "schema");
        throw new IOException("Invalid GTP configuration schema: " + error.getMessage(), error);
      }
    }
  }

  public ApplyResult applyProfile(String engineCommand, JSONObject profile) throws IOException {
    return applyProfile(engineCommand, profile, DEFAULT_TIMEOUT);
  }

  ApplyResult applyProfile(String engineCommand, JSONObject profile, Duration timeout)
      throws IOException {
    if (profile == null) {
      throw new IllegalArgumentException("profile must not be null");
    }
    try (Session session = sessionFactory.open(engineCommand, timeout)) {
      try {
        Response capability = session.request("known_command " + ZENGTP_SCHEMA_COMMAND);
        boolean supported =
            capability.success() && "true".equalsIgnoreCase(capability.payload().trim());
        noteCapabilityCheck(session, supported);
        if (!supported) {
          noteProbeFailed(session, "capability");
          throw new IOException("The selected engine does not support visual configuration");
        }
        Response schemaResponse = session.request(ZENGTP_SCHEMA_COMMAND);
        if (!schemaResponse.success()) {
          noteProbeFailed(session, "schema");
          throw protocolError(schemaResponse);
        }
        GtpConfigurationSchema schema =
            GtpConfigurationSchema.parse(new JSONObject(schemaResponse.payload()));
        if (!ZENGTP_PROTOCOL.equals(schema.protocol()) || schema.version() != 1) {
          noteProbeFailed(session, "schema");
          throw new IOException(
              "Unsupported GTP configuration protocol: "
                  + schema.protocol()
                  + " v"
                  + schema.version());
        }
        for (String key : profile.keySet()) {
          GtpConfigurationSchema.Field field = schema.field(key);
          if (field == null || !field.accepts(profile.opt(key))) {
            noteProbeFailed(session, "schema");
            throw new IOException("Invalid engine configuration value: " + key);
          }
        }
        Response setResponse = session.request(ZENGTP_SET_COMMAND + " " + profile.toString());
        if (!setResponse.success()) {
          noteProbeFailed(session, "apply");
          throw protocolError(setResponse);
        }
        Response saveResponse = session.request(ZENGTP_SAVE_COMMAND);
        if (!saveResponse.success()) {
          noteProbeFailed(session, "apply");
          throw protocolError(saveResponse);
        }
        JSONObject payload = new JSONObject(saveResponse.payload());
        JSONObject savedProfile = payload.optJSONObject("profile");
        JSONObject state = payload.optJSONObject("state");
        if (savedProfile == null) {
          noteProbeFailed(session, "apply");
          throw new IOException("Engine did not return a saved configuration profile");
        }
        return new ApplyResult(
            new JSONObject(savedProfile.toString()),
            state == null ? new JSONObject() : new JSONObject(state.toString()));
      } catch (IllegalArgumentException error) {
        noteProbeFailed(session, "schema");
        throw new IOException("Invalid engine configuration response: " + error.getMessage(), error);
      }
    }
  }

  private static IOException protocolError(Response response) {
    try {
      JSONObject payload = new JSONObject(response.payload());
      JSONObject error = payload.optJSONObject("error");
      if (error != null) {
        String code = error.optString("code", "engine_error");
        String parameter = error.optString("parameter", "");
        String message = error.optString("message", "Engine rejected the configuration");
        String detail = parameter.isEmpty() ? message : parameter + ": " + message;
        return new IOException(code + " - " + detail);
      }
    } catch (Exception ignored) {
      // Fall through to the raw GTP payload when the engine did not return structured JSON.
    }
    return new IOException("Engine rejected the configuration: " + response.payload());
  }

  private static void noteCapabilityCheck(Session session, boolean success) {
    try {
      if (session instanceof ProcessSession processSession) {
        processSession.recordCapabilityCheck(success);
      }
    } catch (RuntimeException ignored) {
    }
  }

  private static void noteProbeFailed(Session session, String stage) {
    try {
      if (session instanceof ProcessSession processSession) {
        processSession.noteProbeFailed(stage);
      }
    } catch (RuntimeException ignored) {
    }
  }

  static void drainProbeStderr(
      BufferedReader stderr, ProbeStderrTail tail, Supplier<String> engineId) {
    if (stderr == null) {
      return;
    }
    try {
      String line;
      while ((line = stderr.readLine()) != null) {
        try {
          if (tail != null) {
            tail.add(line);
          }
        } catch (RuntimeException ignored) {
        }
      }
    } catch (IOException ignored) {
    } catch (RuntimeException error) {
      try {
        String id = engineId == null ? null : engineId.get();
        EngineObservation.recordTransportFailure(id, "stderr", "reader-error", error);
      } catch (RuntimeException ignored) {
      }
    }
  }

  static final class ProbeStderrTail {
    static final int MAX_LINES = 40;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private int utf8Bytes;

    synchronized void add(String line) {
      if (line == null || line.isEmpty()) {
        return;
      }
      String bounded =
          ObservationText.boundedUtf8(line, ObservationText.RAW_EVENT_MAX_UTF8_BYTES, 1);
      int bytes = utf8ByteLength(bounded);
      while (!lines.isEmpty()
          && (lines.size() >= MAX_LINES
              || utf8Bytes + bytes > ObservationText.RAW_EVENT_MAX_UTF8_BYTES)) {
        String removed = lines.removeFirst();
        utf8Bytes = Math.max(0, utf8Bytes - utf8ByteLength(removed));
      }
      lines.addLast(bounded);
      utf8Bytes += bytes;
    }

    synchronized String snapshot() {
      StringBuilder builder = new StringBuilder();
      for (String line : lines) {
        if (line == null || line.isEmpty()) {
          continue;
        }
        if (builder.length() > 0) {
          builder.append('\n');
        }
        builder.append(line);
      }
      return builder.toString();
    }

    synchronized int lineCount() {
      return lines.size();
    }

    synchronized int utf8ByteCount() {
      return utf8Bytes;
    }

    private static int utf8ByteLength(String value) {
      return value.getBytes(StandardCharsets.UTF_8).length;
    }
  }

  interface SessionFactory {
    Session open(String engineCommand, Duration timeout) throws IOException;
  }

  interface Session extends AutoCloseable {
    Response request(String command) throws IOException;

    @Override
    void close();
  }

  static final class Response {
    private final boolean success;
    private final String payload;

    Response(boolean success, String payload) {
      this.success = success;
      this.payload = payload == null ? "" : payload;
    }

    boolean success() {
      return success;
    }

    String payload() {
      return payload;
    }
  }

  public static final class Inspection {
    private final boolean supported;
    private final GtpConfigurationSchema schema;

    private Inspection(boolean supported, GtpConfigurationSchema schema) {
      this.supported = supported;
      this.schema = schema;
    }

    static Inspection unsupported() {
      return new Inspection(false, null);
    }

    static Inspection supported(GtpConfigurationSchema schema) {
      return new Inspection(true, schema);
    }

    public boolean supported() {
      return supported;
    }

    public GtpConfigurationSchema schema() {
      return schema;
    }
  }

  public static final class ApplyResult {
    private final JSONObject profile;
    private final JSONObject state;

    ApplyResult(JSONObject profile, JSONObject state) {
      this.profile = profile;
      this.state = state;
    }

    public JSONObject profile() {
      return new JSONObject(profile.toString());
    }

    public JSONObject state() {
      return new JSONObject(state.toString());
    }
  }

  private static final class ProcessSession implements Session {
    private final Process process;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Duration timeout;
    private final ExecutorService readerExecutor;
    private final ProbeStderrTail stderrTail = new ProbeStderrTail();
    private final AtomicReference<String> pendingFailureStage = new AtomicReference<>();
    private final Thread stderrDrainer;
    private int nextCommandId = 1;

    private ProcessSession(Process process, Duration timeout) {
      this.process = process;
      this.timeout = timeout;
      this.reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      this.writer =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      this.readerExecutor =
          Executors.newSingleThreadExecutor(daemonThreadFactory("gtp-configuration-probe-reader"));
      this.stderrDrainer =
          new Thread(
              () -> {
                try (BufferedReader stderr =
                    new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                  drainProbeStderr(
                      stderr, stderrTail, () -> EngineObservation.identityFor(this));
                } catch (IOException | RuntimeException ignored) {
                }
              },
              "gtp-configuration-probe-stderr");
      stderrDrainer.setDaemon(true);
      stderrDrainer.start();
    }

    static ProcessSession open(String engineCommand, Duration timeout) throws IOException {
      if (engineCommand == null || engineCommand.isBlank()) {
        throw new IOException("Engine command is empty");
      }
      if (KataGoRuntimeHelper.isBenchmarkEngineSyncSuppressed()) {
        throw new IOException("KataGo tuning is using the local compute device");
      }
      if (engineCommand.startsWith("encryption||")) {
        engineCommand = Utils.doDecrypt2(engineCommand.substring("encryption||".length()));
      }
      CommandLaunchHelper.LaunchSpec launchSpec =
          CommandLaunchHelper.prepare(Utils.splitCommand(engineCommand));
      if (launchSpec.getCommandParts().isEmpty()) {
        throw new IOException("Engine command is empty");
      }
      ProcessBuilder builder = new ProcessBuilder(launchSpec.getCommandParts());
      CommandLaunchHelper.configureProcessBuilder(builder, launchSpec);
      ProcessSession session = new ProcessSession(builder.start(), timeout);
      AnalysisResourceCoordinator.processStarted(
          session, AnalysisResourceCoordinator.Purpose.OTHER, engineCommand, session.process);
      session.recordStartedQuietly();
      return session;
    }

    @Override
    public synchronized Response request(String command) throws IOException {
      if (!process.isAlive()) {
        noteProbeFailed("exited");
        throw new IOException("Engine exited before configuration completed");
      }
      int commandId = nextCommandId++;
      try {
        writer.write(commandId + " " + command);
        writer.newLine();
        writer.flush();
      } catch (IOException error) {
        noteProbeFailed(process.isAlive() ? "handshake" : "exited");
        if (!process.isAlive()) {
          throw new IOException("Engine exited before configuration completed", error);
        }
        throw error;
      }
      Future<Response> future =
          readerExecutor.submit((Callable<Response>) () -> readResponse(commandId));
      try {
        return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
      } catch (TimeoutException error) {
        future.cancel(true);
        noteProbeFailed("timeout");
        throw new IOException("Timed out waiting for the engine configuration response", error);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        noteProbeFailed("interrupted");
        throw new IOException(
            "Interrupted while waiting for the engine configuration response", error);
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        noteProbeFailed(process.isAlive() ? "handshake" : "exited");
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }
        throw new IOException("Failed to read the engine configuration response", cause);
      }
    }

    private Response readResponse(int expectedCommandId) throws IOException {
      StringBuilder payload = new StringBuilder();
      boolean success = false;
      boolean started = false;
      String line;
      while ((line = reader.readLine()) != null) {
        if (!started) {
          ParsedHeader header = ParsedHeader.parse(line, expectedCommandId);
          if (header == null) {
            continue;
          }
          success = header.success;
          started = true;
          if (!header.payload.isEmpty()) {
            payload.append(header.payload);
          }
          continue;
        }
        if (line.isEmpty()) {
          return new Response(success, payload.toString());
        }
        if (payload.length() > 0) {
          payload.append('\n');
        }
        payload.append(line);
      }
      try {
        process.waitFor(200, TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
      if (!process.isAlive()) {
        throw new IOException("Engine exited before configuration completed");
      }
      throw new IOException("Engine closed its output before completing the GTP response");
    }

    private void recordStartedQuietly() {
      try {
        EngineObservation.recordProbeStarted(EngineObservation.identityFor(this));
      } catch (RuntimeException ignored) {
      }
    }

    private void recordCapabilityCheck(boolean success) {
      try {
        EngineObservation.recordProbeCapabilityCheck(
            EngineObservation.identityFor(this), success);
      } catch (RuntimeException ignored) {
      }
    }

    private void noteProbeFailed(String stage) {
      pendingFailureStage.compareAndSet(null, stage);
    }

    private void emitPendingFailureDiagnostics() {
      try {
        String stage = pendingFailureStage.get();
        if (stage == null) {
          return;
        }
        String id = EngineObservation.identityFor(this);
        EngineObservation.recordProbeFailed(id, stage);
        String facts = stderrTail.snapshot();
        if (!facts.isEmpty()) {
          EngineObservation.recordProbeStderr(id, facts);
        }
      } catch (RuntimeException ignored) {
      }
    }

    @Override
    public void close() {
      try {
        if (process.isAlive()) {
          writer.write("quit");
          writer.newLine();
          writer.flush();
        }
      } catch (IOException ignored) {
        // The process may already have exited after reporting an error.
      }
      process.destroy();
      try {
        if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor(500, TimeUnit.MILLISECONDS);
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
      readerExecutor.shutdownNow();
      try {
        stderrDrainer.join(200);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
      try {
        emitPendingFailureDiagnostics();
      } finally {
        AnalysisResourceCoordinator.processStopped(
            this, AnalysisResourceCoordinator.Purpose.OTHER, process);
      }
    }
  }

  private static ThreadFactory daemonThreadFactory(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  private static final class ParsedHeader {
    private final boolean success;
    private final String payload;

    private ParsedHeader(boolean success, String payload) {
      this.success = success;
      this.payload = payload;
    }

    private static ParsedHeader parse(String line, int expectedCommandId) {
      if (line == null || line.length() < 2 || (line.charAt(0) != '=' && line.charAt(0) != '?')) {
        return null;
      }
      int index = 1;
      while (index < line.length() && Character.isDigit(line.charAt(index))) {
        index++;
      }
      if (index == 1) {
        return null;
      }
      int commandId;
      try {
        commandId = Integer.parseInt(line.substring(1, index));
      } catch (NumberFormatException error) {
        return null;
      }
      if (commandId != expectedCommandId) {
        return null;
      }
      String payload = index >= line.length() ? "" : line.substring(index).trim();
      return new ParsedHeader(line.charAt(0) == '=', payload);
    }
  }
}
