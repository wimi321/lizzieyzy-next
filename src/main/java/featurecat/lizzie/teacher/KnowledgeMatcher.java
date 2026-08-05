package featurecat.lizzie.teacher;

import featurecat.lizzie.teacher.knowledge.JosekiRecognizer;
import featurecat.lizzie.teacher.knowledge.LocalShapeGeometryMatcher;
import featurecat.lizzie.teacher.knowledge.JsonKnowledgeLoader;
import featurecat.lizzie.teacher.knowledge.MotifRecognizer;
import featurecat.lizzie.teacher.knowledge.LocalPatternMatcher;
import featurecat.lizzie.teacher.knowledge.MatchEngine;
import featurecat.lizzie.teacher.TeacherEvidenceChip;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.BoardData;
import featurecat.lizzie.rules.BoardHistoryNode;
import featurecat.lizzie.rules.Stone;
import java.util.ArrayList;
import java.util.List;

/**
 * 完整对齐 GoAgent knowledge 引擎：把当前局面交给 MotifRecognizer（综合 elite 卡 + 定式 + 启发式）
 * 识别棋形/定式/战术 motif，输出喂给 LLM 的证据文本。替代之前的启发式子集。
 */
public final class KnowledgeMatcher {

    private KnowledgeMatcher() {}
    private static LocalShapeGeometryMatcher.GeometryMatchResult m_recentShapeMatch;
    private static List<MatchEngine.KnowledgeMatch> m_recentMatches;

    static List<LocalPatternMatcher.BoardSnapshotStone> currentBoardSnapshot() {
        List<LocalPatternMatcher.BoardSnapshotStone> out = new ArrayList<>();
        try {
            var board = featurecat.lizzie.Lizzie.board;
            int size = featurecat.lizzie.rules.Board.boardWidth;
            var data = board.getHistory().getEnd().getData();
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                featurecat.lizzie.rules.Stone st = data.stones[y * size + x];
                if (st == null || st == featurecat.lizzie.rules.Stone.EMPTY) continue;
                LocalPatternMatcher.BoardSnapshotStone bs = new LocalPatternMatcher.BoardSnapshotStone();
                bs.point = featurecat.lizzie.rules.Board.convertCoordinatesToName(x, y);
                bs.color = st.isBlack() ? "B" : "W";
                out.add(bs);
            }
        } catch (Exception e) { /* ignore */ }
        return out;
    }

    /** 从当前局面构造 MotifRecognizerQuery 并识别 motif */
    public static List<MotifRecognizer.RecognizedTeachingMotif> recognizeForCurrent(int moveNumber, int totalMoves,
            String[] candidateMoves, String[] principalVariation, String playedMove, String bestMove,
            String lossScore, String judgement, String text) {
        MotifRecognizer.MotifRecognizerQuery q = new MotifRecognizer.MotifRecognizerQuery();
        q.moveNumber = moveNumber;
        q.totalMoves = totalMoves;
        q.boardSize = featurecat.lizzie.rules.Board.boardWidth;
        q.recentMoves = recentMovesFromHistory(40);
        q.playedMove = playedMove != null ? new String[]{playedMove} : null;
        q.candidateMoves = candidateMoves;
        q.principalVariation = principalVariation;
        q.lossScore = lossScore;
        q.judgement = judgement;
        q.text = text;
        q.maxResults = 8;
        if (lossScore != null) { try { q.scoreLoss = Double.parseDouble(lossScore); } catch (Exception ignore) {} }
        // 2) 局部形状几何匹配（对齐 GoAgent localShapeGeometryMatch）：死活/手筋题库
        LocalShapeGeometryMatcher.KnowledgeMatchQuery sq = new LocalShapeGeometryMatcher.KnowledgeMatchQuery();
        sq.boardSize = Board.boardWidth;
        sq.playedMove = playedMove;
        sq.candidateMoves = candidateMoves != null ? java.util.Arrays.asList(candidateMoves) : null;
        sq.principalVariation = principalVariation != null ? java.util.Arrays.asList(principalVariation) : null;
        sq.boardSnapshot = currentBoardSnapshot();
        LocalShapeGeometryMatcher.GeometryMatchResult shapeMatch = LocalShapeGeometryMatcher.matchProblems(JsonKnowledgeLoader.loadTrainingProblems(), sq);
        if (shapeMatch != null && shapeMatch.score >= 12) {
            m_recentShapeMatch = shapeMatch;
        }
        // 3) MatchEngine 完整知识匹配（定式/死活/手筋/形状卡），作为主证据入口
        MatchEngine.KnowledgeMatchQuery mq = new MatchEngine.KnowledgeMatchQuery();
        mq.boardSize = Board.boardWidth;
        mq.moveNumber = moveNumber; mq.totalMoves = totalMoves;
        mq.playedMove = playedMove; mq.judgement = judgement; mq.text = text;
        mq.lossScore = parseLoss(lossScore);
        mq.candidateMoves = candidateMoves != null ? java.util.Arrays.asList(candidateMoves) : new ArrayList<>();
        mq.principalVariation = principalVariation != null ? java.util.Arrays.asList(principalVariation) : new ArrayList<>();
        mq.boardSnapshot = currentBoardSnapshot();
        m_recentMatches = MatchEngine.searchKnowledgeMatchEngine(mq);
        return MotifRecognizer.recognizeTeachingMotifs(q);
    }

    static Double parseLoss(String v) { if (v == null) return null; try { return Double.parseDouble(v.replace("%","").trim()); } catch (Exception e) { return null; } }

    /** 把识别到的 motif 格式化为喂给 LLM 的证据文本 */
    public static String formatForPrompt(List<MotifRecognizer.RecognizedTeachingMotif> motifs) {
        StringBuilder sb = new StringBuilder(MotifRecognizer.formatRecognizedMotifsForPrompt(motifs));
        if (m_recentShapeMatch != null) {
            sb.append("\n\n【局部形状几何匹配】\n")
              .append("在死活/手筋题库中找到相似局部形状（匹配分=").append(m_recentShapeMatch.score)
              .append("，匹配率=").append(String.format("%.2f", m_recentShapeMatch.ratio))
              .append("，变换=").append(m_recentShapeMatch.transform)
              .append("，颜色模式=").append(m_recentShapeMatch.colorMode).append("）。\n")
              .append("可提示学生：此处可能存在死活/手筋考点，注意气数与形状。");
            m_recentShapeMatch = null;
        }
        if (m_recentMatches != null && !m_recentMatches.isEmpty()) {
            sb.append("\n\n【知识库匹配】\n");
            int shown = 0;
            for (MatchEngine.KnowledgeMatch m : m_recentMatches) {
                if (shown >= 5) break;
                if ("weak".equals(m.confidence) && !"shape".equals(m.matchType)) continue;
                sb.append("- [").append(m.matchType).append("/").append(m.confidence).append("] ")
                  .append(m.title).append("：").append(m.applicability).append("\n");
                shown++;
            }
            m_recentMatches = null;
        }
        return sb.toString();
    }

    /** 把识别到的 motif 转成证据 chip，显示在证据分区 */
    public static List<TeacherEvidenceChip> toChips(List<MotifRecognizer.RecognizedTeachingMotif> motifs) {
        List<TeacherEvidenceChip> chips = new ArrayList<>();
        if (motifs == null) return chips;
        int n = Math.min(6, motifs.size());
        for (int i = 0; i < n; i++) {
            MotifRecognizer.RecognizedTeachingMotif m = motifs.get(i);
            String detail = (m.recognition != null ? m.recognition : "")
                + (m.josekiFamily != null ? "（定式族：" + m.josekiFamily + "）" : "");
            chips.add(new TeacherEvidenceChip(
                "motif-" + m.id, TeacherEvidenceChip.Kind.KNOWLEDGE,
                "棋形：" + m.title, detail, null, null));
        }
        return chips;
    }

    /** 同时返回定式识别文本（单独段落，便于 prompt 区分） */
    public static String formatJosekiForPrompt(List<JosekiRecognizer.RecognizedJosekiPattern> patterns) {
        return JosekiRecognizer.formatJosekiPatternsForPrompt(patterns);
    }

    static List<JosekiRecognizer.JosekiMoveLike> recentMovesFromHistory(int limit) {
        List<JosekiRecognizer.JosekiMoveLike> out = new ArrayList<>();
        try {
            var history = featurecat.lizzie.Lizzie.board.getHistory();
            BoardHistoryNode node = history.getEnd();
            List<BoardHistoryNode> chain = new ArrayList<>();
            while (node != null && chain.size() < limit) { chain.add(node); node = node.previous().orElse(null); }
            // 从早到晚
            for (int i = chain.size() - 1; i >= 0; i--) {
                BoardHistoryNode n = chain.get(i);
                BoardData d = n.getData();
                if (d.lastMove.isPresent()) {
                    int[] xy = d.lastMove.get();
                    JosekiRecognizer.JosekiMoveLike mv = new JosekiRecognizer.JosekiMoveLike();
                    mv.col = xy[0]; mv.row = featurecat.lizzie.rules.Board.boardHeight - 1 - xy[1];
                    mv.gtp = featurecat.lizzie.rules.Board.convertCoordinatesToName(xy[0], xy[1]);
                    mv.row = xy[1];
                    out.add(mv);
                }
            }
        } catch (Exception e) { /* ignore */ }
        return out;
    }
}
