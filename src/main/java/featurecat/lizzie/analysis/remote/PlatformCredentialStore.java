package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Creates the native credential backend for the current operating system. */
public final class PlatformCredentialStore {
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
  private static final String APPLICATION_ID = "lizzieyzy-next";
  private static final String ZHIZI_KEYCHAIN_SERVICE_PREFIX = "cn.lizzieyzy.next.zhizi.";
  private static final String AI_COMMENTARY_KEYCHAIN_SERVICE =
      "cn.lizzieyzy.next.ai-commentary.api-key";

  private PlatformCredentialStore() {}

  public static CredentialStore create(Path credentialDirectory) {
    return create(
        System.getProperty("os.name", ""), credentialDirectory, new ProcessCommandRunner());
  }

  static CredentialStore create(
      String osName, Path credentialDirectory, CredentialCommandRunner runner) {
    String os = normalize(osName);
    if (os.contains("mac")) {
      return new MacKeychainStore(runner);
    }
    if (os.contains("windows")) {
      if (credentialDirectory == null) {
        return new UnavailableStore("session-only");
      }
      return new WindowsDpapiStore(credentialDirectory, runner);
    }
    if (os.contains("linux")) {
      return new LinuxSecretServiceStore(runner);
    }
    return new UnavailableStore("session-only");
  }

  private abstract static class CommandCredentialStore implements CredentialStore {
    final CredentialCommandRunner runner;

    CommandCredentialStore(CredentialCommandRunner runner) {
      this.runner = runner;
    }

    CommandResult run(List<String> command, String input) throws IOException {
      return runner.run(command, input == null ? "" : input, COMMAND_TIMEOUT);
    }

    static String account(String account) {
      String normalized = account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
      return normalized.isEmpty() ? "default" : normalized;
    }

    static IOException failure(String operation) {
      return new IOException("System credential storage could not " + operation + " the secret.");
    }
  }

  private static final class MacKeychainStore extends CommandCredentialStore {
    private volatile Boolean available;

    MacKeychainStore(CredentialCommandRunner runner) {
      super(runner);
    }

    @Override
    public String backendName() {
      return "macos-keychain";
    }

    @Override
    public boolean isAvailable() {
      Boolean cached = available;
      if (cached != null) {
        return cached;
      }
      boolean detected;
      try {
        detected =
            run(List.of("/usr/bin/security", "help", "find-generic-password"), "").exitCode == 0;
      } catch (IOException e) {
        detected = false;
      }
      available = detected;
      return detected;
    }

    @Override
    public Optional<String> read(Kind kind, String account) throws IOException {
      if (!isAvailable()) {
        return Optional.empty();
      }
      CommandResult result =
          run(
              List.of(
                  "/usr/bin/security",
                  "find-generic-password",
                  "-a",
                  account(account),
                  "-s",
                  service(kind),
                  "-w"),
              "");
      if (result.exitCode == 0) {
        return nonEmptySecret(result.output);
      }
      if (result.exitCode == 44) {
        return Optional.empty();
      }
      throw failure("read");
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      if (!isAvailable() || secret == null || secret.isEmpty()) {
        throw failure("write");
      }
      // Keeping -w last makes the security tool read the password from stdin instead of argv.
      CommandResult result =
          run(
              List.of(
                  "/usr/bin/security",
                  "add-generic-password",
                  "-U",
                  "-a",
                  account(account),
                  "-s",
                  service(kind),
                  "-w"),
              secret + System.lineSeparator() + secret + System.lineSeparator());
      if (result.exitCode != 0) {
        throw failure("write");
      }
    }

    @Override
    public void delete(Kind kind, String account) throws IOException {
      if (!isAvailable()) {
        return;
      }
      CommandResult result =
          run(
              List.of(
                  "/usr/bin/security",
                  "delete-generic-password",
                  "-a",
                  account(account),
                  "-s",
                  service(kind)),
              "");
      if (result.exitCode != 0 && result.exitCode != 44) {
        throw failure("delete");
      }
    }

    private static String service(Kind kind) {
      return kind == Kind.API_KEY
          ? AI_COMMENTARY_KEYCHAIN_SERVICE
          : ZHIZI_KEYCHAIN_SERVICE_PREFIX + kind.id();
    }
  }

  private static final class LinuxSecretServiceStore extends CommandCredentialStore {
    private volatile Boolean available;

    LinuxSecretServiceStore(CredentialCommandRunner runner) {
      super(runner);
    }

    @Override
    public String backendName() {
      return "linux-secret-service";
    }

    @Override
    public boolean isAvailable() {
      Boolean cached = available;
      if (cached != null) {
        return cached;
      }
      boolean detected;
      try {
        detected = run(List.of("secret-tool", "--version"), "").exitCode == 0;
      } catch (IOException e) {
        detected = false;
      }
      available = detected;
      return detected;
    }

    @Override
    public Optional<String> read(Kind kind, String account) throws IOException {
      if (!isAvailable()) {
        return Optional.empty();
      }
      CommandResult result = run(secretToolCommand("lookup", kind, account), "");
      if (result.exitCode == 0) {
        return nonEmptySecret(result.output);
      }
      if (result.exitCode == 1) {
        return Optional.empty();
      }
      throw failure("read");
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      if (!isAvailable() || secret == null || secret.isEmpty()) {
        throw failure("write");
      }
      ArrayList<String> command = new ArrayList<>();
      command.add("secret-tool");
      command.add("store");
      command.add(
          kind == Kind.API_KEY
              ? "--label=LizzieYzy Next AI Commentary API Key"
              : "--label=LizzieYzy Next Zhizi " + kind.id());
      command.addAll(secretAttributes(kind, account));
      CommandResult result = run(command, secret + System.lineSeparator());
      if (result.exitCode != 0) {
        throw failure("write");
      }
    }

    @Override
    public void delete(Kind kind, String account) throws IOException {
      if (!isAvailable()) {
        return;
      }
      CommandResult result = run(secretToolCommand("clear", kind, account), "");
      if (result.exitCode != 0 && result.exitCode != 1) {
        throw failure("delete");
      }
    }

    private static List<String> secretToolCommand(String operation, Kind kind, String account) {
      ArrayList<String> command = new ArrayList<>();
      command.add("secret-tool");
      command.add(operation);
      command.addAll(secretAttributes(kind, account));
      return command;
    }

    private static List<String> secretAttributes(Kind kind, String account) {
      return List.of("application", APPLICATION_ID, "kind", kind.id(), "account", account(account));
    }
  }

  private static final class WindowsDpapiStore extends CommandCredentialStore {
    private static final String POWERSHELL = "powershell.exe";
    private static final String PROTECT_SCRIPT =
        "$v=[Console]::In.ReadToEnd();"
            + "$b=[Text.Encoding]::UTF8.GetBytes($v);"
            + "$p=[Security.Cryptography.ProtectedData]::Protect($b,$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Convert]::ToBase64String($p))";
    private static final String UNPROTECT_SCRIPT =
        "$v=[Console]::In.ReadToEnd().Trim();"
            + "$b=[Convert]::FromBase64String($v);"
            + "$p=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,"
            + "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
            + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($p))";

    private final Path directory;
    private volatile Boolean available;

    WindowsDpapiStore(Path directory, CredentialCommandRunner runner) {
      super(runner);
      this.directory = directory;
    }

    @Override
    public String backendName() {
      return "windows-dpapi";
    }

    @Override
    public boolean isAvailable() {
      Boolean cached = available;
      if (cached != null) {
        return cached;
      }
      boolean detected;
      try {
        detected =
            run(
                        List.of(
                            POWERSHELL,
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "$null=[Security.Cryptography.ProtectedData];exit 0"),
                        "")
                    .exitCode
                == 0;
      } catch (IOException e) {
        detected = false;
      }
      available = detected;
      return detected;
    }

    @Override
    public Optional<String> read(Kind kind, String account) throws IOException {
      Path path = credentialPath(kind, account);
      if (!isAvailable() || !Files.isRegularFile(path)) {
        return Optional.empty();
      }
      String encrypted = Files.readString(path, StandardCharsets.US_ASCII).trim();
      if (encrypted.isEmpty()) {
        return Optional.empty();
      }
      CommandResult result = run(powershell(UNPROTECT_SCRIPT), encrypted);
      if (result.exitCode != 0) {
        throw failure("read");
      }
      return nonEmptySecret(result.output);
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      if (!isAvailable() || secret == null || secret.isEmpty()) {
        throw failure("write");
      }
      CommandResult result = run(powershell(PROTECT_SCRIPT), secret);
      if (result.exitCode != 0 || nonEmptySecret(result.output).isEmpty()) {
        throw failure("write");
      }
      Files.createDirectories(directory);
      Path target = credentialPath(kind, account);
      Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
      try {
        Files.writeString(temporary, result.output.trim(), StandardCharsets.US_ASCII);
        moveAtomically(temporary, target);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }

    @Override
    public void delete(Kind kind, String account) throws IOException {
      Files.deleteIfExists(credentialPath(kind, account));
    }

    private Path credentialPath(Kind kind, String account) {
      return directory.resolve(kind.id() + "-" + accountDigest(account(account)) + ".dpapi");
    }

    private static List<String> powershell(String script) {
      return List.of(POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", script);
    }
  }

  private static final class UnavailableStore implements CredentialStore {
    private final String backend;

    UnavailableStore(String backend) {
      this.backend = backend;
    }

    @Override
    public String backendName() {
      return backend;
    }

    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public Optional<String> read(Kind kind, String account) {
      return Optional.empty();
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      throw new IOException("System credential storage is unavailable.");
    }

    @Override
    public void delete(Kind kind, String account) {}
  }

  interface CredentialCommandRunner {
    CommandResult run(List<String> command, String input, Duration timeout) throws IOException;
  }

  static final class CommandResult {
    final int exitCode;
    final String output;

    CommandResult(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output == null ? "" : output;
    }
  }

  private static final class ProcessCommandRunner implements CredentialCommandRunner {
    @Override
    public CommandResult run(List<String> command, String input, Duration timeout)
        throws IOException {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      try (OutputStream stdin = process.getOutputStream()) {
        if (input != null && !input.isEmpty()) {
          stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
      }
      try {
        if (!process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          throw new IOException("System credential storage timed out.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new IOException("System credential storage was interrupted.", e);
      }
      byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
      if (output.length > MAX_OUTPUT_BYTES) {
        throw new IOException("System credential storage returned too much data.");
      }
      return new CommandResult(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }
  }

  private static Optional<String> nonEmptySecret(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String normalized = value.stripTrailing();
    return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
  }

  private static String accountDigest(String account) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(account.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable.", e);
    }
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
