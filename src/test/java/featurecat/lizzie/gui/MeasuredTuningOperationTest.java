package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import java.util.ArrayList;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MeasuredTuningOperationTest {
  @Test
  void sharedTaskGateAndExistingSavedIdentityAreRequiredBeforeEitherMeasuredAction() {
    for (boolean saved : new boolean[] {false, true}) {
      for (boolean busy : new boolean[] {false, true}) {
        assertEquals(
            saved && !busy,
            KataGoAutoSetupDialog.measuredTuningActionAvailable("saved-id", saved, busy));
        assertFalse(KataGoAutoSetupDialog.measuredTuningActionAvailable("", saved, busy));
        assertFalse(KataGoAutoSetupDialog.measuredTuningActionAvailable(null, saved, busy));
      }
    }
    // Refresh/catalog/download/switch callers provide the same shared busy predicate.
    assertFalse(KataGoAutoSetupDialog.measuredTuningActionAvailable("saved-id", true, true));
    assertTrue(KataGoAutoSetupDialog.measuredTuningActionAvailable("saved-id", true, false));
  }

  @Test
  void confirmationTargetContainsBothSavedNameAndStableIdentityUsingExistingTranslation() {
    ResourceBundle previous = Lizzie.resourceBundle;
    try {
      Lizzie.resourceBundle =
          new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
              return new Object[][] {{"EngineThreadPolicy.target", "目标：%s"}};
            }
          };
      assertEquals("目标：中文引擎 [saved-id]", MeasuredTuningDialog.targetLabel("中文引擎", "saved-id"));
    } finally {
      Lizzie.resourceBundle = previous;
    }
  }

  @Test
  void hiddenButStillDisplayableOwnerCannotReceiveAnyCompletion() {
    var operation = new MeasuredTuningOperation();
    long token = operation.begin(ignored -> {});
    assertTrue(operation.canDeliver(token, true, true));
    assertFalse(operation.canDeliver(token, false, true));
    assertFalse(operation.canDeliver(token, true, false));
  }

  @Test
  void closingInvalidatesAndCancelsWorkersEvenIfTheOwnerIsLaterShownAgain() {
    var operation = new MeasuredTuningOperation();
    List<Boolean> busy = new ArrayList<>();
    long token = operation.begin(busy::add);
    CompletableFuture<Void> worker = new CompletableFuture<>();
    operation.attach(token, worker);
    operation.invalidate();
    assertTrue(worker.isCancelled());
    assertFalse(operation.canDeliver(token, true, true));
    operation.finish(token);
    assertEquals(List.of(true, false), busy);
  }

  @Test
  void lateCompletionCannotClearBusyStateOrDeliverIntoTheNextOperation() {
    var operation = new MeasuredTuningOperation();
    List<Boolean> busy = new ArrayList<>();
    long oldToken = operation.begin(busy::add);
    long newToken = operation.begin(busy::add);
    operation.finish(oldToken);
    assertEquals(List.of(true, false, true), busy);
    assertFalse(operation.canDeliver(oldToken, true, true));
    assertTrue(operation.canDeliver(newToken, true, true));
    operation.finish(newToken);
    assertEquals(List.of(true, false, true, false), busy);
    assertFalse(operation.canDeliver(newToken, true, true));
  }

  @Test
  void workAttachedAfterCloseAndACompletedReviewAwaitingConfirmationStayInvalidated() {
    var operation = new MeasuredTuningOperation();
    long token = operation.begin(ignored -> {});
    operation.attach(token, CompletableFuture.completedFuture(null));
    operation.invalidate();
    CompletableFuture<Void> late = new CompletableFuture<>();
    operation.attach(token, late);
    assertTrue(late.isCancelled());
    assertFalse(operation.canDeliver(token, true, true));
  }
}
