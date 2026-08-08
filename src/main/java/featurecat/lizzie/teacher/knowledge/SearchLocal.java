package featurecat.lizzie.teacher.knowledge;

import featurecat.lizzie.teacher.knowledge.JsonKnowledgeLoader.JosekiLineEntry;
import featurecat.lizzie.teacher.knowledge.LocalPatternMatcher.ShapePatternCard;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 对齐 GoAgent knowledge/searchLocal.ts：知识卡本地文本/标签/阶段搜索（lizzieyzy 用 resources 卡） */
public final class SearchLocal {

    private SearchLocal() {}

    public static class KnowledgeSearchQuery {
        public String text; public List<String> errorTypes = new ArrayList<>(), tags = new ArrayList<>();
        public String phase; public int limit = 4;
    }
    public static class KnowledgeSearchResult {
        public Object card; public double score; public List<String> reasons = new ArrayList<>();
    }

    public static class KnowledgeCard {
        public String id, title, kind, summary, coachShort, coachLong, drill;
        public List<String> phase = new ArrayList<>(), errorTypes = new ArrayList<>(), tags = new ArrayList<>(), katagoSignals = new ArrayList<>(), boardSignals = new ArrayList<>(), related = new ArrayList<>();
    }

    private static List<KnowledgeCard> cachedP0;
    /** 对齐 TS loadKnowledgeCards：读 resources/knowledge/p0-cards.json */
    public static List<KnowledgeCard> loadKnowledgeCards() {
        if (cachedP0 != null) return cachedP0;
        List<KnowledgeCard> out = new ArrayList<>();
        try (InputStream in = SearchLocal.class.getResourceAsStream("/knowledge/p0-cards.json")) {
            if (in == null) { cachedP0 = out; return out; }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object root = JsonKnowledgeLoader.parse(text);
            if (root instanceof List) for (Object item : (List<?>) root) if (item instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) item;
                KnowledgeCard c = new KnowledgeCard();
                c.id = str(m.get("id")); c.title = str(m.get("title")); c.kind = str(m.get("kind"));
                c.summary = str(m.get("summary")); c.coachShort = str(m.get("coachShort")); c.coachLong = str(m.get("coachLong")); c.drill = str(m.get("drill"));
                c.phase = strList(m.get("phase")); c.errorTypes = strList(m.get("errorTypes")); c.tags = strList(m.get("tags"));
                c.katagoSignals = strList(m.get("katagoSignals")); c.boardSignals = strList(m.get("boardSignals")); c.related = strList(m.get("related"));
                out.add(c);
            }
        } catch (Exception e) { /* ignore */ }
        // 对齐 TS loadKnowledgeCards：合并 pattern 卡（patternToKnowledgeCard）
        try {
            java.util.List<PatternSearchEngine.KnowledgePatternCard> pCards = PatternSearchEngine.loadKnowledgePatternCards();
            for (PatternSearchEngine.KnowledgePatternCard pc : pCards) out.add(patternToKnowledgeCard(pc));
        } catch (Exception e) { /* ignore */ }
        cachedP0 = out;
        return out;
    }

    /** 对齐 TS patternToKnowledgeCard：KnowledgePatternCard → KnowledgeCard */
    static KnowledgeCard patternToKnowledgeCard(PatternSearchEngine.KnowledgePatternCard card) {
        KnowledgeCard c = new KnowledgeCard();
        c.id = card.id; c.title = card.title;
        c.kind = "joseki".equals(card.category) ? "joseki" : "life_death".equals(card.category) ? "life_death" : "shape";
        c.phase = card.phase != null ? new ArrayList<>(card.phase) : new ArrayList<>();
        if ("joseki".equals(card.category)) c.errorTypes = new ArrayList<>(java.util.Arrays.asList("direction"));
        else if ("life_death".equals(card.category)) c.errorTypes = new ArrayList<>(java.util.Arrays.asList("life-death", "reading"));
        else c.errorTypes = new ArrayList<>(java.util.Arrays.asList("shape", "reading"));
        java.util.Set<String> tags = new java.util.LinkedHashSet<>();
        if (card.tags != null) tags.addAll(card.tags);
        if (card.aliases != null) tags.addAll(card.aliases);
        if (card.patternType != null) tags.add(card.patternType);
        c.tags = new ArrayList<>(tags);
        c.katagoSignals = card.triggers != null && card.triggers.candidateFeatures != null ? new ArrayList<>(card.triggers.candidateFeatures) : new ArrayList<>();
        java.util.Set<String> board = new java.util.LinkedHashSet<>();
        if (card.boardSignals != null) board.addAll(card.boardSignals);
        if (card.shape != null && card.shape.canonicalMoves != null) board.addAll(card.shape.canonicalMoves);
        c.boardSignals = new ArrayList<>(board);
        c.summary = card.teaching != null ? card.teaching.recognition : "";
        c.coachShort = card.teaching != null ? card.teaching.correctIdea : "";
        StringBuilder cl = new StringBuilder();
        if (card.teaching != null) {
            cl.append("记忆法: ").append(card.teaching.memoryCue).append("\n");
            cl.append("常见误区: ").append(card.teaching.commonMistake);
        }
        if (card.variations != null && !card.variations.isEmpty()) {
            StringBuilder vs = new StringBuilder();
            for (PatternSearchEngine.Variation v : card.variations) {
                if (vs.length() > 0) vs.append("；");
                vs.append(v.name).append(" - ").append(v.whenToChoose);
            }
            cl.append("\n变化: ").append(vs);
        }
        c.coachLong = cl.toString();
        c.drill = card.teaching != null ? card.teaching.drill : "";
        return c;
    }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    static List<String> strList(Object o) { List<String> l = new ArrayList<>(); if (o instanceof List) for (Object x : (List<?>) o) if (x != null) l.add(String.valueOf(x)); return l; }

    static List<String> normalize(String text) {
        if (text == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String tok : text.toLowerCase().split("[，。！？、；：,.!?;:()（）\\[\\]【】\\s]+")) { tok = tok.trim(); if (!tok.isEmpty()) out.add(tok); }
        return out;
    }
    static String cardTitle(Object card) {
        if (card instanceof JosekiLineEntry e) return e.title;
        if (card instanceof ShapePatternCard c) return c.title;
        if (card instanceof KnowledgeCard k) return k.title;
        return "";
    }
    static String cardText(Object card) {
        if (card instanceof JosekiLineEntry e) return String.join(" ", Arrays.asList(e.title, e.family, e.katagoEraJudgement, String.join(" ", e.tags), String.join(" ", e.relativeSequence)));
        if (card instanceof ShapePatternCard c) return String.join(" ", Arrays.asList(c.title, c.shapeType, c.category, String.join(" ", c.tags), c.teaching.recognition, c.teaching.correctThinking));
        if (card instanceof KnowledgeCard k) return String.join(" ", Arrays.asList(k.title, k.kind, k.summary, k.coachShort, k.coachLong, k.drill, String.join(" ", k.errorTypes), String.join(" ", k.tags), String.join(" ", k.katagoSignals), String.join(" ", k.boardSignals)));
        return "";
    }
    static List<String> cardErrors(Object card) {
        if (card instanceof JosekiLineEntry) return Arrays.asList("direction", "joseki");
        if (card instanceof ShapePatternCard c) return Arrays.asList(c.category, "shape", "reading");
        if (card instanceof KnowledgeCard k) return k.errorTypes;
        return new ArrayList<>();
    }
    static List<String> cardTags(Object card) {
        if (card instanceof JosekiLineEntry e) return e.tags;
        if (card instanceof ShapePatternCard c) return c.tags;
        if (card instanceof KnowledgeCard k) return k.tags;
        return new ArrayList<>();
    }
    static List<String> cardPhases(Object card) {
        if (card instanceof JosekiLineEntry e) return e.phase;
        if (card instanceof ShapePatternCard c) return c.phase;
        if (card instanceof KnowledgeCard k) return k.phase;
        return new ArrayList<>();
    }

    public static List<KnowledgeSearchResult> search(List<Object> cards, KnowledgeSearchQuery query) {
        List<String> terms = normalize(query.text);
        Set<String> wantedErrors = new HashSet<>(), wantedTags = new HashSet<>();
        for (String e : query.errorTypes) wantedErrors.add(e.toLowerCase());
        for (String t : query.tags) wantedTags.add(t.toLowerCase());
        List<KnowledgeSearchResult> results = new ArrayList<>();
        for (Object card : cards) {
            double score = 0; List<String> reasons = new ArrayList<>();
            String text = cardText(card).toLowerCase();
            for (String term : terms) if (text.contains(term)) { score += 2; reasons.add("match:" + term); }
            if (query.phase != null && cardPhases(card).contains(query.phase)) { score += 2; reasons.add("phase:" + query.phase); }
            for (String err : cardErrors(card)) if (wantedErrors.contains(err.toLowerCase())) { score += 5; reasons.add("error:" + err); }
            for (String tag : cardTags(card)) if (wantedTags.contains(tag.toLowerCase())) { score += 3; reasons.add("tag:" + tag); }
            if (score == 0 && terms.isEmpty() && wantedErrors.isEmpty() && wantedTags.isEmpty()) { score = 1; reasons.add("default"); }
            if (score > 0) { KnowledgeSearchResult r = new KnowledgeSearchResult(); r.card = card; r.score = score; r.reasons = reasons; results.add(r); }
        }
        results.sort((a, b) -> Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) : cardTitle(a.card).compareTo(cardTitle(b.card)));
        return results.size() > query.limit ? results.subList(0, query.limit) : results;
    }

    public static String formatKnowledgeCardsForPrompt(List<KnowledgeSearchResult> results) {
        if (results == null || results.isEmpty()) return "未找到相关知识点。请只基于 KataGo 和已验证知识讲解。";
        List<String> blocks = new ArrayList<>();
        int i = 0;
        for (KnowledgeSearchResult r : results.subList(0, Math.min(4, results.size()))) {
            Object card = r.card;
            String title = card instanceof JosekiLineEntry e ? e.title : card instanceof ShapePatternCard c ? c.title : card instanceof KnowledgeCard k ? k.title : "?";
            String kind = card instanceof JosekiLineEntry ? "joseki" : card instanceof ShapePatternCard c ? c.category : card instanceof KnowledgeCard k ? k.kind : "?";
            List<String> errors = cardErrors(card);
            String summary, coachShort, drill;
            if (card instanceof KnowledgeCard k) { summary = k.summary; coachShort = k.coachShort; drill = k.drill; }
            else if (card instanceof ShapePatternCard c) { summary = c.teaching.recognition; coachShort = c.teaching.correctThinking; drill = c.teaching.drillPrompt; }
            else if (card instanceof JosekiLineEntry e) { summary = e.katagoEraJudgement; coachShort = String.join(" ", e.decisionRules); drill = "按方向与先手判断分支选择"; }
            else { summary = ""; coachShort = ""; drill = ""; }
            blocks.add(String.join("\n",
                "#" + (++i) + " " + title,
                "类型: " + kind,
                "错误类型: " + (errors.isEmpty() ? "通用" : String.join(", ", errors)),
                "摘要: " + summary,
                "老师短讲: " + coachShort,
                "训练建议: " + drill));
        }
        return String.join("\n\n", blocks);
    }
}
