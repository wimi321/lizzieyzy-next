package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Uses only the baseline public API so the same regression can run before and after the fix. */
class ZhiziGtpTransportCancellationTest {
  @Test
  void explicitCloseDuringTokenFetchNeverRetriesOrConnects() throws Exception {
    assertTokenFetchCancellationIsTerminal(false);
  }

  @Test
  void explicitAbortDuringTokenFetchNeverRetriesOrConnects() throws Exception {
    assertTokenFetchCancellationIsTerminal(true);
  }

  private static void assertTokenFetchCancellationIsTerminal(boolean abort) throws Exception {
    AtomicReference<ZhiziGtpTransport> owner = new AtomicReference<>();
    AtomicInteger fetches = new AtomicInteger();
    ZhiziApiClient client =
        new ZhiziApiClient(null, null) {
          @Override
          public SocketToken fetchSocketioToken(String accountToken, String args)
              throws IOException {
            if (fetches.incrementAndGet() > 1) {
              throw new IOException("401 unexpected retry after cancellation");
            }
            if (abort) {
              owner.get().abort();
            } else {
              owner.get().close();
            }
            return new SocketToken("offline-fixture-token", "http://127.0.0.1:1");
          }
        };
    try (ZhiziGtpTransport transport = new ZhiziGtpTransport(client, "offline-account", "")) {
      owner.set(transport);
      assertThrows(IOException.class, transport::start);
      assertEquals(1, fetches.get(), "cancellation must not allocate another server session");
      assertFalse(transport.isOpen());
      assertFalse(transport.isRecoveryRequested());
      assertEquals(-1, transport.stdout().read());
      assertThrows(IOException.class, transport::start);
      assertEquals(1, fetches.get());
    }
  }
}
