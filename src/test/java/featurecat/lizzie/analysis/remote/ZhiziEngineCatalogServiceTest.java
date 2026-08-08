package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ZhiziEngineCatalogServiceTest {
  @Test
  void refreshUsesTheSessionAccountWithoutPersistingConnectionCredentials() throws Exception {
    AtomicReference<String> receivedToken = new AtomicReference<>();
    AtomicReference<ZhiziApiClient.ConnectAccount> receivedAccount = new AtomicReference<>();
    ZhiziApiClient.ConnectAccount temporaryAccount =
        new ZhiziApiClient.ConnectAccount("temporary-user", "temporary-password");
    ZhiziEngineCatalog expected =
        new ZhiziEngineCatalog(
            "8.0.1",
            "10b512t",
            List.of(
                new ZhiziEngineCatalog.Option(
                    "10b512t",
                    "medium transformer",
                    ZhiziEngineCatalog.DiscoverySource.SERVER_CAPABILITIES)));
    ZhiziEngineCatalogService service =
        new ZhiziEngineCatalogService(
            token -> {
              receivedToken.set(token);
              return temporaryAccount;
            },
            account -> {
              receivedAccount.set(account);
              return expected;
            });

    ZhiziEngineCatalog actual = service.refresh("  session-token  ");

    assertSame(expected, actual);
    assertEquals("session-token", receivedToken.get());
    assertSame(temporaryAccount, receivedAccount.get());
  }
}
