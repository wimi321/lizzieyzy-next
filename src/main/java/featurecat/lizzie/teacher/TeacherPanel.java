package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.teacher.analysis.AnalysisBrain;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.KataGoCandidate;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.PvReport;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

/**
 * GoAgent 式 AI 讲棋面板（移植到 lizzieyzy Swing GUI）。 集成：证据分区(EvidencePanelModel) +
 * 单手讲解卡片(TeacherArtifactCard) + Markdown 讲解(RunCard) + 关键手导航(TeacherKeyMoveActions) + LLM 多轮对话。
 */
public class TeacherPanel extends JPanel {
  private final JTextArea chatArea;
  private final JTextField inputField;
  private final JPanel evidencePanel;
  private final JPanel artifactHolder;
  private final JPanel keyMoveHolder;
  private final JButton sendBtn;
  private final JButton stopBtn;
  private final JComboBox<String> styleCombo;

  private LLMClient llm;
  private TeacherSession session;
  private volatile boolean running = false;

  public TeacherPanel() {
    try {
      setLayout(new BorderLayout(8, 8));
      setBorder(
          BorderFactory.createTitledBorder(
              BorderFactory.createEtchedBorder(), "AI 讲棋", TitledBorder.LEFT, TitledBorder.TOP));
      setPreferredSize(new Dimension(760, 560));

      // 顶部：老师风格 + 配置
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("老师风格:"));
      styleCombo = new JComboBox<>(new String[] {"亲切耐心", "严格专业", "故事类比"});
      top.add(styleCombo);
      JButton configBtn = new JButton("配置 LLM");
      configBtn.addActionListener(this::openConfig);
      top.add(configBtn);
      add(top, BorderLayout.NORTH);

      // 左侧：证据分区 + 讲解卡片 + 关键手
      JPanel left = new JPanel(new BorderLayout(4, 4));
      evidencePanel = new JPanel();
      evidencePanel.setLayout(new BoxLayout(evidencePanel, BoxLayout.Y_AXIS));
      JScrollPane evScroll = new JScrollPane(evidencePanel);
      evScroll.setBorder(BorderFactory.createTitledBorder("本手证据（分区）"));
      evScroll.setPreferredSize(new Dimension(360, 200));
      left.add(evScroll, BorderLayout.NORTH);

      artifactHolder = new JPanel(new BorderLayout());
      artifactHolder.setBorder(BorderFactory.createTitledBorder("讲解卡片"));
      left.add(artifactHolder, BorderLayout.CENTER);

      keyMoveHolder = new JPanel(new BorderLayout());
      keyMoveHolder.setBorder(BorderFactory.createTitledBorder("关键手"));
      left.add(keyMoveHolder, BorderLayout.SOUTH);

      // 右侧：讲解(Markdown) + 输入
      JPanel right = new JPanel(new BorderLayout(4, 4));
      JEditorPane mdArea = new JEditorPane("text/html", "");
      mdArea.setEditable(false);
      JScrollPane mdScroll = new JScrollPane(mdArea);
      mdScroll.setPreferredSize(new Dimension(380, 360));
      mdScroll.setBorder(BorderFactory.createTitledBorder("老师讲解"));
      right.add(mdScroll, BorderLayout.CENTER);

      JPanel bottom = new JPanel(new BorderLayout(4, 4));
      JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
      JButton explainMove = new JButton("讲解此手");
      explainMove.addActionListener(this::explainCurrentMove);
      actions.add(explainMove);
      JButton explainGame = new JButton("整盘复盘");
      explainGame.addActionListener(this::explainWholeGame);
      actions.add(explainGame);
      bottom.add(actions, BorderLayout.NORTH);

      JPanel inputRow = new JPanel(new BorderLayout(4, 4));
      inputField = new JTextField();
      inputField.addActionListener(e -> send(e));
      inputRow.add(inputField, BorderLayout.CENTER);
      sendBtn = new JButton("发送");
      sendBtn.addActionListener(this::send);
      stopBtn = new JButton("停止");
      stopBtn.setEnabled(false);
      stopBtn.addActionListener(e -> running = false);
      inputRow.add(sendBtn, BorderLayout.EAST);
      inputRow.add(stopBtn, BorderLayout.WEST);
      bottom.add(inputRow, BorderLayout.SOUTH);
      right.add(bottom, BorderLayout.SOUTH);

      // 把 chatArea 作为内部状态（用于发送对话时追加原始文本）
      chatArea = new JTextArea();
      chatArea.setEditable(false);
      mdState = mdArea;

      JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
      split.setDividerLocation(380);
      add(split, BorderLayout.CENTER);

      ensureSession();
    } catch (Throwable t) {
      try (java.io.PrintWriter w = new java.io.PrintWriter("teacher_panel_err.log")) {
        t.printStackTrace(w);
      } catch (Exception ignore) {
      }
      throw new RuntimeException(t);
    }
  }

  private JEditorPane mdState;

  private void ensureSession() {
    if (session == null) {
      TeacherSession.TeacherStyle style =
          TeacherSession.TeacherStyle.values()[styleCombo.getSelectedIndex()];
      session = new TeacherSession("业余初段", 16, style);
    }
  }

  private void ensureLLM() {
    if (llm == null) {
      llm = TeacherConfig.createClient();
      if (llm == null) {
        JOptionPane.showMessageDialog(
            this,
            "未配置 LLM（baseUrl / apiKey / model）。请点「配置 LLM」。",
            "AI 讲棋",
            JOptionPane.WARNING_MESSAGE);
      }
    }
  }

  /** 讲解当前手：派生分析 → 证据分区 + 讲解卡片 → 请求 LLM */
  private void explainCurrentMove(ActionEvent e) {
    ensureSession();
    ensureLLM();
    if (llm == null) return;

    MoveAnalysis ma = analyzeCurrent();
    if (ma == null) {
      appendRaw("（无法获取当前局面分析）\n");
      return;
    }
    showEvidence(ma);
    showArtifact(ma);

    String userText = TeacherSession.chipsToText(ma.chips) + "\n请讲解这一手（结合胜率、目差与 AI 首选），指出是否问题手及改进。";
    session.addUser(userText);
    runLlm(userText);
  }

  /** 整盘复盘：遍历历史节点，逐手分类，收集关键手，做一次整盘讲解 */
  private void explainWholeGame(ActionEvent e) {
    ensureSession();
    ensureLLM();
    if (llm == null) return;
    List<MoveClassification> all = analyzeWholeGame();
    List<TeacherKeyMoveActions.KeyMoveItem> keyMoves =
        TeacherKeyMoveActions.fromClassifications(all);
    showKeyMoves(keyMoves);
    String keyList =
        keyMoves.isEmpty()
            ? "无明显问题手。"
            : String.join(
                "，",
                keyMoves.stream().map(k -> "第" + k.moveNumber + "手(" + k.severity + ")").toList());
    String userText = "请对整盘棋做一次复盘。已识别关键手：" + keyList + "。点出 3 个最关键的手并说明局势走向。";
    session.addUser(userText);
    runLlm(userText);
  }

  // ---- 分析 ----

  private static class MoveAnalysis {
    int moveNumber;
    String actualMove;
    KataGoCandidate best;
    MoveClassification classification;
    PvReport pv;
    List<TeacherEvidenceChip> chips;
    double actualWinrate;
    double actualScoreLead;
  }

  private MoveAnalysis analyzeCurrent() {
    try {
      var node = Lizzie.board.getHistory().getCurrentHistoryNode();
      var data = node.getData();
      MoveAnalysis ma = new MoveAnalysis();
      ma.moveNumber = data.moveNumber;
      ma.actualWinrate = data.winrate;
      ma.actualScoreLead = data.scoreMean;

      if (data.lastMove.isPresent()) {
        int[] xy = data.lastMove.get();
        int c = xy[1] * featurecat.lizzie.rules.Board.boardWidth + xy[0];
        ma.actualMove = featurecat.lizzie.rules.Board.coordsAsName(c);
      }

      List<KataGoCandidate> topMoves = new ArrayList<>();
      if (data.bestMoves != null) {
        for (var m : data.bestMoves) {
          KataGoCandidate kc = new KataGoCandidate();
          kc.move = m.coordinate;
          kc.visits = m.playouts;
          kc.winrate = m.winrate;
          kc.scoreLead = m.scoreMean;
          kc.pv = new String[0];
          topMoves.add(kc);
        }
        if (!topMoves.isEmpty()) ma.best = topMoves.get(0);
      }

      ma.classification =
          AnalysisBrain.classify(
              ma.moveNumber,
              ma.actualWinrate,
              ma.actualScoreLead,
              data.getPlayouts(),
              ma.best == null ? null : ma.best.winrate,
              ma.best == null ? null : ma.best.scoreLead,
              ma.best == null ? null : ma.best.visits,
              null,
              null,
              false);
      ma.pv = AnalysisBrain.buildPvReport(topMoves, false, null);
      ma.chips =
          EvidenceChips.fromAnalysis(
              ma.moveNumber,
              ma.actualMove,
              ma.actualWinrate,
              ma.actualScoreLead,
              data.getPlayouts(),
              ma.best,
              ma.classification,
              ma.pv);
      return ma;
    } catch (Exception ex) {
      ex.printStackTrace();
      return null;
    }
  }

  private List<MoveClassification> analyzeWholeGame() {
    List<MoveClassification> out = new ArrayList<>();
    try {
      var history = Lizzie.board.getHistory();
      var node = history.root();
      int idx = 0;
      while (node != null && idx < 400) {
        var data = node.getData();
        int moveNumber = data.moveNumber;
        double aw = data.winrate, as = data.scoreMean;
        KataGoCandidate best = null;
        List<KataGoCandidate> top = new ArrayList<>();
        if (data.bestMoves != null) {
          for (var m : data.bestMoves) {
            KataGoCandidate kc = new KataGoCandidate();
            kc.move = m.coordinate;
            kc.visits = m.playouts;
            kc.winrate = m.winrate;
            kc.scoreLead = m.scoreMean;
            top.add(kc);
          }
          if (!top.isEmpty()) best = top.get(0);
        }
        out.add(
            AnalysisBrain.classify(
                moveNumber,
                aw,
                as,
                data.getPlayouts(),
                best == null ? null : best.winrate,
                best == null ? null : best.scoreLead,
                best == null ? null : best.visits,
                null,
                null,
                false));
        node = node.next().orElse(null);
        idx++;
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    return out;
  }

  // ---- 展示 ----

  private void showEvidence(MoveAnalysis ma) {
    evidencePanel.removeAll();
    List<EvidencePanelModel.Section> sections =
        EvidencePanelModel.build(ma.chips, ma.classification, ma.pv, ma.actualMove, ma.moveNumber);
    for (EvidencePanelModel.Section s : sections) {
      JPanel sec = new JPanel(new BorderLayout(2, 2));
      sec.setBorder(BorderFactory.createTitledBorder(s.title));
      JPanel chips = new JPanel();
      chips.setLayout(new BoxLayout(chips, BoxLayout.Y_AXIS));
      for (TeacherEvidenceChip c : s.chips) {
        JLabel lab =
            new JLabel(
                "• " + c.label + (c.detail != null && !c.detail.isEmpty() ? " — " + c.detail : ""));
        lab.setForeground(new Color(0x33, 0x77, 0x77));
        chips.add(lab);
      }
      sec.add(chips, BorderLayout.CENTER);
      if (s.summary != null && !s.summary.isEmpty()) {
        JLabel sum = new JLabel(s.summary);
        sum.setForeground(Color.GRAY);
        sec.add(sum, BorderLayout.SOUTH);
      }
      evidencePanel.add(sec);
    }
    evidencePanel.revalidate();
    evidencePanel.repaint();
  }

  private void showArtifact(MoveAnalysis ma) {
    artifactHolder.removeAll();
    artifactHolder.add(
        new TeacherArtifactCard(
            ma.moveNumber,
            ma.actualMove,
            ma.best,
            ma.classification,
            ma.actualWinrate,
            ma.actualScoreLead),
        BorderLayout.CENTER);
    artifactHolder.revalidate();
    artifactHolder.repaint();
  }

  private void showKeyMoves(List<TeacherKeyMoveActions.KeyMoveItem> moves) {
    keyMoveHolder.removeAll();
    if (moves.isEmpty()) {
      keyMoveHolder.add(new JLabel("（整盘无明显问题手）"), BorderLayout.CENTER);
    } else {
      keyMoveHolder.add(
          new TeacherKeyMoveActions(
              moves,
              new TeacherKeyMoveActions.Handler() {
                public void onJumpToMove(int n) {
                  try {
                    Lizzie.board.getHistory().goToMoveNumber(n, false);
                  } catch (Exception ignored) {
                  }
                }

                public void onAnalyzeMove(int n) {
                  /* TODO: 触发该手重新分析 */
                }
              }),
          BorderLayout.CENTER);
    }
    keyMoveHolder.revalidate();
    keyMoveHolder.repaint();
  }

  // ---- 对话 / LLM ----

  private void send(ActionEvent e) {
    String text = inputField.getText().trim();
    if (text.isEmpty()) return;
    inputField.setText("");
    ensureSession();
    ensureLLM();
    if (llm == null) return;
    session.addUser(text);
    runLlm(text);
  }

  private void runLlm(String userText) {
    if (running) return;
    running = true;
    sendBtn.setEnabled(false);
    stopBtn.setEnabled(true);
    appendRaw("你: " + userText + "\n");
    final StringBuilder mdAcc = new StringBuilder();
    new Thread(
            () -> {
              try {
                String full =
                    llm.chatStream(
                        session.messages(),
                        token -> {
                          mdAcc.append(token);
                          SwingUtilities.invokeLater(() -> renderMarkdown(mdAcc.toString()));
                        });
                session.addAssistant(full);
              } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> appendRaw("\n[错误] " + ex.getMessage() + "\n"));
              } finally {
                running = false;
                SwingUtilities.invokeLater(
                    () -> {
                      sendBtn.setEnabled(true);
                      stopBtn.setEnabled(false);
                      appendRaw("\n");
                    });
              }
            })
        .start();
  }

  private void renderMarkdown(String md) {
    mdState.setText(MarkdownText.toHtml(md));
    mdState.setCaretPosition(0);
  }

  private void appendRaw(String s) {
    chatArea.append(s);
  }

  private void openConfig(ActionEvent e) {
    TeacherConfig.showDialog(this);
    llm = null;
    session = null;
    ensureSession();
  }
}
