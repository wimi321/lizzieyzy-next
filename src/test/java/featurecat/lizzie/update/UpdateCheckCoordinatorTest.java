package featurecat.lizzie.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCheckCoordinatorTest {
  @TempDir Path tempDir;

  private featurecat.lizzie.Config previousConfig;
  private ResourceBundle previousResourceBundle;
  private String previousVersion;

  @BeforeEach
  void setUp() {
    previousConfig = Lizzie.config;
    previousResourceBundle = Lizzie.resourceBundle;
    previousVersion = Lizzie.nextVersion;
    Lizzie.config = ConfigTestHelper.createForTests(tempDir.resolve("coordinator-config"));
    Lizzie.config.uiConfig = new JSONObject();
    Lizzie.resourceBundle = null;
    Lizzie.nextVersion = "next-2026-08-01.1";
  }

  @AfterEach
  void tearDown() {
    Lizzie.config = previousConfig;
    Lizzie.resourceBundle = previousResourceBundle;
    Lizzie.nextVersion = previousVersion;
  }

  @Test
  void secondStartWhileInFlightIsRejectedAndDiscoveryRunsOnce() {
    UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator();
    FakePage page = new FakePage();
    RecordingDiscovery discovery = new RecordingDiscovery(UpdateCheckResult.noUpdate());
    QueueRunner runner = new QueueRunner();
    UpdateCheckSelection snapshot = officialSnapshot();

    assertTrue(coordinator.start(page, snapshot, discovery, unusedHandoff(), runner));
    assertFalse(page.checkEnabled);
    assertFalse(page.closeAllowed);
    assertEquals(0, discovery.seen.size());

    FakePage otherPage = new FakePage();
    assertFalse(coordinator.start(otherPage, snapshot, discovery, unusedHandoff(), runner));
    assertTrue(otherPage.checkEnabled);
    assertTrue(otherPage.closeAllowed);
    assertEquals(0, discovery.seen.size());

    runner.drain();

    assertEquals(1, discovery.seen.size());
    assertSame(snapshot, discovery.seen.get(0));
    assertEquals(UpdateCheckResult.Reason.NO_UPDATE, page.stayResults.get(0).reason);
    assertTrue(page.checkEnabled);
    assertTrue(page.closeAllowed);
  }

  @Test
  void nonOfferResultsStayOnPageAndRestoreCheckAndClose() {
    UpdateCheckResult[] stays =
        new UpdateCheckResult[] {
          UpdateCheckResult.unavailableBuild(),
          UpdateCheckResult.unsupportedPlatform(),
          UpdateCheckResult.noUpdate(),
          UpdateCheckResult.noPackage(),
          UpdateCheckResult.failure(UpdateCheckResult.FailureKind.FETCH)
        };
    for (UpdateCheckResult stay : stays) {
      UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator();
      FakePage page = new FakePage();
      RecordingDiscovery discovery = new RecordingDiscovery(stay);

      assertTrue(
          coordinator.start(
              page, officialSnapshot(), discovery, unusedHandoff(), ImmediateRunner.INSTANCE));

      assertEquals(1, page.stayResults.size());
      assertEquals(stay.reason, page.stayResults.get(0).reason);
      assertEquals(0, page.disposeCount);
      assertTrue(page.checkEnabled);
      assertTrue(page.closeAllowed);
    }
  }

  @Test
  void nonOfferTerminalAllowsRetryWithNewSnapshot() {
    UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator();
    FakePage page = new FakePage();
    List<UpdateCheckResult> results = new ArrayList<>();
    results.add(UpdateCheckResult.noUpdate());
    results.add(UpdateCheckResult.noPackage());
    RecordingDiscovery discovery = new RecordingDiscovery(results);
    UpdateCheckSelection first = officialSnapshot();
    UpdateCheckSelection second =
        UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.OFFICIAL_SITE, "next-2026-08-01.1");

    assertTrue(
        coordinator.start(page, first, discovery, unusedHandoff(), ImmediateRunner.INSTANCE));
    assertEquals(UpdateCheckResult.Reason.NO_UPDATE, page.stayResults.get(0).reason);
    assertTrue(page.checkEnabled);
    assertTrue(page.closeAllowed);

    assertTrue(
        coordinator.start(page, second, discovery, unusedHandoff(), ImmediateRunner.INSTANCE));
    assertEquals(2, discovery.seen.size());
    assertSame(first, discovery.seen.get(0));
    assertSame(second, discovery.seen.get(1));
    assertEquals(UpdateCheckResult.Reason.NO_PACKAGE, page.stayResults.get(1).reason);
  }

  @Test
  void offerWindowsDisposesPageAndHandsOffPlan() {
    WindowsUpdatePlan plan = windowsPlan();
    UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator();
    FakePage page = new FakePage();
    FakeHandoff handoff = new FakeHandoff();
    UpdateCheckSelection snapshot = officialSnapshot();

    assertTrue(
        coordinator.start(
            page,
            snapshot,
            new RecordingDiscovery(UpdateCheckResult.offerWindows(plan)),
            handoff,
            ImmediateRunner.INSTANCE));

    assertEquals(1, page.disposeCount);
    assertTrue(page.stayResults.isEmpty());
    assertSame(snapshot, handoff.windowsSelection);
    assertSame(plan, handoff.windowsPlan);
    assertNull(handoff.packagePlan);
  }

  @Test
  void offerPackageDisposesPageAndHandsOffPlan() {
    PackageUpdatePlan plan = new PackageUpdatePlan(null, null, "v", "linux", "x64", "cpu");
    UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator();
    FakePage page = new FakePage();
    FakeHandoff handoff = new FakeHandoff();
    UpdateCheckSelection snapshot = officialSnapshot();

    assertTrue(
        coordinator.start(
            page,
            snapshot,
            new RecordingDiscovery(UpdateCheckResult.offerPackage(plan)),
            handoff,
            ImmediateRunner.INSTANCE));

    assertEquals(1, page.disposeCount);
    assertTrue(page.stayResults.isEmpty());
    assertSame(snapshot, handoff.packageSelection);
    assertSame(plan, handoff.packagePlan);
    assertNull(handoff.windowsPlan);
  }

  @Test
  void offerWithoutPlanStaysOnPageAsFailure() {
    UpdateCheckCoordinator coordinator = new UpdateCheckCoordinator();
    FakePage page = new FakePage();
    FakeHandoff handoff = new FakeHandoff();

    assertTrue(
        coordinator.start(
            page,
            officialSnapshot(),
            new RecordingDiscovery(UpdateCheckResult.offerWindows(null)),
            handoff,
            ImmediateRunner.INSTANCE));

    assertEquals(0, page.disposeCount);
    assertEquals(UpdateCheckResult.Reason.FAILURE, page.stayResults.get(0).reason);
    assertEquals(UpdateCheckResult.FailureKind.ADAPTER, page.stayResults.get(0).failureKind);
    assertNull(handoff.windowsPlan);
    assertTrue(page.checkEnabled);
    assertTrue(page.closeAllowed);
  }

  @Test
  void checkUsesClickSnapshotAndDoesNotRereadConfig() {
    UpdateChannel.persist(UpdateChannel.STABLE);
    UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
    UpdateCheckSelection snapshot =
        UpdateCheckSelection.of(UpdateChannel.STABLE, UpdateSource.GITHUB, "next-2026-08-01.1");
    RecordingDiscovery discovery =
        new RecordingDiscovery(
            selection -> {
              UpdateChannel.persist(UpdateChannel.BETA);
              UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
              Lizzie.nextVersion = "next-dev";
              return UpdateCheckResult.noUpdate();
            });
    FakePage page = new FakePage();

    assertTrue(
        new UpdateCheckCoordinator()
            .start(page, snapshot, discovery, unusedHandoff(), ImmediateRunner.INSTANCE));

    assertEquals(1, discovery.seen.size());
    assertSame(snapshot, discovery.seen.get(0));
    assertEquals(UpdateChannel.STABLE, discovery.seen.get(0).channel);
    assertEquals(UpdateSource.GITHUB, discovery.seen.get(0).effectiveSource);
    assertEquals("next-2026-08-01.1", discovery.seen.get(0).installedVersion);
  }

  @Test
  void startDoesNotPersistChannelOrSource() {
    UpdateChannel.persist(UpdateChannel.STABLE);
    UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
    UpdateCheckSelection snapshot =
        UpdateCheckSelection.of(UpdateChannel.BETA, UpdateSource.GITHUB, "next-2026-08-01.1");

    assertTrue(
        new UpdateCheckCoordinator()
            .start(
                new FakePage(),
                snapshot,
                new RecordingDiscovery(UpdateCheckResult.noUpdate()),
                unusedHandoff(),
                ImmediateRunner.INSTANCE));

    assertEquals(UpdateChannel.STABLE, UpdateChannel.current());
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.current());
    assertEquals(UpdateSource.GITHUB, snapshot.effectiveSource);
  }

  @Test
  void testChannelPagePersistDoesNotOverwriteOfficialSource() {
    UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
    UpdateChannel.persist(UpdateChannel.STABLE);

    CheckUpdateDialog.persistSelection(UpdateChannel.BETA, UpdateSource.GITHUB);

    assertEquals(UpdateChannel.BETA, UpdateChannel.current());
    assertEquals(UpdateSource.OFFICIAL_SITE, UpdateSource.current());
  }

  @Test
  void officialPagePersistWritesChannelAndSource() {
    UpdateSource.persist(UpdateSource.OFFICIAL_SITE);
    UpdateChannel.persist(UpdateChannel.STABLE);

    CheckUpdateDialog.persistSelection(UpdateChannel.STABLE, UpdateSource.GITHUB);

    assertEquals(UpdateChannel.STABLE, UpdateChannel.current());
    assertEquals(UpdateSource.GITHUB, UpdateSource.current());
  }

  @Test
  void stayFeedbackKeysDoNotBindToCopy() {
    assertEquals(
        "WindowsUpdate.devBuild",
        UpdateCheckFeedback.key(UpdateCheckResult.unavailableBuild(), UpdateChannel.STABLE));
    assertEquals(
        "WindowsUpdate.unsupportedPlatform",
        UpdateCheckFeedback.key(UpdateCheckResult.unsupportedPlatform(), UpdateChannel.STABLE));
    assertEquals(
        "WindowsUpdate.noUpdate.stable",
        UpdateCheckFeedback.key(UpdateCheckResult.noUpdate(), UpdateChannel.STABLE));
    assertEquals(
        "WindowsUpdate.noUpdate.beta",
        UpdateCheckFeedback.key(UpdateCheckResult.noUpdate(), UpdateChannel.BETA));
    assertEquals(
        "WindowsUpdate.noPackage",
        UpdateCheckFeedback.key(UpdateCheckResult.noPackage(), UpdateChannel.STABLE));
    assertEquals(
        "WindowsUpdate.fetchFailed.stable",
        UpdateCheckFeedback.key(
            UpdateCheckResult.failure(UpdateCheckResult.FailureKind.FETCH), UpdateChannel.STABLE));
    assertEquals(
        "WindowsUpdate.fetchFailed.beta",
        UpdateCheckFeedback.key(
            UpdateCheckResult.failure(UpdateCheckResult.FailureKind.FETCH), UpdateChannel.BETA));
    assertEquals(
        "WindowsUpdate.invalidTestPointer",
        UpdateCheckFeedback.key(
            UpdateCheckResult.failure(UpdateCheckResult.FailureKind.INVALID_TEST_POINTER),
            UpdateChannel.BETA));
    assertEquals(
        "WindowsUpdate.checkFailed",
        UpdateCheckFeedback.key(
            UpdateCheckResult.failure(UpdateCheckResult.FailureKind.ADAPTER),
            UpdateChannel.STABLE));
  }

  private static UpdateCheckSelection officialSnapshot() {
    return UpdateCheckSelection.of(
        UpdateChannel.STABLE, UpdateSource.OFFICIAL_SITE, "next-2026-08-01.1");
  }

  private static WindowsUpdatePlan windowsPlan() {
    return WindowsUpdatePlan.create(
        UpdateManifest.parse(UpdateManifestTest.validManifest()),
        null,
        "next-2026-06-11.1",
        "opencl");
  }

  private static FakeHandoff unusedHandoff() {
    return new FakeHandoff();
  }

  private static final class FakePage implements UpdateCheckCoordinator.Page {
    boolean checkEnabled = true;
    boolean closeAllowed = true;
    final List<UpdateCheckResult> stayResults = new ArrayList<>();
    int disposeCount;

    @Override
    public void setCheckEnabled(boolean enabled) {
      checkEnabled = enabled;
    }

    @Override
    public void setCloseAllowed(boolean allowed) {
      closeAllowed = allowed;
    }

    @Override
    public void showStayOnPage(UpdateCheckResult result, UpdateCheckSelection snapshot) {
      stayResults.add(result);
    }

    @Override
    public void disposeForOffer() {
      disposeCount++;
    }
  }

  private static final class FakeHandoff implements UpdateCheckCoordinator.OfferHandoff {
    UpdateCheckSelection windowsSelection;
    WindowsUpdatePlan windowsPlan;
    UpdateCheckSelection packageSelection;
    PackageUpdatePlan packagePlan;

    @Override
    public void openWindows(UpdateCheckSelection selection, WindowsUpdatePlan plan) {
      windowsSelection = selection;
      windowsPlan = plan;
    }

    @Override
    public void openPackage(UpdateCheckSelection selection, PackageUpdatePlan plan) {
      packageSelection = selection;
      packagePlan = plan;
    }
  }

  private static final class RecordingDiscovery implements UpdateCheckCoordinator.Discovery {
    final List<UpdateCheckSelection> seen = new ArrayList<>();
    private final List<UpdateCheckResult> results = new ArrayList<>();
    private final java.util.function.Function<UpdateCheckSelection, UpdateCheckResult> factory;
    private final AtomicInteger index = new AtomicInteger();

    RecordingDiscovery(UpdateCheckResult result) {
      this.factory = selection -> result;
    }

    RecordingDiscovery(List<UpdateCheckResult> results) {
      this.results.addAll(results);
      this.factory = selection -> this.results.get(index.getAndIncrement());
    }

    RecordingDiscovery(
        java.util.function.Function<UpdateCheckSelection, UpdateCheckResult> factory) {
      this.factory = factory;
    }

    @Override
    public UpdateCheckResult check(UpdateCheckSelection selection) {
      seen.add(selection);
      return factory.apply(selection);
    }
  }

  private static final class ImmediateRunner implements UpdateCheckCoordinator.Runner {
    static final ImmediateRunner INSTANCE = new ImmediateRunner();

    @Override
    public void runBackground(Runnable work) {
      work.run();
    }

    @Override
    public void runOnEdt(Runnable work) {
      work.run();
    }
  }

  private static final class QueueRunner implements UpdateCheckCoordinator.Runner {
    private Runnable background;

    @Override
    public void runBackground(Runnable work) {
      background = work;
    }

    @Override
    public void runOnEdt(Runnable work) {
      work.run();
    }

    void drain() {
      background.run();
    }
  }
}
