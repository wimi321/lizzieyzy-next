package featurecat.lizzie.teacher.knowledge;

import java.util.*;

/**
 * 对齐 GoAgent knowledge/motifRecognizer.ts：
 * 综合 elite 卡、定式识别、知识匹配、启发式，识别当前局面的棋形/motif（教学要点）。
 */
public final class MotifRecognizer {

    private MotifRecognizer() {}

    public enum MotifConfidence { strong, medium, weak }
    public enum MotifPhase { opening, middlegame, endgame, any }
    public enum MotifRegion { corner, side, center, any }

    public static class RecognizedTeachingMotif {
        public String id, title, motifType, category;
        public MotifPhase[] phase;
        public MotifRegion region;
        public MotifConfidence confidence;
        public double score;
        public String[] evidence;
        public String whyMatched, recognition, wrongThinking, correctThinking, drillPrompt, source;
        public String[] sourceRefs, tags;
        public String sourceQuality;
        public List<ExpectedNextMove> expectedNextMoves = new ArrayList<>();
        public int variationCount;
        public String josekiFamily;
        public String[] relatedMoves;
    }

    public static class ExpectedNextMove { public String move, label, condition; }

    public static class MotifRecognizerQuery {
        public java.util.List<MatchEngine.KnowledgeMatch> knowledgeMatches = new java.util.ArrayList<>();
        public Double scoreLoss;
        public Double spread;
        public String text;
        public int moveNumber, totalMoves, boardSize;
        public List<JosekiRecognizer.JosekiMoveLike> recentMoves;
        public String userLevel, playerColor, lossScore, judgement;
        public String[] contextTags, playedMove, candidateMoves, principalVariation;
        public Integer maxResults;
    }

    static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRST";

    static MotifPhase phaseFrom(int moveNumber, int totalMoves) {
        double ratio = totalMoves > 0 ? (double) moveNumber / totalMoves : 0;
        if (moveNumber <= 40 || ratio <= 0.2) return MotifPhase.opening;
        if (ratio <= 0.72) return MotifPhase.middlegame;
        return MotifPhase.endgame;
    }

    static String normalizeMove(String m) {
        if (m == null) return null;
        String t = m.trim().toUpperCase();
        if (t.isEmpty() || t.equals("PASS")) return null;
        return t;
    }

    static class Pt { int x, y; }
    static Pt pointFromGtp(String m, int boardSize) {
        String n = normalizeMove(m);
        if (n == null) return null;
        var mt = java.util.regex.Pattern.compile("^([A-HJ-T])(\\d{1,2})$").matcher(n);
        if (!mt.find()) return null;
        int x = GTP_COLUMNS.substring(0, boardSize).indexOf(mt.group(1));
        int y = Integer.parseInt(mt.group(2)) - 1;
        if (x < 0 || y < 0 || y >= boardSize) return null;
        Pt p = new Pt(); p.x = x; p.y = y; return p;
    }

    static MotifRegion regionOf(Pt p, int boardSize) {
        if (p == null) return MotifRegion.any;
        int dX = Math.min(p.x, boardSize - 1 - p.x), dY = Math.min(p.y, boardSize - 1 - p.y);
        if (dX <= 4 && dY <= 4) return MotifRegion.corner;
        if (Math.min(dX, dY) <= 3) return MotifRegion.side;
        return MotifRegion.center;
    }

    static int distance(Pt a, Pt b) { if (a == null || b == null) return 99; return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)); }

    static double candidateSpread(List<String[]> topMoves) {
        if (topMoves == null || topMoves.size() < 2) return 99;
        double w0 = Double.parseDouble(topMoves.get(0)[0]); // [winrate, score, ...]
        double w1 = Double.parseDouble(topMoves.get(1)[0]);
        return Math.abs(w0 - w1);
    }

    static String stringifySignals(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (part == null) continue;
            if (part instanceof Object[]) { for (Object o : (Object[]) part) sb.append(String.valueOf(o).toLowerCase()).append(" | "); }
            else sb.append(String.valueOf(part).toLowerCase()).append(" | ");
        }
        return sb.toString();
    }

    static String[] tokenHit(String signalText, String[] tokens) {
        List<String> hits = new ArrayList<>();
        if (tokens == null) return new String[0];
        for (String t : tokens) if (t != null && !t.isEmpty() && signalText.contains(t.toLowerCase())) hits.add(t);
        return hits.toArray(new String[0]);
    }

    static class ScoreWithReasons { double score; List<String> reasons = new ArrayList<>(); }
    static ScoreWithReasons scoreEliteCard(JsonKnowledgeLoader.ElitePatternCard card, MotifRecognizerQuery query,
                                 String signalText, MotifRegion region, MotifPhase phase, int localDistance,
                                 double loss, double scoreLoss, String judgement, double spread) {
        List<String> reasons = new ArrayList<>();
        double score = card.confidenceBoost;
        boolean phaseHit = contains(card.phase, phase.name()) || contains(card.phase, "any");
        if (phaseHit) { score += 2; reasons.add("phase=" + phase); }
        boolean regionHit = contains(card.regions, region.name()) || contains(card.regions, "any");
        if (regionHit) { score += 2; reasons.add("region=" + region); }
        if (query.userLevel == null || contains(card.levels, query.userLevel) || contains(card.levels, "any")) score += 1;
        String[] tagHits = tokenHit(signalText, concat(card.tags, card.aliases));
        if (tagHits.length > 0) { score += Math.min(7, tagHits.length * 2); reasons.add("keyword hits: " + String.join(", ", Arrays.copyOf(tagHits, Math.min(4, tagHits.length)))); }
        String[] trigHits = tokenHit(signalText, card.triggerSignals);
        if (trigHits.length > 0) { score += Math.min(8, trigHits.length * 2.5); reasons.add("trigger signals: " + String.join(", ", Arrays.copyOf(trigHits, Math.min(4, trigHits.length)))); }
        String[] kataHits = tokenHit(signalText, card.katagoSignals);
        if (kataHits.length > 0) { score += Math.min(6, kataHits.length * 2); reasons.add("KataGo signals: " + String.join(", ", Arrays.copyOf(kataHits, Math.min(4, kataHits.length)))); }
        String[] negHits = tokenHit(signalText, card.negativeSignals);
        if (negHits.length > 0) score -= negHits.length * 3;
        if (loss >= 7 || scoreLoss >= 4 || "blunder".equals(judgement)) { score += 3; reasons.add("large-loss review point"); }
        else if (loss >= 2.5 || scoreLoss >= 1.5 || "mistake".equals(judgement)) { score += 2; reasons.add("medium-loss review point"); }
        if (localDistance <= 2 && (card.category.equals("tesuji") || card.category.equals("life-death") || card.category.equals("shapes"))) { score += 2; reasons.add("actual/best move are local alternatives"); }
        if (localDistance >= 6 && (card.category.equals("strategy") || card.category.equals("opening") || card.category.equals("endgame"))) { score += 2; reasons.add("actual/best move indicate whole-board direction"); }
        if (spread < 1.5 && card.patternType.contains("style")) score += 3;
        ScoreWithReasons r = new ScoreWithReasons(); r.score = score; r.reasons = reasons;
        return r;
    }

    static boolean contains(String[] arr, String v) { if (arr == null) return false; for (String a : arr) if (a != null && a.equals(v)) return true; return false; }
    static String[] concat(String[]... arrs) { List<String> out = new ArrayList<>(); for (String[] a : arrs) if (a != null) for (String s : a) out.add(s); return out.toArray(new String[0]); }

    static RecognizedTeachingMotif motifFromCard(JsonKnowledgeLoader.ElitePatternCard card, double score, List<String> reasons, MotifRegion region, String[] relatedMoves) {
        RecognizedTeachingMotif m = new RecognizedTeachingMotif();
        m.id = card.id; m.title = card.title; m.motifType = card.patternType; m.category = card.category;
        m.phase = new MotifPhase[]{ MotifPhase.valueOf(card.phase.length > 0 ? card.phase[0] : "any") };
        m.region = region;
        m.confidence = score >= 14 ? MotifConfidence.strong : score >= 8 ? MotifConfidence.medium : MotifConfidence.weak;
        m.score = Math.round(score * 10) / 10.0;
        m.evidence = reasons.subList(0, Math.min(6, reasons.size())).toArray(new String[0]);
        m.whyMatched = String.join("；", reasons.subList(0, Math.min(4, reasons.size())));
        m.recognition = card.recognition; m.wrongThinking = card.wrongThinking; m.correctThinking = card.correctThinking; m.drillPrompt = card.drillPrompt;
        m.source = "elite-card"; m.sourceRefs = card.sourceRefs; m.sourceQuality = card.sourceQuality;
        m.relatedMoves = relatedMoves; m.tags = card.tags;
        return m;
    }

    static List<RecognizedTeachingMotif> matchKnowledgeMotifs(java.util.List<MatchEngine.KnowledgeMatch> matches, String[] relatedMoves) {
        List<RecognizedTeachingMotif> out = new ArrayList<>();
        if (matches == null) return out;
        int shown = 0;
        for (MatchEngine.KnowledgeMatch match : matches) {
            if ("weak".equals(match.confidence)) continue;
            if (shown >= 4) break;
            shown++;
            List<String> evidence = new ArrayList<>(match.reason);
            if (match.teachingPayload != null && match.teachingPayload.recognition != null) evidence.add(match.teachingPayload.recognition);
            if (evidence.size() > 5) evidence = new ArrayList<>(evidence.subList(0, 5));
            RecognizedTeachingMotif m = new RecognizedTeachingMotif();
            m.id = "knowledge-" + match.id;
            m.title = match.title;
            m.motifType = match.matchType;
            m.category = match.matchType;
            m.phase = new MotifPhase[]{ MotifPhase.any };
            m.region = MotifRegion.any;
            m.confidence = "strong".equals(match.confidence) ? MotifConfidence.strong : MotifConfidence.medium;
            m.score = Math.round((match.score + ("strong".equals(match.confidence) ? 5 : 2)) * 10) / 10.0;
            m.evidence = evidence.toArray(new String[0]);
            m.whyMatched = (evidence.size() > 3 ? String.join("；", evidence.subList(0, 3)) : String.join("；", evidence));
            if (m.whyMatched.isEmpty()) m.whyMatched = "知识匹配 " + match.title;
            m.recognition = match.teachingPayload != null && match.teachingPayload.recognition != null ? match.teachingPayload.recognition : match.title;
            m.wrongThinking = "只看局部结果，没有把棋形和全局目的联系起来。";
            m.correctThinking = "先确认棋形目的，再比较候选手的先后手和方向。";
            m.drillPrompt = "回到这个局面，只问自己：这手的棋形目的是什么？";
            m.source = "knowledge-match";
            m.sourceRefs = new String[]{"local-knowledge-match"};
            m.sourceQuality = "project-local";
            m.relatedMoves = relatedMoves;
            m.tags = new String[]{match.matchType, match.confidence};
            out.add(m);
        }
        return out;
    }

    static List<RecognizedTeachingMotif> heuristicMotifs(MotifRecognizerQuery query, MotifPhase phase, MotifRegion region, String actual, String best, int localDistance, double loss, double scoreLoss, String judgement, String[] relatedMoves) {
        List<RecognizedTeachingMotif> results = new ArrayList<>();
        if ((loss >= 2.5 || scoreLoss >= 1.5 || "mistake".equals(judgement) || "blunder".equals(judgement)) && localDistance >= 6) {
            results.add(makeHeuristic("heuristic-urgent-vs-big", "急所与大场的优先级", "urgent_vs_big", "strategy",
                loss >= 7 || scoreLoss >= 4 ? MotifConfidence.strong : MotifConfidence.medium, 11 + Math.min(5, loss / 2), region, phase, relatedMoves,
                new String[]{"实战手与首选手距离较远", "winrateLoss=" + loss, "scoreLoss=" + scoreLoss},
                "AI 更重视另一区域，说明当前最大问题可能不是具体棋形，而是先后顺序。",
                "只看眼前一块棋的安全或小空，忽略全局最大压力点。",
                "先问哪边如果不处理会立刻变差，再问下一手是否还能回到大场。",
                "遮住推荐点，只判断：这一步是该先救急、攻击，还是抢大场？",
                new String[]{"急所","大场","方向"}));
        }
        if ((loss >= 3 || scoreLoss >= 2) && localDistance <= 2 && phase != MotifPhase.opening) {
            results.add(makeHeuristic("heuristic-local-shape-loss", "局部棋形/读秒下的细节亏损", "shape_inefficiency", "shapes",
                loss >= 7 ? MotifConfidence.strong : MotifConfidence.medium, 10 + Math.min(4, loss / 2), region, phase, relatedMoves,
                new String[]{"实战手和首选手是近距离局部选择", "localDistance=" + localDistance, "judgement=" + judgement},
                "这里不是换区域的问题，而是同一局部里的手筋/形状/先后手差异。",
                "只想“这里也能下”，没有比较哪一手更先手、更补形、更限制对方。",
                "同一区域先比较：是否打吃方向正确、是否留下断点、是否让对方先手。",
                "在同一区域给出两个候选，判断哪一个更先手、形更好。",
                new String[]{"棋形","局部","次序"}));
        }
        if (phase == MotifPhase.endgame && (scoreLoss >= 1 || loss >= 2.5)) {
            results.add(makeHeuristic("heuristic-endgame-sente", "官子先后手与目差", "endgame_sente", "endgame",
                scoreLoss >= 2 ? MotifConfidence.strong : MotifConfidence.medium, 10 + Math.min(5, scoreLoss * 2), region, phase, relatedMoves,
                new String[]{"phase=" + phase, "scoreLoss=" + scoreLoss, "官子阶段目差更可靠"},
                "这类错误通常不是“胜率数字”，而是官子价值和先后手次序。",
                "只看当前能收几目，没看对方下一手是否抢到更大的先手。",
                "先区分先手/后手/逆收，再按双方最大官子排序。",
                "给三个官子点，先判断哪个是先手，再排序大小。",
                new String[]{"官子","先手","目差"}));
        }
        if (phase == MotifPhase.middlegame && localDistance >= 4 && (query.principalVariation != null && query.principalVariation.length >= 3)) {
            results.add(makeHeuristic("heuristic-attack-direction", "攻击方向与借力", "attack_direction", "strategy",
                loss >= 4 ? MotifConfidence.medium : MotifConfidence.weak, 8 + Math.min(4, loss / 2), region, phase, relatedMoves,
                new String[]{"中盘候选手带出连续 PV", "首选手改变作战方向"},
                "AI 可能是在借攻击争取外势、先手或转换。",
                "攻击时只想吃掉对方，不看对方逃跑方向和自己的收益。",
                "攻击前先问：我要把对方赶向哪里？我攻击的同时得到什么？",
                "选择一个方向压迫孤棋，并说出你想得到的收益。",
                new String[]{"攻击","方向","转换"}));
        }
        return results;
    }

    static RecognizedTeachingMotif makeHeuristic(String id, String title, String motifType, String category,
            MotifConfidence conf, double score, MotifRegion region, MotifPhase phase, String[] relatedMoves,
            String[] evidence, String recognition, String wrong, String correct, String drill, String[] tags) {
        RecognizedTeachingMotif m = new RecognizedTeachingMotif();
        m.id = id; m.title = title; m.motifType = motifType; m.category = category;
        m.phase = new MotifPhase[]{ phase }; m.region = region; m.confidence = conf;
        m.score = Math.round(score * 10) / 10.0;
        m.evidence = evidence; m.whyMatched = String.join("；", evidence);
        m.recognition = recognition; m.wrongThinking = wrong; m.correctThinking = correct; m.drillPrompt = drill;
        m.source = "heuristic"; m.relatedMoves = relatedMoves; m.tags = tags; m.sourceRefs = new String[]{"heuristic"};
        return m;
    }

    static RecognizedTeachingMotif[] motifsFromJoseki(JosekiRecognizer.RecognizedJosekiPattern p) {
        RecognizedTeachingMotif m = new RecognizedTeachingMotif();
        m.id = "joseki-" + p.id; m.title = p.name; m.motifType = "joseki:" + p.family; m.category = "joseki";
        m.phase = new MotifPhase[]{ MotifPhase.opening }; m.region = MotifRegion.corner;
        m.confidence = p.confidence == JosekiRecognizer.JosekiConfidence.strong ? MotifConfidence.strong :
                       p.confidence == JosekiRecognizer.JosekiConfidence.medium ? MotifConfidence.medium : MotifConfidence.weak;
        m.score = p.score + 2; m.evidence = p.evidence; m.whyMatched = String.join("；", Arrays.copyOf(p.evidence, Math.min(4, p.evidence.length)));
        m.recognition = p.recognition; m.wrongThinking = p.wrongThinking; m.correctThinking = p.correctThinking; m.drillPrompt = p.drillPrompt;
        m.source = "joseki-card"; m.sourceRefs = p.sourceRefs; m.sourceQuality = p.sourceQuality;
        m.variationCount = p.variationCount; m.josekiFamily = p.family;
        m.relatedMoves = p.commonNextMoves.stream().map(mv -> mv.gtpMove != null ? mv.gtpMove : mv.relativeMove).filter(Objects::nonNull).toArray(String[]::new);
        m.tags = new String[]{"定式","joseki",p.family};
        for (JosekiRecognizer.JosekiNextMove mv : p.commonNextMoves) {
            ExpectedNextMove en = new ExpectedNextMove();
            en.move = mv.gtpMove != null ? mv.gtpMove : mv.relativeMove;
            en.label = mv.label; en.condition = mv.condition;
            m.expectedNextMoves.add(en);
        }
        return new RecognizedTeachingMotif[]{ m };
    }

    static List<RecognizedTeachingMotif> uniqueMotifs(List<RecognizedTeachingMotif> motifs) {
        Map<String, RecognizedTeachingMotif> best = new LinkedHashMap<>();
        for (RecognizedTeachingMotif m : motifs) {
            RecognizedTeachingMotif cur = best.get(m.motifType);
            if (cur == null || m.score > cur.score) best.put(m.motifType, m);
        }
        List<RecognizedTeachingMotif> out = new ArrayList<>(best.values());
        out.sort((a, b) -> Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) : a.title.compareTo(b.title));
        return out;
    }

    public static List<RecognizedTeachingMotif> recognizeTeachingMotifs(MotifRecognizerQuery query) {
        MotifPhase phase = phaseFrom(query.moveNumber, query.totalMoves);
        String bestMove = normalizeMove(query.candidateMoves != null && query.candidateMoves.length > 0 ? query.candidateMoves[0] : null);
        String actualMove = normalizeMove(query.playedMove != null && query.playedMove.length > 0 ? query.playedMove[0] : null);
        Pt bestPoint = pointFromGtp(bestMove, query.boardSize);
        Pt actualPoint = pointFromGtp(actualMove, query.boardSize);
        MotifRegion region = regionOf(bestPoint != null ? bestPoint : actualPoint, query.boardSize);
        int localDistance = distance(bestPoint, actualPoint);
        String[] relatedMoves = concat(new String[]{actualMove, bestMove}, query.principalVariation != null ? Arrays.copyOf(query.principalVariation, Math.min(6, query.principalVariation.length)) : new String[0]);
        double loss = query.lossScore == null ? 0 : Double.parseDouble(query.lossScore);
        double scoreLoss = query.scoreLoss != null ? query.scoreLoss : 0;
        double spread = query.spread != null ? query.spread : 99;
        List<String[]> topMoves = new ArrayList<>(); // [winrate, score]
        String judgement = query.judgement != null ? query.judgement : "";

        String phaseSignal = "phase:" + phase;
        String regionSignal = "region:" + region;
        List<String> implied = new ArrayList<>();
        implied.add(phaseSignal); implied.add(regionSignal);
        implied.add(localDistance <= 2 ? "local_tactical_loss" : "global_direction_loss");
        if (localDistance >= 6) implied.add("tenuki_or_wrong_side");
        implied.add(loss >= 7 ? "high_winrate_loss" : loss >= 2.5 ? "medium_winrate_loss" : "small_loss");
        implied.add(scoreLoss >= 4 ? "high_score_loss" : scoreLoss >= 1.5 ? "medium_score_loss" : "small_score_loss");
        implied.add(spread < 1.5 ? "candidate_style_choice" : "clear_top_candidate");
        implied.add(judgement);
        String signalText = stringifySignals(query.text, query.contextTags, query.candidateMoves, query.principalVariation, implied.toArray(new String[0]));

        List<RecognizedTeachingMotif> cardMotifs = new ArrayList<>();
        for (JsonKnowledgeLoader.ElitePatternCard card : JsonKnowledgeLoader.loadEliteCards()) {
            MotifRecognizer.ScoreWithReasons scr = scoreEliteCard(card, query, signalText, region, phase, localDistance, loss, scoreLoss, judgement, spread);
            if (scr.score >= 8) cardMotifs.add(motifFromCard(card, scr.score, scr.reasons, region, relatedMoves));
        }

        List<RecognizedTeachingMotif> josekiMotifs = new ArrayList<>();
        JosekiRecognizer.JosekiRecognitionQuery jq = new JosekiRecognizer.JosekiRecognitionQuery();
        jq.boardSize = query.boardSize; jq.moveNumber = query.moveNumber; jq.recentMoves = query.recentMoves;
        jq.candidateMoves = query.candidateMoves; jq.principalVariation = query.principalVariation;
        jq.actualMove = actualMove; jq.bestMove = bestMove; jq.text = query.text; jq.maxResults = 4;
        for (JosekiRecognizer.RecognizedJosekiPattern p : JosekiRecognizer.recognizeJosekiPatterns(jq))
            for (RecognizedTeachingMotif m : motifsFromJoseki(p)) josekiMotifs.add(m);

        List<RecognizedTeachingMotif> heuristics = heuristicMotifs(query, phase, region, actualMove, bestMove, localDistance, loss, scoreLoss, judgement, relatedMoves);

        List<RecognizedTeachingMotif> all = new ArrayList<>();
        all.addAll(josekiMotifs); all.addAll(cardMotifs); all.addAll(heuristics);
        all.addAll(matchKnowledgeMotifs(query.knowledgeMatches, relatedMoves));
        List<RecognizedTeachingMotif> unique = uniqueMotifs(all);
        int limit = query.maxResults != null ? query.maxResults : 8;
        return unique.subList(0, Math.min(limit, unique.size()));
    }

    public static String formatRecognizedMotifsForPrompt(List<RecognizedTeachingMotif> motifs) {
        if (motifs == null || motifs.isEmpty()) return "未识别到高置信棋形。请只基于 KataGo 证据讲解。";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(6, motifs.size());
        for (int i = 0; i < n; i++) {
            RecognizedTeachingMotif m = motifs.get(i);
            sb.append((i + 1) + ". " + m.title + " (" + m.motifType + ", " + m.confidence + ", score=" + m.score + ")\n")
              .append("识别依据：" + m.whyMatched + "\n");
            if (m.sourceRefs != null && m.sourceRefs.length > 0)
                sb.append("来源标记：" + String.join(", ", m.sourceRefs) + "；sourceQuality=" + (m.sourceQuality != null ? m.sourceQuality : "unknown") + "\n");
            if (m.variationCount > 0) sb.append("定式/变化数量估计：" + m.variationCount + "\n");
            if (m.expectedNextMoves != null && !m.expectedNextMoves.isEmpty()) {
                StringBuilder en = new StringBuilder();
                for (int k = 0; k < Math.min(4, m.expectedNextMoves.size()); k++) {
                    ExpectedNextMove mv = m.expectedNextMoves.get(k);
                    en.append(mv.move).append(" ").append(mv.label).append(mv.condition != null ? "(" + mv.condition + ")" : "").append("；");
                }
                sb.append("常见下一手：" + en + "\n");
            }
            sb.append("人类讲法：" + m.recognition + "\n")
              .append("常见误区：" + m.wrongThinking + "\n")
              .append("正确思路：" + m.correctThinking + "\n")
              .append("小练习：" + m.drillPrompt + "\n\n");
        }
        return sb.toString().trim();
    }
}
