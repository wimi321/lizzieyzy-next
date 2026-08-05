package featurecat.lizzie.teacher.analysis;

import featurecat.lizzie.teacher.analysis.AnalysisBrain.*;
import java.util.*;

/**
 * 对齐 GoAgent 的 evidenceBundle.ts（125 行）全量：
 * buildTeachingEvidenceBundle（position/classification/pvConfidence/engineSummary/knowledgeMatches/tacticalSignals/
 * forbiddenClaims/recommendedWording）+ formatTeachingEvidenceBundleForPrompt。
 */
public final class EvidenceBundle {

    private EvidenceBundle() {}

    public static class Bundle {
        public int version = 1;
        public Position position = new Position();
        public MoveClassification classification;
        public PvReport pvConfidence;
        public EngineSummary engineSummary = new EngineSummary();
        public List<Object> knowledgeMatches = new ArrayList<>();
        public List<Object> tacticalSignals = new ArrayList<>();
        public List<String> forbiddenClaims = new ArrayList<>();
        public List<String> recommendedWording = new ArrayList<>();
    }
    public static class Position {
        public String gameId = "";
        public int moveNumber, boardSize = 19;
        public String playedMove, color;
    }
    public static class EngineSummary {
        public String bestMove, actualMove, scoreBeforeText, scoreAfterText;
        public double winrateBefore, winrateAfter, winrateLoss, scoreLoss;
        public int bestVisits, actualVisits;
    }

    static double round(Double v, int digits) {
        if (v == null || !Double.isFinite(v)) return 0;
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    static List<String> forbiddenClaims(MoveClassification classification, PvReport pvConfidence, boolean deepenRecommended) {
        List<String> forbidden = new ArrayList<>(Arrays.asList(
            "不要编造未出现在 KataGo 候选点、实战手、PV 或知识匹配中的坐标。",
            "不要把 scoreLead 的正负号自行解释成胜负；必须使用 scoreSummary 或黑棋为正的口径。"));
        if (classification.confidence != Confidence.HIGH) {
            forbidden.add("当前分析置信度不足，不要使用“必然”“唯一”“必败”“必杀”等绝对措辞。");
        }
        if (pvConfidence != null && (pvConfidence.overall == PvLevel.WEAK || pvConfidence.overall == PvLevel.UNSTABLE)) {
            forbidden.add("PV 支撑较弱，只能说参考变化，不能说成强制变化。");
        }
        if (deepenRecommended) {
            forbidden.add("analysisQuality 建议加深分析时，不要把低 visits 结论说成最终结论。");
        }
        return forbidden;
    }

    static List<String> recommendedWording(MoveClassification classification, PvReport pvConfidence) {
        List<String> wording = new ArrayList<>();
        if (classification.severity == Severity.GOOD) wording.add("这手棋整体可以接受，重点讲为什么可行，以及是否有更积极选择。");
        if (classification.severity == Severity.INACCURACY) wording.add("这手棋有小亏，适合讲判断方法，不要夸大成败着。");
        if (classification.severity == Severity.MISTAKE) wording.add("这手棋是本局值得复盘的问题手，要讲清实战思路和 AI 首选差异。");
        if (classification.severity == Severity.BLUNDER) wording.add("这手棋损失较大，要优先讲局部急所、后续变化和如何避免同类错误。");
        if (classification.severity == Severity.UNCLEAR) wording.add("证据不足，先说明需要加深分析，再给有限判断。");
        if (pvConfidence != null && pvConfidence.recommendedWording != null) wording.add(pvConfidence.recommendedWording);
        if (classification.shouldDeepen || (pvConfidence != null && pvConfidence.shouldDeepen)) wording.add("建议加深分析后再把结论用于正式复盘报告。");
        return wording;
    }

    /** 对齐 buildTeachingEvidenceBundle（真实 winrate/scoreLead/scoreSummary 文本） */
    public static Bundle build(MoveClassification classification, PvReport pvConfidence, boolean deepenRecommended) {
        Bundle b = new Bundle();
        b.classification = classification;
        b.pvConfidence = pvConfidence;
        b.engineSummary.bestMove = (pvConfidence != null && !pvConfidence.candidates.isEmpty()) ? pvConfidence.candidates.get(0).move : null;
        b.engineSummary.actualMove = (pvConfidence != null && !pvConfidence.candidates.isEmpty()) ? null : null;
        b.engineSummary.winrateLoss = round(classification.winrateLoss, 2);
        b.engineSummary.scoreLoss = round(classification.scoreLoss, 2);
        b.engineSummary.winrateBefore = 0; b.engineSummary.winrateAfter = 0;
        b.engineSummary.bestVisits = (pvConfidence != null && !pvConfidence.candidates.isEmpty()) ? pvConfidence.candidates.get(0).visits : 0;
        b.engineSummary.actualVisits = 0;
        b.forbiddenClaims = forbiddenClaims(classification, pvConfidence, deepenRecommended);
        b.recommendedWording = recommendedWording(classification, pvConfidence);
        return b;
    }

    /** 增强版：接收真实 winrate/scoreLead，填充 scoreSummary 文本（对齐 TS scoreBeforeText/scoreAfterText 用 scoreSummaryFromBlackLead().text） */
    public static Bundle buildWithScores(MoveClassification classification, PvReport pvConfidence, boolean deepenRecommended,
                                         double winrateBefore, double winrateAfter, double scoreLeadBefore, double scoreLeadAfter,
                                         String actualMove, int actualVisits) {
        Bundle b = build(classification, pvConfidence, deepenRecommended);
        b.engineSummary.winrateBefore = round(winrateBefore, 2);
        b.engineSummary.winrateAfter = round(winrateAfter, 2);
        b.engineSummary.actualMove = actualMove;
        b.engineSummary.actualVisits = actualVisits;
        b.engineSummary.scoreBeforeText = ScorePerspective.scoreSummaryFromBlackLead(scoreLeadBefore, "B").text;
        b.engineSummary.scoreAfterText = ScorePerspective.scoreSummaryFromBlackLead(scoreLeadAfter, "B").text;
        return b;
    }

    /** 兼容旧调用 */
    public static String toPrompt(Bundle bundle) { return formatForPrompt(bundle); }

    /** 对齐 formatTeachingEvidenceBundleForPrompt */
    public static String formatForPrompt(Bundle bundle) {
        if (bundle == null || bundle.classification == null) return "Teaching Evidence Bundle: 未生成。";
        List<String> lines = new ArrayList<>();
        lines.add("【Teaching Evidence Bundle v1】");
        lines.add("局面：第 " + bundle.position.moveNumber + " 手，实战 " + (bundle.engineSummary.actualMove != null ? bundle.engineSummary.actualMove : "未知") + "，AI 首选 " + (bundle.engineSummary.bestMove != null ? bundle.engineSummary.bestMove : "未知") + "。");
        lines.add("分类：" + bundle.classification.severity + "，置信度 " + bundle.classification.confidence + "，" + (bundle.classification.reason != null ? bundle.classification.reason : ""));
        lines.add("损失：胜率 " + bundle.engineSummary.winrateLoss + "% ，目差 " + bundle.engineSummary.scoreLoss + "。");
        lines.add("目差口径：before=" + bundle.engineSummary.scoreBeforeText + "；after=" + bundle.engineSummary.scoreAfterText + "。");
        if (bundle.pvConfidence != null) {
            lines.add("PV 可信度：" + bundle.pvConfidence.summary + "。" + (bundle.pvConfidence.recommendedWording != null ? bundle.pvConfidence.recommendedWording : ""));
        }
        StringBuilder km = new StringBuilder();
        for (Object m : bundle.knowledgeMatches.subList(0, Math.min(4, bundle.knowledgeMatches.size()))) {
            if (m instanceof featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch) {
                featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch kmm = (featurecat.lizzie.teacher.knowledge.MatchEngine.KnowledgeMatch) m;
                if (km.length() > 0) km.append("；");
                km.append(kmm.title).append("/").append(kmm.confidence);
            }
        }
        lines.add("知识匹配：" + (km.length() == 0 ? "无强匹配" : km.toString()));
        StringBuilder ts = new StringBuilder();
        for (Object sig : bundle.tacticalSignals.subList(0, Math.min(4, bundle.tacticalSignals.size()))) {
            if (sig instanceof featurecat.lizzie.teacher.knowledge.TacticalDetectors.TacticalSignal) {
                featurecat.lizzie.teacher.knowledge.TacticalDetectors.TacticalSignal t = (featurecat.lizzie.teacher.knowledge.TacticalDetectors.TacticalSignal) sig;
                if (ts.length() > 0) ts.append("；");
                ts.append(t.type).append("/").append(t.confidence);
            }
        }
        lines.add("战术信号：" + (ts.length() == 0 ? "无明确战术信号" : ts.toString()));
        lines.add("推荐措辞：" + String.join("；", bundle.recommendedWording));
        lines.add("禁止表达：" + String.join("；", bundle.forbiddenClaims));
        return String.join("\n", lines);
    }
}
