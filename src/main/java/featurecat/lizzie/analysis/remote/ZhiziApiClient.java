package featurecat.lizzie.analysis.remote;

import featurecat.lizzie.logging.NetworkEndpointCategory;
import featurecat.lizzie.logging.NetworkObservation;
import featurecat.lizzie.util.NetworkProxy;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class ZhiziApiClient {
  public static final URI DEFAULT_BASE_URI = URI.create("https://www.zhizigo.com");
  public static final List<Long> BALANCE_TOP_UP_PRESETS_FEN = List.of(1000L, 3000L, 5000L, 10000L);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final long DEFAULT_CODE_COOLDOWN_SECONDS = 60L;

  private final URI baseUri;
  private final HttpClient httpClient;

  public ZhiziApiClient() throws IOException {
    this(
        DEFAULT_BASE_URI,
        NetworkProxy.configure(HttpClient.newBuilder()).connectTimeout(REQUEST_TIMEOUT).build());
  }

  public ZhiziApiClient(URI baseUri, HttpClient httpClient) {
    this.baseUri = baseUri;
    this.httpClient = httpClient;
  }

  public String login(String identifier, String password) throws IOException, InterruptedException {
    JSONObject body = identifierBody(identifier);
    body.put("password", password == null ? "" : password);
    return extractToken(
        post("/api/cluster/account/login", body, "", ZhiziApiException.Operation.LOGIN, false),
        ZhiziApiException.Operation.LOGIN);
  }

  public String fastLogin(String identifier, String verificationCode)
      throws IOException, InterruptedException {
    JSONObject body = identifierBody(identifier);
    body.put("verificationCode", verificationCode == null ? "" : verificationCode);
    return extractToken(
        post(
            "/api/cluster/account/fast-login",
            body,
            "",
            ZhiziApiException.Operation.FAST_LOGIN,
            false),
        ZhiziApiException.Operation.FAST_LOGIN);
  }

  public void sendCode(String identifier) throws IOException, InterruptedException {
    sendCode(identifier, VerificationPurpose.FAST_LOGIN);
  }

  public CodeDelivery sendCode(String identifier, VerificationPurpose purpose)
      throws IOException, InterruptedException {
    JSONObject body = identifierBody(identifier);
    body.put("type", (purpose == null ? VerificationPurpose.FAST_LOGIN : purpose).apiValue);
    JsonResponse response =
        postResponse(
            "/api/cluster/account/send-code",
            body,
            "",
            ZhiziApiException.Operation.SEND_CODE,
            true);
    long cooldown = response.retryAfterSeconds;
    return new CodeDelivery(cooldown > 0 ? cooldown : DEFAULT_CODE_COOLDOWN_SECONDS);
  }

  public String resetPassword(String identifier, String verificationCode, String newPassword)
      throws IOException, InterruptedException {
    if (newPassword == null || newPassword.length() < 8) {
      throw new ZhiziApiException(
          400, "password_too_short", "", 0, false, ZhiziApiException.Operation.RESET_PASSWORD);
    }
    JSONObject body = identifierBody(identifier);
    body.put("verificationCode", verificationCode == null ? "" : verificationCode);
    body.put("password", newPassword);
    return extractToken(
        post(
            "/api/cluster/account/reset-password",
            body,
            "",
            ZhiziApiException.Operation.RESET_PASSWORD,
            false),
        ZhiziApiException.Operation.RESET_PASSWORD);
  }

  public SocketToken fetchSocketioToken(String accountToken, String args)
      throws IOException, InterruptedException {
    JSONObject body = new JSONObject();
    body.put(
        "args",
        args == null || args.trim().isEmpty() ? RemoteComputeConfig.DEFAULT_ZHIZI_ARGS : args);
    JSONObject response =
        post(
            "/api/cluster/account/fetch-socketio-token",
            body,
            accountToken,
            ZhiziApiException.Operation.FETCH_SOCKET_TOKEN,
            false);
    String token = response.optString("token", "");
    String socketIOURL = response.optString("socketIOURL", "");
    if (token.isEmpty() || socketIOURL.isEmpty()) {
      throw invalidResponse(ZhiziApiException.Operation.FETCH_SOCKET_TOKEN);
    }
    return new SocketToken(token, socketIOURL);
  }

  ConnectAccount fetchConnectAccount(String accountToken) throws IOException, InterruptedException {
    JSONObject response =
        get(
            "/api/cluster/account/connectAccount/fetch",
            accountToken,
            ZhiziApiException.Operation.FETCH_CONNECT_ACCOUNT);
    String username = response.optString("connectUsername", "").trim();
    String password = response.optString("connectPassword", "");
    if (username.isEmpty() || password.isEmpty()) {
      throw invalidResponse(ZhiziApiException.Operation.FETCH_CONNECT_ACCOUNT);
    }
    return new ConnectAccount(username, password);
  }

  public AccountProfile fetchAccount(String accountToken) throws IOException, InterruptedException {
    JSONObject response =
        get("/api/cluster/account/me", accountToken, ZhiziApiException.Operation.FETCH_ACCOUNT);
    return AccountProfile.fromJson(response);
  }

  public BalanceInfo fetchBalance(String accountToken) throws IOException, InterruptedException {
    JSONObject response =
        get("/api/cluster/balance", accountToken, ZhiziApiException.Operation.FETCH_BALANCE);
    return BalanceInfo.fromJson(response);
  }

  public UsagePage fetchUsages(String accountToken, int page, int pageSize, Boolean finished)
      throws IOException, InterruptedException {
    StringBuilder path =
        new StringBuilder("/api/cluster/usage/my-usages?page=")
            .append(Math.max(0, page))
            .append("&pageSize=")
            .append(clampPageSize(pageSize));
    if (finished != null) {
      path.append("&finished=").append(finished.booleanValue());
    }
    JSONObject response =
        get(path.toString(), accountToken, ZhiziApiException.Operation.FETCH_USAGE);
    return UsagePage.fromJson(response, Math.max(0, page), clampPageSize(pageSize));
  }

  public CreditPage fetchCredits(String accountToken, int page, int pageSize, String creditType)
      throws IOException, InterruptedException {
    StringBuilder path =
        new StringBuilder("/api/cluster/credit/my-credits?page=")
            .append(Math.max(0, page))
            .append("&pageSize=")
            .append(clampPageSize(pageSize));
    String type = creditType == null ? "" : creditType.trim();
    if (!type.isEmpty()) {
      path.append("&creditType=").append(URLEncoder.encode(type, StandardCharsets.UTF_8));
    }
    JSONObject response =
        get(path.toString(), accountToken, ZhiziApiException.Operation.FETCH_CREDITS);
    return CreditPage.fromJson(response, Math.max(0, page), clampPageSize(pageSize));
  }

  public List<MembershipProduct> fetchMembershipProducts()
      throws IOException, InterruptedException {
    JSONArray response =
        getArray(
            "/api/cluster/product?type=MEMBERSHIP", "", ZhiziApiException.Operation.FETCH_PRODUCTS);
    List<MembershipProduct> products = new ArrayList<>();
    for (int index = 0; index < response.length(); index++) {
      JSONObject item = response.optJSONObject(index);
      MembershipProduct product = MembershipProduct.fromJson(item);
      if (product != null) {
        products.add(product);
      }
    }
    products.sort((left, right) -> Integer.compare(left.durationMonths, right.durationMonths));
    return Collections.unmodifiableList(products);
  }

  public PaymentOrder createMembershipOrder(
      String accountToken, MembershipProduct product, boolean autoRenew)
      throws IOException, InterruptedException {
    if (product == null || !product.isPurchasable()) {
      throw new ZhiziApiException(
          400, "invalid_product", "", 0, false, ZhiziApiException.Operation.CREATE_ORDER);
    }
    JSONObject body = new JSONObject();
    body.put("payType", "WECHAT");
    body.put("amount", product.priceFen);
    body.put("tradeType", "NATIVE");
    body.put("body", "LizzieYzy Next VIP membership");
    body.put("orderType", "PURCHASE_PRODUCT");
    body.put("productName", product.name);
    body.put("extraInfo", new JSONObject().put("autoRenew", autoRenew));
    PaymentOrder order =
        PaymentOrder.fromJson(
            post(
                "/api/pay/orders",
                body,
                accountToken,
                ZhiziApiException.Operation.CREATE_ORDER,
                false),
            ZhiziApiException.Operation.CREATE_ORDER);
    if (order.amountFen != product.priceFen
        || order.purpose != PaymentPurpose.VIP_MEMBERSHIP
        || !product.name.equals(order.productName)) {
      throw invalidResponse(ZhiziApiException.Operation.CREATE_ORDER);
    }
    return order;
  }

  public PaymentOrder createBalanceTopUpOrder(String accountToken, long amountFen)
      throws IOException, InterruptedException {
    if (!BALANCE_TOP_UP_PRESETS_FEN.contains(amountFen)) {
      throw new ZhiziApiException(
          400, "invalid_top_up_amount", "", 0, false, ZhiziApiException.Operation.CREATE_ORDER);
    }
    JSONObject body = new JSONObject();
    body.put("payType", "WECHAT");
    body.put("amount", amountFen);
    body.put("tradeType", "NATIVE");
    body.put("body", "LizzieYzy Next account top-up");
    PaymentOrder order =
        PaymentOrder.fromJson(
            post(
                "/api/pay/orders",
                body,
                accountToken,
                ZhiziApiException.Operation.CREATE_ORDER,
                false),
            ZhiziApiException.Operation.CREATE_ORDER);
    if (order.amountFen != amountFen
        || order.purpose != PaymentPurpose.BALANCE_TOP_UP
        || !order.productName.isEmpty()) {
      throw invalidResponse(ZhiziApiException.Operation.CREATE_ORDER);
    }
    return order;
  }

  public PaymentOrder fetchOrder(String accountToken, String orderId)
      throws IOException, InterruptedException {
    String safeOrderId = orderId == null ? "" : orderId.trim();
    if (!safeOrderId.matches("[0-9a-fA-F]{24}")) {
      throw new ZhiziApiException(
          400, "invalid_order_id", "", 0, false, ZhiziApiException.Operation.FETCH_ORDER);
    }
    PaymentOrder order =
        PaymentOrder.fromJson(
            get(
                "/api/pay/orders/" + safeOrderId,
                accountToken,
                ZhiziApiException.Operation.FETCH_ORDER),
            ZhiziApiException.Operation.FETCH_ORDER);
    if (!safeOrderId.equalsIgnoreCase(order.id)) {
      throw invalidResponse(ZhiziApiException.Operation.FETCH_ORDER);
    }
    return order;
  }

  private JSONObject post(
      String path,
      JSONObject body,
      String bearerToken,
      ZhiziApiException.Operation operation,
      boolean allowEmpty)
      throws IOException, InterruptedException {
    return postResponse(path, body, bearerToken, operation, allowEmpty).body;
  }

  private JsonResponse postResponse(
      String path,
      JSONObject body,
      String bearerToken,
      ZhiziApiException.Operation operation,
      boolean allowEmpty)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .version(HttpClient.Version.HTTP_1_1)
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    addAuthorization(builder, bearerToken);
    return sendJson(builder.build(), operation, allowEmpty);
  }

  private JSONObject get(String path, String bearerToken, ZhiziApiException.Operation operation)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .version(HttpClient.Version.HTTP_1_1)
            .GET();
    addAuthorization(builder, bearerToken);
    return sendJson(builder.build(), operation, false).body;
  }

  private JSONArray getArray(String path, String bearerToken, ZhiziApiException.Operation operation)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .version(HttpClient.Version.HTTP_1_1)
            .GET();
    addAuthorization(builder, bearerToken);
    Object body = sendJsonValue(builder.build(), operation, false).body;
    if (!(body instanceof JSONArray)) {
      throw invalidResponse(operation);
    }
    return (JSONArray) body;
  }

  private JsonResponse sendJson(
      HttpRequest request, ZhiziApiException.Operation operation, boolean allowEmpty)
      throws IOException, InterruptedException {
    JsonValueResponse parsed = sendJsonValue(request, operation, allowEmpty);
    if (!(parsed.body instanceof JSONObject)) {
      throw invalidResponse(operation);
    }
    return new JsonResponse((JSONObject) parsed.body, parsed.retryAfterSeconds);
  }

  private JsonValueResponse sendJsonValue(
      HttpRequest request, ZhiziApiException.Operation operation, boolean allowEmpty)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    String requestId = NetworkObservation.newRequestIdentity();
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException firstFailure) {
      if (!operation.isIdempotent()) {
        observe(request, operation, 0, started, requestId, "failed");
        throw networkException(operation, firstFailure);
      }
      Thread.sleep(650L);
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      } catch (IOException retryFailure) {
        observe(request, operation, 0, started, requestId, "failed");
        throw networkException(operation, retryFailure);
      }
    }

    int status = response.statusCode();
    String responseBody = response.body() == null ? "" : response.body();
    long retryAfter = parseRetryAfter(response);
    String headerId = requestId(response);
    if (!headerId.isEmpty()) {
      requestId = headerId;
    }
    if (status < 200 || status >= 300) {
      String errorKey = errorKey(responseBody, status, operation);
      boolean retryable =
          operation.isIdempotent()
              && (status == 408 || status == 425 || status == 429 || status >= 500);
      observe(request, operation, status, started, requestId, "failed");
      throw new ZhiziApiException(status, errorKey, requestId, retryAfter, retryable, operation);
    }
    if (responseBody.trim().isEmpty()) {
      if (!allowEmpty) {
        observe(request, operation, status, started, requestId, "failed");
        throw invalidResponse(operation);
      }
      observe(request, operation, status, started, requestId, "ok");
      return new JsonValueResponse(new JSONObject(), retryAfter);
    }
    try {
      Object parsed = new JSONTokener(responseBody).nextValue();
      if (!(parsed instanceof JSONObject) && !(parsed instanceof JSONArray)) {
        throw new JSONException("JSON response must be an object or array");
      }
      observe(request, operation, status, started, requestId, "ok");
      return new JsonValueResponse(parsed, retryAfter);
    } catch (JSONException invalidJson) {
      observe(request, operation, status, started, requestId, "failed");
      throw new ZhiziApiException(
          status, "invalid_response", requestId, retryAfter, false, operation, invalidJson);
    }
  }

  private void observe(
      HttpRequest request,
      ZhiziApiException.Operation operation,
      Integer status,
      long startedNanos,
      String requestId,
      String outcome) {
    URI uri = request == null ? baseUri : request.uri();
    String host = uri == null || uri.getHost() == null ? baseUri.getHost() : uri.getHost();
    NetworkObservation.recordRemote(
        request == null ? "UNKNOWN" : request.method(),
        host,
        category(operation),
        status,
        Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)),
        outcome,
        requestId);
  }

  static NetworkEndpointCategory category(ZhiziApiException.Operation operation) {
    if (operation == null) {
      return NetworkEndpointCategory.OTHER;
    }
    switch (operation) {
      case LOGIN:
      case FAST_LOGIN:
      case SEND_CODE:
      case RESET_PASSWORD:
        return NetworkEndpointCategory.AUTHENTICATION;
      case FETCH_SOCKET_TOKEN:
      case FETCH_CONNECT_ACCOUNT:
        return NetworkEndpointCategory.CREDENTIAL;
      case FETCH_ACCOUNT:
      case FETCH_BALANCE:
      case FETCH_USAGE:
      case FETCH_CREDITS:
        return NetworkEndpointCategory.ACCOUNT;
      case FETCH_PRODUCTS:
      case CREATE_ORDER:
      case FETCH_ORDER:
        return NetworkEndpointCategory.PAYMENT;
      default:
        return NetworkEndpointCategory.OTHER;
    }
  }

  private static void addAuthorization(HttpRequest.Builder builder, String bearerToken) {
    if (bearerToken != null && !bearerToken.trim().isEmpty()) {
      builder.header("Authorization", "Bearer " + bearerToken.trim());
    }
  }

  private static JSONObject identifierBody(String identifier) {
    String trimmed = identifier == null ? "" : identifier.trim();
    JSONObject body = new JSONObject();
    if (trimmed.contains("@")) {
      body.put("email", trimmed);
    } else {
      body.put("phone", trimmed);
    }
    return body;
  }

  private static String extractToken(JSONObject response, ZhiziApiException.Operation operation)
      throws ZhiziApiException {
    String token = response.optString("token", "");
    if (token.isEmpty()) {
      throw invalidResponse(operation);
    }
    return token;
  }

  private static String errorKey(
      String responseBody, int status, ZhiziApiException.Operation operation) {
    if (responseBody != null && !responseBody.isBlank()) {
      try {
        String key = new JSONObject(responseBody).optString("key", "").trim();
        if (!key.isEmpty()) {
          return key;
        }
      } catch (JSONException ignored) {
        // Some official failures, notably 401, are plain text rather than JSON.
      }
    }
    if (status != 401) {
      return "unknown_error";
    }
    if (operation == ZhiziApiException.Operation.LOGIN) {
      return "invalid_credentials";
    }
    if (operation == ZhiziApiException.Operation.FAST_LOGIN
        || operation == ZhiziApiException.Operation.RESET_PASSWORD) {
      return "invalid_verification_code";
    }
    return "unauthorized";
  }

  private static String requestId(HttpResponse<?> response) {
    for (String name : new String[] {"x-request-id", "x-correlation-id", "traceparent"}) {
      String value = response.headers().firstValue(name).orElse("").trim();
      if (!value.isEmpty()) {
        return value;
      }
    }
    return "";
  }

  private static long parseRetryAfter(HttpResponse<?> response) {
    String value = response.headers().firstValue("Retry-After").orElse("").trim();
    if (value.isEmpty()) {
      return 0L;
    }
    try {
      return Math.max(0L, Math.min(600L, Long.parseLong(value)));
    } catch (NumberFormatException ignored) {
      try {
        long seconds =
            Duration.between(
                    ZonedDateTime.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME))
                .getSeconds();
        return Math.max(0L, Math.min(600L, seconds));
      } catch (DateTimeParseException ignoredDate) {
        return 0L;
      }
    }
  }

  private static ZhiziApiException invalidResponse(ZhiziApiException.Operation operation) {
    return new ZhiziApiException(200, "invalid_response", "", 0, false, operation);
  }

  private static ZhiziApiException networkException(
      ZhiziApiException.Operation operation, IOException cause) {
    return new ZhiziApiException(
        0, "network_error", "", 0, operation.isIdempotent(), operation, cause);
  }

  private static int clampPageSize(int value) {
    return Math.max(1, Math.min(100, value));
  }

  private static BigDecimal decimal(JSONObject json, String key) {
    Object value = json == null ? null : json.opt(key);
    if (value == null || value == JSONObject.NULL) {
      return BigDecimal.ZERO;
    }
    try {
      if (value instanceof BigDecimal) {
        return (BigDecimal) value;
      }
      return new BigDecimal(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return BigDecimal.ZERO;
    }
  }

  private static Long positiveInteger(JSONObject json, String key) {
    Object value = json == null ? null : json.opt(key);
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    try {
      long parsed = new BigDecimal(String.valueOf(value).trim()).longValueExact();
      return parsed > 0L ? parsed : null;
    } catch (ArithmeticException | NumberFormatException ignored) {
      return null;
    }
  }

  private static Instant instant(JSONObject json, String key) {
    String value = json == null ? "" : json.optString(key, "").trim();
    if (value.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ignored) {
      try {
        return OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant();
      } catch (DateTimeParseException ignoredOffset) {
        return null;
      }
    }
  }

  public enum VerificationPurpose {
    FAST_LOGIN("fast_login"),
    RESET_PASSWORD("reset_password");

    private final String apiValue;

    VerificationPurpose(String apiValue) {
      this.apiValue = apiValue;
    }
  }

  public static final class CodeDelivery {
    public final long retryAfterSeconds;

    public CodeDelivery(long retryAfterSeconds) {
      this.retryAfterSeconds = Math.max(0L, Math.min(600L, retryAfterSeconds));
    }
  }

  public static final class SocketToken {
    public final String token;
    public final String socketIOURL;

    public SocketToken(String token, String socketIOURL) {
      this.token = token;
      this.socketIOURL = socketIOURL;
    }
  }

  public static final class ConnectAccount {
    public final String username;
    public final String password;

    public ConnectAccount(String username, String password) {
      this.username = username == null ? "" : username;
      this.password = password == null ? "" : password;
    }
  }

  public static final class AccountProfile {
    public final String phone;
    public final String email;
    public final boolean membership;
    public final Instant membershipExpiresAt;
    public final Boolean membershipAutoRenew;

    private AccountProfile(
        String phone,
        String email,
        boolean membership,
        Instant membershipExpiresAt,
        Boolean membershipAutoRenew) {
      this.phone = phone == null ? "" : phone;
      this.email = email == null ? "" : email;
      this.membership = membership;
      this.membershipExpiresAt = membershipExpiresAt;
      this.membershipAutoRenew = membershipAutoRenew;
    }

    private static AccountProfile fromJson(JSONObject json) {
      Object autoRenew = json.opt("membershipAutoRenew");
      return new AccountProfile(
          json.optString("phone", ""),
          json.optString("email", ""),
          json.optBoolean("isMembership", false),
          instant(json, "membershipExpiresAt"),
          autoRenew instanceof Boolean ? (Boolean) autoRenew : null);
    }

    public String identifier() {
      return !phone.isBlank() ? phone : email;
    }
  }

  public static final class BalanceInfo {
    public final BigDecimal remainingBalanceYuan;
    public final BigDecimal totalCashAmountYuan;
    public final BigDecimal yesterdayConsumptionYuan;
    public final BigDecimal totalConsumptionYuan;
    public final long totalDurationSeconds;
    public final long last24HoursShareDurationSeconds;
    public final long last24HoursVipDurationSeconds;
    public final int currentConnections;
    public final int currentNodes;

    private BalanceInfo(
        BigDecimal remainingBalanceYuan,
        BigDecimal totalCashAmountYuan,
        BigDecimal yesterdayConsumptionYuan,
        BigDecimal totalConsumptionYuan,
        long totalDurationSeconds,
        long last24HoursShareDurationSeconds,
        long last24HoursVipDurationSeconds,
        int currentConnections,
        int currentNodes) {
      this.remainingBalanceYuan = remainingBalanceYuan;
      this.totalCashAmountYuan = totalCashAmountYuan;
      this.yesterdayConsumptionYuan = yesterdayConsumptionYuan;
      this.totalConsumptionYuan = totalConsumptionYuan;
      this.totalDurationSeconds = Math.max(0L, totalDurationSeconds);
      this.last24HoursShareDurationSeconds = Math.max(0L, last24HoursShareDurationSeconds);
      this.last24HoursVipDurationSeconds = Math.max(0L, last24HoursVipDurationSeconds);
      this.currentConnections = Math.max(0, currentConnections);
      this.currentNodes = Math.max(0, currentNodes);
    }

    private static BalanceInfo fromJson(JSONObject json) {
      return new BalanceInfo(
          decimal(json, "remainingBalance"),
          decimal(json, "totalCashAmount"),
          decimal(json, "yesterdayConsumption"),
          decimal(json, "totalConsumption"),
          json.optLong("totalDuration", 0L),
          json.optLong("last24HrsShareDuration", 0L),
          json.optLong("last24HrsVIPShareDuration", 0L),
          json.optInt("currentNumOfMyConnections", 0),
          json.optInt("currentNumOfNodes", 0));
    }
  }

  public static final class UsageRecord {
    public final Instant startedAt;
    public final Instant endedAt;
    public final boolean finished;
    public final boolean ready;
    public final long durationSeconds;
    public final BigDecimal totalCostYuan;
    public final String gpuType;
    public final String engineType;
    public final boolean vip;
    public final boolean shared;

    private UsageRecord(
        Instant startedAt,
        Instant endedAt,
        boolean finished,
        boolean ready,
        long durationSeconds,
        BigDecimal totalCostYuan,
        String gpuType,
        String engineType,
        boolean vip,
        boolean shared) {
      this.startedAt = startedAt;
      this.endedAt = endedAt;
      this.finished = finished;
      this.ready = ready;
      this.durationSeconds = Math.max(0L, durationSeconds);
      this.totalCostYuan = totalCostYuan;
      this.gpuType = gpuType == null ? "" : gpuType;
      this.engineType = engineType == null ? "" : engineType;
      this.vip = vip;
      this.shared = shared;
    }

    private static UsageRecord fromJson(JSONObject json) {
      return new UsageRecord(
          instant(json, "startedAt"),
          instant(json, "endedAt"),
          json.optBoolean("finished", false),
          json.optBoolean("ready", false),
          json.optLong("duration", 0L),
          decimal(json, "totalCost"),
          json.optString("gpuType", ""),
          json.optString("engineType", ""),
          json.optBoolean("vip", false),
          json.optBoolean("share", false));
    }
  }

  public static final class UsagePage {
    public final long total;
    public final int page;
    public final int pageSize;
    public final List<UsageRecord> items;

    private UsagePage(long total, int page, int pageSize, List<UsageRecord> items) {
      this.total = Math.max(0L, total);
      this.page = Math.max(0, page);
      this.pageSize = Math.max(1, pageSize);
      this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static UsagePage fromJson(JSONObject json, int fallbackPage, int fallbackPageSize) {
      List<UsageRecord> items = new ArrayList<>();
      JSONArray array = json.optJSONArray("items");
      if (array != null) {
        for (int i = 0; i < array.length(); i++) {
          JSONObject item = array.optJSONObject(i);
          if (item != null) {
            items.add(UsageRecord.fromJson(item));
          }
        }
      }
      return new UsagePage(
          json.optLong("total", items.size()),
          json.optInt("page", fallbackPage),
          json.optInt("pageSize", fallbackPageSize),
          items);
    }
  }

  public static final class CreditRecord {
    public final String creditType;
    public final BigDecimal amountYuan;
    public final String source;
    public final String productName;
    public final Instant createdAt;

    private CreditRecord(
        String creditType,
        BigDecimal amountYuan,
        String source,
        String productName,
        Instant createdAt) {
      this.creditType = creditType == null ? "" : creditType;
      this.amountYuan = amountYuan;
      this.source = source == null ? "" : source;
      this.productName = productName == null ? "" : productName;
      this.createdAt = createdAt;
    }

    private static CreditRecord fromJson(JSONObject json) {
      return new CreditRecord(
          json.optString("creditType", ""),
          decimal(json, "amount"),
          json.optString("source", ""),
          json.optString("productName", ""),
          instant(json, "createdAt"));
    }
  }

  public static final class CreditPage {
    public final long total;
    public final int page;
    public final int pageSize;
    public final List<CreditRecord> items;

    private CreditPage(long total, int page, int pageSize, List<CreditRecord> items) {
      this.total = Math.max(0L, total);
      this.page = Math.max(0, page);
      this.pageSize = Math.max(1, pageSize);
      this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    private static CreditPage fromJson(JSONObject json, int fallbackPage, int fallbackPageSize) {
      List<CreditRecord> items = new ArrayList<>();
      JSONArray array = json.optJSONArray("items");
      if (array != null) {
        for (int i = 0; i < array.length(); i++) {
          JSONObject item = array.optJSONObject(i);
          if (item != null) {
            items.add(CreditRecord.fromJson(item));
          }
        }
      }
      return new CreditPage(
          json.optLong("total", items.size()),
          json.optInt("page", fallbackPage),
          json.optInt("pageSize", fallbackPageSize),
          items);
    }
  }

  public static final class MembershipProduct {
    private static final List<String> SUPPORTED_NAMES =
        List.of(
            "MEMBERSHIP_1_MONTH",
            "MEMBERSHIP_3_MONTH",
            "MEMBERSHIP_6_MONTH",
            "MEMBERSHIP_12_MONTH");

    public final String name;
    public final long priceFen;
    public final int durationMonths;

    private MembershipProduct(String name, long priceFen, int durationMonths) {
      this.name = name;
      this.priceFen = priceFen;
      this.durationMonths = durationMonths;
    }

    private static MembershipProduct fromJson(JSONObject json) {
      if (json == null || !"MEMBERSHIP".equals(json.optString("type", ""))) {
        return null;
      }
      String name = json.optString("name", "").trim();
      if (!SUPPORTED_NAMES.contains(name)) {
        return null;
      }
      Long priceFen = positiveInteger(json, "price");
      if (priceFen == null) {
        return null;
      }
      int start = "MEMBERSHIP_".length();
      int end = name.indexOf("_MONTH", start);
      try {
        int months = Integer.parseInt(name.substring(start, end));
        return new MembershipProduct(name, priceFen.longValue(), months);
      } catch (RuntimeException invalidName) {
        return null;
      }
    }

    public boolean isPurchasable() {
      return SUPPORTED_NAMES.contains(name) && priceFen > 0L && durationMonths > 0;
    }
  }

  public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAIL
  }

  public enum PaymentPurpose {
    BALANCE_TOP_UP,
    VIP_MEMBERSHIP
  }

  public static final class PaymentOrder {
    public final String id;
    public final long amountFen;
    public final String productName;
    public final PaymentPurpose purpose;
    public final PaymentStatus status;
    public final String codeUrl;
    public final Instant paidAt;
    public final Instant updatedAt;

    private PaymentOrder(
        String id,
        long amountFen,
        String productName,
        PaymentPurpose purpose,
        PaymentStatus status,
        String codeUrl,
        Instant paidAt,
        Instant updatedAt) {
      this.id = id;
      this.amountFen = amountFen;
      this.productName = productName;
      this.purpose = purpose;
      this.status = status;
      this.codeUrl = codeUrl;
      this.paidAt = paidAt;
      this.updatedAt = updatedAt;
    }

    private static PaymentOrder fromJson(JSONObject json, ZhiziApiException.Operation operation)
        throws ZhiziApiException {
      String id = json.optString("id", "").trim();
      Long amountFen = positiveInteger(json, "amount");
      String statusValue = json.optString("paidStatus", "").trim();
      PaymentStatus status;
      try {
        status = PaymentStatus.valueOf(statusValue);
      } catch (IllegalArgumentException invalidStatus) {
        throw invalidResponse(operation);
      }
      String productName = json.optString("productName", "").trim();
      String orderType = json.optString("orderType", "").trim();
      if (!orderType.isEmpty() && !"PURCHASE_PRODUCT".equals(orderType)) {
        throw invalidResponse(operation);
      }
      PaymentPurpose purpose =
          "PURCHASE_PRODUCT".equals(orderType) || !productName.isEmpty()
              ? PaymentPurpose.VIP_MEMBERSHIP
              : PaymentPurpose.BALANCE_TOP_UP;
      JSONObject nativePay = json.optJSONObject("nativePayRequest");
      String codeUrl = nativePay == null ? "" : nativePay.optString("codeURL", "").trim();
      if (!id.matches("[0-9a-fA-F]{24}") || amountFen == null) {
        throw invalidResponse(operation);
      }
      if (status == PaymentStatus.PENDING && !isSafeWechatCodeUrl(codeUrl)) {
        throw invalidResponse(operation);
      }
      return new PaymentOrder(
          id,
          amountFen.longValue(),
          productName,
          purpose,
          status,
          codeUrl,
          instant(json, "paidAt"),
          instant(json, "updatedAt"));
    }

    private static boolean isSafeWechatCodeUrl(String value) {
      return value != null
          && value.length() <= 2048
          && value.regionMatches(true, 0, "weixin://wxpay/", 0, "weixin://wxpay/".length());
    }
  }

  private static final class JsonResponse {
    private final JSONObject body;
    private final long retryAfterSeconds;

    private JsonResponse(JSONObject body, long retryAfterSeconds) {
      this.body = body;
      this.retryAfterSeconds = retryAfterSeconds;
    }
  }

  private static final class JsonValueResponse {
    private final Object body;
    private final long retryAfterSeconds;

    private JsonValueResponse(Object body, long retryAfterSeconds) {
      this.body = body;
      this.retryAfterSeconds = retryAfterSeconds;
    }
  }
}
