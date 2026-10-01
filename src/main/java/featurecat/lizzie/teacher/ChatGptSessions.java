package featurecat.lizzie.teacher;

import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.analysis.remote.PlatformCredentialStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** User-scoped registrations and native secrets. All disk/network work must run off the EDT. */
final class ChatGptSessions {
  final ChatGptHttp http;
  private final Path directory;
  private final CredentialStore store;
  private final Map<String, JSONObject> sessionOnly = new HashMap<>();
  private final AtomicLong generation = new AtomicLong();
  private final java.util.Set<TeacherLlmClient.Cancellation> requests =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private JSONObject state;
  private ChatGptSignIn pending;

  static ChatGptSessions createDefault() {
    return DefaultHolder.INSTANCE;
  }

  private static final class DefaultHolder {
    private static final ChatGptSessions INSTANCE = createUserSessions();
  }

  private static ChatGptSessions createUserSessions() {
    Path directory = defaultDirectory();
    return new ChatGptSessions(
        directory,
        PlatformCredentialStore.create(directory.resolve("credentials")),
        new ChatGptHttp());
  }

  static Path defaultDirectory() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    Path home = Path.of(System.getProperty("user.home"));
    if (os.contains("windows")) {
      String appData = System.getenv("APPDATA");
      return (appData == null || appData.isBlank()
              ? home.resolve("AppData/Roaming")
              : Path.of(appData))
          .resolve("LizzieYzy Next/chatgpt");
    }
    if (os.contains("mac"))
      return home.resolve("Library/Application Support/LizzieYzy Next/chatgpt");
    String config = System.getenv("XDG_CONFIG_HOME");
    return (config == null || config.isBlank() ? home.resolve(".config") : Path.of(config))
        .resolve("lizzieyzy-next/chatgpt");
  }

  ChatGptSessions(Path directory, CredentialStore store, ChatGptHttp http) {
    this.directory = directory;
    this.store = store;
    this.http = http;
  }

  synchronized ChatGptSignIn signIn(String profile) throws IOException {
    if (pending != null && !pending.result.isDone()) return pending;
    pending = new ChatGptSignIn(this, profile, Duration.ofMinutes(5));
    return pending;
  }

  String hostId() throws IOException {
    return locked(() -> state.getString("host"));
  }

  long authorizationRevision() throws IOException {
    return locked(() -> state.optLong("authorizationRevision", 0));
  }

  String registrationClient() throws IOException {
    return locked(() -> state.optString("pendingRegistration", "dynamic_agent_client"));
  }

  void retainRegistration(String client) throws IOException {
    locked(
        () -> {
          state.put("pendingRegistration", client);
          persist();
          return null;
        });
  }

  String clientId(String id) throws IOException {
    return locked(() -> entry(id).getString("client"));
  }

  String subject(String id) throws IOException {
    return locked(() -> entry(id).getString("subject"));
  }

  boolean planAuthorized(String id) throws IOException {
    return locked(() -> entry(id).optBoolean("authorized"));
  }

  ChatGptIdentity verify(String token, String client, String nonce, String subject)
      throws IOException, InterruptedException {
    JSONObject keys = http.get(http.auth.resolve("/.well-known/jwks.json"), null);
    return ChatGptIdentity.verify(
        token, keys.toString(), http.auth.toString(), client, nonce, subject, Instant.now());
  }

  Account accept(
      String priorId,
      String client,
      ChatGptIdentity identity,
      JSONObject tokens,
      long authorizationRevision)
      throws IOException {
    return locked(
        () -> {
          // A logout in any app instance invalidates callbacks from earlier authorizations.
          if (state.optLong("authorizationRevision", 0) != authorizationRevision)
            throw ChatGptHttp.error("loginRequired");
          String id = priorId;
          if (id == null) {
            id = UUID.randomUUID().toString();
            for (String candidate : accounts().keySet()) {
              JSONObject old = entry(candidate);
              if (client.equals(old.getString("client"))
                  && identity.subject.equals(old.getString("subject"))) {
                id = candidate;
                break;
              }
            }
          } else if (!client.equals(entry(id).getString("client"))
              || !identity.subject.equals(entry(id).getString("subject"))) {
            throw ChatGptHttp.error("identity");
          }
          JSONObject record = accounts().optJSONObject(id);
          if (record == null) record = new JSONObject().put("model", "").put("welcomed", false);
          record
              .put("client", client)
              .put("subject", identity.subject)
              .put("email", identity.email)
              .put("signedOut", false);
          accounts().put(id, record);
          saveTokens(id, normalizedTokens(tokens, null));
          if (client.equals(state.optString("pendingRegistration")))
            state.remove("pendingRegistration");
          state.put("active", id);
          persist();
          invalidateRequests();
          return account(id);
        });
  }

  List<Account> list() throws IOException {
    return locked(
        () -> {
          List<Account> result = new ArrayList<>();
          for (String id : accounts().keySet()) result.add(account(id));
          result.sort(java.util.Comparator.comparing(a -> a.id));
          return List.copyOf(result);
        });
  }

  Account active() throws IOException {
    return locked(
        () -> {
          String id = state.optString("active");
          return accounts().has(id) ? account(id) : null;
        });
  }

  void select(String id) throws IOException {
    locked(
        () -> {
          entry(id);
          state.put("active", id);
          persist();
          invalidateRequests();
          return null;
        });
  }

  void model(String id, String model) throws IOException {
    model(id, model, "");
  }

  String reasoningEffort(String id, String model) throws IOException {
    return locked(
        () -> {
          JSONObject values = entry(id).optJSONObject("reasoningByModel");
          return values == null ? "" : values.optString(model);
        });
  }

  void model(String id, String model, String effort) throws IOException {
    if (model == null
        || model.isBlank()
        || effort == null
        || (!effort.isEmpty() && !ChatGptCommentaryClient.validEffort(effort)))
      throw ChatGptHttp.error("reasoning");
    locked(
        () -> {
          JSONObject record = entry(id);
          record.put("model", model);
          JSONObject values = record.optJSONObject("reasoningByModel");
          if (values == null) values = new JSONObject();
          values.put(model, effort);
          record.put("reasoningByModel", values);
          persist();
          invalidateRequests();
          return null;
        });
  }

  void welcomed(String id) throws IOException {
    locked(
        () -> {
          entry(id).put("welcomed", true);
          persist();
          return null;
        });
  }

  long generation() {
    return generation.get();
  }

  void register(TeacherLlmClient.Cancellation request) {
    requests.add(request);
  }

  void unregister(TeacherLlmClient.Cancellation request) {
    requests.remove(request);
  }

  private void invalidateRequests() {
    generation.incrementAndGet();
    for (TeacherLlmClient.Cancellation request : requests) request.cancel();
  }

  String accessToken(String id) throws IOException {
    return locked(
        () -> {
          JSONObject record = entry(id);
          JSONObject tokens = readTokens(id);
          if (!authorized(tokens)) throw ChatGptHttp.error("permission");
          if (tokens.optLong("expires_at") <= Instant.now().getEpochSecond() + 60) {
            String refresh = tokens.optString("refresh_token");
            if (refresh.isBlank()) throw ChatGptHttp.error("loginRequired");
            try {
              JSONObject replacement =
                  http.token(
                      Map.of(
                          "grant_type",
                          "refresh_token",
                          "client_id",
                          record.getString("client"),
                          "refresh_token",
                          refresh,
                          "resource",
                          ChatGptHttp.RESOURCE));
              if (replacement.has("id_token")) {
                verify(
                    replacement.getString("id_token"),
                    record.getString("client"),
                    null,
                    record.getString("subject"));
              }
              tokens = normalizedTokens(replacement, tokens);
              saveTokens(id, tokens);
              persist();
            } catch (ChatGptApiException rejected) {
              if (rejected.refreshRevoked()) {
                record.put("signedOut", true);
                sessionOnly.remove(id);
                persist();
                try {
                  store.delete(CredentialStore.Kind.CHATGPT_SESSION, key(id));
                } catch (IOException unavailable) {
                  /* Tombstone prevents reusing a revoked grant. */
                }
                invalidateRequests();
              }
              throw rejected;
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw ChatGptHttp.error("network");
            }
          }
          if (!authorized(tokens)) throw ChatGptHttp.error("permission");
          return tokens.getString("access_token");
        });
  }

  boolean signOut(String id) throws IOException {
    invalidateRequests();
    return locked(
        () -> {
          JSONObject tokens;
          boolean readable = true;
          try {
            tokens = readTokens(id);
          } catch (IOException absent) {
            tokens = new JSONObject();
            readable = false;
          }
          boolean revoked = readable && tokens.optString("refresh_token").isEmpty();
          if (!tokens.optString("refresh_token").isEmpty()) {
            for (int attempt = 0; attempt < 2 && !revoked; attempt++) {
              try {
                if (attempt > 0) Thread.sleep(500);
                revoked =
                    http.revoke(entry(id).getString("client"), tokens.getString("refresh_token"));
              } catch (IOException | RuntimeException failure) {
                // Local sign-out still succeeds; UI distinguishes unconfirmed remote revocation.
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
              }
            }
          }
          boolean interrupted = Thread.interrupted();
          try {
            state.put("authorizationRevision", state.optLong("authorizationRevision", 0) + 1);
            entry(id).put("signedOut", true);
            sessionOnly.remove(id);
            persist(); // Tombstone first: an unavailable keychain must not resurrect this login.
            try {
              store.delete(CredentialStore.Kind.CHATGPT_SESSION, key(id));
            } catch (IOException unavailable) {
              revoked = false;
            }
          } finally {
            if (interrupted) Thread.currentThread().interrupt();
          }
          return revoked;
        });
  }

  private JSONObject normalizedTokens(JSONObject reply, JSONObject previous) throws IOException {
    String access = reply.optString("access_token");
    if (previous == null
        && access.isBlank()
        && !authorized(reply)
        && !reply.optString("id_token").isBlank()) {
      // A verified identity is not permission to spend plan usage.
      return new JSONObject()
          .put("id_token", reply.getString("id_token"))
          .put("scope", reply.optString("scope"))
          .put("access_token", "")
          .put("expires_at", 0);
    }
    long seconds = reply.optLong("expires_in", 0);
    if (access.isBlank()
        || access.chars().anyMatch(Character::isISOControl)
        || !"Bearer".equalsIgnoreCase(reply.optString("token_type"))
        || seconds <= 0
        || seconds > 86400 * 30L) throw ChatGptHttp.error("protocol");
    JSONObject result =
        new JSONObject()
            .put("access_token", access)
            .put("expires_at", Instant.now().getEpochSecond() + seconds)
            .put(
                "scope",
                reply.optString("scope", previous == null ? "" : previous.optString("scope")));
    for (String field : List.of("refresh_token", "id_token")) {
      result.put(field, reply.optString(field, previous == null ? "" : previous.optString(field)));
    }
    return result;
  }

  private void saveTokens(String id, JSONObject tokens) throws IOException {
    boolean saved = false;
    if (store.isAvailable()) {
      try {
        store.write(CredentialStore.Kind.CHATGPT_SESSION, key(id), tokens.toString());
        saved =
            store
                .read(CredentialStore.Kind.CHATGPT_SESSION, key(id))
                .filter(tokens.toString()::equals)
                .isPresent();
      } catch (IOException unavailable) {
        /* Session-only, never plaintext fallback. */
      }
    }
    entry(id).put("sessionOnly", !saved).put("authorized", authorized(tokens));
    if (!saved) sessionOnly.put(id, tokens);
    else sessionOnly.remove(id);
  }

  private JSONObject readTokens(String id) throws IOException {
    JSONObject record = entry(id);
    if (record.optBoolean("signedOut", true)) throw ChatGptHttp.error("loginRequired");
    if (record.optBoolean("sessionOnly")) {
      JSONObject tokens = sessionOnly.get(id);
      if (tokens == null) throw ChatGptHttp.error("loginRequired");
      return tokens;
    }
    java.util.Optional<String> stored;
    try {
      stored = store.read(CredentialStore.Kind.CHATGPT_SESSION, key(id));
    } catch (IOException unavailable) {
      // A locked or temporarily inaccessible vault is not a revoked or missing login.
      throw new CredentialsUnavailable();
    }
    try {
      return new JSONObject(stored.orElseThrow(() -> ChatGptHttp.error("loginRequired")));
    } catch (RuntimeException malformed) {
      throw ChatGptHttp.error("loginRequired");
    }
  }

  private String key(String id) {
    return "chatgpt:" + state.getString("host") + ":" + id;
  }

  private JSONObject accounts() {
    return state.getJSONObject("accounts");
  }

  private JSONObject entry(String id) throws IOException {
    JSONObject record = id == null ? null : accounts().optJSONObject(id);
    if (record == null) throw ChatGptHttp.error("loginRequired");
    return record;
  }

  private Account account(String id) throws IOException {
    JSONObject record = entry(id);
    boolean signedIn;
    boolean credentialsUnavailable = false;
    try {
      JSONObject tokens = readTokens(id);
      signedIn =
          !tokens.optString("access_token").isBlank() || !tokens.optString("id_token").isBlank();
    } catch (CredentialsUnavailable unavailable) {
      signedIn = false;
      credentialsUnavailable = true;
    } catch (IOException absent) {
      signedIn = false;
    }
    Account result =
        new Account(
            id,
            record.getString("email"),
            record.optString("model"),
            signedIn,
            record.optBoolean("authorized"),
            record.optBoolean("sessionOnly"),
            record.optBoolean("welcomed"),
            credentialsUnavailable);
    JSONObject efforts = record.optJSONObject("reasoningByModel");
    if (efforts != null)
      for (String key : efforts.keySet()) {
        String effort = efforts.optString(key);
        if (ChatGptCommentaryClient.validEffort(effort)) result.reasoningByModel.put(key, effort);
      }
    return result;
  }

  private static boolean authorized(JSONObject tokens) {
    return List.of(tokens.optString("scope").split(" +")).contains("chatgpt.tokens.use.direct");
  }

  private synchronized <T> T locked(Operation<T> operation) throws IOException {
    Files.createDirectories(directory);
    restrict(directory, true);
    Path lockFile = directory.resolve("session.lock");
    try (FileChannel channel =
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      restrict(lockFile, false);
      long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
      FileLock lock = null;
      while (lock == null) {
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException busy) {
          /* Other instance. */
        }
        if (lock == null) {
          if (System.nanoTime() >= deadline) throw ChatGptHttp.error("busy");
          try {
            Thread.sleep(50);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
          }
        }
      }
      try (FileLock held = lock) {
        Path file = directory.resolve("accounts.json");
        if (Files.exists(file)) {
          try {
            state = new JSONObject(Files.readString(file));
          } catch (RuntimeException invalid) {
            throw ChatGptHttp.error("storage");
          }
          if (!state.has("host") || state.optJSONObject("accounts") == null)
            throw ChatGptHttp.error("storage");
        } else {
          state =
              new JSONObject()
                  .put("host", "urn:uuid:" + UUID.randomUUID())
                  .put("accounts", new JSONObject());
          persist();
        }
        return operation.run();
      }
    }
  }

  private void persist() throws IOException {
    Path temporary = Files.createTempFile(directory, "accounts-", ".tmp");
    try {
      restrict(temporary, false);
      Files.writeString(temporary, state.toString(2));
      try {
        Files.move(
            temporary,
            directory.resolve("accounts.json"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
        Files.move(
            temporary, directory.resolve("accounts.json"), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void restrict(Path path, boolean directory) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(
          path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
  }

  @FunctionalInterface
  private interface Operation<T> {
    T run() throws IOException;
  }

  static final class Account {
    final String id;
    final String email;
    final String model;
    final boolean signedIn;
    final boolean authorized;
    final boolean sessionOnly;
    final boolean welcomed;
    final boolean credentialsUnavailable;
    final java.util.Map<String, String> reasoningByModel = new java.util.HashMap<>();

    Account(
        String id,
        String email,
        String model,
        boolean signedIn,
        boolean authorized,
        boolean sessionOnly,
        boolean welcomed,
        boolean credentialsUnavailable) {
      this.id = id;
      this.email = email;
      this.model = model;
      this.signedIn = signedIn;
      this.authorized = authorized;
      this.sessionOnly = sessionOnly;
      this.welcomed = welcomed;
      this.credentialsUnavailable = credentialsUnavailable;
    }

    @Override
    public String toString() {
      return email + " (" + id.substring(0, 8) + ")";
    }
  }

  static final class CredentialsUnavailable extends IOException {
    CredentialsUnavailable() {
      super(ChatGptHttp.error("credentialsUnavailable").getMessage());
    }
  }
}
