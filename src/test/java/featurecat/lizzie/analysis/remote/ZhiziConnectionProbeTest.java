package featurecat.lizzie.analysis.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class ZhiziConnectionProbeTest {
  @Test
  void parseInfoLineHandlesMultipleCandidatesFromKataAnalyze() {
    String line =
        "info move Q16 visits 120 winrate 0.612 scoreLead 3.4 order 0 pv Q16 D4 "
            + "info move D16 visits 80 winrate 0.587 scoreLead 2.2 order 1 pv D16 Q4";

    List<ZhiziConnectionProbe.Candidate> candidates = ZhiziConnectionProbe.parseInfoLine(line);

    assertEquals(2, candidates.size());
    assertEquals("Q16", candidates.get(0).move);
    assertEquals(120, candidates.get(0).visits);
    assertEquals(0, candidates.get(0).order);
    assertEquals(0.612, candidates.get(0).winrate, 0.0001);
    assertEquals(3.4, candidates.get(0).scoreLead, 0.0001);
    assertEquals("D16", candidates.get(1).move);
  }

  @Test
  void parseInfoLineIgnoresNonInfoOutput() {
    List<ZhiziConnectionProbe.Candidate> candidates =
        ZhiziConnectionProbe.parseInfoLine("= boardsize");

    assertFalse(candidates.iterator().hasNext());
  }
}
