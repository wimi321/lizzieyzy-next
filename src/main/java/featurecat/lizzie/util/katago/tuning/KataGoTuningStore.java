package featurecat.lizzie.util.katago.tuning;

import java.util.Objects;
import java.util.Optional;
import org.json.JSONObject;

/** Stores exactly one local tuning profile in a caller-owned JSON object. */
public final class KataGoTuningStore {
  public static final String KEY = "katago-apple-tuning-profile-v1";

  private final JSONObject storage;

  public KataGoTuningStore(JSONObject storage) {
    this.storage = Objects.requireNonNull(storage, "storage");
  }

  /** Returns whether storage contains a structurally valid profile, without hashing any files. */
  public synchronized boolean hasStoredProfile() {
    try {
      JSONObject profileJson = storage.optJSONObject(KEY);
      return profileJson != null && KataGoTuningProfile.fromJson(profileJson).isPresent();
    } catch (RuntimeException corruptStorage) {
      return false;
    }
  }

  public synchronized Optional<KataGoTuningProfile> loadMatching(
      KataGoTuningFingerprint fingerprint) {
    Objects.requireNonNull(fingerprint, "fingerprint");
    try {
      JSONObject profileJson = storage.optJSONObject(KEY);
      if (profileJson == null) {
        return Optional.empty();
      }
      return KataGoTuningProfile.fromJson(profileJson)
          .filter(profile -> profile.fingerprintDigest().equals(fingerprint.canonicalDigest()));
    } catch (RuntimeException corruptStorage) {
      return Optional.empty();
    }
  }

  public synchronized void save(KataGoTuningProfile profile) {
    storage.put(KEY, Objects.requireNonNull(profile, "profile").toJson());
  }

  public synchronized void clear() {
    storage.remove(KEY);
  }
}
