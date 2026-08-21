package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.logging.DiagnosticModule;
import featurecat.lizzie.logging.LoggingLimits;
import featurecat.lizzie.logging.LoggingRuntime;
import featurecat.lizzie.logging.LoggingSettings;
import featurecat.lizzie.logging.TraceScope;
import featurecat.lizzie.logging.WorkDirectoryResolution;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZhiziApiClientTest {
  private HttpServer server;
  private String lastPath;
  private String lastMethod;
  private String lastQuery;
  private String lastAuthorization;
  private JSONObject lastBody;
  private int responseStatus;
  private String responseBody;
  private final Map<String, String> responseHeaders = new LinkedHashMap<>();
  private final Map<String, String> responseBodiesByPath = new LinkedHashMap<>();
  private int requestCount;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    responseStatus = 200;
    responseBody = null;
    responseHeaders.clear();
    responseBodiesByPath.clear();
    requestCount = 0;
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void passwordLoginUsesEmailBodyAndReturnsToken() throws Exception {
    ZhiziApiClient client = client();

    String token = client.login("player@example.com", "secret");

    assertEquals("account-token", token);
    assertEquals("/api/cluster/account/login", lastPath);
    assertEquals("player@example.com", lastBody.getString("email"));
    assertEquals("secret", lastBody.getString("password"));
  }

  @Test
  void fastLoginUsesPhoneBodyAndReturnsToken() throws Exception {
    ZhiziApiClient client = client();

    String token = client.fastLogin("13800138000", "123456");

    assertEquals("account-token", token);
    assertEquals("/api/cluster/account/fast-login", lastPath);
    assertEquals("13800138000", lastBody.getString("phone"));
    assertEquals("123456", lastBody.getString("verificationCode"));
  }

  @Test
  void fetchSocketTokenSendsBearerTokenAndArgs() throws Exception {
    ZhiziApiClient client = client();

    ZhiziApiClient.SocketToken token =
        client.fetchSocketioToken("account-token", RemoteComputeConfig.FASTER_ZHIZI_ARGS);

    assertEquals("socket-token", token.token);
    assertEquals("https://socket.example", token.socketIOURL);
    assertEquals("/api/cluster/account/fetch-socketio-token", lastPath);
    assertEquals("Bearer account-token", lastAuthorization);
    assertTrue(lastBody.getString("args").contains("--gpu-type 3x"));
  }

  @Test
  void fetchSocketTokenCanUseVipShareGpuType() throws Exception {
    ZhiziApiClient client = client();

    client.fetchSocketioToken("account-token", RemoteComputeConfig.VIP_ZHIZI_ARGS);

    assertEquals("/api/cluster/account/fetch-socketio-token", lastPath);
    assertTrue(lastBody.getString("args").contains("--gpu-type vip-share"));
  }

  @Test
  void fetchConnectAccountUsesBearerGetAndReturnsTransientCredentials() throws Exception {
    ZhiziApiClient client = client();

    ZhiziApiClient.ConnectAccount account = client.fetchConnectAccount("account-token");

    assertEquals("GET", lastMethod);
    assertEquals("/api/cluster/account/connectAccount/fetch", lastPath);
    assertEquals("Bearer account-token", lastAuthorization);
    assertEquals("zz-player@example.com", account.username);
    assertEquals("temporary-password", account.password);
  }

  @Test
  void accountAndBalanceTolerateExtraFieldsAndKeepDecimalPrecision() throws Exception {
    ZhiziApiClient client = client();

    responseBody =
        "{\"phone\":\"13800138000\",\"isMembership\":true,"
            + "\"membershipExpiresAt\":\"2026-12-31T16:00:00Z\","
            + "\"membershipAutoRenew\":false,\"futureField\":{\"safe\":true}}";
    ZhiziApiClient.AccountProfile profile = client.fetchAccount("account-token");
    assertEquals("13800138000", profile.identifier());
    assertTrue(profile.membership);
    assertEquals("2026-12-31T16:00:00Z", profile.membershipExpiresAt.toString());
    assertEquals(Boolean.FALSE, profile.membershipAutoRenew);

    responseBody =
        "{\"remainingBalance\":\"12.3456\",\"yesterdayConsumption\":0.10,"
            + "\"totalCashAmount\":\"40.25\",\"totalConsumption\":\"100.0001\","
            + "\"totalDuration\":3661,"
            + "\"currentNumOfMyConnections\":2,\"currentNumOfNodes\":8}";
    ZhiziApiClient.BalanceInfo balance = client.fetchBalance("account-token");
    assertEquals(new BigDecimal("12.3456"), balance.remainingBalanceYuan);
    assertEquals(new BigDecimal("40.25"), balance.totalCashAmountYuan);
    assertEquals(new BigDecimal("0.10"), balance.yesterdayConsumptionYuan);
    assertEquals(new BigDecimal("100.0001"), balance.totalConsumptionYuan);
    assertEquals(3661L, balance.totalDurationSeconds);
    assertEquals(2, balance.currentConnections);
  }

  @Test
  void usageAndCreditPagesUseOfficialZeroBasedPagination() throws Exception {
    ZhiziApiClient client = client();

    responseBody =
        "{\"total\":21,\"page\":1,\"pageSize\":20,\"items\":[{"
            + "\"startedAt\":\"2026-08-01T01:02:03Z\",\"finished\":true,"
            + "\"duration\":61,\"totalCost\":\"0.015\",\"gpuType\":\"vip-share\","
            + "\"vip\":true,\"unknown\":123}]}";
    ZhiziApiClient.UsagePage usages = client.fetchUsages("account-token", 1, 20, true);
    assertEquals("page=1&pageSize=20&finished=true", lastQuery);
    assertEquals(21L, usages.total);
    assertEquals(1, usages.page);
    assertEquals(new BigDecimal("0.015"), usages.items.get(0).totalCostYuan);
    assertTrue(usages.items.get(0).vip);

    responseBody =
        "{\"total\":1,\"page\":0,\"pageSize\":20,\"items\":[{"
            + "\"creditType\":\"PURCHASE_PRODUCT\",\"amount\":\"30.50\","
            + "\"productName\":\"MEMBERSHIP_1_MONTH\","
            + "\"createdAt\":\"2026-08-01T02:03:04+00:00\"}]}";
    ZhiziApiClient.CreditPage credits = client.fetchCredits("account-token", 0, 20, "CASH");
    assertEquals("page=0&pageSize=20&creditType=CASH", lastQuery);
    assertEquals(new BigDecimal("30.50"), credits.items.get(0).amountYuan);
    assertEquals("MEMBERSHIP_1_MONTH", credits.items.get(0).productName);
  }

  @Test
  void membershipCatalogUsesOfficialArrayAndIntegerFen() throws Exception {
    responseBody =
        "[{\"name\":\"MEMBERSHIP_12_MONTH\",\"type\":\"MEMBERSHIP\",\"price\":28000},"
            + "{\"name\":\"MEMBERSHIP_1_MONTH\",\"type\":\"MEMBERSHIP\",\"price\":3000},"
            + "{\"name\":\"MEMBERSHIP_3_MONTH\",\"type\":\"OTHER\",\"price\":8000},"
            + "{\"name\":\"MEMBERSHIP_FUTURE\",\"type\":\"MEMBERSHIP\",\"price\":1},"
            + "{\"name\":\"MEMBERSHIP_6_MONTH\",\"type\":\"MEMBERSHIP\",\"price\":15000.5}]";

    List<ZhiziApiClient.MembershipProduct> products = client().fetchMembershipProducts();

    assertEquals("GET", lastMethod);
    assertEquals("/api/cluster/product", lastPath);
    assertEquals("type=MEMBERSHIP", lastQuery);
    assertEquals(2, products.size());
    assertEquals("MEMBERSHIP_1_MONTH", products.get(0).name);
    assertEquals(3000L, products.get(0).priceFen);
    assertEquals(1, products.get(0).durationMonths);
    assertEquals("MEMBERSHIP_12_MONTH", products.get(1).name);
  }

  @Test
  void vipOrderUsesExactProductPriceAndIsNeverRetried() throws Exception {
    responseBody = "[{\"name\":\"MEMBERSHIP_1_MONTH\",\"type\":\"MEMBERSHIP\",\"price\":3000}]";
    ZhiziApiClient client = client();
    ZhiziApiClient.MembershipProduct product = client.fetchMembershipProducts().get(0);
    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"userId\":\"user\","
            + "\"amount\":3000,\"productName\":\"MEMBERSHIP_1_MONTH\","
            + "\"paidStatus\":\"PENDING\",\"nativePayRequest\":{"
            + "\"codeURL\":\"weixin://wxpay/bizpayurl?pr=opaque\"}}";

    ZhiziApiClient.PaymentOrder order =
        client.createMembershipOrder("account-token", product, false);

    assertEquals("POST", lastMethod);
    assertEquals("/api/pay/orders", lastPath);
    assertEquals("Bearer account-token", lastAuthorization);
    assertEquals(3000L, lastBody.getLong("amount"));
    assertEquals("WECHAT", lastBody.getString("payType"));
    assertEquals("NATIVE", lastBody.getString("tradeType"));
    assertEquals("PURCHASE_PRODUCT", lastBody.getString("orderType"));
    assertEquals("MEMBERSHIP_1_MONTH", lastBody.getString("productName"));
    assertFalse(lastBody.getJSONObject("extraInfo").getBoolean("autoRenew"));
    assertEquals(ZhiziApiClient.PaymentPurpose.VIP_MEMBERSHIP, order.purpose);
    assertEquals(ZhiziApiClient.PaymentStatus.PENDING, order.status);
    assertEquals("weixin://wxpay/bizpayurl?pr=opaque", order.codeUrl);

    int requestsBeforeFailure = requestCount;
    responseStatus = 500;
    responseBody = "{\"statusCode\":500,\"key\":\"create_order_error\"}";
    ZhiziApiException failure =
        assertThrows(
            ZhiziApiException.class,
            () -> client.createMembershipOrder("account-token", product, false));
    assertEquals(ZhiziApiException.Operation.CREATE_ORDER, failure.operation());
    assertFalse(failure.isRetryable());
    assertEquals(
        requestsBeforeFailure + 1,
        requestCount,
        "an uncertain financial POST must never be retried automatically");
  }

  @Test
  void balanceTopUpUsesFixedFenWithoutProductFieldsOrAutomaticRetry() throws Exception {
    assertEquals(List.of(1000L, 3000L, 5000L, 10000L), ZhiziApiClient.BALANCE_TOP_UP_PRESETS_FEN);
    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"userId\":\"user\","
            + "\"amount\":1000,\"productName\":null,\"orderType\":null,"
            + "\"paidStatus\":\"PENDING\",\"nativePayRequest\":{"
            + "\"codeURL\":\"weixin://wxpay/bizpayurl?pr=topup\"}}";
    ZhiziApiClient client = client();

    ZhiziApiClient.PaymentOrder order = client.createBalanceTopUpOrder("account-token", 1000L);

    assertEquals("POST", lastMethod);
    assertEquals("/api/pay/orders", lastPath);
    assertEquals("Bearer account-token", lastAuthorization);
    assertEquals(1000L, lastBody.getLong("amount"));
    assertEquals("WECHAT", lastBody.getString("payType"));
    assertEquals("NATIVE", lastBody.getString("tradeType"));
    assertFalse(lastBody.has("orderType"));
    assertFalse(lastBody.has("productName"));
    assertFalse(lastBody.has("extraInfo"));
    assertEquals(ZhiziApiClient.PaymentPurpose.BALANCE_TOP_UP, order.purpose);
    assertEquals("", order.productName);

    int requestsBeforeFailure = requestCount;
    responseStatus = 500;
    responseBody = "{\"statusCode\":500,\"key\":\"create_order_error\"}";
    ZhiziApiException failure =
        assertThrows(
            ZhiziApiException.class, () -> client.createBalanceTopUpOrder("account-token", 1000L));
    assertEquals(ZhiziApiException.Operation.CREATE_ORDER, failure.operation());
    assertFalse(failure.isRetryable());
    assertEquals(requestsBeforeFailure + 1, requestCount);
  }

  @Test
  void balanceTopUpRejectsUnsupportedAndMismatchedAmountsBeforePayment() throws Exception {
    ZhiziApiClient client = client();

    ZhiziApiException unsupported =
        assertThrows(
            ZhiziApiException.class, () -> client.createBalanceTopUpOrder("account-token", 999L));
    assertEquals("invalid_top_up_amount", unsupported.errorKey());
    assertEquals(0, requestCount, "unsupported amounts must be rejected before network I/O");

    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"amount\":3000,"
            + "\"paidStatus\":\"PENDING\",\"nativePayRequest\":{"
            + "\"codeURL\":\"weixin://wxpay/bizpayurl?pr=wrong-amount\"}}";
    ZhiziApiException mismatch =
        assertThrows(
            ZhiziApiException.class, () -> client.createBalanceTopUpOrder("account-token", 1000L));
    assertEquals("invalid_response", mismatch.errorKey());
  }

  @Test
  void orderPollingValidatesIdentityAndTerminalStates() throws Exception {
    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"userId\":\"user\","
            + "\"amount\":3000,\"productName\":\"MEMBERSHIP_1_MONTH\","
            + "\"paidStatus\":\"SUCCESS\",\"paidAt\":\"2026-08-02T01:02:03Z\"}";

    ZhiziApiClient.PaymentOrder order =
        client().fetchOrder("account-token", "66a000000000000000000010");

    assertEquals("GET", lastMethod);
    assertEquals("/api/pay/orders/66a000000000000000000010", lastPath);
    assertEquals(ZhiziApiClient.PaymentStatus.SUCCESS, order.status);
    assertEquals("2026-08-02T01:02:03Z", order.paidAt.toString());

    ZhiziApiException invalidId =
        assertThrows(
            ZhiziApiException.class,
            () -> client().fetchOrder("account-token", "../../another-order"));
    assertEquals("invalid_order_id", invalidId.errorKey());
  }

  @Test
  void orderResponsesRejectPriceChangesAndUnsafeQrPayloads() throws Exception {
    responseBody = "[{\"name\":\"MEMBERSHIP_1_MONTH\",\"type\":\"MEMBERSHIP\",\"price\":3000}]";
    ZhiziApiClient client = client();
    ZhiziApiClient.MembershipProduct product = client.fetchMembershipProducts().get(0);

    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"amount\":2999,"
            + "\"productName\":\"MEMBERSHIP_1_MONTH\",\"paidStatus\":\"PENDING\","
            + "\"nativePayRequest\":{\"codeURL\":\"weixin://wxpay/bizpayurl?pr=opaque\"}}";
    ZhiziApiException changedPrice =
        assertThrows(
            ZhiziApiException.class,
            () -> client.createMembershipOrder("account-token", product, false));
    assertEquals("invalid_response", changedPrice.errorKey());

    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"amount\":3000,"
            + "\"productName\":\"MEMBERSHIP_1_MONTH\",\"paidStatus\":\"PENDING\","
            + "\"nativePayRequest\":{\"codeURL\":\"https://example.com/not-wechat\"}}";
    ZhiziApiException unsafeQr =
        assertThrows(
            ZhiziApiException.class,
            () -> client.createMembershipOrder("account-token", product, false));
    assertEquals("invalid_response", unsafeQr.errorKey());

    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"amount\":3000,"
            + "\"productName\":null,\"orderType\":null,\"paidStatus\":\"SUCCESS\"}";
    ZhiziApiException wrongPurpose =
        assertThrows(
            ZhiziApiException.class,
            () -> client.createMembershipOrder("account-token", product, false));
    assertEquals("invalid_response", wrongPurpose.errorKey());
  }

  @Test
  void topUpSettlementRequiresCashTotalAndMatchingPaymentCredit() throws Exception {
    ZhiziApiClient client = client();
    ZhiziAccountService service = new ZhiziAccountService(client);
    responseBodiesByPath.put(
        "/api/cluster/balance", "{\"remainingBalance\":\"8.50\",\"totalCashAmount\":\"5.00\"}");
    ZhiziAccountService.PaymentBaseline baseline =
        service.capturePaymentBaseline(
            "account-token", ZhiziApiClient.PaymentPurpose.BALANCE_TOP_UP);

    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"amount\":1000,"
            + "\"paidStatus\":\"SUCCESS\",\"paidAt\":\"2026-08-03T01:02:03Z\"}";
    ZhiziApiClient.PaymentOrder order = client.createBalanceTopUpOrder("account-token", 1000L);
    responseBodiesByPath.put(
        "/api/cluster/balance", "{\"remainingBalance\":\"18.50\",\"totalCashAmount\":\"15.00\"}");
    responseBodiesByPath.put(
        "/api/cluster/credit/my-credits",
        "{\"total\":1,\"page\":0,\"pageSize\":20,\"items\":[{"
            + "\"creditType\":\"CASH\",\"amount\":\"10.00\",\"source\":\"PAYMENT\","
            + "\"createdAt\":\""
            + Instant.now().plusSeconds(1)
            + "\"}]}");

    ZhiziAccountService.PaymentVerification verified =
        service.verifyPayment("account-token", baseline, order);
    assertTrue(verified.settled);
    assertTrue(verified.remainingBalanceChanged);
    assertTrue(verified.cashCreditFound);

    responseBodiesByPath.put(
        "/api/cluster/credit/my-credits",
        "{\"total\":1,\"page\":0,\"pageSize\":20,\"items\":[{"
            + "\"creditType\":\"CASH\",\"amount\":\"10.00\",\"source\":\"COUPON\","
            + "\"createdAt\":\""
            + Instant.now().plusSeconds(1)
            + "\"}]}");
    assertFalse(service.verifyPayment("account-token", baseline, order).settled);
  }

  @Test
  void vipSettlementRequiresMembershipExpiryToAdvance() throws Exception {
    ZhiziApiClient client = client();
    ZhiziAccountService service = new ZhiziAccountService(client);
    responseBodiesByPath.put(
        "/api/cluster/account/me",
        "{\"isMembership\":true,\"membershipExpiresAt\":\"2026-08-31T00:00:00Z\"}");
    ZhiziAccountService.PaymentBaseline baseline =
        service.capturePaymentBaseline(
            "account-token", ZhiziApiClient.PaymentPurpose.VIP_MEMBERSHIP);

    responseBody = "[{\"name\":\"MEMBERSHIP_1_MONTH\",\"type\":\"MEMBERSHIP\",\"price\":3000}]";
    ZhiziApiClient.MembershipProduct product = client.fetchMembershipProducts().get(0);
    responseBody =
        "{\"id\":\"66a000000000000000000010\",\"amount\":3000,"
            + "\"orderType\":\"PURCHASE_PRODUCT\","
            + "\"productName\":\"MEMBERSHIP_1_MONTH\",\"paidStatus\":\"SUCCESS\"}";
    ZhiziApiClient.PaymentOrder order =
        client.createMembershipOrder("account-token", product, false);

    responseBodiesByPath.put(
        "/api/cluster/account/me",
        "{\"isMembership\":true,\"membershipExpiresAt\":\"2026-09-30T00:00:00Z\"}");
    ZhiziAccountService.PaymentVerification verified =
        service.verifyPayment("account-token", baseline, order);
    assertTrue(verified.settled);
    assertTrue(verified.membershipAdvanced);
  }

  @Test
  void accountServiceCachesOverviewWithoutPersistingOrRepeatingRequests() throws Exception {
    ZhiziAccountService service = new ZhiziAccountService(client());

    ZhiziAccountService.Overview first = service.fetchOverview("account-token", false);
    ZhiziAccountService.Overview second = service.fetchOverview("account-token", false);

    assertEquals(3, requestCount, "profile, balance and recent usage should each load once");
    assertTrue(first == second, "a fresh overview should be reused within the cache window");
    service.clear();
    service.fetchOverview("account-token", false);
    assertEquals(6, requestCount, "clearing a login must invalidate the account cache");
  }

  @Test
  void protectedAccountEndpointsClassifyExpiredSessions() {
    responseStatus = 401;
    responseBody = "Not Authorized";

    ZhiziApiException failure =
        assertThrows(ZhiziApiException.class, () -> client().fetchBalance("expired-account-token"));

    assertEquals(ZhiziApiException.Operation.FETCH_BALANCE, failure.operation());
    assertTrue(failure.isUnauthorized());
    assertFalse(failure.getMessage().contains("expired-account-token"));
  }

  @Test
  void sendCodeUsesOfficialPurposeAndHonorsRetryAfter() throws Exception {
    responseBody = "";
    responseHeaders.put("Retry-After", "45");

    ZhiziApiClient.CodeDelivery delivery =
        client().sendCode("13800138000", ZhiziApiClient.VerificationPurpose.RESET_PASSWORD);

    assertEquals("/api/cluster/account/send-code", lastPath);
    assertEquals("reset_password", lastBody.getString("type"));
    assertEquals(45L, delivery.retryAfterSeconds);
  }

  @Test
  void resetPasswordRotatesTokenAndValidatesMinimumLength() throws Exception {
    String token = client().resetPassword("player@example.com", "654321", "new-secret");

    assertEquals("account-token", token);
    assertEquals("/api/cluster/account/reset-password", lastPath);
    assertEquals("654321", lastBody.getString("verificationCode"));
    assertEquals("new-secret", lastBody.getString("password"));

    ZhiziApiException tooShort =
        assertThrows(
            ZhiziApiException.class,
            () -> client().resetPassword("player@example.com", "654321", "short"));
    assertEquals("password_too_short", tooShort.errorKey());
    assertEquals(1, requestCount, "local validation must not send a second request");
  }

  @Test
  void jsonErrorIsStructuredWithoutLeakingRawResponse() {
    responseStatus = 500;
    responseBody = "{\"statusCode\":500,\"key\":\"send_code_error\",\"secret\":\"do-not-leak\"}";
    responseHeaders.put("X-Request-Id", "request-42");

    ZhiziApiException failure =
        assertThrows(
            ZhiziApiException.class,
            () -> client().sendCode("13800138000", ZhiziApiClient.VerificationPurpose.FAST_LOGIN));

    assertEquals(500, failure.statusCode());
    assertEquals("send_code_error", failure.errorKey());
    assertEquals("request-42", failure.requestId());
    assertFalse(failure.isRetryable(), "send-code is a side-effecting request");
    assertFalse(failure.getMessage().contains("do-not-leak"));
    assertFalse(failure.getMessage().contains("statusCode"));
  }

  @Test
  void plainTextUnauthorizedIsClassifiedByOperation() {
    responseStatus = 401;
    responseBody = "Not Authorized: account details must not be echoed";

    ZhiziApiException loginFailure =
        assertThrows(
            ZhiziApiException.class, () -> client().login("player@example.com", "bad-password"));
    assertEquals("invalid_credentials", loginFailure.errorKey());
    assertFalse(loginFailure.isUnauthorized(), "bad credentials are not an expired saved session");
    assertFalse(loginFailure.getMessage().contains("account details"));

    ZhiziApiException tokenFailure =
        assertThrows(
            ZhiziApiException.class,
            () -> client().fetchSocketioToken("expired-token", "--gpu-type 1x"));
    assertEquals("unauthorized", tokenFailure.errorKey());
    assertTrue(tokenFailure.isUnauthorized());
  }

  @Test
  void unknownErrorKeyAndEmptyLoginResponseRemainSafe() {
    responseStatus = 409;
    responseBody = "{\"key\":\"future_server_error\"}";
    ZhiziApiException unknown =
        assertThrows(ZhiziApiException.class, () -> client().fastLogin("13800138000", "123456"));
    assertEquals("future_server_error", unknown.errorKey());
    assertFalse(unknown.isRetryable());

    responseStatus = 200;
    responseBody = "";
    ZhiziApiException empty =
        assertThrows(
            ZhiziApiException.class, () -> client().login("player@example.com", "password"));
    assertEquals("invalid_response", empty.errorKey());
  }

  @Test
  void retryAfterIsClampedToOfficialCodeLifetime() {
    responseStatus = 429;
    responseBody = "{\"key\":\"fast_login_too_frequent\"}";
    responseHeaders.put("Retry-After", "3600");

    ZhiziApiException failure =
        assertThrows(
            ZhiziApiException.class,
            () -> client().sendCode("13800138000", ZhiziApiClient.VerificationPurpose.FAST_LOGIN));

    assertEquals(600L, failure.retryAfterSeconds());
    assertFalse(failure.isRetryable());
    assertEquals(1, requestCount, "the client must not blindly retry send-code");
  }

  @Test
  void failedLoginDoesNotPersistPasswordCanary(@TempDir Path tempDir) throws Exception {
    LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    LoggingRuntime runtime =
        LoggingRuntime.initialize(
            new WorkDirectoryResolution(tempDir, List.of()),
            new LoggingLimits(64, 32, 32, 32, 7, 1_000_000, 256_000));
    try {
      runtime.applySettings(
          LoggingSettings.defaults()
              .withDiagnosticsEnabled(true)
              .withDiagnosticModules(EnumSet.of(DiagnosticModule.NETWORK_REMOTE)));
      runtime.startFullTrace(EnumSet.of(TraceScope.NETWORK_WEBSOCKET));
      responseStatus = 401;
      responseBody = "Not Authorized";
      assertThrows(
          ZhiziApiException.class,
          () -> client().login("player@example.com", "T05_ZHIZI_PASSWORD_CANARY"));
      runtime.shutdown();
      String app = Files.readString(tempDir.resolve("logs/app.log"), StandardCharsets.UTF_8);
      assertTrue(app.contains("remote event=http"), app);
      assertTrue(app.contains("category=authentication"), app);
      assertTrue(app.contains("outcome=failed"), app);
      assertFalse(app.contains("T05_ZHIZI_PASSWORD_CANARY"), app);
      assertFalse(app.contains("/api/cluster/account/login"), app);
      Path trace = tempDir.resolve("logs/network-trace.log");
      if (Files.isRegularFile(trace)) {
        assertFalse(
            Files.readString(trace).contains("T05_ZHIZI_PASSWORD_CANARY"), Files.readString(trace));
      }
    } finally {
      LoggingRuntime.current().ifPresent(LoggingRuntime::shutdown);
    }
  }

  private ZhiziApiClient client() {
    URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    return new ZhiziApiClient(baseUri, HttpClient.newHttpClient());
  }

  private void handle(HttpExchange exchange) throws IOException {
    requestCount++;
    lastPath = exchange.getRequestURI().getPath();
    lastQuery = exchange.getRequestURI().getQuery();
    lastMethod = exchange.getRequestMethod();
    lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    lastBody = request.isBlank() ? new JSONObject() : new JSONObject(request);
    String payload = responseBodiesByPath.getOrDefault(lastPath, responseBody);
    if (payload == null) {
      JSONObject response = new JSONObject();
      if (lastPath.endsWith("/fetch-socketio-token")) {
        response.put("token", "socket-token");
        response.put("socketIOURL", "https://socket.example");
      } else if (lastPath.endsWith("/connectAccount/fetch")) {
        response.put("connectUsername", "zz-player@example.com");
        response.put("connectPassword", "temporary-password");
      } else if (lastPath.endsWith("/send-code")) {
        payload = "";
      } else if (lastPath.endsWith("/account/me")) {
        response.put("email", "player@example.com");
        response.put("isMembership", true);
      } else if (lastPath.endsWith("/balance")) {
        response.put("remainingBalance", "8.50");
        response.put("yesterdayConsumption", "0.25");
      } else if (lastPath.endsWith("/my-usages")) {
        response.put("total", 0);
        response.put("page", 0);
        response.put("pageSize", 5);
        response.put("items", new org.json.JSONArray());
      } else if (lastPath.endsWith("/my-credits")) {
        response.put("total", 0);
        response.put("page", 0);
        response.put("pageSize", 20);
        response.put("items", new org.json.JSONArray());
      } else {
        response.put("token", "account-token");
      }
      if (payload == null) {
        payload = response.toString();
      }
    }
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
    exchange.sendResponseHeaders(responseStatus, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
