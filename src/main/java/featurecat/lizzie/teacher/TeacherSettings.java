package featurecat.lizzie.teacher;

import featurecat.lizzie.Config;
import featurecat.lizzie.analysis.remote.CredentialStore;
import featurecat.lizzie.analysis.remote.PlatformCredentialStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

/** Persists non-secret AI commentary preferences and keeps the API key in native storage. */
public final class TeacherSettings {
  static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
  static final String DEFAULT_MODEL = "gpt-4o-mini";

  private static final String FILE_NAME = "teacher.properties";
  private static final String CREDENTIAL_PREFIX = "ai-commentary:";

  private final Path settingsFile;
  private final CredentialStore credentialStore;

  private String baseUrl = DEFAULT_BASE_URL;
  private String model = DEFAULT_MODEL;
  private boolean rememberApiKey;
  private boolean loaded;
  private char[] sessionApiKey = new char[0];

  // ---- Teaching preferences (讲解设置：等级/风格/术语密度/节奏/变化细节) ----
  /** "k"=级位（18k~1k），"d"=段位（1d~9d）。 */
  private String rankMode = "k";
  /** 数字部分：级位 1-18，段位 1-9。 */
  private int rankNum = 5;
  /** 0=平衡自然 1=严谨细致 2=亲切耐心 3=严格专业 4=风趣幽默。 */
  private int styleIndex = 0;
  /** 0=少 1=中 2=多。 */
  private int densityIndex = 1;
  /** 0=简洁 1=标准 2=细讲。 */
  private int paceIndex = 1;
  /** 0=少讲 1=适中 2=详细。 */
  private int variationIndex = 1;

  public static TeacherSettings createDefault() {
    Path workDirectory = Config.resolvedWorkDirPath();
    CredentialStore store =
        PlatformCredentialStore.create(workDirectory.resolve("secure-credentials"));
    return new TeacherSettings(workDirectory.resolve(FILE_NAME), store);
  }

  TeacherSettings(Path settingsFile, CredentialStore credentialStore) {
    this.settingsFile = settingsFile;
    this.credentialStore = credentialStore;
  }

  public synchronized Snapshot load() throws IOException {
    if (loaded) {
      return snapshot();
    }
    Properties properties = new Properties();
    if (Files.isRegularFile(settingsFile)) {
      try (InputStream input = Files.newInputStream(settingsFile)) {
        properties.load(input);
      }
    }

    baseUrl = validateBaseUrl(properties.getProperty("baseUrl", DEFAULT_BASE_URL));
    model = validateModel(properties.getProperty("model", DEFAULT_MODEL));
    rememberApiKey = Boolean.parseBoolean(properties.getProperty("rememberApiKey", "false"));
    rankMode = "d".equals(properties.getProperty("teacher.rankMode", "k")) ? "d" : "k";
    rankNum = clampInt(properties.getProperty("teacher.rankNum", "5"), 1, 18, 5);
    styleIndex = clampInt(properties.getProperty("teacher.styleIndex", "0"), 0, 4, 0);
    densityIndex = clampInt(properties.getProperty("teacher.densityIndex", "1"), 0, 2, 1);
    paceIndex = clampInt(properties.getProperty("teacher.paceIndex", "1"), 0, 2, 1);
    variationIndex = clampInt(properties.getProperty("teacher.variationIndex", "1"), 0, 2, 1);

    // Builds before this integration stored the key in plaintext. Keep it for this process only,
    // remove it from disk immediately, and let the user explicitly opt into native storage.
    String legacyApiKey = properties.getProperty("apiKey", "").trim();
    if (!legacyApiKey.isEmpty()) {
      replaceSessionApiKey(legacyApiKey.toCharArray());
      rememberApiKey = false;
      properties.remove("apiKey");
      writeProperties(sanitizedProperties());
    } else if (rememberApiKey && credentialStore.isAvailable()) {
      try {
        Optional<String> stored =
            credentialStore.read(CredentialStore.Kind.API_KEY, credentialAccount(baseUrl));
        replaceSessionApiKey(stored.orElse("").toCharArray());
      } catch (IOException e) {
        replaceSessionApiKey(new char[0]);
      }
    }
    loaded = true;
    return snapshot();
  }

  public synchronized Snapshot save(
      String requestedBaseUrl,
      String requestedModel,
      char[] requestedApiKey,
      boolean requestedRememberApiKey)
      throws IOException {
    String normalizedBaseUrl = validateBaseUrl(requestedBaseUrl);
    String normalizedModel = validateModel(requestedModel);
    char[] suppliedKey = requestedApiKey == null ? new char[0] : requestedApiKey.clone();
    String oldAccount = credentialAccount(baseUrl);
    String newAccount = credentialAccount(normalizedBaseUrl);

    try {
      if (requestedRememberApiKey) {
        if (!credentialStore.isAvailable()) {
          throw new IOException("System credential storage is unavailable.");
        }
        if (suppliedKey.length == 0) {
          throw new IOException("An API key is required before it can be remembered.");
        }
        credentialStore.write(CredentialStore.Kind.API_KEY, newAccount, new String(suppliedKey));
      } else {
        credentialStore.delete(CredentialStore.Kind.API_KEY, newAccount);
      }
      if (!oldAccount.equals(newAccount)) {
        credentialStore.delete(CredentialStore.Kind.API_KEY, oldAccount);
      }

      baseUrl = normalizedBaseUrl;
      model = normalizedModel;
      rememberApiKey = requestedRememberApiKey;
      loaded = true;
      // The password field is authoritative: clearing it must also clear the session copy.
      replaceSessionApiKey(suppliedKey);
      writeProperties(sanitizedProperties());
      return snapshot();
    } finally {
      Arrays.fill(suppliedKey, '\0');
      if (requestedApiKey != null) {
        Arrays.fill(requestedApiKey, '\0');
      }
    }
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(
        baseUrl,
        model,
        rememberApiKey,
        sessionApiKey.length > 0,
        credentialStore.isAvailable(),
        credentialStore.backendName(),
        rankMode,
        rankNum,
        styleIndex,
        densityIndex,
        paceIndex,
        variationIndex);
  }

  /** 保存讲解设置（等级/风格/术语密度/节奏/变化细节），不触碰 LLM 凭据。 */
  public synchronized void saveTeachingPreferences(
      String requestedRankMode,
      int requestedRankNum,
      int requestedStyleIndex,
      int requestedDensityIndex,
      int requestedPaceIndex,
      int requestedVariationIndex)
      throws IOException {
    rankMode = "d".equals(requestedRankMode) ? "d" : "k";
    rankNum = clampInt(String.valueOf(requestedRankNum), 1, 18, 5);
    styleIndex = clampInt(String.valueOf(requestedStyleIndex), 0, 4, 0);
    densityIndex = clampInt(String.valueOf(requestedDensityIndex), 0, 2, 1);
    paceIndex = clampInt(String.valueOf(requestedPaceIndex), 0, 2, 1);
    variationIndex = clampInt(String.valueOf(requestedVariationIndex), 0, 2, 1);
    loaded = true;
    writeProperties(sanitizedProperties());
  }

  private static int clampInt(String value, int min, int max, int fallback) {
    try {
      int parsed = Integer.parseInt(value);
      return Math.max(min, Math.min(max, parsed));
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  public synchronized Optional<String> apiKey() {
    return sessionApiKey.length == 0 ? Optional.empty() : Optional.of(new String(sessionApiKey));
  }

  public synchronized void forgetApiKey() throws IOException {
    credentialStore.delete(CredentialStore.Kind.API_KEY, credentialAccount(baseUrl));
    replaceSessionApiKey(new char[0]);
    rememberApiKey = false;
    loaded = true;
    writeProperties(sanitizedProperties());
  }

  private Properties sanitizedProperties() {
    Properties properties = new Properties();
    properties.setProperty("baseUrl", baseUrl);
    properties.setProperty("model", model);
    properties.setProperty("rememberApiKey", Boolean.toString(rememberApiKey));
    properties.setProperty("teacher.rankMode", rankMode);
    properties.setProperty("teacher.rankNum", String.valueOf(rankNum));
    properties.setProperty("teacher.styleIndex", String.valueOf(styleIndex));
    properties.setProperty("teacher.densityIndex", String.valueOf(densityIndex));
    properties.setProperty("teacher.paceIndex", String.valueOf(paceIndex));
    properties.setProperty("teacher.variationIndex", String.valueOf(variationIndex));
    return properties;
  }

  private void writeProperties(Properties properties) throws IOException {
    Path parent = settingsFile.toAbsolutePath().normalize().getParent();
    if (parent == null) {
      throw new IOException("AI commentary settings have no parent directory.");
    }
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, "teacher-", ".tmp");
    try {
      try (OutputStream output = Files.newOutputStream(temporary)) {
        properties.store(output, "LizzieYzy Next AI commentary settings (no secrets)");
      }
      try {
        Files.move(
            temporary,
            settingsFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void replaceSessionApiKey(char[] replacement) {
    Arrays.fill(sessionApiKey, '\0');
    sessionApiKey = replacement == null ? new char[0] : replacement.clone();
  }

  static String validateBaseUrl(String value) {
    String candidate = value == null ? "" : value.trim();
    if (candidate.isEmpty()) {
      candidate = DEFAULT_BASE_URL;
    }
    while (candidate.endsWith("/")) {
      candidate = candidate.substring(0, candidate.length() - 1);
    }
    try {
      URI uri = new URI(candidate);
      String scheme = uri.getScheme();
      if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null) {
        throw new IllegalArgumentException("Base URL must be an HTTP(S) server URL.");
      }
      if ("http".equalsIgnoreCase(scheme) && !isLoopbackHost(uri.getHost())) {
        throw new IllegalArgumentException(
            "Plain HTTP is allowed only for a provider running on this computer.");
      }
      return candidate;
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Base URL is invalid.", e);
    }
  }

  private static boolean isLoopbackHost(String host) {
    String normalized = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT);
    return "localhost".equals(normalized)
        || "127.0.0.1".equals(normalized)
        || "::1".equals(normalized)
        || "0:0:0:0:0:0:0:1".equals(normalized);
  }

  static String validateModel(String value) {
    String candidate = value == null ? "" : value.trim();
    if (candidate.isEmpty()) {
      throw new IllegalArgumentException("A model name is required.");
    }
    if (candidate.length() > 160 || candidate.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Model name is invalid.");
    }
    return candidate;
  }

  static String credentialAccount(String baseUrl) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(validateBaseUrl(baseUrl).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return CREDENTIAL_PREFIX + HexFormat.of().formatHex(digest, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable.", e);
    }
  }

  public static final class Snapshot {
    public final String baseUrl;
    public final String model;
    public final boolean rememberApiKey;
    public final boolean hasApiKey;
    public final boolean secureStorageAvailable;
    public final String secureStorageBackend;
    public final String rankMode;
    public final int rankNum;
    public final int styleIndex;
    public final int densityIndex;
    public final int paceIndex;
    public final int variationIndex;

    Snapshot(
        String baseUrl,
        String model,
        boolean rememberApiKey,
        boolean hasApiKey,
        boolean secureStorageAvailable,
        String secureStorageBackend,
        String rankMode,
        int rankNum,
        int styleIndex,
        int densityIndex,
        int paceIndex,
        int variationIndex) {
      this.baseUrl = baseUrl;
      this.model = model;
      this.rememberApiKey = rememberApiKey;
      this.hasApiKey = hasApiKey;
      this.secureStorageAvailable = secureStorageAvailable;
      this.secureStorageBackend = secureStorageBackend;
      this.rankMode = rankMode;
      this.rankNum = rankNum;
      this.styleIndex = styleIndex;
      this.densityIndex = densityIndex;
      this.paceIndex = paceIndex;
      this.variationIndex = variationIndex;
    }
  }
}
