package featurecat.lizzie.theme;

import static java.io.File.separator;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.util.Utils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Theme Allow to load the external image & theme config */
public class Theme {
  public static final String CUSTOM_BOARD_IMAGE_KEY = "custom-board-image";
  public static final String CUSTOM_BACKGROUND_IMAGE_KEY = "custom-window-background-image";

  BufferedImage blackStoneCached = null;
  BufferedImage whiteStoneCached = null;
  BufferedImage boardCached = null;
  BufferedImage backgroundCached = null;

  private String configFile = "theme.txt";
  public static String pathPrefix = "theme" + separator;
  public String path = null;
  public JSONObject config = new JSONObject();
  private JSONObject uiConfig = null;
  private Optional<List<Double>> blunderWinrateThresholds = Optional.empty();

  public Theme() {}

  public boolean getTheme(JSONObject uiConfig) {
    this.uiConfig = uiConfig;
    String themeName = uiConfig.optString("theme");
    this.path = Theme.pathPrefix + (themeName.isEmpty() ? "" : themeName + separator);
    File file = new File(this.path + this.configFile);
    boolean canReadFile = file.canRead();
    if (canReadFile) {
      try (FileInputStream fp = new FileInputStream(file)) {
        config = new JSONObject(new JSONTokener(fp));
      } catch (IOException | JSONException e) {
        e.printStackTrace();
      }
    }
    return canReadFile;
  }

  private String getImagePathByKey(String key) {
    return config.optString(key);
  }

  public String backgroundPath() {
    return getEffectiveImagePath(CUSTOM_BACKGROUND_IMAGE_KEY, "background-image");
  }

  public int stoneIndicatorType() {
    String key = "stone-indicator-type";
    return config.optInt(key, uiConfig.optInt(key, 1));
  }

  public String blackStonePath() {
    return getImagePathByKey("black-stone-image");
  }

  public String whiteStonePath() {
    return getImagePathByKey("white-stone-image");
  }

  public String boardPath() {
    return getEffectiveImagePath(CUSTOM_BOARD_IMAGE_KEY, "board-image");
  }

  public Color pureBoardColor() {
    return Utils.getNoneAlphaColor(getColorByKey("pure-board-color", new Color(217, 152, 77)));
  }

  public Color pureBackgroundColor() {
    return Utils.getNoneAlphaColor(getColorByKey("pure-background-color", Color.GRAY));
  }

  public Color scoreMeanLineColor() {
    return getColorByKey("scoremean-line-color", new Color(255, 0, 255));
  }

  public Theme(String themeName) {
    this.uiConfig = Lizzie.config.uiConfig;
    this.path = Theme.pathPrefix + (themeName.isEmpty() ? "" : themeName + separator);
    File file = new File(this.path + this.configFile);
    if (file.canRead()) {
      try (FileInputStream fp = new FileInputStream(file)) {
        config = new JSONObject(new JSONTokener(fp));
      } catch (IOException | JSONException e) {
        e.printStackTrace();
      }
    }
  }

  public String boardImageString() {
    return getEffectiveImageString(CUSTOM_BOARD_IMAGE_KEY, "board-image", "board.png");
  }

  public String backgroundImageString() {
    return getEffectiveImageString(
        CUSTOM_BACKGROUND_IMAGE_KEY, "background-image", "background.png");
  }

  public String whiteStoneImageString() {
    return this.path + config.optString("white-stone-image", "white.png");
  }

  public String blackStoneImageString() {
    return this.path + config.optString("black-stone-image", "black.png");
  }

  public BufferedImage blackStone() {
    if (blackStoneCached == null) {
      blackStoneCached = getImageByKey("black-stone-image", "black.png", "black0.png");
      if (blackStoneCached == null) {
        blackStoneCached = createStoneFallback(new Color(30, 30, 30), new Color(0, 0, 0, 180));
      }
    }
    return blackStoneCached;
  }

  public BufferedImage whiteStone() {
    if (whiteStoneCached == null) {
      whiteStoneCached = getImageByKey("white-stone-image", "white.png", "white0.png");
      if (whiteStoneCached == null) {
        whiteStoneCached =
            createStoneFallback(new Color(245, 245, 245), new Color(180, 180, 180, 220));
      }
    }
    return whiteStoneCached;
  }

  public BufferedImage board() {
    if (boardCached == null) {
      boardCached =
          getImageByKeyWithOverride(
              CUSTOM_BOARD_IMAGE_KEY, "board-image", "board.png", "board.png");
      if (boardCached == null) {
        boardCached = createSolidFallbackImage(128, 128, new Color(217, 152, 77));
      }
    }
    return boardCached;
  }

  public void clearBoardCache() {
    boardCached = null;
  }

  public BufferedImage background() {
    if (backgroundCached == null) {
      backgroundCached =
          getImageByKeyWithOverride(
              CUSTOM_BACKGROUND_IMAGE_KEY, "background-image", "background.png", "background.jpg");
      if (backgroundCached == null) {
        backgroundCached = createSolidFallbackImage(128, 128, new Color(38, 38, 42));
      }
    }
    return backgroundCached;
  }

  public void clearBackgroundCache() {
    backgroundCached = null;
  }

  public boolean isUsingCustomBoardImage() {
    return hasReadableConfiguredImage(getUiImagePath(CUSTOM_BOARD_IMAGE_KEY), Theme.pathPrefix);
  }

  public boolean isUsingCustomBackgroundImage() {
    return hasReadableConfiguredImage(
        getUiImagePath(CUSTOM_BACKGROUND_IMAGE_KEY), Theme.pathPrefix);
  }

  /** Use custom font for general text */
  public String fontName() {
    String key = "font-name";
    //    String name = config.optString(key, uiConfig.optString(key, "微软雅黑"));
    //    if (name.equals("Lizzie默认")) name = "微软雅黑";
    return config.optString(key, uiConfig.optString(key, null));
  }

  /** Use custom font for the UI */
  public String uiFontName() {
    String key = "ui-font-name";
    String name = config.optString(key, uiConfig.optString(key, "Lizzie Default")); // 微软雅黑
    if (Lizzie.config != null
        && Lizzie.config.isChinese
        && (name.equals("Lizzie默认") || name.equals("Lizzie Default")))
      name = "Microsoft YaHei"; // 微软雅黑
    return name;
  }

  /** Use custom font for the Leela Zero winrate on the stone */
  public String winrateFontName() {
    String key = "winrate-font-name";
    return config.optString(key, uiConfig.optString(key, null));
  }

  /** Show the node with the comment color */
  public boolean showCommentNodeColor(boolean onLoad) {
    String key = "show-comment-node-color";
    if (onLoad) return config.optBoolean(key, uiConfig.optBoolean(key, true));
    else return config.optBoolean(key, true);
  }

  public boolean usePureStone(boolean onLoad) {
    String key = "use-pure-stone";
    if (onLoad) return config.optBoolean(key, uiConfig.optBoolean(key, false));
    else return config.optBoolean(key, false);
  }

  public boolean usePureBackground(boolean onLoad) {
    String key = "use-pure-background";
    if (onLoad) return config.optBoolean(key, uiConfig.optBoolean(key, false));
    else return config.optBoolean(key, false);
  }

  public boolean usePureBoard(boolean onLoad) {
    String key = "use-pure-board";
    if (onLoad) return config.optBoolean(key, uiConfig.optBoolean(key, false));
    else return config.optBoolean(key, false);
  }

  public boolean useScoreDiffInVariationTree(boolean onLoad) {
    String key = "use-scorediff-in-variation-tree";
    if (onLoad) return config.optBoolean(key, uiConfig.optBoolean(key, true));
    else return config.optBoolean(key, true);
  }

  public double scoreDiffInVariationTreeFactor(boolean onLoad) {
    String key = "scorediff-in-variation-tree-factor";
    if (onLoad) return config.optDouble(key, uiConfig.optDouble(key, 0.5));
    else return config.optDouble(key, 0.5);
  }

  public boolean showStoneShadow(boolean onLoad) {
    String key = "show-stone-shadow";
    if (onLoad) return config.optBoolean(key, uiConfig.optBoolean(key, true));
    else return config.optBoolean(key, true);
  }

  /** The size of the shadow */
  public int shadowSize() {
    return getIntByKey("shadow-size", 85);
  }

  /** The stroke width of the winrate line */
  public float winrateStrokeWidth() {
    return config.optFloat("winrate-stroke-width", uiConfig.optFloat("winrate-stroke-width", 1.7f));
  }

  public float scoreLeadStrokeWidth() {
    return config.optFloat(
        "score-lead-stroke-width", uiConfig.optFloat("score-lead-stroke-width", 1.0f));
  }

  /** The minimum width of the blunder bar */
  public int minimumBlunderBarWidth() {
    return getIntByKey("minimum-blunder-bar-width", 1);
  }

  /** The font size of the comment */
  public int commentFontSize() {
    return getIntByKey("comment-font-size", 0);
  }

  public int backgroundFilter() {
    return getIntByKey("background-filter", 20);
  }

  /** The size of the shadow */
  public int nodeColorMode() {
    return getIntByKey("node-color-mode", 0);
  }

  /**
   * The background color of the comment panel
   *
   * @return
   */
  public Color commentBackgroundColor() {
    return getColorByKey("comment-background-color", new Color(0, 0, 0, 200));
  }

  /** The font color of the comment */
  public Color commentFontColor() {
    return getColorByKey("comment-font-color", Color.WHITE);
  }

  /** The color of the node with the comment */
  public Color commentNodeColor() {
    return getColorByKey("comment-node-color", Color.BLUE);
  }

  /** The color of the winrate line */
  public Color winrateLineColor() {
    return getColorByKey("winrate-line-color", Color.GREEN);
  }

  /** The color of the line that missed the winrate */
  public Color winrateMissLineColor() {
    return getColorByKey("winrate-miss-line-color", Color.blue.darker());
  }

  /** The color of the blunder bar */
  public Color blunderBarColor() {
    return getColorByKey("blunder-bar-color", new Color(255, 204, 255, 170));
  }

  public Color bestMoveColor() {
    return getColorByKey("best-move-color", Color.CYAN);
  }

  //  public Color bestVarPanelColor() {
  //    return getColorByKey("var-panel-color", new Color(80, 80, 80));
  //  }

  /** The threshold list of the blunder winrate */
  public Optional<List<Double>> blunderWinrateThresholds() {
    String key = "blunder-winrate-thresholds";
    Optional<JSONArray> array = Optional.ofNullable(config.optJSONArray(key));
    if (!array.isPresent()) {
      array = Optional.ofNullable(uiConfig.optJSONArray(key));
    }
    array.ifPresent(
        m -> {
          blunderWinrateThresholds = Optional.of(new ArrayList<Double>());
          m.forEach(a -> blunderWinrateThresholds.get().add(Double.valueOf(a.toString())));
        });
    return blunderWinrateThresholds;
  }

  /** The color list of the blunder node */
  public Optional<Map<Double, Color>> blunderNodeColors() {
    Optional<Map<Double, Color>> map = Optional.of(new HashMap<Double, Color>());
    String key = "blunder-node-colors";
    Optional<JSONArray> array = Optional.ofNullable(config.optJSONArray(key));
    if (!array.isPresent()) {
      array = Optional.ofNullable(uiConfig.optJSONArray(key));
    }
    array.ifPresent(
        a -> {
          IntStream.range(0, a.length())
              .forEach(
                  i -> {
                    Color color = array2Color((JSONArray) a.get(i), null);
                    blunderWinrateThresholds.map(l -> l.get(i)).map(t -> map.get().put(t, color));
                  });
        });
    return map;
  }

  private Color getColorByKey(String key, Color defaultColor) {
    Color color = array2Color(config.optJSONArray(key), null);
    if (color == null) {
      color = array2Color(uiConfig.optJSONArray(key), defaultColor);
    }
    return color;
  }

  /** Convert option color array to Color */
  public static Color array2Color(JSONArray a, Color defaultColor) {
    if (a != null) {
      if (a.length() == 3) {
        return new Color(a.getInt(0), a.getInt(1), a.getInt(2));
      } else if (a.length() == 4) {
        return new Color(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3));
      }
    }
    return defaultColor;
  }

  private BufferedImage getImageByKey(String key, String defaultValue, String defaultImg) {
    BufferedImage image = null;
    try {
      image = readConfiguredImage(config.optString(key, defaultValue), this.path);
    } catch (IOException e) {
      image = null;
    }
    if (image == null) {
      try {
        image = readConfiguredImage(uiConfig.optString(key, defaultValue), Theme.pathPrefix);
      } catch (IOException e1) {
        image = null;
      }
    }
    if (image == null) {
      try (InputStream stream = getClass().getResourceAsStream("/assets/" + defaultImg)) {
        if (stream != null) {
          image = ImageIO.read(stream);
        }
      } catch (IOException | IllegalArgumentException e2) {
        e2.printStackTrace();
      }
    }
    return image;
  }

  private BufferedImage getImageByKeyWithOverride(
      String overrideKey, String key, String defaultValue, String defaultImg) {
    BufferedImage image = null;
    try {
      image = readConfiguredImage(getUiImagePath(overrideKey), Theme.pathPrefix);
    } catch (IOException e) {
      image = null;
    }
    if (image != null) {
      return image;
    }
    return getImageByKey(key, defaultValue, defaultImg);
  }

  private String getUiImagePath(String key) {
    return uiConfig == null ? "" : uiConfig.optString(key, "").trim();
  }

  private String getEffectiveImagePath(String overrideKey, String key) {
    String overridePath = getUiImagePath(overrideKey);
    if (!overridePath.isEmpty()) {
      return overridePath;
    }
    return getImagePathByKey(key);
  }

  private String getEffectiveImageString(String overrideKey, String key, String defaultValue) {
    String overridePath = getUiImagePath(overrideKey);
    if (!overridePath.isEmpty()) {
      return normalizeImagePath(overridePath, Theme.pathPrefix);
    }
    if (config.has(key)) {
      return normalizeImagePath(config.optString(key, defaultValue), this.path);
    }
    if (uiConfig != null && uiConfig.has(key)) {
      return normalizeImagePath(uiConfig.optString(key, defaultValue), Theme.pathPrefix);
    }
    return normalizeImagePath(defaultValue, this.path);
  }

  private String normalizeImagePath(String configuredPath, String basePath) {
    if (configuredPath == null || configuredPath.trim().isEmpty()) {
      return "";
    }
    File file = new File(configuredPath);
    if (file.isAbsolute()) {
      return file.getPath();
    }
    return basePath + configuredPath;
  }

  private BufferedImage readConfiguredImage(String configuredPath, String basePath)
      throws IOException {
    File file = resolveConfiguredFile(configuredPath, basePath);
    if (!file.canRead()) {
      return null;
    }
    return ImageIO.read(file);
  }

  private File resolveConfiguredFile(String configuredPath, String basePath) {
    if (configuredPath == null || configuredPath.trim().isEmpty()) {
      return new File("");
    }
    File file = new File(configuredPath);
    if (!file.isAbsolute()) {
      file = new File(basePath + configuredPath);
    }
    return file;
  }

  private boolean hasReadableConfiguredImage(String configuredPath, String basePath) {
    File file = resolveConfiguredFile(configuredPath, basePath);
    return file.canRead();
  }

  private BufferedImage createSolidFallbackImage(int width, int height, Color color) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setColor(color);
    g.fillRect(0, 0, width, height);
    g.dispose();
    return image;
  }

  private BufferedImage createStoneFallback(Color fill, Color outline) {
    int size = 200;
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(fill);
    g.fillOval(8, 8, size - 16, size - 16);
    g.setColor(outline);
    g.drawOval(8, 8, size - 17, size - 17);
    g.dispose();
    return image;
  }

  public void save() {
    try {
      File file = new File(this.path + this.configFile);
      File parent = file.getParentFile();
      if (parent != null && !parent.exists()) {
        parent.mkdirs();
      }
      if (!file.exists()) {
        file.createNewFile();
      }

      try (FileOutputStream fp = new FileOutputStream(file);
          OutputStreamWriter writer = new OutputStreamWriter(fp)) {
        Iterator<String> keys = config.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          Object value = config.get(key);
          if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
            keys.remove();
          }
        }
        writer.write(config.toString(2));
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public static JSONArray color2Array(Color c) {
    JSONArray a = new JSONArray("[]");
    if (c != null) {
      a.put(c.getRed());
      a.put(c.getGreen());
      a.put(c.getBlue());
      if (c.getAlpha() != 255) {
        a.put(c.getAlpha());
      }
    }
    return a;
  }

  public static JSONArray color2ArrayNoAlpha(Color c) {
    JSONArray a = new JSONArray("[]");
    if (c != null) {
      a.put(c.getRed());
      a.put(c.getGreen());
      a.put(c.getBlue());
    }
    return a;
  }

  private int getIntByKey(String key, int defaultValue) {
    return config.optInt(key, uiConfig.optInt(key, defaultValue));
  }

  public Color morandiSuggestionColor(int level) {
    if (!Lizzie.config.useMorandiColors) {
      return null;
    }
    return MorandiPalette.getSuggestionColor(level);
  }

  public Color morandiSuggestionAlphaColor(int level) {
    if (!Lizzie.config.useMorandiColors) {
      return null;
    }
    return MorandiPalette.getSuggestionAlphaColor(level);
  }

  public Color morandiSuggestionLightColor(int level) {
    if (!Lizzie.config.useMorandiColors) {
      return null;
    }
    return MorandiPalette.getSuggestionLightColor(level);
  }

  public Color morandiPanelBackground() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.PANEL_BACKGROUND;
  }

  public Color morandiPanelBackgroundLight() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.PANEL_BACKGROUND_LIGHT;
  }

  public Color morandiTableHeader() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.TABLE_HEADER;
  }

  public Color morandiTableRowEven() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.TABLE_ROW_EVEN;
  }

  public Color morandiTableRowOdd() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.TABLE_ROW_ODD;
  }

  public Color morandiTextPrimary() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.TEXT_PRIMARY;
  }

  public Color morandiTextSecondary() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.TEXT_SECONDARY;
  }

  public Color morandiControlsOverlay() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.CONTROLS_OVERLAY;
  }

  public Color morandiControlsBorder() {
    if (!Lizzie.config.useMorandiColors) return null;
    return MorandiPalette.CONTROLS_BORDER;
  }

  public Color glassPanelOverlayColor() {
    return getColorByKey("glass-panel-overlay-color", new Color(24, 24, 26, 102));
  }

  public Color glassPanelBorderColor() {
    return getColorByKey("glass-panel-border-color", new Color(255, 255, 255, 26));
  }

  public Color glassPanelHighlightColor() {
    return getColorByKey("glass-panel-highlight-color", new Color(255, 255, 255, 77));
  }

  public Color glassPanelShadowColor() {
    return getColorByKey("glass-panel-shadow-color", new Color(0, 0, 0, 64));
  }

  public Color glassAccentColor() {
    return getColorByKey("glass-accent-color", new Color(96, 165, 250));
  }

  public int glassBlurRadius() {
    return Math.max(0, getIntByKey("glass-blur-radius", 10));
  }

  public int liquidBlurRadius() {
    return Math.max(0, getIntByKey("liquid-blur-radius", 15));
  }

  public int glassCornerRadius() {
    return Math.max(4, getIntByKey("glass-corner-radius", 12));
  }
}
