package featurecat.lizzie.teacher.analysis;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 对齐 GoAgent 的 intentClassifier.ts（195 行）全量：
 * 9 个信号（含 requiresGame / 日韩语 / rank-gap / learning-goal）、GAME_COUNT_PATTERN、
 * requestedGameCount、confidenceFrom（score/runnerUp）、classifyTeacherIntent（mode 短路 + 信号加权 + 区间解析）。
 */
public final class IntentClassifier {

    private IntentClassifier() {}

    public enum Intent { CURRENT_MOVE, GAME_REVIEW, BATCH_REVIEW, TRAINING_PLAN, OPEN_ENDED, MOVE_RANGE }
    public enum Confidence { high, medium, low }

    public static class TeacherIntentClassification {
        public Intent intent;
        public Confidence confidence;
        public String rationale;
        public List<String> matchedSignals = new ArrayList<>();
        public Integer requestedGameCount;
    }

    static final Pattern GAME_COUNT_PATTERN = Pattern.compile("(?:最近|近|last|recent|past|latest|直近|최근)\\s*(\\d{1,2})\\s*(?:盘|局|games?|対局|게임)", Pattern.CASE_INSENSITIVE);

    static class Signal {
        Intent intent; int weight; boolean requiresGame; String label; Pattern pattern;
        Signal(Intent intent, int weight, boolean requiresGame, String label, String regex) {
            this.intent = intent; this.weight = weight; this.requiresGame = requiresGame;
            this.label = label; this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
    }

    static final List<Signal> SIGNALS = new ArrayList<>();
    static {
        SIGNALS.add(new Signal(Intent.CURRENT_MOVE, 6, true, "explicit-current-move", "当前手|这手|这一手|本手|刚才这手|第\\s*\\d+\\s*手|why\\s+(?:this\\s+)?move|this\\s+move|current\\s+(?:move|position)|この手|現在の手|이번\\s*수|현재\\s*수"));
        SIGNALS.add(new Signal(Intent.CURRENT_MOVE, 5, true, "local-why", "为什么.*(?:这里|这手|这一手|下这里|不下)|为何.*(?:这里|这手)|怎么.*(?:应对|走|下)|is\\s+this\\s+(?:good|bad)|bad\\s+move|好手|恶手|错手|疑问手"));
        SIGNALS.add(new Signal(Intent.CURRENT_MOVE, 3, true, "coordinate-mentioned", "\\b[A-HJ-T](?:1?\\d|2[0-5])\\b|坐标|coordinate|point"));
        SIGNALS.add(new Signal(Intent.GAME_REVIEW, 6, true, "whole-game-review", "整盘|全盘|整局|本局|这盘|全局|复盘|review\\s+(?:this\\s+)?game|whole\\s+game|full\\s+review|この対局|一局全体|이번\\s*대국"));
        SIGNALS.add(new Signal(Intent.GAME_REVIEW, 4, true, "turning-point", "哪里.*(?:崩|输|亏|转折)|为什么.*(?:输了|崩了|被逆转)|胜负手|转折点|turning\\s+point|where\\s+did\\s+I\\s+lose|collapse"));
        SIGNALS.add(new Signal(Intent.BATCH_REVIEW, 7, false, "multi-game-profile", "最近|近\\s*\\d+\\s*盘|多盘|批量|常犯|画像|弱点|习惯|趋势|情况|\\d+\\s*盘|十盘|last\\s+\\d+\\s+games|recent\\s+games|my\\s+(?:weakness|weaknesses|habits)|profile|trend|直近|複数|最近\\s*\\d+\\s*局|최근|여러\\s*판"));
        SIGNALS.add(new Signal(Intent.BATCH_REVIEW, 5, false, "rank-gap", "和.*(?:差距|区别)|离.*(?:段|级)|提升到|升段|compare|gap|rank\\s+up|level\\s+up"));
        SIGNALS.add(new Signal(Intent.TRAINING_PLAN, 7, false, "training-plan", "训练|计划|一周|每日|每天|练习|题目|作业|怎么练|训练计划|drill|practice|training\\s+plan|homework|exercise|一週間|練習|훈련|연습|계획"));
        SIGNALS.add(new Signal(Intent.TRAINING_PLAN, 4, false, "learning-goal", "提高|提升|进步|涨棋|变强|improve|study|learn|強くな|실력"));
    }

    static Integer requestedGameCount(String prompt) {
        if (prompt == null) return null;
        var m = GAME_COUNT_PATTERN.matcher(prompt);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Exception ignore) {} }
        if (Pattern.compile("十盘|10\\s*盘|ten\\s+games|最近十局|최근\\s*10\\s*판", Pattern.CASE_INSENSITIVE).matcher(prompt).find()) return 10;
        return null;
    }

    static Confidence confidenceFrom(int score, int runnerUp) {
        if (score >= 7 && score - runnerUp >= 3) return Confidence.high;
        if (score >= 4) return Confidence.medium;
        return Confidence.low;
    }

    /** 从提示里解析区间 起-止（如 "10-30"），对齐 @shared/moveRange.parseMoveRangeFromPrompt */
    public static int[] parseMoveRangeFromPrompt(String prompt) {
        if (prompt == null) return null;
        var m = Pattern.compile("(\\d+)\\s*[-~到至]\\s*(\\d+)\\s*手").matcher(prompt);
        if (!m.find()) m = Pattern.compile("(\\d+)\\s*[-~到至]\\s*(\\d+)").matcher(prompt);
        if (m.find()) {
            try { return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) }; } catch (Exception ignore) {}
        }
        return null;
    }

    public static TeacherIntentClassification classifyTeacherIntent(String mode, boolean hasGameId, Integer moveRangeStart, String prompt) {
        TeacherIntentClassification r = new TeacherIntentClassification();
        String p = prompt == null ? "" : prompt.trim();
        if ("current-move".equals(mode)) {
            r.intent = Intent.CURRENT_MOVE; r.confidence = Confidence.high;
            r.rationale = "front-end requested current-move mode"; r.matchedSignals.add("mode=current-move");
            return r;
        }
        if ("move-range".equals(mode)) {
            r.intent = Intent.MOVE_RANGE; r.confidence = Confidence.high;
            r.rationale = "front-end requested move-range mode"; r.matchedSignals.add("mode=move-range");
            return r;
        }
        if (hasGameId && (moveRangeStart != null || parseMoveRangeFromPrompt(p) != null)) {
            r.intent = Intent.MOVE_RANGE; r.confidence = Confidence.high;
            r.rationale = "game selected with move-range reference"; r.matchedSignals.add("move-range-in-prompt");
            return r;
        }
        if (p.isEmpty()) {
            r.intent = Intent.OPEN_ENDED; r.confidence = Confidence.low;
            r.rationale = "empty prompt";
            return r;
        }
        int[] scores = new int[Intent.values().length];
        @SuppressWarnings("unchecked")
        java.util.List<String>[] labels = new java.util.ArrayList[Intent.values().length];
        for (int i = 0; i < labels.length; i++) labels[i] = new java.util.ArrayList<>();
        boolean hasGame = hasGameId;
        for (Signal s : SIGNALS) {
            if (s.requiresGame && !hasGame) continue;
            if (s.pattern.matcher(p).find()) { scores[s.intent.ordinal()] += s.weight; labels[s.intent.ordinal()].add(s.label); }
        }
        // selected-game-general-review：选了棋局 + 泛化复盘措辞 → game-review +2
        if (hasGame && Pattern.compile("帮我看|看看|分析一下|讲一下|help\s+me\s+(?:review|analyze)|analyse|analyze|review", Pattern.CASE_INSENSITIVE).matcher(p).find()) {
            scores[Intent.GAME_REVIEW.ordinal()] += 2;
            labels[Intent.GAME_REVIEW.ordinal()].add("selected-game-general-review");
        }
        // training-overrides-profile-summary：训练≥7 且 batch≥5 → training +2
        if (scores[Intent.TRAINING_PLAN.ordinal()] >= 7 && scores[Intent.BATCH_REVIEW.ordinal()] >= 5) {
            scores[Intent.TRAINING_PLAN.ordinal()] += 2;
            labels[Intent.TRAINING_PLAN.ordinal()].add("training-overrides-profile-summary");
        }
        Intent best = Intent.OPEN_ENDED; int max = 0, runnerUp = 0;
        for (Intent it : Intent.values()) {
            if (scores[it.ordinal()] > max) { runnerUp = max; max = scores[it.ordinal()]; best = it; }
            else if (scores[it.ordinal()] > runnerUp) runnerUp = scores[it.ordinal()];
        }
        r.requestedGameCount = requestedGameCount(p);
        if (max <= 0) {
            r.intent = Intent.OPEN_ENDED; r.confidence = Confidence.low;
            r.rationale = "no strong signal";
            return r;
        }
        r.intent = best; r.confidence = confidenceFrom(max, runnerUp);
        r.matchedSignals = labels[best.ordinal()];
        r.rationale = "matched " + (r.matchedSignals.isEmpty() ? "implicit" : String.join(", ", r.matchedSignals)) + "; score=" + max + "; runnerUp=" + runnerUp;
        return r;
    }

    /** 兼容旧调用 */
    public static class Result {
        public Intent intent;
        public String rationale;
        public List<String> matched = new ArrayList<>();
    }
    public static Result classify(String prompt) {
        TeacherIntentClassification c = classifyTeacherIntent(null, false, null, prompt);
        Result r = new Result(); r.intent = c.intent; r.rationale = c.rationale; r.matched = c.matchedSignals;
        return r;
    }
    public static int[] parseMoveRange(String prompt) { return parseMoveRangeFromPrompt(prompt); }
}
