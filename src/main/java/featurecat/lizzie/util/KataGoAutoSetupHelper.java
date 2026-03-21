package featurecat.lizzie.util;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jdesktop.swingx.util.OS;

public final class KataGoAutoSetupHelper {
  private static final String AUTO_SETUP_ENGINE_NAME = "KataGo Auto Setup";
  private static final String USER_AGENT =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";
  private static final String NETWORKS_URL = "https://katagotraining.org/networks/";
  private static final Pattern STRONGEST_PATTERN =
      Pattern.compile(
          "Strongest confidently-rated network:</span>\\s*<a href=\"([^\"]+)\">([^<]+)</a>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern LATEST_PATTERN =
      Pattern.compile(
          "Latest network:</span>\\s*<a href=\"([^\"]+)\">([^<]+)</a>",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private KataGoAutoSetupHelper() {}

  public interface ProgressListener {
    void onProgress(String statusText, long downloadedBytes, long totalBytes);
  }

  public static final class SetupSnapshot {
    public final Path workingDir;
    public final Path appRoot;
    public final Path enginePath;
    public final Path gtpConfigPath;
    public final Path analysisConfigPath;
    public final Path estimateConfigPath;
    public final Path activeWeightPath;
    public final List<Path> weightCandidates;

    private SetupSnapshot(
        Path workingDir,
        Path appRoot,
        Path enginePath,
        Path gtpConfigPath,
        Path analysisConfigPath,
        Path estimateConfigPath,
        Path activeWeightPath,
        List<Path> weightCandidates) {
      this.workingDir = workingDir;
      this.appRoot = appRoot;
      this.enginePath = enginePath;
      this.gtpConfigPath = gtpConfigPath;
      this.analysisConfigPath = analysisConfigPath;
      this.estimateConfigPath = estimateConfigPath;
      this.activeWeightPath = activeWeightPath;
      this.weightCandidates = Collections.unmodifiableList(new ArrayList<>(weightCandidates));
    }

    public boolean hasEngine() {
      return enginePath != null && Files.isRegularFile(enginePath);
    }

    public boolean hasConfigs() {
      return gtpConfigPath != null
          && Files.isRegularFile(gtpConfigPath)
          && analysisConfigPath != null
          && Files.isRegularFile(analysisConfigPath);
    }

    public boolean hasWeight() {
      return activeWeightPath != null && Files.isRegularFile(activeWeightPath);
    }

    public SetupSnapshot withActiveWeight(Path weightPath) {
      List<Path> updatedCandidates = new ArrayList<>();
      if (weightPath != null) {
        updatedCandidates.add(weightPath.toAbsolutePath().normalize());
      }
      updatedCandidates.addAll(weightCandidates);
      LinkedHashSet<Path> dedup = new LinkedHashSet<>();
      for (Path candidate : updatedCandidates) {
        if (candidate != null) {
          dedup.add(candidate.toAbsolutePath().normalize());
        }
      }
      return new SetupSnapshot(
          workingDir,
          appRoot,
          enginePath,
          gtpConfigPath,
          analysisConfigPath,
          estimateConfigPath,
          weightPath == null ? activeWeightPath : weightPath.toAbsolutePath().normalize(),
          new ArrayList<>(dedup));
    }
  }

  public static final class RemoteWeightInfo {
    public final String typeLabel;
    public final String modelName;
    public final String downloadUrl;
    public final String uploadedAt;

    private RemoteWeightInfo(
        String typeLabel, String modelName, String downloadUrl, String uploadedAt) {
      this.typeLabel = typeLabel;
      this.modelName = modelName;
      this.downloadUrl = downloadUrl;
      this.uploadedAt = uploadedAt;
    }

    public String fileName() {
      String urlFileName = fileNameFromUrl(downloadUrl);
      if (!urlFileName.isEmpty()) {
        return urlFileName;
      }
      return modelName.endsWith(".bin.gz") ? modelName : modelName + ".bin.gz";
    }
  }

  public static final class SetupResult {
    public final SetupSnapshot snapshot;
    public final int engineIndex;
    public final String engineName;

    private SetupResult(SetupSnapshot snapshot, int engineIndex, String engineName) {
      this.snapshot = snapshot;
      this.engineIndex = engineIndex;
      this.engineName = engineName;
    }
  }

  public static SetupSnapshot inspectLocalSetup() {
    Path workingDir = currentWorkingDir();
    Path appRoot = findAppRoot().orElse(workingDir);
    Path enginePath = detectEngineBinary(workingDir, appRoot);
    Path gtpConfigPath = detectConfig(workingDir, appRoot, "gtp.cfg");
    Path analysisConfigPath = detectConfig(workingDir, appRoot, "analysis.cfg");
    Path estimateConfigPath = detectConfig(workingDir, appRoot, "estimate.cfg");
    if (estimateConfigPath == null) {
      estimateConfigPath = gtpConfigPath;
    }
    List<Path> weightCandidates = collectWeightCandidates(workingDir, appRoot);
    Path activeWeightPath = chooseActiveWeight(workingDir, appRoot, weightCandidates);
    return new SetupSnapshot(
        workingDir,
        appRoot,
        enginePath,
        gtpConfigPath,
        analysisConfigPath,
        estimateConfigPath,
        activeWeightPath,
        weightCandidates);
  }

  public static RemoteWeightInfo fetchRecommendedWeight() throws IOException {
    String html = httpGet(NETWORKS_URL);
    RemoteWeightInfo strongest =
        parseWeightInfo(
            html, STRONGEST_PATTERN, resource("AutoSetup.recommendedStrongest", "Strongest"));
    if (strongest != null) {
      return strongest;
    }
    RemoteWeightInfo latest =
        parseWeightInfo(html, LATEST_PATTERN, resource("AutoSetup.recommendedLatest", "Latest"));
    if (latest != null) {
      return latest;
    }
    throw new IOException("Unable to parse KataGo network recommendations.");
  }

  public static Path downloadRecommendedWeight(ProgressListener listener) throws IOException {
    RemoteWeightInfo info = fetchRecommendedWeight();
    if (listener != null) {
      listener.onProgress(info.modelName, 0, -1);
    }
    return downloadWeight(info, listener);
  }

  public static Path downloadWeight(RemoteWeightInfo info, ProgressListener listener)
      throws IOException {
    SetupSnapshot snapshot = inspectLocalSetup();
    Path weightsDir = snapshot.workingDir.resolve("weights");
    Files.createDirectories(weightsDir);

    Path target = weightsDir.resolve(info.fileName());
    if (Files.isRegularFile(target) && Files.size(target) > 1024L * 1024L) {
      rememberPreferredWeight(target);
      if (listener != null) {
        listener.onProgress(info.modelName, Files.size(target), Files.size(target));
      }
      return target;
    }

    Path temp = weightsDir.resolve(info.fileName() + ".part");
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(info.downloadUrl).openConnection();
      conn.setInstanceFollowRedirects(true);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(30000);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      conn.setRequestProperty("Accept", "application/octet-stream,*/*");
      int code = conn.getResponseCode();
      if (code < 200 || code >= 400) {
        throw new IOException("HTTP " + code + " from " + info.downloadUrl);
      }
      long totalBytes = conn.getContentLengthLong();
      try (InputStream raw = conn.getInputStream();
          BufferedInputStream input = new BufferedInputStream(raw);
          OutputStream output = Files.newOutputStream(temp)) {
        byte[] buffer = new byte[8192];
        long downloaded = 0L;
        int read;
        long lastReportTime = 0L;
        while ((read = input.read(buffer)) >= 0) {
          output.write(buffer, 0, read);
          downloaded += read;
          long now = System.currentTimeMillis();
          if (listener != null && (now - lastReportTime > 120 || totalBytes == downloaded)) {
            listener.onProgress(info.modelName, downloaded, totalBytes);
            lastReportTime = now;
          }
        }
      }
      try {
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      rememberPreferredWeight(target);
      return target;
    } catch (IOException e) {
      Files.deleteIfExists(temp);
      throw e;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  public static SetupResult applyAutoSetup(SetupSnapshot snapshot) throws IOException {
    if (snapshot == null) {
      snapshot = inspectLocalSetup();
    }
    if (!snapshot.hasEngine()) {
      throw new IOException(
          resource("AutoSetup.missingEngine", "No local KataGo binary was found."));
    }
    if (snapshot.gtpConfigPath == null || !Files.isRegularFile(snapshot.gtpConfigPath)) {
      throw new IOException(
          resource("AutoSetup.missingConfig", "No KataGo config file was found."));
    }
    if (!snapshot.hasWeight()) {
      throw new IOException(
          resource("AutoSetup.missingWeight", "No KataGo weight file was found."));
    }

    Path analysisConfig =
        snapshot.analysisConfigPath != null ? snapshot.analysisConfigPath : snapshot.gtpConfigPath;
    Path estimateConfig =
        snapshot.estimateConfigPath != null ? snapshot.estimateConfigPath : snapshot.gtpConfigPath;

    String engineCommand =
        quoteCommandPath(snapshot.workingDir, snapshot.enginePath)
            + " gtp -model "
            + quoteCommandPath(snapshot.workingDir, snapshot.activeWeightPath)
            + " -config "
            + quoteCommandPath(snapshot.workingDir, snapshot.gtpConfigPath);
    String analysisCommand =
        quoteCommandPath(snapshot.workingDir, snapshot.enginePath)
            + " analysis -model "
            + quoteCommandPath(snapshot.workingDir, snapshot.activeWeightPath)
            + " -config "
            + quoteCommandPath(snapshot.workingDir, analysisConfig)
            + " -quit-without-waiting";
    String estimateCommand =
        quoteCommandPath(snapshot.workingDir, snapshot.enginePath)
            + " gtp -model "
            + quoteCommandPath(snapshot.workingDir, snapshot.activeWeightPath)
            + " -config "
            + quoteCommandPath(snapshot.workingDir, estimateConfig);

    ArrayList<EngineData> engines = Utils.getEngineData();
    int engineIndex = findAutoSetupEngineIndex(engines);
    EngineData engineData;
    if (engineIndex >= 0) {
      engineData = engines.get(engineIndex);
    } else {
      engineData = new EngineData();
      engines.add(engineData);
      engineIndex = engines.size() - 1;
    }

    for (int i = 0; i < engines.size(); i++) {
      EngineData existing = engines.get(i);
      existing.index = i;
      existing.isDefault = false;
    }

    engineData.index = engineIndex;
    engineData.name = AUTO_SETUP_ENGINE_NAME;
    engineData.commands = engineCommand;
    engineData.preload = false;
    engineData.width = 19;
    engineData.height = 19;
    engineData.komi = 7.5F;
    engineData.isDefault = true;
    engineData.useJavaSSH = false;
    engineData.ip = "";
    engineData.port = "";
    engineData.userName = "";
    engineData.password = "";
    engineData.useKeyGen = false;
    engineData.keyGenPath = "";
    engineData.initialCommand = "";

    Utils.saveEngineSettings(engines);
    rememberPreferredWeight(snapshot.activeWeightPath);
    Lizzie.config.uiConfig.put("autoload-default", true);
    Lizzie.config.uiConfig.put("autoload-empty", false);
    Lizzie.config.uiConfig.put("default-engine", engineIndex);
    Lizzie.config.uiConfig.put("analysis-engine-command", analysisCommand);
    Lizzie.config.uiConfig.put("estimate-command", estimateCommand);
    Lizzie.config.uiConfig.put(
        "katago-auto-setup-weight-name", snapshot.activeWeightPath.getFileName().toString());
    Lizzie.config.uiConfig.put(
        "katago-auto-setup-weight-path",
        snapshot.activeWeightPath.toAbsolutePath().normalize().toString());
    Lizzie.config.uiConfig.put(
        "katago-auto-setup-engine-path",
        snapshot.enginePath.toAbsolutePath().normalize().toString());
    Lizzie.config.uiConfig.put("katago-auto-setup-updated-at", System.currentTimeMillis());
    Lizzie.config.save();
    return new SetupResult(snapshot, engineIndex, AUTO_SETUP_ENGINE_NAME);
  }

  public static String getAutoSetupEngineName() {
    return AUTO_SETUP_ENGINE_NAME;
  }

  private static void rememberPreferredWeight(Path weightPath) {
    if (weightPath == null || Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return;
    }
    Lizzie.config.uiConfig.put(
        "katago-preferred-weight-path", weightPath.toAbsolutePath().normalize().toString());
  }

  private static int findAutoSetupEngineIndex(ArrayList<EngineData> engines) {
    for (int i = 0; i < engines.size(); i++) {
      EngineData engineData = engines.get(i);
      if (AUTO_SETUP_ENGINE_NAME.equals(engineData.name)) {
        return i;
      }
    }
    return -1;
  }

  private static RemoteWeightInfo parseWeightInfo(String html, Pattern pattern, String typeLabel) {
    Matcher matcher = pattern.matcher(html);
    if (!matcher.find()) {
      return null;
    }
    String url = resolveUrl(matcher.group(1));
    String name = matcher.group(2);
    String uploadedAt = extractUploadedAt(html, name);
    return new RemoteWeightInfo(typeLabel, name, url, uploadedAt);
  }

  private static String extractUploadedAt(String html, String modelName) {
    String compactName = modelName.trim();
    Pattern rowPattern =
        Pattern.compile(
            "<tr[^>]*>\\s*<td>\\s*"
                + Pattern.quote(compactName)
                + "\\s*</td>\\s*<td>\\s*([^<]+)\\s*</td>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    Matcher matcher = rowPattern.matcher(html);
    if (matcher.find()) {
      return matcher.group(1).trim();
    }
    return "";
  }

  private static String httpGet(String url) throws IOException {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setInstanceFollowRedirects(true);
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(15000);
      conn.setReadTimeout(30000);
      conn.setRequestProperty("User-Agent", USER_AGENT);
      conn.setRequestProperty(
          "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
      int code = conn.getResponseCode();
      if (code < 200 || code >= 400) {
        throw new IOException("HTTP " + code + " from " + url);
      }
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          builder.append(line).append('\n');
        }
        return builder.toString();
      }
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  private static String resource(String key, String fallback) {
    try {
      if (Lizzie.resourceBundle != null && Lizzie.resourceBundle.containsKey(key)) {
        return Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception e) {
    }
    return fallback;
  }

  private static Path currentWorkingDir() {
    return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
  }

  private static Optional<Path> findAppRoot() {
    LinkedHashSet<Path> seedPaths = new LinkedHashSet<>();
    try {
      Path codePath =
          Paths.get(
              KataGoAutoSetupHelper.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI());
      seedPaths.add(Files.isDirectory(codePath) ? codePath : codePath.getParent());
    } catch (URISyntaxException e) {
    }
    seedPaths.add(currentWorkingDir());
    seedPaths.add(Paths.get("").toAbsolutePath().normalize());

    for (Path seedPath : seedPaths) {
      Path current = seedPath;
      for (int depth = 0; current != null && depth < 8; depth++) {
        if (Files.isDirectory(current.resolve("engines"))
            && Files.isDirectory(current.resolve("weights"))) {
          return Optional.of(current.toAbsolutePath().normalize());
        }
        current = current.getParent();
      }
    }
    return Optional.empty();
  }

  private static Path detectEngineBinary(Path workingDir, Path appRoot) {
    String binaryName = OS.isWindows() ? "katago.exe" : "katago";
    String platformDir = detectPlatformDir();
    List<Path> directCandidates = new ArrayList<>();
    directCandidates.add(
        workingDir.resolve("engines").resolve("katago").resolve(platformDir).resolve(binaryName));
    directCandidates.add(
        appRoot.resolve("engines").resolve("katago").resolve(platformDir).resolve(binaryName));
    directCandidates.add(workingDir.resolve("engines").resolve("katago").resolve(binaryName));
    directCandidates.add(appRoot.resolve("engines").resolve("katago").resolve(binaryName));
    for (Path candidate : directCandidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    Path searched = searchFileByName(workingDir.resolve("engines"), binaryName, 5);
    if (searched != null) {
      return searched;
    }
    return searchFileByName(appRoot.resolve("engines"), binaryName, 5);
  }

  private static Path detectConfig(Path workingDir, Path appRoot, String fileName) {
    List<Path> directCandidates = new ArrayList<>();
    directCandidates.add(
        workingDir.resolve("engines").resolve("katago").resolve("configs").resolve(fileName));
    directCandidates.add(
        appRoot.resolve("engines").resolve("katago").resolve("configs").resolve(fileName));
    for (Path candidate : directCandidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    Path searched = searchFileByName(workingDir.resolve("engines"), fileName, 6);
    if (searched != null) {
      return searched;
    }
    return searchFileByName(appRoot.resolve("engines"), fileName, 6);
  }

  private static List<Path> collectWeightCandidates(Path workingDir, Path appRoot) {
    LinkedHashSet<Path> candidates = new LinkedHashSet<>();
    collectWeightCandidates(candidates, workingDir.resolve("weights"));
    if (!workingDir.equals(appRoot)) {
      collectWeightCandidates(candidates, appRoot.resolve("weights"));
    }
    return new ArrayList<>(candidates);
  }

  private static void collectWeightCandidates(LinkedHashSet<Path> out, Path weightsDir) {
    if (!Files.isDirectory(weightsDir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(weightsDir, 3)) {
      paths
          .filter(Files::isRegularFile)
          .filter(
              path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bin.gz"))
          .sorted(
              Comparator.comparing(
                      (Path path) ->
                          path.getFileName().toString().equalsIgnoreCase("default.bin.gz"))
                  .reversed()
                  .thenComparing(
                      (Path path) -> {
                        try {
                          return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException e) {
                          return 0L;
                        }
                      },
                      Comparator.reverseOrder()))
          .forEach(path -> out.add(path.toAbsolutePath().normalize()));
    } catch (IOException e) {
    }
  }

  private static Path chooseActiveWeight(Path workingDir, Path appRoot, List<Path> candidates) {
    Path preferred = preferredWeightFromConfig(workingDir);
    if (preferred != null) {
      return preferred;
    }
    Path workingDefault =
        workingDir.resolve("weights").resolve("default.bin.gz").toAbsolutePath().normalize();
    if (Files.isRegularFile(workingDefault)) {
      return workingDefault;
    }
    if (!candidates.isEmpty()) {
      return candidates.get(0);
    }
    Path bundledDefault =
        appRoot.resolve("weights").resolve("default.bin.gz").toAbsolutePath().normalize();
    if (Files.isRegularFile(bundledDefault)) {
      return bundledDefault;
    }
    return null;
  }

  private static Path preferredWeightFromConfig(Path workingDir) {
    if (Lizzie.config == null || Lizzie.config.uiConfig == null) {
      return null;
    }
    String preferredText =
        Lizzie.config.uiConfig.optString("katago-preferred-weight-path", "").trim();
    if (preferredText.isEmpty()) {
      return null;
    }
    Path preferred = Paths.get(preferredText);
    if (!preferred.isAbsolute()) {
      preferred = workingDir.resolve(preferred);
    }
    preferred = preferred.toAbsolutePath().normalize();
    if (Files.isRegularFile(preferred)) {
      return preferred;
    }
    return null;
  }

  private static Path searchFileByName(Path root, String fileName, int maxDepth) {
    if (root == null || !Files.isDirectory(root)) {
      return null;
    }
    try (Stream<Path> paths = Files.walk(root, maxDepth)) {
      Optional<Path> found =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
              .findFirst();
      if (found.isPresent()) {
        return found.get().toAbsolutePath().normalize();
      }
    } catch (IOException e) {
    }
    return null;
  }

  private static String detectPlatformDir() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    boolean isArm = arch.contains("aarch64") || arch.contains("arm64");
    boolean is64 = arch.contains("64");
    if (osName.contains("win")) {
      return is64 ? "windows-x64" : "windows-x86";
    }
    if (osName.contains("mac") || osName.contains("darwin")) {
      return isArm ? "macos-arm64" : "macos-amd64";
    }
    return is64 ? "linux-x64" : "linux-x86";
  }

  private static String quoteCommandPath(Path workingDir, Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    Path displayPath = normalized;
    if (normalized.startsWith(workingDir)) {
      displayPath = workingDir.relativize(normalized);
    }
    return '"' + displayPath.toString() + '"';
  }

  private static String fileNameFromUrl(String url) {
    if (url == null || url.trim().isEmpty()) {
      return "";
    }
    int slashIndex = url.lastIndexOf('/');
    if (slashIndex < 0 || slashIndex == url.length() - 1) {
      return "";
    }
    String name = url.substring(slashIndex + 1);
    int queryIndex = name.indexOf('?');
    if (queryIndex >= 0) {
      name = name.substring(0, queryIndex);
    }
    return name;
  }

  private static String resolveUrl(String url) {
    if (url == null || url.trim().isEmpty()) {
      return "";
    }
    String trimmed = url.trim();
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      return trimmed;
    }
    try {
      return new URL(new URL(NETWORKS_URL), trimmed).toString();
    } catch (IOException e) {
      return trimmed;
    }
  }
}
