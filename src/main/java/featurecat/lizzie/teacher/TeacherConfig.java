package featurecat.lizzie.teacher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * AI 讲棋的 LLM 配置：独立的 teacher.properties（不改动 Lizzie 主 Config）。 对齐 GoAgent 的 settings（llmBaseUrl /
 * llmApiKey / llmModel）。
 */
public class TeacherConfig {
  public static String baseUrl = "https://api.openai.com/v1";
  public static String apiKey = "";
  public static String model = "gpt-4o-mini";

  private static final String FILE = "teacher.properties";

  /** 复用 Lizzie 的真实工作目录（与 persist/config.txt 同处），避免猜测路径 */
  private static File dir() {
    try {
      // Lizzie.config 的目录里有 persist 文件，作为根
      File d = new File(System.getProperty("user.home"), ".lizzieyzy-next");
      return d;
    } catch (Exception e) {
      return new File(".");
    }
  }

  public static void load() {
    try {
      File dir = dir();
      if (!dir.exists()) dir.mkdirs();
      File f = new File(dir, FILE);
      if (!f.exists()) return;
      Properties p = new Properties();
      try (FileInputStream in = new FileInputStream(f)) {
        p.load(in);
      }
      baseUrl = p.getProperty("baseUrl", baseUrl);
      apiKey = p.getProperty("apiKey", apiKey);
      model = p.getProperty("model", model);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static void save() {
    try {
      File dir = dir();
      if (!dir.exists()) dir.mkdirs();
      File f = new File(dir, FILE);
      Properties p = new Properties();
      p.setProperty("baseUrl", baseUrl);
      p.setProperty("apiKey", apiKey);
      p.setProperty("model", model);
      try (FileOutputStream out = new FileOutputStream(f)) {
        p.store(out, "GoAgent AI Teacher config");
      }
      System.out.println("[Teacher] saved config to " + f.getAbsolutePath());
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  public static LLMClient createClient() {
    if (apiKey == null || apiKey.isEmpty()) return null;
    return new LLMClient(baseUrl, apiKey, model);
  }

  public static void showDialog(java.awt.Component parent) {
    load();
    JTextField baseUrlF = new JTextField(baseUrl, 30);
    JTextField modelF = new JTextField(model, 30);
    JPasswordField keyF = new JPasswordField(apiKey, 30);

    JPanel p = new JPanel(new java.awt.GridLayout(0, 1));
    p.add(new JLabel("Base URL (OpenAI 兼容):"));
    p.add(baseUrlF);
    p.add(new JLabel("API Key:"));
    p.add(keyF);
    p.add(new JLabel("Model:"));
    p.add(modelF);

    int r =
        JOptionPane.showConfirmDialog(
            parent, p, "AI 讲棋 LLM 配置", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (r == JOptionPane.OK_OPTION) {
      baseUrl = baseUrlF.getText().trim();
      apiKey = new String(keyF.getPassword());
      model = modelF.getText().trim();
      save();
    }
  }
}
