package featurecat.lizzie.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.Config;
import featurecat.lizzie.ExtraMode;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.GtpConsolePane;
import featurecat.lizzie.gui.LizzieFrame;
import java.awt.Window;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LeelazLoadSgfResponseBindingTest {




  @Test
  void mirroredLoadSgfErrorWaitsForAlreadyDispatchedPeerBeforeCleanup() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz primary = new Leelaz("");
      Leelaz mirror = new Leelaz("");
      RecordingOutputStream primaryOutput = new RecordingOutputStream();
      RecordingOutputStream mirrorOutput = new RecordingOutputStream();
      setOutputStream(primary, primaryOutput);
      setOutputStream(mirror, mirrorOutput);
      Path sgfFile = Files.createTempFile("mirrored-loadsgf-response-", ".sgf");
      CountDownLatch cleaned = new CountDownLatch(1);
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  primary.loadSgf(
                      sgfFile,
                      mirror,
                      () -> {
                        try {
                          Files.deleteIfExists(sgfFile);
                        } catch (IOException failure) {
                          throw new IllegalStateException(failure);
                        } finally {
                          cleaned.countDown();
                        }
                      });
                } catch (Throwable failure) {
                  loadFailure.set(failure);
                }
              },
              "mirrored-loadsgf-response-error");
      try {
        loadThread.start();
        waitForCommandCount(primaryOutput, 1, 1000L, "primary loadsgf should be dispatched.");
        waitForCommandCount(mirrorOutput, 1, 1000L, "mirror loadsgf should be dispatched.");

        invokeCommandResponseLine(
            primary, "?" + responseCommandId(primaryOutput.commands().get(0)) + " cannot loadsgf");

        assertFalse(cleaned.await(50, TimeUnit.MILLISECONDS));
        assertTrue(loadThread.isAlive());
        assertTrue(Files.exists(sgfFile));
        assertEquals(1, pendingHandlerCount(mirror));

        invokeCommandResponseLine(mirror, buildSuccessResponseLine(mirrorOutput.commands().get(0)));
        loadThread.join(1000L);

        assertFalse(loadThread.isAlive());
        assertTrue(loadFailure.get() instanceof IllegalStateException);
        assertTrue(cleaned.await(1, TimeUnit.SECONDS));
        assertFalse(Files.exists(sgfFile));
      } finally {
        if (loadThread.isAlive() && !mirrorOutput.commands().isEmpty()) {
          invokeCommandResponseLine(
              mirror, buildSuccessResponseLine(mirrorOutput.commands().get(0)));
          loadThread.join(1000L);
        }
        Files.deleteIfExists(sgfFile);
      }
    }
  }



  @Test
  void loadSgfBindsAfterConsumedToImmediateResponse() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      setOutputStream(engine, new ImmediateResponseOutputStream(engine));

      Path sgfFile = Files.createTempFile("loadsgf-race-", ".sgf");
      AtomicInteger consumedCount = new AtomicInteger();
      try {
        engine.loadSgf(sgfFile, consumedCount::incrementAndGet);
      } finally {
        Files.deleteIfExists(sgfFile);
      }

      assertEquals(
          1,
          consumedCount.get(),
          "loadsgf immediate response should trigger its own callback once.");
      assertEquals(
          0, pendingHandlerCount(engine), "loadsgf response callback queue should be drained.");
    }
  }

  @Test
  void unnumberedNumericPayloadResponsesConsumeOrdinaryCallbacks() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      setOutputStream(engine, new PassiveOutputStream());

      String[] numericPayloadResponses = {"= 2", "= 7.5", "? 404"};
      AtomicInteger callbackCount = new AtomicInteger();
      for (String responseLine : numericPayloadResponses) {
        sendCommandWithResponse(engine, "name", callbackCount::incrementAndGet);
        assertEquals(
            1,
            pendingHandlerCount(engine),
            "ordinary command should enqueue one pending callback before response.");

        invokeResponseHandlerForLine(engine, responseLine);
        assertEquals(
            0,
            pendingHandlerCount(engine),
            "ordinary command callback should be consumed for response: " + responseLine);
      }

      assertEquals(
          numericPayloadResponses.length,
          callbackCount.get(),
          "every numeric payload response should trigger its own ordinary callback.");
    }
  }

  @Test
  void queuedLoadSgfTimedOutBeforeSendIsDroppedAndNeverSentLater() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      engine.requireResponseBeforeSend = true;
      setCurrentCmdNum(engine, -1);

      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      Path sgfFile = Files.createTempFile("loadsgf-queued-timeout-", ".sgf");
      AtomicInteger consumedCount = new AtomicInteger();
      try {
        IllegalStateException thrown =
            assertThrows(
                IllegalStateException.class,
                () -> engine.loadSgf(sgfFile, consumedCount::incrementAndGet));
        assertTrue(
            thrown.getMessage().contains("Timed out while waiting for loadsgf response"),
            "timeout should be surfaced when queued loadsgf never gets dispatched.");
      } finally {
        Files.deleteIfExists(sgfFile);
      }

      assertEquals(1, consumedCount.get(), "timeout cleanup should still trigger afterConsumed.");
      assertEquals(0, commandQueueSize(engine), "timed-out queued loadsgf should be dropped.");
      assertEquals(0, output.commands().size(), "stalled loadsgf should stay unsent before retry.");

      engine.setResponseUpToDate();
      invokeTrySendCommandFromQueue(engine);

      assertEquals(
          0,
          output.commands().size(),
          "timed-out queued loadsgf must stay unsent after queue is retried.");
    }
  }

  @Test
  void loadSgfSendFailureRetiresOutstandingResponseCount() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      setOutputStream(engine, new FailingCommandOutputStream("loadsgf "));

      int outstandingBefore = outstandingResponseCount(engine);
      Path sgfFile = Files.createTempFile("loadsgf-send-failure-", ".sgf");
      AtomicInteger consumedCount = new AtomicInteger();
      try {
        IllegalStateException thrown =
            assertThrows(
                IllegalStateException.class,
                () -> engine.loadSgf(sgfFile, consumedCount::incrementAndGet));
        assertTrue(
            thrown.getMessage().contains("loadsgf"), "send failures should keep loadsgf context.");
      } finally {
        Files.deleteIfExists(sgfFile);
      }

      assertEquals(1, consumedCount.get(), "failed loadsgf should still finish afterConsumed.");
      assertEquals(
          outstandingBefore,
          outstandingResponseCount(engine),
          "failed loadsgf should retire its outstanding response count.");
    }
  }

  @Test
  void loadSgfNoResponseTimeoutRetiresOutstandingAndIsolatesLateResponse() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      engine.isKatago = false;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      int outstandingBefore = outstandingResponseCount(engine);
      Path sgfFile = Files.createTempFile("loadsgf-no-response-timeout-", ".sgf");
      AtomicInteger consumedCount = new AtomicInteger();
      try {
        IllegalStateException thrown =
            assertThrows(
                IllegalStateException.class,
                () -> engine.loadSgf(sgfFile, consumedCount::incrementAndGet));
        assertTrue(
            thrown.getMessage().contains("Timed out while waiting for loadsgf response"),
            "loadsgf without response should fail by timeout.");
      } finally {
        Files.deleteIfExists(sgfFile);
      }

      assertEquals(1, consumedCount.get(), "timed-out loadsgf should still finish afterConsumed.");
      assertEquals(
          outstandingBefore,
          outstandingResponseCount(engine),
          "timed-out loadsgf should retire its outstanding response count.");
      assertEquals(1, output.commands().size(), "timeout case should send exactly one loadsgf.");

      AtomicInteger callbackCount = new AtomicInteger();
      sendCommandWithResponse(engine, "name", callbackCount::incrementAndGet);
      assertEquals(1, pendingHandlerCount(engine), "next command should enqueue one callback.");

      invokeResponseHandlerForLine(engine, buildSuccessResponseLine(output.commands().get(0)));
      assertEquals(0, callbackCount.get(), "late timed-out loadsgf response should be ignored.");

      invokeResponseHandlerForLine(engine, "=");
      assertEquals(1, callbackCount.get(), "next callback should run on its own response.");
      assertEquals(0, pendingHandlerCount(engine), "pending callback queue should drain.");
    }
  }

  @Test
  void loadSgfNoResponseTimeoutAutoSendsAlreadyQueuedCommand() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      engine.requireResponseBeforeSend = true;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      Path sgfFile = Files.createTempFile("loadsgf-no-response-queued-", ".sgf");
      AtomicInteger consumedCount = new AtomicInteger();
      AtomicInteger callbackCount = new AtomicInteger();
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  engine.loadSgf(sgfFile, consumedCount::incrementAndGet);
                } catch (Throwable ex) {
                  loadFailure.set(ex);
                }
              },
              "loadsgf-no-response-queued");
      try {
        loadThread.start();
        waitForCommandCount(
            output, 1, 2000L, "loadsgf should be sent before queueing follow-up commands.");
        sendCommandWithResponse(engine, "name", callbackCount::incrementAndGet);
        assertEquals(1, output.commands().size(), "follow-up command should stay queued.");

        loadThread.join(9000L);
        assertFalse(loadThread.isAlive(), "loadsgf timeout should release the waiting thread.");
        assertEquals(1, consumedCount.get(), "timeout cleanup should still run afterConsumed.");
        assertTrue(
            loadFailure.get() instanceof IllegalStateException,
            "loadsgf no-response timeout should surface as failure.");
        assertTrue(
            loadFailure.get().getMessage().contains("Timed out while waiting for loadsgf response"),
            "loadsgf no-response timeout should keep timeout context.");

        waitForCommandCount(output, 2, 2000L, "queued command should auto-send after timeout.");
        assertEquals("name", output.commands().get(1), "queued command should be sent next.");
        assertEquals(0, callbackCount.get(), "queued callback should wait for its own response.");
      } finally {
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void queuedLoadSgfSendFailureAutoSendsAlreadyQueuedCommand() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      engine.requireResponseBeforeSend = true;
      RecordingFailingOutputStream output = new RecordingFailingOutputStream("loadsgf ");
      setOutputStream(engine, output);

      sendCommandWithResponse(engine, "name", () -> {});
      assertEquals(1, output.commands().size(), "command A should be sent immediately.");

      Path sgfFile = Files.createTempFile("loadsgf-send-failure-queued-", ".sgf");
      AtomicInteger consumedCount = new AtomicInteger();
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  engine.loadSgf(sgfFile, consumedCount::incrementAndGet);
                } catch (Throwable ex) {
                  loadFailure.set(ex);
                }
              },
              "loadsgf-send-failure-queued");
      try {
        loadThread.start();
        waitForQueueSize(
            engine, 1, 2000L, "loadsgf should queue while waiting for command A response.");
        engine.sendCommandNoLeelaz2("version");
        assertEquals(
            2,
            commandQueueSize(engine),
            "follow-up command should queue behind waiting loadsgf before response.");

        invokeCommandResponseLine(engine, "=");

        waitForCommandCount(
            output, 2, 2000L, "queued follow-up command should auto-send after loadsgf failure.");
        assertEquals("version", output.commands().get(1), "follow-up command should be sent next.");

        loadThread.join(3000L);
        assertFalse(loadThread.isAlive(), "loadsgf send failure should release waiting thread.");
        assertTrue(
            loadFailure.get() instanceof IllegalStateException,
            "queued loadsgf send failure should surface as failure.");
        assertTrue(
            loadFailure.get().getMessage().contains("loadsgf"),
            "queued loadsgf send failure should keep loadsgf context.");
        assertEquals(1, consumedCount.get(), "failed loadsgf should still finish afterConsumed.");
        assertEquals(0, commandQueueSize(engine), "queue should drain after sending follow-up.");
      } finally {
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void queuedLoadSgfRawWriteFailureClearsStaleBytesBeforeQueueAdvance() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      engine.requireResponseBeforeSend = true;
      RecordingOutputStream initialOutput = new RecordingOutputStream();
      setOutputStream(engine, initialOutput);

      sendCommandWithResponse(engine, "name", () -> {});
      assertEquals(1, initialOutput.commands().size(), "command A should be sent immediately.");

      FailOnFirstRawWriteOutputStream rawOutput = new FailOnFirstRawWriteOutputStream();
      setOutputStream(engine, rawOutput);

      Path sgfFile = Files.createTempFile("loadsgf-raw-write-failure-", ".sgf");
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  engine.loadSgf(sgfFile, () -> {});
                } catch (Throwable ex) {
                  loadFailure.set(ex);
                }
              },
              "loadsgf-raw-write-failure");
      try {
        loadThread.start();
        waitForQueueSize(
            engine, 1, 2000L, "loadsgf should queue while waiting for command A response.");
        engine.sendCommandNoLeelaz2("version");
        assertEquals(2, commandQueueSize(engine), "follow-up command should queue behind loadsgf.");

        invokeCommandResponseLine(engine, "=");

        waitForCommandCount(rawOutput, 1, 2000L, "follow-up command should send after failure.");
        loadThread.join(3000L);
        assertFalse(loadThread.isAlive(), "loadsgf send failure should release waiting thread.");
        assertTrue(loadFailure.get() instanceof IllegalStateException, "loadsgf should fail fast.");
        assertTrue(
            loadFailure.get().getMessage().contains("loadsgf"), "failure should keep context.");

        List<String> commands = rawOutput.commands();
        assertEquals(
            1, commands.size(), "failed loadsgf bytes must be discarded before next flush.");
        assertEquals("version", commands.get(0), "only live queued command should be written.");
      } finally {
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void queuedLoadSgfPartialRawWriteFailureInvalidatesPollutedOutputStream() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      engine.requireResponseBeforeSend = true;
      RecordingOutputStream initialOutput = new RecordingOutputStream();
      setOutputStream(engine, initialOutput);

      sendCommandWithResponse(engine, "name", () -> {});
      assertEquals(1, initialOutput.commands().size(), "command A should be sent immediately.");

      PartialRawWriteThenFailOutputStream pollutedOutput =
          new PartialRawWriteThenFailOutputStream(8);
      setOutputStream(engine, pollutedOutput);

      Path sgfFile = Files.createTempFile("loadsgf-partial-raw-write-failure-", ".sgf");
      AtomicReference<Throwable> loadFailure = new AtomicReference<>();
      Thread loadThread =
          new Thread(
              () -> {
                try {
                  engine.loadSgf(sgfFile, () -> {});
                } catch (Throwable ex) {
                  loadFailure.set(ex);
                }
              },
              "loadsgf-partial-raw-write-failure");
      try {
        loadThread.start();
        waitForQueueSize(
            engine, 1, 2000L, "loadsgf should queue while waiting for command A response.");

        invokeCommandResponseLine(engine, "=");
        loadThread.join(3000L);

        assertFalse(
            loadThread.isAlive(), "loadsgf partial raw write failure should release thread.");
        assertTrue(loadFailure.get() instanceof IllegalStateException, "loadsgf should fail fast.");
        assertTrue(
            loadFailure.get().getMessage().contains("loadsgf"),
            "failure should keep loadsgf context.");
        String pollutedText = pollutedOutput.writtenText();
        int firstSpace = pollutedText.indexOf(' ');
        assertTrue(
            firstSpace > 0
                && pollutedText.substring(0, firstSpace).chars().allMatch(Character::isDigit),
            "polluted stream should capture the command id prefix before invalidation.");
        assertTrue(
            outputStreamField(engine) == null, "polluted output stream should be invalidated.");

        RecordingOutputStream recoveryOutput = new RecordingOutputStream();
        setOutputStream(engine, recoveryOutput);
        sendCommandWithResponse(engine, "version", () -> {});

        assertEquals(
            List.of("version"),
            recoveryOutput.commands(),
            "recovered output stream should only send clean follow-up commands.");
      } finally {
        loadThread.join(1000L);
        Files.deleteIfExists(sgfFile);
      }
    }
  }

  @Test
  void retiredLateLoadSgfResponseDoesNotAdvanceWindowOrSendQueuedCommandEarly() throws Exception {
    try (TestHarness harness = TestHarness.open()) {
      Leelaz engine = new Leelaz("");
      Lizzie.leelaz = engine;
      RecordingOutputStream output = new RecordingOutputStream();
      setOutputStream(engine, output);

      sendCommandWithResponse(engine, "name", () -> {});
      engine.requireResponseBeforeSend = true;
      enqueueCommandWithoutSending(engine, "version", () -> {});
      setCurrentCmdNum(engine, intField(engine, "cmdNumber") - 2);
      assertEquals(
          1,
          output.commands().size(),
          "command B should stay queued before command A receives a real response.");
      assertEquals(1, commandQueueSize(engine), "command B should stay queued.");

      int currentBeforeLateResponse = intField(engine, "currentCmdNum");
      invokeCommandResponseLine(engine, "=999");

      assertEquals(
          currentBeforeLateResponse,
          intField(engine, "currentCmdNum"),
          "late retired loadsgf response should not advance currentCmdNum.");
      assertEquals(
          1,
          output.commands().size(),
          "late retired loadsgf response should not release the sending window.");
      assertEquals(1, commandQueueSize(engine), "command B should remain queued.");

      invokeCommandResponseLine(engine, "=");
      assertEquals(
          2,
          output.commands().size(),
          "command B should be sent after command A receives its own response.");
    }
  }

  private static void setOutputStream(Leelaz engine, OutputStream stream) throws Exception {
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    outputField.set(engine, Leelaz.createCommandOutputStream(stream));
  }

  private static Object outputStreamField(Leelaz engine) throws Exception {
    Field outputField = Leelaz.class.getDeclaredField("outputStream");
    outputField.setAccessible(true);
    return outputField.get(engine);
  }

  private static int pendingHandlerCount(Leelaz engine) throws Exception {
    Field pendingField = Leelaz.class.getDeclaredField("pendingResponseHandlers");
    pendingField.setAccessible(true);
    Object pending = pendingField.get(engine);
    if (pending == null) {
      return 0;
    }
    return ((java.util.ArrayDeque<?>) pending).size();
  }

  private static int commandQueueSize(Leelaz engine) throws Exception {
    Field queueField = Leelaz.class.getDeclaredField("cmdQueue");
    queueField.setAccessible(true);
    Object queue = queueField.get(engine);
    if (queue == null) {
      return 0;
    }
    return ((java.util.ArrayDeque<?>) queue).size();
  }

  private static int outstandingResponseCount(Leelaz engine) throws Exception {
    int commandNumber = intField(engine, "cmdNumber");
    int currentCommandNumber = intField(engine, "currentCmdNum");
    return Math.max(0, commandNumber - 1 - currentCommandNumber);
  }

  private static int intField(Leelaz engine, String fieldName) throws Exception {
    Field field = Leelaz.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Integer) field.get(engine);
  }

  private static void setCurrentCmdNum(Leelaz engine, int currentCmdNum) throws Exception {
    Field currentField = Leelaz.class.getDeclaredField("currentCmdNum");
    currentField.setAccessible(true);
    currentField.set(engine, currentCmdNum);
  }

  private static void invokeTrySendCommandFromQueue(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("trySendCommandFromQueue");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeNextResponseHandler(Leelaz engine) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("runNextPendingResponseHandler");
    method.setAccessible(true);
    method.invoke(engine);
  }

  private static void invokeResponseHandlerForLine(Leelaz engine, String line) throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod("runPendingResponseHandlerForLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void invokeCommandResponseLine(Leelaz engine, String line) throws Exception {
    Method method = Leelaz.class.getDeclaredMethod("processCommandResponseLine", String.class);
    method.setAccessible(true);
    method.invoke(engine, line);
  }

  private static void waitForCommandCount(
      RecordingOutputStream output, int expectedCount, long timeoutMillis, String message)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (output.commands().size() >= expectedCount) {
        return;
      }
      Thread.sleep(10L);
    }
    assertTrue(output.commands().size() >= expectedCount, message);
  }

  private static void waitForCommandCount(
      RecordingFailingOutputStream output, int expectedCount, long timeoutMillis, String message)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (output.commands().size() >= expectedCount) {
        return;
      }
      Thread.sleep(10L);
    }
    assertTrue(output.commands().size() >= expectedCount, message);
  }

  private static void waitForCommandCount(
      FailOnFirstRawWriteOutputStream output, int expectedCount, long timeoutMillis, String message)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (output.commands().size() >= expectedCount) {
        return;
      }
      Thread.sleep(10L);
    }
    assertTrue(output.commands().size() >= expectedCount, message);
  }

  private static void waitForQueueSize(
      Leelaz engine, int expectedCount, long timeoutMillis, String message) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (commandQueueSize(engine) >= expectedCount) {
        return;
      }
      Thread.sleep(10L);
    }
    assertTrue(commandQueueSize(engine) >= expectedCount, message);
  }

  private static void sendCommandWithResponse(Leelaz engine, String command, Runnable onResponse)
      throws Exception {
    Method method =
        Leelaz.class.getDeclaredMethod(
            "sendCommand", String.class, Runnable.class, boolean.class, boolean.class);
    method.setAccessible(true);
    method.invoke(engine, command, onResponse, false, false);
  }

  @SuppressWarnings("unchecked")
  private static void enqueueCommandWithoutSending(
      Leelaz engine, String command, Runnable onResponse) throws Exception {
    Method commandQueueMethod = Leelaz.class.getDeclaredMethod("commandQueue");
    commandQueueMethod.setAccessible(true);
    java.util.ArrayDeque<Object> queue =
        (java.util.ArrayDeque<Object>) commandQueueMethod.invoke(engine);

    Class<?> sendFailureHandlerClass =
        Class.forName("featurecat.lizzie.analysis.Leelaz$CommandSendFailureHandler");
    Class<?> queuedCommandClass = Class.forName("featurecat.lizzie.analysis.Leelaz$QueuedCommand");
    java.lang.reflect.Constructor<?> constructor =
        queuedCommandClass.getDeclaredConstructor(
            String.class, Runnable.class, sendFailureHandlerClass, boolean.class);
    constructor.setAccessible(true);
    Object queuedCommand = constructor.newInstance(command, onResponse, null, false);

    synchronized (queue) {
      queue.addLast(queuedCommand);
    }

    Field cmdNumberField = Leelaz.class.getDeclaredField("cmdNumber");
    cmdNumberField.setAccessible(true);
    cmdNumberField.set(engine, intField(engine, "cmdNumber") + 1);
  }

  private static String buildSuccessResponseLine(String command) {
    String trimmed = command == null ? "" : command.trim();
    int firstSpace = trimmed.indexOf(' ');
    if (firstSpace <= 0) {
      return "=";
    }
    String firstToken = trimmed.substring(0, firstSpace);
    for (int index = 0; index < firstToken.length(); index++) {
      if (!Character.isDigit(firstToken.charAt(index))) {
        return "=";
      }
    }
    return "=" + firstToken;
  }

  private static int responseCommandId(String command) {
    String firstToken = command.trim().split(" ", 2)[0];
    try {
      return Integer.parseInt(firstToken);
    } catch (NumberFormatException failure) {
      return -1;
    }
  }

  private static Config minimalConfig() throws Exception {
    Config config = allocate(Config.class);
    config.extraMode = ExtraMode.Normal;
    config.alwaysGtp = false;
    return config;
  }

  @SuppressWarnings("unchecked")
  private static <T> T allocate(Class<T> type) throws Exception {
    return (T) UnsafeHolder.UNSAFE.allocateInstance(type);
  }

  private static final class ImmediateResponseOutputStream extends OutputStream {
    private final Leelaz engine;

    private ImmediateResponseOutputStream(Leelaz engine) {
      this.engine = engine;
    }

    @Override
    public void write(int b) {}

    @Override
    public void flush() throws IOException {
      try {
        invokeNextResponseHandler(engine);
      } catch (Exception ex) {
        throw new IOException("failed to simulate immediate response", ex);
      }
    }
  }

  private static final class PassiveOutputStream extends OutputStream {
    @Override
    public void write(int b) {}
  }

  private static final class RecordingOutputStream extends OutputStream {
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    @Override
    public synchronized void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public synchronized void flush() {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.isEmpty()) {
        return;
      }
      commands.add(command);
    }

    private synchronized List<String> commands() {
      return new ArrayList<>(commands);
    }
  }

  private static final class FailingCommandOutputStream extends OutputStream {
    private final String failPrefix;
    private final StringBuilder currentCommand = new StringBuilder();

    private FailingCommandOutputStream(String failPrefix) {
      this.failPrefix = failPrefix;
    }

    @Override
    public void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.startsWith(failPrefix) || command.contains(" " + failPrefix)) {
        throw new IOException("simulated flush failure: " + command);
      }
    }
  }

  private static final class RecordingFailingOutputStream extends OutputStream {
    private final String failPrefix;
    private final StringBuilder currentCommand = new StringBuilder();
    private final List<String> commands = new ArrayList<>();

    private RecordingFailingOutputStream(String failPrefix) {
      this.failPrefix = failPrefix;
    }

    @Override
    public synchronized void write(int b) {
      currentCommand.append((char) b);
    }

    @Override
    public synchronized void flush() throws IOException {
      String command = currentCommand.toString().trim();
      currentCommand.setLength(0);
      if (command.isEmpty()) {
        return;
      }
      if (command.startsWith(failPrefix) || command.contains(" " + failPrefix)) {
        throw new IOException("simulated flush failure: " + command);
      }
      commands.add(command);
    }

    private synchronized List<String> commands() {
      return new ArrayList<>(commands);
    }
  }

  private static final class FailOnFirstRawWriteOutputStream extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private boolean failedFirstWrite;

    @Override
    public synchronized void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
      if (!failedFirstWrite) {
        failedFirstWrite = true;
        throw new IOException("simulated first raw write failure");
      }
      bytes.write(b, off, len);
    }

    private synchronized List<String> commands() {
      String[] lines = new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\\R");
      List<String> parsed = new ArrayList<>();
      for (String line : lines) {
        String command = line.trim();
        if (!command.isEmpty()) {
          parsed.add(command);
        }
      }
      return parsed;
    }
  }

  private static final class PartialRawWriteThenFailOutputStream extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int failAfterBytes;
    private int writtenBytes;
    private boolean failed;

    private PartialRawWriteThenFailOutputStream(int failAfterBytes) {
      this.failAfterBytes = failAfterBytes;
    }

    @Override
    public synchronized void write(int b) throws IOException {
      if (!failed && writtenBytes >= failAfterBytes) {
        failed = true;
        throw new IOException("simulated partial raw write failure");
      }
      bytes.write(b);
      writtenBytes++;
    }

    private synchronized String writtenText() {
      return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static final class SilentFrame extends LizzieFrame {
    private SilentFrame() {
      super();
    }

    @Override
    public void refresh() {}
  }

  private static final class SilentGtpConsole extends GtpConsolePane {
    private SilentGtpConsole() {
      super((Window) null);
    }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addCommand(String command, int commandNumber, String engineName) {}

    @Override
    public void addCommandForEngineGame(
        String command, int commandNumber, String engineName, boolean isBlack) {}

    @Override
    public void addLine(String line) {}
  }

  private static final class TestHarness implements AutoCloseable {
    private final Config previousConfig;
    private final LizzieFrame previousFrame;
    private final GtpConsolePane previousGtpConsole;
    private final Leelaz previousLeelaz;
    private final Leelaz previousLeelaz2;
    private final boolean previousEngineGameFlag;

    private TestHarness(
        Config previousConfig,
        LizzieFrame previousFrame,
        GtpConsolePane previousGtpConsole,
        Leelaz previousLeelaz,
        Leelaz previousLeelaz2,
        boolean previousEngineGameFlag) {
      this.previousConfig = previousConfig;
      this.previousFrame = previousFrame;
      this.previousGtpConsole = previousGtpConsole;
      this.previousLeelaz = previousLeelaz;
      this.previousLeelaz2 = previousLeelaz2;
      this.previousEngineGameFlag = previousEngineGameFlag;
    }

    private static TestHarness open() throws Exception {
      TestHarness harness =
          new TestHarness(
              Lizzie.config,
              Lizzie.frame,
              Lizzie.gtpConsole,
              Lizzie.leelaz,
              Lizzie.leelaz2,
              EngineManager.isEngineGame);
      Lizzie.config = minimalConfig();
      Lizzie.frame = allocate(SilentFrame.class);
      Lizzie.gtpConsole = allocate(SilentGtpConsole.class);
      Lizzie.leelaz = null;
      Lizzie.leelaz2 = null;
      EngineManager.isEngineGame = false;
      return harness;
    }

    @Override
    public void close() {
      Lizzie.config = previousConfig;
      Lizzie.frame = previousFrame;
      Lizzie.gtpConsole = previousGtpConsole;
      Lizzie.leelaz = previousLeelaz;
      Lizzie.leelaz2 = previousLeelaz2;
      EngineManager.isEngineGame = previousEngineGameFlag;
    }
  }

  private static final class UnsafeHolder {
    private static final sun.misc.Unsafe UNSAFE = loadUnsafe();

    private static sun.misc.Unsafe loadUnsafe() {
      try {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Failed to access Unsafe", ex);
      }
    }
  }
}
