package featurecat.lizzie.teacher.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 对齐 GoAgent knowledge/katagoShapeFeatures.ts：从 KataGo 输入（实战手/首选手/PV/目差）提取形状特征信号 */
public final class KatagoShapeFeatures {

    private KatagoShapeFeatures() {}

    public static class KataGoShapeFeatureInput {
        public int boardSize = 19, moveNumber, totalMoves;
        public String playedMove, bestMove;
        public List<String> candidateMoves = new ArrayList<>();
        public List<String> principalVariation = new ArrayList<>();
        public Double lossScore;
        public String judgement;
    }
    public static class KataGoShapeFeature {
        public String id, shapeType, confidence, recognition, wrongThinking, correctThinking, drillPrompt;
        public double score;
        public List<String> evidence = new ArrayList<>(), counterEvidence = new ArrayList<>(), relatedMoves = new ArrayList<>();
    }

    static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
    static class Pt { int row, col; }
    static Pt coord(String move, int boardSize) {
        if (move == null) return null;
        var m = java.util.regex.Pattern.compile("^([A-HJ-Z])(\\d{1,2})$").matcher(move.trim().toUpperCase());
        if (!m.find()) return null;
        int col = GTP_COLUMNS.substring(0, boardSize).indexOf(m.group(1));
        int num = Integer.parseInt(m.group(2));
        if (col < 0 || num < 1 || num > boardSize) return null;
        Pt p = new Pt(); p.row = boardSize - num; p.col = col; return p;
    }
    static int distance(Pt a, Pt b) { if (a == null || b == null) return 99; return Math.max(Math.abs(a.row - b.row), Math.abs(a.col - b.col)); }
    static String phase(KataGoShapeFeatureInput in) {
        double ratio = in.totalMoves > 0 ? (double) in.moveNumber / in.totalMoves : 0;
        if (in.moveNumber <= 40 || ratio <= 0.2) return "opening";
        if (ratio <= 0.72) return "middlegame";
        return "endgame";
    }
    static List<String> uniq(List<String> values) {
        Set<String> s = new LinkedHashSet<>(values);
        List<String> out = new ArrayList<>(s);
        return out.size() > 10 ? out.subList(0, 10) : out;
    }

    public static List<KataGoShapeFeature> extract(KataGoShapeFeatureInput in) {
        String actual = in.playedMove;
        String best = in.candidateMoves != null && !in.candidateMoves.isEmpty() ? in.candidateMoves.get(0) : null;
        Pt actualPoint = coord(actual, in.boardSize);
        Pt bestPoint = coord(best, in.boardSize);
        int localDistance = distance(actualPoint, bestPoint);
        double scoreLoss = in.lossScore != null ? in.lossScore : 0;
        String gamePhase = phase(in);
        List<String> related = uniq(new ArrayList<>() {{ if (actual != null) add(actual); if (best != null) add(best); if (in.principalVariation != null) addAll(in.principalVariation.stream().limit(6).toList()); }});
        List<KataGoShapeFeature> features = new ArrayList<>();

        if (scoreLoss >= 1.5 && localDistance <= 2 && !gamePhase.equals("opening")) {
            features.add(mk("katago-local-candidate-shape-detail", "local_shape_detail", scoreLoss >= 4 ? "strong" : "medium", 18 + Math.min(8, scoreLoss * 2), related,
                "实战手和首选手在同一局部，KataGo 认为差别主要来自棋形、气数、先后手或次序。",
                "只看这里都能下，没有比较哪一手更补形、更先手或更限制对方。",
                "同一区域的两个候选先比较气数、连接、断点、眼形和对方最强应手。",
                "遮住 AI 首选，只在这个局部列两个候选，判断哪手更先手、形更完整。",
                localDistance, scoreLoss, gamePhase));
        }
        if (scoreLoss >= 1.5 && localDistance >= 6) {
            features.add(mk("katago-global-vs-local-shape-choice", "local_vs_global_shape", scoreLoss >= 4 ? "strong" : "medium", 16 + Math.min(8, scoreLoss * 1.5), related,
                "实战手和首选手相距很远，问题更像是全局方向、急所/大场或攻击收益，而不是单点棋形。",
                "把局部形状看得太重，忽略了另一侧更急的攻防或实地转换。",
                "先判断哪边如果不处理会立刻变差，再判断局部补形是否真的有先手价值。",
                "把棋盘分成四个区域，先选最急区域，再回头比较局部形状。",
                localDistance, scoreLoss, gamePhase));
        }
        if ((in.principalVariation != null && in.principalVariation.size() >= 6) && scoreLoss >= 1) {
            features.add(mk("katago-pv-supported-shape-line", "pv_supported_shape", in.principalVariation.size() >= 8 ? "medium" : "weak", 12 + Math.min(6, in.principalVariation.size()), related,
                "KataGo 给出了较长 PV，说明这个棋形判断要结合后续应手，不宜只讲第一感。",
                "只看推荐点，不摆对方最强应手，容易把手筋或补形讲错。",
                "讲棋形时至少沿 PV 摆到双方各 2-3 手，确认收益来自哪里。",
                "复盘时先摆首选 PV 前 6 手，再用一句话说出这条线的收益。",
                in.principalVariation.size(), scoreLoss, gamePhase));
        }
        return features;
    }

    static KataGoShapeFeature mk(String id, String shapeType, String confidence, double score, List<String> related,
            String recognition, String wrongThinking, String correctThinking, String drillPrompt, Object... ev) {
        KataGoShapeFeature f = new KataGoShapeFeature();
        f.id = id; f.shapeType = shapeType; f.confidence = confidence; f.score = score;
        f.relatedMoves = related; f.recognition = recognition; f.wrongThinking = wrongThinking;
        f.correctThinking = correctThinking; f.drillPrompt = drillPrompt;
        for (Object e : ev) f.evidence.add(String.valueOf(e));
        return f;
    }
}
