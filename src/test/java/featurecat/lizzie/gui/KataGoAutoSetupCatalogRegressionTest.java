package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoAutoSetupHelper;
import featurecat.lizzie.util.KataGoAutoSetupHelper.EngineValidationResult;
import featurecat.lizzie.util.KataGoAutoSetupHelper.EngineValidationStatus;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import featurecat.lizzie.util.KataGoRuntimeHelper;
import java.awt.Cursor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

class KataGoAutoSetupCatalogRegressionTest {
  @TempDir Path root;

  @Test
  void cancellingKeepsStopDisabledEvenWhenMoreProgressArrives() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    var session = new KataGoAutoSetupHelper.DownloadSession();
    set(dialog, "activeDownloadSession", session);
    set(dialog, "controlsBusy", true);
    set(dialog, "progressPanel", new JPanel());
    set(dialog, "footerPanel", new JPanel());
    set(dialog, "progressBar", new JProgressBar());
    Method stop = KataGoAutoSetupDialog.class.getDeclaredMethod("stopActiveDownload");
    Method progress =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "setBusy", boolean.class, String.class, long.class, long.class);
    stop.setAccessible(true);
    progress.setAccessible(true);
    JFontButton stopButton = (JFontButton) get(dialog, "btnStopDownload");
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            progress.invoke(dialog, true, "Working", 0L, -1L);
            assertTrue(stopButton.isEnabled());
            stop.invoke(dialog);
            assertTrue(session.isCancelled());
            assertFalse(stopButton.isEnabled());
            progress.invoke(dialog, true, "Late progress", 100L, 1000L);
            assertFalse(stopButton.isEnabled(), "late progress must preserve the cancelling state");
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
  }

  @Test
  void olderValidationCannotOverwriteANewerCheckForTheSameExecutable() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    SetupSnapshot selected = snapshot(root.resolve("weight.bin.gz"));
    EngineValidationResult current = validation("1.18.2");
    set(dialog, "snapshot", selected);
    set(dialog, "engineValidationRequestId", 2L);
    set(dialog, "engineValidationResult", current);
    Method complete =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "completeEngineValidation", long.class, Path.class, EngineValidationResult.class);
    complete.setAccessible(true);
    complete.invoke(dialog, 1L, selected.enginePath, validation("1.17.0"));
    assertSame(current, get(dialog, "engineValidationResult"));
    set(dialog, "disposed", true);
    complete.invoke(dialog, 2L, selected.enginePath, validation("1.17.0"));
    assertSame(
        current, get(dialog, "engineValidationResult"), "disposal invalidates late results too");
  }

  @Test
  void lateTensorRtRepairDoesNotPublishSuccessAfterCancellationOrDisposal() throws Exception {
    SetupSnapshot repairedSnapshot = snapshot(root.resolve("weight.bin.gz"));
    Method complete =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "completeTensorRtRepair",
            KataGoAutoSetupHelper.DownloadSession.class,
            KataGoRuntimeHelper.TensorRtInstallStatus.class,
            SetupSnapshot.class);
    complete.setAccessible(true);
    HeadlessRefreshDialog cancelled = unconstructedDialog();
    initializeRefreshControls(cancelled);
    var session = new KataGoAutoSetupHelper.DownloadSession();
    session.cancel();
    set(cancelled, "activeDownloadSession", session);
    set(cancelled, "controlsBusy", true);
    HeadlessRefreshDialog disposed = unconstructedDialog();
    set(disposed, "disposed", true);

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            // The files may have finished installing, but late success cannot show a summary or
            // replace the window's snapshot. Stopping while open still ends its busy state.
            complete.invoke(cancelled, session, null, repairedSnapshot);
            assertEquals(null, get(cancelled, "snapshot"));
            assertEquals(false, get(cancelled, "controlsBusy"));
            assertFalse(((JPanel) get(cancelled, "progressPanel")).isVisible());
            complete.invoke(
                disposed, new KataGoAutoSetupHelper.DownloadSession(), null, repairedSnapshot);
            assertEquals(null, get(disposed, "snapshot"));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
  }

  @Test
  void acceptedProfileKeepsControlsBusyUntilEngineSwitchHasCompleted() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    var pending = KataGoAutoSetupDialog.WeightSwitchDisplayState.active(null).begin(null);
    set(dialog, "weightSwitchDisplayState", pending);
    Method active = KataGoAutoSetupDialog.class.getDeclaredMethod("hasActiveBackgroundTask");
    active.setAccessible(true);
    assertTrue((boolean) active.invoke(dialog), "reload dispatch has no worker or timer yet");
    set(dialog, "weightSwitchDisplayState", pending.succeed(pending.token(), null));
    assertFalse((boolean) active.invoke(dialog));
  }

  @Test
  void disposingSetupCancelsItsWorkerSessionAndPendingSwitch() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    var session = new KataGoAutoSetupHelper.DownloadSession();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              started.countDown();
              try {
                release.await();
              } catch (InterruptedException expected) {
                interrupted.countDown();
              }
            });
    var timer = new javax.swing.Timer(60000, event -> {});
    set(dialog, "activeDownloadSession", session);
    set(dialog, "activeWorkerThread", worker);
    set(dialog, "pendingWeightSwitchTimer", timer);
    set(
        dialog,
        "weightSwitchDisplayState",
        KataGoAutoSetupDialog.WeightSwitchDisplayState.active(null).begin(null));
    Method cancel = KataGoAutoSetupDialog.class.getDeclaredMethod("cancelOwnedSetupTasks");
    cancel.setAccessible(true);
    worker.start();
    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            timer.start();
            try {
              cancel.invoke(dialog);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
            assertTrue(session.isCancelled());
            assertFalse(timer.isRunning());
            try {
              assertEquals(1L, get(dialog, "engineValidationRequestId"));
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
          });
      assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS));
    } finally {
      release.countDown();
      worker.join(5000);
    }
  }

  @Test
  void unavailableSavedEntryRefreshRestoresRetryWithoutUnlockingSidebar() throws Exception {
    // No native window is needed to exercise the real completion path. Missing/remote entries
    // have no later validation callback to repair these controls.
    HeadlessRefreshDialog dialog = unconstructedDialog();
    JFontButton refresh = new JFontButton();
    JFontButton close = new JFontButton();
    var sidebar = new KataGoAutoSetupDialog.ExactHitList<String>();
    set(dialog, "selectedBenchmarkEntryId", "saved-entry");
    set(dialog, "snapshot", null);
    set(dialog, "progressBar", new JProgressBar());
    set(dialog, "progressPanel", new JPanel());
    JPanel footer = new JPanel();
    set(dialog, "footerPanel", footer);
    set(dialog, "btnRefresh", refresh);
    set(dialog, "btnClose", close);
    set(dialog, "btnRemoteCompute", new JButton());
    set(dialog, "sectionNav", sidebar);
    Method complete =
        KataGoAutoSetupDialog.class.getDeclaredMethod("setStateRefreshPending", boolean.class);
    complete.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          refresh.setEnabled(false);
          close.setEnabled(false);
          sidebar.setEnabled(false);
          try {
            complete.invoke(dialog, false);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
          assertTrue(refresh.isEnabled(), "unavailable entries must remain refreshable");
          assertTrue(close.isEnabled());
          assertTrue(footer.isVisible(), "idle status and Close remain visible after inspection");
          assertFalse(
              sidebar.isEnabled(), "saved-entry scope must never unlock other setup sections");
        });
    set(dialog, "selectedBenchmarkEntryId", "");
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            complete.invoke(dialog, false);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
          assertTrue(sidebar.isEnabled(), "normal setup navigation must still be restored");
        });
    set(dialog, "measuredTuningBusy", true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            complete.invoke(dialog, false);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
          assertFalse(refresh.isEnabled(), "late refresh must preserve measured-review exclusion");
          assertFalse(sidebar.isEnabled());
          assertTrue(close.isEnabled(), "closing must remain possible during review");
        });
  }

  @Test
  void replacedTransformerOnUseIsRejectedBeforeAnyProfileWrite() throws Exception {
    String transformer = KataGoAutoSetupHelper.DEFAULT_TRANSFORMER_MODEL;
    String cnn = "ordinary_" + "x".repeat(transformer.length() - "ordinary_".length());
    Path weight = root.resolve("selected.bin");
    writeHeader(weight, cnn);
    SetupSnapshot selected = snapshot(weight).scanWeightCatalog();
    var modified = Files.getLastModifiedTime(weight);
    long size = Files.size(weight);
    writeHeader(weight, transformer);
    Files.setLastModifiedTime(weight, modified);
    assertEquals(size, Files.size(weight));
    assertEquals(
        cnn, selected.weightCatalog.modelName(weight), "rendering still uses its snapshot");
    Path settings = root.resolve("config.txt");
    Files.writeString(settings, "original configuration");
    byte[] original = Files.readAllBytes(settings);
    AtomicBoolean persisted = new AtomicBoolean();
    var error =
        assertThrows(
            KataGoAutoSetupDialog.WeightPreflightException.class,
            () ->
                KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(
                    selected,
                    validation("1.16.0"),
                    ignored -> {
                      persisted.set(true);
                      Files.writeString(settings, "changed configuration");
                      return null;
                    }));
    assertEquals(KataGoAutoSetupDialog.WeightSelectionCheck.INCOMPATIBLE, error.check);
    assertFalse(persisted.get());
    assertArrayEquals(original, Files.readAllBytes(settings));
  }

  @Test
  void compatibleReplacementStillRequiresFreshSelectionAndDeletedFileNeverWrites()
      throws Exception {
    Path weight = root.resolve("selected.bin");
    writeHeader(weight, "custom_first");
    SetupSnapshot selected = snapshot(weight).scanWeightCatalog();
    writeHeader(weight, "custom_other");
    var error =
        assertThrows(
            KataGoAutoSetupDialog.WeightPreflightException.class,
            () ->
                KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(
                    selected,
                    validation("1.18.2"),
                    ignored -> {
                      throw new AssertionError("stale selection must not persist");
                    }));
    assertEquals(KataGoAutoSetupDialog.WeightSelectionCheck.CHANGED, error.check);
    SetupSnapshot refreshed = selected.scanWeightCatalog();
    AtomicBoolean persisted = new AtomicBoolean();
    KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(
        refreshed,
        validation("1.18.2"),
        ignored -> {
          persisted.set(true);
          return null;
        });
    assertTrue(persisted.get());
    Files.delete(weight);
    var missing =
        assertThrows(
            KataGoAutoSetupDialog.WeightPreflightException.class,
            () ->
                KataGoAutoSetupDialog.applyWeightProfileAfterPreflight(
                    refreshed,
                    validation("1.18.2"),
                    ignored -> {
                      throw new AssertionError("missing weight must not persist");
                    }));
    assertEquals(KataGoAutoSetupDialog.WeightSelectionCheck.MISSING, missing.check);
  }

  @Test
  void reopeningDuringADownloadLeavesTheDownloadAlone() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    set(dialog, "selectedBenchmarkEntryId", "");
    set(dialog, "activeDownloadSession", new KataGoAutoSetupHelper.DownloadSession());
    set(dialog, "controlsBusy", true);
    set(dialog, "stateRefreshRequestId", 7L);

    // The toolbar and 更新官方权重 call refreshState on a dialog that is already open.
    SwingUtilities.invokeAndWait(dialog::refreshState);

    assertEquals(7L, get(dialog, "stateRefreshRequestId"), "no refresh replaced the download");
    assertEquals(null, get(dialog, "stateRefreshWorker"));
    assertEquals(true, get(dialog, "controlsBusy"), "a running download must remain busy");
  }

  @Test
  void reopeningAfterCloseDroppedDownloadCompletionResetsBusyState() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    initializeRefreshControls(dialog);
    var session = new KataGoAutoSetupHelper.DownloadSession();
    Thread worker = new Thread(() -> {});
    set(dialog, "activeDownloadSession", session);
    set(dialog, "activeWorkerThread", worker);
    set(dialog, "controlsBusy", true);
    set(dialog, "stateRefreshRequestId", 7L);
    Method close = KataGoAutoSetupDialog.class.getDeclaredMethod("closeOrCancelActiveTask");
    Method finished =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "clearActiveDownload", KataGoAutoSetupHelper.DownloadSession.class, Thread.class);
    Method dispose = KataGoAutoSetupDialog.class.getDeclaredMethod("cancelOwnedSetupTasks");
    close.setAccessible(true);
    finished.setAccessible(true);
    dispose.setAccessible(true);

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            close.invoke(dialog);
            assertTrue(session.isCancelled());
            assertEquals(8L, get(dialog, "stateRefreshRequestId"));
            // The download already completed; its catalog scan answers after close. The success
            // callback is dropped by requestId, so only the worker's finally cleanup is delivered.
            finished.invoke(dialog, session, worker);
            assertEquals(false, get(dialog, "controlsBusy"), "completion owns a fresh refresh now");
            assertEquals(9L, get(dialog, "stateRefreshRequestId"));
            assertTrue(((JPanel) get(dialog, "progressPanel")).isVisible());
            assertFalse(((JFontButton) get(dialog, "btnRefresh")).isEnabled());
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          } finally {
            try {
              dispose.invoke(dialog); // Cancel inspection before it can publish into this fixture.
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
          }
        });
    SwingUtilities.invokeAndWait(() -> {});
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void reopeningBeforeLateTaskCompletionStartsAFreshRefresh(boolean download) throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    initializeRefreshControls(dialog);
    var session = download ? new KataGoAutoSetupHelper.DownloadSession() : null;
    Thread worker = new Thread(() -> {});
    set(dialog, "activeDownloadSession", session);
    set(dialog, "activeWorkerThread", worker);
    set(dialog, "controlsBusy", true);
    set(dialog, "stateRefreshRequestId", 7L);
    Method close = KataGoAutoSetupDialog.class.getDeclaredMethod("closeOrCancelActiveTask");
    Method finished =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "clearActiveDownload", KataGoAutoSetupHelper.DownloadSession.class, Thread.class);
    Method dispose = KataGoAutoSetupDialog.class.getDeclaredMethod("cancelOwnedSetupTasks");
    close.setAccessible(true);
    finished.setAccessible(true);
    dispose.setAccessible(true);

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            close.invoke(dialog);
            dialog.refreshState(); // Reopen while a download or local import still owns its worker.
            assertEquals(8L, get(dialog, "stateRefreshRequestId"));
            assertEquals(true, get(dialog, "controlsBusy"));
            // The old success publication is now stale. Its finally cleanup must refresh the
            // reopened view without accepting the old snapshot or leaving all controls disabled.
            finished.invoke(dialog, session, worker);
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            assertEquals(
                false, get(dialog, "controlsBusy"), "finished work cannot leave stale busy state");
            assertEquals(
                9L, get(dialog, "stateRefreshRequestId"), "a new snapshot owns the reopened view");
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          } finally {
            try {
              dispose.invoke(dialog);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
          }
        });
    SwingUtilities.invokeAndWait(() -> {});
  }

  @ParameterizedTest
  @CsvSource({"true,false", "false,false", "true,true", "false,true"})
  void localImportWorkerAlwaysFinishesAfterCloseAndEarlyReopen(boolean humanSl, boolean fail)
      throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    initializeRefreshControls(dialog);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Method close = KataGoAutoSetupDialog.class.getDeclaredMethod("closeOrCancelActiveTask");
    Method dispose = KataGoAutoSetupDialog.class.getDeclaredMethod("cancelOwnedSetupTasks");
    close.setAccessible(true);
    dispose.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () ->
            dialog.startWeightImport(
                () -> {
                  assertFalse(SwingUtilities.isEventDispatchThread());
                  started.countDown();
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                  if (fail) throw new IOException("late import failure");
                  return new SetupWeightImport(root.resolve("must-not-publish.bin.gz"), null);
                },
                humanSl));
    Thread worker = (Thread) get(dialog, "activeWorkerThread");
    assertTrue(started.await(5, TimeUnit.SECONDS));
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              close.invoke(dialog);
              dialog.refreshState();
              assertSame(worker, get(dialog, "activeWorkerThread"));
              assertEquals(true, get(dialog, "controlsBusy"));
              assertEquals(2L, get(dialog, "stateRefreshRequestId"));
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
          });
    } finally {
      release.countDown();
    }
    worker.join(5000L);
    assertFalse(worker.isAlive());
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            assertEquals(null, get(dialog, "activeWorkerThread"));
            assertEquals(false, get(dialog, "controlsBusy"));
            assertEquals(3L, get(dialog, "stateRefreshRequestId"));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          } finally {
            try {
              dispose.invoke(dialog);
            } catch (ReflectiveOperationException failure) {
              throw new AssertionError(failure);
            }
          }
        });
    SwingUtilities.invokeAndWait(() -> {});
  }

  @Test
  void lateCleanupCannotUnlockANewerWorkerOrPendingEngineSwitch() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    initializeRefreshControls(dialog);
    Thread current = new Thread(() -> {});
    set(dialog, "activeWorkerThread", current);
    set(dialog, "controlsBusy", true);
    set(dialog, "stateRefreshRequestId", 7L);
    Method finished =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "clearActiveDownload", KataGoAutoSetupHelper.DownloadSession.class, Thread.class);
    finished.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            finished.invoke(dialog, null, new Thread(() -> {}));
            assertSame(current, get(dialog, "activeWorkerThread"));
            assertEquals(true, get(dialog, "controlsBusy"));
            assertEquals(7L, get(dialog, "stateRefreshRequestId"));
            var pending = KataGoAutoSetupDialog.WeightSwitchDisplayState.active(null).begin(null);
            set(dialog, "weightSwitchDisplayState", pending);
            finished.invoke(dialog, null, current);
            assertEquals(null, get(dialog, "activeWorkerThread"));
            assertSame(pending, get(dialog, "weightSwitchDisplayState"));
            assertEquals(true, get(dialog, "controlsBusy"));
            assertEquals(7L, get(dialog, "stateRefreshRequestId"));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    SwingUtilities.invokeAndWait(() -> {});
  }

  @Test
  void workerCleanupRunsOnTheEventThreadAndCannotRefreshADisposedView() throws Exception {
    HeadlessRefreshDialog dialog = unconstructedDialog();
    initializeRefreshControls(dialog);
    var session = new KataGoAutoSetupHelper.DownloadSession();
    Thread owner = new Thread(() -> {});
    set(dialog, "activeDownloadSession", session);
    set(dialog, "activeWorkerThread", owner);
    set(dialog, "controlsBusy", true);
    set(dialog, "stateRefreshRequestId", 7L);
    Method finished =
        KataGoAutoSetupDialog.class.getDeclaredMethod(
            "clearActiveDownload", KataGoAutoSetupHelper.DownloadSession.class, Thread.class);
    finished.setAccessible(true);
    SwingUtilities.invokeAndWait(
        () -> {
          Thread cleanup =
              new Thread(
                  () -> {
                    try {
                      finished.invoke(dialog, session, owner);
                    } catch (ReflectiveOperationException failure) {
                      throw new AssertionError(failure);
                    }
                  });
          cleanup.start();
          try {
            cleanup.join(5000L);
            assertFalse(cleanup.isAlive());
            assertSame(session, get(dialog, "activeDownloadSession"));
            assertSame(owner, get(dialog, "activeWorkerThread"));
            set(dialog, "disposed", true); // The queued cleanup must not restart this closed owner.
          } catch (InterruptedException | ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            assertEquals(null, get(dialog, "activeDownloadSession"));
            assertEquals(null, get(dialog, "activeWorkerThread"));
            assertEquals(null, get(dialog, "stateRefreshWorker"));
            assertEquals(7L, get(dialog, "stateRefreshRequestId"));
          } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
          }
        });
  }

  private static void initializeRefreshControls(HeadlessRefreshDialog dialog) throws Exception {
    set(dialog, "selectedBenchmarkEntryId", "");
    set(dialog, "progressBar", new JProgressBar());
    set(dialog, "progressPanel", new JPanel());
    set(dialog, "footerPanel", new JPanel());
    set(dialog, "btnRemoteCompute", new JButton());
    set(dialog, "sectionNav", new KataGoAutoSetupDialog.ExactHitList<>());
    set(dialog, "weightCatalogList", new KataGoAutoSetupDialog.ExactHitList<>());
    set(dialog, "chkUseQuickAnalysisModel", new JCheckBox());
    set(dialog, "btnOfficialWeightTab", new JToggleButton());
    set(dialog, "btnCustomWeightTab", new JToggleButton());
    set(dialog, "cmbExperimentalBackend", new JComboBox<>());
    set(dialog, "measuredTuningDialog", new MeasuredTuningDialog());
    set(dialog, "tensorRtRepairSession", new KataGoRuntimeHelper.TensorRtRepairSession());
    set(dialog, "directedTensorRtBanner", new JPanel());
    for (String name :
        List.of("balancedRecommendation", "strongestRecommendation", "lightweightRecommendation")) {
      Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
      var constructor = field.getType().getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      set(
          dialog,
          name,
          constructor.newInstance(
              dialog, constructor.getParameterTypes()[1].getEnumConstants()[0]));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void anEarlierWeightListThatAnswersLateIsDropped(boolean latestFetchStartsFirst)
      throws Exception {
    List<KataGoAutoSetupHelper.RemoteWeightInfo> latest = List.of(listed("latest"));
    List<KataGoAutoSetupHelper.RemoteWeightInfo> earlier = List.of(listed("earlier"));
    try (WeightListFixture fixture = new WeightListFixture()) {
      WeightListRequest older = fixture.request(earlier);
      WeightListRequest newer = fixture.request(latest);
      // Request identity is independent of the order in which workers reach the source.
      (latestFetchStartsFirst ? newer : older).enterFetch();
      (latestFetchStartsFirst ? older : newer).enterFetch();
      newer.answerAndDrain();
      onEventThread(
          () -> {
            assertEquals(List.of(latest), fixture.dialog.shown);
            assertEquals(false, get(fixture.dialog, "remoteWeightsLoading"));
            return null;
          });
      older.answerAndDrain();
      onEventThread(
          () -> {
            assertEquals(
                List.of(latest),
                fixture.dialog.shown,
                "the earlier answer arrived last and was dropped");
            return null;
          });
    }
  }

  @Test
  void theListSaysItIsLoadingUntilTheAnswerArrivesNotThatItFailed() throws Exception {
    List<KataGoAutoSetupHelper.RemoteWeightInfo> answer = List.of(listed("answer"));
    try (WeightListFixture fixture = new WeightListFixture()) {
      WeightListRequest request = fixture.request(answer);
      request.enterFetch();
      onEventThread(
          () -> {
            // The list is loading until its answer arrives, not temporarily unavailable.
            assertEquals(true, get(fixture.dialog, "remoteWeightsLoading"));
            return null;
          });
      request.answerAndDrain();
      onEventThread(
          () -> {
            assertEquals(false, get(fixture.dialog, "remoteWeightsLoading"));
            assertEquals(List.of(answer), fixture.dialog.shown);
            return null;
          });
    }
  }

  private static KataGoAutoSetupHelper.RemoteWeightInfo listed(String name) throws Exception {
    var constructor =
        KataGoAutoSetupHelper.RemoteWeightInfo.class.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            boolean.class,
            boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        "Transformer",
        name,
        "https://media.katagotraining.org/uploaded/" + name + ".bin.gz",
        "2026-07-29",
        "",
        false,
        false);
  }

  @Test
  void aFailedWeightListAssertionReleasesItsOwnWorkersAndDrainsTheirCallbacks() throws Exception {
    List<KataGoAutoSetupHelper.RemoteWeightInfo> latest = List.of(listed("latest"));
    WeightListFixture fixture = new WeightListFixture();
    AssertionError original =
        new AssertionError("controlled failure before either source proceeds");
    AssertionError observed =
        assertThrows(
            AssertionError.class,
            () -> {
              try (fixture) {
                fixture.request(List.of(listed("earlier")));
                fixture.request(latest);
                throw original;
              }
            });
    assertSame(original, observed);
    assertEquals(0, observed.getSuppressed().length);
    for (WeightListRequest request : fixture.requests) {
      assertFalse(request.worker.isAlive());
    }
    onEventThread(
        () -> {
          assertEquals(List.of(latest), fixture.dialog.shown);
          assertEquals(false, get(fixture.dialog, "remoteWeightsLoading"));
          return null;
        });
  }

  private static <T> T onEventThread(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    SwingUtilities.invokeAndWait(task);
    return task.get();
  }

  private static final class WeightListFixture implements AutoCloseable {
    private final ListRecordingDialog dialog;
    private final List<WeightListRequest> requests = new ArrayList<>();

    private WeightListFixture() throws Exception {
      dialog =
          onEventThread(
              () -> {
                ListRecordingDialog created = unconstructed(ListRecordingDialog.class);
                set(created, "remoteWeightInfos", List.of(listed("cached")));
                return created;
              });
    }

    private WeightListRequest request(List<KataGoAutoSetupHelper.RemoteWeightInfo> answer)
        throws Exception {
      WeightListRequest request = new WeightListRequest(answer);
      requests.add(request);
      onEventThread(
          () -> {
            dialog.remoteWeightSource = request;
            dialog.loadRemoteWeightInfo();
            return null;
          });
      return request;
    }

    @Override
    public void close() throws Exception {
      // Release all gates before joining, including when an assertion fails before an answer.
      for (WeightListRequest request : requests) {
        request.allowFetch.countDown();
        request.allowAnswer.countDown();
      }
      AssertionError cleanupFailure = null;
      for (WeightListRequest request : requests) {
        try {
          request.joinWorker();
        } catch (Exception | AssertionError failure) {
          if (cleanupFailure == null) {
            cleanupFailure = new AssertionError("Weight-list worker cleanup failed");
          }
          cleanupFailure.addSuppressed(failure);
        }
      }
      SwingUtilities.invokeAndWait(() -> {});
      if (cleanupFailure != null) throw cleanupFailure;
    }
  }

  private static final class WeightListRequest implements KataGoAutoSetupDialog.RemoteWeightSource {
    private final List<KataGoAutoSetupHelper.RemoteWeightInfo> answer;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch allowFetch = new CountDownLatch(1);
    private final CountDownLatch enteredFetch = new CountDownLatch(1);
    private final CountDownLatch allowAnswer = new CountDownLatch(1);
    private Thread worker;

    private WeightListRequest(List<KataGoAutoSetupHelper.RemoteWeightInfo> answer) {
      this.answer = answer;
    }

    @Override
    public List<KataGoAutoSetupHelper.RemoteWeightInfo> fetch() throws IOException {
      worker = Thread.currentThread();
      started.countDown();
      try {
        allowFetch.await();
        enteredFetch.countDown();
        allowAnswer.await();
        return answer;
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IOException("Controlled weight-list request interrupted", failure);
      }
    }

    private void enterFetch() throws InterruptedException {
      allowFetch.countDown();
      assertTrue(
          enteredFetch.await(5, TimeUnit.SECONDS), "the selected request entered its source");
    }

    private void joinWorker() throws InterruptedException {
      assertTrue(started.await(5, TimeUnit.SECONDS), "the owned weight-list worker started");
      worker.join(5000L);
      assertFalse(worker.isAlive(), "the owned weight-list worker exited");
    }

    private void answerAndDrain() throws Exception {
      allowAnswer.countDown();
      joinWorker();
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  private static <T extends KataGoAutoSetupDialog> T unconstructed(Class<T> type) throws Exception {
    Field field = Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    T dialog = type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    for (Field button : KataGoAutoSetupDialog.class.getDeclaredFields()) {
      if (button.getType() == JFontButton.class) {
        button.setAccessible(true);
        button.set(dialog, new JFontButton());
      } else if (button.getType() == JLabel.class) {
        button.setAccessible(true);
        button.set(dialog, new JLabel());
      } else if (button.getType() == JTextArea.class) {
        button.setAccessible(true);
        button.set(dialog, new JTextArea());
      }
    }
    return dialog;
  }

  private static final class ListRecordingDialog extends KataGoAutoSetupDialog {
    private List<List<KataGoAutoSetupHelper.RemoteWeightInfo>> shown;

    private ListRecordingDialog() {
      super(null);
    }

    @Override
    void showRemoteWeightInfo(List<KataGoAutoSetupHelper.RemoteWeightInfo> infos) {
      assertTrue(SwingUtilities.isEventDispatchThread());
      if (shown == null) {
        shown = new java.util.ArrayList<>();
      }
      shown.add(infos);
    }
  }

  private static Object get(Object target, String name) throws ReflectiveOperationException {
    Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private SetupSnapshot snapshot(Path weight) {
    EngineData entry = new EngineData();
    entry.commands = "katago gtp -model \"" + weight + "\"";
    return KataGoAutoSetupHelper.inspectSavedEngine(entry);
  }

  private static void writeHeader(Path weight, String model) throws Exception {
    Files.writeString(weight, model + "\n15\n22\n19\n");
  }

  private static EngineValidationResult validation(String version) throws Exception {
    var constructor =
        EngineValidationResult.class.getDeclaredConstructor(
            EngineValidationStatus.class, String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(EngineValidationStatus.VALID, "KataGo v" + version);
  }

  private static HeadlessRefreshDialog unconstructedDialog() throws Exception {
    return unconstructed(HeadlessRefreshDialog.class);
  }

  private static void set(Object target, String name, Object value)
      throws ReflectiveOperationException {
    Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static final class HeadlessRefreshDialog extends KataGoAutoSetupDialog {
    private HeadlessRefreshDialog() {
      super(null);
    }

    @Override
    public void revalidate() {}

    @Override
    public void repaint() {}

    @Override
    public void setCursor(Cursor cursor) {}

    @Override
    public void setVisible(boolean visible) {}

    @Override
    public java.awt.Container getContentPane() {
      return new JPanel();
    }
  }
}
