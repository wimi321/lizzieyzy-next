package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.analysis.remote.CredentialStore;
import org.junit.jupiter.api.Test;

class ChatGptLiveAcceptanceCliTest {
  @Test
  void observationPreservesCredentialBytesAndRecordsOnlyExpiryMetadata() throws Exception {
    var delegate = new ChatGptIntegrationTest.MemoryStore();
    var observer = new ChatGptLiveAcceptanceCli.ExpiryObservationStore(delegate);
    String before = "{\"expires_at\":100,\"access_token\":\"synthetic-token\"}";
    delegate.write(CredentialStore.Kind.CHATGPT_SESSION, "fixture", before);
    assertEquals(
        before, observer.read(CredentialStore.Kind.CHATGPT_SESSION, "fixture").orElseThrow());
    assertEquals(100, observer.firstExpiry);
    assertEquals(100, observer.lastExpiry);
    assertEquals(0, observer.writes);
    String after = "{\"expires_at\":200,\"access_token\":\"synthetic-renewed\"}";
    observer.write(CredentialStore.Kind.CHATGPT_SESSION, "fixture", after);
    assertEquals(
        after, observer.read(CredentialStore.Kind.CHATGPT_SESSION, "fixture").orElseThrow());
    assertEquals(100, observer.firstExpiry);
    assertEquals(200, observer.lastExpiry);
    assertEquals(1, observer.writes);
  }

  @Test
  void otherCredentialKindsAreNotParsedOrCounted() throws Exception {
    var observer =
        new ChatGptLiveAcceptanceCli.ExpiryObservationStore(new ChatGptIntegrationTest.MemoryStore());
    observer.write(CredentialStore.Kind.API_KEY, "fixture", "synthetic-not-json");
    assertEquals(
        "synthetic-not-json", observer.read(CredentialStore.Kind.API_KEY, "fixture").orElseThrow());
    assertEquals(0, observer.firstExpiry);
    assertEquals(0, observer.writes);
  }

  @Test
  void missingCredentialsRemainMissingAndDeleteIsScoped() throws Exception {
    var delegate = new ChatGptIntegrationTest.MemoryStore();
    var observer = new ChatGptLiveAcceptanceCli.ExpiryObservationStore(delegate);
    assertTrue(observer.read(CredentialStore.Kind.CHATGPT_SESSION, "missing").isEmpty());
    delegate.write(CredentialStore.Kind.API_KEY, "keep", "synthetic");
    delegate.write(CredentialStore.Kind.CHATGPT_SESSION, "remove", "{\"expires_at\":100}");
    observer.delete(CredentialStore.Kind.CHATGPT_SESSION, "remove");
    assertTrue(observer.read(CredentialStore.Kind.CHATGPT_SESSION, "remove").isEmpty());
    assertEquals("synthetic", observer.read(CredentialStore.Kind.API_KEY, "keep").orElseThrow());
  }
}
