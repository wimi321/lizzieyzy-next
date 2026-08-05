package featurecat.lizzie.teacher.knowledge;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对齐 GoAgent knowledge/patterns.ts（329 行）：
 * loadKnowledgePatternCards + searchKnowledgePatterns + formatPatternForPrompt + patternToSearchText。
 * 基于 KnowledgePatternCard.triggers 的加权打分（level/phase/region/moveNumber/lossScore/judgement/
 * contextTags/moveFeatures/candidateFeatures/pvFeatures/textScore），是 GoAgent 知识匹配的 pattern 层。
 * 数据源：resources/knowledge/pattern-cards.json（已复制 GoAgent 原始数据）。
 */
public final class PatternSearchEngine {

    private PatternSearchEngine() {}

    public enum PatternCategory { joseki, life_death, tesuji, shape }
    public enum PatternPhase { opening, middlegame, endgame }
    public enum PatternRegion { corner, side, center }
    public enum PatternConfidence { low, medium, high }

    public static class Variation { public String name, mainLine, whenToChoose, warning; }
    public static class Teaching { public String recognition, correctIdea, memoryCue, commonMistake, drill; }
    public static class Triggers {
        public List<String> moveFeatures = new ArrayList<>(), candidateFeatures = new ArrayList<>(), pvFeatures = new ArrayList<>(), contextTags = new ArrayList<>(), judgements = new ArrayList<>();
        public Integer minMoveNumber, maxMoveNumber; public Double minLossScore;
    }
    public static class Shape { public String anchor; public List<String> canonicalMoves = new ArrayList<>(), gtpExamples = new ArrayList<>(); public String symmetry; }
    public static class KnowledgePatternCard {
        public String id, title, category, patternType;
        public List<String> phase = new ArrayList<>(), levels = new ArrayList<>(), regions = new ArrayList<>(), tags = new ArrayList<>(), aliases = new ArrayList<>(), boardSignals = new ArrayList<>();
        public Triggers triggers = new Triggers();
        public Shape shape = new Shape();
        public List<Variation> variations = new ArrayList<>();
        public Teaching teaching = new Teaching();
    }
    public static class PatternSearchContext {
        public String userLevel, phase, region, text, playedMove, judgement;
        public int boardSize = 19, moveNumber;
        public List<GameMove> recentMoves = new ArrayList<>();
        public List<String> contextTags = new ArrayList<>(), candidateMoves = new ArrayList<>(), principalVariation = new ArrayList<>();
        public Double lossScore;
    }
    public static class GameMove { public String gtp; public Integer row, col; }
    public static class PatternSearchMatch {
        public KnowledgePatternCard card; public double score; public PatternConfidence confidence; public List<String> reasons = new ArrayList<>();
    }

    private static List<KnowledgePatternCard> cachedCards;
    private static String cachedRoot = "";

    public static List<KnowledgePatternCard> loadKnowledgePatternCards() {
        if (cachedCards != null && "resources".equals(cachedRoot)) return cachedCards;
        try (InputStream in = PatternSearchEngine.class.getResourceAsStream("/knowledge/pattern-cards.json")) {
            if (in == null) { cachedRoot = "resources"; cachedCards = new ArrayList<>(); return cachedCards; }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object root = JsonKnowledgeLoader.parse(text);
            if (root instanceof List) {
                List<KnowledgePatternCard> out = new ArrayList<>();
                for (Object item : (List<?>) root) if (item instanceof Map) out.add(parseCard((Map<?, ?>) item));
                cachedRoot = "resources"; cachedCards = out; return out;
            }
        } catch (Exception e) { /* fall through */ }
        cachedRoot = "resources"; cachedCards = new ArrayList<>(); return cachedCards;
    }

    @SuppressWarnings("unchecked")
    private static KnowledgePatternCard parseCard(Map<?, ?> m) {
        KnowledgePatternCard c = new KnowledgePatternCard();
        c.id = str(m.get("id")); c.title = str(m.get("title")); c.category = str(m.get("category")); c.patternType = str(m.get("patternType"));
        c.phase = strList(m.get("phase")); c.levels = strList(m.get("levels")); c.regions = strList(m.get("regions"));
        c.tags = strList(m.get("tags")); c.aliases = strList(m.get("aliases")); c.boardSignals = strList(m.get("boardSignals"));
        Object tr = m.get("triggers");
        if (tr instanceof Map) {
            Map<?, ?> tm = (Map<?, ?>) tr;
            c.triggers.moveFeatures = strList(tm.get("moveFeatures")); c.triggers.candidateFeatures = strList(tm.get("candidateFeatures"));
            c.triggers.pvFeatures = strList(tm.get("pvFeatures")); c.triggers.contextTags = strList(tm.get("contextTags")); c.triggers.judgements = strList(tm.get("judgements"));
            c.triggers.minMoveNumber = tm.get("minMoveNumber") instanceof Number ? ((Number) tm.get("minMoveNumber")).intValue() : null;
            c.triggers.maxMoveNumber = tm.get("maxMoveNumber") instanceof Number ? ((Number) tm.get("maxMoveNumber")).intValue() : null;
            c.triggers.minLossScore = tm.get("minLossScore") instanceof Number ? ((Number) tm.get("minLossScore")).doubleValue() : null;
        }
        Object sh = m.get("shape");
        if (sh instanceof Map) {
            Map<?, ?> sm = (Map<?, ?>) sh;
            c.shape.anchor = str(sm.get("anchor")); c.shape.symmetry = str(sm.get("symmetry"));
            c.shape.canonicalMoves = strList(sm.get("canonicalMoves")); c.shape.gtpExamples = strList(sm.get("gtpExamples"));
        }
        Object vars = m.get("variations");
        if (vars instanceof List) for (Object v : (List<?>) vars) if (v instanceof Map) {
            Map<?, ?> vm = (Map<?, ?>) v; Variation var = new Variation();
            var.name = str(vm.get("name")); var.mainLine = str(vm.get("mainLine")); var.whenToChoose = str(vm.get("whenToChoose")); var.warning = str(vm.get("warning"));
            c.variations.add(var);
        }
        Object t = m.get("teaching");
        if (t instanceof Map) {
            Map<?, ?> tm = (Map<?, ?>) t;
            c.teaching.recognition = str(tm.get("recognition")); c.teaching.correctIdea = str(tm.get("correctIdea"));
            c.teaching.memoryCue = str(tm.get("memoryCue")); c.teaching.commonMistake = str(tm.get("commonMistake")); c.teaching.drill = str(tm.get("drill"));
        }
        return c;
    }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    static List<String> normalizeTokens(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) if (v != null) for (String tok : v.toLowerCase().split("[，。！？、；：,.!?;:()\\[\\]【】\\s/]+")) {
            tok = tok.trim(); if (!tok.isEmpty()) out.add(tok);
        }
        return new ArrayList<>(out);
    }
    static final String LETTERS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";
    static class Pt { int row, col; }
    static Pt gtpToPoint(String point, int boardSize) {
        if (point == null) return null;
        var m = java.util.regex.Pattern.compile("^([A-HJ-Z])(\\d{1,2})$").matcher(point.trim().toUpperCase());
        if (!m.find()) return null;
        int col = LETTERS.indexOf(m.group(1)), num = Integer.parseInt(m.group(2));
        if (col < 0 || col >= boardSize || num < 1 || num > boardSize) return null;
        Pt p = new Pt(); p.col = col; p.row = boardSize - num; return p;
    }
    static void addPointFeatures(Set<String> features, int row, int col, int boardSize) {
        int x = Math.min(col, boardSize - 1 - col), y = Math.min(row, boardSize - 1 - row);
        int minEdge = Math.min(x, y), maxEdge = Math.max(x, y);
        if (x <= 5 && y <= 5) features.add("corner");
        else if (minEdge <= 3) features.add("side");
        else features.add("center");
        if (minEdge == 0) features.add("first-line");
        if (minEdge == 1) features.add("second-line");
        if (minEdge == 2) features.add("third-line");
        if (minEdge == 3) features.add("fourth-line");
        if (x == 3 && y == 3) features.add("4-4");
        if (x == 2 && y == 2) features.add("3-3");
        if ((x == 2 && y == 3) || (x == 3 && y == 2)) features.add("3-4");
        if (minEdge <= 3 && maxEdge >= 4 && maxEdge <= 6) features.add("approach");
        if (minEdge <= 2 && maxEdge <= 5) features.add("eye-shape");
    }
    static Set<String> moveFeaturesFromGtp(List<String> points, int boardSize) {
        Set<String> features = new LinkedHashSet<>();
        if (points != null) for (String p : points) { Pt parsed = gtpToPoint(p, boardSize); if (parsed != null) addPointFeatures(features, parsed.row, parsed.col, boardSize); }
        return features;
    }
    static Set<String> moveFeaturesFromRecord(List<GameMove> moves, int boardSize) {
        Set<String> features = new LinkedHashSet<>();
        for (GameMove mv : moves) if (mv.row != null && mv.col != null) addPointFeatures(features, mv.row, mv.col, boardSize);
        if (moves.size() >= 2) {
            GameMove last = moves.get(moves.size() - 1), prev = moves.get(moves.size() - 2);
            if (last.row != null && last.col != null && prev.row != null && prev.col != null) {
                int dx = Math.abs(last.col - prev.col), dy = Math.abs(last.row - prev.row);
                if (dx + dy == 1) features.add("contact");
                if ((dx == 1 && dy == 2) || (dx == 2 && dy == 1)) features.add("knight-move");
                if ((dx == 0 && dy == 2) || (dx == 2 && dy == 0)) features.add("jump");
            }
        }
        return features;
    }
    static double overlapScore(List<String> needles, Set<String> haystack, double weight, String label, List<String> reasons) {
        double score = 0;
        for (String n : needles) if (haystack.contains(n.toLowerCase())) { score += weight; reasons.add(label + ":" + n); }
        return score;
    }
    static double textScore(KnowledgePatternCard card, PatternSearchContext ctx, List<String> reasons) {
        List<String> queryVals = new ArrayList<>(); if (ctx.text != null) queryVals.add(ctx.text); queryVals.addAll(ctx.contextTags);
        Set<String> queryTokens = new LinkedHashSet<>(normalizeTokens(queryVals));
        if (queryTokens.isEmpty()) return 0;
        List<String> cardVals = new ArrayList<>();
        cardVals.addAll(Arrays.asList(card.title, card.category, card.patternType));
        cardVals.addAll(card.tags); cardVals.addAll(card.aliases); cardVals.addAll(card.boardSignals);
        cardVals.addAll(Arrays.asList(card.teaching.recognition, card.teaching.correctIdea, card.teaching.memoryCue));
        List<String> cardTokens = normalizeTokens(cardVals);
        double score = 0;
        for (String token : queryTokens) {
            if (cardTokens.stream().anyMatch(cand -> cand.contains(token) || token.contains(cand))) { score += 4; reasons.add("text:" + token); }
        }
        return score;
    }
    static PatternConfidence confidenceFromScore(double score) { if (score >= 18) return PatternConfidence.high; if (score >= 10) return PatternConfidence.medium; return PatternConfidence.low; }

    public static List<PatternSearchMatch> searchKnowledgePatterns(List<KnowledgePatternCard> cards, PatternSearchContext ctx) {
        Set<String> contextTags = new LinkedHashSet<>(normalizeTokens(ctx.contextTags));
        Set<String> recentFeatures = moveFeaturesFromRecord(ctx.recentMoves.size() > 8 ? ctx.recentMoves.subList(ctx.recentMoves.size() - 8, ctx.recentMoves.size()) : ctx.recentMoves, ctx.boardSize);
        Set<String> playedFeatures = moveFeaturesFromGtp(ctx.playedMove != null ? Collections.singletonList(ctx.playedMove) : new ArrayList<>(), ctx.boardSize);
        Set<String> candidateFeatures = moveFeaturesFromGtp(ctx.candidateMoves, ctx.boardSize);
        Set<String> pvFeatures = moveFeaturesFromGtp(ctx.principalVariation, ctx.boardSize);
        Set<String> moveFeatures = new LinkedHashSet<>(recentFeatures); moveFeatures.addAll(playedFeatures);

        List<PatternSearchMatch> matches = new ArrayList<>();
        for (KnowledgePatternCard card : cards) {
            double score = 0; List<String> reasons = new ArrayList<>();
            if (card.levels.contains(ctx.userLevel)) { score += 2; reasons.add("level:" + ctx.userLevel); }
            if (card.phase.contains(ctx.phase)) { score += 4; reasons.add("phase:" + ctx.phase); }
            if (card.regions.contains(ctx.region)) { score += 5; reasons.add("region:" + ctx.region); }
            int minMove = card.triggers.minMoveNumber != null ? card.triggers.minMoveNumber : 0;
            int maxMove = card.triggers.maxMoveNumber != null ? card.triggers.maxMoveNumber : Integer.MAX_VALUE;
            if (ctx.moveNumber >= minMove && ctx.moveNumber <= maxMove) { if (card.triggers.minMoveNumber != null || card.triggers.maxMoveNumber != null) score += 2; }
            double minLoss = card.triggers.minLossScore != null ? card.triggers.minLossScore : Double.POSITIVE_INFINITY;
            if ((ctx.lossScore != null ? ctx.lossScore : 0) >= minLoss) { score += 3; reasons.add("loss>=" + card.triggers.minLossScore); }
            if (ctx.judgement != null && !ctx.judgement.isEmpty() && card.triggers.judgements.contains(ctx.judgement)) { score += 2; reasons.add("judgement:" + ctx.judgement); }
            for (String tag : card.triggers.contextTags) {
                String normalized = tag.toLowerCase();
                if (contextTags.contains(normalized) || contextTags.stream().anyMatch(item -> item.contains(normalized) || normalized.contains(item))) { score += 4; reasons.add("tag:" + tag); }
            }
            score += overlapScore(card.triggers.moveFeatures, moveFeatures, 5, "shape", reasons);
            score += overlapScore(card.triggers.candidateFeatures, candidateFeatures, 6, "candidate", reasons);
            score += overlapScore(card.triggers.pvFeatures, pvFeatures, 3, "pv", reasons);
            score += textScore(card, ctx, reasons);
            if ("joseki".equals(card.category) && "opening".equals(ctx.phase) && "corner".equals(ctx.region)) { score += 3; reasons.add("joseki-opening-corner"); }
            if ("life_death".equals(card.category) && (ctx.lossScore != null ? ctx.lossScore : 0) >= 2 && (ctx.region.equals("corner") || ctx.region.equals("side"))) { score += 3; reasons.add("life-death-risk"); }
            if (score >= 8) {
                PatternSearchMatch m = new PatternSearchMatch();
                m.card = card; m.score = score; m.confidence = confidenceFromScore(score);
                List<String> uniq = new ArrayList<>(new LinkedHashSet<>(reasons));
                m.reasons = uniq.size() > 8 ? uniq.subList(0, 8) : uniq;
                matches.add(m);
            }
        }
        matches.sort((a, b) -> Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) : a.card.title.compareTo(b.card.title));
        return matches;
    }

    public static String formatPatternForPrompt(PatternSearchMatch match) {
        KnowledgePatternCard card = match.card;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Variation v : card.variations.subList(0, Math.min(3, card.variations.size()))) {
            sb.append(++i).append(". ").append(v.name).append("\n");
            sb.append("   主线: ").append(v.mainLine).append("\n");
            sb.append("   选择条件: ").append(v.whenToChoose).append("\n");
            if (v.warning != null && !v.warning.isEmpty()) sb.append("   注意: ").append(v.warning).append("\n");
        }
        return String.join("\n",
            "匹配置信度: " + match.confidence,
            "匹配依据: " + String.join(", ", match.reasons),
            "棋形识别: " + card.teaching.recognition,
            "正确思路: " + card.teaching.correctIdea,
            "常见变化:\n" + sb.toString().trim(),
            "记忆法: " + card.teaching.memoryCue,
            "常见误区: " + card.teaching.commonMistake,
            "训练题: " + card.teaching.drill,
            "老师使用边界: 只有在棋形和手顺确实相近时才说“这是某定式/死活型”；匹配不完整时要说“这像这个型”，不要硬套。");
    }

    public static String patternToSearchText(KnowledgePatternCard card) {
        List<String> parts = new ArrayList<>();
        parts.addAll(Arrays.asList(card.title, card.category, card.patternType));
        parts.addAll(card.tags); parts.addAll(card.aliases); parts.addAll(card.boardSignals); parts.addAll(card.shape.canonicalMoves);
        parts.addAll(Arrays.asList(card.teaching.recognition, card.teaching.correctIdea, card.teaching.memoryCue, card.teaching.commonMistake, card.teaching.drill));
        for (Variation v : card.variations) parts.addAll(Arrays.asList(v.name, v.mainLine, v.whenToChoose, v.warning != null ? v.warning : ""));
        return String.join(" ", parts);
    }
}
