package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import featurecat.lizzie.analysis.Leelaz;
import featurecat.lizzie.analysis.Leelaz.ForegroundAnalysisLeaseFailure;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LizzieFrameLoadingTextTest {
  @Test
  void missingOrFailedEngineUsesTheFailureStatusWithoutDereferencingNull() throws Exception {
    assertEquals("LizzieFrame.display.down", LizzieFrame.loadingTextResourceKey(null));

    Leelaz failed = new Leelaz("");
    failed.isDownWithError = true;
    assertEquals("LizzieFrame.display.down", LizzieFrame.loadingTextResourceKey(failed));
  }

  @Test
  void distinguishesTuningFromOrdinaryLoading() throws Exception {
    Leelaz loading = new Leelaz("");
    assertEquals("LizzieFrame.display.loading", LizzieFrame.loadingTextResourceKey(loading));

    loading.isTuning = true;
    assertEquals("LizzieFrame.display.tuning", LizzieFrame.loadingTextResourceKey(loading));
  }

  @Test
  void unrestoredForegroundReaderIsNotPresentedAsLoading() throws Exception {
    Leelaz unrestored =
        new Leelaz("") {
          @Override
          public Optional<ForegroundAnalysisLeaseFailure> unrestoredForegroundLeaseFailure() {
            return Optional.of(ForegroundAnalysisLeaseFailure.FINAL_STOP_TIMEOUT);
          }
        };
    assertEquals(
        LizzieFrame.FOREGROUND_UNRESTORED_STATUS_KEY,
        LizzieFrame.loadingTextResourceKey(unrestored));

    unrestored.isDownWithError = true;
    assertEquals("LizzieFrame.display.down", LizzieFrame.loadingTextResourceKey(unrestored));
  }
}
