package featurecat.lizzie.analysis.remote;

import java.io.IOException;
import java.util.Optional;

/** Stores application secrets outside the ordinary application configuration. */
public interface CredentialStore {
  enum Kind {
    ACCOUNT_TOKEN("account-token"),
    PASSWORD("password"),
    API_KEY("api-key"),
    CHATGPT_SESSION("chatgpt-session");

    private final String id;

    Kind(String id) {
      this.id = id;
    }

    String id() {
      return id;
    }
  }

  String backendName();

  boolean isAvailable();

  Optional<String> read(Kind kind, String account) throws IOException;

  void write(Kind kind, String account, String secret) throws IOException;

  void delete(Kind kind, String account) throws IOException;
}
