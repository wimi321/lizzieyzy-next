package featurecat.lizzie.util;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Public OGS player lookup and completed-game history; never follows server-provided URLs. */
public class OgsGameClient {
  public static final int PAGE_SIZE = 20;
  private static final String API = "https://online-go.com/api/v1/";
  private final Transport transport;

  @FunctionalInterface
  interface Transport {
    String get(URI uri) throws IOException;
  }

  public record Player(long id, String name) {}

  public record Game(
      long id, String ended, String black, String white, String result, String size) {}

  public record GamePage(List<Game> games, int page, int pages) {}

  public OgsGameClient() {
    this(OgsSgfClient::readResponse);
  }

  OgsGameClient(Transport transport) {
    this.transport = transport;
  }

  public Player findPlayer(String input) throws IOException {
    String query = input.trim();
    if (query.isEmpty()) return null;
    try {
      if (query.matches("[1-9][0-9]*")) {
        JSONObject json = json("players/" + query + "/");
        return player(json);
      }
      JSONObject json =
          json("players/?username=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
      JSONArray players = json.getJSONArray("results");
      for (int i = 0; i < players.length(); i++) {
        JSONObject candidate = players.getJSONObject(i);
        if (query.equalsIgnoreCase(candidate.getString("username"))) return player(candidate);
      }
      return null;
    } catch (JSONException | IllegalArgumentException e) {
      throw new IOException("Invalid OGS player response", e);
    }
  }

  private static Player player(JSONObject json) throws IOException {
    long id = json.getLong("id");
    if (id <= 0) throw new IOException("Invalid OGS player ID");
    return new Player(id, json.getString("username"));
  }

  public GamePage games(Player player, int page) throws IOException {
    if (player.id() <= 0 || page < 1) throw new IllegalArgumentException("Invalid page or player");
    try {
      JSONObject json =
          json(
              "players/"
                  + player.id()
                  + "/games/?ended__isnull=false&ordering=-ended&page_size="
                  + PAGE_SIZE
                  + "&page="
                  + page);
      JSONArray results = json.getJSONArray("results");
      List<Game> games = new ArrayList<>();
      for (int i = 0; i < results.length(); i++) {
        JSONObject game = results.getJSONObject(i);
        long id = game.getLong("id");
        if (id <= 0) throw new IOException("Invalid OGS game ID");
        JSONObject players = game.getJSONObject("players");
        games.add(
            new Game(
                id,
                game.getString("ended"),
                players.getJSONObject("black").getString("username"),
                players.getJSONObject("white").getString("username"),
                result(game),
                game.getInt("width") + "×" + game.getInt("height")));
      }
      long count = json.getLong("count");
      if (count < 0 || count > Integer.MAX_VALUE) throw new IOException("Invalid OGS game count");
      int pages = (int) Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
      return new GamePage(List.copyOf(games), page, pages);
    } catch (JSONException e) {
      throw new IOException("Invalid OGS game history response", e);
    }
  }

  static String result(JSONObject game) {
    String outcome = game.optString("outcome", "");
    if (game.optBoolean("annulled")) return "Void";
    boolean blackLost = game.optBoolean("black_lost");
    boolean whiteLost = game.optBoolean("white_lost");
    if (blackLost == whiteLost) return outcome;
    if (outcome.equalsIgnoreCase("Resignation")) outcome = "R";
    else if (outcome.equalsIgnoreCase("Timeout")) outcome = "T";
    return (blackLost ? "W+" : "B+") + outcome;
  }

  public String sgf(long gameId) throws IOException {
    if (gameId <= 0) throw new IllegalArgumentException("Invalid game ID");
    return OgsSgfClient.download(URI.create(API + "games/" + gameId + "/sgf"));
  }

  private JSONObject json(String path) throws IOException {
    return new JSONObject(transport.get(URI.create(API + path)));
  }
}
