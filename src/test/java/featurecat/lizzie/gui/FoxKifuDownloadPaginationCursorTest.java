package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FoxKifuDownloadPaginationCursorTest {
  @Test
  void failedPageRequestBecomesRetryableAfterRevert() {
    FoxKifuDownload.FoxPaginationCursor cursor = new FoxKifuDownload.FoxPaginationCursor();

    assertTrue(cursor.shouldRequest("chess-100"));
    cursor.beginRequest("chess-100");
    assertFalse(cursor.shouldRequest("chess-100"));

    cursor.revertPendingRequest();
    assertTrue(cursor.shouldRequest("chess-100"));
  }

  @Test
  void committedPageRequestStaysDeduplicatedEvenAfterLaterFailures() {
    FoxKifuDownload.FoxPaginationCursor cursor = new FoxKifuDownload.FoxPaginationCursor();

    cursor.beginRequest("chess-100");
    cursor.commit();
    assertFalse(cursor.shouldRequest("chess-100"));

    cursor.revertPendingRequest();
    assertFalse(cursor.shouldRequest("chess-100"));
  }

  @Test
  void failedFollowUpRequestRevertsToLastCommittedPage() {
    FoxKifuDownload.FoxPaginationCursor cursor = new FoxKifuDownload.FoxPaginationCursor();

    cursor.beginRequest("chess-100");
    cursor.commit();
    cursor.beginRequest("chess-200");
    cursor.revertPendingRequest();

    assertFalse(cursor.shouldRequest("chess-100"));
    assertTrue(cursor.shouldRequest("chess-200"));
  }

  @Test
  void resetRestoresInitialState() {
    FoxKifuDownload.FoxPaginationCursor cursor = new FoxKifuDownload.FoxPaginationCursor();

    cursor.beginRequest("chess-100");
    cursor.commit();
    cursor.reset();

    assertTrue(cursor.shouldRequest("chess-100"));
  }
}
