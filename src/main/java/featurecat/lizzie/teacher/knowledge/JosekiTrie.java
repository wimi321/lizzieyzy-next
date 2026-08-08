package featurecat.lizzie.teacher.knowledge;

import java.util.*;

/**
 * 对齐 GoAgent knowledge/josekiTrie.ts：把定式卡的序列（相对坐标）与查询的角落序列做前缀匹配，
 * 给出 strong/medium/weak 置信度与 safeWording。
 */
public final class JosekiTrie {

    private JosekiTrie() {}

    public enum JosekiTrieConfidence { strong, medium, weak }

    public static class JosekiTrieMatch {
        public String cardId, family;
        public JosekiRecognizer.JosekiCorner corner;
        public int prefixLength, relativeHits, colorHits, tenukiCount;
        public boolean exactOrderMatch, colorConsistent, kataGoSupportsContinuation;
        public JosekiTrieConfidence confidence;
        public double score;
        public String safeWording;
        public String[] evidence;
    }

    static class SequenceToken { Character color; String relative; }
    static class CornerMoveToken extends SequenceToken { String rawMove; }

    static List<SequenceToken[]> cardSequences(JosekiRecognizer.JosekiPatternCard card) {
        List<SequenceToken[]> out = new ArrayList<>();
        for (String v : card.variations != null ? card.variations : new ArrayList<String>()) {
            SequenceToken[] toks = parseSequenceTokens(v);
            if (toks.length >= 2) out.add(toks);
        }
        Set<String> req = new LinkedHashSet<>(Arrays.asList(card.requiredRelativeStones != null ? card.requiredRelativeStones : new String[0]));
        if (out.isEmpty() && req.size() >= 2) {
            SequenceToken[] toks = new SequenceToken[req.size()];
            int i = 0; for (String r : req) { toks[i++] = new SequenceToken(); toks[i-1].relative = r; }
            out.add(toks);
        }
        return out;
    }

    static SequenceToken[] parseSequenceTokens(String value) {
        List<SequenceToken> toks = new ArrayList<>();
        var regex = java.util.regex.Pattern.compile("\\b([BW])?\\s*(\\d{1,2}-\\d{1,2})\\b");
        var m = regex.matcher(value);
        while (m.find()) {
            SequenceToken t = new SequenceToken();
            t.color = (m.group(1) != null && (m.group(1).equals("B") || m.group(1).equals("W"))) ? m.group(1).charAt(0) : null;
            t.relative = m.group(2);
            toks.add(t);
        }
        return toks.toArray(new SequenceToken[0]);
    }

    static Map<JosekiRecognizer.JosekiCorner, List<CornerMoveToken>> queryCornerSequences(JosekiRecognizer.JosekiRecognitionQuery query) {
        Map<JosekiRecognizer.JosekiCorner, List<CornerMoveToken>> result = new EnumMap<>(JosekiRecognizer.JosekiCorner.class);
        for (JosekiRecognizer.JosekiCorner c : JosekiRecognizer.JosekiCorner.values()) result.put(c, new ArrayList<>());
        List<JosekiRecognizer.JosekiMoveLike> recent = query.recentMoves == null ? new ArrayList<>() :
            query.recentMoves.subList(Math.max(0, query.recentMoves.size() - 60), query.recentMoves.size());
        int idx = 0;
        for (JosekiRecognizer.JosekiMoveLike mv : recent) {
            JosekiRecognizer.Pt p = JosekiRecognizer.pointFromMove(mv, query.boardSize);
            if (p == null) continue;
            JosekiRecognizer.JosekiCorner corner = JosekiRecognizer.cornerOf(p, query.boardSize);
            CornerMoveToken t = new CornerMoveToken();
            t.color = colorFromMove(mv, idx);
            t.relative = JosekiRecognizer.relativePoint(p, query.boardSize);
            t.rawMove = p.gtp != null ? p.gtp : t.relative + "@" + corner;
            result.get(corner).add(t);
            idx++;
        }
        return result;
    }

    static Character colorFromMove(JosekiRecognizer.JosekiMoveLike mv, int fallbackIndex) {
        // 从 gtp 推断颜色不可靠，用交替（弱回退）
        return fallbackIndex % 2 == 0 ? 'B' : 'W';
    }

    static Set<String> normalizeRelativeMoves(String[] moves, int boardSize) {
        Set<String> out = new HashSet<>();
        for (String m : moves) {
            JosekiRecognizer.Pt p = JosekiRecognizer.pointFromGtp(m, boardSize);
            if (p != null) out.add(JosekiRecognizer.relativePoint(p, boardSize));
        }
        return out;
    }

    static class ScoreOut {
        int prefixLength, relativeHits, colorHits, tenukiCount;
        boolean exactOrderMatch, colorConsistent;
    }

    static ScoreOut scoreSequence(SequenceToken[] cardTokens, CornerMoveToken[] queryTokens) {
        ScoreOut o = new ScoreOut();
        int n = Math.min(cardTokens.length, queryTokens.length);
        for (int i = 0; i < n; i++) {
            if (!cardTokens[i].relative.equals(queryTokens[i].relative)) break;
            o.prefixLength++;
            if (cardTokens[i].color == null || queryTokens[i].color == null || cardTokens[i].color == queryTokens[i].color) o.colorHits++;
        }
        Set<String> qRel = new HashSet<>();
        for (CornerMoveToken t : queryTokens) qRel.add(t.relative);
        o.relativeHits = 0;
        for (SequenceToken t : cardTokens) if (qRel.contains(t.relative)) o.relativeHits++;
        o.tenukiCount = Math.max(0, queryTokens.length - o.prefixLength);
        o.exactOrderMatch = o.prefixLength >= Math.min(Math.min(cardTokens.length, queryTokens.length), 4);
        o.colorConsistent = o.prefixLength == 0 ? false : o.colorHits >= Math.max(1, (int) Math.floor(o.prefixLength * 0.75));
        return o;
    }

    static JosekiTrieConfidence confidenceFrom(double score, boolean exactOrderMatch, boolean colorConsistent) {
        if (score >= 22 && exactOrderMatch && colorConsistent) return JosekiTrieConfidence.strong;
        if (score >= 13) return JosekiTrieConfidence.medium;
        return JosekiTrieConfidence.weak;
    }

    static String wordingFor(JosekiTrieConfidence c, boolean exactOrderMatch, boolean kataGoSupports) {
        if (c == JosekiTrieConfidence.strong && exactOrderMatch && kataGoSupports) return "明确属于该定式族";
        if (c != JosekiTrieConfidence.weak && exactOrderMatch) return "像该定式分支";
        return "SGF 树有此前缀，但本局未必该继续";
    }

    public static List<JosekiTrieMatch> recognizeJosekiTrie(List<JosekiRecognizer.JosekiPatternCard> cards, JosekiRecognizer.JosekiRecognitionQuery query) {
        if (query.boardSize != 19 || cards.isEmpty()) return new ArrayList<>();
        Map<JosekiRecognizer.JosekiCorner, List<CornerMoveToken>> byCorner = queryCornerSequences(query);
        Set<String> continuationRelatives = normalizeRelativeMoves(
            concatArr(new String[]{query.actualMove, query.bestMove}, safe(query.candidateMoves), safe(query.principalVariation)), query.boardSize);
        List<JosekiTrieMatch> matches = new ArrayList<>();
        for (JosekiRecognizer.JosekiPatternCard card : cards) {
            List<SequenceToken[]> sequences = cardSequences(card);
            if (sequences.isEmpty()) continue;
            for (JosekiRecognizer.JosekiCorner corner : JosekiRecognizer.JosekiCorner.values()) {
                List<CornerMoveToken> queryTokens = byCorner.get(corner);
                if (queryTokens.size() < 2) continue;
                for (SequenceToken[] seq : sequences) {
                    ScoreOut sc = scoreSequence(seq, queryTokens.toArray(new CornerMoveToken[0]));
                    if (sc.prefixLength < 2 && sc.relativeHits < 3) continue;
                    SequenceToken nextToken = sc.prefixLength < seq.length ? seq[sc.prefixLength] : null;
                    boolean kataGoSupports = nextToken != null && continuationRelatives.contains(nextToken.relative);
                    double score = sc.prefixLength * 5 + sc.colorHits * 2 + sc.relativeHits * 2 + (kataGoSupports ? 5 : 0) - Math.min(6, sc.tenukiCount);
                    if (score < 8) continue;
                    JosekiTrieConfidence conf = confidenceFrom(score, sc.exactOrderMatch, sc.colorConsistent);
                    JosekiTrieMatch m = new JosekiTrieMatch();
                    m.cardId = card.id; m.family = card.family; m.corner = corner;
                    m.prefixLength = sc.prefixLength; m.relativeHits = sc.relativeHits; m.colorHits = sc.colorHits;
                    m.tenukiCount = sc.tenukiCount; m.exactOrderMatch = sc.exactOrderMatch; m.colorConsistent = sc.colorConsistent;
                    m.kataGoSupportsContinuation = kataGoSupports; m.confidence = conf; m.score = Math.round(score * 10) / 10.0;
                    m.safeWording = wordingFor(conf, sc.exactOrderMatch, kataGoSupports);
                    m.evidence = new String[]{"sequencePrefix=" + sc.prefixLength, "relativeHits=" + sc.relativeHits,
                        "colorHits=" + sc.colorHits, "tenukiCount=" + sc.tenukiCount, "kataGoSupportsContinuation=" + kataGoSupports};
                    matches.add(m);
                }
            }
        }
        matches.sort((a, b) -> Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) :
            Integer.compare(b.prefixLength, a.prefixLength) != 0 ? Integer.compare(b.prefixLength, a.prefixLength) :
            a.cardId.compareTo(b.cardId));
        int limit = query.maxResults != null ? query.maxResults : 8;
        return matches.subList(0, Math.min(limit, matches.size()));
    }

    static String[] concatArr(String[] a, String[]... rest) {
    List<String> l = new ArrayList<>();
    if (a != null) for (String x : a) if (x != null) l.add(x);
    for (String[] r : rest) if (r != null) for (String x : r) if (x != null) l.add(x);
    return l.toArray(new String[0]);
  }
  static String[] safe(String[] a) { return a == null ? new String[0] : a; }
    static String[] concat(String... a) { List<String> l = new ArrayList<>(); for (String s : a) if (s != null) l.add(s); return l.toArray(new String[0]); }

    /** 对齐 TS josekiTrieMatchSummary */
    public static String josekiTrieMatchSummary(JosekiTrieMatch match) {
        return match.safeWording + "：prefix=" + match.prefixLength + "，颜色一致=" + (match.colorConsistent ? "是" : "否") + "，KataGo续手支持=" + (match.kataGoSupportsContinuation ? "是" : "否") + "。";
    }
}
