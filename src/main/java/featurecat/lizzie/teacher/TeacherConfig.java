package featurecat.lizzie.teacher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import java.awt.*;

/**
 * AI 解说的 LLM 配置：独立的 teacher.properties。
 */
public class TeacherConfig {
  public static String baseUrl = "https://api.openai.com/v1";
  public static String apiKey = "";
  public static String model = "gpt-4o-mini";
  // 解说设置
  public static String rankMode = "级位";
  public static String rankNum = "5";
  public static int styleIndex = 0;   // 平衡自然
  public static int densityIndex = 1; // 中
  public static int paceIndex = 1;    // 标准
  public static int variationIndex = 1; // 适中

  private static final String FILE = "teacher.properties";

  private static File dir() {
    return new File(System.getProperty("user.home"), ".lizzieyzy-next");
  }

  public static void load() {
    try {
      File f = new File(dir(), FILE);
      if (!f.exists()) return;
      Properties p = new Properties();
      try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
      baseUrl = p.getProperty("baseUrl", baseUrl);
      apiKey = p.getProperty("apiKey", apiKey);
      model = p.getProperty("model", model);
      rankMode = p.getProperty("rankMode", rankMode);
      rankNum = p.getProperty("rankNum", rankNum);
      styleIndex = Integer.parseInt(p.getProperty("styleIndex", "0"));
      densityIndex = Integer.parseInt(p.getProperty("densityIndex", "1"));
      paceIndex = Integer.parseInt(p.getProperty("paceIndex", "1"));
      variationIndex = Integer.parseInt(p.getProperty("variationIndex", "1"));
    } catch (Exception ignored) {}
  }

  public static void save() {
    try {
      File d = dir();
      if (!d.exists()) d.mkdirs();
      Properties p = new Properties();
      p.setProperty("baseUrl", baseUrl);
      p.setProperty("apiKey", apiKey);
      p.setProperty("model", model);
      p.setProperty("rankMode", rankMode);
      p.setProperty("rankNum", rankNum);
      p.setProperty("styleIndex", String.valueOf(styleIndex));
      p.setProperty("densityIndex", String.valueOf(densityIndex));
      p.setProperty("paceIndex", String.valueOf(paceIndex));
      p.setProperty("variationIndex", String.valueOf(variationIndex));
      try (FileOutputStream out = new FileOutputStream(new File(d, FILE))) { p.store(out, "AI Teacher config"); }
    } catch (Exception ignored) {}
  }

  public static LLMClient createClient() {
    load();
    if (apiKey == null || apiKey.isEmpty()) return null;
    return new LLMClient(baseUrl, apiKey, model);
  }

  public static void showDialog(java.awt.Component parent) {
    load();
    JTextField baseUrlF = new JTextField(baseUrl, 24);
    JTextField modelF = new JTextField(model, 18);
    JPasswordField keyF = new JPasswordField(apiKey, 24);

    // 小眼睛：setEchoChar 切换
    JButton eyeBtn = new JButton("\uD83D\uDC41");
    eyeBtn.setMargin(new Insets(2, 4, 2, 4));
    eyeBtn.setToolTipText("显示/隐藏 API Key");
    eyeBtn.setFocusable(false);
    final boolean[] showing = {false};
    keyF.setEchoChar('\u25CF'); // 统一实心圆点
    eyeBtn.addActionListener(ev -> {
      showing[0] = !showing[0];
      keyF.setEchoChar(showing[0] ? (char) 0 : '\u25CF');
    });
    JPanel keyRow = new JPanel(new BorderLayout(4, 0));
    keyRow.add(keyF, BorderLayout.CENTER);
    keyRow.add(eyeBtn, BorderLayout.EAST);

    // 刷新模型 + 测试连接
    JButton refreshBtn = new JButton("刷新模型");
    JButton testBtn = new JButton("测试连接");
    JLabel statusLabel = new JLabel(" ");
    statusLabel.setPreferredSize(new Dimension(320, 20));

    refreshBtn.addActionListener(ev -> {
      String url = baseUrlF.getText().trim();
      String key = new String(keyF.getPassword());
      if (key.isEmpty()) { statusLabel.setText("请先输入 API Key"); return; }
      refreshBtn.setEnabled(false);
      refreshBtn.setText("加载中...");
      new Thread(() -> {
        try {
          List<String> models = fetchModels(url, key);
          SwingUtilities.invokeLater(() -> {
            modelF.setText(models.isEmpty() ? model : models.get(0));
            modelF.setToolTipText("可用: " + String.join(", ", models));
            refreshBtn.setEnabled(true);
            refreshBtn.setText("刷新模型");
            statusLabel.setText(models.isEmpty() ? "\u274c 未找到模型" : "\u2705 " + models.size() + " 个模型");
            statusLabel.revalidate();
          });
        } catch (Exception ex) {
          String msg = ex.getMessage();
          if (msg == null || msg.isEmpty()) msg = ex.getClass().getSimpleName();
          final String fmsg = msg;
          SwingUtilities.invokeLater(() -> { refreshBtn.setEnabled(true); refreshBtn.setText("刷新模型"); statusLabel.setText("\u274c " + fmsg); });
        }
      }).start();
    });

    testBtn.addActionListener(ev -> {
      String url = baseUrlF.getText().trim();
      String key = new String(keyF.getPassword());
      String mdl = modelF.getText().trim();
      if (key.isEmpty()) { statusLabel.setText("请先输入 API Key"); return; }
      testBtn.setEnabled(false);
      testBtn.setText("测试中...");
      statusLabel.setText("连接中...");
      new Thread(() -> {
        try {
          String b = normalizeUrl(url);
          HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
          String body = "{\"model\":\"" + mdl + "\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
          HttpRequest req = HttpRequest.newBuilder()
              .uri(URI.create(b + "/chat/completions"))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + key)
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
          long t0 = System.currentTimeMillis();
          HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
          long ms = System.currentTimeMillis() - t0;
          String msg = resp.statusCode() == 200
              ? "\u2705 连接成功（" + mdl + "，" + ms + "ms）"
              : "\u274c HTTP " + resp.statusCode() + " " + resp.body().substring(0, Math.min(80, resp.body().length()));
          final String fmsg = msg;
          SwingUtilities.invokeLater(() -> {
            statusLabel.setText(fmsg);
            statusLabel.revalidate();
            testBtn.setEnabled(true);
            testBtn.setText("测试连接");
            JOptionPane.showMessageDialog(parent, fmsg, "测试连接", JOptionPane.INFORMATION_MESSAGE);
          });
        } catch (Exception ex) {
          String errMsg = ex.getMessage();
          if (errMsg == null || errMsg.isEmpty()) errMsg = ex.getClass().getSimpleName();
          final String fmsg = errMsg;
          SwingUtilities.invokeLater(() -> {
            statusLabel.setText("\u274c " + fmsg);
            statusLabel.revalidate();
            testBtn.setEnabled(true);
            testBtn.setText("测试连接");
            JOptionPane.showMessageDialog(parent, "\u274c " + fmsg, "测试连接", JOptionPane.ERROR_MESSAGE);
          });
        }
      }).start();
    });

    JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    btnRow.add(refreshBtn);
    btnRow.add(testBtn);
    btnRow.add(statusLabel);
    statusLabel.setPreferredSize(new Dimension(240, 20));

    JPanel p = new JPanel();
    p.setLayout(new GridLayout(0, 1, 0, 4));
    p.add(new JLabel("Base URL (OpenAI 兼容):"));
    p.add(baseUrlF);
    p.add(new JLabel("API Key:"));
    p.add(keyRow);
    p.add(new JLabel("模型:"));
    p.add(modelF);
    p.add(btnRow);

    // 用非模态 JDialog 替代 JOptionPane（JOptionPane 是模态的，会阻塞 EDT）
    java.awt.Window win = SwingUtilities.getWindowAncestor(parent);
    JDialog dlg = (win instanceof JFrame) ? new JDialog((JFrame) win, "AI 解说 LLM 配置", false)
        : (win instanceof JDialog) ? new JDialog((JDialog) win, "AI 解说 LLM 配置", false)
        : new JDialog((Frame) null, "AI 解说 LLM 配置", false);
    dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

    JButton okBtn = new JButton("保存");
    JButton cancelBtn = new JButton("取消");
    JPanel dlgBtnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    dlgBtnRow.add(okBtn);
    dlgBtnRow.add(cancelBtn);

    dlg.getContentPane().setLayout(new BorderLayout());
    dlg.getContentPane().add(p, BorderLayout.CENTER);
    dlg.getContentPane().add(dlgBtnRow, BorderLayout.SOUTH);

    final boolean[] confirmed = {false};
    okBtn.addActionListener(ev -> {
      confirmed[0] = true;
      baseUrl = baseUrlF.getText().trim();
      apiKey = new String(keyF.getPassword());
      model = modelF.getText().trim();
      save();
      dlg.dispose();
    });
    cancelBtn.addActionListener(ev -> dlg.dispose());

    dlg.pack();
    dlg.setLocationRelativeTo(parent);
    dlg.setVisible(true); // 非模态，线程可以更新 UI
  }

  private static List<String> fetchModels(String baseUrl, String apiKey) throws Exception {
    String b = normalizeUrl(baseUrl);
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(b + "/models"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + apiKey)
        .GET().build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
    List<String> models = new ArrayList<>();
    String body = resp.body();
    int idx = 0;
    while (true) {
      idx = body.indexOf("\"id\"", idx);
      if (idx < 0) break;
      int colon = body.indexOf(':', idx + 4);
      int start = body.indexOf('"', colon + 1);
      int end = body.indexOf('"', start + 1);
      if (end < 0) break;
      models.add(body.substring(start + 1, end));
      idx = end + 1;
    }
    return models;
  }

  private static String normalizeUrl(String url) {
    String b = url.trim();
    if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
    if (!b.endsWith("/v1")) b = b + "/v1";
    return b;
  }
}
