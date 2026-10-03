package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.analysis.remote.PlatformCredentialStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import javax.swing.SwingUtilities;
import org.json.JSONObject;

/** Explicit local acceptance only. Uses an isolated registration and never prints credentials. */
public final class ChatGptLiveAcceptanceCli {
  public static void main(String[] args) throws Exception {
    boolean appSession = args.length == 3 && "--app-session".equals(args[2]);
    if ((args.length != 2 && !appSession)
        || !List.of("settings", "check", "generate", "refresh-check", "logout").contains(args[0])
        || (appSession && !List.of("check", "generate", "refresh-check").contains(args[0]))) {
      throw new IllegalArgumentException(
          "Usage: settings|check|generate|refresh-check|logout isolated-directory"
              + " [--app-session (check/generate/refresh-check only)]");
    }
    Path directory = Path.of(args[1]).toAbsolutePath();
    var store =
        new ExpiryObservationStore(
            PlatformCredentialStore.create(directory.resolve("credentials")));
    var sessions =
        new ChatGptSessions(
            appSession ? directory : directory.resolve("registration"), store, new ChatGptHttp());
    var settings = new TeacherSettings(directory.resolve("teacher.properties"), store, sessions);
    Lizzie.resourceBundle =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);
    if (args[0].equals("settings")) {
      SwingUtilities.invokeAndWait(() -> TeacherSettingsDialog.show(null, settings));
    } else {
      var account = sessions.active();
      if (account == null || !account.signedIn || !account.authorized) {
        System.out.println("BLOCKED_LOGIN_REQUIRED");
        System.exit(2);
      }
      if (args[0].equals("refresh-check")) {
        // Observe natural expiry only. Never backdate or print a real credential.
        long before = store.firstExpiry;
        if (before <= 0 || before > Instant.now().getEpochSecond()) {
          System.out.println("BLOCKED_NATURAL_EXPIRY_REQUIRED");
          System.exit(3);
        }
        var models = new ChatGptCommentaryClient(sessions, account.id, account.model).models();
        if (models.isEmpty()
            || store.writes != 1
            || store.lastExpiry <= Instant.now().getEpochSecond() + 60)
          throw new IOException("Expired-session refresh did not persist a usable session");
        var reopened =
            new ChatGptSessions(
                appSession ? directory : directory.resolve("registration"),
                store,
                new ChatGptHttp());
        var restored = reopened.active();
        if (restored == null || !restored.signedIn || restored.sessionOnly)
          throw new IOException("Refreshed session did not survive reopening");
        var repeated =
            new ChatGptCommentaryClient(reopened, restored.id, restored.model).models();
        if (repeated.isEmpty() || store.writes != 1)
          throw new IOException("Reopened session unexpectedly refreshed again");
        System.out.println(
            "NATURAL_EXPIRY_REFRESH_OK persisted=true reopened=true modelCount=" + models.size());
      } else if (args[0].equals("logout")) {
        System.out.println(sessions.signOut(account.id) ? "LOGOUT_REVOKED" : "LOGOUT_LOCAL_ONLY");
      } else {
        var client = new ChatGptCommentaryClient(sessions, account.id, account.model);
        var models = client.models();
        System.out.println(
            "MODEL_CATALOG_OK count=" + models.size() + " sessionOnly=" + account.sessionOnly);
        for (var model : models) {
          System.out.println(
              "MODEL_CAPABILITY slug="
                  + model.slug
                  + " reasoning="
                  + model.reasoningEfforts
                  + " default="
                  + model.defaultReasoningEffort);
        }
        System.out.println(
            "SELECTED_MODEL="
                + account.model
                + " reasoning="
                + sessions.reasoningEffort(account.id, account.model));
        if (args[0].equals("generate")) {
          String explanation =
              client.stream(
                  List.of(
                      new TeacherLlmClient.Message(
                          "system",
                          "This is a synthetic Go commentary integration test. Explain only the supplied candidate statistics, in two short Chinese sentences. Do not invent tactical variations."),
                      new TeacherLlmClient.Message(
                          "user",
                          "Synthetic KataGo evidence, black to play: Q16 winrate 55%, scoreLead +1.2, visits 1000; D4 winrate 53%, scoreLead +0.8, visits 800. Explain that the first candidate is slightly preferred but these numbers alone do not prove a tactical result.")),
                  new TeacherLlmClient.Cancellation(),
                  ignored -> {});
          System.out.println("RESPONSES_COMPLETED characters=" + explanation.length());
        }
      }
    }
    System.exit(0);
  }

  static final class ExpiryObservationStore implements CredentialStore {
    private final CredentialStore delegate;
    long firstExpiry;
    long lastExpiry;
    int writes;

    ExpiryObservationStore(CredentialStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public String backendName() {
      return delegate.backendName();
    }

    @Override
    public boolean isAvailable() {
      return delegate.isAvailable();
    }

    @Override
    public Optional<String> read(Kind kind, String account) throws IOException {
      Optional<String> secret = delegate.read(kind, account);
      if (kind == Kind.CHATGPT_SESSION && secret.isPresent()) {
        lastExpiry = new JSONObject(secret.get()).optLong("expires_at");
        if (firstExpiry == 0) firstExpiry = lastExpiry;
      }
      return secret;
    }

    @Override
    public void write(Kind kind, String account, String secret) throws IOException {
      delegate.write(kind, account, secret);
      if (kind == Kind.CHATGPT_SESSION) writes++;
    }

    @Override
    public void delete(Kind kind, String account) throws IOException {
      delegate.delete(kind, account);
    }
  }
}
