package featurecat.lizzie.teacher;

/**
 * 多语言工具：读主程序当前语言 bundle（与主程序对齐），缺 key 时回退默认文本。
 * AI 解说（teacher 包）各显示组件共用。
 */
public final class TeacherI18n {
  private TeacherI18n() {}

  public static String t(String key, String fallback) {
    try {
      if (featurecat.lizzie.Lizzie.resourceBundle != null
          && featurecat.lizzie.Lizzie.resourceBundle.containsKey(key)) {
        return featurecat.lizzie.Lizzie.resourceBundle.getString(key);
      }
    } catch (Exception ignored) {
    }
    return fallback;
  }
}
