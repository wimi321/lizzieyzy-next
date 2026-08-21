package featurecat.lizzie.analysis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/** Cross-platform fake GTP process used by engine lifecycle integration tests. */
public final class UpdateEngineGtpFixture {
  private UpdateEngineGtpFixture() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 6) {
      throw new IllegalArgumentException(
          "expected log, startup gate, loadsgf failure, fence gate, catchup gate, and fence failure paths");
    }
    Path log = Path.of(args[0]);
    Path startupGate = Path.of(args[1]);
    Path loadSgfFailure = Path.of(args[2]);
    Path fenceGate = Path.of(args[3]);
    Path catchUpGate = Path.of(args[4]);
    Path fenceFailure = Path.of(args[5]);
    int nameCount = 0;
    int loadSgfCount = 0;
    try (BufferedReader input =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter output =
            new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
      String line;
      while ((line = input.readLine()) != null) {
        append(log, line);
        ParsedCommand parsed = ParsedCommand.parse(line);
        if (parsed.command.startsWith("loadsgf ")) {
          loadSgfCount++;
          Path sgf = Path.of(parsed.command.substring("loadsgf ".length()));
          append(
              log,
              "SGF:"
                  + stripTrailingNewlines(readWithRetry(sgf)));
          if (Files.isRegularFile(loadSgfFailure)) {
            writeResponse(output, parsed.id, false, "controlled restore failure");
            continue;
          }
          if (loadSgfCount >= 2) {
            waitForMarker(catchUpGate);
          }
        }
        if ("name".equals(parsed.command)) {
          nameCount++;
          waitForMarker(nameCount == 1 ? startupGate : fenceGate);
          if (nameCount >= 2 && Files.isRegularFile(fenceFailure)) {
            writeResponse(output, parsed.id, false, "controlled fence failure");
            continue;
          }
        }
        String body =
            parsed.id.isEmpty()
                ? switch (parsed.command) {
                  case "name" -> "KataGo";
                  case "version" -> "1.15";
                  case "list_commands" -> "protocol_version";
                  default -> "";
                }
                : "";
        writeResponse(output, parsed.id, true, body);
        if ("quit".equals(parsed.command)) {
          return;
        }
      }
    }
  }

  private static void waitForMarker(Path marker) throws InterruptedException {
    while (!Files.isRegularFile(marker)) {
      Thread.sleep(10L);
    }
  }

  private static String stripTrailingNewlines(String content) {
    int end = content.length();
    while (end > 0 && content.charAt(end - 1) == '\n') {
      end--;
    }
    return content.substring(0, end);
  }

  private static void append(Path log, String line) throws Exception {
    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
    writeWithRetry(log, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private static String readWithRetry(Path path) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      try {
        return Files.readString(path, StandardCharsets.UTF_8);
      } catch (IOException ex) {
        if (System.nanoTime() >= deadline) {
          throw ex;
        }
        Thread.sleep(10L);
      }
    }
  }

  private static void writeWithRetry(Path path, byte[] bytes, StandardOpenOption... options)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (true) {
      try {
        Files.write(path, bytes, options);
        return;
      } catch (IOException ex) {
        if (System.nanoTime() >= deadline) {
          throw ex;
        }
        Thread.sleep(10L);
      }
    }
  }

  private static void writeResponse(
      BufferedWriter output, String id, boolean success, String body) throws Exception {
    output.write(success ? '=' : '?');
    output.write(id);
    if (!body.isEmpty()) {
      output.write(' ');
      output.write(body);
    }
    output.newLine();
    output.newLine();
    output.flush();
  }

  private static final class ParsedCommand {
    private final String id;
    private final String command;

    private ParsedCommand(String id, String command) {
      this.id = id;
      this.command = command;
    }

    private static ParsedCommand parse(String line) {
      int separator = line.indexOf(' ');
      if (separator > 0 && line.substring(0, separator).chars().allMatch(Character::isDigit)) {
        return new ParsedCommand(line.substring(0, separator), line.substring(separator + 1));
      }
      return new ParsedCommand("", line);
    }
  }
}
