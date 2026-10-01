package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FoxKifuDownloadRankTest {
  private static final ResourceBundle CHINESE =
      ResourceBundle.getBundle("l10n.DisplayStrings", Locale.SIMPLIFIED_CHINESE);

  @ParameterizedTest
  @CsvSource({
    "100, P1段", "101, P2段", "102, P3段", "103, P4段", "104, P5段",
    "105, P6段", "106, P7段", "107, P8段", "108, P9段"
  })
  void decodesProfessionalRanksInsteadOfApplyingTheOnlineOffset(int rawRank, String expected) {
    assertEquals(expected, FoxKifuDownload.formatFoxRank(rawRank, CHINESE));
  }

  @ParameterizedTest
  @CsvSource({"0, 18级", "16, 2级", "17, 1级", "18, 1段", "26, 9段", "27, 10段"})
  void preservesOnlineRanksAndTheKyuDanBoundary(int rawRank, String expected) {
    assertEquals(expected, FoxKifuDownload.formatFoxRank(rawRank, CHINESE));
  }

  @ParameterizedTest
  @CsvSource({"en-US, P9dan", "ja-JP, P9段", "ko, P9단", "th-TH, P9แดน", "zh-TW, P9段", "zh-HK, P9段"})
  void usesTheSelectedLanguageForProfessionalRanks(String languageTag, String expected) {
    ResourceBundle resources =
        ResourceBundle.getBundle("l10n.DisplayStrings", Locale.forLanguageTag(languageTag));
    assertEquals(expected, FoxKifuDownload.formatFoxRank(108, resources));
  }
}
