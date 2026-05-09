package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class YikeUrlParserTest {
  @Test
  void parsesOldLiveRoomUrl() {
    Optional<YikeUrlInfo> parsed =
        YikeUrlParser.parse("https://home.yikeweiqi.com/#/live/room/18328/1/15630642");

    assertTrue(parsed.isPresent());
    YikeUrlInfo info = parsed.get();
    assertEquals(YikeUrlInfo.TYPE_OLD_LIVE_ROOM, info.getType());
    assertEquals("18328", info.getId());
    assertEquals(15630642, info.getRoomId());
    assertEquals("https://api.yikeweiqi.com/golive/dtl?id=18328&flag=1", info.getAjaxUrl());
  }

  @Test
  void parsesOldLiveBoardUrlWithZeroRoomSuffix() {
    Optional<YikeUrlInfo> parsed =
        YikeUrlParser.parse("https://home.yikeweiqi.com/#/live/room/4903/0/0");

    assertTrue(parsed.isPresent());
    YikeUrlInfo info = parsed.get();
    assertEquals(YikeUrlInfo.TYPE_OLD_LIVE_BOARD, info.getType());
    assertEquals("4903", info.getId());
    assertEquals("https://api.yikeweiqi.com/golive/dtl?id=4903", info.getAjaxUrl());
  }

  @Test
  void parsesNewLiveRoomUrl() {
    Optional<YikeUrlInfo> parsed =
        YikeUrlParser.parse("https://home.yikeweiqi.com/#/live/new-room/186031/0/0");

    assertTrue(parsed.isPresent());
    YikeUrlInfo info = parsed.get();
    assertEquals(YikeUrlInfo.TYPE_NEW_LIVE_ROOM, info.getType());
    assertEquals("186031", info.getId());
    assertEquals("https://api-new.yikeweiqi.com/v1/golives/186031", info.getAjaxUrl());
  }

  @Test
  void parsesYikeGameRoomUrl() {
    Optional<YikeUrlInfo> parsed =
        YikeUrlParser.parse("https://home.yikeweiqi.com/#/game/play/1/15630642");

    assertTrue(parsed.isPresent());
    YikeUrlInfo info = parsed.get();
    assertEquals(YikeUrlInfo.TYPE_GAME_ROOM, info.getType());
    assertEquals(15630642, info.getRoomId());
    assertEquals("https://api.yikeweiqi.com/golive/dtl?id=15630642", info.getAjaxUrl());
  }

  @Test
  void parsesYikeUniteRoomUrl() {
    Optional<YikeUrlInfo> parsed =
        YikeUrlParser.parse("https://home.yikeweiqi.com/#/unite/66304678");

    assertTrue(parsed.isPresent());
    YikeUrlInfo info = parsed.get();
    assertEquals(YikeUrlInfo.TYPE_UNITE_ROOM, info.getType());
    assertEquals(66304678, info.getRoomId());
    assertEquals(
        "https://game-server.yikeweiqi.com/game/info?id=66304678&sgf_option=true&players_option=true&setting_option=true&clock_option=true&is_void=true",
        info.getAjaxUrl());
  }

  @Test
  void doesNotParseYikeLobbyUrls() {
    assertTrue(YikeUrlParser.parse("https://home.yikeweiqi.com/#/live").isEmpty());
    assertTrue(YikeUrlParser.parse("https://home.yikeweiqi.com/#/game").isEmpty());
  }

  @Test
  void buildsStableSessionKeysForRoomUrls() {
    assertEquals(
        "live-room:186031",
        OnlineDialog.yikeSessionKey("https://home.yikeweiqi.com/#/live/new-room/186031/0/0"));
    assertEquals(
        "unite-board:66304678",
        OnlineDialog.yikeSessionKey("https://home.yikeweiqi.com/#/unite/66304678"));
    assertEquals("", OnlineDialog.yikeSessionKey("https://home.yikeweiqi.com/#/live"));
  }
}
