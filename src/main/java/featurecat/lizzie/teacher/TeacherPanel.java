package featurecat.lizzie.teacher;

import featurecat.lizzie.Lizzie;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

/**
 * GoAgent 式 AI 讲棋面板（移植到 lizzieyzy Swing GUI）。 对齐 GoAgent TeacherComposerPro：对话区 + 输入框 + 发送/停止 + 证据链
 * chips + 老师快捷动作。
 */
public class TeacherPanel extends JPanel {
  private final JTextArea chatArea;
  private final JTextField inputField;
  private final JPanel chipsPanel;
  private final JButton sendBtn;
  private final JButton stopBtn;
  private final JComboBox<String> styleCombo;

  private LLMClient llm;
  private TeacherSession session;
  private volatile boolean running = false;

  public TeacherPanel() {
    setLayout(new BorderLayout(8, 8));
    setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), "AI 讲棋", TitledBorder.LEFT, TitledBorder.TOP));

    // 顶部：老师风格选择
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
    top.add(new JLabel("老师风格:"));
    styleCombo = new JComboBox<>(new String[] {"亲切耐心", "严格专业", "故事类比"});
    top.add(styleCombo);
    JButton configBtn = new JButton("配置 LLM");
    configBtn.addActionListener(this::openConfig);
    top.add(configBtn);
    add(top, BorderLayout.NORTH);

    // 中部：对话区 + 证据链
    JPanel center = new JPanel(new BorderLayout(4, 4));
    chatArea = new JTextArea();
    chatArea.setEditable(false);
    chatArea.setLineWrap(true);
    chatArea.setWrapStyleWord(true);
    chatArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    JScrollPane chatScroll = new JScrollPane(chatArea);
    chatScroll.setPreferredSize(new Dimension(360, 320));
    center.add(chatScroll, BorderLayout.CENTER);

    chipsPanel = new JPanel();
    chipsPanel.setLayout(new BoxLayout(chipsPanel, BoxLayout.Y_AXIS));
    JScrollPane chipsScroll = new JScrollPane(chipsPanel);
    chipsScroll.setPreferredSize(new Dimension(360, 90));
    chipsScroll.setBorder(BorderFactory.createTitledBorder("本手证据链"));
    center.add(chipsScroll, BorderLayout.SOUTH);
    add(center, BorderLayout.CENTER);

    // 底部：输入区 + 快捷动作
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
    add(bottom, BorderLayout.SOUTH);

    ensureSession();
  }

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

  /** 讲解当前手：从棋盘当前节点分析生成 evidenceChips 并请求 LLM */
  private void explainCurrentMove(ActionEvent e) {
    ensureSession();
    ensureLLM();
    if (llm == null) return;

    List<TeacherEvidenceChip> chips = currentMoveChips();
    showChips(chips);
    String userText = TeacherSession.chipsToText(chips) + "\n请讲解这一手（结合胜率、目差与 AI 首选），指出是否问题手及改进。";
    session.addUser(userText);
    runLlm(userText);
  }

  /** 从当前历史节点的 BoardData 派生证据链 chips（对齐 GoAgent evidenceChipsFromAnalysis） */
  private List<TeacherEvidenceChip> currentMoveChips() {
    try {
      var node = Lizzie.board.getHistory().getCurrentHistoryNode();
      var data = node.getData();
      int moveNumber = data.moveNumber;
      String actualMove =
          node.getData().lastMove == null ? null : node.getData().lastMove.toString();
      String bestMoves = data.bestMovesToString();
      String bestMove = EvidenceChips.parseFirstBestMove(bestMoves);
      double bestWinrate = bestMove != null ? data.winrate : data.winrate;
      double bestScoreLead = data.scoreMean;
      return EvidenceChips.fromAnalysis(
          data, actualMove, bestMove, bestWinrate, bestScoreLead, moveNumber);
    } catch (Exception ex) {
      return java.util.Collections.emptyList();
    }
  }

  private void explainWholeGame(ActionEvent e) {
    ensureSession();
    ensureLLM();
    if (llm == null) return;
    String userText = "请对整盘棋做一次复盘，点出 3 个最关键的手，并说明局势走向。";
    session.addUser(userText);
    runLlm(userText);
  }

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
    appendChat("你: " + userText + "\n");
    appendChat("老师: ");
    new Thread(
            () -> {
              try {
                StringBuilder acc = new StringBuilder();
                String full =
                    llm.chatStream(
                        session.messages(),
                        token -> {
                          acc.append(token);
                          SwingUtilities.invokeLater(() -> appendChat(token));
                        });
                session.addAssistant(full);
              } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> appendChat("\n[错误] " + ex.getMessage() + "\n"));
              } finally {
                running = false;
                SwingUtilities.invokeLater(
                    () -> {
                      sendBtn.setEnabled(true);
                      stopBtn.setEnabled(false);
                      appendChat("\n");
                    });
              }
            })
        .start();
  }

  private void showChips(List<TeacherEvidenceChip> chips) {
    chipsPanel.removeAll();
    for (TeacherEvidenceChip c : chips) {
      JLabel lab = new JLabel("• " + c.toString());
      lab.setForeground(new Color(0x33, 0x99, 0x99));
      chipsPanel.add(lab);
    }
    chipsPanel.revalidate();
    chipsPanel.repaint();
  }

  private void appendChat(String s) {
    chatArea.append(s);
    chatArea.setCaretPosition(chatArea.getDocument().getLength());
  }

  private void openConfig(ActionEvent e) {
    TeacherConfig.showDialog(this);
    llm = null; // 强制下次重建
    session = null;
    ensureSession();
  }
}
