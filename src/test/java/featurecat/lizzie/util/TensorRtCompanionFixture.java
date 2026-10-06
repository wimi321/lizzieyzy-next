package featurecat.lizzie.util;

import featurecat.lizzie.Config;
import featurecat.lizzie.ConfigTestHelper;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.KataGoAutoSetupHelper.SetupSnapshot;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/**
 * Test-only project-built TensorRT install whose HumanSL CUDA companion shares the TensorRT engine
 * manifest. Main engine and companion have different contents and digests, and no zlib DLL exists in
 * any preflight search directory.
 */
public final class TensorRtCompanionFixture {
  public static final String COMPANION = KataGoRuntimeHelper.HUMAN_SL_CUDA_COMPANION_NAME;
  public static final String ENGINE_MANIFEST = "lizzieyzy-next-katago-engine-manifest.txt";
  private static final List<String> TENSORRT_RUNTIME_WITHOUT_ZLIB =
      List.of(
          "cudart64_12.dll",
          "cublas64_12.dll",
          "cublasLt64_12.dll",
          "cudnn64_9.dll",
          "nvJitLink64_12.dll",
          "nvrtc64_120_0.dll",
          "nvrtc-builtins64_128.dll",
          "nvinfer_10.dll",
          "nvonnxparser_10.dll",
          "nvinfer_plugin_10.dll");
  private static final List<String> ISOLATED_PROPERTIES =
      List.of("os.name", "lizzie.tensorrt.runtimeSearchPath", "lizzie.opencl.nvidiaDriverVersion");

  public enum Layout {
    /** Full package: engine and NVIDIA runtime under the app directory, here one with a space. */
    FULL_PACKAGE,
    /** In-app install: managed engine directory with the runtime in the shared runtime directory. */
    ONLINE_INSTALL_WITH_SHARED_RUNTIME
  }

  public final Path root;
  public final Path runtimeWorkDirectory;
  public final Path engineDir;
  public final Path runtimeDir;
  public final Path tensorRtEngine;
  public final Path companion;

  private TensorRtCompanionFixture(
      Path root,
      Path runtimeWorkDirectory,
      Path engineDir,
      Path runtimeDir,
      Path tensorRtEngine,
      Path companion) {
    this.root = root;
    this.runtimeWorkDirectory = runtimeWorkDirectory;
    this.engineDir = engineDir;
    this.runtimeDir = runtimeDir;
    this.tensorRtEngine = tensorRtEngine;
    this.companion = companion;
  }

  /** Pins Windows, an empty runtime PATH and a CUDA 12.8 capable driver until closed. */
  public static Isolation isolateEnvironment() {
    return new Isolation();
  }

  /** Installs the fixture, pins both digests to it and points {@code Lizzie.config} at it. */
  public static TensorRtCompanionFixture install(Layout layout) throws IOException {
    Path root = Files.createTempDirectory("trt-companion").toAbsolutePath().normalize();
    Path runtimeWorkDirectory = Files.createDirectories(root.resolve("runtime-root"));
    Path engineDir =
        layout == Layout.FULL_PACKAGE
            ? root.resolve("LizzieYzy Next/app/engines/katago/windows-x64")
            : runtimeWorkDirectory.resolve("engines/katago/windows-x64-nvidia-tensorrt");
    Files.createDirectories(engineDir);
    Path tensorRtEngine =
        Files.writeString(engineDir.resolve("katago.exe"), "tensorrt main executable");
    Path companion = Files.writeString(engineDir.resolve(COMPANION), "cuda companion executable");
    KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(sha256(tensorRtEngine));
    KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(sha256(companion));
    Files.writeString(engineDir.resolve("lizzieyzy-next-engine-backend.txt"), "nvidia-tensorrt\n");
    Path runtimeDir =
        layout == Layout.FULL_PACKAGE ? engineDir : runtimeWorkDirectory.resolve("nvidia-runtime");
    installRuntimeWithoutZlib(runtimeDir);
    TensorRtCompanionFixture fixture =
        new TensorRtCompanionFixture(
            root, runtimeWorkDirectory, engineDir, runtimeDir, tensorRtEngine, companion);
    fixture.writeManifest(true);
    Lizzie.config = createTestConfig(runtimeWorkDirectory);
    return fixture;
  }

  /** Writes the strict TensorRT manifest, optionally listing the companion. */
  public void writeManifest(boolean withCompanion) throws IOException {
    String text = KataGoRuntimeHelper.tensorRtEngineManifestText();
    if (withCompanion) {
      text +=
          "HumanSL companion: "
              + COMPANION
              + "\nHumanSL companion SHA-256: "
              + sha256(companion)
              + "\n";
    }
    Files.writeString(engineDir.resolve(ENGINE_MANIFEST), text);
  }

  /** The AI Coach analysis command for this TensorRT engine, with every path quoted. */
  public String humanSlCommand(Path humanModel) {
    return quote(tensorRtEngine)
        + " analysis -model "
        + quote(root.resolve("default model.bin.gz"))
        + " -human-model "
        + quote(humanModel);
  }

  /** A setup snapshot whose managed TensorRT target is this fixture's online install directory. */
  public SetupSnapshot snapshot() throws IOException, ReflectiveOperationException {
    Path workingDir = Files.createDirectories(root.resolve("working"));
    Path appRoot = Files.createDirectories(root.resolve("snapshot-app"));
    Path snapshotEngineDir =
        Files.createDirectories(appRoot.resolve("engines/katago/windows-x64-directml"));
    Path enginePath = Files.write(snapshotEngineDir.resolve("katago.exe"), new byte[0]);
    Files.writeString(snapshotEngineDir.resolve("lizzieyzy-next-engine-backend.txt"), "directml");
    Path configDir = Files.createDirectories(appRoot.resolve("engines/katago/configs"));
    Path gtpConfig = Files.write(configDir.resolve("gtp.cfg"), new byte[0]);
    Path analysisConfig = Files.write(configDir.resolve("analysis.cfg"), new byte[0]);
    Path weight =
        Files.write(
            Files.createDirectories(workingDir.resolve("weights")).resolve("default.bin.gz"),
            new byte[0]);
    Constructor<SetupSnapshot> constructor =
        SetupSnapshot.class.getDeclaredConstructor(
            Path.class, Path.class, Path.class, Path.class, Path.class, Path.class, List.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        workingDir, appRoot, enginePath, gtpConfig, analysisConfig, weight, List.of(weight));
  }

  public static String sha256(Path path) throws IOException {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
      StringBuilder builder = new StringBuilder();
      for (byte value : hash) {
        builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String quote(Path path) {
    return "\"" + path.toAbsolutePath().normalize() + "\"";
  }

  private static void installRuntimeWithoutZlib(Path runtimeDir) throws IOException {
    Files.createDirectories(runtimeDir);
    for (String dll : TENSORRT_RUNTIME_WITHOUT_ZLIB) {
      Files.write(runtimeDir.resolve(dll), new byte[0]);
    }
    Files.writeString(
        runtimeDir.resolve("lizzieyzy-next-nvidia-runtime-manifest.txt"),
        "CUDA NVRTC: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_VERSION
            + "\nfixture\nSHA-256: "
            + KataGoRuntimeHelper.CUDA_12_8_NVRTC_SHA256
            + "\n");
  }

  private static Config createTestConfig(Path runtimeWorkDirectory) {
    Config config = ConfigTestHelper.createForTests(runtimeWorkDirectory);
    config.config = new JSONObject();
    config.leelazConfig = new JSONObject();
    config.uiConfig = new JSONObject();
    config.config.put("leelaz", config.leelazConfig);
    config.config.put("ui", config.uiConfig);
    return config;
  }

  /** Restores system properties, digest overrides and {@code Lizzie.config} on close. */
  public static final class Isolation implements AutoCloseable {
    private final Map<String, String> previousProperties = new LinkedHashMap<>();
    private final Config previousConfig = Lizzie.config;

    private Isolation() {
      for (String key : ISOLATED_PROPERTIES) {
        previousProperties.put(key, System.getProperty(key));
      }
      System.setProperty("os.name", "Windows 11");
      System.setProperty("lizzie.tensorrt.runtimeSearchPath", "");
      System.setProperty("lizzie.opencl.nvidiaDriverVersion", "610.74");
    }

    @Override
    public void close() {
      KataGoRuntimeHelper.setKatagoExecutableSha256ForTests(null);
      KataGoRuntimeHelper.setHumanSlCompanionSha256ForTests(null);
      previousProperties.forEach(
          (key, value) -> {
            if (value == null) {
              System.clearProperty(key);
            } else {
              System.setProperty(key, value);
            }
          });
      Lizzie.config = previousConfig;
    }
  }
}
