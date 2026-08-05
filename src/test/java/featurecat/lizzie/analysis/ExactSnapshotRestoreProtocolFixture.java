package featurecat.lizzie.analysis;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Test-only transport that exercises Leelaz's real command queue and response routing. */
public final class ExactSnapshotRestoreProtocolFixture {
  private ExactSnapshotRestoreProtocolFixture() {}

  public static Transport install(Leelaz engine, CommandBehavior behavior) {
    Transport transport = new Transport(engine, behavior);
    engine.installCommandOutputForTest(transport);
    return transport;
  }

  @FunctionalInterface
  public interface CommandBehavior {
    Response onCommand(String command) throws Exception;
  }

  public static final class Response {
    private final char prefix;
    private final String detail;

    private Response(char prefix, String detail) {
      this.prefix = prefix;
      this.detail = detail;
    }

    public static Response success() {
      return new Response('=', "");
    }

    public static Response error(String detail) {
      return new Response('?', detail == null ? "" : detail);
    }
  }

  public static final class Transport extends OutputStream {
    private final Leelaz engine;
    private final CommandBehavior behavior;
    private final StringBuilder current = new StringBuilder();
    private final List<String> commands = new ArrayList<>();
    private final List<String> rawCommands = new ArrayList<>();

    private Transport(Leelaz engine, CommandBehavior behavior) {
      this.engine = engine;
      this.behavior = behavior;
    }

    @Override
    public synchronized void write(int value) {
      current.append((char) value);
    }

    @Override
    public synchronized void flush() throws IOException {
      String commandLine = current.toString().trim();
      current.setLength(0);
      if (commandLine.isEmpty()) {
        return;
      }
      rawCommands.add(commandLine);
      commands.add(commandPayload(commandLine));
      try {
        Response response = behavior.onCommand(commandPayload(commandLine));
        if (response != null) {
          invokeResponse(responseLine(commandLine, response));
        }
      } catch (IOException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IOException(failure.getMessage(), failure);
      }
    }

    public synchronized List<String> commands() {
      return List.copyOf(commands);
    }

    public synchronized List<String> rawCommands() {
      return List.copyOf(rawCommands);
    }

    private void invokeResponse(String responseLine) {
      engine.processCommandResponseLineForTest(responseLine);
    }

    private static String commandPayload(String commandLine) {
      int split = commandLine.indexOf(' ');
      if (split <= 0 || !isDigits(commandLine.substring(0, split))) {
        return commandLine;
      }
      return commandLine.substring(split + 1);
    }

    private static String responseLine(String commandLine, Response response) {
      int split = commandLine.indexOf(' ');
      String id =
          split > 0 && isDigits(commandLine.substring(0, split))
              ? commandLine.substring(0, split)
              : "";
      return response.prefix + id + (response.detail.isEmpty() ? "" : " " + response.detail);
    }

    private static boolean isDigits(String value) {
      if (value.isEmpty()) {
        return false;
      }
      for (int index = 0; index < value.length(); index++) {
        if (!Character.isDigit(value.charAt(index))) {
          return false;
        }
      }
      return true;
    }
  }
}
