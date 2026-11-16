package featurecat.lizzie.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class OgsGameClientTest {
  @Test
  void resolvesExactUsernameWithEncodedQueryOrNumericId() throws Exception {
    List<URI> requests = new ArrayList<>();
    OgsGameClient client =
        new OgsGameClient(
            uri -> {
              requests.add(uri);
              return uri.getQuery() == null
                  ? "{\"id\":12,\"username\":\"Alice\"}"
                  : "{\"results\":[{\"id\":12,\"username\":\"Alice & Bob\"}]}";
            });
    assertEquals(12, client.findPlayer(" Alice & Bob ").id());
    assertEquals("username=Alice+%26+Bob", requests.get(0).getRawQuery());
    assertEquals("Alice", client.findPlayer("12").name());
    assertEquals("/api/v1/players/12/", requests.get(1).getPath());
  }

  @Test
  void noMatchAndInvalidJsonNeverSelectAnotherPlayer() throws Exception {
    OgsGameClient unrelated =
        new OgsGameClient(uri -> "{\"results\":[{\"id\":1,\"username\":\"other\"}]}");
    assertNull(unrelated.findPlayer("alice"));
    assertNull(unrelated.findPlayer(" "));
    assertThrows(
        IOException.class,
        () -> new OgsGameClient(uri -> "<html>error</html>").findPlayer("alice"));
    assertThrows(
        IOException.class,
        () -> new OgsGameClient(uri -> "{\"id\":0,\"username\":\"bad\"}").findPlayer("12"));
  }

  @Test
  void parsesPagedCompletedGamesAndConstructsTrustedPageRequests() throws Exception {
    List<URI> requests = new ArrayList<>();
    OgsGameClient client =
        new OgsGameClient(
            uri -> {
              requests.add(uri);
              return """
          {"count":41,"next":"https://untrusted.test/page","results":[
          {"id":99,"ended":"2026-01-01T12:00:00Z","players":{
          "black":{"username":"黒"},"white":{"username":"白"}},
          "black_lost":false,"white_lost":true,"outcome":"Resignation","width":19,"height":19}]}
          """;
            });
    var player = new OgsGameClient.Player(12, "alice");
    var page = client.games(player, 2);
    assertEquals(2, page.page());
    assertEquals(3, page.pages());
    assertEquals(99, page.games().get(0).id());
    assertEquals("黒", page.games().get(0).black());
    assertEquals("B+R", page.games().get(0).result());
    assertEquals("19×19", page.games().get(0).size());
    assertEquals("online-go.com", requests.get(0).getHost());
    assertTrue(requests.get(0).getQuery().contains("ended__isnull=false"));
    assertTrue(requests.get(0).getQuery().contains("page=2"));
    assertThrows(IllegalArgumentException.class, () -> client.games(player, 0));
  }

  @Test
  void emptyAndMalformedPagesAreDistinguished() throws Exception {
    var player = new OgsGameClient.Player(12, "alice");
    var page = new OgsGameClient(uri -> "{\"count\":0,\"results\":[]}").games(player, 1);
    assertTrue(page.games().isEmpty());
    assertEquals(1, page.pages());
    assertThrows(
        IOException.class, () -> new OgsGameClient(uri -> "{\"count\":2}").games(player, 1));
  }

  @Test
  void formatsWinnerTimeoutPointsAndAnnulledGames() {
    JSONObject game = new JSONObject().put("black_lost", true).put("white_lost", false);
    assertEquals("W+T", OgsGameClient.result(game.put("outcome", "Timeout")));
    assertEquals("W+6.5", OgsGameClient.result(game.put("outcome", "6.5")));
    assertEquals("Void", OgsGameClient.result(game.put("annulled", true)));
  }
}
