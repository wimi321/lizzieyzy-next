package featurecat.lizzie.gui;

import featurecat.lizzie.util.KataGoRuntimeHelper.TensorRtInstallStatus;
import featurecat.lizzie.util.NvidiaGpuDetector.TensorRtRecommendation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class TensorRtAccelerationView {
  static final String GPU_DETECTING_KEY = "AutoSetup.gpuDetecting";
  static final String GPU_NOT_FOUND_KEY = "AutoSetup.gpuDetectNotFound";
  static final String GPU_RECOMMENDED_KEY = "AutoSetup.gpuRecommendTensorRt";
  static final String GPU_ALLOWED_KEY = "AutoSetup.gpuAllowTensorRt";
  static final String GPU_NOT_RECOMMENDED_KEY = "AutoSetup.gpuNotRecommendTensorRt";
  static final String GPU_UNKNOWN_KEY = "AutoSetup.gpuUnknownTensorRt";
  static final String RUNTIME_READY_KEY = "AutoSetup.tensorRtRuntimeReady";
  static final String RUNTIME_NOT_READY_KEY = "AutoSetup.tensorRtRuntimeNotReady";
  static final String COMPANION_READY_KEY = "AutoSetup.tensorRtCompanionReady";
  static final String COMPANION_MISSING_KEY = "AutoSetup.tensorRtCompanionMissing";
  static final String ENGINE_READY_KEY = "AutoSetup.tensorRtEngineReady";
  static final String ENGINE_MISSING_KEY = "AutoSetup.tensorRtEngineMissing";
  static final String ENGINE_STALE_KEY = "AutoSetup.tensorRtEngineStale";
  static final String PROFILE_ACTIVE_KEY = "AutoSetup.tensorRtProfileActive";
  static final String PROFILE_INACTIVE_KEY = "AutoSetup.tensorRtProfileInactive";
  static final String PROFILE_NOT_READY_KEY = "AutoSetup.tensorRtProfileNotReady";
  static final String NOT_APPLICABLE_KEY = "AutoSetup.tensorRtNotApplicable";
  static final String NOT_FOUND_KEY = "AutoSetup.notFound";
  static final String REPAIR_SUMMARY_KEY = "AutoSetup.tensorRtRepairSummary";
  static final String ACTIVATION_MISSING_KEY = "AutoSetup.tensorRtActivationMissing";

  final String gpuAdviceKey;
  final String runtimeStatusKey;
  final String companionStatusKey;
  final String engineStatusKey;
  final String activationStatusKey;
  final List<String> activationMissingKeys;
  final boolean repairEnabled;
  final boolean enableEnabled;
  final String gpuAccessibleNameKey;
  final String gpuAccessibleDescriptionKey;
  final String runtimeAccessibleNameKey;
  final String runtimeAccessibleDescriptionKey;
  final String companionAccessibleNameKey;
  final String companionAccessibleDescriptionKey;
  final String engineAccessibleNameKey;
  final String engineAccessibleDescriptionKey;
  final String activationAccessibleNameKey;
  final String activationAccessibleDescriptionKey;
  final String repairAccessibleNameKey;
  final String repairAccessibleDescriptionKey;
  final String enableAccessibleNameKey;
  final String enableAccessibleDescriptionKey;
  final String repairSummaryKey;
  final String repairTarget;
  final String runtimeSummaryKey;
  final String companionSummaryKey;
  final String engineSummaryKey;

  private TensorRtAccelerationView(
      String gpuAdviceKey,
      String runtimeStatusKey,
      String companionStatusKey,
      String engineStatusKey,
      String activationStatusKey,
      List<String> activationMissingKeys,
      boolean repairEnabled,
      boolean enableEnabled,
      String repairTarget,
      String runtimeSummaryKey,
      String companionSummaryKey,
      String engineSummaryKey) {
    this.gpuAdviceKey = gpuAdviceKey;
    this.runtimeStatusKey = runtimeStatusKey;
    this.companionStatusKey = companionStatusKey;
    this.engineStatusKey = engineStatusKey;
    this.activationStatusKey = activationStatusKey;
    this.activationMissingKeys = List.copyOf(activationMissingKeys);
    this.repairEnabled = repairEnabled;
    this.enableEnabled = enableEnabled;
    this.gpuAccessibleNameKey = "AutoSetup.tensorRtGpuAdviceAccessibleName";
    this.gpuAccessibleDescriptionKey = "AutoSetup.tensorRtGpuAdviceAccessibleDescription";
    this.runtimeAccessibleNameKey = "AutoSetup.tensorRtRuntimeAccessibleName";
    this.runtimeAccessibleDescriptionKey = "AutoSetup.tensorRtRuntimeAccessibleDescription";
    this.companionAccessibleNameKey = "AutoSetup.tensorRtCompanionAccessibleName";
    this.companionAccessibleDescriptionKey = "AutoSetup.tensorRtCompanionAccessibleDescription";
    this.engineAccessibleNameKey = "AutoSetup.tensorRtEngineAccessibleName";
    this.engineAccessibleDescriptionKey = "AutoSetup.tensorRtEngineAccessibleDescription";
    this.activationAccessibleNameKey = "AutoSetup.tensorRtActivationAccessibleName";
    this.activationAccessibleDescriptionKey =
        this.activationMissingKeys.isEmpty()
            ? "AutoSetup.tensorRtActivationAccessibleDescription"
            : ACTIVATION_MISSING_KEY;
    this.repairAccessibleNameKey = "AutoSetup.repairTensorRtAccessibleName";
    this.repairAccessibleDescriptionKey = "AutoSetup.repairTensorRtAccessibleDescription";
    this.enableAccessibleNameKey = "AutoSetup.enableTensorRtAccessibleName";
    this.enableAccessibleDescriptionKey =
        this.activationMissingKeys.isEmpty()
            ? "AutoSetup.enableTensorRtAccessibleDescription"
            : ACTIVATION_MISSING_KEY;
    this.repairSummaryKey = REPAIR_SUMMARY_KEY;
    this.repairTarget = repairTarget == null ? "" : repairTarget;
    this.runtimeSummaryKey = runtimeSummaryKey;
    this.companionSummaryKey = companionSummaryKey;
    this.engineSummaryKey = engineSummaryKey;
  }

  static TensorRtAccelerationView from(
      TensorRtInstallStatus status, boolean gpuDetectionPending, boolean idle) {
    if (status == null) {
      return present(
          gpuDetectionPending,
          false,
          false,
          false,
          TensorRtRecommendation.UNKNOWN,
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          false,
          List.of(),
          null,
          idle);
    }
    return present(
        gpuDetectionPending,
        status.gpuDetectionComplete,
        status.gpuDetected,
        status.hardwareEligible,
        status.gpuRecommendation,
        status.platformSupported,
        status.managedTargetAvailable,
        status.runtimeReady,
        status.companionReady,
        status.enginePresent,
        status.engineCurrent,
        status.profileActive,
        status.repairable,
        status.activatable,
        status.activationMissingItems,
        status.enginePath,
        idle);
  }

  static TensorRtAccelerationView present(
      boolean gpuDetectionPending,
      boolean gpuDetectionComplete,
      boolean gpuDetected,
      boolean hardwareEligible,
      TensorRtRecommendation recommendation,
      boolean platformSupported,
      boolean managedTargetAvailable,
      boolean runtimeReady,
      boolean companionReady,
      boolean enginePresent,
      boolean engineCurrent,
      boolean profileActive,
      boolean repairable,
      boolean activatable,
      List<String> activationMissingItems,
      Path targetEnginePath,
      boolean idle) {
    String gpuAdviceKey =
        gpuAdviceKey(
            gpuDetectionPending,
            gpuDetectionComplete,
            gpuDetected,
            recommendation);
    boolean showComponents = platformSupported && managedTargetAvailable;
    String runtimeStatusKey =
        showComponents
            ? (runtimeReady ? RUNTIME_READY_KEY : RUNTIME_NOT_READY_KEY)
            : notApplicableOrMissing(platformSupported);
    String companionStatusKey =
        showComponents
            ? (companionReady ? COMPANION_READY_KEY : COMPANION_MISSING_KEY)
            : notApplicableOrMissing(platformSupported);
    String engineStatusKey =
        showComponents
            ? engineStatusKey(enginePresent, engineCurrent)
            : notApplicableOrMissing(platformSupported);
    String activationStatusKey =
        showComponents
            ? activationStatusKey(
                profileActive, runtimeReady, companionReady, enginePresent, engineCurrent)
            : notApplicableOrMissing(platformSupported);
    List<String> effectiveMissingItems = new ArrayList<String>();
    if (activationMissingItems != null) {
      effectiveMissingItems.addAll(activationMissingItems);
    }
    if (!gpuDetectionComplete) {
      addMissingFirst(effectiveMissingItems, TensorRtInstallStatus.MISSING_GPU_DETECTION);
    } else if (!hardwareEligible) {
      addMissingFirst(effectiveMissingItems, TensorRtInstallStatus.MISSING_NVIDIA_GPU);
    }
    List<String> missingKeys = missingKeys(effectiveMissingItems);
    boolean needsComponentRepair =
        !runtimeReady || !companionReady || !enginePresent || !engineCurrent;
    boolean hardwareReady = gpuDetectionComplete && gpuDetected && hardwareEligible;
    boolean repairEnabled = idle && hardwareReady && repairable && needsComponentRepair;
    boolean enableEnabled = idle && hardwareReady && activatable && !profileActive;
    String target = targetEnginePath == null ? "" : targetEnginePath.toString();
    return new TensorRtAccelerationView(
        gpuAdviceKey,
        runtimeStatusKey,
        companionStatusKey,
        engineStatusKey,
        activationStatusKey,
        missingKeys,
        repairEnabled,
        enableEnabled,
        target,
        runtimeStatusKey,
        companionStatusKey,
        engineStatusKey);
  }

  private static String gpuAdviceKey(
      boolean gpuDetectionPending,
      boolean gpuDetectionComplete,
      boolean gpuDetected,
      TensorRtRecommendation recommendation) {
    if (gpuDetectionPending || !gpuDetectionComplete) {
      return GPU_DETECTING_KEY;
    }
    if (!gpuDetected) {
      return GPU_NOT_FOUND_KEY;
    }
    if (recommendation == TensorRtRecommendation.RECOMMENDED) {
      return GPU_RECOMMENDED_KEY;
    }
    if (recommendation == TensorRtRecommendation.ALLOWED) {
      return GPU_ALLOWED_KEY;
    }
    if (recommendation == TensorRtRecommendation.NOT_RECOMMENDED) {
      return GPU_NOT_RECOMMENDED_KEY;
    }
    return GPU_UNKNOWN_KEY;
  }

  private static void addMissingFirst(List<String> items, String item) {
    if (!items.contains(item)) {
      items.add(0, item);
    }
  }

  private static String engineStatusKey(boolean enginePresent, boolean engineCurrent) {
    if (!enginePresent) {
      return ENGINE_MISSING_KEY;
    }
    return engineCurrent ? ENGINE_READY_KEY : ENGINE_STALE_KEY;
  }

  private static String activationStatusKey(
      boolean profileActive,
      boolean runtimeReady,
      boolean companionReady,
      boolean enginePresent,
      boolean engineCurrent) {
    if (profileActive) {
      return PROFILE_ACTIVE_KEY;
    }
    if (runtimeReady && companionReady && enginePresent && engineCurrent) {
      return PROFILE_INACTIVE_KEY;
    }
    return PROFILE_NOT_READY_KEY;
  }

  private static String notApplicableOrMissing(boolean platformSupported) {
    return platformSupported ? NOT_FOUND_KEY : NOT_APPLICABLE_KEY;
  }

  String activationVisibleText(Function<String, String> text) {
    String status = text.apply(activationStatusKey);
    if (activationMissingKeys.isEmpty()) {
      return status;
    }
    return status + " — " + joinMissingItems(text);
  }

  String accessibleDescription(String key, Function<String, String> text) {
    if (ACTIVATION_MISSING_KEY.equals(key)) {
      return String.format(text.apply(ACTIVATION_MISSING_KEY), joinMissingItems(text));
    }
    return text.apply(key);
  }

  String joinMissingItems(Function<String, String> text) {
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < activationMissingKeys.size(); i++) {
      if (i > 0) {
        joined.append(", ");
      }
      joined.append(text.apply(activationMissingKeys.get(i)));
    }
    return joined.toString();
  }

  private static List<String> missingKeys(List<String> activationMissingItems) {
    List<String> keys = new ArrayList<String>();
    if (activationMissingItems == null) {
      return keys;
    }
    for (String item : activationMissingItems) {
      if (TensorRtInstallStatus.MISSING_GPU_DETECTION.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingGpuDetection");
      } else if (TensorRtInstallStatus.MISSING_NVIDIA_GPU.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingNvidiaGpu");
      } else if (TensorRtInstallStatus.MISSING_RUNTIME.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingRuntime");
      } else if (TensorRtInstallStatus.MISSING_COMPANION.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingCompanion");
      } else if (TensorRtInstallStatus.MISSING_ENGINE.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingEngine");
      } else if (TensorRtInstallStatus.MISSING_ENGINE_STALE.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingEngineStale");
      } else if (TensorRtInstallStatus.MISSING_WEIGHT.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingWeight");
      } else if (TensorRtInstallStatus.MISSING_GTP_CONFIG.equals(item)) {
        keys.add("AutoSetup.tensorRtMissingGtpConfig");
      }
    }
    return keys;
  }
}
