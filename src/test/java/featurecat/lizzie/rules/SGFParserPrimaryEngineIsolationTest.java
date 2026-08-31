package featurecat.lizzie.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SGFParserPrimaryEngineIsolationTest {
  @Test
  void deferredSgfLoadDoesNotForwardAnIntermediateClearToThePrimaryEngine() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      AtomicInteger forwardingAttempts = new AtomicInteger();
      Runnable previousHook = Board.beforeHistoryOverwriteEngineForward;
      Board.beforeHistoryOverwriteEngineForward = forwardingAttempts::incrementAndGet;
      try {
        assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5];B[aa];W[bb])", false));
        assertEquals(
            0,
            forwardingAttempts.get(),
            "deferred synchronization must not send a partial clear while parsing");
      } finally {
        Board.beforeHistoryOverwriteEngineForward = previousHook;
      }
    }
  }

  @Test
  void legacySgfLoadStillForwardsItsInitialPrimaryEngineClear() throws Exception {
    try (RulesLayerTestHarness env = RulesLayerTestHarness.open()) {
      AtomicInteger forwardingAttempts = new AtomicInteger();
      Runnable previousHook = Board.beforeHistoryOverwriteEngineForward;
      Board.beforeHistoryOverwriteEngineForward = forwardingAttempts::incrementAndGet;
      try {
        assertTrue(SGFParser.loadFromString("(;FF[4]SZ[5];B[aa])"));
        assertTrue(
            forwardingAttempts.get() > 0,
            "existing SGF callers must retain synchronous primary-engine forwarding");
      } finally {
        Board.beforeHistoryOverwriteEngineForward = previousHook;
      }
    }
  }
}
