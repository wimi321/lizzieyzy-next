package featurecat.lizzie.teacher.knowledge;

import java.util.*;

/**
 * 对齐 GoAgent knowledge/josekiRecognizer.ts + josekiTrie.ts：
 * 定式卡数据模型 + 识别（相对坐标/角落/文本信号/Trie 序列匹配）。
 */
public final class JosekiRecognizer {

    private JosekiRecognizer() {}

    public enum JosekiConfidence { strong, medium, weak }
    public enum JosekiCorner { SW, SE, NW, NE }

    public static class JosekiMoveLike {
        public Integer row, col;
        public String gtp;
    }

    public static class JosekiNextMove {
        public String relativeMove;
        public String gtpMove;
        public String label;
        public String condition;
    }

    public static class JosekiPatternCard {
        public String id, name, family;
        public Integer boardSize;
        public String[] sourceRefs;
        public String sourceQuality;
        public String[] requiredRelativeStones;
        public String[] sequenceSignals;
        public int variationCount;
        public List<JosekiNextMove> commonNextMoves = new ArrayList<>();
        public List<String> variations = new ArrayList<>();
        public String recognition, wrongThinking, correctThinking, drillPrompt;
    }

    public static class RecognizedJosekiPattern {
        public String id, name, family;
        public JosekiConfidence confidence;
        public double score;
        public JosekiCorner matchedCorner;
        public String[] matchedRelativeStones;
        public String[] evidence;
        public String[] sourceRefs;
        public String sourceQuality;
        public int variationCount;
        public List<JosekiNextMove> commonNextMoves = new ArrayList<>();
        public List<String> variations = new ArrayList<>();
        public String recognition, wrongThinking, correctThinking, drillPrompt;
    }

    public static class JosekiRecognitionQuery {
        public int boardSize;
        public int moveNumber;
        public List<JosekiMoveLike> recentMoves;
        public String[] candidateMoves;
        public String[] principalVariation;
        public String actualMove, bestMove, text;
        public Integer maxResults;
    }

    static final String GTP_COLUMNS = "ABCDEFGHJKLMNOPQRSTUVWXYZ";

    static String normalizeMove(String m) {
        if (m == null) return null;
        String t = m.trim().toUpperCase();
        if (t.isEmpty() || t.equals("PASS")) return null;
        return t;
    }

    static class Pt { int x, y; String gtp; }

    static Pt pointFromGtp(String m, int boardSize) {
        String n = normalizeMove(m);
        if (n == null) return null;
        var mt = java.util.regex.Pattern.compile("^([A-HJ-Z])(\\d{1,2})$").matcher(n);
        if (!mt.find()) return null;
        int x = GTP_COLUMNS.substring(0, boardSize).indexOf(mt.group(1));
        int y = Integer.parseInt(mt.group(2)) - 1;
        if (x < 0 || y < 0 || y >= boardSize) return null;
        Pt p = new Pt(); p.x = x; p.y = y; p.gtp = n;
        return p;
    }

    static Pt pointFromMove(JosekiMoveLike m, int boardSize) {
        Pt p = pointFromGtp(m.gtp, boardSize);
        if (p != null) return p;
        if (m.col != null && m.row != null) {
            int x = m.col, y = boardSize - 1 - m.row;
            if (x >= 0 && x < boardSize && y >= 0 && y < boardSize) { Pt q = new Pt(); q.x = x; q.y = y; return q; }
        }
        return null;
    }

    static JosekiCorner cornerOf(Pt p, int boardSize) {
        boolean east = p.x >= boardSize / 2, north = p.y >= boardSize / 2;
        if (!east && !north) return JosekiCorner.SW;
        if (east && !north) return JosekiCorner.SE;
        if (!east && north) return JosekiCorner.NW;
        return JosekiCorner.NE;
    }

    static String relativePoint(Pt p, int boardSize) {
        int rx = Math.min(p.x + 1, boardSize - p.x);
        int ry = Math.min(p.y + 1, boardSize - p.y);
        return rx + "-" + ry;
    }

    static String gtpFromRelative(String relative, JosekiCorner corner, int boardSize) {
        var mt = java.util.regex.Pattern.compile("^(\\d{1,2})-(\\d{1,2})$").matcher(relative);
        if (!mt.find()) return null;
        int rx = Integer.parseInt(mt.group(1)), ry = Integer.parseInt(mt.group(2));
        if (rx < 1 || ry < 1 || rx > boardSize || ry > boardSize) return null;
        int x = (corner == JosekiCorner.SE || corner == JosekiCorner.NE) ? boardSize - rx : rx - 1;
        int y = (corner == JosekiCorner.NW || corner == JosekiCorner.NE) ? boardSize - ry : ry - 1;
        if (x < 0 || x >= boardSize || y < 0 || y >= boardSize) return null;
        return GTP_COLUMNS.charAt(x) + "" + (y + 1);
    }

    private static final List<JosekiPatternCard> cachedCards = new ArrayList<>();
    private static boolean loaded = false;

    static List<JosekiPatternCard> loadJosekiCards() {
        if (loaded) return cachedCards;
        cachedCards.addAll(JosekiSgfDatabase.loadBundledJosekiSgfCards());
        // curated json（joseki-pattern-cards.json）如有则合并
        List<JosekiPatternCard> curated = JsonKnowledgeLoader.loadJosekiPatternCards();
        Set<String> seen = new HashSet<>();
        for (JosekiPatternCard c : cachedCards) seen.add(c.id);
        for (JosekiPatternCard c : curated) if (!seen.contains(c.id)) cachedCards.add(c);
        loaded = true;
        return cachedCards;
    }

    static int confidenceScore(double score) {
        if (score >= 16) return 2; // strong
        if (score >= 11) return 1; // medium
        return 0; // weak
    }

    static String[] textHit(String text, String[] signals) {
        if (text == null) return new String[0];
        String lower = text.toLowerCase();
        List<String> hits = new ArrayList<>();
        for (String s : signals) if (s != null && !s.isEmpty() && lower.contains(s.toLowerCase())) hits.add(s);
        return hits.toArray(new String[0]);
    }

    static class ScoreResult { double score; List<String> evidence = new ArrayList<>(); List<JosekiNextMove> nextMoves = new ArrayList<>(); }

    static ScoreResult scoreCard(JosekiPatternCard card, JosekiRecognitionQuery query,
                                 JosekiCorner corner, Set<String> relativeSet, Set<String> moveSet, String signalText) {
        ScoreResult r = new ScoreResult();
        List<String> requiredHits = new ArrayList<>();
        for (String s : card.requiredRelativeStones) if (relativeSet.contains(s)) requiredHits.add(s);
        if (!requiredHits.isEmpty()) {
            r.score += requiredHits.size() * 4;
            r.evidence.add("relative stones: " + String.join(", ", requiredHits));
        }
        if (requiredHits.size() == card.requiredRelativeStones.length && card.requiredRelativeStones.length > 1) {
            r.score += 5; r.evidence.add("all required local stones found");
        }
        String[] signals = textHit(signalText, concatArr(new String[]{card.name, card.family}, card.sequenceSignals));
        if (signals.length > 0) {
            r.score += Math.min(6, signals.length * 2);
            r.evidence.add("text/signal hits: " + String.join(", ", Arrays.copyOf(signals, Math.min(3, signals.length))));
        }
        for (JosekiNextMove mv : card.commonNextMoves) {
            mv.gtpMove = (mv.relativeMove != null && mv.relativeMove.contains("-")) ? gtpFromRelative(mv.relativeMove, corner, query.boardSize) : null;
        }
        JosekiNextMove nextHit = null;
        for (JosekiNextMove mv : card.commonNextMoves) if (mv.gtpMove != null && moveSet.contains(mv.gtpMove)) { nextHit = mv; break; }
        if (nextHit != null) { r.score += 4; r.evidence.add("candidate/PV matches common next move " + nextHit.gtpMove); }
        if (query.moveNumber <= 80) r.score += 2;
        else if (query.moveNumber <= 120) r.score += 1;
        else r.score -= 2;
        if (card.requiredRelativeStones.length <= 1 && signals.length == 0 && nextHit == null) r.score -= 4;
        r.nextMoves = card.commonNextMoves;
        return r;
    }

    static String[] concatArr(String[]... arrs) {
        List<String> out = new ArrayList<>();
        for (String[] a : arrs) if (a != null) for (String x : a) if (x != null) out.add(x);
        return out.toArray(new String[0]);
    }

    public static List<RecognizedJosekiPattern> recognizeJosekiPatterns(JosekiRecognitionQuery query) {
        if (query.boardSize != 19) return new ArrayList<>();
        List<JosekiPatternCard> cards = loadJosekiCards();
        if (cards.isEmpty()) return new ArrayList<>();
        List<JosekiMoveLike> recent = query.recentMoves == null ? new ArrayList<>() :
            query.recentMoves.subList(Math.max(0, query.recentMoves.size() - 40), query.recentMoves.size());
        Map<JosekiCorner, Set<String>> corners = new EnumMap<>(JosekiCorner.class);
        Map<JosekiCorner, List<String>> rawCornerMoves = new EnumMap<>(JosekiCorner.class);
        for (JosekiCorner c : JosekiCorner.values()) { corners.put(c, new HashSet<>()); rawCornerMoves.put(c, new ArrayList<>()); }
        Set<String> seen = new HashSet<>();
        for (JosekiMoveLike mv : recent) {
            Pt p = pointFromMove(mv, query.boardSize);
            if (p == null) continue;
            String key = p.x + "," + p.y;
            if (seen.contains(key)) continue;
            seen.add(key);
            JosekiCorner corner = cornerOf(p, query.boardSize);
            String rel = relativePoint(p, query.boardSize);
            corners.get(corner).add(rel);
            rawCornerMoves.get(corner).add(p.gtp != null ? p.gtp : rel + "@" + corner);
        }
        Set<String> moveSet = new HashSet<>();
        for (String m : new String[]{query.actualMove, query.bestMove}) {
            String n = normalizeMove(m); if (n != null) moveSet.add(n);
        }
        for (String m : safeArr(query.candidateMoves)) { String n = normalizeMove(m); if (n != null) moveSet.add(n); }
        for (String m : safeArr(query.principalVariation)) { String n = normalizeMove(m); if (n != null) moveSet.add(n); }
        List<JosekiTrie.JosekiTrieMatch> trieMatches = JosekiTrie.recognizeJosekiTrie(cards, query);
        String signalText = String.join(" | ", safeArr(nonNull(query.text),
            new String[]{ String.join(" ", safeArr(query.candidateMoves)) },
            new String[]{ String.join(" ", safeArr(query.principalVariation)) }));
        List<RecognizedJosekiPattern> results = new ArrayList<>();
        for (JosekiPatternCard card : cards) {
            if (card.boardSize != null && card.boardSize != query.boardSize) continue;
            for (JosekiCorner corner : JosekiCorner.values()) {
                ScoreResult sr = scoreCard(card, query, corner, corners.get(corner), moveSet, signalText);
                JosekiTrie.JosekiTrieMatch trieMatch = null;
                for (JosekiTrie.JosekiTrieMatch tm : trieMatches)
                    if (tm.cardId.equals(card.id) && tm.corner == corner) { trieMatch = tm; break; }
                if (trieMatch != null) {
                    sr.score += trieMatch.confidence == JosekiTrie.JosekiTrieConfidence.strong ? 6 :
                               trieMatch.confidence == JosekiTrie.JosekiTrieConfidence.medium ? 3 : 1;
                    sr.evidence.add("sequence-trie:" + trieMatch.safeWording);
                    for (int i = 0; i < Math.min(2, trieMatch.evidence.length); i++) sr.evidence.add(trieMatch.evidence[i]);
                }
                if (sr.score < 9) continue;
                RecognizedJosekiPattern pat = new RecognizedJosekiPattern();
                pat.id = card.id; pat.name = card.name; pat.family = card.family;
                pat.confidence = confidenceScore(sr.score) == 2 ? JosekiConfidence.strong :
                                 confidenceScore(sr.score) == 1 ? JosekiConfidence.medium : JosekiConfidence.weak;
                pat.score = Math.round(sr.score * 10) / 10.0;
                pat.matchedCorner = corner;
                pat.matchedRelativeStones = sorted(corners.get(corner));
                List<String> ev = new ArrayList<>(sr.evidence);
                ev.add("corner=" + corner);
                ev.add("cornerMoves=" + String.join(", ", rawCornerMoves.get(corner).subList(0, Math.min(8, rawCornerMoves.get(corner).size()))));
                if (ev.size() > 10) ev = new ArrayList<>(ev.subList(0, 10));
                pat.evidence = ev.toArray(new String[0]);
                pat.sourceRefs = card.sourceRefs != null ? card.sourceRefs : new String[]{"goagent-curated-original"};
                pat.sourceQuality = card.sourceQuality != null ? card.sourceQuality : "curated";
                pat.variationCount = card.variationCount;
                pat.commonNextMoves = sr.nextMoves;
                pat.variations = card.variations;
                pat.recognition = card.recognition;
                pat.wrongThinking = card.wrongThinking;
                pat.correctThinking = card.correctThinking;
                pat.drillPrompt = card.drillPrompt;
                results.add(pat);
            }
        }
        results.sort((a, b) -> Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) :
            Integer.compare(b.variationCount, a.variationCount) != 0 ? Integer.compare(b.variationCount, a.variationCount) :
            a.name.compareTo(b.name));
        int limit = query.maxResults != null ? query.maxResults : 4;
        return results.subList(0, Math.min(limit, results.size()));
    }

    static String[] sorted(Set<String> s) { List<String> l = new ArrayList<>(s); Collections.sort(l); return l.toArray(new String[0]); }
    static String[] safeArr(String[]... arrs) { List<String> l = new ArrayList<>(); for (String[] a : arrs) if (a != null) for (String x : a) if (x != null) l.add(x); return l.toArray(new String[0]); }
    static String[] nonNull(String... a) { List<String> l = new ArrayList<>(); for (String s : a) if (s != null) l.add(s); return l.toArray(new String[0]); }

    public static String formatJosekiPatternsForPrompt(List<RecognizedJosekiPattern> patterns) {
        if (patterns == null || patterns.isEmpty()) return "未识别到高置信定式族。不要主动给出定式名称。";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(4, patterns.size());
        for (int idx = 0; idx < n; idx++) {
            RecognizedJosekiPattern p = patterns.get(idx);
            StringBuilder next = new StringBuilder();
            for (int k = 0; k < Math.min(4, p.commonNextMoves.size()); k++) {
                JosekiNextMove mv = p.commonNextMoves.get(k);
                next.append(mv.gtpMove != null ? mv.gtpMove : mv.relativeMove).append(": ").append(mv.label)
                    .append(mv.condition != null ? "（" + mv.condition + "）" : "").append("；");
            }
            sb.append((idx + 1) + ". " + p.name + " (" + p.family + ", " + p.confidence + ", score=" + p.score + ")\n")
              .append("角落：" + p.matchedCorner + "；变化数量估计：" + p.variationCount + "\n")
              .append("识别依据：" + String.join("；", p.evidence) + "\n")
              .append("常见下一手/分支：" + (next.length() > 0 ? next : "需以 KataGo 候选为准") + "\n")
              .append("教学说明：" + p.recognition + "\n")
              .append("来源标记：" + String.join(", ", p.sourceRefs) + "；sourceQuality=" + p.sourceQuality + "\n\n");
        }
        return sb.toString().trim();
    }
}
