package featurecat.lizzie.teacher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** 对齐 GoAgent teacher/studentProfileQuality.ts：学生画像弱点评分与置信度 */
public final class StudentProfileQuality {

    private StudentProfileQuality() {}

    public enum AnalysisConfidence { high, medium, low }

    public static class StudentProfile {
        public int gamesReviewed;
        public List<CommonMistake> commonMistakes = new ArrayList<>();
        public List<TypicalMove> typicalMoves = new ArrayList<>();
    }
    public static class CommonMistake { public String tag; public int count; }
    public static class TypicalMove { public String label; public double lossWinrate, lossScore; }

    public static class ProfileWeaknessQuality {
        public String tag; public int count; public double avgLossWinrate, avgLossScore;
        public AnalysisConfidence confidence; public List<TypicalMove> evidenceMoves = new ArrayList<>();
        public String recommendation;
    }

    static double round(double v, int d) { double f = Math.pow(10, d); return Math.round(v * f) / f; }

    static AnalysisConfidence confidenceFor(int count, double avgLossScore, int gamesReviewed) {
        if (count >= 5 && gamesReviewed >= 5 && avgLossScore >= 2) return AnalysisConfidence.high;
        if (count >= 3 || avgLossScore >= 3) return AnalysisConfidence.medium;
        return AnalysisConfidence.low;
    }
    static String recommendationFor(String tag, AnalysisConfidence c) {
        if (c == AnalysisConfidence.low) return "“" + tag + "” 目前只是观察信号，不要当成长期弱点。";
        if (tag.matches(".*(官子|先手|后手|逆收).*")) return "把每盘最后 60 手按目差损失排序，单独训练先后手判断。";
        if (tag.matches(".*(死活|眼|气|杀棋|对杀).*")) return "每天做短时死活，复盘时先数气和眼位，再看 AI 推荐。";
        if (tag.matches(".*(手筋|断点|连接|征子|枷).*")) return "复盘时把候选点按“打吃方向、连接、切断”三类重摆一遍。";
        if (tag.matches(".*(布局|大场|方向|厚薄).*")) return "开局阶段先写下全局最大压力点，再比较局部手是否值得。";
        return "保留为训练主题，但继续用更多对局确认。";
    }

    public static List<ProfileWeaknessQuality> scoreProfileWeaknesses(StudentProfile profile) {
        List<ProfileWeaknessQuality> out = new ArrayList<>();
        if (profile == null) return out;
        for (CommonMistake mistake : profile.commonMistakes) {
            List<TypicalMove> ev = new ArrayList<>();
            for (TypicalMove mv : profile.typicalMoves)
                if (mv.label != null && (mv.label.contains(mistake.tag) || mistake.tag.contains(mv.label))) { ev.add(mv); if (ev.size() >= 5) break; }
            double avgW = ev.isEmpty() ? 0 : ev.stream().mapToDouble(m -> m.lossWinrate).average().orElse(0);
            double avgS = ev.isEmpty() ? 0 : ev.stream().mapToDouble(m -> m.lossScore).average().orElse(0);
            AnalysisConfidence conf = confidenceFor(mistake.count, avgS, profile.gamesReviewed);
            ProfileWeaknessQuality q = new ProfileWeaknessQuality();
            q.tag = mistake.tag; q.count = mistake.count; q.avgLossWinrate = round(avgW, 2); q.avgLossScore = round(avgS, 2);
            q.confidence = conf; q.evidenceMoves = ev; q.recommendation = recommendationFor(mistake.tag, conf);
            out.add(q);
        }
        out.sort(Comparator.comparingInt((ProfileWeaknessQuality q) -> q.confidence == AnalysisConfidence.high ? 3 : q.confidence == AnalysisConfidence.medium ? 2 : 1)
            .thenComparingInt(q -> q.count).thenComparingDouble(q -> q.avgLossScore).reversed());
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    public static String summarizeProfileQualityForPrompt(StudentProfile profile) {
        if (profile == null) return "无学生画像，不能臆造长期弱点。";
        List<ProfileWeaknessQuality> scored = scoreProfileWeaknesses(profile);
        if (scored.isEmpty()) return "样本不足，只能基于当前局面讲解。";
        StringBuilder sb = new StringBuilder();
        for (ProfileWeaknessQuality item : scored.subList(0, Math.min(4, scored.size())))
            sb.append(item.tag).append(":").append(item.confidence).append(", ").append(item.count).append("次, 平均目差损失").append(item.avgLossScore).append("；");
        return sb.toString();
    }

    public static boolean shouldPromoteWeakness(int count, int gamesReviewed, double avgLossScore, Integer lastSeenDaysAgo) {
        if ((lastSeenDaysAgo != null && lastSeenDaysAgo > 120)) return false;
        if (gamesReviewed < 3) return false;
        return count >= 3 || avgLossScore >= 3.5;
    }
}
