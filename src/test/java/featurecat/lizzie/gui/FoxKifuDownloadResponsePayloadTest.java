package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class FoxKifuDownloadResponsePayloadTest {
  @Test
  void successPayloadWithoutGameListOrRecordIsFlaggedAsEmpty() {
    assertTrue(FoxKifuDownload.isFoxPayloadWithoutContent(new JSONObject("{\"result\":0}")));
  }

  @Test
  void errcodeStyleBodyWithoutContentIsFlaggedAsEmpty() {
    assertTrue(
        FoxKifuDownload.isFoxPayloadWithoutContent(
            new JSONObject("{\"errcode\":5,\"errmsg\":\"server busy\"}")));
  }

  @Test
  void chessListPayloadHasContent() {
    assertFalse(
        FoxKifuDownload.isFoxPayloadWithoutContent(
            new JSONObject("{\"result\":0,\"chesslist\":[]}")));
  }

  @Test
  void chessRecordPayloadHasContent() {
    assertFalse(
        FoxKifuDownload.isFoxPayloadWithoutContent(
            new JSONObject("{\"result\":0,\"chess\":\"(;GM[1]FF[4]SZ[19])\"}")));
  }

  @Test
  void onlyPageRequestFailuresMayRevertThePaginationCursor() {
    assertTrue(
        FoxKifuDownload.isPageRequestFailure(
            new JSONObject("{\"result\":1,\"resultstr\":\"timeout\",\"fox_action\":\"uid\"}")));
    assertFalse(
        FoxKifuDownload.isPageRequestFailure(
            new JSONObject("{\"result\":1,\"resultstr\":\"timeout\",\"fox_action\":\"chessid\"}")));
    assertFalse(
        FoxKifuDownload.isPageRequestFailure(
            new JSONObject("{\"result\":1,\"resultstr\":\"timeout\"}")));
    assertFalse(FoxKifuDownload.isPageRequestFailure(null));
  }
}
