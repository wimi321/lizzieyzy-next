package featurecat.lizzie.teacher.knowledge;

import java.util.*;

/**
 * 对齐 GoAgent knowledge/shapeRecognitionEngine.ts：综合局部模式匹配（LocalPatternMatcher）+ KataGo 形状特征（KatagoShapeFeatures）输出识别棋形。
 * 数据加载走 JsonKnowledgeLoader（resources/knowledge/shape-pattern-cards-v1.json 等）。
 */
public final class ShapeRecognitionEngine {

    private ShapeRecognitionEngine() {}

    public enum ShapeRecognitionConfidence { strong, medium, weak }

    public static class RecognizedShape {
        public String id, title, shapeType, category, confidence, safeWording;
        public double score;
        public List<String> evidence = new ArrayList<>(), counterEvidence = new ArrayList<>(), relatedMoves = new ArrayList<>();
        public String recognition, wrongThinking, correctThinking, drillPrompt;
        public List<String> sourceRefs = new ArrayList<>(); public String sourceQuality;
    }

    static String phase(int moveNumber, int totalMoves) {
        double ratio = totalMoves > 0 ? (double) moveNumber / totalMoves : 0;
        if (moveNumber <= 40 || ratio <= 0.2) return "opening";
        if (ratio <= 0.72) return "middlegame";
        return "endgame";
    }
    static String safeWording(String confidence, List<String> counterEvidence) {
        if (counterEvidence.size() >= 3) return "不能主讲";
        if (confidence.equals("strong") && counterEvidence.isEmpty()) return "可以明确说";
        if (confidence.equals("medium")) return "更像是";
        return "只作为训练类比";
    }
    static ShapeRecognitionConfidence featureConfidence(double score) { return score >= 22 ? ShapeRecognitionConfidence.strong : score >= 15 ? ShapeRecognitionConfidence.medium : ShapeRecognitionConfidence.weak; }

    static RecognizedShape fromLocalMatch(LocalPatternMatcher.LocalPatternMatch m) {
        RecognizedShape s = new RecognizedShape();
        s.id = "local-pattern:" + m.card.id + ":" + m.anchor;
        s.title = m.card.title; s.shapeType = m.card.shapeType; s.category = m.card.category;
        s.confidence = m.confidence; s.score = m.score;
        s.evidence = m.evidence; s.counterEvidence = m.counterEvidence;
        s.safeWording = safeWording(m.confidence, m.counterEvidence);
        s.relatedMoves = new ArrayList<>(Collections.singletonList(m.anchor));
        s.recognition = m.card.teaching.recognition; s.wrongThinking = m.card.teaching.wrongThinking;
        s.correctThinking = m.card.teaching.correctThinking; s.drillPrompt = m.card.teaching.drillPrompt;
        s.sourceRefs = m.card.sourceRefs; s.sourceQuality = m.card.sourceQuality;
        return s;
    }

    static List<RecognizedShape> uniqueShapes(List<RecognizedShape> shapes) {
        Map<String, RecognizedShape> best = new LinkedHashMap<>();
        for (RecognizedShape shape : shapes) {
            String key = shape.shapeType + ":" + (shape.relatedMoves.isEmpty() ? "" : shape.relatedMoves.get(0));
            RecognizedShape cur = best.get(key);
            if (cur == null || shape.score > cur.score) best.put(key, shape);
        }
        List<RecognizedShape> out = new ArrayList<>(best.values());
        out.sort((a, b) -> Double.compare(b.score, a.score) != 0 ? Double.compare(b.score, a.score) : a.title.compareTo(b.title));
        return out;
    }

    public static class ShapeRecognitionInput {
        public int moveNumber, totalMoves, boardSize = 19;
        public List<KatagoShapeFeatures.KataGoShapeFeatureInput> _unused;
        public String playerColor, playedMove, judgement, lossScore;
        public List<String> recentMoves = new ArrayList<>(), candidateMoves = new ArrayList<>(), principalVariation = new ArrayList<>(), contextTags = new ArrayList<>();
        public List<LocalPatternMatcher.BoardSnapshotStone> boardSnapshot = new ArrayList<>();
        public List<LocalPatternMatcher.LocalWindow> localWindows = new ArrayList<>();
        public Integer maxResults = 6;
    }

    public static List<RecognizedShape> recognizeShapes(ShapeRecognitionInput input, List<LocalPatternMatcher.ShapePatternCard> cards) {
        List<String> anchors = new ArrayList<>();
        if (input.playedMove != null) anchors.add(input.playedMove);
        anchors.addAll(input.candidateMoves.subList(0, Math.min(6, input.candidateMoves.size())));
        anchors.addAll(input.principalVariation.subList(0, Math.min(6, input.principalVariation.size())));

        LocalPatternMatcher.LocalPatternMatcherInput lp = new LocalPatternMatcher.LocalPatternMatcherInput();
        lp.boardSize = input.boardSize; lp.boardSnapshot = input.boardSnapshot; lp.localWindows = input.localWindows;
        lp.anchors = anchors; lp.playerColor = input.playerColor; lp.phase = phase(input.moveNumber, input.totalMoves);
        List<RecognizedShape> localMatches = new ArrayList<>();
        for (LocalPatternMatcher.LocalPatternMatch m : LocalPatternMatcher.findLocalPatternMatches(cards, lp)) localMatches.add(fromLocalMatch(m));

        List<RecognizedShape> featureShapes = new ArrayList<>();
        KatagoShapeFeatures.KataGoShapeFeatureInput kf = new KatagoShapeFeatures.KataGoShapeFeatureInput();
        kf.boardSize = input.boardSize; kf.moveNumber = input.moveNumber; kf.totalMoves = input.totalMoves;
        kf.playedMove = input.playedMove; kf.candidateMoves = input.candidateMoves; kf.principalVariation = input.principalVariation;
        if (input.lossScore != null) { try { kf.lossScore = Double.parseDouble(input.lossScore); } catch (Exception e) {} }
        kf.judgement = input.judgement;
        for (KatagoShapeFeatures.KataGoShapeFeature f : KatagoShapeFeatures.extract(kf)) {
            RecognizedShape s = new RecognizedShape();
            s.id = "katago-feature:" + f.id; s.title = f.recognition.length() > 28 ? f.recognition.substring(0, 28) : f.recognition;
            s.shapeType = f.shapeType; s.category = "katago-shape-feature";
            s.confidence = f.confidence; s.score = f.score; s.evidence = f.evidence; s.counterEvidence = f.counterEvidence;
            s.safeWording = safeWording(f.confidence, f.counterEvidence);
            s.relatedMoves = f.relatedMoves; s.recognition = f.recognition; s.wrongThinking = f.wrongThinking;
            s.correctThinking = f.correctThinking; s.drillPrompt = f.drillPrompt;
            s.sourceRefs = new ArrayList<>(Arrays.asList("katago-analysis-engine-docs", "goagent-curated-original"));
            s.sourceQuality = "engine-derived-local-feature";
            featureShapes.add(s);
        }
        return uniqueShapes(new ArrayList<>() {{ addAll(localMatches); addAll(featureShapes); }}).subList(0, Math.min(input.maxResults != null ? input.maxResults : 6, localMatches.size() + featureShapes.size()));
    }

    /** 对齐 TS recognizedShapesToKnowledgePackets：过滤"不能主讲"，输出 KnowledgePacket（Map 形式） */
    public static List<java.util.Map<String, Object>> recognizedShapesToKnowledgePackets(List<RecognizedShape> shapes) {
        List<java.util.Map<String, Object>> packets = new ArrayList<>();
        if (shapes == null) return packets;
        for (RecognizedShape shape : shapes) {
            if ("不能主讲".equals(shape.safeWording)) continue;
            if (packets.size() >= 6) break;
            java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
            p.put("id", shape.id);
            p.put("title", shape.title);
            p.put("category", "shape:" + shape.shapeType);
            p.put("phase", "any");
            List<String> tags = new ArrayList<>();
            tags.add(shape.shapeType); tags.add(shape.confidence); tags.add(shape.safeWording);
            if (shape.sourceRefs != null) tags.addAll(shape.sourceRefs.subList(0, Math.min(2, shape.sourceRefs.size())));
            p.put("tags", tags);
            p.put("summary", shape.recognition);
            List<String> body = new ArrayList<>();
            body.add("棋形识别: " + shape.recognition);
            body.add("安全措辞: " + shape.safeWording);
            body.add("识别依据: " + (shape.evidence.isEmpty() ? "无" : String.join("；", shape.evidence)));
            if (!shape.counterEvidence.isEmpty()) body.add("反证/降置信: " + String.join("；", shape.counterEvidence));
            body.add("常见误区: " + shape.wrongThinking);
            body.add("正确思路: " + shape.correctThinking);
            body.add("小练习: " + shape.drillPrompt);
            body.add("sourceRefs: " + (shape.sourceRefs != null ? String.join(", ", shape.sourceRefs) : ""));
            p.put("selectedBody", String.join("\n", body));
            p.put("score", shape.score + ("strong".equals(shape.confidence) ? 10 : "medium".equals(shape.confidence) ? 5 : 0));
            packets.add(p);
        }
        return packets;
    }

    public static String formatShapeRecognitionForPrompt(List<RecognizedShape> shapes) {
        if (shapes.isEmpty()) return "未识别到高置信局部棋形。请只基于 KataGo 和已验证知识讲解。";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (RecognizedShape s : shapes.subList(0, Math.min(6, shapes.size()))) {
            sb.append(++i).append(". ").append(s.title).append(" (").append(s.shapeType).append(", ").append(s.confidence).append(", score=").append(s.score).append(")\n");
            sb.append("安全措辞：").append(s.safeWording).append("\n");
            sb.append("证据：").append(String.join("；", s.evidence)).append("\n");
            if (!s.counterEvidence.isEmpty()) sb.append("反证：").append(String.join("；", s.counterEvidence)).append("\n");
            sb.append("讲法：").append(s.recognition).append("\n");
            sb.append("误区：").append(s.wrongThinking).append("\n");
            sb.append("正确思路：").append(s.correctThinking).append("\n\n");
        }
        return sb.toString().trim();
    }
}
