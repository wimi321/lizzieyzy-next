package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GetTencentRequestTest {

  @Test
  void buildChessListUrlUsesTencentCgiContract() {
    String url =
        GetTencentRequest.buildChessListUrl(
            "测试 用户", "", "1778996987030102957", 25, "session token");

    assertTrue(
        url.startsWith("https://cgi.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChessList?"));
    assertTrue(url.contains("type=7"));
    assertTrue(url.contains("lastCode=1778996987030102957"));
    assertTrue(url.contains("username=%E6%B5%8B%E8%AF%95+%E7%94%A8%E6%88%B7"));
    assertTrue(url.contains("srcuid="));
    assertTrue(url.contains("txwqsession=session+token"));
    assertTrue(url.contains("fetchnum=25"));
  }

  @Test
  void numericTencentQueryIsAlsoUsedAsSrcUid() {
    assertEquals("1358592", GetTencentRequest.resolveSrcUid("1358592"));
    assertEquals("", GetTencentRequest.resolveSrcUid("腾讯昵称"));
  }

  @Test
  void buildChessDetailUrlUsesChessIdOnly() {
    String url = GetTencentRequest.buildChessDetailUrl("1778996477030103932");

    assertEquals(
        "https://happyapp.huanle.qq.com/cgi-bin/CommonMobileCGI/TXWQFetchChess?chessid=1778996477030103932",
        url);
  }
}
