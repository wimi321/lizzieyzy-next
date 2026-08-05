package featurecat.lizzie.teacher.analysis;

/**
 * 对齐 GoAgent 的 humanWinrateCalibrator.ts（76 行）全量：
 * LEVEL_SCALE / phaseVolatility / sigmoid / confidenceFor（boardSize≠19→low、|scoreLead|≥20→medium、moveNumber>0→medium）
 * + calibrateHumanWinrate（humanWinrateEstimate/confidence/explanation）+ humanizeLossForTeaching。
 */
public final class HumanWinrateCalibrator {

    private HumanWinrateCalibrator() {}

    public enum Level { BEGINNER, INTERMEDIATE, ADVANCED, DAN }
    public enum Confidence { high, medium, low }

    private static double levelScale(Level level) {
        return switch (level) {
            case BEGINNER -> 0.34;
            case INTERMEDIATE -> 0.46;
            case ADVANCED -> 0.62;
            case DAN -> 0.78;
        };
    }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private static double sigmoid(double v) { return 1.0 / (1.0 + Math.exp(-v)); }
    private static double phaseVolatility(int moveNumber) {
        if (moveNumber <= 50) return 0.85;
        if (moveNumber <= 160) return 1.15;
        return 0.95;
    }

    public static class Calibration {
        public final Double aiWinrate;
        public final double humanWinrateEstimate;
        public final double scoreLead;
        public final Level level;
        public final Confidence confidence;
        public final String explanation;
        public Calibration(Double aiWinrate, double humanWinrateEstimate, double scoreLead, Level level, Confidence confidence, String explanation) {
            this.aiWinrate = aiWinrate; this.humanWinrateEstimate = humanWinrateEstimate;
            this.scoreLead = scoreLead; this.level = level; this.confidence = confidence; this.explanation = explanation;
        }
    }

    static Confidence confidenceFor(Double scoreLead, int moveNumber, Integer boardSize) {
        if (boardSize != null && boardSize != 19) return Confidence.low;
        if (scoreLead != null && Math.abs(scoreLead) >= 20) return Confidence.medium;
        if (moveNumber > 0 && scoreLead != null && Double.isFinite(scoreLead)) return Confidence.medium;
        return Confidence.low;
    }

    /** 对齐 calibrateHumanWinrate */
    public static Calibration calibrateHumanWinrate(Double aiWinrate, double scoreLead, int moveNumber, Level userLevel, Integer boardSize) {
        double scale = levelScale(userLevel != null ? userLevel : Level.INTERMEDIATE);
        double volatility = phaseVolatility(moveNumber);
        double normalized = scoreLead * scale / (8.5 * volatility);
        double estimated = clamp(sigmoid(normalized) * 100, 1, 99);
        Confidence confidence = confidenceFor(scoreLead, moveNumber, boardSize);
        String explanation = "AI winrate is " + (aiWinrate != null ? String.format("%.1f%%", aiWinrate) : "not provided")
            + ", but teaching should be anchored on scoreLead=" + String.format("%.1f", scoreLead) + " for " + userLevel + ". "
            + ((userLevel == Level.BEGINNER || userLevel == Level.INTERMEDIATE)
                ? "For this level, prefer score loss, shape purpose, and one executable next-game reminder over tiny winrate changes."
                : "For stronger students, winrate, score, and candidate-spread details can be shown together.");
        return new Calibration(aiWinrate, Math.round(estimated * 10) / 10.0, scoreLead, userLevel, confidence, explanation);
    }

    /** 兼容旧调用 */
    public static Calibration calibrate(double scoreLead, int moveNumber, Level level) {
        return calibrateHumanWinrate(null, scoreLead, moveNumber, level, null);
    }

    /** 对齐 humanizeLossForTeaching */
    public static String humanizeLoss(double winrateLoss, double scoreLoss, Level level) {
        double score = Math.round(scoreLoss * 10) / 10.0;
        double wr = Math.round(winrateLoss * 10) / 10.0;
        if (level == Level.BEGINNER) {
            if (score < 1.5 && wr < 3) return "损失很小，重点是理解方向，不要把它当成大错。";
            return String.format("这手主要可理解为约 %.1f 目的训练点，先别只盯胜率。", score);
        }
        if (level == Level.INTERMEDIATE) {
            return String.format("这手约亏 %.1f 目 / %.1f%% 胜率；讲解应先说判断顺序，再说数字。", score, wr);
        }
        return String.format("这手约亏 %.1f 目 / %.1f%% 胜率；可以进一步比较候选间距、PV 和 ownership。", score, wr);
    }
}
