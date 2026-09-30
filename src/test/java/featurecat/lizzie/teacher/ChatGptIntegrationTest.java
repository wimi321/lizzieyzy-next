package featurecat.lizzie.teacher;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import featurecat.lizzie.analysis.remote.CredentialStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatGptIntegrationTest {
  @TempDir Path directory;
  HttpServer server;
  RSAKey key;
  URI origin;
  ChatGptHttp http;
  MemoryStore store = new MemoryStore();
  ChatGptSessions sessions;
  Map<String, String> auth;
  String scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
  String subject = "subject-one";
  String workspaceSuffix = "";
  int expires = 3600;
  int revokeStatus = 200;
  AtomicInteger tokenCalls = new AtomicInteger();
  AtomicInteger refreshCalls = new AtomicInteger();
  AtomicInteger responseCalls = new AtomicInteger();
  String modelList =
      "{\"models\":[{\"slug\":\"hidden\",\"visibility\":\"hide\"},{\"slug\":\"m2\",\"display_name\":\"Model Two\",\"visibility\":\"list\"},{\"slug\":\"m1\",\"visibility\":\"list\"}]}";

  @BeforeEach
  void setup() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    http = new ChatGptHttp(origin, URI.create(origin + "/v1"));
    sessions = new ChatGptSessions(directory, store, http);
    server.createContext(
        "/.well-known/jwks.json", x -> reply(x, 200, new JWKSet(key.toPublicJWK()).toString()));
    server.createContext(
        "/.well-known/openid-configuration",
        x ->
            reply(
                x,
                200,
                new JSONObject().put("revocation_endpoint", origin + "/revoke").toString()));
    server.createContext("/revoke", x -> reply(x, revokeStatus, ""));
    server.createContext(
        "/api/accounts/oauth/token",
        x -> {
          tokenCalls.incrementAndGet();
          try {
            Map<String, String> form =
                ChatGptSignIn.query(
                    new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean refresh = "refresh_token".equals(form.get("grant_type"));
            if (refresh) refreshCalls.incrementAndGet();
            else {
              assertEquals(
                  auth.get("code_challenge"), ChatGptSignIn.challenge(form.get("code_verifier")));
              assertEquals(auth.get("redirect_uri"), form.get("redirect_uri"));
              assertNotEquals("dynamic_agent_client", form.get("client_id"));
            }
            JSONObject tokens =
                new JSONObject()
                    .put("access_token", "private-access")
                    .put("refresh_token", "private-refresh-" + refreshCalls.get())
                    .put("scope", scope)
                    .put("token_type", "Bearer")
                    .put("expires_in", refresh ? 3600 : expires);
            if (!refresh)
              tokens.put(
                  "id_token",
                  jwt(
                      form.get("client_id"),
                      auth.get("nonce"),
                      subject,
                      origin.toString(),
                      Instant.now().plusSeconds(3600)));
            reply(x, 200, tokens.toString());
          } catch (Throwable error) {
            reply(x, 500, "fixture failure");
          }
        });
    server.createContext(
        "/v1/models",
        x -> {
          assertEquals("Bearer private-access", x.getRequestHeaders().getFirst("Authorization"));
          reply(x, 200, modelList);
        });
    server.createContext(
        "/v1/responses",
        x -> {
          responseCalls.incrementAndGet();
          JSONObject body =
              new JSONObject(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          assertFalse(body.getBoolean("store"));
          assertTrue(body.getBoolean("stream"));
          assertEquals("developer", body.getJSONArray("input").getJSONObject(0).getString("role"));
          reply(
              x,
              200,
              "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Go explanation\"}\n\n"
                  + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n");
        });
    server.start();
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
  }

  private String jwt(String client, String nonce, String sub, String issuer, Instant expiry)
      throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(sub)
                .audience(client)
                .expirationTime(Date.from(expiry))
                .claim("nonce", nonce)
                .claim("email", "same@example.test")
                .build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  ChatGptSessions.Account login(String prior) throws Exception {
    ChatGptSignIn attempt = sessions.signIn(prior);
    auth = ChatGptSignIn.query(attempt.authorization.getRawQuery());
    String client =
        prior == null ? "oaiapp_" + subject + workspaceSuffix : sessions.clientId(prior);
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        auth.get("redirect_uri")
                            + "?"
                            + ChatGptHttp.form(
                                Map.of(
                                    "state",
                                    auth.get("state"),
                                    "code",
                                    "single-use-code",
                                    "client_id",
                                    client))))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.discarding());
    return attempt.result.get(10, TimeUnit.SECONDS);
  }

  @Test
  void registersUsingPkceAndRestoresWithoutPlaintextSecrets() throws Exception {
    ChatGptSessions.Account account = login(null);
    assertTrue(account.signedIn);
    assertTrue(account.authorized);
    assertFalse(account.sessionOnly);
    assertEquals("dynamic_agent_client", auth.get("client_id"));
    assertEquals("S256", auth.get("code_challenge_method"));
    assertEquals("LizzieYzy Next", auth.get("agent_name_hint"));
    ChatGptSessions reopened = new ChatGptSessions(directory, store, http);
    assertEquals(sessions.hostId(), reopened.hostId());
    assertEquals(account.id, reopened.active().id);
    assertEquals("private-access", reopened.accessToken(account.id));
    String disk = Files.readString(directory.resolve("accounts.json"));
    assertFalse(disk.contains("private-access"));
    assertFalse(disk.contains("private-refresh"));
    assertFalse(disk.contains("id_token"));
    assertEquals(1, tokenCalls.get());
  }

  @Test
  void accountSwitchDoesNotMergeSameEmailsOrDestroyOtherCredentials() throws Exception {
    var first = login(null);
    subject = "subject-two";
    var second = login(null);
    assertNotEquals(first.id, second.id);
    assertEquals(first.email, second.email);
    sessions.select(first.id);
    assertEquals("private-access", sessions.accessToken(first.id));
    assertEquals("private-access", sessions.accessToken(second.id));
    assertEquals(2, sessions.list().size());
  }

  @Test
  void reauthorizationReusesIssuedClientAndHost() throws Exception {
    var first = login(null);
    String host = sessions.hostId();
    var again = login(first.id);
    assertEquals(first.id, again.id);
    assertEquals(host, auth.get("ext_agent_host_id"));
    assertEquals("oaiapp_subject-one", auth.get("client_id"));
    assertFalse(auth.containsKey("agent_name_hint"));
  }

  @Test
  void missingPlanPermissionKeepsIdentityButNeverSendsInference() throws Exception {
    scope = "openid email profile";
    var account = login(null);
    assertTrue(account.signedIn);
    assertFalse(account.authorized);
    assertThrows(IOException.class, () -> sessions.accessToken(account.id));
    assertEquals(0, responseCalls.get());
  }

  @Test
  void unavailableStoreIsSessionOnlyAndDoesNotSurviveRestart() throws Exception {
    store.available = false;
    var account = login(null);
    assertTrue(account.signedIn);
    assertTrue(account.sessionOnly);
    assertEquals("private-access", sessions.accessToken(account.id));
    var reopened = new ChatGptSessions(directory, store, http);
    assertFalse(reopened.active().signedIn);
    assertThrows(IOException.class, () -> reopened.accessToken(account.id));
  }

  @Test
  void failedKeychainWriteFallsBackToMemoryNotPlaintext() throws Exception {
    store.failWrite = true;
    var account = login(null);
    assertTrue(account.sessionOnly);
    assertFalse(Files.readString(directory.resolve("accounts.json")).contains("private-access"));
  }

  @Test
  void refreshIsSerializedAcrossIndependentManagers() throws Exception {
    expires = 1;
    var account = login(null);
    var other = new ChatGptSessions(directory, store, http);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var a = workers.submit(() -> sessions.accessToken(account.id));
      var b = workers.submit(() -> other.accessToken(account.id));
      assertEquals("private-access", a.get(5, TimeUnit.SECONDS));
      assertEquals("private-access", b.get(5, TimeUnit.SECONDS));
      assertEquals(1, refreshCalls.get());
      assertTrue(store.values.values().iterator().next().contains("private-refresh-1"));
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void logoutTombstoneSurvivesUnavailableKeychainAndFailedRevocation() throws Exception {
    var account = login(null);
    store.failDelete = true;
    revokeStatus = 503;
    assertFalse(sessions.signOut(account.id));
    assertFalse(new ChatGptSessions(directory, store, http).active().signedIn);
    assertEquals("oaiapp_subject-one", sessions.clientId(account.id));
  }

  @Test
  void logoutRevokesAndClearsAllTokens() throws Exception {
    var account = login(null);
    assertTrue(sessions.signOut(account.id));
    assertTrue(store.values.isEmpty());
    assertFalse(sessions.active().signedIn);
  }

  @Test
  void invalidCallbackCannotConsumeValidAttempt() throws Exception {
    try (var attempt = sessions.signIn(null)) {
      auth = ChatGptSignIn.query(attempt.authorization.getRawQuery());
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create(auth.get("redirect_uri") + "?state=wrong&code=wrong"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding());
      assertEquals(400, response.statusCode());
      assertFalse(attempt.result.isDone());
      assertEquals(0, tokenCalls.get());
    }
  }

  @Test
  void duplicateClickReusesAttemptAndCancelCreatesNoAccount() throws Exception {
    var attempt = sessions.signIn(null);
    assertSame(attempt, sessions.signIn(null));
    attempt.close();
    assertTrue(attempt.result.isCancelled());
    assertNull(sessions.active());
    assertEquals(0, tokenCalls.get());
  }

  @Test
  void deniedAuthorizationDoesNotExchangeOrPersistTokens() throws Exception {
    try (var attempt = sessions.signIn(null)) {
      auth = ChatGptSignIn.query(attempt.authorization.getRawQuery());
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(
                      URI.create(
                          auth.get("redirect_uri")
                              + "?"
                              + ChatGptHttp.form(
                                  Map.of("state", auth.get("state"), "error", "access_denied"))))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> attempt.result.get(5, TimeUnit.SECONDS));
      assertEquals(0, tokenCalls.get());
      assertTrue(store.values.isEmpty());
      assertNull(sessions.active());
    }
  }

  @Test
  void duplicateCallbackParametersAreRejectedBeforeTokenExchange() throws Exception {
    try (var attempt = sessions.signIn(null)) {
      auth = ChatGptSignIn.query(attempt.authorization.getRawQuery());
      var result =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create(
                              auth.get("redirect_uri")
                                  + "?state="
                                  + auth.get("state")
                                  + "&state=attacker&code=wrong"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding());
      assertEquals(400, result.statusCode());
      assertEquals(0, tokenCalls.get());
    }
  }

  @Test
  void refreshPermissionChangesAreHonoredWithoutApiFallback() throws Exception {
    expires = 1;
    var account = login(null);
    scope = "openid email";
    assertThrows(IOException.class, () -> sessions.accessToken(account.id));
    assertFalse(sessions.active().authorized);
    assertEquals(0, responseCalls.get());
  }

  @Test
  void missingOrNonDisplayableModelsAreNotGuessed() throws Exception {
    var account = login(null);
    modelList = "{\"data\":[{\"id\":\"not-a-plan-model\"}]}";
    assertThrows(
        IOException.class, () -> new ChatGptCommentaryClient(sessions, account.id, "").models());
    modelList = "{\"models\":[{\"slug\":\"hidden\",\"visibility\":\"hide\"}]}";
    assertThrows(
        IOException.class, () -> new ChatGptCommentaryClient(sessions, account.id, "").models());
    assertEquals(0, responseCalls.get());
  }

  @Test
  void accountSwitchCancelsAnActiveStreamBeforeCompletion() throws Exception {
    var account = login(null);
    var cancellation = new TeacherLlmClient.Cancellation();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            new ChatGptCommentaryClient(sessions, account.id, "m2")
                .stream(
                    List.of(new TeacherLlmClient.Message("system", "Use evidence")),
                    cancellation,
                    text -> {
                      try {
                        sessions.select(account.id);
                      } catch (IOException failure) {
                        throw new AssertionError(failure);
                      }
                    }));
    assertTrue(cancellation.isCancelled());
    assertEquals(1, responseCalls.get());
  }

  @Test
  void identityNamespacesDoNotReadOtherProvidersSecrets() throws Exception {
    store.write(CredentialStore.Kind.API_KEY, "account", "api-secret");
    var account = login(null);
    sessions.signOut(account.id);
    assertEquals("api-secret", store.read(CredentialStore.Kind.API_KEY, "account").orElseThrow());
    assertEquals(1, store.values.size());
  }

  @Test
  void reconsentIsOnlyRequestedForMissingPlanScope() throws Exception {
    scope = "openid profile email";
    var account = login(null);
    try (var attempt = sessions.signIn(account.id)) {
      assertEquals(
          "consent", ChatGptSignIn.query(attempt.authorization.getRawQuery()).get("prompt"));
    }
  }

  @Test
  void usageLimitDuringStreamingHasActionableErrorAndNoSuccess() throws Exception {
    String stream =
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
            + "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"private-server-detail\"}}}\n\n";
    var error =
        assertThrows(
            ChatGptApiException.class,
            () ->
                ChatGptCommentaryClient.readStream(
                    new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                    new TeacherLlmClient.Cancellation(),
                    ignored -> {}));
    assertEquals("subscription_sharing_usage_limit_exceeded", error.code);
    assertEquals(ChatGptHttp.error("limit").getMessage(), error.getMessage());
    assertFalse(error.toString().contains("private-server-detail"));
  }

  @Test
  void revokedRefreshTokensAreClearedButRegistrationSurvives() throws Exception {
    expires = 1;
    var account = login(null);
    server.removeContext("/api/accounts/oauth/token");
    server.createContext(
        "/api/accounts/oauth/token",
        x ->
            reply(
                x,
                400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"private-refresh-data\"}"));
    var error = assertThrows(ChatGptApiException.class, () -> sessions.accessToken(account.id));
    assertTrue(error.refreshRevoked());
    assertTrue(store.values.isEmpty());
    assertFalse(sessions.active().signedIn);
    assertEquals("oaiapp_subject-one", sessions.clientId(account.id));
    assertFalse(error.toString().contains("private-refresh-data"));
  }

  @Test
  void transientRefreshFailurePreservesCredentials() throws Exception {
    expires = 1;
    var account = login(null);
    server.removeContext("/api/accounts/oauth/token");
    server.createContext(
        "/api/accounts/oauth/token", x -> reply(x, 503, "{\"detail\":\"try later\"}"));
    assertThrows(ChatGptApiException.class, () -> sessions.accessToken(account.id));
    assertFalse(store.values.isEmpty());
    assertTrue(sessions.active().signedIn);
  }

  @Test
  void timeoutClosesListenerAndDoesNotCreateAccount() throws Exception {
    try (var attempt = new ChatGptSignIn(sessions, null, Duration.ofMillis(60))) {
      assertThrows(
          java.util.concurrent.ExecutionException.class,
          () -> attempt.result.get(2, TimeUnit.SECONDS));
      assertNull(sessions.active());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"issuer", "audience", "nonce", "subject", "expiry", "signature"})
  void rejectsInvalidIdentity(String fault) throws Exception {
    String jwt =
        jwt(
            fault.equals("audience") ? "another" : "client",
            "nonce",
            "subject",
            fault.equals("issuer") ? "https://evil.test" : origin.toString(),
            Instant.now().plusSeconds(fault.equals("expiry") ? -60 : 3600));
    String keys =
        fault.equals("signature")
            ? new JWKSet(new RSAKeyGenerator(2048).keyID("test-key").generate().toPublicJWK())
                .toString()
            : new JWKSet(key.toPublicJWK()).toString();
    assertThrows(
        IOException.class,
        () ->
            ChatGptIdentity.verify(
                jwt,
                keys,
                origin.toString(),
                "client",
                fault.equals("nonce") ? "wrong" : "nonce",
                fault.equals("subject") ? "wrong" : "subject",
                Instant.now()));
  }

  @Test
  void modelDiscoveryUsesAccountCatalogOrderAndNotApiKeyShape() throws Exception {
    var account = login(null);
    var models = new ChatGptCommentaryClient(sessions, account.id, "").models();
    assertEquals(List.of("m2", "m1"), models.stream().map(m -> m.slug).toList());
    assertEquals("Model Two", models.get(0).toString());
    assertEquals(0, responseCalls.get());
  }

  @Test
  void responsesRequestUsesPlanContractAndCompletesExactlyOnce() throws Exception {
    var account = login(null);
    String text =
        new ChatGptCommentaryClient(sessions, account.id, "m2")
            .stream(
                List.of(
                    new TeacherLlmClient.Message("system", "Use KataGo evidence"),
                    new TeacherLlmClient.Message("user", "Explain")),
                new TeacherLlmClient.Cancellation(),
                ignored -> {});
    assertEquals("Go explanation", text);
    assertEquals(1, responseCalls.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "data: [DONE]\n\n",
        "data: {\"type\":\"response.failed\"}\n\n",
        "data: {\"type\":\"response.incomplete\"}\n\n"
      })
  void incompleteStreamIsNeverSuccessful(String ending) {
    byte[] bytes =
        ("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n" + ending)
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        IOException.class,
        () ->
            ChatGptCommentaryClient.readStream(
                new ByteArrayInputStream(bytes),
                new TeacherLlmClient.Cancellation(),
                ignored -> {}));
  }

  @Test
  void changedAccountInvalidatesOldClientBeforeRequest() throws Exception {
    var account = login(null);
    var client = new ChatGptCommentaryClient(sessions, account.id, "m1");
    sessions.select(account.id);
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            client.stream(
                List.of(new TeacherLlmClient.Message("user", "Explain")),
                new TeacherLlmClient.Cancellation(),
                ignored -> {}));
    assertEquals(0, responseCalls.get());
  }

  @Test
  void providerSelectionPreservesApiCredentials() throws Exception {
    var settings = new TeacherSettings(directory.resolve("teacher.properties"), store);
    assertEquals(TeacherSettings.Provider.UNSELECTED, settings.load().provider);
    settings.save(
        "https://provider.example/v1", "custom-model", "api-only-secret".toCharArray(), true);
    settings.selectProvider(TeacherSettings.Provider.API_KEY);
    settings.selectProvider(TeacherSettings.Provider.CHATGPT);
    var reopened = new TeacherSettings(directory.resolve("teacher.properties"), store);
    assertEquals(TeacherSettings.Provider.CHATGPT, reopened.load().provider);
    assertEquals("api-only-secret", reopened.apiKey().orElseThrow());
    assertEquals("custom-model", reopened.snapshot().model);
  }

  @Test
  void legacySettingsKeepApiProvider() throws Exception {
    Files.writeString(
        directory.resolve("teacher.properties"),
        "model=legacy\nbaseUrl=https://provider.example/v1\n");
    var settings = new TeacherSettings(directory.resolve("teacher.properties"), store);
    assertEquals(TeacherSettings.Provider.API_KEY, settings.load().provider);
  }

  @Test
  void failedCodeExchangeRetainsIssuedRegistrationWithoutChangingActiveAccount() throws Exception {
    var existing = login(null);
    subject = "subject-two";
    server.removeContext("/api/accounts/oauth/token");
    server.createContext(
        "/api/accounts/oauth/token", x -> reply(x, 400, "{\"error\":\"invalid_grant\"}"));
    assertThrows(java.util.concurrent.ExecutionException.class, () -> login(null));
    assertEquals(existing.id, sessions.active().id);
    var reopened = new ChatGptSessions(directory, store, http);
    try (var attempt = reopened.signIn(null)) {
      var query = ChatGptSignIn.query(attempt.authorization.getRawQuery());
      assertEquals("oaiapp_subject-two", query.get("client_id"));
      assertFalse(query.containsKey("agent_name_hint"));
      assertEquals(existing.id, reopened.active().id);
    }
    assertFalse(Files.readString(directory.resolve("accounts.json")).contains("single-use-code"));
  }

  @Test
  void cancelledCodeExchangeCannotCommitLateTokens() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var finished = new java.util.concurrent.CountDownLatch(1);
    server.removeContext("/api/accounts/oauth/token");
    server.createContext(
        "/api/accounts/oauth/token",
        x -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
            reply(
                x,
                200,
                new JSONObject()
                    .put("access_token", "late-secret")
                    .put("refresh_token", "late-refresh")
                    .put("token_type", "Bearer")
                    .put("scope", scope)
                    .put("expires_in", 3600)
                    .put(
                        "id_token",
                        jwt(
                            "oaiapp_subject-one",
                            auth.get("nonce"),
                            subject,
                            origin.toString(),
                            Instant.now().plusSeconds(3600)))
                    .toString());
          } catch (Exception failed) {
            x.close();
          } finally {
            finished.countDown();
          }
        });
    try (var attempt = sessions.signIn(null)) {
      auth = ChatGptSignIn.query(attempt.authorization.getRawQuery());
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(
                      URI.create(
                          auth.get("redirect_uri")
                              + "?"
                              + ChatGptHttp.form(
                                  Map.of(
                                      "state",
                                      auth.get("state"),
                                      "code",
                                      "one-use",
                                      "client_id",
                                      "oaiapp_subject-one"))))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      attempt.close();
      release.countDown();
      assertTrue(finished.await(3, TimeUnit.SECONDS));
      Thread.sleep(100);
      assertTrue(attempt.result.isCancelled());
      assertNull(sessions.active());
      assertTrue(store.values.isEmpty());
    } finally {
      release.countDown();
    }
  }

  @Test
  void identityOnlyGrantIsRetainedWithoutPretendingPlanAccess() throws Exception {
    server.removeContext("/api/accounts/oauth/token");
    server.createContext(
        "/api/accounts/oauth/token",
        x -> {
          try {
            reply(
                x,
                200,
                new JSONObject()
                    .put("scope", "openid email profile")
                    .put(
                        "id_token",
                        jwt(
                            "oaiapp_subject-one",
                            auth.get("nonce"),
                            subject,
                            origin.toString(),
                            Instant.now().plusSeconds(3600)))
                    .toString());
          } catch (Exception failure) {
            reply(x, 500, "fixture error");
          }
        });
    var account = login(null);
    assertTrue(account.signedIn);
    assertFalse(account.authorized);
    assertTrue(new ChatGptSessions(directory, store, http).active().signedIn);
    assertThrows(IOException.class, () -> sessions.accessToken(account.id));
    assertEquals(0, responseCalls.get());
  }

  @Test
  void sameIdentityInDifferentWorkspacesHasIndependentModelsAndCredentials() throws Exception {
    var first = login(null);
    sessions.model(first.id, "model-a");
    workspaceSuffix = "_workspace_b";
    var second = login(null);
    sessions.model(second.id, "model-b");
    assertNotEquals(first.id, second.id);
    assertEquals(sessions.subject(first.id), sessions.subject(second.id));
    assertNotEquals(sessions.clientId(first.id), sessions.clientId(second.id));
    sessions.signOut(second.id);
    sessions.select(first.id);
    assertEquals("model-a", sessions.active().model);
    assertEquals("private-access", sessions.accessToken(first.id));
    assertEquals(1, store.values.size());
  }

  @Test
  void malformedRevocationDiscoveryCannotPreventLocalLogout() throws Exception {
    var account = login(null);
    server.removeContext("/.well-known/openid-configuration");
    server.createContext(
        "/.well-known/openid-configuration",
        x -> reply(x, 200, "{\"revocation_endpoint\":\"not a valid endpoint\"}"));
    assertFalse(sessions.signOut(account.id));
    assertFalse(sessions.active().signedIn);
    assertTrue(store.values.isEmpty());
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  static final class MemoryStore implements CredentialStore {
    final Map<String, String> values = new ConcurrentHashMap<>();
    boolean available = true, failWrite, failDelete;

    public String backendName() {
      return "test";
    }

    public boolean isAvailable() {
      return available;
    }

    public Optional<String> read(Kind kind, String account) {
      return Optional.ofNullable(values.get(kind + account));
    }

    public void write(Kind kind, String account, String secret) throws IOException {
      if (!available || failWrite) throw new IOException("test unavailable");
      values.put(kind + account, secret);
    }

    public void delete(Kind kind, String account) throws IOException {
      if (failDelete) throw new IOException("test unavailable");
      values.remove(kind + account);
    }
  }
}
