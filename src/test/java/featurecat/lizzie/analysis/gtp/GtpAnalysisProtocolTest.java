package featurecat.lizzie.analysis.gtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GtpAnalysisProtocolTest {
  @Test
  void overviewOnlyCapsReportIntervalAndPreservesFullOutputOptions() {
    assertEquals(
        "kata-analyze W 10 ownership true pvVisits true rootInfo true",
        GtpAnalysisProtocol.overviewCommand(
            "kata-analyze W 50 ownership true pvVisits true rootInfo true"));
    assertEquals("kata-analyze B 1", GtpAnalysisProtocol.overviewCommand("kata-analyze B 1"));
    assertEquals("kata-analyze B 10", GtpAnalysisProtocol.overviewCommand("kata-analyze B 10"));
    assertEquals(
        "kata-analyze B 50 bad\nstop",
        GtpAnalysisProtocol.overviewCommand("kata-analyze B 50 bad\nstop"));
    assertFalse(GtpAnalysisProtocol.supportsOverviewBudget(0));
    assertTrue(GtpAnalysisProtocol.supportsOverviewBudget(500));
    assertFalse(GtpAnalysisProtocol.supportsOverviewBudget(501));
  }

  @Test
  void onlyIncrementalPlaysCanReplaceTheStopBarrier() {
    assertEquals(
        "play W Q4", GtpAnalysisProtocol.advanceCommand(List.of("play W Q4", "kata-analyze B 50")));
    assertEquals(
        "play B pass",
        GtpAnalysisProtocol.advanceCommand(
            List.of("play B pass", "play W D16", "kata-analyze B 50")));
    for (List<String> commands :
        List.of(
            List.of("kata-analyze B 50"),
            List.of("boardsize 19", "play W Q4", "kata-analyze B 50"),
            List.of("play W Q4", "clear_board", "kata-analyze B 50"),
            List.of("play W Q4\nclear_board", "kata-analyze B 50"),
            List.of("play W resign", "kata-analyze B 50"),
            List.of("play W I4", "kata-analyze B 50"),
            List.of("play W Q4", "kata-search_analyze_cancellable B 10000"))) {
      assertNull(GtpAnalysisProtocol.advanceCommand(commands), commands.toString());
    }
  }

  @Test
  void setupSplitPreservesOrderAndRequiresExactlyOneAnalysisCommand() {
    var split =
        GtpAnalysisProtocol.split(List.of("boardsize 19", "play B D16", "kata-analyze W 50"));
    assertEquals(List.of("boardsize 19", "play B D16"), List.copyOf(split.commands()));
    assertEquals("kata-analyze W 50", split.analyze());
    assertNull(GtpAnalysisProtocol.split(List.of("play B D16")));
    assertNull(GtpAnalysisProtocol.split(List.of("kata-analyze B 50", "kata-analyze W 50")));
  }

  @Test
  void numberedResponsesCannotMatchAnotherCommandOrAnUnnumberedBoundary() {
    assertTrue(GtpAnalysisProtocol.success("=810000000", 810000000));
    assertTrue(GtpAnalysisProtocol.success("=810000000 accepted", 810000000));
    assertFalse(GtpAnalysisProtocol.success("=8100000000", 810000000));
    assertFalse(GtpAnalysisProtocol.success("=", 810000000));
    assertFalse(GtpAnalysisProtocol.success("?810000000 rejected", 810000000));
    assertFalse(GtpAnalysisProtocol.success(null, 810000000));
    assertFalse(GtpAnalysisProtocol.success("=0", 0));
    assertTrue(GtpAnalysisProtocol.error("?810000000 illegal move", 810000000));
    assertEquals("value", GtpAnalysisProtocol.payload("=810000000 value", 810000000));
    assertEquals("", GtpAnalysisProtocol.payload("=810000000", 810000000));
  }
}
