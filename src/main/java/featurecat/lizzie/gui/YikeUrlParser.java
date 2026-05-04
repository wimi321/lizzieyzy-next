package featurecat.lizzie.gui;

import featurecat.lizzie.util.Utils;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YikeUrlParser {
  private static final Pattern NEW_LIVE_FULL =
      Pattern.compile(
          "https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(live/new-room/)([^/]+)/[0-9]+/([^/\\s]+)[^\\n]*");
  private static final Pattern NEW_LIVE_SHORT =
      Pattern.compile("https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(live/new-room/)([^/\\s]+)");
  private static final Pattern OLD_LIVE_FULL =
      Pattern.compile(
          "https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(live/room/)([^/]+)/[0-9]+/([^/\\s]+)[^\\n]*");
  private static final Pattern OLD_LIVE_SHORT =
      Pattern.compile("https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(live/room/)([^/\\s]+)");
  private static final Pattern GAME_ROOM =
      Pattern.compile(
          "https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(game/[a-zA-Z]+/)[0-9]+/([^/\\s]+)");
  private static final Pattern HALL_ROOM =
      Pattern.compile("https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(room=)([0-9]+)(&hall)(?s).*?");
  private static final Pattern UNITE_ROOM =
      Pattern.compile("https*://(?s).*?([^\\./]+\\.[^\\./]+)/(?s).*?(unite/)([0-9]+)(?s).*?");

  public static Optional<YikeUrlInfo> parse(String rawUrl) {
    if (Utils.isBlank(rawUrl)) return Optional.empty();
    String url = rawUrl.trim();
    if (url.endsWith("/0/0")) {
      url = url.substring(0, url.length() - 4);
    }

    Matcher matcher = NEW_LIVE_FULL.matcher(url);
    if (matcher.matches() && matcher.groupCount() >= 4) {
      String id = matcher.group(3);
      return Optional.of(
          new YikeUrlInfo(
              YikeUrlInfo.TYPE_NEW_LIVE_ROOM,
              id,
              parseLongOrDefault(matcher.group(4), parseLongOrDefault(id, 0)),
              YikeApiClient.detailUrl(id)));
    }

    matcher = NEW_LIVE_SHORT.matcher(url);
    if (matcher.matches() && matcher.groupCount() >= 3) {
      String id = matcher.group(3);
      return Optional.of(
          new YikeUrlInfo(
              YikeUrlInfo.TYPE_NEW_LIVE_ROOM,
              id,
              parseLongOrDefault(id, 0),
              YikeApiClient.detailUrl(id)));
    }

    matcher = OLD_LIVE_FULL.matcher(url);
    if (matcher.matches() && matcher.groupCount() >= 4) {
      int type = YikeUrlInfo.TYPE_OLD_LIVE_ROOM;
      String id = matcher.group(3);
      long roomId = parseLongOrDefault(matcher.group(4), -1);
      if (roomId < 0) {
        roomId = parseLongOrDefault(id, 0);
        type = YikeUrlInfo.TYPE_OLD_LIVE_BOARD;
      }
      if (!Utils.isBlank(id) && roomId > 0) {
        return Optional.of(
            new YikeUrlInfo(
                type,
                id,
                roomId,
                "https://api." + matcher.group(1) + "/golive/dtl?id=" + id + "&flag=1"));
      }
    }

    matcher = OLD_LIVE_SHORT.matcher(url);
    if (matcher.matches() && matcher.groupCount() >= 3) {
      String id = matcher.group(3);
      if (!Utils.isBlank(id)) {
        return Optional.of(
            new YikeUrlInfo(
                YikeUrlInfo.TYPE_OLD_LIVE_BOARD,
                id,
                parseLongOrDefault(id, 0),
                "https://api." + matcher.group(1) + "/golive/dtl?id=" + id));
      }
    }

    matcher = GAME_ROOM.matcher(url);
    if (matcher.matches() && matcher.groupCount() >= 3) {
      long roomId = parseLongOrDefault(matcher.group(3), 0);
      if (roomId > 0) {
        return Optional.of(
            new YikeUrlInfo(
                YikeUrlInfo.TYPE_GAME_ROOM,
                matcher.group(3),
                roomId,
                "https://api." + matcher.group(1) + "/golive/dtl?id=" + roomId));
      }
    }

    matcher = HALL_ROOM.matcher(url);
    if (matcher.matches() && matcher.groupCount() >= 3) {
      long roomId = parseLongOrDefault(matcher.group(3), 0);
      if (roomId > 0) {
        return Optional.of(
            new YikeUrlInfo(
                YikeUrlInfo.TYPE_GAME_ROOM,
                matcher.group(3),
                roomId,
                "https://api." + matcher.group(1) + "/golive/dtl?id=" + roomId));
      }
    }

    matcher = UNITE_ROOM.matcher(url);
    if (matcher.matches() && matcher.groupCount() >= 3) {
      long roomId = parseLongOrDefault(matcher.group(3), 0);
      if (roomId > 0) {
        return Optional.of(
            new YikeUrlInfo(
                YikeUrlInfo.TYPE_UNITE_ROOM,
                matcher.group(3),
                roomId,
                "https://game-server.yikeweiqi.com/game/info?id="
                    + roomId
                    + "&sgf_option=true&players_option=true&setting_option=true&clock_option=true&is_void=true"));
      }
    }

    return Optional.empty();
  }

  private static long parseLongOrDefault(String value, long fallback) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
