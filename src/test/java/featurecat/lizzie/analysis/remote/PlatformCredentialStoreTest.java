package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class PlatformCredentialStoreTest {
  @Test
  @EnabledOnOs(OS.MAC)
  @EnabledIfSystemProperty(named = "lizzie.test.nativeKeychain", matches = "true")
  void macKeychainPreservesLongTokensAndSeparatesServices() throws Exception {
    RecordingRunner runner = new RecordingRunner();
    CredentialStore store = PlatformCredentialStore.create("Mac OS X", Path.of("unused"), runner);
    String account = "keychain-regression-" + java.util.UUID.randomUUID();
    assertTrue(store.isAvailable());
    try {
      assertTrue(store.read(CredentialStore.Kind.CHATGPT_SESSION, account).isEmpty());
      store.write(CredentialStore.Kind.API_KEY, account, "independent-api-key");
      for (int size : new int[] {32, 128, 129, 1024, 4096, 12000, 65536}) {
        String secret = "x".repeat(size) + "\u4e2d\u6587\n trailing space ";
        store.write(CredentialStore.Kind.CHATGPT_SESSION, account, secret);
        CredentialStore reader = PlatformCredentialStore.create(Path.of("unused"));
        assertEquals(
            secret, reader.read(CredentialStore.Kind.CHATGPT_SESSION, account).orElseThrow());
      }
      assertEquals(
          "independent-api-key", store.read(CredentialStore.Kind.API_KEY, account).orElseThrow());
      assertTrue(runner.commands.isEmpty());
      assertTrue(runner.inputs.isEmpty());
    } finally {
      store.delete(CredentialStore.Kind.CHATGPT_SESSION, account);
      store.delete(CredentialStore.Kind.API_KEY, account);
    }
    store.delete(CredentialStore.Kind.CHATGPT_SESSION, account);
    assertTrue(store.read(CredentialStore.Kind.CHATGPT_SESSION, account).isEmpty());
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @EnabledIfSystemProperty(named = "lizzie.test.legacyKeychain", matches = "true")
  void macKeychainReadsAndUpdatesLegacyCommandLineEntries() throws Exception {
    String account = "legacy-keychain-regression-" + java.util.UUID.randomUUID();
    CredentialStore store = PlatformCredentialStore.create(Path.of("unused"));
    try {
      Process legacy =
          new ProcessBuilder(
                  "/usr/bin/security",
                  "add-generic-password",
                  "-a",
                  account,
                  "-s",
                  "cn.lizzieyzy.next.ai-commentary.api-key",
                  "-w")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        try (var input = legacy.getOutputStream()) {
          input.write("legacy-canary\nlegacy-canary\n".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(legacy.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, legacy.exitValue());
      } finally {
        if (legacy.isAlive()) legacy.destroyForcibly();
      }
      assertEquals(
          "legacy-canary", store.read(CredentialStore.Kind.API_KEY, account).orElseThrow());
      store.write(CredentialStore.Kind.API_KEY, account, "updated".repeat(1000));
      assertEquals(
          "updated".repeat(1000), store.read(CredentialStore.Kind.API_KEY, account).orElseThrow());
    } finally {
      store.delete(CredentialStore.Kind.API_KEY, account);
    }
  }

  @Test
  @EnabledOnOs(OS.MAC)
  @EnabledIfSystemProperty(named = "lizzie.test.nativeKeychain", matches = "true")
  void chatGptBackgroundReadRefusesForeignCredentialsWithoutPrompting() throws Exception {
    String account = "quiet-keychain-regression-" + java.util.UUID.randomUUID();
    String service = "cn.lizzieyzy.next.ai-commentary.chatgpt-session";
    CredentialStore store = PlatformCredentialStore.create(Path.of("unused"));
    var security = com.sun.jna.Native.load("Security", MacKeychainNative.Security.class);
    byte[] before = new byte[1];
    assertEquals(0, security.SecKeychainGetUserInteractionAllowed(before));
    try {
      // Only the creator may read this synthetic entry. Our JVM must never ask for access.
      Process creator =
          new ProcessBuilder(
                  "/usr/bin/security",
                  "add-generic-password",
                  "-a",
                  account,
                  "-s",
                  service,
                  "-T",
                  "/usr/bin/security",
                  "-w")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        try (var input = creator.getOutputStream()) {
          input.write("quiet-canary\nquiet-canary\n".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(creator.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, creator.exitValue());
      } finally {
        if (creator.isAlive()) creator.destroyForcibly();
      }
      org.junit.jupiter.api.Assertions.assertTimeout(
          Duration.ofSeconds(5),
          () -> {
            for (int attempt = 0; attempt < 3; attempt++) {
              IOException denied =
                  assertThrows(
                      IOException.class,
                      () -> store.read(CredentialStore.Kind.CHATGPT_SESSION, account));
              assertFalse(denied.toString().contains("quiet-canary"));
            }
          });
      byte[] after = new byte[1];
      assertEquals(0, security.SecKeychainGetUserInteractionAllowed(after));
      assertEquals(before[0], after[0]);
    } finally {
      Process cleanup =
          new ProcessBuilder(
                  "/usr/bin/security", "delete-generic-password", "-a", account, "-s", service)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        assertTrue(cleanup.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, cleanup.exitValue());
      } finally {
        if (cleanup.isAlive()) cleanup.destroyForcibly();
      }
    }
  }

  @Test
  void linuxSecretServiceReceivesSecretsOnlyThroughStandardInput() throws Exception {
    RecordingRunner runner = new RecordingRunner();
    runner.readOutput = "linux-secret\n";
    CredentialStore store = PlatformCredentialStore.create("Linux", Path.of("unused"), runner);

    store.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com", "never-in-argv");
    assertEquals(
        "linux-secret",
        store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com").orElseThrow());

    assertFalse(runner.flattenedCommands().contains("never-in-argv"));
    assertTrue(runner.inputs.contains("never-in-argv" + System.lineSeparator()));
    assertTrue(runner.flattenedCommands().contains("secret-tool store"));
    assertTrue(runner.flattenedCommands().contains("secret-tool lookup"));
  }

  @Test
  void windowsDpapiPersistsOnlyEncryptedUserScopedBlob() throws Exception {
    Path directory = Files.createTempDirectory("dpapi-store");
    RecordingRunner runner = new RecordingRunner();
    FakeWindowsDataProtector protector = new FakeWindowsDataProtector();
    protector.protectedOutput = "encrypted-dpapi-blob".getBytes(StandardCharsets.UTF_8);
    CredentialStore store =
        PlatformCredentialStore.create("Windows 11", directory, runner, protector);

    store.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com", "never-in-file");

    List<Path> files;
    try (var paths = Files.list(directory)) {
      files = paths.toList();
    }
    assertEquals(1, files.size());
    assertEquals(
        Base64.getEncoder().encodeToString(protector.protectedOutput),
        Files.readString(files.get(0)));
    assertFalse(files.get(0).getFileName().toString().contains("user@example.com"));
    assertEquals(
        "never-in-file",
        store.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com").orElseThrow());
    assertTrue(runner.commands.isEmpty());
    assertTrue(protector.protectCalls >= 2);
    assertTrue(protector.unprotectCalls >= 3);
  }

  @Test
  void windowsDpapiRejectsCredentialThatCannotBeReadBack() throws Exception {
    Path directory = Files.createTempDirectory("dpapi-store-verification");
    RecordingRunner runner = new RecordingRunner();
    FakeWindowsDataProtector protector = new FakeWindowsDataProtector();
    protector.corruptAfterProbe = true;
    CredentialStore store =
        PlatformCredentialStore.create("Windows 11", directory, runner, protector);

    assertThrows(
        IOException.class,
        () -> store.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user@example.com", "token"));
    try (var files = Files.list(directory)) {
      assertEquals(0L, files.count());
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsDpapiRoundTripsAcrossRealNativeCalls() throws Exception {
    Path directory = Files.createTempDirectory("dpapi-store-real");
    String account = "dpapi-regression-test";
    String secret = "round-trip-secret-\u4e2d\u6587";

    CredentialStore writer = PlatformCredentialStore.create(directory);
    assertTrue(writer.isAvailable());
    writer.write(CredentialStore.Kind.ACCOUNT_TOKEN, account, secret);

    CredentialStore reader = PlatformCredentialStore.create(directory);
    assertTrue(reader.isAvailable());
    assertEquals(secret, reader.read(CredentialStore.Kind.ACCOUNT_TOKEN, account).orElseThrow());

    reader.delete(CredentialStore.Kind.ACCOUNT_TOKEN, account);
    assertTrue(reader.read(CredentialStore.Kind.ACCOUNT_TOKEN, account).isEmpty());
  }

  @Test
  void unsupportedPlatformUsesSessionOnlyBackend() {
    CredentialStore store =
        PlatformCredentialStore.create("Plan 9", Path.of("unused"), new RecordingRunner());

    assertEquals("session-only", store.backendName());
    assertFalse(store.isAvailable());
  }

  private static final class RecordingRunner
      implements PlatformCredentialStore.CredentialCommandRunner {
    final List<List<String>> commands = new ArrayList<>();
    final List<String> inputs = new ArrayList<>();
    String readOutput = "";

    @Override
    public PlatformCredentialStore.CommandResult run(
        List<String> command, String input, Duration timeout) throws IOException {
      commands.add(List.copyOf(command));
      inputs.add(input == null ? "" : input);
      String flattened = String.join(" ", command);
      if (flattened.contains("find-generic-password") || flattened.contains("secret-tool lookup")) {
        return new PlatformCredentialStore.CommandResult(0, readOutput);
      }
      return new PlatformCredentialStore.CommandResult(0, "");
    }

    String flattenedCommands() {
      StringBuilder out = new StringBuilder();
      for (List<String> command : commands) {
        if (out.length() > 0) {
          out.append('\n');
        }
        out.append(String.join(" ", command));
      }
      return out.toString();
    }
  }

  private static final class FakeWindowsDataProtector
      implements PlatformCredentialStore.WindowsDataProtector {
    byte[] protectedOutput = "encrypted".getBytes(StandardCharsets.UTF_8);
    byte[] lastPlaintext = new byte[0];
    int protectCalls;
    int unprotectCalls;
    boolean corruptAfterProbe;

    @Override
    public byte[] protect(byte[] plaintext) {
      protectCalls++;
      lastPlaintext = Arrays.copyOf(plaintext, plaintext.length);
      return Arrays.copyOf(protectedOutput, protectedOutput.length);
    }

    @Override
    public byte[] unprotect(byte[] encrypted) {
      unprotectCalls++;
      if (corruptAfterProbe && unprotectCalls > 1) {
        return "different-secret".getBytes(StandardCharsets.UTF_8);
      }
      return Arrays.copyOf(lastPlaintext, lastPlaintext.length);
    }
  }
}
