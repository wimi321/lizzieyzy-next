package featurecat.lizzie.util;

import featurecat.lizzie.Config;
import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineData;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
  private static final Pattern TABLE_PATTERN =
      Pattern.compile(
          "<table class=\"table mt-3\">(.*?)</table>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern ROW_PATTERN =
      Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern CELL_PATTERN =
      Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern HREF_PATTERN =
      Pattern.compile("<a[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern WEIGHT_FAMILY_PATTERN =
      Pattern.compile("\\b(b\\d+)c\\d+", Pattern.CASE_INSENSITIVE);
  private static final Pattern VERSION_MODEL_SOURCE_PATTERN =
      Pattern.compile("^Model source:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
  private static final int MAX_OFFICIAL_WEIGHTS = 40;
  private static final int MAX_OFFICIAL_WEIGHT_FAMILIES = 5;
  private static final int MAX_OFFICIAL_WEIGHTS_PER_FAMILY = 3;
  private static final List<String> PREFERRED_WEIGHT_FAMILIES =
      Collections.unmodifiableList(Arrays.asList("b28", "b40", "b20", "b15", "b10"));
  private static final String DEFAULT_WEIGHT_FILE_NAME = "default.bin.gz";

  private KataGoAutoSetupHelper() {}

  public interface ProgressListener {
    void onProgress(String statusText, long downloadedBytes, long totalBytes);
  }

  public static final class DownloadSession {
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;

    public void cancel() {
      cancelled = true;
      HttpURLConnection current = connection;
      if (current != null) {
        current.disconnect();
      }
    }

    public boolean isCancelled() {
      return cancelled || Thread.currentThread().isInterrupted();
    }

    void attach(HttpURLConnection conn) {
      connection = conn;
      if (cancelled && conn != null) {
        conn.disconnect();
      }
    }

    void clear() {
      connection = null;
    }

    void throwIfCancelled() throws DownloadCancelledException {
      if (isCancelled()) {
        throw new DownloadCancelledException(
            resource("AutoSetup.downloadCancelled", "Download cancelled."));
      }
    }
  }

  public static final class DownloadCancelledException extends InterruptedIOException {
    DownloadCancelledException(String message) {
      super(message);
    }
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
    public final String eloRating;
    public final boolean recommended;
    public final boolean latest;

    private RemoteWeightInfo(
        String typeLabel,
        String modelName,
        String downloadUrl,
        String uploadedAt,
        String eloRating,
        boolean recommended,
        boolean latest) {
      this.typeLabel = typeLabel;
      this.modelName = modelName;
      this.downloadUrl = downloadUrl;
      this.uploadedAt = uploadedAt;
      this.eloRating = eloRating;
      this.recommended = recommended;
      this.latest = latest;
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

  public static boolean migrateAutoSetupCommandsIfNeeded() {
    if (Lizzie.config == null
        || Lizzie.config.leelazConfig == null
        || Lizzie.config.uiConfig == null) {
      return false;
    }
    org.json.JSONArray engines = Lizzie.config.leelazConfig.optJSONArray("engine-settings-list");
    if (engines == null) {
      return false;
    }
    boolean needsRewrite = false;
    for (int i = 0; i < engines.length(); i++) {
      org.json.JSONObject engineInfo = engines.optJSONObject(i);
      if (engineInfo == null) {
        continue;
      }
      String name = engineInfo.optString("name", "").trim();
      String command = engineInfo.optString("command", "").trim();
      if (AUTO_SETUP_ENGINE_NAME.equals(name) && hasRelativeBundledPath(command)) {
        needsRewrite = true;
        break;
      }
    }
    if (!needsRewrite
        && (hasRelativeBundledPath(Lizzie.config.uiConfig.optString("analysis-engine-command", ""))
            || hasRelativeBundledPath(Lizzie.config.uiConfig.optString("estimate-command", "")))) {
      needsRewrite = true;
    }
    if (!needsRewrite) {
      return false;
    }
    try {
      SetupSnapshot snapshot = inspectLocalSetup();
      if (snapshot.hasEngine() && snapshot.hasConfigs() && snapshot.hasWeight()) {
        applyAutoSetup(snapshot.withActiveWeight(snapshot.activeWeightPath));
        return true;
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }

  public static boolean repairBrokenBundledCommandsIfNeeded() {
    if (Lizzie.config == null
        || Lizzie.config.uiConfig == null
        || Lizzie.config.leelazConfig == null) {
      return false;
    }
    try {
      SetupSnapshot snapshot = inspectLocalSetup();
      if (!snapshot.hasEngine() || !snapshot.hasConfigs() || !snapshot.hasWeight()) {
        return false;
      }
      ArrayList<EngineData> engines = Utils.getEngineData();
      int defaultEngine = Lizzie.config.uiConfig.optInt("default-engine", -1);
      boolean repairDefault = false;
      if (defaultEngine >= 0 && defaultEngine < engines.size()) {
        EngineData engineData = engines.get(defaultEngine);
        repairDefault =
            shouldRepairBundledCommand(
                engineData.name,
                engineData.commands,
                snapshot.enginePath,
                snapshot.gtpConfigPath,
                snapshot.activeWeightPath);
      }
      if (!repairDefault) {
        for (EngineData engineData : engines) {
          if (engineData != null
              && engineData.isDefault
              && shouldRepairBundledCommand(
                  engineData.name,
                  engineData.commands,
                  snapshot.enginePath,
                  snapshot.gtpConfigPath,
                  snapshot.activeWeightPath)) {
            repairDefault = true;
            break;
          }
        }
      }
      boolean repairAnalysis =
          shouldRepairAuxCommand(
              Lizzie.config.uiConfig.optString("analysis-engine-command", ""),
              snapshot.enginePath,
              snapshot.analysisConfigPath,
              snapshot.activeWeightPath);
      boolean repairEstimate =
          shouldRepairAuxCommand(
              Lizzie.config.uiConfig.optString("estimate-command", ""),
              snapshot.enginePath,
              snapshot.estimateConfigPath != null
                  ? snapshot.estimateConfigPath
                  : snapshot.gtpConfigPath,
              snapshot.activeWeightPath);
      if (!(repairDefault || repairAnalysis || repairEstimate)) {
        return false;
      }
      applyAutoSetup(snapshot.withActiveWeight(snapshot.activeWeightPath));
      return true;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }

  public static boolean repairBrokenStartupEngineIfNeeded() {
    if (Lizzie.config == null
        || Lizzie.config.uiConfig == null
        || Lizzie.config.leelazConfig == null) {
      return false;
    }
    try {
      SetupSnapshot snapshot = inspectLocalSetup();
      if (!snapshot.hasEngine() || !snapshot.hasConfigs() || !snapshot.hasWeight()) {
        return false;
      }
      ArrayList<EngineData> engines = Utils.getEngineData();
      int startupEngineIndex = resolveStartupEngineIndex(engines);
      if (!shouldRepairStartupEngine(engines, startupEngineIndex, snapshot)) {
        return false;
      }
      if (startupEngineIndex >= 0 && startupEngineIndex < engines.size()) {
        EngineData startupEngine = engines.get(startupEngineIndex);
        if (startupEngine != null) {
          startupEngine.name = AUTO_SETUP_ENGINE_NAME;
          Utils.saveEngineSettings(engines);
        }
      }
      applyAutoSetup(snapshot.withActiveWeight(snapshot.activeWeightPath));
      return true;
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }

  public static List<RemoteWeightInfo> fetchOfficialWeights() throws IOException {
    return parseOfficialWeights(httpGet(NETWORKS_URL));
  }

  public static RemoteWeightInfo fetchRecommendedWeight() throws IOException {
    List<RemoteWeightInfo> weights = fetchOfficialWeights();
    for (RemoteWeightInfo info : weights) {
      if (info.recommended) {
        return info;
      }
    }
    for (RemoteWeightInfo info : weights) {
      if (info.latest) {
        return info;
      }
    }
    if (!weights.isEmpty()) {
      return weights.get(0);
    }
    throw new IOException("Unable to parse KataGo official weights.");
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
    return downloadWeight(info, listener, null);
  }

  public static Path downloadWeight(
      RemoteWeightInfo info, ProgressListener listener, DownloadSession session)
      throws IOException {
    SetupSnapshot snapshot = inspectLocalSetup();
    Path weightsDir = snapshot.workingDir.resolve("weights");
    Files.createDirectories(weightsDir);
    DownloadSession activeSession = session != null ? session : new DownloadSession();
    activeSession.throwIfCancelled();

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
      activeSession.attach(conn);
      activeSession.throwIfCancelled();
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
        while (true) {
          activeSession.throwIfCancelled();
          read = input.read(buffer);
          if (read < 0) {
            break;
          }
          output.write(buffer, 0, read);
          downloaded += read;
          activeSession.throwIfCancelled();
          long now = System.currentTimeMillis();
          if (listener != null && (now - lastReportTime > 120 || totalBytes == downloaded)) {
            listener.onProgress(info.modelName, downloaded, totalBytes);
            lastReportTime = now;
          }
        }
      }
      activeSession.throwIfCancelled();
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
      if (activeSession.isCancelled() && !(e instanceof DownloadCancelledException)) {
        throw new DownloadCancelledException(
            resource("AutoSetup.downloadCancelled", "Download cancelled."));
      }
      throw e;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
      activeSession.clear();
    }
  }

  public static String resolveActiveWeightModelName(SetupSnapshot snapshot) {
    if (snapshot == null || snapshot.activeWeightPath == null) {
      return "";
    }
    String fileName = snapshot.activeWeightPath.getFileName().toString();
    if (!DEFAULT_WEIGHT_FILE_NAME.equalsIgnoreCase(fileName)) {
      return fileName;
    }
    String bundledModel = readBundledModelSource(snapshot.workingDir, snapshot.appRoot);
    if (!bundledModel.isEmpty()) {
      return bundledModel;
    }
    if (Lizzie.config != null && Lizzie.config.uiConfig != null) {
      String remembered =
          Lizzie.config.uiConfig.optString("katago-auto-setup-weight-name", "").trim();
      if (!remembered.isEmpty() && !DEFAULT_WEIGHT_FILE_NAME.equalsIgnoreCase(remembered)) {
        return remembered;
      }
    }
    return fileName;
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

  private static List<RemoteWeightInfo> parseOfficialWeights(String html) throws IOException {
    Matcher strongestMatcher = STRONGEST_PATTERN.matcher(html);
    String strongestUrl = "";
    String strongestName = "";
    if (strongestMatcher.find()) {
      strongestUrl = resolveUrl(strongestMatcher.group(1));
      strongestName = collapseWhitespace(strongestMatcher.group(2));
    }

    Matcher latestMatcher = LATEST_PATTERN.matcher(html);
    String latestUrl = "";
    String latestName = "";
    if (latestMatcher.find()) {
      latestUrl = resolveUrl(latestMatcher.group(1));
      latestName = collapseWhitespace(latestMatcher.group(2));
    }

    Matcher tableMatcher = TABLE_PATTERN.matcher(html);
    List<RemoteWeightInfo> parsedWeights = new ArrayList<>();
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    boolean foundTable = false;
    while (tableMatcher.find()) {
      foundTable = true;
      Matcher rowMatcher = ROW_PATTERN.matcher(tableMatcher.group(1));
      while (rowMatcher.find()) {
        List<String> cells = extractCells(rowMatcher.group(2));
        if (cells.size() < 4) {
          continue;
        }
        String modelName = cleanHtmlText(cells.get(0));
        String uploadedAt = cleanHtmlText(cells.get(1));
        String eloRating = cleanHtmlText(cells.get(2));
        String downloadUrl = resolveUrl(extractHref(cells.get(3)));
        if (modelName.isEmpty() || downloadUrl.isEmpty()) {
          continue;
        }
        String dedupKey = modelName.toLowerCase(Locale.ROOT);
        if (!seen.add(dedupKey)) {
          continue;
        }
        boolean recommended =
            matchesRemoteWeight(modelName, downloadUrl, strongestName, strongestUrl);
        boolean latest = matchesRemoteWeight(modelName, downloadUrl, latestName, latestUrl);
        parsedWeights.add(
            new RemoteWeightInfo(
                buildTypeLabel(recommended, latest, buildWeightFamilyDisplay(modelName)),
                modelName,
                downloadUrl,
                uploadedAt,
                eloRating,
                recommended,
                latest));
      }
    }
    if (!foundTable) {
      throw new IOException("Unable to parse KataGo weight table.");
    }
    List<RemoteWeightInfo> weights = selectOfficialWeightChoices(parsedWeights);
    if (weights.isEmpty()) {
      throw new IOException("Unable to parse KataGo official weights.");
    }
    return weights;
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

  private static boolean matchesRemoteWeight(
      String modelName, String downloadUrl, String expectedName, String expectedUrl) {
    if (!expectedUrl.isEmpty() && expectedUrl.equalsIgnoreCase(downloadUrl)) {
      return true;
    }
    return !expectedName.isEmpty() && expectedName.equalsIgnoreCase(modelName);
  }

  private static String buildTypeLabel(boolean recommended, boolean latest, String familyLabel) {
    String recommendedLabel = resource("AutoSetup.recommendedStrongest", "Strongest");
    String latestLabel = resource("AutoSetup.recommendedLatest", "Latest");
    String officialLabel = resource("AutoSetup.officialWeight", "Official");
    String baseLabel;
    if (recommended && latest) {
      baseLabel = recommendedLabel + " / " + latestLabel;
    } else if (recommended) {
      baseLabel = recommendedLabel;
    } else if (latest) {
      baseLabel = latestLabel;
    } else {
      baseLabel = officialLabel;
    }
    if (familyLabel.isEmpty()) {
      return baseLabel;
    }
    return baseLabel + " · " + familyLabel;
  }

  private static List<RemoteWeightInfo> selectOfficialWeightChoices(
      List<RemoteWeightInfo> parsedWeights) {
    if (parsedWeights.isEmpty()) {
      return parsedWeights;
    }
    LinkedHashMap<String, List<RemoteWeightInfo>> byFamily = new LinkedHashMap<>();
    for (RemoteWeightInfo info : parsedWeights) {
      String family = normalizeWeightFamily(info.modelName);
      if (family.isEmpty()) {
        family = info.modelName.toLowerCase(Locale.ROOT);
      }
      List<RemoteWeightInfo> familyWeights = byFamily.get(family);
      if (familyWeights == null) {
        familyWeights = new ArrayList<>();
        byFamily.put(family, familyWeights);
      }
      familyWeights.add(info);
    }

    List<String> chosenFamilies = chooseOfficialWeightFamilies(byFamily);
    List<RemoteWeightInfo> selected = new ArrayList<>();
    for (String family : chosenFamilies) {
      List<RemoteWeightInfo> familyWeights = byFamily.get(family);
      if (familyWeights == null) {
        continue;
      }
      for (int i = 0; i < familyWeights.size() && i < MAX_OFFICIAL_WEIGHTS_PER_FAMILY; i++) {
        selected.add(familyWeights.get(i));
      }
    }
    if (selected.isEmpty()) {
      return new ArrayList<>(
          parsedWeights.subList(0, Math.min(parsedWeights.size(), MAX_OFFICIAL_WEIGHTS)));
    }
    if (selected.size() > MAX_OFFICIAL_WEIGHTS) {
      return new ArrayList<>(selected.subList(0, MAX_OFFICIAL_WEIGHTS));
    }
    return selected;
  }

  private static List<String> chooseOfficialWeightFamilies(
      LinkedHashMap<String, List<RemoteWeightInfo>> byFamily) {
    List<String> chosen = new ArrayList<>();
    for (String family : PREFERRED_WEIGHT_FAMILIES) {
      if (byFamily.containsKey(family)) {
        chosen.add(family);
      }
      if (chosen.size() >= MAX_OFFICIAL_WEIGHT_FAMILIES) {
        return chosen;
      }
    }
    for (String family : byFamily.keySet()) {
      if (!chosen.contains(family)) {
        chosen.add(family);
      }
      if (chosen.size() >= MAX_OFFICIAL_WEIGHT_FAMILIES) {
        break;
      }
    }
    return chosen;
  }

  private static String normalizeWeightFamily(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) {
      return "";
    }
    Matcher matcher = WEIGHT_FAMILY_PATTERN.matcher(modelName);
    if (matcher.find()) {
      return matcher.group(1).toLowerCase(Locale.ROOT);
    }
    return "";
  }

  private static String buildWeightFamilyDisplay(String modelName) {
    String family = normalizeWeightFamily(modelName);
    if (family.isEmpty() || family.length() <= 1) {
      return "";
    }
    return family.substring(1).toUpperCase(Locale.ROOT) + "B";
  }

  private static List<String> extractCells(String rowHtml) {
    List<String> cells = new ArrayList<>();
    Matcher cellMatcher = CELL_PATTERN.matcher(rowHtml);
    while (cellMatcher.find()) {
      cells.add(cellMatcher.group(1));
    }
    return cells;
  }

  private static String extractHref(String htmlFragment) {
    Matcher hrefMatcher = HREF_PATTERN.matcher(htmlFragment);
    if (hrefMatcher.find()) {
      return hrefMatcher.group(1).trim();
    }
    return "";
  }

  private static String cleanHtmlText(String htmlFragment) {
    String text = htmlFragment.replaceAll("(?i)<br\\s*/?>", " ");
    text = text.replaceAll("(?s)<[^>]+>", " ");
    text = decodeHtmlEntities(text);
    return collapseWhitespace(text);
  }

  private static String collapseWhitespace(String text) {
    if (text == null) {
      return "";
    }
    return text.replaceAll("\\s+", " ").trim();
  }

  private static String decodeHtmlEntities(String text) {
    String decoded =
        text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&plusmn;", "±")
            .replace("&#177;", "±");
    Matcher hexMatcher = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(decoded);
    StringBuffer hexBuffer = new StringBuffer();
    while (hexMatcher.find()) {
      int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
      hexMatcher.appendReplacement(
          hexBuffer, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
    }
    hexMatcher.appendTail(hexBuffer);

    Matcher decMatcher = Pattern.compile("&#(\\d+);").matcher(hexBuffer.toString());
    StringBuffer decBuffer = new StringBuffer();
    while (decMatcher.find()) {
      int codePoint = Integer.parseInt(decMatcher.group(1));
      decMatcher.appendReplacement(
          decBuffer, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
    }
    decMatcher.appendTail(decBuffer);
    return decBuffer.toString();
  }

  private static String readBundledModelSource(Path workingDir, Path appRoot) {
    List<Path> candidates = new ArrayList<>();
    candidates.add(workingDir.resolve("engines").resolve("katago").resolve("VERSION.txt"));
    if (!workingDir.equals(appRoot)) {
      candidates.add(appRoot.resolve("engines").resolve("katago").resolve("VERSION.txt"));
    }
    for (Path candidate : candidates) {
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      try {
        String text = new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
        Matcher matcher = VERSION_MODEL_SOURCE_PATTERN.matcher(text);
        if (matcher.find()) {
          String modelName = matcher.group(1).trim();
          if (!modelName.isEmpty()) {
            return modelName;
          }
        }
      } catch (IOException e) {
      }
    }
    return "";
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
    return '"' + normalized.toString() + '"';
  }

  private static boolean hasRelativeBundledPath(String command) {
    if (command == null || command.trim().isEmpty()) {
      return false;
    }
    String normalized = command.replace('\\', '/');
    return normalized.contains("\"engines/")
        || normalized.contains(" engines/")
        || normalized.contains("\"weights/")
        || normalized.contains(" weights/");
  }

  private static boolean shouldRepairBundledCommand(
      String name,
      String command,
      Path expectedEnginePath,
      Path expectedConfigPath,
      Path expectedWeightPath) {
    if (command == null || command.trim().isEmpty()) {
      return false;
    }
    boolean bundledLike =
        AUTO_SETUP_ENGINE_NAME.equals(name)
            || "KataGo Bundled".equals(name)
            || Config.isBundledKataGoCommand(command);
    if (!bundledLike) {
      return false;
    }
    return isCommandBrokenOrOutdated(
        command, expectedEnginePath, expectedConfigPath, expectedWeightPath);
  }

  private static int resolveStartupEngineIndex(ArrayList<EngineData> engines) {
    if (engines == null || engines.isEmpty()) {
      return -1;
    }
    int defaultEngine = Lizzie.config.uiConfig.optInt("default-engine", -1);
    if (defaultEngine >= 0 && defaultEngine < engines.size()) {
      return defaultEngine;
    }
    for (int i = 0; i < engines.size(); i++) {
      EngineData engineData = engines.get(i);
      if (engineData != null && engineData.isDefault) {
        return i;
      }
    }
    return -1;
  }

  private static boolean shouldRepairStartupEngine(
      ArrayList<EngineData> engines, int startupEngineIndex, SetupSnapshot snapshot) {
    if (engines == null || engines.isEmpty()) {
      return true;
    }
    if (startupEngineIndex < 0 || startupEngineIndex >= engines.size()) {
      return true;
    }
    EngineData startupEngine = engines.get(startupEngineIndex);
    if (startupEngine == null) {
      return true;
    }
    if (startupEngine.useJavaSSH) {
      return false;
    }
    String command = startupEngine.commands == null ? "" : startupEngine.commands.trim();
    if (command.isEmpty()) {
      return true;
    }
    if (shouldRepairBundledCommand(
        startupEngine.name,
        command,
        snapshot.enginePath,
        snapshot.gtpConfigPath,
        snapshot.activeWeightPath)) {
      return true;
    }
    return isLegacyStartupCommandBroken(startupEngine.name, command);
  }

  private static boolean isLegacyStartupCommandBroken(String name, String command) {
    List<String> commandParts = Utils.splitCommand(command);
    if (commandParts == null || commandParts.isEmpty()) {
      return true;
    }
    String executableToken = commandParts.get(0);
    Path executablePath = KataGoRuntimeHelper.resolveCommandExecutable(commandParts);
    boolean executableMissing = executablePath == null || !Files.isRegularFile(executablePath);
    if (Utils.isJavaCommand(executableToken)) {
      return executableMissing || !hasUsableJarTarget(commandParts);
    }
    if (!looksLikeManagedStartupCommand(name, command)) {
      return false;
    }
    if (executableMissing) {
      return true;
    }
    if (referencesManagedAssets(command) && hasMissingReferencedAsset(commandParts)) {
      return true;
    }
    return false;
  }

  private static boolean looksLikeManagedStartupCommand(String name, String command) {
    String normalizedName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    if (AUTO_SETUP_ENGINE_NAME.equals(name) || "KataGo Bundled".equals(name)) {
      return true;
    }
    if (normalizedName.contains("katago")
        || normalizedName.contains("lizzie")
        || normalizedName.contains("foxuid")) {
      return true;
    }
    String normalizedCommand = command == null ? "" : command.toLowerCase(Locale.ROOT);
    return normalizedCommand.contains("katago")
        || normalizedCommand.contains(".bin.gz")
        || normalizedCommand.contains(".jar")
        || normalizedCommand.contains("weights")
        || normalizedCommand.contains("analysis.cfg")
        || normalizedCommand.contains("gtp.cfg");
  }

  private static boolean referencesManagedAssets(String command) {
    if (command == null || command.trim().isEmpty()) {
      return false;
    }
    String normalized = command.toLowerCase(Locale.ROOT).replace('\\', '/');
    return normalized.contains("engines/")
        || normalized.contains("weights/")
        || normalized.contains(".lizzieyzy-next")
        || normalized.contains(".lizzieyzy-next-foxuid")
        || normalized.contains("lizzieyzy next")
        || normalized.contains("lizzieyzy-next")
        || normalized.contains("lizzie-yzy");
  }

  private static boolean hasUsableJarTarget(List<String> commandParts) {
    for (int i = 0; i < commandParts.size() - 1; i++) {
      if ("-jar".equals(commandParts.get(i))) {
        try {
          return Files.isRegularFile(
              Paths.get(commandParts.get(i + 1)).toAbsolutePath().normalize());
        } catch (Exception e) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean hasMissingReferencedAsset(List<String> commandParts) {
    Path modelPath = extractOptionPath(commandParts, "-model");
    if (modelPath != null && !Files.isRegularFile(modelPath)) {
      return true;
    }
    Path configPath = extractOptionPath(commandParts, "-config");
    if (configPath != null && !Files.isRegularFile(configPath)) {
      return true;
    }
    return false;
  }

  private static boolean shouldRepairAuxCommand(
      String command, Path expectedEnginePath, Path expectedConfigPath, Path expectedWeightPath) {
    if (!Config.isBundledKataGoCommand(command)) {
      return false;
    }
    return isCommandBrokenOrOutdated(
        command, expectedEnginePath, expectedConfigPath, expectedWeightPath);
  }

  private static boolean isCommandBrokenOrOutdated(
      String command, Path expectedEnginePath, Path expectedConfigPath, Path expectedWeightPath) {
    List<String> commandParts = Utils.splitCommand(command);
    Path actualEnginePath = KataGoRuntimeHelper.resolveCommandExecutable(commandParts);
    if (!pathMatches(actualEnginePath, expectedEnginePath)) {
      return true;
    }
    Path actualWeightPath = extractOptionPath(commandParts, "-model");
    if (!pathMatches(actualWeightPath, expectedWeightPath)) {
      return true;
    }
    Path actualConfigPath = extractOptionPath(commandParts, "-config");
    if (!pathMatches(actualConfigPath, expectedConfigPath)) {
      return true;
    }
    return false;
  }

  private static Path extractOptionPath(List<String> commandParts, String optionName) {
    if (commandParts == null || optionName == null) {
      return null;
    }
    for (int i = 0; i < commandParts.size() - 1; i++) {
      if (optionName.equals(commandParts.get(i))) {
        try {
          return Paths.get(commandParts.get(i + 1)).toAbsolutePath().normalize();
        } catch (Exception e) {
          return null;
        }
      }
    }
    return null;
  }

  private static boolean pathMatches(Path actual, Path expected) {
    if (expected == null) {
      return actual != null && Files.isRegularFile(actual);
    }
    if (actual == null) {
      return false;
    }
    Path normalizedActual = actual.toAbsolutePath().normalize();
    Path normalizedExpected = expected.toAbsolutePath().normalize();
    return Files.isRegularFile(normalizedActual) && normalizedActual.equals(normalizedExpected);
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
