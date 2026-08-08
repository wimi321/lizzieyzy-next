package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Moves secrets into the system store only after a verified write, while preserving old data. */
final class MigratingCredentialStore implements CredentialStore {
  private final CredentialStore primary;
  private final List<CredentialStore> legacyStores;

  MigratingCredentialStore(CredentialStore primary, List<CredentialStore> legacyStores) {
    this.primary = primary;
    this.legacyStores =
        legacyStores == null ? List.of() : List.copyOf(new ArrayList<>(legacyStores));
  }

  @Override
  public String backendName() {
    return primary.backendName();
  }

  @Override
  public boolean isAvailable() {
    if (primary.isAvailable()) {
      return true;
    }
    for (CredentialStore legacy : legacyStores) {
      if (legacy.isAvailable()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Optional<String> read(Kind kind, String account) throws IOException {
    IOException primaryFailure = null;
    if (primary.isAvailable()) {
      try {
        Optional<String> stored = primary.read(kind, account);
        if (stored.isPresent()) {
          return stored;
        }
      } catch (IOException e) {
        primaryFailure = e;
      }
    }

    for (CredentialStore legacy : legacyStores) {
      if (!legacy.isAvailable()) {
        continue;
      }
      Optional<String> existing;
      try {
        existing = legacy.read(kind, account);
      } catch (IOException ignored) {
        continue;
      }
      if (existing.isEmpty()) {
        continue;
      }
      String secret = existing.get();
      if (primary.isAvailable()) {
        try {
          writeAndVerifyPrimary(kind, account, secret);
          deleteLegacyAfterVerifiedWrite(kind, account);
        } catch (IOException ignored) {
          // The DPAPI-protected legacy copy remains usable and will be retried on the next load.
        }
      }
      return Optional.of(secret);
    }
    if (primaryFailure != null) {
      throw primaryFailure;
    }
    return Optional.empty();
  }

  @Override
  public void write(Kind kind, String account, String secret) throws IOException {
    if (!primary.isAvailable()) {
      throw new IOException("System credential storage is unavailable.");
    }
    writeAndVerifyPrimary(kind, account, secret);
    deleteLegacyAfterVerifiedWrite(kind, account);
  }

  @Override
  public void delete(Kind kind, String account) throws IOException {
    IOException failure = null;
    try {
      primary.delete(kind, account);
    } catch (IOException e) {
      failure = e;
    }
    for (CredentialStore legacy : legacyStores) {
      try {
        legacy.delete(kind, account);
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void writeAndVerifyPrimary(Kind kind, String account, String secret) throws IOException {
    primary.write(kind, account, secret);
    Optional<String> verified = primary.read(kind, account);
    if (verified.isEmpty() || !sameSecret(secret, verified.get())) {
      throw new IOException("System credential storage verification failed.");
    }
  }

  private void deleteLegacyAfterVerifiedWrite(Kind kind, String account) {
    for (CredentialStore legacy : legacyStores) {
      try {
        legacy.delete(kind, account);
      } catch (IOException ignored) {
        // A verified system copy already exists; a later load will retry legacy cleanup.
      }
    }
  }

  private static boolean sameSecret(String expected, String actual) {
    byte[] left = (expected == null ? "" : expected).getBytes(StandardCharsets.UTF_8);
    byte[] right = (actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(left, right);
  }
}
