package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.*;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.DownloadSession;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;

/** Headless component tests: no native dialogs, discovery threads, files or engines are started. */
class MeasuredTuningBusyGateTest {
  private static final String[] DEPENDENT_CONTROLS = {
    "btnRefresh",
    "btnChooseLocalEngine",
    "btnRepairAnalysisConfig",
    "btnOpenAppFolder",
    "btnViewFullDownloads",
    "btnReloadRemoteWeights",
    "btnDownloadWeight",
    "btnImportWeight",
    "btnUseWeight",
    "btnDownloadHumanSlModel",
    "btnImportHumanSlModel",
    "btnDownloadQuickAnalysisModel",
    "chkUseQuickAnalysisModel",
    "weightCatalogList",
    "btnOfficialWeightTab",
    "btnCustomWeightTab",
    "balancedRecommendation",
    "strongestRecommendation",
    "lightweightRecommendation",
    "btnInstallNvidiaRuntime",
    "btnInstallTensorRt",
    "btnEnableTensorRt",
    "btnSwitchBackCuda",
    "btnCleanTensorRtCache",
    "cmbExperimentalBackend",
    "btnInstallExperimentalBackend",
    "btnOptimizePerformance",
    "btnExperimentalPerformance",
    "btnImportMeasuredTuning",
    "btnRestoreMeasuredTuning"
  };

  @Test
  void everySharedBackgroundTaskBlocksTheMeasuredActions() throws Exception {
    try (Fixture fixture = new Fixture()) {
      assertFalse(fixture.busy());
      for (Object[] task :
          new Object[][] {
            {"measuredTuningBusy", true, false},
            {
              "stateRefreshWorker",
              new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                  return null;
                }
              },
              null
            },
            {"activeDownloadSession", new DownloadSession(), null},
            {"activeWorkerThread", new Thread(), null},
            {"pendingWeightSwitchTimer", new Timer(1000, ignored -> {}), null}
          }) {
        fixture.set((String) task[0], task[1]);
        assertTrue(fixture.busy(), task[0].toString());
        assertFalse(
            KataGoAutoSetupDialog.measuredTuningActionAvailable("saved", true, fixture.busy()));
        fixture.set((String) task[0], task[2]);
        assertFalse(fixture.busy());
      }
    }
  }

  @Test
  void measuredWorkDisablesAllConflictingControlsAndLateIdleCallbacksCannotEnableThem()
      throws Exception {
    try (Fixture fixture = new Fixture()) {
      SwingUtilities.invokeAndWait(
          () -> {
            fixture.call("setMeasuredTuningBusy", boolean.class, true);
            assertTrue(fixture.busy());
            fixture.assertDependentControlsDisabled();
            assertFalse(fixture.component("sectionNav").isEnabled());
            assertFalse(fixture.component("btnRemoteCompute").isEnabled());
            assertTrue(fixture.component("btnClose").isEnabled());
            // A callback from a previous discovery/download must not lift the shared gate.
            fixture.call("refreshIdleControls");
            fixture.assertDependentControlsDisabled();
            fixture.set("activeWorkerThread", new Thread());
            fixture.call("setMeasuredTuningBusy", boolean.class, false);
            assertTrue(fixture.busy());
            fixture.assertDependentControlsDisabled();
          });
    }
  }

  @Test
  void alreadyQueuedActionsCannotStartModelOrSetupWorkDuringMeasuredReview() throws Exception {
    try (Fixture fixture = new Fixture()) {
      SwingUtilities.invokeAndWait(
          () -> {
            fixture.call("setMeasuredTuningBusy", boolean.class, true);
            for (String action :
                new String[] {
                  "refreshState",
                  "useSelectedWeight",
                  "importCustomWeight",
                  "importHumanSlModel",
                  "startHumanSlModelDownload",
                  "startQuickAnalysisModelDownload",
                  "startRecommendedWeightDownloadInternal",
                  "startNvidiaRuntimeInstall",
                  "chooseExistingKataGo",
                  "repairAnalysisConfig",
                  "openRemoteComputeCenter",
                  "reloadRemoteWeightInfo"
                }) {
              fixture.call(action);
              assertTrue(fixture.busy(), action);
              assertNull(fixture.get("activeWorkerThread"), action);
              assertNull(fixture.get("activeDownloadSession"), action);
              assertNull(fixture.get("stateRefreshWorker"), action);
            }
            fixture.call("startPerformanceBenchmark", boolean.class, false);
            fixture.call("startMeasuredTuningAction", boolean.class, false);
            fixture.call("startMeasuredTuningAction", boolean.class, true);
            fixture.assertDependentControlsDisabled();
          });
    }
  }

  private static final class Fixture implements AutoCloseable {
    final ResourceBundle previous = Lizzie.resourceBundle;
    final KataGoAutoSetupDialog dialog;

    Fixture() throws Exception {
      Lizzie.resourceBundle = ResourceBundle.getBundle("l10n.DisplayStrings", Locale.US);
      Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
      // Bypass only the top-level native window constructor and its discovery startup.
      // Every component touched below is normally constructed, not an Unsafe stub.
      dialog = (KataGoAutoSetupDialog) unsafe.allocateInstance(KataGoAutoSetupDialog.class);
      set("selectedBenchmarkEntryId", "");
      for (String name : DEPENDENT_CONTROLS) initializeComponent(name);
      for (String name :
          new String[] {
            "sectionNav", "btnRemoteCompute", "btnClose", "btnStopDownload", "lblStatus"
          }) initializeComponent(name);
    }

    void initializeComponent(String name) throws Exception {
      Field field = field(name);
      Class<?> type = field.getType();
      Object component;
      if (type.getSimpleName().equals("WeightRecommendationCard")) {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object tone = constructor.getParameterTypes()[1].getEnumConstants()[0];
        component = constructor.newInstance(dialog, tone);
      } else {
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        component = constructor.newInstance();
      }
      set(name, component);
    }

    boolean busy() {
      return (boolean) call("hasActiveBackgroundTask");
    }

    JComponent component(String name) {
      return (JComponent) get(name);
    }

    void assertDependentControlsDisabled() {
      for (String name : DEPENDENT_CONTROLS) assertFalse(component(name).isEnabled(), name);
    }

    Field field(String name) {
      try {
        Field field = KataGoAutoSetupDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    }

    Object get(String name) {
      try {
        return field(name).get(dialog);
      } catch (IllegalAccessException failure) {
        throw new AssertionError(failure);
      }
    }

    void set(String name, Object value) {
      try {
        field(name).set(dialog, value);
      } catch (IllegalAccessException failure) {
        throw new AssertionError(failure);
      }
    }

    Object call(String name) {
      return call(name, new Class<?>[0], new Object[0]);
    }

    Object call(String name, Class<?> parameter, Object argument) {
      return call(name, new Class<?>[] {parameter}, new Object[] {argument});
    }

    Object call(String name, Class<?>[] parameters, Object[] arguments) {
      try {
        Method method = KataGoAutoSetupDialog.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(dialog, arguments);
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    }

    @Override
    public void close() {
      Lizzie.resourceBundle = previous;
    }
  }
}
