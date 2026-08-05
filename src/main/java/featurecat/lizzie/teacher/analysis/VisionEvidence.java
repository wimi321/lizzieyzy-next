package featurecat.lizzie.teacher.analysis;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对齐 GoAgent 的 visionEvidence.ts（271 行）全量：
 * buildVisionEvidenceReport（data URL 解析/mime/字节/PNG+JPEG 尺寸/warnings/blockingIssues）
 * + validateVisionEvidenceForIntent + formatVisionEvidenceForPrompt + buildVisionImageContentParts
 * + visionRequiredForMode / visionRequiredForIntent。
 */
public final class VisionEvidence {

    private VisionEvidence() {}

    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final int RECOMMENDED_IMAGE_BYTES = 2 * 1024 * 1024;
    public static final String DEFAULT_DETAIL = "high";

    static final Pattern DATA_URL_PATTERN = Pattern.compile("^data:(image/(png|jpeg|jpg));base64,([A-Za-z0-9+/=\\s]+)$", Pattern.CASE_INSENSITIVE);

    public static class VisionEvidenceImage {
        public String id; public int index; public String role, source, moveNumber, mimeType, detail, caption;
        public long bytes; public int width, height; public boolean valid;
        public List<String> warnings = new ArrayList<>();
    }
    public static class VisionEvidenceReport {
        public boolean required, attached;
        public int imageCount;
        public String providerSupportsVision = "unknown";
        public String source = "initial-attachment";
        public String createdAt;
        public List<VisionEvidenceImage> images = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> blockingIssues = new ArrayList<>();
    }
    public static class RawVisionImage {
        public String url; public int index; public String role; public Integer moveNumber; public String caption;
    }
    public static class VisionValidationResult {
        public boolean ok;
        public List<String> blockingIssues = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
    }

    static String nowIso() { return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new java.util.Date()); }

    public static boolean visionRequiredForMode(String mode) {
        return "current-move".equals(mode) || "move-range".equals(mode);
    }
    public static boolean visionRequiredForIntent(String intent) { return false; }

    static String normalizeMime(String value) {
        String lowered = value == null ? "" : value.toLowerCase();
        if (lowered.equals("image/png")) return "image/png";
        if (lowered.equals("image/jpeg") || lowered.equals("image/jpg")) return "image/jpeg";
        return "unknown";
    }
    static long byteLengthFromBase64(String base64) {
        String clean = base64 == null ? "" : base64.replaceAll("\\s+", "");
        if (clean.isEmpty()) return 0;
        int padding = clean.endsWith("==") ? 2 : clean.endsWith("=") ? 1 : 0;
        return Math.max(0, (clean.length() * 3) / 4 - padding);
    }
    static int[] readPngSize(byte[] bytes) {
        if (bytes.length < 24) return null;
        byte[] sig = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
        for (int i = 0; i < 8; i++) if (bytes[i] != sig[i]) return null;
        int width = ((bytes[16] & 0xff) << 24) | ((bytes[17] & 0xff) << 16) | ((bytes[18] & 0xff) << 8) | (bytes[19] & 0xff);
        int height = ((bytes[20] & 0xff) << 24) | ((bytes[21] & 0xff) << 16) | ((bytes[22] & 0xff) << 8) | (bytes[23] & 0xff);
        return new int[]{width, height};
    }
    static int[] readJpegSize(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) return null;
        int offset = 2;
        while (offset + 9 < bytes.length) {
            if ((bytes[offset] & 0xff) != 0xff) { offset += 1; continue; }
            int marker = bytes[offset + 1] & 0xff;
            int length = ((bytes[offset + 2] & 0xff) << 8) + (bytes[offset + 3] & 0xff);
            if (length < 2) return null;
            boolean isStartOfFrame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (isStartOfFrame && offset + 8 < bytes.length) {
                int height = ((bytes[offset + 5] & 0xff) << 8) + (bytes[offset + 6] & 0xff);
                int width = ((bytes[offset + 7] & 0xff) << 8) + (bytes[offset + 8] & 0xff);
                return new int[]{width, height};
            }
            offset += 2 + length;
        }
        return null;
    }
    static int[] decodeSizeFromDataUrl(String url, String mimeType) {
        Matcher m = DATA_URL_PATTERN.matcher(url);
        if (!m.find()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(m.group(3).replaceAll("\\s+", ""));
            return mimeType.equals("image/png") ? readPngSize(bytes) : mimeType.equals("image/jpeg") ? readJpegSize(bytes) : null;
        } catch (Exception e) { return null; }
    }
    static long imageBytes(String url) {
        Matcher m = DATA_URL_PATTERN.matcher(url);
        if (!m.find()) return 0;
        return byteLengthFromBase64(m.group(3));
    }
    static String imageMime(String url) {
        Matcher m = DATA_URL_PATTERN.matcher(url);
        return normalizeMime(m.find() ? m.group(1) : null);
    }
    static List<String> imageWarnings(RawVisionImage input, long bytes, String mimeType) {
        List<String> warnings = new ArrayList<>();
        if (!input.url.startsWith("data:image/")) warnings.add("image is not a data:image URL");
        if (mimeType.equals("unknown")) warnings.add("unsupported image mime type");
        if (bytes <= 0) warnings.add("image bytes are empty or invalid");
        if (bytes > RECOMMENDED_IMAGE_BYTES) warnings.add("image is larger than recommended " + RECOMMENDED_IMAGE_BYTES + " bytes");
        if (bytes > MAX_IMAGE_BYTES) warnings.add("image exceeds hard limit " + MAX_IMAGE_BYTES + " bytes");
        return warnings;
    }

    static List<RawVisionImage> rawImagesFromRequest(String boardImageDataUrl, List<String> boardImageDataUrls, String mode, Integer moveNumber, List<Integer> rangeKeyMoveNumbers, Integer rangeStart) {
        List<RawVisionImage> output = new ArrayList<>();
        if (boardImageDataUrl != null) {
            RawVisionImage img = new RawVisionImage();
            img.url = boardImageDataUrl; img.index = output.size();
            img.role = "move-range".equals(mode) ? "range-key-move" : "current-board";
            img.moveNumber = moveNumber;
            img.caption = moveNumber != null
                ? "图 " + (output.size() + 1) + ": 第 " + moveNumber + " 手棋盘截图；请结合 KataGo 数据核对实战手、候选点和棋形。"
                : "图 " + (output.size() + 1) + ": 当前棋盘截图；请结合 KataGo 数据核对实战手、候选点和棋形。";
            output.add(img);
        }
        if (boardImageDataUrls != null) {
            for (String url : boardImageDataUrls) {
                Integer summaryMove = (rangeKeyMoveNumbers != null && output.size() < rangeKeyMoveNumbers.size()) ? rangeKeyMoveNumbers.get(output.size()) : null;
                Integer mn = summaryMove != null ? summaryMove : (rangeStart != null ? rangeStart + output.size() : null);
                RawVisionImage img = new RawVisionImage();
                img.url = url; img.index = output.size(); img.role = "range-key-move"; img.moveNumber = mn;
                img.caption = mn != null
                    ? "图 " + (output.size() + 1) + ": 区间关键手第 " + mn + " 手棋盘截图；先看区间走势，再讲此关键手得失。"
                    : "图 " + (output.size() + 1) + ": 区间关键手棋盘截图；先看区间走势，再讲关键手得失。";
                output.add(img);
            }
        }
        return output;
    }

    public static VisionEvidenceReport buildVisionEvidenceReport(String boardImageDataUrl, List<String> boardImageDataUrls, String mode, Integer moveNumber, List<Integer> rangeKeyMoveNumbers, Integer rangeStart) {
        boolean required = visionRequiredForMode(mode);
        List<RawVisionImage> raw = rawImagesFromRequest(boardImageDataUrl, boardImageDataUrls, mode, moveNumber, rangeKeyMoveNumbers, rangeStart);
        VisionEvidenceReport report = new VisionEvidenceReport();
        report.createdAt = nowIso();
        for (RawVisionImage input : raw) {
            long bytes = imageBytes(input.url);
            String mimeType = imageMime(input.url);
            List<String> warnings = imageWarnings(input, bytes, mimeType);
            int[] size = decodeSizeFromDataUrl(input.url, mimeType);
            VisionEvidenceImage image = new VisionEvidenceImage();
            image.id = "vision-" + (input.index + 1); image.index = input.index; image.role = input.role;
            image.source = "initial-attachment"; image.moveNumber = input.moveNumber != null ? String.valueOf(input.moveNumber) : null;
            image.mimeType = mimeType; image.bytes = bytes;
            if (size != null) { image.width = size[0]; image.height = size[1]; }
            image.detail = DEFAULT_DETAIL; image.caption = input.caption;
            boolean bad = false;
            for (String w : warnings) if (w.matches(".*(exceeds hard limit|empty|invalid|unsupported).*")) bad = true;
            image.valid = !bad;
            image.warnings = warnings;
            report.images.add(image);
        }
        for (VisionEvidenceImage img : report.images) for (String w : img.warnings) report.warnings.add(img.id + ": " + w);
        if (required && report.images.isEmpty()) report.blockingIssues.add("this teacher task requires a board image, but none was attached");
        for (VisionEvidenceImage img : report.images) {
            if (!img.valid) report.blockingIssues.add(img.id + " is not valid for vision input");
            if (img.bytes > MAX_IMAGE_BYTES) report.blockingIssues.add(img.id + " exceeds the maximum supported vision input size");
        }
        report.required = required;
        report.attached = !report.images.isEmpty();
        report.imageCount = report.images.size();
        return report;
    }

    public static VisionValidationResult validateVisionEvidenceForIntent(VisionEvidenceReport report, String intentOrMode) {
        VisionValidationResult r = new VisionValidationResult();
        boolean required = report.required || visionRequiredForIntent(intentOrMode);
        List<String> blocking = new ArrayList<>(report.blockingIssues);
        if (required && !report.attached) blocking.add("required board image was not attached");
        if (required && !report.images.isEmpty() && report.images.stream().allMatch(img -> !img.valid)) blocking.add("no valid board image is available for required vision task");
        r.blockingIssues = new ArrayList<>(new LinkedHashSet<>(blocking));
        r.ok = r.blockingIssues.isEmpty();
        r.warnings = report.warnings;
        return r;
    }

    public static String formatVisionEvidenceForPrompt(VisionEvidenceReport report) {
        String header = report.attached
            ? "【棋盘图证据】本轮已附 " + report.imageCount + " 张棋盘图，detail=high。"
            : "【棋盘图证据】本轮未附棋盘图。required=" + (report.required ? "true" : "false") + "。";
        List<String> lines = new ArrayList<>();
        for (VisionEvidenceImage img : report.images) {
            lines.add("- " + img.id + ": " + img.caption);
            lines.add("  role=" + img.role + ", move=" + (img.moveNumber != null ? img.moveNumber : "unknown") + ", mime=" + img.mimeType + ", bytes=" + img.bytes + ", size=" + (img.width > 0 ? img.width : "?") + "x" + (img.height > 0 ? img.height : "?") + ", valid=" + img.valid);
        }
        if (!report.warnings.isEmpty()) lines.add("警告: " + String.join("；", report.warnings));
        if (report.attached) lines.add("本轮已经提供棋盘图。除非 visionEvidence.attached=false，否则严禁说“没有棋盘图”“看不到棋盘”“未提供图片”。");
        else lines.add("如果任务需要棋盘图而未提供，请直接说明需要重新生成棋盘截图，不要假装看到了图。");
        return String.join("\n", header + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines)));
    }

    public static String buildVisionImageContentParts(String boardImageDataUrl, VisionEvidenceReport report) {
        StringBuilder sb = new StringBuilder();
        for (VisionEvidenceImage img : report.images) {
            if (!img.valid || boardImageDataUrl == null) continue;
            sb.append(img.caption).append("\n");
            sb.append(boardImageDataUrl).append("\n");
        }
        return sb.toString();
    }

    /** 兼容旧调用：校验 data URL 图片（格式/大小）。返回报告。 */
    public static Report verify(String dataUrl) {
        Report r = new Report(dataUrl != null && !dataUrl.isEmpty());
        if (!r.attached) {
            r.warnings.add("未提供棋盘图；讲解只能基于 KataGo 数据与知识库，不能使用图片相关断言。");
            return r;
        }
        if (!dataUrl.startsWith("data:image/png")) r.warnings.add("棋盘图应为 PNG data URL。");
        int comma = dataUrl.indexOf(',');
        if (comma > 0) {
            long bytes = imageBytes(dataUrl);
            if (bytes > MAX_IMAGE_BYTES) r.warnings.add("棋盘图超过 8MB，可能被端点拒绝。");
            else if (bytes > RECOMMENDED_IMAGE_BYTES) r.warnings.add("棋盘图偏大（>2MB），建议缩小。");
        }
        return r;
    }
    public static class Report {
        public final boolean attached;
        public final List<String> warnings = new ArrayList<>();
        public Report(boolean attached) { this.attached = attached; }
    }

    /** 对齐 TS visionEvidenceForLog：深拷贝报告（images 逐个复制） */
    public static VisionEvidenceReport visionEvidenceForLog(VisionEvidenceReport report) {
        VisionEvidenceReport copy = new VisionEvidenceReport();
        copy.required = report.required; copy.attached = report.attached; copy.imageCount = report.imageCount;
        copy.providerSupportsVision = report.providerSupportsVision; copy.source = report.source; copy.createdAt = report.createdAt;
        copy.warnings = new ArrayList<>(report.warnings); copy.blockingIssues = new ArrayList<>(report.blockingIssues);
        for (VisionEvidenceImage img : report.images) {
            VisionEvidenceImage ic = new VisionEvidenceImage();
            ic.id = img.id; ic.index = img.index; ic.role = img.role; ic.source = img.source; ic.moveNumber = img.moveNumber;
            ic.mimeType = img.mimeType; ic.bytes = img.bytes; ic.width = img.width; ic.height = img.height;
            ic.detail = img.detail; ic.caption = img.caption; ic.valid = img.valid; ic.warnings = new ArrayList<>(img.warnings);
            copy.images.add(ic);
        }
        return copy;
    }

    public static String systemInstruction(boolean boardImageReady) {
        if (boardImageReady) {
            return "本轮已经提供棋盘图。除非明确说明，否则严禁说“没有棋盘图”“看不到棋盘”“未提供图片”；"
                + "应基于棋盘图、KataGo 数据与知识库综合讲解。";
        }
        return "本轮未提供棋盘图，讲解只能基于 KataGo 数据与知识库，不得使用任何图片相关断言。";
    }
}
