package featurecat.lizzie.analysis.remote;

import java.io.IOException;

/** Loads the model choices advertised by the user's current Zhizi engine service. */
public final class ZhiziEngineCatalogService {
  private final AccountFetcher accountFetcher;
  private final CatalogFetcher catalogFetcher;

  public ZhiziEngineCatalogService(ZhiziApiClient apiClient) throws IOException {
    this(apiClient::fetchConnectAccount, new ZhiziServerCatalogClient()::fetchCatalog);
  }

  ZhiziEngineCatalogService(AccountFetcher accountFetcher, CatalogFetcher catalogFetcher) {
    this.accountFetcher = accountFetcher;
    this.catalogFetcher = catalogFetcher;
  }

  public ZhiziEngineCatalog refresh(String accountToken) throws IOException, InterruptedException {
    if (accountToken == null || accountToken.isBlank()) {
      throw new IOException("Zhizi login is required before refreshing engine models.");
    }
    ZhiziApiClient.ConnectAccount account = accountFetcher.fetch(accountToken.trim());
    return catalogFetcher.fetch(account);
  }

  @FunctionalInterface
  interface AccountFetcher {
    ZhiziApiClient.ConnectAccount fetch(String accountToken)
        throws IOException, InterruptedException;
  }

  @FunctionalInterface
  interface CatalogFetcher {
    ZhiziEngineCatalog fetch(ZhiziApiClient.ConnectAccount account)
        throws IOException, InterruptedException;
  }
}
