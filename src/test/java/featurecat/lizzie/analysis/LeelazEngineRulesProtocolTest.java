package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.enginegame.EngineGamePlans;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.gui.Menu;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class LeelazEngineRulesProtocolTest {
  private static final String CHINESE =
      "{\"ko\":\"SIMPLE\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";
  private static final String POSITIONAL_CHINESE =
      "{\"ko\":\"POSITIONAL\",\"scoring\":\"AREA\",\"tax\":\"NONE\",\"suicide\":false,"
          + "\"hasButton\":false,\"whiteHandicapBonus\":\"N\",\"friendlyPassOk\":true}";

  @Test
  void parameterReadbackPublishesTheCompletePairRegardlessOfRulesResponseOrder() throws Exception {
    for (String order : List.of("RPW", "PRW", "PWR", "WPR", "WRP", "RWP")) {
      try (Fixture fixture = Fixture.ordinary()) {
        Menu toolbar = fixture.installParameterToolbar();
        fixture.engine.queryEngineRules();
        fixture.engine.readKataParameters(TimeUnit.MINUTES.toMillis(1));
        for (char response : order.toCharArray()) {
          String command = response == 'R' ? "kata-get-rules"
              : "kata-get-param " + (response == 'P' ? "playoutDoublingAdvantage" : "analysisWideRootNoise");
          String value = response == 'R' ? CHINESE : response == 'P' ? "1.75" : "0.35";
          fixture.engine.dispatchReaderLineForTest(
              "=" + commandIdFor(fixture.output.toString(), command) + " " + value);
        }
        SwingUtilities.invokeAndWait(() -> {
          assertEquals("1.75", toolbar.txtGfPDA.getText(), order);
          assertEquals("0.35", toolbar.txtWRN.getText(), order);
        });
        assertEquals(1.75, fixture.engine.pda);
        assertEquals(0.35, fixture.engine.wrn);
        assertTrue(fixture.engine.engineRulesResult().isConfirmed());
        assertEquals(0, Lizzie.board.getHistory().getMoveNumber());
      }
    }
  }

  @Test
  void rejectedOrInvalidParameterDoesNotPublishAPartialPair() throws Exception {
    for (String response : List.of("?%d unsupported", "=%d malformed", "=%d NaN")) {
      try (Fixture fixture = Fixture.controlledParameters()) {
        Menu toolbar = fixture.installParameterToolbar();
        fixture.engine.readKataParameters(30_000);
        fixture.reply("kata-get-param playoutDoublingAdvantage", "1.75");
        int wrnId = commandIdFor(fixture.output.toString(), "kata-get-param analysisWideRootNoise");
        fixture.engine.dispatchReaderLineForTest(String.format(response, wrnId));
        fixture.reply("kata-get-rules", CHINESE);
        assertParameterToolbar(toolbar, "", "");
        assertTrue(fixture.engine.engineRulesResult().isConfirmed());
      }
    }
  }

  @Test
  void failedRulesQueryDoesNotBlockZeroParameterReadback() throws Exception {
    try (Fixture fixture = Fixture.controlledParameters()) {
      Menu toolbar = fixture.installParameterToolbar();
      fixture.engine.readKataParameters(30_000);
      int rulesId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("?" + rulesId + " unsupported rules");
      fixture.reply("kata-get-param analysisWideRootNoise", "0");
      assertParameterToolbar(toolbar, "", "");
      fixture.reply("kata-get-param playoutDoublingAdvantage", "0");
      assertParameterToolbar(toolbar, "0", "0");
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, fixture.engine.engineRulesResult().status());
    }
  }

  @Test
  void expiredReadCannotPublishLateParametersOrCancelItsSuccessor() throws Exception {
    try (Fixture fixture = Fixture.controlledParameters()) {
      Menu toolbar = fixture.installParameterToolbar();
      ControlledParameterLeelaz engine = (ControlledParameterLeelaz) fixture.engine;
      engine.readKataParameters(30_000);
      int oldWrnId = commandIdFor(fixture.output.toString(), "kata-get-param analysisWideRootNoise");
      Runnable oldTimeout = engine.timeout;
      fixture.reply("kata-get-param playoutDoublingAdvantage", "1.75");
      fixture.reply("kata-get-rules", CHINESE);
      oldTimeout.run();
      engine.dispatchReaderLineForTest("=" + oldWrnId + " 0.35");
      assertParameterToolbar(toolbar, "", "");

      engine.readKataParameters(30_000);
      fixture.reply("kata-get-param analysisWideRootNoise", "0.25");
      oldTimeout.run();
      fixture.reply("kata-get-param playoutDoublingAdvantage", "2");
      assertParameterToolbar(toolbar, "2", "0.25");
      assertEquals(2, engine.pda);
      assertEquals(0.25, engine.wrn);
    }
  }

  @Test
  void newReadDoesNotCombineOldResponsesWithItsOwnPair() throws Exception {
    try (Fixture fixture = Fixture.controlledParameters()) {
      Menu toolbar = fixture.installParameterToolbar();
      fixture.engine.readKataParameters(30_000);
      int oldPdaId = commandIdFor(fixture.output.toString(), "kata-get-param playoutDoublingAdvantage");
      int oldWrnId = commandIdFor(fixture.output.toString(), "kata-get-param analysisWideRootNoise");
      fixture.engine.readKataParameters(30_000);
      fixture.engine.dispatchReaderLineForTest("=" + oldPdaId + " 8");
      fixture.reply("kata-get-param analysisWideRootNoise", "0.25");
      fixture.engine.dispatchReaderLineForTest("=" + oldWrnId + " 0.9");
      assertParameterToolbar(toolbar, "", "");
      fixture.reply("kata-get-param playoutDoublingAdvantage", "2");
      assertParameterToolbar(toolbar, "2", "0.25");
    }
  }

  @Test
  void cancelledReadCannotPublishQueuedToolbarUpdate() throws Exception {
    try (Fixture fixture = Fixture.controlledParameters()) {
      Menu toolbar = fixture.installParameterToolbar();
      fixture.engine.readKataParameters(30_000);
      SwingUtilities.invokeAndWait(() -> {
        fixture.reply("kata-get-param playoutDoublingAdvantage", "1.75");
        fixture.reply("kata-get-param analysisWideRootNoise", "0.35");
        fixture.engine.cancelParameterRead();
      });
      assertParameterToolbar(toolbar, "", "");
      fixture.engine.readKataParameters(30_000);
      fixture.reply("kata-get-param playoutDoublingAdvantage", "0");
      fixture.reply("kata-get-param analysisWideRootNoise", "0");
      assertParameterToolbar(toolbar, "0", "0");
    }
  }

  @Test
  void readerReplacementCannotPublishQueuedToolbarUpdate() throws Exception {
    try (Fixture fixture = Fixture.controlledParameters()) {
      Menu toolbar = fixture.installParameterToolbar();
      fixture.engine.readKataParameters(30_000);
      SwingUtilities.invokeAndWait(() -> {
        fixture.reply("kata-get-param playoutDoublingAdvantage", "1.75");
        fixture.reply("kata-get-param analysisWideRootNoise", "0.35");
        fixture.engine.installFreshCommandOutputForTest(new ByteArrayOutputStream());
      });
      assertParameterToolbar(toolbar, "", "");
    }
  }

  @Test
  void foregroundReplacementRejectsOldPairBeforeEdtPublication() throws Exception {
    try (Fixture fixture = Fixture.controlledParameters()) {
      Menu toolbar = fixture.installParameterToolbar();
      Leelaz replacement = fixture.secondEngine();
      fixture.engine.readKataParameters(30_000);
      SwingUtilities.invokeAndWait(() -> {
        fixture.reply("kata-get-param playoutDoublingAdvantage", "1.75");
        fixture.reply("kata-get-param analysisWideRootNoise", "0.35");
        Lizzie.setPrimaryEngine(replacement);
      });
      assertParameterToolbar(toolbar, "", "");
      replacement.readKataParameters(30_000);
      replacement.dispatchReaderLineForTest("=" + commandIdFor(fixture.secondOutput.toString(),
          "kata-get-param playoutDoublingAdvantage") + " 2");
      replacement.dispatchReaderLineForTest("=" + commandIdFor(fixture.secondOutput.toString(),
          "kata-get-param analysisWideRootNoise") + " 0.25");
      assertParameterToolbar(toolbar, "2", "0.25");
      replacement.cancelParameterRead();
    }
  }

  private static void assertParameterToolbar(Menu toolbar, String pda, String wrn) throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      if (pda.isEmpty()) assertEquals("", toolbar.txtGfPDA.getText());
      else assertEquals(Double.parseDouble(pda), Double.parseDouble(toolbar.txtGfPDA.getText()));
      if (wrn.isEmpty()) assertEquals("", toolbar.txtWRN.getText());
      else assertEquals(Double.parseDouble(wrn), Double.parseDouble(toolbar.txtWRN.getText()));
    });
  }

  @Test
  void successfulReadbackBecomesConfirmedActualRulesNotTheRequest() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.applyEngineRules(KataGoRules.parse("chinese").orElseThrow());
      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      fixture.engine.dispatchReaderLineForTest("=" + setId);
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + POSITIONAL_CHINESE);

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertTrue(result.isConfirmed());
      assertEquals("POSITIONAL", result.observed().string("ko"));
      assertFalse(result.observed().semanticallyEquals(KataGoRules.parse("chinese").orElseThrow()));
      assertEquals(1, fixture.engine.usingSpecificRules);
    }
  }

  @Test
  void matchingQueryErrorIsNotMaskedByPreviousConfirmedRules() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.confirm(CHINESE);
      String previous = fixture.engine.recentRulesLine;

      fixture.engine.queryEngineRules();
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("?" + queryId + " unknown command");

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, result.status());
      assertEquals(EngineRulesResult.Reason.QUERY_REJECTED, result.reason());
      assertTrue(result.lastKnownStale());
      assertEquals(previous, fixture.engine.recentRulesLine);
      assertFalse(result.isConfirmed());
    }
  }

  @Test
  void lateResponseDoesNotUpdateAReplacementEngine() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.queryEngineRules();
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      Leelaz replacement = fixture.replaceEngine();
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);

      assertEquals("", replacement.recentRulesLine);
      assertEquals("", Lizzie.config.currentKataGoRules);
      assertFalse(replacement.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void timeoutRetiresSetAndLateAckCannotLaunchReadback() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Leelaz.EngineRulesOperation operation =
          fixture.engine.applyEngineRulesOperation(KataGoRules.parse("chinese").orElseThrow(), 50L);
      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      EngineRulesResult settled = operation.await(TimeUnit.SECONDS.toMillis(2));

      assertNotNull(settled);
      assertEquals(EngineRulesResult.Status.SET_FAILED, settled.status());
      assertEquals(EngineRulesResult.Reason.SET_TIMEOUT, settled.reason());
      fixture.engine.processCommandResponseLineForTest("=" + setId);

      assertEquals(
          EngineRulesResult.Status.SET_FAILED, fixture.engine.engineRulesResult().status());
      assertEquals(
          EngineRulesResult.Reason.SET_TIMEOUT, fixture.engine.engineRulesResult().reason());
      assertFalse(fixture.output.toString().contains("kata-get-rules"));
      assertFalse(fixture.engine.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void timeoutRetiresQueryAndLateReadbackCannotBecomeConfirmed() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Leelaz.EngineRulesOperation operation = fixture.engine.queryEngineRulesOperation(50L);
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      EngineRulesResult settled = operation.await(TimeUnit.SECONDS.toMillis(2));

      assertNotNull(settled);
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, settled.status());
      assertEquals(EngineRulesResult.Reason.QUERY_TIMEOUT, settled.reason());
      fixture.engine.processCommandResponseLineForTest("=" + queryId + " " + CHINESE);

      assertEquals(
          EngineRulesResult.Status.QUERY_FAILED, fixture.engine.engineRulesResult().status());
      assertEquals(
          EngineRulesResult.Reason.QUERY_TIMEOUT, fixture.engine.engineRulesResult().reason());
      assertEquals("", fixture.engine.recentRulesLine);
      assertFalse(fixture.engine.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void replacingOperationRetiresOldResponseWithoutTouchingSuccessor() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Leelaz.EngineRulesOperation oldOperation = fixture.engine.queryEngineRulesOperation(1000L);
      int oldQueryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      Leelaz.EngineRulesOperation successor =
          fixture.engine.applyEngineRulesOperation(
              KataGoRules.parse("chinese").orElseThrow(), 1000L);
      int successorSetId = commandIdFor(fixture.output.toString(), "kata-set-rules");

      EngineRulesResult replaced = oldOperation.await(TimeUnit.SECONDS.toMillis(2));
      assertNotNull(replaced);
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, replaced.status());
      assertEquals(EngineRulesResult.Reason.REPLACED, replaced.reason());

      fixture.engine.processCommandResponseLineForTest("=" + oldQueryId + " " + CHINESE);
      assertEquals(successor.generation(), fixture.engine.engineRulesResult().generation());
      assertEquals(EngineRulesResult.Status.PENDING, successor.snapshot().status());

      fixture.engine.dispatchReaderLineForTest("=" + successorSetId);
      int successorQueryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + successorQueryId + " " + POSITIONAL_CHINESE);
      assertTrue(successor.result().isConfirmed());
      assertEquals("POSITIONAL", successor.result().observed().string("ko"));
    }
  }

  @Test
  void lateReadbackAfterReplacementCannotOverwriteRulesCache() throws Exception {
    try (Fixture fixture = Fixture.controlledResponse()) {
      ControlledRulesResponseLeelaz engine = (ControlledRulesResponseLeelaz) fixture.engine;
      try {
        fixture.confirm(CHINESE);
        String previousRulesLine = engine.recentRulesLine;
        String previousConfigRules = Lizzie.config.currentKataGoRules;
        engine.pauseNextResponse();
        Leelaz.EngineRulesOperation oldOperation = engine.queryEngineRulesOperation(1000L);
        int oldQueryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
        Throwable[] responseFailure = new Throwable[1];
        Thread responseWorker =
            new Thread(
                () -> {
                  try {
                    engine.dispatchReaderLineForTest("=" + oldQueryId + " " + POSITIONAL_CHINESE);
                  } catch (Throwable thrown) {
                    responseFailure[0] = thrown;
                  }
                },
                "engine-rules-replacement-response-test");
        responseWorker.start();

        assertTrue(engine.responsePeekEntered.await(2, TimeUnit.SECONDS));
        Leelaz.EngineRulesOperation successor = engine.queryEngineRulesOperation(1000L);
        int successorQueryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
        EngineRulesResult replaced = oldOperation.await(TimeUnit.SECONDS.toMillis(2));
        assertNotNull(replaced);
        assertEquals(EngineRulesResult.Reason.REPLACED, replaced.reason());

        engine.releaseResponsePeek.countDown();
        responseWorker.join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(responseWorker.isAlive());
        assertNull(responseFailure[0], () -> "response worker failed: " + responseFailure[0]);
        assertEquals(successor.generation(), engine.engineRulesResult().generation());
        assertEquals(EngineRulesResult.Status.PENDING, engine.engineRulesResult().status());
        assertEquals(previousRulesLine, engine.recentRulesLine);
        assertEquals(previousConfigRules, Lizzie.config.currentKataGoRules);

        engine.dispatchReaderLineForTest("=" + successorQueryId + " " + CHINESE);
        assertTrue(successor.result().isConfirmed());
        assertEquals(previousRulesLine, engine.recentRulesLine);
        assertEquals(previousConfigRules, Lizzie.config.currentKataGoRules);
      } finally {
        engine.releaseResponsePeek.countDown();
      }
    }
  }

  @Test
  void replacingReaderBindingCannotConsumeTheOldRulesResponse() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Leelaz.EngineRulesOperation operation = fixture.engine.queryEngineRulesOperation(1000L);
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");

      fixture.engine.installFreshCommandStreamsForTest(
          new ByteArrayInputStream(new byte[0]),
          new ByteArrayOutputStream(),
          new ByteArrayInputStream(new byte[0]));
      fixture.engine.processCommandResponseLineForTest("=" + queryId + " " + CHINESE);

      assertFalse(operation.isDone());
      assertEquals(EngineRulesResult.Status.PENDING, operation.snapshot().status());
      EngineRulesResult settled = operation.await(TimeUnit.SECONDS.toMillis(2));
      assertNotNull(settled);
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, settled.status());
      assertEquals(EngineRulesResult.Reason.QUERY_TIMEOUT, settled.reason());
      assertEquals("", fixture.engine.recentRulesLine);
    }
  }

  @Test
  void sendFailureCompletesOperationAsFailedWithoutSyntheticSuccess() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Leelaz failing = Fixture.liveEngine(new ThrowingOutputStream());
      Leelaz.EngineRulesOperation operation =
          failing.applyEngineRulesOperation(KataGoRules.parse("chinese").orElseThrow(), 1000L);
      EngineRulesResult result = operation.await(TimeUnit.SECONDS.toMillis(2));

      assertNotNull(result);
      assertEquals(EngineRulesResult.Status.SET_FAILED, result.status());
      assertEquals(EngineRulesResult.Reason.SEND_FAILED, result.reason());
      assertFalse(result.isConfirmed());
    }
  }

  private static final class ThrowingOutputStream extends OutputStream {
    @Override
    public void write(int value) throws IOException {
      throw new IOException("synthetic transport failure");
    }
  }

  @Test
  void invalidReadbackIsFailureNotSuccess() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.queryEngineRules();
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " not-json");

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, result.status());
      assertEquals(EngineRulesResult.Reason.INVALID_READBACK, result.reason());
    }
  }

  @Test
  void malformedBraceReadbackDoesNotOverwriteSuccessfulRulesCache() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.confirm(CHINESE);
      String previousRulesLine = fixture.engine.recentRulesLine;
      String previousConfigRules = Lizzie.config.currentKataGoRules;
      fixture.engine.readKataParameters(TimeUnit.SECONDS.toMillis(30));

      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " {\"ko\":");

      EngineRulesResult result = fixture.engine.engineRulesResult();
      assertEquals(EngineRulesResult.Status.QUERY_FAILED, result.status());
      assertEquals(EngineRulesResult.Reason.INVALID_READBACK, result.reason());
      assertEquals(previousRulesLine, fixture.engine.recentRulesLine);
      assertEquals(previousConfigRules, Lizzie.config.currentKataGoRules);
    }
  }

  @Test
  void settableButNotQueryableStartupIsUnconfirmed() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.commandLists.clear();
      fixture.engine.commandLists.add("kata-set-rules");
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = CHINESE;
      fixture.engine.confirmKataRulesAfterStartup(false, 50L, TimeUnit.SECONDS.toMillis(2));
      assertTrue(fixture.output.toString().contains("kata-set-rules"));
      int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
      fixture.engine.dispatchReaderLineForTest("=" + setId);
      assertFalse(fixture.output.toString().contains("kata-get-rules"));
      assertTrue(fixture.engine.engineRulesResult().isUnconfirmed());
      assertEquals(
          EngineRulesResult.Reason.QUERY_UNSUPPORTED, fixture.engine.engineRulesResult().reason());
    }
  }

  @Test
  void missingSetCapabilityDoesNotSendSetCommand() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.commandLists.clear();
      fixture.engine.commandLists.add("kata-get-rules");
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = CHINESE;
      fixture.engine.confirmKataRulesAfterStartup(false, 50L, TimeUnit.SECONDS.toMillis(2));
      assertFalse(fixture.output.toString().contains("kata-set-rules"));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);
      assertTrue(fixture.engine.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void isolatedStartupDoesNotPublishForegroundCache() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Lizzie.config.currentKataGoRules = "= previous";
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = CHINESE;
      Leelaz isolated = fixture.secondEngine();
      isolated.confirmKataRulesAfterStartup(true, 50L, TimeUnit.SECONDS.toMillis(2));
      int setId = commandIdFor(fixture.secondOutput.toString(), "kata-set-rules");
      isolated.dispatchReaderLineForTest("=" + setId);
      int queryId = commandIdFor(fixture.secondOutput.toString(), "kata-get-rules");
      isolated.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);
      assertTrue(isolated.engineRulesResult().isConfirmed());
      assertEquals("= previous", Lizzie.config.currentKataGoRules);
      assertEquals("", Lizzie.leelaz.recentRulesLine);
    }
  }

  @Test
  void unparseableStartupDefaultDoesNotSendSetCommand() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Lizzie.config.autoLoadKataRules = true;
      Lizzie.config.kataRules = "not-a-rules-value";
      fixture.engine.confirmKataRulesAfterStartup(false, 50L, TimeUnit.SECONDS.toMillis(2));
      assertFalse(fixture.output.toString().contains("kata-set-rules"));
      int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
      fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);
      assertTrue(fixture.engine.engineRulesResult().isConfirmed());
    }
  }

  @Test
  void startupConfirmationDoesNotBlockCallerBeforeCommandListCompletes() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Field ready = Leelaz.class.getDeclaredField("endGetCommandList");
      ready.setAccessible(true);
      ready.setBoolean(fixture.engine, false);
      long startedAt = System.nanoTime();
      fixture.engine.confirmKataRulesAfterStartup(
          false, TimeUnit.SECONDS.toMillis(2), TimeUnit.SECONDS.toMillis(2));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500L);
      assertFalse(fixture.output.toString().contains("kata-get-rules"));
      assertEquals(EngineRulesResult.Status.PENDING, fixture.engine.engineRulesResult().status());
      ready.setBoolean(fixture.engine, true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (System.nanoTime() < deadline
          && !fixture.output.toString().contains("kata-get-rules")) {
        Thread.sleep(20L);
      }
      assertTrue(fixture.output.toString().contains("kata-get-rules"));
    }
  }

  @Test
  void matchOwnerAppliesRulesDuringEngineGameOccupancy() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      EngineManager.resetEngineGameTransactionStateForTest();
      EngineManager manager = new EngineManager(List.of(fixture.engine, fixture.secondEngine()));
      Lizzie.engineManager = manager;
      assertNotNull(
          EngineManager.beginEngineGameTransaction(
              manager, EngineGamePlans.harness(0, 1, true), null, true));
      try {
        assertTrue(EngineManager.occupiesEngineGameAdmission());
        assertFalse(fixture.engine.applyEngineRules(KataGoRules.parse("japanese").orElseThrow()));
        assertEquals(
            EngineRulesResult.Reason.OCCUPIED, fixture.engine.engineRulesResult().reason());
        fixture.engine.enableAutoSettleMatchRulesForTest();
        assertTrue(
            fixture.engine.applyEngineRulesForMatchOwner(
                KataGoRules.parse("japanese").orElseThrow()));
        assertTrue(fixture.engine.engineRulesResult().isConfirmed());
        assertEquals("TERRITORY", fixture.engine.engineRulesResult().observed().string("scoring"));
      } finally {
        EngineManager.resetEngineGameTransactionStateForTest();
      }
    }
  }

  @Test
  void matchOwnerReadbackRetainsOwnerAdmissionAfterSetAck() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      GtpConsolePane previousConsole = Lizzie.gtpConsole;
      Lizzie.gtpConsole = SilentGtpConsole.create();
      try {
        EngineManager.resetEngineGameTransactionStateForTest();
        EngineManager manager = new EngineManager(List.of(fixture.engine, fixture.secondEngine()));
        Lizzie.engineManager = manager;
        EngineManager.EngineGameOwnerTransaction transaction =
            EngineManager.beginEngineGameTransaction(
                manager, EngineGamePlans.harness(0, 1, true), null, true);
        assertNotNull(transaction);
        Leelaz.EngineRulesOperation[] operation = new Leelaz.EngineRulesOperation[1];
        Throwable[] failure = new Throwable[1];
        Thread ownerWorker =
            new Thread(
                () -> {
                  try {
                    boolean current =
                        EngineManager.runEngineGameIoStepForTest(
                            transaction,
                            () -> {
                              operation[0] =
                                  fixture.engine.applyEngineRulesForMatchOwnerOperation(
                                      KataGoRules.parse("japanese").orElseThrow());
                              try {
                                operation[0].await(TimeUnit.SECONDS.toMillis(2));
                              } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                              }
                            });
                    if (!current) {
                      failure[0] =
                          new AssertionError("owner transaction retired before rules settled");
                    }
                  } catch (Throwable thrown) {
                    failure[0] = thrown;
                  }
                },
                "engine-rules-owner-test");
        ownerWorker.start();

        long setDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!fixture.output.toString().contains("kata-set-rules")
            && System.nanoTime() < setDeadline) {
          Thread.sleep(10L);
        }
        int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
        fixture.engine.dispatchReaderLineForTest("=" + setId);

        long queryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!fixture.output.toString().contains("kata-get-rules")
            && System.nanoTime() < queryDeadline) {
          Thread.sleep(10L);
        }
        assertTrue(
            fixture.output.toString().contains("kata-get-rules"),
            () ->
                "owner readback was not sent: status="
                    + operation[0].snapshot().status()
                    + " reason="
                    + operation[0].snapshot().reason());
        int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
        fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + POSITIONAL_CHINESE);
        ownerWorker.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(ownerWorker.isAlive());
        assertNull(failure[0], () -> "owner worker failed: " + failure[0]);
        assertNotNull(operation[0].result());
        assertTrue(operation[0].result().isConfirmed());
        assertEquals("POSITIONAL", operation[0].result().observed().string("ko"));
      } finally {
        Lizzie.gtpConsole = previousConsole;
        EngineManager.resetEngineGameTransactionStateForTest();
      }
    }
  }

  @Test
  void matchOwnerReadbackIsRetiredWhenOwnerEndsBeforePhysicalWrite() throws Exception {
    try (Fixture fixture = Fixture.controlledOwner()) {
      GtpConsolePane previousConsole = Lizzie.gtpConsole;
      Lizzie.gtpConsole = SilentGtpConsole.create();
      ControlledOwnerLeelaz engine = (ControlledOwnerLeelaz) fixture.engine;
      try {
        EngineManager.resetEngineGameTransactionStateForTest();
        EngineManager manager = new EngineManager(List.of(fixture.engine, fixture.secondEngine()));
        Lizzie.engineManager = manager;
        EngineManager.EngineGameOwnerTransaction transaction =
            EngineManager.beginEngineGameTransaction(
                manager, EngineGamePlans.harness(0, 1, true), null, true);
        assertNotNull(transaction);
        Leelaz.EngineRulesOperation[] operation = new Leelaz.EngineRulesOperation[1];
        Throwable[] failure = new Throwable[1];
        Thread ownerWorker =
            new Thread(
                () -> {
                  try {
                    EngineManager.runEngineGameIoStepForTest(
                        transaction,
                        () -> {
                          operation[0] =
                              fixture.engine.applyEngineRulesForMatchOwnerOperation(
                                  KataGoRules.parse("japanese").orElseThrow());
                          try {
                            operation[0].await(TimeUnit.SECONDS.toMillis(2));
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                          }
                        });
                  } catch (Throwable thrown) {
                    failure[0] = thrown;
                  }
                },
                "engine-rules-owner-retirement-test");
        ownerWorker.start();

        long setDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!fixture.output.toString().contains("kata-set-rules")
            && System.nanoTime() < setDeadline) {
          Thread.sleep(10L);
        }
        int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
        Throwable[] responseFailure = new Throwable[1];
        Thread responseWorker =
            new Thread(
                () -> {
                  try {
                    fixture.engine.dispatchReaderLineForTest("=" + setId);
                  } catch (Throwable thrown) {
                    responseFailure[0] = thrown;
                  }
                },
                "engine-rules-owner-response-test");
        responseWorker.start();

        assertTrue(engine.queryOutputEntered.await(2, TimeUnit.SECONDS));
        EngineManager.failEngineGameTransaction(
            transaction, new IllegalStateException("owner retired before rules readback write"));
        assertFalse(EngineManager.isCurrentEngineGameTransaction(transaction));
        engine.releaseQueryOutput.countDown();
        ownerWorker.join(TimeUnit.SECONDS.toMillis(2));
        responseWorker.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(ownerWorker.isAlive());
        assertFalse(responseWorker.isAlive());
        assertNull(responseFailure[0], () -> "response worker failed: " + responseFailure[0]);
        assertNull(failure[0], () -> "owner worker failed: " + failure[0]);
        assertFalse(
            fixture.output.toString().contains("kata-get-rules"), fixture.output.toString());
        assertNotNull(operation[0].result());
        assertEquals(EngineRulesResult.Status.QUERY_FAILED, operation[0].result().status());
        assertEquals(EngineRulesResult.Reason.SEND_FAILED, operation[0].result().reason());
      } finally {
        engine.releaseQueryOutput.countDown();
        Lizzie.gtpConsole = previousConsole;
        EngineManager.resetEngineGameTransactionStateForTest();
      }
    }
  }

  @Test
  void occupancyRejectsRuleMutation() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      fixture.engine.beginForegroundRestoreForTest();
      boolean sent = fixture.engine.applyEngineRules(KataGoRules.parse("chinese").orElseThrow());
      assertFalse(sent);
      assertEquals(
          EngineRulesResult.Status.SET_FAILED, fixture.engine.engineRulesResult().status());
      assertEquals(EngineRulesResult.Reason.OCCUPIED, fixture.engine.engineRulesResult().reason());
      assertFalse(fixture.output.toString().contains("kata-set-rules"));
    }
  }

  @Test
  void lifecycleRulesOperationRetainsOwnerAdmissionThroughReadback() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      Object owner = new Object();
      try (Leelaz.ExclusiveGtpLifecycleReservation reservation =
          fixture.engine.beginExclusiveGtpLifecycleReservation(owner)) {
        assertNotNull(reservation);
        Leelaz.ExactSnapshotRestoreAdmission admission =
            fixture.engine.captureExactSnapshotRestoreAdmission(
                Leelaz.ExactSnapshotRestoreOwner.LIFECYCLE, owner, null);
        Leelaz.EngineRulesOperation[] operation = new Leelaz.EngineRulesOperation[1];
        fixture.engine.withExactSnapshotRestoreAdmission(
            admission,
            () ->
                operation[0] =
                    fixture.engine.applyEngineRulesOperation(
                        KataGoRules.parse("japanese").orElseThrow()));

        assertTrue(operation[0].accepted());
        int setId = commandIdFor(fixture.output.toString(), "kata-set-rules");
        fixture.engine.dispatchReaderLineForTest("=" + setId);
        int queryId = commandIdFor(fixture.output.toString(), "kata-get-rules");
        fixture.engine.dispatchReaderLineForTest("=" + queryId + " " + CHINESE);

        assertTrue(operation[0].result().isConfirmed());
      }
    }
  }

  @Test
  void rulesCommandsAreNotMirroredToTheSecondEngine() throws Exception {
    try (Fixture fixture = Fixture.ordinary()) {
      ExtraMode previousMode = Lizzie.config.extraMode;
      Leelaz previousSecond = Lizzie.leelaz2;
      Leelaz second = fixture.secondEngine();
      Lizzie.config.extraMode = ExtraMode.Double_Engine;
      Lizzie.leelaz2 = second;
      try {
        fixture.engine.applyEngineRules(KataGoRules.parse("chinese").orElseThrow());
        assertTrue(fixture.output.toString().contains("kata-set-rules"));
        assertFalse(
            fixture.secondOutput.toString().contains("kata-set-rules"),
            fixture.secondOutput.toString());
      } finally {
        Lizzie.config.extraMode = previousMode;
        Lizzie.leelaz2 = previousSecond;
      }
    }
  }

  private static int commandIdFor(String commands, String command) {
    String[] lines = commands.split("\\R");
    for (int index = lines.length - 1; index >= 0; index--) {
      String trimmed = lines[index].trim();
      int split = trimmed.indexOf(' ');
      if (split <= 0) {
        continue;
      }
      if (trimmed.substring(split + 1).startsWith(command)) {
        return Integer.parseInt(trimmed.substring(0, split));
      }
    }
    throw new AssertionError("missing " + command + " in " + commands);
  }

  private static <T> T allocate(Class<T> type) throws Exception {
    Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
  }

  private static final class ParameterFrame extends LizzieFrame {
    private ParameterFrame() throws IOException { super(); }

    @Override
    public void refresh() {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super(null);
    }

    private static SilentGtpConsole create() throws Exception {
      Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return (SilentGtpConsole)
          ((sun.misc.Unsafe) field.get(null)).allocateInstance(SilentGtpConsole.class);
    }

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}
  }

  private static final class ControlledRulesResponseLeelaz extends Leelaz {
    private final CountDownLatch responsePeekEntered = new CountDownLatch(1);
    private final CountDownLatch releaseResponsePeek = new CountDownLatch(1);
    private volatile boolean pauseNextResponse;

    private ControlledRulesResponseLeelaz() throws IOException {
      super("");
    }

    private void pauseNextResponse() {
      pauseNextResponse = true;
    }

    @Override
    void afterEngineRulesResponseHandlerPeek() {
      if (!pauseNextResponse) {
        return;
      }
      pauseNextResponse = false;
      responsePeekEntered.countDown();
      try {
        releaseResponsePeek.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class ControlledOwnerLeelaz extends Leelaz {
    private final CountDownLatch queryOutputEntered = new CountDownLatch(1);
    private final CountDownLatch releaseQueryOutput = new CountDownLatch(1);

    private ControlledOwnerLeelaz() throws IOException {
      super("");
    }

    @Override
    void beforeEngineRulesCommandPhysicalWrite(String command) {
      if (!"kata-get-rules".equals(command)) {
        return;
      }
      queryOutputEntered.countDown();
      try {
        releaseQueryOutput.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class ControlledParameterLeelaz extends Leelaz {
    private Runnable timeout;

    private ControlledParameterLeelaz() throws IOException {
      super("");
    }

    @Override
    void scheduleParameterReadTimeout(Runnable timeout, long timeoutMillis) {
      this.timeout = timeout;
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Config previousConfig;
    private final Leelaz previousLeelaz;
    private final GtpConsolePane previousConsole;
    private final Board previousBoard;
    private final LizzieFrame previousFrame;
    private final Menu previousMenu;
    private final EngineManager previousManager;
    final Leelaz engine;
    final ByteArrayOutputStream output;
    ByteArrayOutputStream secondOutput;

    private Fixture() throws Exception {
      this(null, new ByteArrayOutputStream());
    }

    private Fixture(Leelaz suppliedEngine, ByteArrayOutputStream fixtureOutput) throws Exception {
      previousConfig = Lizzie.config;
      previousLeelaz = Lizzie.leelaz;
      previousConsole = Lizzie.gtpConsole;
      previousBoard = Lizzie.board;
      previousFrame = Lizzie.frame;
      previousMenu = LizzieFrame.menu;
      previousManager = Lizzie.engineManager;
      Lizzie.config = ConfigTestHelper.createForTests(Files.createTempDirectory("engine-rules"));
      Lizzie.gtpConsole = null;
      Lizzie.frame = null;
      EngineManager.resetEngineGameTransactionStateForTest();
      output = fixtureOutput;
      engine = suppliedEngine == null ? liveEngine(output) : suppliedEngine;
      Lizzie.leelaz = engine;
      Lizzie.board = new Board();
    }

    static Fixture ordinary() throws Exception {
      return new Fixture();
    }

    static Fixture controlledParameters() throws Exception {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ControlledParameterLeelaz engine = new ControlledParameterLeelaz();
      liveEngine(engine, output);
      return new Fixture(engine, output);
    }

    static Fixture controlledResponse() throws Exception {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ControlledRulesResponseLeelaz engine = new ControlledRulesResponseLeelaz();
      liveEngine(engine, output);
      return new Fixture(engine, output);
    }

    static Fixture controlledOwner() throws Exception {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ControlledOwnerLeelaz engine = new ControlledOwnerLeelaz();
      liveEngine(engine, output);
      return new Fixture(engine, output);
    }

    Menu installParameterToolbar() throws Exception {
      ParameterFrame frame = allocate(ParameterFrame.class);
      Menu toolbar = allocate(Menu.class);
      SwingUtilities.invokeAndWait(() -> {
        try {
          for (String name : List.of("txtGfPDA", "txtWRN", "chkPDA", "chkWRN")) {
            Field field = Menu.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(toolbar, field.getType().getConstructor().newInstance());
          }
        } catch (ReflectiveOperationException failure) {
          throw new AssertionError(failure);
        }
      });
      LizzieFrame.menu = toolbar;
      Lizzie.frame = frame;
      return toolbar;
    }

    void reply(String command, String payload) {
      try {
        engine.dispatchReaderLineForTest("=" + commandIdFor(output.toString(), command) + " " + payload);
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
    }

    void confirm(String payload) throws Exception {
      engine.queryEngineRules();
      int queryId = commandIdFor(output.toString(), "kata-get-rules");
      engine.dispatchReaderLineForTest("=" + queryId + " " + payload);
    }

    Leelaz replaceEngine() throws Exception {
      Leelaz replacement = liveEngine(new ByteArrayOutputStream());
      Lizzie.leelaz = replacement;
      return replacement;
    }

    Leelaz secondEngine() throws Exception {
      secondOutput = new ByteArrayOutputStream();
      return liveEngine(secondOutput);
    }

    private static Leelaz liveEngine(OutputStream stream) throws Exception {
      return liveEngine(new Leelaz(""), stream);
    }

    private static Leelaz liveEngine(Leelaz created, OutputStream stream) throws Exception {
      created.installFreshCommandOutputForTest(stream);
      created.started = true;
      created.isLoaded = true;
      created.isKatago = true;
      created.commandLists.addAll(List.of("kata-set-rules", "kata-get-rules"));
      Field ready = Leelaz.class.getDeclaredField("endGetCommandList");
      ready.setAccessible(true);
      ready.setBoolean(created, true);
      return created;
    }

    @Override
    public void close() {
      engine.cancelParameterRead();
      EngineManager.resetEngineGameTransactionStateForTest();
      Lizzie.config = previousConfig;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.gtpConsole = previousConsole;
      Lizzie.board = previousBoard;
      Lizzie.frame = previousFrame;
      LizzieFrame.menu = previousMenu;
      Lizzie.engineManager = previousManager;
    }
  }
}
