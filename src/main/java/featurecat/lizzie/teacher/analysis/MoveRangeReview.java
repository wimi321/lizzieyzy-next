package featurecat.lizzie.teacher.analysis;

import java.util.*;

/**
 * 对齐 GoAgent 的 moveRangeReview.ts（80 行）全量：
 * summarizeMoveRangeAnalyses（区间内按手数排序、按损失排序挑关键手、start/end 必含、evidenceRefs 证据引用）
 * + formatMoveRangeSummaryForPrompt + selectMoveNumbersForRangeRefine（selectKeyMoveNumbers）。
 */
public final class MoveRangeReview {

    private MoveRangeReview() {}

    public static final int MOVE_RANGE_KEY_MOVE_LIMIT = 6;

    public static class ParsedMoveRange { public int start, end; public ParsedMoveRange(int s, int e) { start = s; end = e; } }

    public static class MoveAnalysisLike {
        public int moveNumber;
        public String playedMove, bestMove, judgement;
        public Double winrateLoss, scoreLoss;
        public String analysisQualityConfidence;
        public String tacticalSignalType;
    }
    public static class KeyMove {
        public int moveNumber;
        public String playedMove, bestMove, judgement;
        public double winrateLoss, scoreLoss;
        public List<String> evidenceRefs = new ArrayList<>();
    }
    public static class MoveRangeReviewSummary {
        public int start, end, totalMoves, omittedMoves;
        public String analysisMethod = "range-cache-or-quick-sweep, then key-move-focused teacher review";
        public List<KeyMove> keyMoves = new ArrayList<>();
    }

    static double round(Double v, int digits) {
        if (v == null || !Double.isFinite(v)) return 0;
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    public static MoveRangeReviewSummary summarizeMoveRangeAnalyses(List<MoveAnalysisLike> analyses, ParsedMoveRange range, int maxKeyMoves) {
        MoveRangeReviewSummary s = new MoveRangeReviewSummary();
        s.start = range.start; s.end = range.end;
        List<MoveAnalysisLike> sorted = new ArrayList<>();
        for (MoveAnalysisLike a : analyses) if (a.moveNumber >= range.start && a.moveNumber <= range.end) sorted.add(a);
        sorted.sort(Comparator.comparingInt(a -> a.moveNumber));
        List<MoveAnalysisLike> byLoss = new ArrayList<>();
        for (MoveAnalysisLike a : sorted) if (a.playedMove != null) byLoss.add(a);
        byLoss.sort((l, r) -> {
            double d = (r.winrateLoss != null ? r.winrateLoss : 0) - (l.winrateLoss != null ? l.winrateLoss : 0);
            if (d != 0) return d > 0 ? 1 : -1;
            double d2 = (r.scoreLoss != null ? r.scoreLoss : 0) - (l.scoreLoss != null ? l.scoreLoss : 0);
            if (d2 != 0) return d2 > 0 ? 1 : -1;
            return Integer.compare(l.moveNumber, r.moveNumber);
        });
        Set<Integer> keyNumbers = new LinkedHashSet<>(Arrays.asList(range.start, range.end));
        int quota = Math.max(0, maxKeyMoves - 2);
        for (MoveAnalysisLike a : byLoss) { if (keyNumbers.size() >= maxKeyMoves) break; keyNumbers.add(a.moveNumber); }
        List<Integer> sortedKeyNumbers = new ArrayList<>(keyNumbers);
        sortedKeyNumbers.sort(Comparator.naturalOrder());
        for (int num : sortedKeyNumbers) {
            MoveAnalysisLike found = null;
            for (MoveAnalysisLike a : sorted) if (a.moveNumber == num) { found = a; break; }
            if (found == null) continue;
            KeyMove km = new KeyMove();
            km.moveNumber = found.moveNumber;
            km.playedMove = found.playedMove; km.bestMove = found.bestMove;
            km.winrateLoss = round(found.winrateLoss, 2); km.scoreLoss = round(found.scoreLoss, 2);
            km.judgement = found.judgement;
            km.evidenceRefs.add("katago:move:" + found.moveNumber);
            if (found.analysisQualityConfidence != null) km.evidenceRefs.add("analysisQuality:" + found.analysisQualityConfidence);
            if (found.tacticalSignalType != null) km.evidenceRefs.add("tactical:" + found.tacticalSignalType);
            s.keyMoves.add(km);
        }
        s.totalMoves = sorted.size() > 0 ? sorted.size() : (range.end - range.start + 1);
        s.omittedMoves = Math.max(0, (range.end - range.start + 1) - s.keyMoves.size());
        return s;
    }

    public static String formatMoveRangeSummaryForPrompt(MoveRangeReviewSummary summary) {
        if (summary == null) return "未提供区间摘要；如需区间复盘，请先调用区间工具或要求用户选择区间。";
        List<String> lines = new ArrayList<>();
        lines.add("区间：第 " + summary.start + "-" + summary.end + " 手，共 " + summary.totalMoves + " 手。");
        lines.add("分析方法：" + summary.analysisMethod);
        lines.add("未逐手展开的手数：" + summary.omittedMoves);
        lines.add("关键手：");
        for (KeyMove move : summary.keyMoves) {
            List<String> parts = new ArrayList<>();
            parts.add("- 第 " + move.moveNumber + " 手");
            if (move.playedMove != null) parts.add("实战 " + move.playedMove);
            if (move.bestMove != null) parts.add("首选 " + move.bestMove);
            parts.add("胜率损失 " + round(move.winrateLoss, 1) + "%");
            parts.add("目差损失 " + round(move.scoreLoss, 1));
            if (move.judgement != null && !move.judgement.isEmpty()) parts.add("判断 " + move.judgement);
            if (!move.evidenceRefs.isEmpty()) parts.add("证据 " + String.join(", ", move.evidenceRefs));
            lines.add(String.join("，", parts));
        }
        return String.join("\n", lines);
    }

    /** 对齐 selectKeyMoveNumbers（@shared/moveRange） */
    public static List<Integer> selectKeyMoveNumbers(MoveRangeReviewSummary summary, ParsedMoveRange range, int maxCount) {
        Set<Integer> out = new LinkedHashSet<>();
        if (summary != null) {
            for (KeyMove km : summary.keyMoves) { out.add(km.moveNumber); if (out.size() >= maxCount) break; }
        }
        if (range != null) { out.add(range.start); out.add(range.end); }
        List<Integer> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.naturalOrder());
        return sorted.size() > maxCount ? sorted.subList(0, maxCount) : sorted;
    }
}
