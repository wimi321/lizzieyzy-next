package featurecat.lizzie.util.katago.tuning;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Content-addressed identity for the host, engine, model, config, and tuning algorithm. */
public final class KataGoTuningFingerprint {
  public static final int SCHEMA_VERSION = 1;
  public static final int PLANNER_VERSION = 3;

  private static final int HASH_BUFFER_BYTES = 64 * 1024;
  private static final int MAX_STABLE_HASH_ATTEMPTS = 3;
  private static final CancellationCheck NEVER_CANCELLED = () -> {};
  private static final ConcurrentMap<HashCacheKey, String> CONTENT_HASH_CACHE =
      new ConcurrentHashMap<HashCacheKey, String>();

  private final int schemaVersion;
  private final int plannerVersion;
  private final AppleSiliconHardwareProbe.HardwareProfile host;
  private final ArtifactFingerprint engine;
  private final ArtifactFingerprint model;
  private final ArtifactFingerprint config;
  private final String commandSemantics;
  private final String canonicalDigest;

  private KataGoTuningFingerprint(
      int schemaVersion,
      int plannerVersion,
      AppleSiliconHardwareProbe.HardwareProfile host,
      ArtifactFingerprint engine,
      ArtifactFingerprint model,
      ArtifactFingerprint config,
      String commandSemantics) {
    this.schemaVersion = schemaVersion;
    this.plannerVersion = plannerVersion;
    this.host = Objects.requireNonNull(host, "host");
    this.engine = Objects.requireNonNull(engine, "engine");
    this.model = Objects.requireNonNull(model, "model");
    this.config = Objects.requireNonNull(config, "config");
    this.commandSemantics = commandSemantics == null ? "" : commandSemantics.trim();
    this.canonicalDigest = computeCanonicalDigest();
  }

  /** Builds a path-independent fingerprint after hashing all three artifacts by content. */
  public static KataGoTuningFingerprint create(
      Path engine,
      Path model,
      Path config,
      AppleSiliconHardwareProbe.HardwareProfile hardwareProfile)
      throws IOException {
    return create(engine, model, config, hardwareProfile, "", NEVER_CANCELLED);
  }

  /** Builds a path-independent fingerprint while cooperatively checking for cancellation. */
  public static KataGoTuningFingerprint create(
      Path engine,
      Path model,
      Path config,
      AppleSiliconHardwareProbe.HardwareProfile hardwareProfile,
      CancellationCheck cancellationCheck)
      throws IOException {
    return create(engine, model, config, hardwareProfile, "", cancellationCheck);
  }

  /** Builds a fingerprint that also scopes the result to non-managed inline command semantics. */
  public static KataGoTuningFingerprint create(
      Path engine,
      Path model,
      Path config,
      AppleSiliconHardwareProbe.HardwareProfile hardwareProfile,
      String commandSemantics)
      throws IOException {
    return create(engine, model, config, hardwareProfile, commandSemantics, NEVER_CANCELLED);
  }

  /**
   * Builds a semantics-scoped fingerprint while cooperatively checking for cancellation.
   *
   * <p>Any {@link IOException} thrown by the callback is propagated unchanged, allowing a caller's
   * own cancellation exception type to survive the hashing layer.
   */
  public static KataGoTuningFingerprint create(
      Path engine,
      Path model,
      Path config,
      AppleSiliconHardwareProbe.HardwareProfile hardwareProfile,
      String commandSemantics,
      CancellationCheck cancellationCheck)
      throws IOException {
    Objects.requireNonNull(hardwareProfile, "hardwareProfile");
    CancellationCheck cancellation =
        cancellationCheck == null ? NEVER_CANCELLED : cancellationCheck;
    cancellation.checkCancelled();
    return new KataGoTuningFingerprint(
        SCHEMA_VERSION,
        PLANNER_VERSION,
        hardwareProfile,
        fingerprintArtifact(engine, "engine", cancellation),
        fingerprintArtifact(model, "model", cancellation),
        fingerprintArtifact(config, "config", cancellation),
        commandSemantics);
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public int plannerVersion() {
    return plannerVersion;
  }

  public AppleSiliconHardwareProbe.HardwareProfile host() {
    return host;
  }

  public ArtifactFingerprint engine() {
    return engine;
  }

  public ArtifactFingerprint model() {
    return model;
  }

  public ArtifactFingerprint config() {
    return config;
  }

  public String canonicalDigest() {
    return canonicalDigest;
  }

  public String engineSha256() {
    return engine.sha256();
  }

  public long engineSizeBytes() {
    return engine.sizeBytes();
  }

  public String modelSha256() {
    return model.sha256();
  }

  public long modelSizeBytes() {
    return model.sizeBytes();
  }

  public String configSha256() {
    return config.sha256();
  }

  public long configSizeBytes() {
    return config.sizeBytes();
  }

  private static ArtifactFingerprint fingerprintArtifact(
      Path path, String label, CancellationCheck cancellation) throws IOException {
    cancellation.checkCancelled();
    Objects.requireNonNull(path, label);
    Path canonicalPath = path.toRealPath();

    for (int attempt = 0; attempt < MAX_STABLE_HASH_ATTEMPTS; attempt++) {
      cancellation.checkCancelled();
      BasicFileAttributes before = Files.readAttributes(canonicalPath, BasicFileAttributes.class);
      if (!before.isRegularFile()) {
        throw new IOException(label + " is not a regular file: " + canonicalPath);
      }

      HashCacheKey key = new HashCacheKey(canonicalPath, before.size(), before.lastModifiedTime());
      String cached = CONTENT_HASH_CACHE.get(key);
      if (cached != null) {
        cancellation.checkCancelled();
        return new ArtifactFingerprint(cached, before.size());
      }

      String digest = sha256(canonicalPath, cancellation);
      cancellation.checkCancelled();
      BasicFileAttributes after = Files.readAttributes(canonicalPath, BasicFileAttributes.class);
      if (before.size() == after.size()
          && before.lastModifiedTime().equals(after.lastModifiedTime())) {
        String prior = CONTENT_HASH_CACHE.putIfAbsent(key, digest);
        return new ArtifactFingerprint(prior == null ? digest : prior, before.size());
      }
    }
    throw new IOException(label + " changed repeatedly while it was being fingerprinted");
  }

  private String computeCanonicalDigest() {
    MessageDigest digest = newSha256();
    addCanonicalField(digest, "schemaVersion", Integer.toString(schemaVersion));
    addCanonicalField(digest, "plannerVersion", Integer.toString(plannerVersion));
    addCanonicalField(digest, "host.hardwareModel", host.hardwareModel());
    addCanonicalField(digest, "host.chipOrBrand", host.chipOrBrand());
    addCanonicalField(digest, "host.architecture", host.architecture());
    addCanonicalField(digest, "host.logicalCpuCount", Integer.toString(host.logicalCpuCount()));
    addCanonicalField(digest, "host.memoryBytes", Long.toString(host.memoryBytes()));
    addCanonicalField(digest, "host.macOsBuild", host.macOsBuild());
    addCanonicalField(digest, "host.rosettaTranslated", Boolean.toString(host.rosettaTranslated()));
    addArtifact(digest, "engine", engine);
    addArtifact(digest, "model", model);
    addArtifact(digest, "config", config);
    addCanonicalField(digest, "commandSemantics", commandSemantics);
    return toHex(digest.digest());
  }

  private static void addArtifact(
      MessageDigest digest, String label, ArtifactFingerprint artifact) {
    addCanonicalField(digest, label + ".sha256", artifact.sha256());
    addCanonicalField(digest, label + ".sizeBytes", Long.toString(artifact.sizeBytes()));
  }

  private static void addCanonicalField(MessageDigest digest, String name, String value) {
    byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(nameBytes.length).array());
    digest.update(nameBytes);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(valueBytes.length).array());
    digest.update(valueBytes);
  }

  private static String sha256(Path path, CancellationCheck cancellation) throws IOException {
    MessageDigest digest = newSha256();
    byte[] buffer = new byte[HASH_BUFFER_BYTES];
    cancellation.checkCancelled();
    try (InputStream input = Files.newInputStream(path)) {
      while (true) {
        cancellation.checkCancelled();
        int read = input.read(buffer);
        if (read == -1) {
          break;
        }
        digest.update(buffer, 0, read);
      }
    }
    cancellation.checkCancelled();
    return toHex(digest.digest());
  }

  private static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
      result.append(Character.forDigit(value & 0x0f, 16));
    }
    return result.toString();
  }

  static void clearHashCacheForTests() {
    CONTENT_HASH_CACHE.clear();
  }

  /** Cooperative cancellation hook called before artifacts and between hash input chunks. */
  @FunctionalInterface
  public interface CancellationCheck {
    void checkCancelled() throws IOException;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof KataGoTuningFingerprint that)) {
      return false;
    }
    return schemaVersion == that.schemaVersion
        && plannerVersion == that.plannerVersion
        && host.equals(that.host)
        && engine.equals(that.engine)
        && model.equals(that.model)
        && config.equals(that.config)
        && commandSemantics.equals(that.commandSemantics)
        && canonicalDigest.equals(that.canonicalDigest);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        schemaVersion,
        plannerVersion,
        host,
        engine,
        model,
        config,
        commandSemantics,
        canonicalDigest);
  }

  @Override
  public String toString() {
    return "KataGoTuningFingerprint[canonicalDigest=" + canonicalDigest + "]";
  }

  /** Content identity for one artifact; its filesystem path is intentionally absent. */
  public record ArtifactFingerprint(String sha256, long sizeBytes) {
    public ArtifactFingerprint {
      sha256 = Objects.requireNonNull(sha256, "sha256").trim().toLowerCase(Locale.ROOT);
      if (!sha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("sha256 must be a 64-character lowercase hex digest");
      }
      if (sizeBytes < 0L) {
        throw new IllegalArgumentException("sizeBytes must not be negative");
      }
    }
  }

  private record HashCacheKey(Path canonicalPath, long sizeBytes, FileTime modifiedTime) {
    private HashCacheKey {
      Objects.requireNonNull(canonicalPath, "canonicalPath");
      Objects.requireNonNull(modifiedTime, "modifiedTime");
    }
  }
}
