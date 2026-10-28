package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class RemoteComputeEngineSwitchTest {
  @Test
  void zhiziDisconnectDoesNotCancelPendingLocalOrCustomSwitch() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.backend.zhizi = false;
          f.start();
          f.switcher.cancelZhizi();
          assertTrue(f.switcher.isPending());
          assertFalse(f.switcher.isZhiziPending());
          f.release.get(0).run();
          assertEquals(1, f.backend.submissions);
          f.switcher.cancel();
        });
  }

  @Test
  void backendIdentityIsCapturedBeforeReleaseRatherThanAtSubmission() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          FakeBackend original = new FakeBackend();
          java.util.concurrent.atomic.AtomicReference<FakeBackend> owner =
              new java.util.concurrent.atomic.AtomicReference<>(original);
          f.switcher.start(2, f.release::add, owner::get, f.results::add);
          owner.set(new FakeBackend());
          f.release.get(0).run();
          assertEquals(1, original.submissions);
          assertEquals(0, owner.get().submissions);
          f.switcher.cancel();
        });
  }

  @Test
  void productionBackendRejectsReplacedManagerOrCatalogSlot() throws Exception {
    featurecat.lizzie.analysis.Leelaz engine =
        new featurecat.lizzie.analysis.Leelaz(
            featurecat.lizzie.analysis.remote.RemoteComputeConfig.COMMAND_ZHIZI);
    featurecat.lizzie.analysis.Leelaz replacement = new featurecat.lizzie.analysis.Leelaz("katago");
    var engines = new java.util.ArrayList<>(java.util.List.of(engine));
    var manager = new featurecat.lizzie.analysis.EngineManager(engines);
    var owner = new java.util.concurrent.atomic.AtomicReference<>(manager);
    var backend = RemoteComputeEngineSwitch.backend(manager, owner::get, () -> engine, 0);
    engine.isLoaded = true;
    assertTrue(backend.alreadyActive(0));
    engines.set(0, replacement);
    assertFalse(backend.alreadyActive(0));
    assertNull(backend.submit(0));
    assertFalse(backend.poll(0, 0).success());
    engines.set(0, engine);
    owner.set(new featurecat.lizzie.analysis.EngineManager(engines));
    assertFalse(backend.alreadyActive(0));
    assertNull(backend.submit(0));
    owner.set(manager);
    engine.setEngineCommand("katago");
    assertFalse(backend.alreadyActive(0));
    assertNull(backend.submit(0));
  }

  @Test
  void dismissalOrDisconnectBeforeAutomaticAnalysisReleaseCannotStartEngine() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.start();
          f.switcher.cancel();
          f.release.get(0).run();
          assertEquals(0, f.backend.submissions);
          assertTrue(f.results.isEmpty());
          assertTrue(f.ticks.isEmpty());
        });
  }

  @Test
  void newerRequestWinsWhenReleaseCallbacksArriveOutOfOrder() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.start();
          f.start();
          f.release.get(1).run();
          f.release.get(0).run();
          assertEquals(1, f.backend.submissions);
          assertEquals(1, f.ticks.size());
          f.backend.result = new RemoteComputeEngineSwitch.Result(true, "");
          f.ticks.get(0).run();
          f.ticks.get(0).run();
          assertEquals(1, f.results.size());
          assertTrue(f.results.get(0).success());
          assertEquals(1, f.stops);
        });
  }

  @Test
  void queuedTimerAfterCancellationCannotPublishOrWarmAnalysis() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.start();
          f.release.get(0).run();
          f.switcher.cancel();
          f.switcher.cancel();
          f.backend.result = new RemoteComputeEngineSwitch.Result(true, "");
          f.ticks.get(0).run();
          assertEquals(1, f.stops);
          assertTrue(f.results.isEmpty());
        });
  }

  @Test
  void staleTimerCannotFinishNewRequest() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.start();
          f.release.get(0).run();
          f.start();
          f.release.get(1).run();
          f.backend.result = new RemoteComputeEngineSwitch.Result(true, "");
          f.ticks.get(0).run();
          assertTrue(f.results.isEmpty());
          f.ticks.get(1).run();
          assertEquals(1, f.results.size());
        });
  }

  @Test
  void pendingAndFailureRetainDetailWithoutPublishingSuccess() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture f = new Fixture();
          f.start();
          f.release.get(0).run();
          f.ticks.get(0).run();
          assertTrue(f.results.isEmpty());
          f.backend.result = new RemoteComputeEngineSwitch.Result(false, "connection failed");
          f.ticks.get(0).run();
          assertEquals(List.of(f.backend.result), f.results);
        });
  }

  @Test
  void rejectedSubmissionAndAlreadyActiveCompleteOnceWithoutTimer() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture rejected = new Fixture();
          rejected.backend.rejected = true;
          rejected.start();
          rejected.release.get(0).run();
          assertFalse(rejected.results.get(0).success());
          assertTrue(rejected.ticks.isEmpty());
          Fixture active = new Fixture();
          active.backend.active = true;
          active.start();
          active.release.get(0).run();
          assertTrue(active.results.get(0).success());
          assertEquals(0, active.backend.submissions);
          assertTrue(active.ticks.isEmpty());
        });
  }

  private static class Fixture {
    final List<Runnable> release = new ArrayList<>();
    final List<Runnable> ticks = new ArrayList<>();
    final List<RemoteComputeEngineSwitch.Result> results = new ArrayList<>();
    final FakeBackend backend = new FakeBackend();
    int stops;
    final RemoteComputeEngineSwitch switcher =
        new RemoteComputeEngineSwitch(
            callback -> {
              ticks.add(callback);
              return () -> stops++;
            });

    void start() {
      switcher.start(2, release::add, () -> backend, results::add);
    }
  }

  private static class FakeBackend implements RemoteComputeEngineSwitch.Backend {
    int submissions;
    boolean active;
    boolean zhizi = true;
    boolean rejected;
    RemoteComputeEngineSwitch.Result result;

    public boolean isZhizi(int index) {
      return zhizi;
    }

    public boolean alreadyActive(int index) {
      assertEquals(2, index);
      return active;
    }

    public Long submit(int index) {
      submissions++;
      return rejected ? null : (long) submissions;
    }

    public RemoteComputeEngineSwitch.Result poll(long token, int index) {
      return result;
    }
  }
}
