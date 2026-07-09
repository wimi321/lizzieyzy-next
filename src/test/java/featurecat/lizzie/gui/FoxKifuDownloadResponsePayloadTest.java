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
}
