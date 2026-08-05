package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.teacher.analysis.AnalysisBrain;
import featurecat.lizzie.teacher.analysis.TeacherPersona;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.KataGoCandidate;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.MoveClassification;
import featurecat.lizzie.teacher.analysis.AnalysisBrain.PvReport;
import featurecat.lizzie.teacher.KnowledgeMatcher;
import featurecat.lizzie.teacher.analysis.ScorePerspective;
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
import featurecat.lizzie.teacher.BoardImageExporter;
import featurecat.lizzie.teacher.analysis.EvidenceBundle;
import featurecat.lizzie.teacher.analysis.IntentClassifier;
import featurecat.lizzie.teacher.analysis.QualityGate;
import featurecat.lizzie.teacher.analysis.VisionEvidence;
import featurecat.lizzie.teacher.StructuredResultParser;
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
  private final JComboBox<String> rankModeCombo;
  private final JTextField rankNumField;
  private final JComboBox<String> densityCombo;
  private final JComboBox<String> paceCombo;
  private final JComboBox<String> variationCombo;

  private LLMClient llm;
  private TeacherSession session;
  private volatile boolean running = false;
  private MoveAnalysis currentAnalysis;

  public TeacherPanel() {
    try {
      setLayout(new BorderLayout(8, 8));
      setBorder(
          BorderFactory.createTitledBorder(
              BorderFactory.createEtchedBorder(), "AI 讲棋", TitledBorder.LEFT, TitledBorder.TOP));
      setPreferredSize(new Dimension(760, 560));

      // 顶部：学生段位/年龄 + 老师风格 + 配置
      JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
      top.add(new JLabel("学生:"));
      rankModeCombo = new JComboBox<>(new String[] {"级位", "段位"});
      top.add(rankModeCombo);
      rankNumField = new JTextField("5", 3);
      top.add(rankNumField);
      top.add(new JLabel("老师:"));
      styleCombo = new JComboBox<>(new String[] {"平衡自然", "严谨细致", "亲切耐心", "严格专业", "风趣幽默"});
      top.add(styleCombo);
      top.add(new JLabel("术语:"));
      densityCombo = new JComboBox<>(new String[] {"少", "中", "多"});
      top.add(densityCombo);
      top.add(new JLabel("节奏:"));
      paceCombo = new JComboBox<>(new String[] {"简洁", "标准", "细讲"});
      top.add(paceCombo);
      top.add(new JLabel("变化:"));
      variationCombo = new JComboBox<>(new String[] {"少讲", "适中", "详细"});
      top.add(variationCombo);
      JButton configBtn = new JButton("配置 LLM");
      configBtn.addActionListener(this::openConfig);
      top.add(configBtn);
      add(top, BorderLayout.NORTH);

      // 左侧：证据分区 + 讲解卡片 + 关键手（纵向排列，各区独立滚动）
      JPanel left = new JPanel();
      left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
      evidencePanel = new JPanel();
      evidencePanel.setLayout(new BoxLayout(evidencePanel, BoxLayout.Y_AXIS));
      JScrollPane evScroll = new JScrollPane(evidencePanel);
      evScroll.setBorder(BorderFactory.createTitledBorder("本手证据（分区）"));
      evScroll.setPreferredSize(new Dimension(360, 190));
      evScroll.setMinimumSize(new Dimension(360, 120));
      left.add(evScroll);

      artifactHolder = new JPanel(new BorderLayout());
      artifactHolder.setBorder(BorderFactory.createTitledBorder("讲解卡片"));
      artifactHolder.setPreferredSize(new Dimension(360, 230));
      artifactHolder.setMinimumSize(new Dimension(360, 160));
      left.add(artifactHolder);

      keyMoveHolder = new JPanel(new BorderLayout());
      keyMoveHolder.setBorder(BorderFactory.createTitledBorder("关键手"));
      keyMoveHolder.setPreferredSize(new Dimension(360, 150));
      keyMoveHolder.setMinimumSize(new Dimension(360, 100));
      left.add(keyMoveHolder);

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
      JButton rangeBtn = new JButton("区间复盘");
      rangeBtn.addActionListener(this::explainMoveRange);
      actions.add(rangeBtn);
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
      String rankMode = (String) rankModeCombo.getSelectedItem();
      String rankNum = rankNumField.getText().trim();
      if (rankNum.isEmpty()) rankNum = "5";
      String level = ("段位".equals(rankMode)) ? "业余" + rankNum + "段" : "业余" + rankNum + "级";
      // 年龄不设置（age=0 → persona 走 UNKNOWN 年龄分支）；术语密度/讲解节奏/变化细节按 UI 选择
      TeacherPersona.TerminologyDensity density =
          densityCombo.getSelectedIndex() == 0 ? TeacherPersona.TerminologyDensity.LOW
          : densityCombo.getSelectedIndex() == 2 ? TeacherPersona.TerminologyDensity.HIGH
          : TeacherPersona.TerminologyDensity.MEDIUM;
      TeacherPersona.ExplanationPace pace =
          paceCombo.getSelectedIndex() == 0 ? TeacherPersona.ExplanationPace.BRIEF
          : paceCombo.getSelectedIndex() == 2 ? TeacherPersona.ExplanationPace.DETAILED
          : TeacherPersona.ExplanationPace.STANDARD;
      TeacherPersona.VariationDetail variation =
          variationCombo.getSelectedIndex() == 0 ? TeacherPersona.VariationDetail.FEW
          : variationCombo.getSelectedIndex() == 2 ? TeacherPersona.VariationDetail.MANY
          : TeacherPersona.VariationDetail.MODERATE;
      session = new TeacherSession(level, 0, style, density, pace, variation);
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

  /** 当前对局总手数（遍历历史） */
  private int totalMoves() {
    try {
      var history = featurecat.lizzie.Lizzie.board.getHistory();
      int n = 0;
      var node = history.root();
      while (node != null) { n++; node = node.next().orElse(null); }
      return n;
    } catch (Exception e) { return 0; }
  }

  /** 当前节点 KataGo 候选点的坐标列表（前5） */
  private String[] bestCandidateMoves() {
    try {
      var data = featurecat.lizzie.Lizzie.board.getHistory().getEnd().getData();
      if (data.bestMoves == null) return new String[0];
      List<String> out = new ArrayList<>();
      for (var m : data.bestMoves) { if (m.coordinate != null) out.add(m.coordinate); if (out.size() >= 5) break; }
      return out.toArray(new String[0]);
    } catch (Exception e) { return new String[0]; }
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

    // 注入防编造证据包（EvidenceBundle，原防编造约束）
    EvidenceBundle.Bundle bundle = EvidenceBundle.buildWithScores(ma.classification, ma.pv, true,
        ma.beforeWinrate, ma.afterWinrate, ma.beforeScoreLead, ma.afterScoreLead,
        ma.actualMove, ma.best != null ? ma.best.visits : 0);
    session.setEvidenceBundle(bundle);

    // 完整教学证据（对齐 GoAgent buildTeachingEvidence）：含视角修正胜率/目差、定式/motif、loss/severity/confidence、pacing
    java.util.List<TeachingEvidenceBuilder.KnowledgeReference> krefs = new java.util.ArrayList<>();
    try {
      featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatchQuery mq2 = new featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatchQuery();
      mq2.boardSize = featurecat.lizzie.rules.Board.boardWidth;
      mq2.moveNumber = ma.moveNumber; mq2.totalMoves = totalMoves();
      mq2.playedMove = ma.actualMove; mq2.text = "讲解当前手";
      mq2.lossScore = ma.classification != null ? ma.classification.scoreLoss : null;
      java.util.List<featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch> kmatches = featurecat.lizzie.teacher.knowledge.MatchEngine.searchKnowledgeMatchEngine(mq2);
      for (featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch km : kmatches.subList(0, Math.min(6, kmatches.size()))) {
        TeachingEvidenceBuilder.KnowledgeReference kr = new TeachingEvidenceBuilder.KnowledgeReference();
        kr.id = km.id; kr.title = km.title; kr.confidence = km.confidence; kr.score = km.score;
        kr.matchType = km.matchType;
        kr.keyVariations = km.teachingPayload != null ? km.teachingPayload.keyVariations : new java.util.ArrayList<>();
        kr.whyMatched = String.join("；", km.reason.subList(0, Math.min(3, km.reason.size())));
        krefs.add(kr);
      }
    } catch (Exception ex) { /* 知识匹配失败不阻断 */ }
    ma.teachingEvidence = TeachingEvidenceBuilder.buildTeachingEvidence(ma, "讲解当前手", ma.knowledge, krefs, new java.util.ArrayList<>());
    session.setTeachingEvidence(ma.teachingEvidence);

    // 导出棋盘图（vision 证据）
    String boardImg = BoardImageExporter.exportCurrentBoard(760);
    VisionEvidence.verify(boardImg);

    String motifText = KnowledgeMatcher.formatForPrompt(ma.knowledge);
    String userText = TeacherSession.chipsToText(ma.chips);
    if (motifText != null && !motifText.isEmpty() && !motifText.startsWith("未识别")) {
      userText += "\n\n【棋形/定式识别】\n" + motifText;
    }
    // KataGo Trace Packet（对齐 katagoTraceTranslator）：policy/search 一致性、PV 支撑、安全措辞、禁用结论
    try {
      KatagoTraceTranslator.KataGoTracePacket trace = KatagoTraceTranslator.buildKataGoTracePacket(
          ma.pv != null ? ma.pv.candidates : new java.util.ArrayList<>(),
          ma.moveNumber,
          ma.classification != null ? ma.classification.winrateLoss : 0,
          ma.classification != null ? ma.classification.scoreLoss : 0,
          ma.actualWinrate != 0 ? actualMoveGtp(ma) : null,
          ma.classification != null && ma.classification.confidence != null ? KatagoTraceTranslator.Confidence.valueOf(ma.classification.confidence.name()) : KatagoTraceTranslator.Confidence.medium,
          "intermediate",
          ma.ownership);
      String traceText = KatagoTraceTranslator.formatKataGoTraceForPrompt(trace);
      userText += "\n\n" + traceText;
      // PV 变化路径（逐手追踪变化图的数据来源）
      if (ma.pv != null && ma.pv.candidates != null) {
        userText += "\n\n【各选点变化图（PV）】\n";
        for (int i = 0; i < Math.min(3, ma.pv.candidates.size()); i++) {
          var c = ma.pv.candidates.get(i);
          String label = i == 0 ? "一选" : i == 1 ? "二选" : "三选";
          userText += label + " " + c.move;
          if (c.winrate != null) userText += " 胜率" + String.format("%.1f%%", c.winrate * 100);
          if (c.scoreLead != null) userText += " 目差" + String.format("%.1f", c.scoreLead);
          userText += " PV: " + String.join(" ", c.pv) + "\n";
        }
      }
    } catch (Exception ex) { /* trace 失败不阻断讲解 */ }
    // 棋理知识按需加载（GoAgent data/knowledge 的 39 篇 markdown 文档）
    try {
      java.util.List<String[]> mdHits = new java.util.ArrayList<>();
      String focus = ma.teachingEvidence != null ? ma.teachingEvidence.teachingFocus : "";
      if (focus != null) {
        if (focus.contains("life") || focus.contains("tesuji")) mdHits.addAll(featurecat.lizzie.teacher.knowledge.MarkdownKnowledgeLoader.search("死活", 1));
        if (focus.contains("tesuji")) mdHits.addAll(featurecat.lizzie.teacher.knowledge.MarkdownKnowledgeLoader.search("手筋", 1));
        if (focus.contains("endgame")) mdHits.addAll(featurecat.lizzie.teacher.knowledge.MarkdownKnowledgeLoader.search("官子", 1));
        if (focus.contains("joseki") || focus.contains("opening")) mdHits.addAll(featurecat.lizzie.teacher.knowledge.MarkdownKnowledgeLoader.search("布局", 1));
      }
      if (mdHits.isEmpty()) mdHits.addAll(featurecat.lizzie.teacher.knowledge.MarkdownKnowledgeLoader.search("急所", 1));
      String mdText = featurecat.lizzie.teacher.knowledge.MarkdownKnowledgeLoader.formatForPrompt(mdHits);
      if (!mdText.isEmpty()) userText += "\n\n" + mdText;
    } catch (Exception ex) { /* 棋理知识注入失败不阻断讲解 */ }
    // 结构化输出 + 防编造指令（对齐 ClaimVerifier.buildStructuredTeachingInstruction）
    userText += "\n\n" + ClaimVerifier.buildStructuredTeachingInstruction();
    userText += "\n\n请基于以上数据，按角色设定中的要求进行讲解。\n";
    userText += "先给出整体结论，再逐手追踪变化图，然后对每个选点进行胜率目差解读、棋理分析和对比。\n";
    userText += "务必胜率和目差并列呈现，不要因微小差异制造虚假优劣感。\\n";
    userText += "所有坐标和胜率必须来自上方证据，禁用编造。若数据不足，坦诚说明。";
    session.addUser(userText);
    java.util.List<String> imgs = boardImg != null ? java.util.List.of(boardImg) : null;
    runLlm(userText, imgs);
  }

  /** 整盘复盘：遍历历史节点，逐手分类，收集关键手，做一次整盘讲解 */

  private static String actualMoveGtp(MoveAnalysis ma) {
    try {
      var data = featurecat.lizzie.Lizzie.board.getHistory().getEnd().getData();
      if (data.lastMove.isPresent()) { int[] xy = data.lastMove.get(); return featurecat.lizzie.rules.Board.convertCoordinatesToName(xy[0], xy[1]); }
    } catch (Exception e) {}
    return null;
  }

  private void explainWholeGame(ActionEvent e) {
    ensureSession();
    ensureLLM();
    if (llm == null) return;
    List<MoveClassification> all = analyzeWholeGame();
    List<TeacherKeyMoveActions.KeyMoveItem> keyMoves =
        TeacherKeyMoveActions.fromClassifications(all);
    showKeyMoves(keyMoves);
    // 拼关键手详情（severity + 简要），喂给 LLM 做整盘复盘 + 局势走向
    StringBuilder detail = new StringBuilder();
    for (TeacherKeyMoveActions.KeyMoveItem k : keyMoves) {
      int idx = k.moveNumber - 1;
      MoveClassification mc = idx >= 0 && idx < all.size() ? all.get(idx) : null;
      detail.append("- 第").append(k.moveNumber).append("手：").append(k.severity);
      if (mc != null) {
        detail.append("（").append(mc.severity).append("/").append(mc.confidence).append("）");
        if (mc.reason != null && !mc.reason.isEmpty()) detail.append(" ").append(mc.reason);
      }
      detail.append("\n");
    }
    String keyDetail = detail.length() == 0 ? "无明显问题手。" : detail.toString();
    String userText =
        "请对整盘棋做一次复盘。以下是 AI 逐手分析识别出的关键手（含判定与原因）：\n"
            + keyDetail
            + "\n请点出 3 个最关键的手，结合胜率/目差曲线说明整盘局势走向，并给出总体评价与提升建议。";
    session.addUser(userText);
    runLlm(userText);
  }


  /** 区间复盘：输入起止手数，遍历该区间逐手分类，挑关键手喂 LLM */
  private void explainMoveRange(ActionEvent e) {
    ensureSession();
    ensureLLM();
    if (llm == null) return;
    String input = JOptionPane.showInputDialog(this, "输入复盘区间（起-止，如 10-30）：", "区间复盘", JOptionPane.PLAIN_MESSAGE);
    if (input == null || input.trim().isEmpty()) return;
    int start, end;
    try {
      String[] parts = input.replace("，", ",").split("-");
      start = Integer.parseInt(parts[0].trim());
      end = Integer.parseInt(parts[1].trim());
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, "格式错误，请用 起-止（如 10-30）", "区间复盘", JOptionPane.WARNING_MESSAGE);
      return;
    }
    List<MoveClassification> range = analyzeRange(start, end);
    List<TeacherKeyMoveActions.KeyMoveItem> keyMoves = TeacherKeyMoveActions.fromClassifications(range);
    showKeyMoves(keyMoves);
    StringBuilder detail = new StringBuilder();
    for (TeacherKeyMoveActions.KeyMoveItem k : keyMoves) {
      int idx = k.moveNumber - 1;
      MoveClassification mc = idx >= 0 && idx < range.size() ? range.get(idx) : null;
      detail.append("- 第").append(k.moveNumber).append("手：").append(k.severity);
      if (mc != null) detail.append("（").append(mc.severity).append("/").append(mc.confidence).append("）");
      detail.append("\n");
    }
    String keyDetail = detail.length() == 0 ? "该区间无明显问题手。" : detail.toString();
    String userText =
        "请对第 " + start + "-" + end + " 手这一段进行区间复盘（附棋盘图）。已识别关键手：\n"
            + keyDetail
            + "\n请说明这段的关键转折、问题手与最佳应对，并给出针对性提升建议。";
    session.addUser(userText);
    String boardImg = BoardImageExporter.exportCurrentBoard(760);
    java.util.List<String> imgs = boardImg != null ? java.util.List.of(boardImg) : null;
    runLlm(userText, imgs);
  }

  /** 分析指定区间 [start,end] 的每一手（对齐 GoAgent moveRangeReview） */
  private List<MoveClassification> analyzeRange(int start, int end) {
    List<MoveClassification> out = new ArrayList<>();
    try {
      var history = Lizzie.board.getHistory();
      var node = history.root();
      int idx = 0;
      while (node != null && idx < 400) {
        var data = node.getData();
        int moveNumber = data.moveNumber;
        if (moveNumber >= start && moveNumber <= end) {
          var prevNode = node.previous().orElse(null);
          var refNode = (prevNode != null) ? prevNode : node;
          var refData = refNode.getData();
          if (refData.bestMoves != null && !refData.bestMoves.isEmpty()) {
            KataGoCandidate best = null;
            List<KataGoCandidate> top = new ArrayList<>();
            for (var m : refData.bestMoves) {
              KataGoCandidate kc = new KataGoCandidate();
              kc.move = m.coordinate; kc.visits = m.playouts; kc.winrate = m.winrate; kc.scoreLead = m.scoreMean; kc.prior = m.policy; kc.humanPrior = m.humanPrior != 0 ? m.humanPrior : null; kc.humanPolicy = m.humanPolicy != 0 ? m.humanPolicy : null;
              top.add(kc);
            }
            if (!top.isEmpty()) best = top.get(0);
            out.add(AnalysisBrain.classify(moveNumber, 1.0 - data.winrate, -data.scoreMean,
                refData.getPlayouts(), best == null ? null : best.winrate, best == null ? null : best.scoreLead,
                best == null ? null : best.visits, null, null, false));
          }
        }
        node = node.next().orElse(null);
        idx++;
      }
    } catch (Exception ex) { ex.printStackTrace(); }
    return out;
  }

  // ---- 分析 ----

  static class MoveAnalysis {
    int moveNumber;
    String gameId;
    String actualMove;
    KataGoCandidate best;
    MoveClassification classification;
    PvReport pv;
    List<TeacherEvidenceChip> chips;
    List<featurecat.lizzie.teacher.knowledge.MotifRecognizer.RecognizedTeachingMotif> knowledge;
    List<TeacherArtifactCard.Variation> variations;
    List<TeacherArtifactCard.TrainingItem> training;
    TeachingEvidenceBuilder.TeachingEvidence teachingEvidence;
    String artifactHtml;
    double actualWinrate;
    double actualScoreLead;
    double beforeWinrate;
    double afterWinrate;
    double beforeScoreLead;
    double afterScoreLead;
    double[] ownership;  // KataGo ownership 数组（来自 BoardData.estimateArray）
  }

  private MoveAnalysis analyzeCurrent() {
    try {
      var node = Lizzie.board.getHistory().getCurrentHistoryNode();
      var data = node.getData();
      MoveAnalysis ma = new MoveAnalysis();
      ma.moveNumber = data.moveNumber;

      // 实战手坐标（当前节点已落子的手）
      if (data.lastMove.isPresent()) {
        int[] xy = data.lastMove.get();
        ma.actualMove = featurecat.lizzie.rules.Board.convertCoordinatesToName(xy[0], xy[1]);
      }

      // 视角：取“上一手节点”的 bestMoves + winrate 作为“AI 对该实战手的首选/基准胜率”
      // （当前节点的 bestMoves 是给下一手对手的推荐，不能直接当本手首选）
      var prevNode = node.previous().orElse(null);
      var refNode = (prevNode != null) ? prevNode : node;
      var refData = refNode.getData();
      // 视角统一为“实战手方”（对齐 GoAgent scorePerspective）：
      // data.winrate 是落子后轮到对手的胜率 → 实战手方 = 1 - winrate
      // data.scoreMean 是“黑正”约定落子后目差 → 实战手方 = scoreLeadForColor(..., 实战手是黑?)
      boolean playedByBlack = (data.lastMoveColor == featurecat.lizzie.rules.Stone.BLACK);
      ma.actualWinrate = ScorePerspective.winrateFromAfterMove(data.winrate);
      ma.actualScoreLead = ScorePerspective.scoreLeadFromAfterMove(data.scoreMean, playedByBlack);
      ma.beforeWinrate = ScorePerspective.winrateFromAfterMove(refData.winrate);
      ma.beforeScoreLead = ScorePerspective.scoreLeadFromAfterMove(refData.scoreMean, playedByBlack);
      ma.afterWinrate = ma.actualWinrate;
      ma.afterScoreLead = ma.actualScoreLead;
      if (refData.estimateArray != null && !refData.estimateArray.isEmpty()) {
        double[] own = new double[refData.estimateArray.size()];
        for (int i = 0; i < own.length; i++) own[i] = refData.estimateArray.get(i);
        ma.ownership = own;
      }

      List<KataGoCandidate> topMoves = new ArrayList<>();
      if (refData.bestMoves != null) {
        for (var m : refData.bestMoves) {
          KataGoCandidate kc = new KataGoCandidate();
          kc.move = m.coordinate;
          kc.visits = m.playouts;
          kc.winrate = m.winrate;
          kc.scoreLead = m.scoreMean;
          kc.prior = m.policy;
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
              refData.getPlayouts(),
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
              refData.getPlayouts(),
              ma.best,
              ma.classification,
              ma.pv);
      // 知识匹配（完整 knowledge 引擎：elite 卡 + 定式 Trie + 启发式 motif）
      ma.knowledge = KnowledgeMatcher.recognizeForCurrent(
          ma.moveNumber, totalMoves(),
          bestCandidateMoves(), null, ma.actualMove,
          ma.best != null ? ma.best.move : null,
          String.valueOf(ma.classification != null ? Math.round(ma.classification.winrateLoss * 100) / 100.0 : 0),
          ma.classification != null ? ma.classification.severity.name().toLowerCase() : "",
          "");
      ma.chips.addAll(KnowledgeMatcher.toChips(ma.knowledge));
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
        var prevNode = node.previous().orElse(null);
        var refNode = (prevNode != null) ? prevNode : node;
        var refData = refNode.getData();
        // 跳过无分析数据的节点
        if (refData.bestMoves == null || refData.bestMoves.isEmpty()) {
          node = node.next().orElse(null);
          idx++;
          continue;
        }
        int moveNumber = data.moveNumber;
        double aw = refData.winrate, as = refData.scoreMean;
        KataGoCandidate best = null;
        List<KataGoCandidate> top = new ArrayList<>();
        for (var m : refData.bestMoves) {
          KataGoCandidate kc = new KataGoCandidate();
          kc.move = m.coordinate;
          kc.visits = m.playouts;
          kc.winrate = m.winrate;
          kc.scoreLead = m.scoreMean;
          top.add(kc);
        }
        if (!top.isEmpty()) best = top.get(0);
        out.add(
            AnalysisBrain.classify(
                moveNumber,
                aw,
                as,
                refData.getPlayouts(),
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
            ma.actualScoreLead,
            ma.knowledge,
            ma.variations,
            ma.training),
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
    // 自由输入：识别意图，current-move / move-range 附棋盘图
    IntentClassifier.Result intent = IntentClassifier.classify(text);
    int[] range = IntentClassifier.parseMoveRange(text);
    java.util.List<String> imgs = null;
    boolean visionMode = intent.intent == IntentClassifier.Intent.CURRENT_MOVE
        || intent.intent == IntentClassifier.Intent.MOVE_RANGE;
    if (visionMode) {
      String boardImg = BoardImageExporter.exportCurrentBoard(760);
      if (boardImg != null) imgs = java.util.List.of(boardImg);
    }
    runLlm(text, imgs);
  }

  private void runLlm(String userText) {
    runLlm(userText, null);
  }

  private void runLlm(String userText, java.util.List<String> images) {
    if (running) return;
    running = true;
    sendBtn.setEnabled(false);
    stopBtn.setEnabled(true);
    appendRaw("你: " + userText + "\n");
    // 把图片附加到最后一条 user 消息（多模态）
    if (images != null && !images.isEmpty()) {
      int n = session.messages().size();
      if (n > 0) {
        LLMClient.Message last = session.messages().get(n - 1);
        if ("user".equals(last.role)) {
          session.messages().set(n - 1, new LLMClient.Message(last.role, last.content, images));
        }
      }
    }
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
                final String f = full;
                SwingUtilities.invokeLater(() -> refreshArtifactFromLlm(f));
                // 完整防编造校验（对齐 GoAgent verifyTeacherMarkdown）：坐标/百分比/定式引用
                if (currentAnalysis != null && currentAnalysis.teachingEvidence != null) {
                  TeachingEvidenceBuilder.MarkdownVerification v = TeachingEvidenceBuilder.verifyTeacherMarkdown(f, currentAnalysis.teachingEvidence);
                  for (String w : v.warnings) appendRaw("\n> 防编造校验：" + w);
                  for (String viol : v.violations) appendRaw("\n> 防编造违规：" + viol);
                  if (v.ok) appendRaw("\n> 防编造校验：坐标与证据一致。");
                  // claim 级防编造校验（对齐 GoAgent ClaimVerifier.verifyTeacherClaimsFromMarkdown）
                  ClaimVerifier.ClaimVerificationResult cv = ClaimVerifier.verifyTeacherClaimsFromMarkdown(f, currentAnalysis.teachingEvidence);
                  appendRaw("\n> " + ClaimVerifier.buildClaimVerificationNote(cv));
                  // 生成完整教学产物 HTML（对齐 GoAgent buildTeacherArtifact）
                  TeachingArtifactBuilder.BuildInput bi = new TeachingArtifactBuilder.BuildInput();
                  bi.id = "move-" + currentAnalysis.moveNumber;
                  bi.title = "第 " + currentAnalysis.moveNumber + " 手讲解";
                  bi.intent = "current-move";
                  bi.markdown = f;
                  bi.analysis = currentAnalysis;
                  bi.structured = StructuredResultParser.parse(f, "current-move");
                  bi.knowledgeMatches = currentAnalysis.knowledge;
                  currentAnalysis.artifactHtml = TeachingArtifactBuilder.renderTeacherArtifactHtml(
                      TeachingArtifactBuilder.buildTeacherArtifact(bi));
                }
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
    mdState.setCaretPosition(mdState.getDocument().getLength());
  }

  /** 从 LLM 返回里解析 ### 正确思路 / ### 练习建议 区块，刷新讲解卡片 */
  private void refreshArtifactFromLlm(String full) {
    if (currentAnalysis == null) return;
    List<TeacherArtifactCard.Variation> variations = new ArrayList<>();
    List<TeacherArtifactCard.TrainingItem> training = new ArrayList<>();
    // 优先用结构化 JSON 解析（对齐 GoAgent structuredResultParser），失败降级到 ### 标记解析
    StructuredResultParser.StructuredTeacherResult sr = StructuredResultParser.parse(full, "current-move");
    if (sr.correctThinking != null && !sr.correctThinking.isEmpty()) {
      for (String line : sr.correctThinking) variations.add(new TeacherArtifactCard.Variation("正确思路", null, null, line));
    }
    if (sr.drills != null) {
      for (String line : sr.drills) {
        String kind = "思路";
        if (line.contains("死活")) kind = "死活";
        else if (line.contains("手筋")) kind = "手筋";
        training.add(new TeacherArtifactCard.TrainingItem(line, kind, ""));
      }
    }
    // 降级：### 标记解析（兼容非 JSON 返回）
    if (variations.isEmpty() || training.isEmpty()) {
      String[] blocks = full.split("### ");
      for (String b : blocks) {
        String head = b.split("\n", 2)[0].trim();
        if (b.toLowerCase().startsWith("正确思路") || head.equals("正确思路")) {
          String body = b.contains("\n") ? b.substring(b.indexOf("\n") + 1).trim() : "";
          for (String line : body.split("\n")) {
            line = line.replaceAll("^[-*\\d.、)\\s]+", "").trim();
            if (!line.isEmpty() && variations.isEmpty())
              variations.add(new TeacherArtifactCard.Variation("正确思路", null, null, line));
          }
        } else if (b.toLowerCase().startsWith("练习建议") || head.equals("练习建议")) {
          String body = b.contains("\n") ? b.substring(b.indexOf("\n") + 1).trim() : "";
          for (String line : body.split("\n")) {
            line = line.replaceAll("^[-*\\d.、)\\s]+", "").trim();
            if (line.isEmpty()) continue;
            String kind = "思路";
            if (line.contains("死活")) kind = "死活";
            else if (line.contains("手筋")) kind = "手筋";
            if (training.isEmpty()) training.add(new TeacherArtifactCard.TrainingItem(line, kind, ""));
          }
        }
      }
    }
    currentAnalysis.variations = variations;
    currentAnalysis.training = training;
    showArtifact(currentAnalysis);
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
