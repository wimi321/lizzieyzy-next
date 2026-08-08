package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MigratingCredentialStoreTest {
  @Test
  void legacySecretIsDeletedOnlyAfterPrimaryReadBackMatches() throws Exception {
    MemoryStore primary = new MemoryStore("windows-dpapi");
    MemoryStore legacy = new MemoryStore("windows-dpapi");
    legacy.write(CredentialStore.Kind.PASSWORD, "user", "old-secret");
    CredentialStore migrating = new MigratingCredentialStore(primary, List.of(legacy));

    assertEquals(
        "old-secret",
        migrating.read(CredentialStore.Kind.PASSWORD, "user").orElseThrow());
    assertEquals(
        "old-secret", primary.read(CredentialStore.Kind.PASSWORD, "user").orElseThrow());
    assertTrue(legacy.read(CredentialStore.Kind.PASSWORD, "user").isEmpty());
  }

  @Test
  void failedPrimaryVerificationKeepsLegacySecretAvailable() throws Exception {
    MemoryStore primary = new MemoryStore("windows-dpapi");
    primary.readOverride = "different-secret";
    MemoryStore legacy = new MemoryStore("windows-dpapi");
    legacy.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user", "old-token");
    CredentialStore migrating = new MigratingCredentialStore(primary, List.of(legacy));

    assertEquals(
        "old-token",
        migrating.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user").orElseThrow());
    assertEquals(
        "old-token", legacy.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user").orElseThrow());
  }

  @Test
  void explicitWriteDoesNotDeleteLegacyWhenPrimaryCannotBeVerified() throws Exception {
    MemoryStore primary = new MemoryStore("windows-dpapi");
    primary.readOverride = "corrupt";
    MemoryStore legacy = new MemoryStore("windows-dpapi");
    legacy.write(CredentialStore.Kind.PASSWORD, "user", "legacy-password");
    CredentialStore migrating = new MigratingCredentialStore(primary, List.of(legacy));

    assertThrows(
        IOException.class,
        () -> migrating.write(CredentialStore.Kind.PASSWORD, "user", "new-password"));
    assertEquals(
        "legacy-password", legacy.read(CredentialStore.Kind.PASSWORD, "user").orElseThrow());
  }

  @Test
  void deleteClearsPrimaryAndEveryLegacyCopy() throws Exception {
    MemoryStore primary = new MemoryStore("windows-dpapi");
    MemoryStore firstLegacy = new MemoryStore("windows-dpapi");
    MemoryStore secondLegacy = new MemoryStore("windows-dpapi");
    for (MemoryStore store : List.of(primary, firstLegacy, secondLegacy)) {
      store.write(CredentialStore.Kind.ACCOUNT_TOKEN, "user", "token");
    }
    CredentialStore migrating =
        new MigratingCredentialStore(primary, List.of(firstLegacy, secondLegacy));

    migrating.delete(CredentialStore.Kind.ACCOUNT_TOKEN, "user");

    assertFalse(primary.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user").isPresent());
    assertFalse(firstLegacy.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user").isPresent());
    assertFalse(secondLegacy.read(CredentialStore.Kind.ACCOUNT_TOKEN, "user").isPresent());
  }

  private static final class MemoryStore implements CredentialStore {
    private final String backend;
    private final Map<Kind, Map<String, String>> secrets = new EnumMap<>(Kind.class);
    private boolean available = true;
    private String readOverride;

    MemoryStore(String backend) {
      this.backend = backend;
    }

    @Override
    public String backendName() {
      return backend;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public Optional<String> read(Kind kind, String account) {
      String stored = secrets.getOrDefault(kind, Map.of()).get(account);
      if (readOverride != null && stored != null) {
        return Optional.of(readOverride);
      }
      return Optional.ofNullable(stored);
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      if (!available) {
        throw new IOException("unavailable");
      }
      secrets.computeIfAbsent(kind, ignored -> new java.util.HashMap<>()).put(account, secret);
    }

    @Override
    public void delete(Kind kind, String account) {
      Map<String, String> values = secrets.get(kind);
      if (values != null) {
        values.remove(account);
      }
    }
  }
}
