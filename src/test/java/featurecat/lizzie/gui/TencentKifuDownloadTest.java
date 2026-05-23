package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TencentKifuDownloadTest {

  @Test
  void formatTencentRankSupportsProfessionalRankCodes() {
    assertEquals("P9段", TencentKifuDownload.formatTencentRank(108, "段", "级"));
    assertEquals("P1段", TencentKifuDownload.formatTencentRank(100, "段", "级"));
  }

  @Test
  void formatTencentRankKeepsAmateurRankCodes() {
    assertEquals("3段", TencentKifuDownload.formatTencentRank(20, "段", "级"));
    assertEquals("1级", TencentKifuDownload.formatTencentRank(17, "段", "级"));
    assertEquals("", TencentKifuDownload.formatTencentRank(0, "段", "级"));
  }
}
