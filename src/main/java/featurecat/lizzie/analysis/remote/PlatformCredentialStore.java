package featurecat.lizzie.analysis.remote;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
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
import java.util.Arrays;
import java.util.Base64;
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
        System.getProperty("os.name", ""),
        credentialDirectory,
        new ProcessCommandRunner(),
        new JnaWindowsDataProtector());
  }

  static CredentialStore create(
      String osName, Path credentialDirectory, CredentialCommandRunner runner) {
    return create(osName, credentialDirectory, runner, new JnaWindowsDataProtector());
  }

  static CredentialStore create(
      String osName,
      Path credentialDirectory,
      CredentialCommandRunner runner,
      WindowsDataProtector windowsDataProtector) {
    String os = normalize(osName);
    if (os.contains("mac")) {
      return new MacKeychainStore(runner);
    }
    if (os.contains("windows")) {
      if (credentialDirectory == null) {
        return new UnavailableStore("session-only");
      }
      return new WindowsDpapiStore(credentialDirectory, windowsDataProtector);
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
      if (kind == Kind.CHATGPT_SESSION) {
        return "cn.lizzieyzy.next.ai-commentary.chatgpt-session";
      }
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
          kind == Kind.API_KEY || kind == Kind.CHATGPT_SESSION
              ? "--label=LizzieYzy Next AI Commentary " + kind.id()
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

  private static final class WindowsDpapiStore implements CredentialStore {
    private final Path directory;
    private final WindowsDataProtector protector;
    private volatile Boolean available;

    WindowsDpapiStore(Path directory, WindowsDataProtector protector) {
      this.directory = directory;
      this.protector = protector;
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
      byte[] probe = "lizzieyzy-next-dpapi-probe".getBytes(StandardCharsets.UTF_8);
      byte[] encrypted = null;
      byte[] decrypted = null;
      boolean detected;
      try {
        encrypted = protector.protect(probe);
        decrypted = protector.unprotect(encrypted);
        detected = MessageDigest.isEqual(probe, decrypted);
      } catch (IOException | LinkageError | RuntimeException e) {
        detected = false;
      } finally {
        clear(probe);
        clear(encrypted);
        clear(decrypted);
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
      String encoded = Files.readString(path, StandardCharsets.US_ASCII).trim();
      if (encoded.isEmpty()) {
        return Optional.empty();
      }
      byte[] encrypted = null;
      byte[] plaintext = null;
      try {
        encrypted = Base64.getDecoder().decode(encoded);
        plaintext = protector.unprotect(encrypted);
        return nonEmptySecret(new String(plaintext, StandardCharsets.UTF_8));
      } catch (RuntimeException e) {
        throw CommandCredentialStore.failure("read");
      } finally {
        clear(encrypted);
        clear(plaintext);
      }
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      if (!isAvailable() || secret == null || secret.isEmpty()) {
        throw CommandCredentialStore.failure("write");
      }
      byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
      byte[] encrypted = null;
      byte[] verification = null;
      String encoded;
      try {
        encrypted = protector.protect(plaintext);
        verification = protector.unprotect(encrypted);
        if (!MessageDigest.isEqual(plaintext, verification)) {
          throw CommandCredentialStore.failure("verify");
        }
        encoded = Base64.getEncoder().encodeToString(encrypted);
      } catch (RuntimeException e) {
        throw CommandCredentialStore.failure("write");
      } finally {
        clear(plaintext);
        clear(encrypted);
        clear(verification);
      }
      Files.createDirectories(directory);
      Path target = credentialPath(kind, account);
      Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
      try {
        Files.writeString(temporary, encoded, StandardCharsets.US_ASCII);
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
      return directory.resolve(
          kind.id() + "-" + accountDigest(CommandCredentialStore.account(account)) + ".dpapi");
    }

    private static void clear(byte[] bytes) {
      if (bytes != null) {
        Arrays.fill(bytes, (byte) 0);
      }
    }
  }

  interface WindowsDataProtector {
    byte[] protect(byte[] plaintext) throws IOException;

    byte[] unprotect(byte[] encrypted) throws IOException;
  }

  private static final class JnaWindowsDataProtector implements WindowsDataProtector {
    @Override
    public byte[] protect(byte[] plaintext) throws IOException {
      return callDpapi(
          () -> Crypt32Util.cryptProtectData(plaintext, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN));
    }

    @Override
    public byte[] unprotect(byte[] encrypted) throws IOException {
      return callDpapi(
          () -> Crypt32Util.cryptUnprotectData(encrypted, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN));
    }

    private static byte[] callDpapi(DpapiCall call) throws IOException {
      try {
        byte[] result = call.run();
        if (result == null || result.length == 0) {
          throw new IOException("Windows DPAPI returned no data.");
        }
        return result;
      } catch (LinkageError | RuntimeException e) {
        throw new IOException("Windows DPAPI is unavailable.", e);
      }
    }
  }

  @FunctionalInterface
  private interface DpapiCall {
    byte[] run();
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
