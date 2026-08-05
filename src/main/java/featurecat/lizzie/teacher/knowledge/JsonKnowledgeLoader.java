package featurecat.lizzie.teacher.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 对齐 GoAgent knowledge 各 JSON 卡加载（joseki-pattern-cards / elite-pattern-cards* / pattern-cards），
 * 使用内嵌轻量 JSON 解析（不引入外部依赖）。
 */
public final class JsonKnowledgeLoader {

    public JsonKnowledgeLoader() {}

    static String readResource(String path) {
        try (InputStream is = JsonKnowledgeLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { return null; }
    }

    // ---- 极简 JSON 解析（仅支持本工程需要的数组/对象/字符串/数字/布尔/null）----
    public static Object parse(String json) { return new Parser(json).parseValue(); }

    static class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; this.i = 0; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object parseValue() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBool();
            if (c == 'n') { i += 4; return null; }
            return parseNumber();
        }
        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); if (s.charAt(i) != '"') break;
                String key = parseString();
                ws(); i++; // :
                Object val = parseValue();
                m.put(key, val);
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }
        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(parseValue());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return list;
        }
        String parseString() {
            i++; // "
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) { char e = s.charAt(i++); sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e); }
                else sb.append(c);
            }
            return sb.toString();
        }
        Object parseNumber() {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-' || s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            return s.substring(start, i);
        }
        boolean parseBool() {
            if (s.startsWith("true", i)) { i += 4; return true; }
            i += 5; return false;
        }
    }

    @SuppressWarnings("unchecked")
    static String asStr(Object o) { return o == null ? null : o.toString(); }
    @SuppressWarnings("unchecked")
    static List<String> asStrList(Object o) {
        if (!(o instanceof List)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object x : (List<?>) o) if (x != null) out.add(x.toString());
        return out;
    }
    static String[] asStrArray(Object o) {
        if (o == null) return new String[0];
        if (o instanceof List) { List<Object> l = (List<Object>) o; String[] out = new String[l.size()]; for (int i = 0; i < l.size(); i++) out[i] = l.get(i) == null ? null : l.get(i).toString(); return out; }
        return new String[]{ o.toString() };
    }

    // ---- joseki-pattern-cards.json → JosekiPatternCard ----
    public static List<JosekiRecognizer.JosekiPatternCard> loadJosekiPatternCards() {
        List<JosekiRecognizer.JosekiPatternCard> out = new ArrayList<>();
        String json = readResource("knowledge/joseki-pattern-cards.json");
        if (json == null) return out;
        Object parsed = parse(json);
        if (!(parsed instanceof List)) return out;
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            JosekiRecognizer.JosekiPatternCard card = new JosekiRecognizer.JosekiPatternCard();
            card.id = asStr(m.get("id"));
            card.name = asStr(m.get("name"));
            card.family = asStr(m.get("family"));
            Object bs = m.get("boardSize"); card.boardSize = bs == null ? 19 : Integer.parseInt(bs.toString());
            card.sourceRefs = asStrArray(m.get("sourceRefs"));
            card.sourceQuality = asStr(m.get("sourceQuality"));
            card.requiredRelativeStones = asStrArray(m.get("requiredRelativeStones"));
            card.sequenceSignals = asStrArray(m.get("sequenceSignals"));
            Object vc = m.get("variationCount"); card.variationCount = vc == null ? 0 : Integer.parseInt(vc.toString());
            card.recognition = asStr(m.get("recognition"));
            card.wrongThinking = asStr(m.get("wrongThinking"));
            card.correctThinking = asStr(m.get("correctThinking"));
            card.drillPrompt = asStr(m.get("drillPrompt"));
            Object cnm = m.get("commonNextMoves");
            if (cnm instanceof List) for (Object o : (List<?>) cnm) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> nm = (Map<String, Object>) o;
                JosekiRecognizer.JosekiNextMove mv = new JosekiRecognizer.JosekiNextMove();
                mv.relativeMove = asStr(nm.get("relativeMove"));
                mv.label = asStr(nm.get("label"));
                mv.condition = asStr(nm.get("condition"));
                card.commonNextMoves.add(mv);
            }
            Object vars = m.get("variations");
            if (vars instanceof List) for (Object o : (List<?>) vars) card.variations.add(o == null ? "" : o.toString());
            out.add(card);
        }
        return out;
    }

    // ---- elite-pattern-cards*.json → ElitePatternCard ----
    public static class ElitePatternCard {
        public String id, title, category, patternType, scope;
        public String[] phase, regions, levels, tags, aliases, triggerSignals, negativeSignals, katagoSignals, sourceRefs;
        public String recognition, wrongThinking, correctThinking, drillPrompt, sourceQuality;
        public double confidenceBoost;
    }

    public static List<ElitePatternCard> loadEliteCards() {
        List<ElitePatternCard> out = new ArrayList<>();
        String[] files = {"elite-pattern-cards.json","elite-pattern-cards-v4.json","elite-pattern-cards-v6.json",
            "elite-pattern-cards-v7.json","elite-pattern-cards-v8.json","elite-pattern-cards-v9.json",
            "elite-pattern-cards-v10.json","elite-pattern-cards-v11.json"};
        for (String f : files) {
            String json = readResource("knowledge/" + f);
            if (json == null) continue;
            Object parsed = parse(json);
            if (!(parsed instanceof List)) continue;
            for (Object item : (List<?>) parsed) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                ElitePatternCard c = new ElitePatternCard();
                c.id = asStr(m.get("id")); c.title = asStr(m.get("title")); c.category = asStr(m.get("category"));
                c.patternType = asStr(m.get("patternType")); c.scope = asStr(m.get("scope"));
                c.phase = asStrArray(m.get("phase")); c.regions = asStrArray(m.get("regions"));
                c.levels = asStrArray(m.get("levels")); c.tags = asStrArray(m.get("tags")); c.aliases = asStrArray(m.get("aliases"));
                c.triggerSignals = asStrArray(m.get("triggerSignals")); c.negativeSignals = asStrArray(m.get("negativeSignals"));
                c.katagoSignals = asStrArray(m.get("katagoSignals")); c.sourceRefs = asStrArray(m.get("sourceRefs"));
                c.recognition = asStr(m.get("recognition")); c.wrongThinking = asStr(m.get("wrongThinking"));
                c.correctThinking = asStr(m.get("correctThinking")); c.drillPrompt = asStr(m.get("drillPrompt"));
                c.sourceQuality = asStr(m.get("sourceQuality"));
                Object cb = m.get("confidenceBoost"); c.confidenceBoost = cb == null ? 0 : Double.parseDouble(cb.toString());
                out.add(c);
            }
        }
        return out;
    }

    /** 加载 training-catalog.json 的 lifeDeath/tesuji 题，转成 LocalShapeGeometryMatcher.ProblemEntry */
    public static List<LocalShapeGeometryMatcher.ProblemEntry> loadTrainingProblems() {
        List<LocalShapeGeometryMatcher.ProblemEntry> out = new ArrayList<>();
        Object root = parse(readResource("knowledge/training-catalog.json"));
        if (!(root instanceof Map)) return out;
        Map<?, ?> m = (Map<?, ?>) root;
        for (String key : new String[]{"lifeDeathProblems", "tesujiProblems"}) {
            Object arr = m.get(key);
            if (!(arr instanceof List)) continue;
            for (Object item : (List<?>) arr) {
                if (!(item instanceof Map)) continue;
                Map<?, ?> prob = (Map<?, ?>) item;
                LocalShapeGeometryMatcher.ProblemEntry pe = new LocalShapeGeometryMatcher.ProblemEntry();
                pe.type = key;
                pe.problemKind = "lifeDeathProblems".equals(key) ? "life_death" : "tesuji";
                pe.id = asStr(prob.get("id")); pe.title = asStr(prob.get("title"));
                pe.region = asStr(prob.get("region")); pe.difficulty = asStr(prob.get("difficulty"));
                pe.objective = asStr(prob.get("objective")); pe.sourceKind = asStr(prob.get("sourceKind"));
                pe.tags = asStrList(prob.get("tags"));
                Map<?,?> teaching = asMap(prob.get("teaching"));
                if (teaching != null) {
                    pe.teachingRecognition = asStr(teaching.get("recognition"));
                    pe.teachingTesujiIdea = asStr(teaching.get("tesujiIdea"));
                    pe.teachingExplanation = asStr(teaching.get("explanation"));
                    pe.teachingMemoryCue = asStr(teaching.get("memoryCue"));
                    pe.teachingFirstFeeling = asStr(teaching.get("firstFeeling"));
                    pe.teachingFirstHint = asStr(teaching.get("firstHint"));
                    pe.teachingFailureExplanation = asStr(teaching.get("failureExplanation"));
                }
                pe.patternCardIds = asStrList(prob.get("patternCardIds"));
                Object init = prob.get("initialStones");
                if (init instanceof List) for (Object st : (List<?>) init) {
                    if (st instanceof Map) { Map<?,?> sm = (Map<?,?>) st;
                        LocalPatternMatcher.BoardSnapshotStone bs = new LocalPatternMatcher.BoardSnapshotStone();
                        bs.point = sm.get("point") != null ? sm.get("point").toString() : (sm.get("gtp") != null ? sm.get("gtp").toString() : null);
                        bs.color = sm.get("color") != null ? sm.get("color").toString() : "B";
                        if (bs.point != null) pe.initialStones.add(bs);
                    }
                }
                Object cm = prob.get("correctMoves");
                if (cm instanceof List) for (Object mv : (List<?>) cm) {
                    LocalShapeGeometryMatcher.TrainingMove tm = new LocalShapeGeometryMatcher.TrainingMove();
                    if (mv instanceof Map) { Map<?,?> mm = (Map<?,?>) mv; tm.move = mm.get("move") != null ? mm.get("move").toString() : (mm.get("gtp") != null ? mm.get("gtp").toString() : null); }
                    else tm.move = mv != null ? mv.toString() : null;
                    if (tm.move != null) pe.correctMoves.add(tm);
                }
                Object fm = prob.get("failureMoves");
                if (fm instanceof List) for (Object mv : (List<?>) fm) {
                    LocalShapeGeometryMatcher.TrainingMove tm = new LocalShapeGeometryMatcher.TrainingMove();
                    if (mv instanceof Map) { Map<?,?> mm = (Map<?,?>) mv; tm.move = asStr(mm.get("move")); tm.why = asStr(mm.get("why")); }
                    else tm.move = mv != null ? mv.toString() : null;
                    if (tm.move != null) pe.failureMoves.add(tm);
                }
                if (!pe.initialStones.isEmpty()) out.add(pe);
            }
        }
        return out;
    }

    private static Map<?,?> asMap(Object o) { return o instanceof Map ? (Map<?,?>) o : null; }
    private static double asNum(Object o) { if (o instanceof Number) return ((Number) o).doubleValue(); if (o instanceof String) try { return Double.parseDouble((String) o); } catch (Exception e) {} return 0; }

    public static class JosekiLineEntry {
        public String id, title, family, katagoEraJudgement, sourceKind;
        public List<String> phase = new ArrayList<>(), levels = new ArrayList<>(), tags = new ArrayList<>(), normalizedFeatures = new ArrayList<>(), relativeSequence = new ArrayList<>(), decisionRules = new ArrayList<>(), commonMistakes = new ArrayList<>(), trainingFocus = new ArrayList<>(), patternCardIds = new ArrayList<>();
        public List<Branch> branches = new ArrayList<>();
        public static class Branch { public String name, whenToChoose, warning; }
    }

    public static List<JosekiLineEntry> loadJosekiLines() {
        List<JosekiLineEntry> out = new ArrayList<>();
        Object root = parse(readResource("knowledge/training-catalog.json"));
        if (!(root instanceof Map)) return out;
        Object arr = ((Map<?,?>) root).get("josekiLines");
        if (!(arr instanceof List)) return out;
        for (Object item : (List<?>) arr) {
            if (!(item instanceof Map)) continue;
            Map<?,?> m = (Map<?,?>) item;
            JosekiLineEntry e = new JosekiLineEntry();
            e.id = asStr(m.get("id")); e.title = asStr(m.get("title")); e.family = asStr(m.get("family"));
            e.katagoEraJudgement = asStr(m.get("katagoEraJudgement")); e.sourceKind = asStr(m.get("sourceKind"));
            e.phase = asStrList(m.get("phase")); e.levels = asStrList(m.get("levels")); e.tags = asStrList(m.get("tags"));
            e.normalizedFeatures = asStrList(m.get("normalizedFeatures")); e.relativeSequence = asStrList(m.get("relativeSequence"));
            e.decisionRules = asStrList(m.get("decisionRules")); e.commonMistakes = asStrList(m.get("commonMistakes")); e.trainingFocus = asStrList(m.get("trainingFocus"));
            e.patternCardIds = asStrList(m.get("patternCardIds"));
            Object br = m.get("branches");
            if (br instanceof List) for (Object b : (List<?>) br) { if (b instanceof Map) { Map<?,?> bm = (Map<?,?>) b; JosekiLineEntry.Branch bb = new JosekiLineEntry.Branch(); bb.name = asStr(bm.get("name")); bb.whenToChoose = asStr(bm.get("whenToChoose")); bb.warning = asStr(bm.get("warning")); e.branches.add(bb); } }
            out.add(e);
        }
        return out;
    }

    public static List<LocalPatternMatcher.ShapePatternCard> loadShapePatternCards() {
        List<LocalPatternMatcher.ShapePatternCard> out = new ArrayList<>();
        Object root = parse(readResource("knowledge/shape-pattern-cards-v1.json"));
        if (root instanceof List) for (Object item : (List<?>) root) { if (item instanceof Map) out.add(parseShapeCard((Map<?,?>) item)); }
        else if (root instanceof Map) { Object cards = ((Map<?,?>) root).get("cards"); if (cards instanceof List) for (Object item : (List<?>) cards) { if (item instanceof Map) out.add(parseShapeCard((Map<?,?>) item)); } }
        return out;
    }
    private static LocalPatternMatcher.ShapePatternCard parseShapeCard(Map<?,?> m) {
        LocalPatternMatcher.ShapePatternCard c = new LocalPatternMatcher.ShapePatternCard();
        c.id = asStr(m.get("id")); c.title = asStr(m.get("title")); c.shapeType = asStr(m.get("shapeType")); c.category = asStr(m.get("category"));
        c.anchorRole = asStr(m.get("anchorRole")); c.sourceQuality = asStr(m.get("sourceQuality"));
        c.minScore = m.get("minScore") instanceof Number ? ((Number) m.get("minScore")).doubleValue() : null;
        c.phase = asStrList(m.get("phase")); c.regions = asStrList(m.get("regions")); c.tags = asStrList(m.get("tags")); c.sourceRefs = asStrList(m.get("sourceRefs"));
        Object pts = m.get("points");
        if (pts instanceof List) for (Object p : (List<?>) pts) { if (p instanceof Map) { Map<?,?> pm = (Map<?,?>) p; LocalPatternMatcher.LocalPatternPoint lp = new LocalPatternMatcher.LocalPatternPoint(); lp.dx = (int) asNum(pm.get("dx")); lp.dy = (int) asNum(pm.get("dy")); lp.state = LocalPatternMatcher.LocalPatternPointState.valueOf(asStr(pm.get("state"))); lp.required = pm.get("required") instanceof Boolean ? (Boolean) pm.get("required") : true; c.points.add(lp); } }
        Object cons = m.get("constraints");
        if (cons instanceof List) for (Object cn : (List<?>) cons) { if (cn instanceof Map) { Map<?,?> cm = (Map<?,?>) cn; LocalPatternMatcher.LocalPatternConstraint lc = new LocalPatternMatcher.LocalPatternConstraint(); lc.type = asStr(cm.get("type")); lc.value = (int) asNum(cm.get("value")); c.constraints.add(lc); } }
        Object anti = m.get("antiPatterns");
        if (anti instanceof List) for (Object a : (List<?>) anti) { if (a instanceof Map) { Map<?,?> am = (Map<?,?>) a; LocalPatternMatcher.LocalPatternPoint lp = new LocalPatternMatcher.LocalPatternPoint(); lp.dx = (int) asNum(am.get("dx")); lp.dy = (int) asNum(am.get("dy")); lp.state = LocalPatternMatcher.LocalPatternPointState.valueOf(asStr(am.get("state"))); c.antiPatterns.add(lp); } }
        Object t = m.get("teaching");
        if (t instanceof Map) { Map<?,?> tm = (Map<?,?>) t; c.teaching = new LocalPatternMatcher.Teaching(); c.teaching.recognition = asStr(tm.get("recognition")); c.teaching.wrongThinking = asStr(tm.get("wrongThinking")); c.teaching.correctThinking = asStr(tm.get("correctThinking")); c.teaching.drillPrompt = asStr(tm.get("drillPrompt")); }
        return c;
    }
}
