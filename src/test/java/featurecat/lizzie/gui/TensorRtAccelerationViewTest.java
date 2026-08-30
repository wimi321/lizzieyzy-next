package featurecat.lizzie.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.NvidiaGpuDetector.TensorRtRecommendation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import javax.swing.JButton;
import javax.swing.JLabel;
import org.junit.jupiter.api.Test;

class TensorRtAccelerationViewTest {
  private static final Path TARGET = Path.of("engines", "katago-tensorrt", "katago.exe");

  @Test
  void runtimeMissingIsNotHiddenByAnInstalledStyleEnginePresence() {
    TensorRtAccelerationView view =
        view(false, true, true, true, false, true, false, List.of(TensorRtInstallStatus.MISSING_RUNTIME));

    assertEquals(TensorRtAccelerationView.RUNTIME_NOT_READY_KEY, view.runtimeStatusKey);
    assertEquals(TensorRtAccelerationView.COMPANION_READY_KEY, view.companionStatusKey);
    assertEquals(TensorRtAccelerationView.ENGINE_READY_KEY, view.engineStatusKey);
    assertEquals(TensorRtAccelerationView.PROFILE_NOT_READY_KEY, view.activationStatusKey);
    assertTrue(view.repairEnabled);
    assertFalse(view.enableEnabled);
  }

  @Test
  void companionMissingStaysVisibleWhenRuntimeAndEngineAreReady() {
    TensorRtAccelerationView view =
        view(
            true,
            false,
            true,
            true,
            false,
            true,
            false,
            List.of(TensorRtInstallStatus.MISSING_COMPANION));

    assertEquals(TensorRtAccelerationView.RUNTIME_READY_KEY, view.runtimeStatusKey);
    assertEquals(TensorRtAccelerationView.COMPANION_MISSING_KEY, view.companionStatusKey);
    assertEquals(TensorRtAccelerationView.ENGINE_READY_KEY, view.engineStatusKey);
    assertEquals(TensorRtAccelerationView.PROFILE_NOT_READY_KEY, view.activationStatusKey);
    assertTrue(view.repairEnabled);
    assertFalse(view.enableEnabled);
  }

  @Test
  void engineMissingIsReportedIndependently() {
    TensorRtAccelerationView view =
        view(
            true,
            true,
            false,
            false,
            false,
            true,
            false,
            List.of(TensorRtInstallStatus.MISSING_ENGINE));

    assertEquals(TensorRtAccelerationView.ENGINE_MISSING_KEY, view.engineStatusKey);
    assertEquals(TensorRtAccelerationView.RUNTIME_READY_KEY, view.runtimeStatusKey);
    assertEquals(TensorRtAccelerationView.COMPANION_READY_KEY, view.companionStatusKey);
    assertTrue(view.repairEnabled);
    assertFalse(view.enableEnabled);
  }

  @Test
  void staleEngineIsNotReportedAsReady() {
    TensorRtAccelerationView view =
        view(
            true,
            true,
            true,
            false,
            false,
            true,
            false,
            List.of(TensorRtInstallStatus.MISSING_ENGINE_STALE));

    assertEquals(TensorRtAccelerationView.ENGINE_STALE_KEY, view.engineStatusKey);
    assertEquals(TensorRtAccelerationView.PROFILE_NOT_READY_KEY, view.activationStatusKey);
    assertTrue(view.repairEnabled);
    assertFalse(view.enableEnabled);
  }

  @Test
  void readyComponentsWithInactiveProfileStayDistinguishable() {
    TensorRtAccelerationView view =
        view(true, true, true, true, false, true, true, List.of());

    assertEquals(TensorRtAccelerationView.RUNTIME_READY_KEY, view.runtimeStatusKey);
    assertEquals(TensorRtAccelerationView.COMPANION_READY_KEY, view.companionStatusKey);
    assertEquals(TensorRtAccelerationView.ENGINE_READY_KEY, view.engineStatusKey);
    assertEquals(TensorRtAccelerationView.PROFILE_INACTIVE_KEY, view.activationStatusKey);
    assertFalse(view.repairEnabled);
    assertTrue(view.enableEnabled);
  }

  @Test
  void gpuPendingBlocksRepairAndShowsDetectingAdvice() {
    TensorRtAccelerationView view =
        TensorRtAccelerationView.present(
            true,
            false,
            false,
            false,
            TensorRtRecommendation.UNKNOWN,
            true,
            true,
            false,
            false,
            false,
            false,
            false,
            true,
            false,
            List.of(TensorRtInstallStatus.MISSING_RUNTIME),
            TARGET,
            true);

    assertEquals(TensorRtAccelerationView.GPU_DETECTING_KEY, view.gpuAdviceKey);
    assertFalse(view.repairEnabled);
    assertEquals(
        List.of(
            "AutoSetup.tensorRtMissingGpuDetection",
            "AutoSetup.tensorRtMissingRuntime"),
        view.activationMissingKeys);
  }

  @Test
  void gpuAdviceKeepsModernAndUnknownNvidiaRepairableButBlocksMissingOrOldHardware() {
    assertEquals(
        TensorRtAccelerationView.GPU_ALLOWED_KEY,
        gpuView(true, true, TensorRtRecommendation.ALLOWED).gpuAdviceKey);
    assertEquals(
        TensorRtAccelerationView.GPU_NOT_RECOMMENDED_KEY,
        gpuView(true, true, TensorRtRecommendation.NOT_RECOMMENDED).gpuAdviceKey);
    assertEquals(
        TensorRtAccelerationView.GPU_UNKNOWN_KEY,
        gpuView(true, true, TensorRtRecommendation.UNKNOWN).gpuAdviceKey);
    assertTrue(gpuView(true, true, TensorRtRecommendation.NOT_RECOMMENDED).repairEnabled);
    assertTrue(gpuView(true, true, TensorRtRecommendation.UNKNOWN).repairEnabled);

    TensorRtAccelerationView noGpu =
        gpuView(false, false, TensorRtRecommendation.UNKNOWN);
    assertEquals(TensorRtAccelerationView.GPU_NOT_FOUND_KEY, noGpu.gpuAdviceKey);
    assertFalse(noGpu.repairEnabled);
    assertEquals(
        "AutoSetup.tensorRtMissingNvidiaGpu", noGpu.activationMissingKeys.get(0));

    TensorRtAccelerationView unsupported =
        gpuView(true, false, TensorRtRecommendation.NOT_RECOMMENDED);
    assertEquals(
        TensorRtAccelerationView.GPU_NOT_RECOMMENDED_KEY, unsupported.gpuAdviceKey);
    assertFalse(unsupported.repairEnabled);
  }

  @Test
  void missingActivationItemsAreExposedOnTheAccessibleEnableDescription() throws Exception {
    TensorRtAccelerationView view =
        view(
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            List.of(
                TensorRtInstallStatus.MISSING_WEIGHT,
                TensorRtInstallStatus.MISSING_GTP_CONFIG));

    assertFalse(view.enableEnabled);
    assertEquals(TensorRtAccelerationView.ACTIVATION_MISSING_KEY, view.activationAccessibleDescriptionKey);
    assertEquals(TensorRtAccelerationView.ACTIVATION_MISSING_KEY, view.enableAccessibleDescriptionKey);
    assertEquals(
        List.of("AutoSetup.tensorRtMissingWeight", "AutoSetup.tensorRtMissingGtpConfig"),
        view.activationMissingKeys);

    Properties bundle = englishBundle();
    Function<String, String> text = key -> bundle.getProperty(key);
    String weight = text.apply("AutoSetup.tensorRtMissingWeight");
    String gtp = text.apply("AutoSetup.tensorRtMissingGtpConfig");

    JLabel activation = new JLabel();
    activation.setFocusable(true);
    activation.setText(view.activationVisibleText(text));
    AccessibilitySupport.named(
        activation,
        text.apply(view.activationAccessibleNameKey),
        view.accessibleDescription(view.activationAccessibleDescriptionKey, text));
    JButton enable = new JButton();
    enable.setEnabled(view.enableEnabled);
    AccessibilitySupport.button(
        enable,
        text.apply(view.enableAccessibleNameKey),
        view.accessibleDescription(view.enableAccessibleDescriptionKey, text));

    assertTrue(activation.isFocusable());
    assertTrue(activation.getText().contains(weight));
    assertTrue(activation.getText().contains(gtp));
    assertTrue(activation.getAccessibleContext().getAccessibleDescription().contains(weight));
    assertTrue(activation.getAccessibleContext().getAccessibleDescription().contains(gtp));
    assertFalse(enable.isEnabled());
    assertTrue(enable.getAccessibleContext().getAccessibleDescription().contains(weight));
    assertTrue(enable.getAccessibleContext().getAccessibleDescription().contains(gtp));
  }

  @Test
  void incompleteWeightAndGtpDoNotBlockRepairAndAreListedForEnable() {
    TensorRtAccelerationView view =
        view(
            false,
            false,
            false,
            false,
            false,
            true,
            false,
            List.of(
                TensorRtInstallStatus.MISSING_RUNTIME,
                TensorRtInstallStatus.MISSING_WEIGHT,
                TensorRtInstallStatus.MISSING_GTP_CONFIG));

    assertTrue(view.repairEnabled);
    assertFalse(view.enableEnabled);
    assertEquals(
        List.of(
            "AutoSetup.tensorRtMissingRuntime",
            "AutoSetup.tensorRtMissingWeight",
            "AutoSetup.tensorRtMissingGtpConfig"),
        view.activationMissingKeys);
  }

  @Test
  void repairSummaryNamesTargetAndComponentStatesWithoutActivation() {
    TensorRtAccelerationView view =
        view(true, true, true, true, false, true, true, List.of());

    assertEquals(TensorRtAccelerationView.REPAIR_SUMMARY_KEY, view.repairSummaryKey);
    assertEquals(TARGET.toString(), view.repairTarget);
    assertEquals(TensorRtAccelerationView.RUNTIME_READY_KEY, view.runtimeSummaryKey);
    assertEquals(TensorRtAccelerationView.COMPANION_READY_KEY, view.companionSummaryKey);
    assertEquals(TensorRtAccelerationView.ENGINE_READY_KEY, view.engineSummaryKey);
    assertEquals(TensorRtAccelerationView.PROFILE_INACTIVE_KEY, view.activationStatusKey);
    assertFalse(view.repairSummaryKey.toLowerCase().contains("enable"));
  }

  @Test
  void actionsAndStatusRowsExposeLocalizedAccessibleKeys() {
    TensorRtAccelerationView view =
        view(true, true, true, true, false, true, true, List.of());

    assertEquals("AutoSetup.tensorRtGpuAdviceAccessibleName", view.gpuAccessibleNameKey);
    assertEquals("AutoSetup.tensorRtGpuAdviceAccessibleDescription", view.gpuAccessibleDescriptionKey);
    assertEquals("AutoSetup.tensorRtRuntimeAccessibleName", view.runtimeAccessibleNameKey);
    assertEquals("AutoSetup.tensorRtRuntimeAccessibleDescription", view.runtimeAccessibleDescriptionKey);
    assertEquals("AutoSetup.tensorRtCompanionAccessibleName", view.companionAccessibleNameKey);
    assertEquals(
        "AutoSetup.tensorRtCompanionAccessibleDescription", view.companionAccessibleDescriptionKey);
    assertEquals("AutoSetup.tensorRtEngineAccessibleName", view.engineAccessibleNameKey);
    assertEquals("AutoSetup.tensorRtEngineAccessibleDescription", view.engineAccessibleDescriptionKey);
    assertEquals("AutoSetup.tensorRtActivationAccessibleName", view.activationAccessibleNameKey);
    assertEquals(
        "AutoSetup.tensorRtActivationAccessibleDescription", view.activationAccessibleDescriptionKey);
    assertEquals("AutoSetup.repairTensorRtAccessibleName", view.repairAccessibleNameKey);
    assertEquals("AutoSetup.repairTensorRtAccessibleDescription", view.repairAccessibleDescriptionKey);
    assertEquals("AutoSetup.enableTensorRtAccessibleName", view.enableAccessibleNameKey);
    assertEquals("AutoSetup.enableTensorRtAccessibleDescription", view.enableAccessibleDescriptionKey);
  }

  private static TensorRtAccelerationView view(
      boolean runtimeReady,
      boolean companionReady,
      boolean enginePresent,
      boolean engineCurrent,
      boolean profileActive,
      boolean repairable,
      boolean activatable,
      List<String> missingItems) {
    return TensorRtAccelerationView.present(
        false,
        true,
        true,
        true,
        TensorRtRecommendation.ALLOWED,
        true,
        true,
        runtimeReady,
        companionReady,
        enginePresent,
        engineCurrent,
        profileActive,
        repairable,
        activatable,
        missingItems,
        TARGET,
        true);
  }

  @Test
  void presentationKeysExistInTheEnglishBundle() throws Exception {
    TensorRtAccelerationView view =
        view(true, true, true, true, false, true, true, List.of());
    Properties bundle = englishBundle();
    for (String key :
        List.of(
            view.gpuAdviceKey,
            view.runtimeStatusKey,
            view.companionStatusKey,
            view.engineStatusKey,
            view.activationStatusKey,
            view.repairSummaryKey,
            view.gpuAccessibleNameKey,
            view.gpuAccessibleDescriptionKey,
            view.runtimeAccessibleNameKey,
            view.runtimeAccessibleDescriptionKey,
            view.companionAccessibleNameKey,
            view.companionAccessibleDescriptionKey,
            view.engineAccessibleNameKey,
            view.engineAccessibleDescriptionKey,
            view.activationAccessibleNameKey,
            view.activationAccessibleDescriptionKey,
            view.repairAccessibleNameKey,
            view.repairAccessibleDescriptionKey,
            view.enableAccessibleNameKey,
            view.enableAccessibleDescriptionKey,
            "AutoSetup.tensorRtRuntimeStatus",
            "AutoSetup.tensorRtCompanionStatus",
            "AutoSetup.tensorRtEngineStatus",
            "AutoSetup.tensorRtActivationStatus",
            "AutoSetup.tensorRtActivationMissing",
            "AutoSetup.tensorRtMissingGpuDetection",
            "AutoSetup.tensorRtMissingNvidiaGpu")) {
      assertTrue(bundle.containsKey(key), key);
      assertFalse(bundle.getProperty(key).isBlank(), key);
    }
  }

  private static Properties englishBundle() throws Exception {
    Properties bundle = new Properties();
    try (var in =
        Files.newBufferedReader(
            Path.of("src/main/resources/l10n/DisplayStrings.properties"),
            java.nio.charset.StandardCharsets.UTF_8)) {
      bundle.load(in);
    }
    return bundle;
  }

  private static TensorRtAccelerationView gpuView(
      boolean detected,
      boolean hardwareEligible,
      TensorRtRecommendation recommendation) {
    return TensorRtAccelerationView.present(
        false,
        true,
        detected,
        hardwareEligible,
        recommendation,
        true,
        true,
        false,
        false,
        false,
        false,
        false,
        true,
        false,
        List.of(TensorRtInstallStatus.MISSING_RUNTIME),
        TARGET,
        true);
  }
}
