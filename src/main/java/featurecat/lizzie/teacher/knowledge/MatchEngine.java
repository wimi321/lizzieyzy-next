package featurecat.lizzie.teacher.knowledge;


import java.util.*;

/**
 * 对齐 GoAgent knowledge/matchEngine.ts（1018 行）：知识匹配主引擎。
 * 含 buildFeatures（意图/阶段/区域/特征提取）、josekiMatch（定式匹配）、problemMatch（死活/手筋匹配）、
 * localShapeGeometryMatch（复用 LocalShapeGeometryMatcher）、searchKnowledgeMatchEngine（聚合）、
 * recommendedProblemsFromMatches、formatKnowledgeMatchForPrompt。
 * 数据来自 JsonKnowledgeLoader（training-catalog.json + pattern-cards）。
 */
public final class MatchEngine {

    private MatchEngine() {}

    public enum KnowledgeMatchType { joseki, life_death, tesuji, shape }
    public enum KnowledgeMatchConfidence { exact, strong, partial, weak }

    public static class KnowledgeMatchQuery {
        public String dataRoot, userLevel, studentLevel, text, playedMove, judgement, boardSnapshotStr;
        public int boardSize = 19, moveNumber, totalMoves, maxResults = 8;
        public List<String> candidateMoves = new ArrayList<>(), principalVariation = new ArrayList<>(), contextTags = new ArrayList<>();
        public List<GameMove> recentMoves = new ArrayList<>();
        public List<LocalPatternMatcher.BoardSnapshotStone> boardSnapshot = new ArrayList<>();
        public List<LocalPatternMatcher.LocalWindow> localWindows = new ArrayList<>();
        public Double lossScore;
    }
    public static class GameMove { public String gtp; public Integer row, col; }
    public static class KnowledgeMatch {
        public String id, matchType, title, confidence, applicability;
        public double score;
        public List<String> reason = new ArrayList<>();
        public TeachingPayload teachingPayload = new TeachingPayload();
        public List<RecommendedProblem> relatedProblems = new ArrayList<>();
    }
    public static class TeachingPayload {
        public String summary, recognition, correctIdea, memoryCue;
        public List<String> keyVariations = new ArrayList<>(), commonMistakes = new ArrayList<>(), drills = new ArrayList<>();
        public String boundary, sourceKind;
    }
    public static class RecommendedProblem {
        public String id, title, problemType, difficulty, objective, firstHint, answerSummary;
        public List<String> tags = new ArrayList<>();
    }

    public enum PatternPhase { opening, middlegame, endgame }
    public enum TrainingRegion { corner, side, center }
    static final Set<String> BROAD_TRAINING_TAGS = new HashSet<>(Arrays.asList(
        "角部", "边上", "中腹", "中心", "定式", "布局", "方向", "大场", "变化", "价值判断",
        "局部轻重", "先手价值", "实地", "外势", "问题手", "急所", "死活", "手筋", "形", "形状",
        "先手", "后手", "ai定式"));
    static final Map<KnowledgeMatchConfidence, Integer> CONFIDENCE_RANK = new HashMap<>();
    static { CONFIDENCE_RANK.put(KnowledgeMatchConfidence.exact, 4); CONFIDENCE_RANK.put(KnowledgeMatchConfidence.strong, 3); CONFIDENCE_RANK.put(KnowledgeMatchConfidence.partial, 2); CONFIDENCE_RANK.put(KnowledgeMatchConfidence.weak, 1); }

    static final String TOKEN_SPLIT = "[，。！？、；：,.!?;:()\\[\\]【】\\s/_-]+";
    static PatternPhase phaseFromMove(int moveNumber, int totalMoves) {
        double ratio = totalMoves > 0 ? (double) moveNumber / totalMoves : 0;
        if (moveNumber <= 40 || ratio <= 0.2) return PatternPhase.opening;
        if (ratio <= 0.72) return PatternPhase.middlegame;
        return PatternPhase.endgame;
    }
    static List<String> normalizeTokens(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) if (v != null) for (String tok : v.toLowerCase().split(TOKEN_SPLIT)) { tok = tok.trim(); if (!tok.isEmpty()) out.add(tok); }
        return new ArrayList<>(out);
    }
    static final String GTP = "ABCDEFGHJKLMNOPQRST";
    static class RC { int row, col; RC(int r, int c){row=r;col=c;} }
    static RC gtpToPoint(String point, int boardSize) {
        if (point == null) return null;
        var m = java.util.regex.Pattern.compile("^([A-HJ-T])(\\d{1,2})$").matcher(point.trim().toUpperCase());
        if (!m.find()) return null;
        int col = GTP.indexOf(m.group(1)), num = Integer.parseInt(m.group(2));
        if (col < 0 || num < 1 || num > boardSize) return null;
        return new RC(boardSize - num, col);
    }
    static String boardKey(int row, int col) { return row + "," + col; }
    static void addPointFeatures(Set<String> features, int row, int col, int boardSize) {
        int x = Math.min(col, boardSize - 1 - col);
        int y = Math.min(row, boardSize - 1 - row);
        int minEdge = Math.min(x, y);
        int maxEdge = Math.max(x, y);
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
    static void addFeaturesFromGtp(List<String> points, int boardSize, Set<String> out) {
        if (points == null) return;
        for (String p : points) { RC c = gtpToPoint(p, boardSize); if (c != null) addPointFeatures(out, c.row, c.col, boardSize); }
    }
    static Set<String> addFeaturesFromMoves(List<GameMove> moves, int boardSize) {
        Set<String> features = new LinkedHashSet<>();
        if (moves != null) {
            for (GameMove mv : moves) {
                if (mv.row != null && mv.col != null) addPointFeatures(features, mv.row, mv.col, boardSize);
                else if (mv.gtp != null) { Set<String> tmp = new LinkedHashSet<>(); addFeaturesFromGtp(Collections.singletonList(mv.gtp), boardSize, tmp); features.addAll(tmp); }
            }
            if (moves.size() >= 2) {
                GameMove last = moves.get(moves.size() - 1), previous = moves.get(moves.size() - 2);
                RC lp = (last.row != null && last.col != null) ? new RC(last.row, last.col) : gtpToPoint(last.gtp, boardSize);
                RC pp = (previous.row != null && previous.col != null) ? new RC(previous.row, previous.col) : gtpToPoint(previous.gtp, boardSize);
                if (lp != null && pp != null) {
                    int dx = Math.abs(lp.col - pp.col), dy = Math.abs(lp.row - pp.row);
                    if (dx + dy == 1) features.add("contact");
                    if ((dx == 1 && dy == 2) || (dx == 2 && dy == 1)) features.add("knight-move");
                    if ((dx == 0 && dy == 2) || (dx == 2 && dy == 0)) features.add("jump");
                }
            }
        }
        return features;
    }
    static TrainingRegion detectRegion(KnowledgeMatchQuery query) {
        List<String> points = new ArrayList<>();
        if (query.playedMove != null) points.add(query.playedMove);
        if (query.candidateMoves != null) points.addAll(query.candidateMoves.subList(0, Math.min(3, query.candidateMoves.size())));
        if (query.principalVariation != null) points.addAll(query.principalVariation.subList(0, Math.min(5, query.principalVariation.size())));
        if (query.recentMoves != null) for (GameMove g : query.recentMoves) if (g.gtp != null) points.add(g.gtp);
        int corner = 0, side = 0, center = 0;
        for (String p : points) {
            RC c = gtpToPoint(p, query.boardSize);
            if (c == null) continue;
            int x = Math.min(c.col, query.boardSize - 1 - c.col);
            int y = Math.min(c.row, query.boardSize - 1 - c.row);
            if (x <= 5 && y <= 5) corner += 1;
            else if (Math.min(x, y) <= 3) side += 1;
            else center += 1;
        }
        if (corner >= side && corner >= center) return TrainingRegion.corner;
        if (side >= center) return TrainingRegion.side;
        return TrainingRegion.center;
    }
    static class QueryFeatures {
        Set<String> tokens = new HashSet<>(), explicitTokens = new HashSet<>(), moveFeatures = new HashSet<>(), candidateFeatures = new HashSet<>(), pvFeatures = new HashSet<>(), allPoints = new HashSet<>();
        Set<KnowledgeMatchType> intentTypes = new HashSet<>(), explicitIntentTypes = new HashSet<>();
        PatternPhase phase; TrainingRegion region;
    }
    static QueryFeatures buildFeatures(KnowledgeMatchQuery query) {
        QueryFeatures f = new QueryFeatures();
        f.phase = phaseFromMove(query.moveNumber, query.totalMoves);
        f.region = detectRegion(query);
        List<String> tokVals = new ArrayList<>();
        if (query.text != null) tokVals.add(query.text);
        if (query.judgement != null) tokVals.add(query.judgement);
        if (query.contextTags != null) tokVals.addAll(query.contextTags);
        f.tokens = new HashSet<>(normalizeTokens(tokVals));
        List<String> expVals = new ArrayList<>();
        if (query.text != null) expVals.add(query.text);
        f.explicitTokens = new HashSet<>(normalizeTokens(expVals));
        List<GameMove> recent = query.recentMoves != null
            ? (query.recentMoves.size() > 10 ? query.recentMoves.subList(query.recentMoves.size() - 10, query.recentMoves.size()) : query.recentMoves)
            : new ArrayList<>();
        Set<String> mv = new LinkedHashSet<>(addFeaturesFromMoves(recent, query.boardSize));
        if (query.playedMove != null) { Set<String> tmp = new LinkedHashSet<>(); addFeaturesFromGtp(Collections.singletonList(query.playedMove), query.boardSize, tmp); mv.addAll(tmp); }
        f.moveFeatures = mv;
        f.candidateFeatures = new LinkedHashSet<>();
        addFeaturesFromGtp(query.candidateMoves, query.boardSize, f.candidateFeatures);
        f.pvFeatures = new LinkedHashSet<>();
        addFeaturesFromGtp(query.principalVariation, query.boardSize, f.pvFeatures);
        f.allPoints = new LinkedHashSet<>();
        if (query.playedMove != null) f.allPoints.add(query.playedMove);
        if (query.candidateMoves != null) f.allPoints.addAll(query.candidateMoves);
        if (query.principalVariation != null) f.allPoints.addAll(query.principalVariation);
        if (query.recentMoves != null) for (GameMove g : query.recentMoves) if (g.gtp != null) f.allPoints.add(g.gtp);
        if (query.boardSnapshot != null) for (LocalPatternMatcher.BoardSnapshotStone st : query.boardSnapshot) if (st.point != null) f.allPoints.add(st.point);
        if (query.localWindows != null) for (LocalPatternMatcher.LocalWindow w : query.localWindows) {
            if (w.anchor != null) f.allPoints.add(w.anchor);
            if (w.stones != null) for (LocalPatternMatcher.BoardSnapshotStone st : w.stones) if (st.point != null) f.allPoints.add(st.point);
        }
        f.intentTypes = detectIntentTypes(f.tokens);
        f.explicitIntentTypes = detectIntentTypes(f.explicitTokens);
        return f;
    }
    static final Map<KnowledgeMatchType, List<String>> INTENT_KEYWORDS = new HashMap<>();
    static {
        INTENT_KEYWORDS.put(KnowledgeMatchType.joseki, Arrays.asList(
            "定式", "joseki", "星位", "小目", "点三三", "三三", "挂角", "守角", "夹攻", "低挂", "高挂",
            "大雪崩", "雪崩", "大斜", "太斜", "双飞燕", "妖刀", "托退", "中国流", "三连星", "小林流", "布局", "开局"));
        INTENT_KEYWORDS.put(KnowledgeMatchType.life_death, Arrays.asList(
            "死活", "做活", "杀棋", "活棋", "真眼", "假眼", "眼形", "急所", "对杀", "劫活", "双活",
            "曲四", "刀把五", "梅花六", "葡萄六", "板六", "金鸡独立", "直三", "弯三", "中手", "破眼", "扑入"));
        INTENT_KEYWORDS.put(KnowledgeMatchType.tesuji, Arrays.asList(
            "手筋", "倒扑", "吃回", "接不归", "老鼠偷油", "征子", "枷", "滚打包收", "挖", "扭十字",
            "断", "窥", "扳头", "双打", "弃子", "扑"));
    }
    static Set<KnowledgeMatchType> detectIntentTypes(Set<String> tokens) {
        Set<KnowledgeMatchType> intentTypes = new LinkedHashSet<>();
        for (Map.Entry<KnowledgeMatchType, List<String>> e : INTENT_KEYWORDS.entrySet())
            if (e.getValue().stream().anyMatch(kw -> tokenSetHas(tokens, kw))) intentTypes.add(e.getKey());
        return intentTypes;
    }
    static boolean tokenSetHas(Set<String> tokens, String value) {
        String n = value.toLowerCase().trim();
        if (tokens.contains(n)) return true;
        for (String t : tokens) if (n.contains(t) || t.contains(n)) return true;
        return false;
    }
    static double addOverlapScore(List<String> values, Set<String> tokens, double weight, List<String> reasons, String prefix) {
        if (values == null) return 0; double s = 0;
        for (String v : values) if (tokenSetHas(tokens, v)) { s += weight; reasons.add(prefix + ":" + v); }
        return s;
    }
    static boolean hasSpecificTextHit(List<String> values, Set<String> tokens) {
        if (values == null) return false;
        for (String v : values) if (v != null && !BROAD_TRAINING_TAGS.contains(v.toLowerCase())) if (tokenSetHas(tokens, v)) return true;
        return false;
    }
    static double featureOverlap(List<String> values, Set<String> features, double weight, List<String> reasons, String prefix) {
        if (values == null) return 0; double s = 0;
        for (String v : values) { String n = v.toLowerCase(); if (features.contains(n) || features.stream().anyMatch(f -> n.contains(f) || f.contains(n))) { s += weight; reasons.add(prefix + ":" + v); } }
        return s;
    }
    static double sequenceOverlap(List<String> sequence, Set<String> points, List<String> reasons) {
        if (sequence == null) return 0; double s = 0;
        for (String m : sequence) if (points.contains(m)) s += 1;
        if (s > 0) reasons.add("sequence-overlap:" + (int) s);
        return s;
    }
    static List<String> uniqueValidPoints(List<String> points, int boardSize) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : points) if (p != null && gtpToPoint(p, boardSize) != null) out.add(p.toUpperCase());
        return new ArrayList<>(out);
    }

    // ---- geometry match 复用 LocalShapeGeometryMatcher ----
    static LocalShapeGeometryMatcher.GeometryMatchResult geometryMatch(LocalShapeGeometryMatcher.ProblemEntry problem, KnowledgeMatchQuery query) {
        List<String> queryAnchors = uniqueValidPoints(new ArrayList<>() {{
            if (query.playedMove != null) add(query.playedMove);
            if (query.candidateMoves != null) addAll(query.candidateMoves.subList(0, Math.min(6, query.candidateMoves.size())));
            if (query.principalVariation != null) addAll(query.principalVariation.subList(0, Math.min(4, query.principalVariation.size())));
            if (query.localWindows != null) for (LocalPatternMatcher.LocalWindow w : query.localWindows) if (w.anchor != null) add(w.anchor);
        }}, query.boardSize);
        // 对齐 TS localShapeGeometryMatch：query 锚点只用 query 自身数据（playedMove/candidateMoves/principalVariation/localWindows），
        // 不把 problem 正解点混入 query 侧；problem 锚点是 problem.correctMoves（在 matchProblems 内部计算）
        LocalShapeGeometryMatcher.KnowledgeMatchQuery pq = new LocalShapeGeometryMatcher.KnowledgeMatchQuery();
        pq.boardSize = query.boardSize; pq.boardSnapshot = query.boardSnapshot;
        pq.playedMove = query.playedMove;
        pq.candidateMoves = query.candidateMoves != null ? new ArrayList<>(query.candidateMoves) : new ArrayList<>();
        pq.principalVariation = query.principalVariation != null ? new ArrayList<>(query.principalVariation) : new ArrayList<>();
        pq.localWindows = new ArrayList<>();
        return LocalShapeGeometryMatcher.matchProblems(java.util.Collections.singletonList(problem), pq);
    }

    static KnowledgeMatchConfidence confidence(double score, boolean exactish) {
        if (exactish && score >= 28) return KnowledgeMatchConfidence.exact;
        if (score >= 21) return KnowledgeMatchConfidence.strong;
        if (score >= 12) return KnowledgeMatchConfidence.partial;
        return KnowledgeMatchConfidence.weak;
    }
    static KnowledgeMatchConfidence capConfidence(KnowledgeMatchConfidence v, KnowledgeMatchConfidence max) { return CONFIDENCE_RANK.get(v) > CONFIDENCE_RANK.get(max) ? max : v; }
    static int sortMatchScore(KnowledgeMatch m) {
        int intentBonus = m.reason.stream().anyMatch(r -> r.startsWith("explicit-intent:")) ? 8 : 0;
        int exactBonus = m.reason.stream().anyMatch(r -> r.startsWith("answer-overlap") || r.startsWith("sequence-overlap")) ? 4 : 0;
        int geoBonus = m.reason.stream().anyMatch(r -> r.startsWith("geometry:")) ? 6 : 0;
        return CONFIDENCE_RANK.get(KnowledgeMatchConfidence.valueOf(m.confidence)) * 1000 + (int) m.score + intentBonus + exactBonus + geoBonus;
    }
    static String applicabilityFor(KnowledgeMatchConfidence c, KnowledgeMatchType t) {
        if (c == KnowledgeMatchConfidence.exact) return "本局局部手顺、候选点和区域都高度一致，可以作为同型讲解。";
        if (c == KnowledgeMatchConfidence.strong) return "本局棋形和 KataGo 候选点相近，可以作为强相关型讲解，但仍要看全局厚薄。";
        if (c == KnowledgeMatchConfidence.partial) return "本局只是像这个" + (t == KnowledgeMatchType.joseki ? "定式" : "棋形") + "，老师应说“像这个型”，不能硬套结论。";
        return "弱相关，只适合作为备用训练建议，不应进入主讲。";
    }

    static RecommendedProblem problemSummary(LocalShapeGeometryMatcher.ProblemEntry problem, String type) {
        RecommendedProblem p = new RecommendedProblem();
        p.id = problem.id; p.title = problem.title; p.problemType = type; p.difficulty = problem.difficulty;
        p.objective = problem.objective;
        p.firstHint = problem.teachingRecognition != null ? problem.teachingRecognition : problem.teachingTesujiIdea;
        p.answerSummary = problem.correctMoves.isEmpty() ? "先找急所。" : (problem.correctMoves.get(0).move + ": " + (problem.correctMoves.get(0).explanation != null ? problem.correctMoves.get(0).explanation : "第一手占急所。"));
        p.tags = problem.tags;
        return p;
    }
    static List<RecommendedProblem> relatedProblemsForTags(List<LocalShapeGeometryMatcher.ProblemEntry> lifeDeath, List<LocalShapeGeometryMatcher.ProblemEntry> tesuji, List<String> tags, int limit) {
        Set<String> tokenSet = new HashSet<>();
        for (String t : tags) { String n = t.toLowerCase(); if (!BROAD_TRAINING_TAGS.contains(n)) tokenSet.add(n); }
        if (tokenSet.isEmpty()) return new ArrayList<>();
        List<Object[]> cands = new ArrayList<>();
        for (LocalShapeGeometryMatcher.ProblemEntry p : lifeDeath) cands.add(new Object[]{p, "life_death", addOverlapScore(p.tags, tokenSet, 2, new ArrayList<>(), "tag")});
        for (LocalShapeGeometryMatcher.ProblemEntry p : tesuji) cands.add(new Object[]{p, "tesuji", addOverlapScore(p.tags, tokenSet, 2, new ArrayList<>(), "tag")});
        cands.sort((a, b) -> Double.compare((Double) b[2], (Double) a[2]) != 0 ? Double.compare((Double) b[2], (Double) a[2]) : ((LocalShapeGeometryMatcher.ProblemEntry) a[0]).title.compareTo(((LocalShapeGeometryMatcher.ProblemEntry) b[0]).title));
        List<RecommendedProblem> out = new ArrayList<>();
        for (Object[] c : cands) { if ((Double) c[2] > 0) { out.add(problemSummary((LocalShapeGeometryMatcher.ProblemEntry) c[0], (String) c[1])); if (out.size() >= limit) break; } }
        return out;
    }

    static KnowledgeMatch josekiMatch(JsonKnowledgeLoader.JosekiLineEntry line, KnowledgeMatchQuery query, QueryFeatures features, List<LocalShapeGeometryMatcher.ProblemEntry> lifeDeath, List<LocalShapeGeometryMatcher.ProblemEntry> tesuji) {
        double score = 0; List<String> reasons = new ArrayList<>();
        boolean explicitJoseki = features.explicitIntentTypes.contains(KnowledgeMatchType.joseki);
        boolean explicitTactical = features.explicitIntentTypes.contains(KnowledgeMatchType.life_death) || features.explicitIntentTypes.contains(KnowledgeMatchType.tesuji);
        String level = query.studentLevel != null ? query.studentLevel : query.userLevel;
        if (line.levels.contains(level)) { score += 2; reasons.add("level:" + level); }
        if (line.phase.contains(features.phase.name())) { score += 5; reasons.add("phase:" + features.phase.name()); }
        if (features.region == TrainingRegion.corner) { score += 5; reasons.add("region:corner"); }
        if (explicitJoseki) { score += 10; reasons.add("explicit-intent:joseki"); }
        else if (features.intentTypes.contains(KnowledgeMatchType.joseki)) { score += 3; reasons.add("context-intent:joseki"); }
        score += addOverlapScore(new ArrayList<>(){{addAll(line.tags);add(line.title);add(line.family);}}, features.tokens, 4, reasons, "text");
        score += featureOverlap(line.normalizedFeatures, new HashSet<>(){{addAll(features.moveFeatures);addAll(features.candidateFeatures);addAll(features.pvFeatures);}}, 4, reasons, "shape");
        double overlap = sequenceOverlap(line.relativeSequence, features.allPoints, reasons);
        score += overlap * (features.phase == PatternPhase.opening || explicitJoseki ? 5 : 2);
        if (query.candidateMoves != null && line.relativeSequence.size() > 1 && query.candidateMoves.contains(line.relativeSequence.get(1))) { score += 5; reasons.add("katago-candidate-prefix"); }
        if (query.moveNumber <= 70) { score += 2; reasons.add("opening-timing"); }
        if (features.phase != PatternPhase.opening && !explicitJoseki) { score -= explicitTactical ? 22 : 10; reasons.add(explicitTactical ? "penalty:tactical-query" : "penalty:non-opening-joseki-context"); }
        if (score < 8) return null;
        boolean exactish = overlap >= 3 && (features.phase == PatternPhase.opening || explicitJoseki) && !explicitTactical;
        KnowledgeMatchConfidence raw = confidence(score, exactish);
        KnowledgeMatchConfidence conf = (features.phase != PatternPhase.opening && !explicitJoseki) ? capConfidence(raw, KnowledgeMatchConfidence.partial) : raw;
        KnowledgeMatch m = new KnowledgeMatch();
        m.id = line.id; m.matchType = "joseki"; m.title = line.title; m.confidence = conf.name(); m.score = score;
        m.reason = new ArrayList<>(new LinkedHashSet<>(reasons)).subList(0, Math.min(8, new LinkedHashSet<>(reasons).size()));
        m.applicability = applicabilityFor(conf, KnowledgeMatchType.joseki);
        m.teachingPayload.summary = line.katagoEraJudgement; m.teachingPayload.recognition = "识别为" + line.title + "相关局部：看角部手顺、挂角/点三三位置和外势方向。";
        m.teachingPayload.correctIdea = String.join(" ", line.decisionRules); m.teachingPayload.keyVariations = line.branches.stream().limit(3).map(b -> b.name + ": " + b.whenToChoose).toList();
        m.teachingPayload.memoryCue = "定式先问方向，再问先手，最后才背手顺。"; m.teachingPayload.commonMistakes = line.commonMistakes;
        m.teachingPayload.drills = line.trainingFocus.stream().map(f -> line.title + "专项：" + f).toList();
        m.teachingPayload.boundary = applicabilityFor(conf, KnowledgeMatchType.joseki); m.teachingPayload.sourceKind = line.sourceKind;
        m.relatedProblems = relatedProblemsForTags(lifeDeath, tesuji, line.tags, 3);
        return m;
    }

    static KnowledgeMatch problemMatch(LocalShapeGeometryMatcher.ProblemEntry problem, String type, KnowledgeMatchQuery query, QueryFeatures features, List<LocalShapeGeometryMatcher.ProblemEntry> lifeDeath, List<LocalShapeGeometryMatcher.ProblemEntry> tesuji) {
        double score = 0; List<String> reasons = new ArrayList<>();
        boolean explicitType = features.explicitIntentTypes.contains(KnowledgeMatchType.valueOf(type));
        boolean contextualType = features.intentTypes.contains(KnowledgeMatchType.valueOf(type));
        boolean explicitJosekiOnly = features.explicitIntentTypes.contains(KnowledgeMatchType.joseki) && !explicitType;
        if (problem.region.equals(features.region.name())) { score += 5; reasons.add("region:" + problem.region); }
        if ((query.lossScore != null ? query.lossScore : 0) >= 2 || (query.judgement != null && (query.judgement.contains("mistake") || query.judgement.contains("blunder")))) { score += type.equals("life_death") ? 4 : 3; reasons.add("katago-loss"); }
        if (explicitType) { score += 12; reasons.add("explicit-intent:" + type); }
        else if (contextualType) { score += 5; reasons.add("context-intent:" + type); }
        if (explicitJosekiOnly) { score -= 8; reasons.add("penalty:joseki-query"); }
        List<String> textValues = new ArrayList<>(){{addAll(problem.tags);add(problem.title);add(problem.objective);}};
        if (hasSpecificTextHit(textValues, features.explicitTokens)) { score += 6; reasons.add("specific-text:" + type); }
        score += addOverlapScore(textValues, features.tokens, 4, reasons, "text");
        score += featureOverlap(problem.tags, new HashSet<>(){{addAll(features.moveFeatures);addAll(features.candidateFeatures);addAll(features.pvFeatures);}}, 3, reasons, "shape");
        List<String> answerMoves = problem.correctMoves.stream().map(m -> m.move).toList();
        double answerOverlap = sequenceOverlap(answerMoves, features.allPoints, reasons);
        score += answerOverlap * 12;
        LocalShapeGeometryMatcher.GeometryMatchResult geo = geometryMatch(problem, query);
        if (geo != null) { score += geo.score; reasons.add("geometry:" + geo.transform + ":" + geo.colorMode + ":" + geo.matched + "/" + geo.expected); reasons.add("liberties:" + geo.libertyScore); if (geo.ratio >= 0.72) { score += 6; reasons.add("geometry-strong-local-shape"); } }
        Set<String> answerPoints = new HashSet<>(answerMoves);
        if (query.playedMove != null && answerPoints.contains(query.playedMove)) { score += 5; reasons.add("answer-played"); }
        if (query.candidateMoves != null && query.candidateMoves.stream().anyMatch(answerPoints::contains)) { score += 7; reasons.add("answer-candidate"); }
        if (query.principalVariation != null && query.principalVariation.stream().anyMatch(answerPoints::contains)) { score += 5; reasons.add("answer-pv"); }
        if (type.equals("life_death") && features.moveFeatures.contains("eye-shape")) { score += 5; reasons.add("eye-shape"); }
        if (type.equals("tesuji") && (features.moveFeatures.contains("contact") || features.moveFeatures.contains("jump"))) { score += 3; reasons.add("local-tesuji-relation"); }
        if (score < 8) return null;
        boolean exactish = (answerOverlap >= 1 && hasSpecificTextHit(textValues, features.explicitTokens) && (explicitType || score >= 24)) || (geo != null && geo.ratio >= 0.72 && geo.matched >= 3);
        KnowledgeMatchConfidence conf = confidence(score, exactish);
        KnowledgeMatch m = new KnowledgeMatch();
        m.id = problem.id; m.matchType = type; m.title = problem.title; m.confidence = conf.name(); m.score = score;
        m.reason = new ArrayList<>(new LinkedHashSet<>(reasons)).subList(0, Math.min(8, new LinkedHashSet<>(reasons).size()));
        m.applicability = applicabilityFor(conf, KnowledgeMatchType.valueOf(type));
        m.teachingPayload.summary = problem.objective; m.teachingPayload.recognition = problem.teachingRecognition != null ? problem.teachingRecognition : (problem.teachingTesujiIdea != null ? problem.teachingTesujiIdea : "先识别局部形状和双方气数。");
        m.teachingPayload.correctIdea = problem.teachingExplanation != null ? problem.teachingExplanation : (problem.teachingTesujiIdea != null ? problem.teachingTesujiIdea : "先找急所，再读失败手。");
        m.teachingPayload.keyVariations = problem.correctMoves.stream().limit(2).map(mm -> mm.move + ": " + (mm.explanation != null ? mm.explanation : "正确第一手")).toList();
        m.teachingPayload.memoryCue = problem.teachingMemoryCue != null ? problem.teachingMemoryCue : "记住急所和次序。";
        m.teachingPayload.commonMistakes = problem.failureMoves.stream().limit(2).map(mm -> mm.move + ": " + (mm.why != null ? mm.why : "次序错误")).toList();
        m.teachingPayload.drills = new ArrayList<>(Collections.singletonList(problem.title + "：先看题，不看答案，读清第一手和失败手。"));
        m.teachingPayload.boundary = applicabilityFor(conf, KnowledgeMatchType.valueOf(type)); m.teachingPayload.sourceKind = problem.sourceKind;
        m.relatedProblems = new ArrayList<>(Collections.singletonList(problemSummary(problem, type)));
        return m;
    }

    public static List<KnowledgeMatch> searchKnowledgeMatchEngine(KnowledgeMatchQuery query) {
        List<LocalShapeGeometryMatcher.ProblemEntry> library = JsonKnowledgeLoader.loadTrainingProblems();
        List<LocalShapeGeometryMatcher.ProblemEntry> lifeDeath = library.stream().filter(p -> "life_death".equals(p.problemKind)).toList();
        List<LocalShapeGeometryMatcher.ProblemEntry> tesuji = library.stream().filter(p -> "tesuji".equals(p.problemKind)).toList();
        List<JsonKnowledgeLoader.JosekiLineEntry> lines = JsonKnowledgeLoader.loadJosekiLines();
        QueryFeatures features = buildFeatures(query);
        List<KnowledgeMatch> matches = new ArrayList<>();
        for (JsonKnowledgeLoader.JosekiLineEntry line : lines) { KnowledgeMatch m = josekiMatch(line, query, features, lifeDeath, tesuji); if (m != null) matches.add(m); }
        for (LocalShapeGeometryMatcher.ProblemEntry p : lifeDeath) { KnowledgeMatch m = problemMatch(p, "life_death", query, features, lifeDeath, tesuji); if (m != null) matches.add(m); }
        for (LocalShapeGeometryMatcher.ProblemEntry p : tesuji) { KnowledgeMatch m = problemMatch(p, "tesuji", query, features, lifeDeath, tesuji); if (m != null) matches.add(m); }

        // pattern 层：对齐 GoAgent searchKnowledgeMatchEngine 里的 searchKnowledgePatterns(loadKnowledgePatternCards(...), {...})
        PatternSearchEngine.PatternSearchContext pc = new PatternSearchEngine.PatternSearchContext();
        pc.userLevel = query.studentLevel != null ? query.studentLevel : (query.userLevel != null ? query.userLevel : "intermediate");
        pc.phase = features.phase.name(); pc.region = features.region.name();
        pc.boardSize = query.boardSize; pc.moveNumber = query.moveNumber; pc.text = query.text;
        pc.playedMove = query.playedMove; pc.judgement = query.judgement; pc.lossScore = query.lossScore;
        pc.contextTags = query.contextTags != null ? query.contextTags : new ArrayList<>();
        pc.candidateMoves = query.candidateMoves != null ? query.candidateMoves : new ArrayList<>();
        pc.principalVariation = query.principalVariation != null ? query.principalVariation : new ArrayList<>();
        pc.recentMoves = new ArrayList<>();
        if (query.recentMoves != null) for (GameMove gm : query.recentMoves) {
            PatternSearchEngine.GameMove g = new PatternSearchEngine.GameMove();
            g.gtp = gm.gtp;
            var rc = PatternSearchEngine.gtpToPoint(gm.gtp, query.boardSize);
            if (rc != null) { g.row = rc.row; g.col = rc.col; }
            pc.recentMoves.add(g);
        }
        int patternShown = 0;
        for (PatternSearchEngine.PatternSearchMatch ps : PatternSearchEngine.searchKnowledgePatterns(PatternSearchEngine.loadKnowledgePatternCards(), pc)) {
            if (patternShown >= 4) break;
            patternShown++;
            KnowledgeMatch m = new KnowledgeMatch();
            m.id = ps.card.id; m.matchType = ps.card.category;
            m.title = ps.card.title;
            m.confidence = ps.confidence == PatternSearchEngine.PatternConfidence.high ? "strong" : ps.confidence == PatternSearchEngine.PatternConfidence.medium ? "partial" : "weak";
            m.score = ps.score; m.reason = new ArrayList<>(ps.reasons);
            m.applicability = applicabilityFor(KnowledgeMatchConfidence.valueOf(m.confidence), KnowledgeMatchType.valueOf(ps.card.category));
            m.teachingPayload.summary = ps.card.teaching.correctIdea;
            m.teachingPayload.recognition = ps.card.teaching.recognition;
            m.teachingPayload.correctIdea = ps.card.teaching.correctIdea;
            m.teachingPayload.keyVariations = ps.card.variations.stream().limit(3).map(v -> v.name + ": " + v.whenToChoose).toList();
            m.teachingPayload.memoryCue = ps.card.teaching.memoryCue;
            m.teachingPayload.commonMistakes = new ArrayList<>(Collections.singletonList(ps.card.teaching.commonMistake));
            m.teachingPayload.drills = new ArrayList<>(Collections.singletonList(ps.card.teaching.drill));
            m.teachingPayload.boundary = m.applicability + "\n" + PatternSearchEngine.formatPatternForPrompt(ps).split("\n")[PatternSearchEngine.formatPatternForPrompt(ps).split("\n").length - 1];
            m.teachingPayload.sourceKind = "common-pattern";
            // relatedProblems：按卡标签匹配题库
            List<RecommendedProblem> rel = relatedProblemsForTags(lifeDeath, tesuji, ps.card.tags, 2);
            if (!rel.isEmpty()) m.relatedProblems = rel;
            matches.add(m);
        }
        matches.sort((a, b) -> sortMatchScore(b) - sortMatchScore(a) != 0 ? sortMatchScore(b) - sortMatchScore(a) : Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) : a.title.compareTo(b.title));
        return matches.size() > (query.maxResults) ? matches.subList(0, query.maxResults) : matches;
    }

    public static List<RecommendedProblem> recommendedProblemsFromMatches(List<KnowledgeMatch> matches, int limit, boolean includeWeakFallback, boolean includeJosekiFallback, boolean includeDrillFallback) {
        Set<String> seen = new HashSet<>(); List<RecommendedProblem> problems = new ArrayList<>();
        if (collectRelated(matches, limit, m -> !"weak".equals(m.confidence) && !"joseki".equals(m.matchType), seen, problems)) return problems;
        if (includeWeakFallback && collectRelated(matches, limit, m -> "weak".equals(m.confidence) && !"joseki".equals(m.matchType), seen, problems)) return problems;
        if (includeJosekiFallback && collectRelated(matches, limit, m -> "joseki".equals(m.matchType), seen, problems)) return problems;
        if (includeDrillFallback) {
            for (KnowledgeMatch m : matches) {
                String drill = m.teachingPayload.drills.stream().filter(Objects::nonNull).findFirst().orElse(null);
                if (drill == null) continue;
                String id = "drill-" + m.id; if (seen.contains(id)) continue; seen.add(id);
                RecommendedProblem p = new RecommendedProblem();
                p.id = id; p.title = m.title + "：专项训练"; p.problemType = "life_death".equals(m.matchType) ? "life_death" : "tesuji";
                p.difficulty = ("exact".equals(m.confidence) || "strong".equals(m.confidence)) ? "standard" : "review";
                p.objective = m.teachingPayload.correctIdea != null ? m.teachingPayload.correctIdea : m.teachingPayload.summary;
                p.firstHint = m.teachingPayload.memoryCue != null ? m.teachingPayload.memoryCue : m.teachingPayload.recognition;
                p.answerSummary = drill; p.tags = new ArrayList<>(Collections.singletonList(m.matchType));
                problems.add(p); if (problems.size() >= limit) return problems;
            }
        }
        return problems;
    }
    static boolean collectRelated(List<KnowledgeMatch> matches, int limit, java.util.function.Predicate<KnowledgeMatch> pred, Set<String> seen, List<RecommendedProblem> problems) {
        for (KnowledgeMatch m : matches) {
            if (!pred.test(m)) continue;
            for (RecommendedProblem p : m.relatedProblems) if (seen.add(p.id)) problems.add(p);
            if (problems.size() >= limit) return true;
        }
        return false;
    }

    public static String formatKnowledgeMatchForPrompt(KnowledgeMatch match) {
        return String.join("\n",
            "匹配类型: " + match.matchType,
            "名称: " + match.title,
            "置信度: " + match.confidence,
            "匹配依据: " + String.join(", ", match.reason),
            "适用边界: " + match.applicability,
            "识别特征: " + match.teachingPayload.recognition,
            "正确思路: " + match.teachingPayload.correctIdea,
            "常见变化: " + String.join("；", match.teachingPayload.keyVariations),
            "记忆法: " + match.teachingPayload.memoryCue,
            "常见误区: " + String.join("；", match.teachingPayload.commonMistakes),
            "训练建议: " + (match.relatedProblems.stream().map(p -> p.title + "(" + p.difficulty + ")").reduce((a, b) -> a + "、" + b).orElse(String.join("；", match.teachingPayload.drills))),
            "老师使用边界: exact/strong 可以说“这是某某型”；partial 只能说“像某某型”；weak 不进入主讲。"
        );
    }
}
