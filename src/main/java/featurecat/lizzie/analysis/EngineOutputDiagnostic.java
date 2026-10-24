package featurecat.lizzie.analysis;

import featurecat.lizzie.logging.ObservationText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses process output (stdout/stderr) conservatively for explicit DLL loading, image, entry
 * point, or initialization errors.
 */
final class EngineOutputDiagnostic {
  static final int MAX_DETAIL_BYTES = 512;

  // DLL token: either enclosed in quotes/brackets ([^...]+?.dll) or an unquoted path token ending
  // in .dll
  private static final String DLL_TOKEN =
      "(?:['\"‘“\\[<]([^'\"’”\\]>\\r\\n]+?(?i:\\.dll))['\"’”\\]>]|(?<![a-zA-Z0-9_.+~\\\\/-])([a-zA-Z0-9_.+~\\\\/:-]+?(?i:\\.dll)))";

  // Phrases that explicitly negate failure predicates
  private static final Pattern NEGATED_FAILURE =
      Pattern.compile(
          "(?i)\\b(?:(?:did\\s+not|didn'?t|does\\s+not|doesn'?t|never)\\s+fail|(?:is|was|are|were)?\\s*not\\s+missing|(?:no|zero|0)\\s+missing|without\\s+(?:any\\s+)?(?:error|missing|failing)|not\\s+found\\s+to\\s+be\\s+missing|(?:was|is)\\s+found\\b(?!\\s+(?:to\\s+be\\s+missing|that|where)))");

  // Success indicators
  private static final Pattern SUCCESS_WORDS =
      Pattern.compile("(?i)\\b(?:success|succeeded|successfully|successful)\\b");

  private static final Pattern WINDOWS_PATH_LENGTH_ERROR =
      Pattern.compile("\\berror\\s+code\\s*[:=]?\\s*206\\b");

  private record Rule(Pattern pattern, String fixedOutcome) {}

  private static final List<Rule> RULES = new ArrayList<>();

  static {
    // Rule 1: Windows Loader cannot proceed / cannot start -> dll-not-found
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:the\\s+code\\s+execution\\s+cannot\\s+proceed\\s+because|the\\s+program\\s+can'?t\\s+start\\s+because)\\s+"
                    + DLL_TOKEN
                    + "\\s+(?:was\\s+not\\s+found|is\\s+missing)"),
            "dll-not-found"));

    // Rule 2: DLL was not found / could not be found / is missing -> dll-not-found
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:^|[\\s\"'‘“\\[<:;])"
                    + DLL_TOKEN
                    + "\\s+(?:was\\s+not\\s+found|could\\s+not\\s+be\\s+found|cannot\\s+be\\s+found|can\\s+not\\s+be\\s+found|is\\s+missing(?:\\s+from\\s+(?:your\\s+)?computer)?|is\\s+not\\s+found)"),
            "dll-not-found"));

    // Rule 3: Could not find / cannot find / missing DLL -> dll-not-found
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:could\\s+not\\s+find|cannot\\s+find|can\\s+not\\s+find|failed\\s+to\\s+find|unable\\s+to\\s+find|missing)\\s+(?:required\\s+)?(?:library|module|dll|file)?\\s*[:=-]?\\s*"
                    + DLL_TOKEN),
            "dll-not-found"));

    // Rule 4: POSIX / Wine shared library missing -> dll-not-found
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:error\\s+while\\s+loading\\s+shared\\s+libraries|cannot\\s+open\\s+shared\\s+object\\s+file)\\s*:\\s*"
                    + DLL_TOKEN),
            "dll-not-found"));

    // Rule 5: Colon with module not found / cannot open (e.g. JNI / Java UnsatisfiedLinkError) ->
    // dll-not-found
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)"
                    + DLL_TOKEN
                    + "\\s*:\\s*(?:the\\s+specified\\s+module\\s+could\\s+not\\s+be\\s+found|can'?t\\s+find\\s+dependent\\s+libraries|no\\s+such\\s+file|cannot\\s+open\\s+shared\\s+object\\s+file)"),
            "dll-not-found"));

    // Rule 6: Chinese Windows missing messages -> dll-not-found
    RULES.add(
        new Rule(
            Pattern.compile("(?i)(?:由于找不到|计算机中丢失|丢失\\s*(?:dll)?)\\s*" + DLL_TOKEN),
            "dll-not-found"));

    // Rule 7: Entrypoint not found -> entrypoint-not-found
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:procedure\\s+entry\\s+point|entry\\s*point|entrypoint).*?(?:could\\s+not\\s+be\\s+located|not\\s+found).*?in\\s+(?:the\\s+dynamic\\s+link\\s+library\\s+)?"
                    + DLL_TOKEN),
            "entrypoint-not-found"));

    // Rule 8: Chinese entry point not found -> entrypoint-not-found
    RULES.add(
        new Rule(
            Pattern.compile("(?i)(?:无法定位|无法找到)程序输入点.*?于动态链接库\\s*" + DLL_TOKEN),
            "entrypoint-not-found"));

    // Rule 9: Invalid image format (bad image / invalid image format) -> invalid-image-format
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:bad\\s+image|invalid\\s+image(?:\\s+format)?|not\\s+a\\s+valid\\s+win32\\s+application).*?"
                    + DLL_TOKEN),
            "invalid-image-format"));

    // Rule 10: DLL is not a valid Win32 application / not designed to run on Windows ->
    // invalid-image-format
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)"
                    + DLL_TOKEN
                    + "\\s+(?:is\\s+either\\s+not\\s+designed\\s+to\\s+run\\s+on\\s+windows|is\\s+not\\s+a\\s+valid\\s+win32\\s+application)"),
            "invalid-image-format"));

    // Rule 11: Colon with invalid format (e.g. JNI %1 is not a valid Win32 application) ->
    // invalid-image-format
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)"
                    + DLL_TOKEN
                    + "\\s*:\\s*(?:%1\\s+is\\s+not\\s+a\\s+valid\\s+win32\\s+application|not\\s+a\\s+valid\\s+win32\\s+application|bad\\s+image)"),
            "invalid-image-format"));

    // Rule 12: DLL Init Failed (initialization routine failed) -> dll-init-failed
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:initialization\\s+routine\\s+failed|dll\\s+initialization\\s+failed).*?"
                    + DLL_TOKEN),
            "dll-init-failed"));

    // Rule 13: DLL initialization routine failed -> dll-init-failed
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)"
                    + DLL_TOKEN
                    + "\\s+(?:initialization\\s+routine\\s+failed|initialization\\s+failed)"),
            "dll-init-failed"));

    // Rule 14: Colon with initialization routine failed -> dll-init-failed
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)"
                    + DLL_TOKEN
                    + "\\s*:\\s*(?:a\\s+dynamic\\s+link\\s+library\\s+\\(dll\\)\\s+initialization\\s+routine\\s+failed|initialization\\s+routine\\s+failed)"),
            "dll-init-failed"));

    // Rule 15: Generic load failure -> resolved via error code or context
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)(?:could\\s+not\\s+load|cannot\\s+load|can\\s+not\\s+load|failed\\s+to\\s+load|unable\\s+to\\s+load|error\\s+loading)\\s+(?:library|module|dll|file)?\\s*[:=-]?\\s*"
                    + DLL_TOKEN),
            null));

    // Rule 16: DLL failed to load / could not be loaded -> resolved via error code or context
    RULES.add(
        new Rule(
            Pattern.compile(
                "(?i)"
                    + DLL_TOKEN
                    + "\\s+(?:failed\\s+to\\s+load|could\\s+not\\s+be\\s+loaded|cannot\\s+be\\s+loaded|can\\s+not\\s+be\\s+loaded)"),
            null));

    // Rule 17: LoadLibrary failed -> resolved via error code or context
    RULES.add(
        new Rule(
            Pattern.compile("(?i)loadlibrary(?:[a-w]*)?\\s+(?:failed|error).*?" + DLL_TOKEN),
            null));

    // Rule 18: Chinese load failure -> resolved via error code or context
    RULES.add(
        new Rule(Pattern.compile("(?i)(?:无法加载|加载失败|加载动态链接库失败)\\s*[:=-]?\\s*" + DLL_TOKEN), null));
  }

  private EngineOutputDiagnostic() {}

  static List<EngineStartupDiagnostics.Finding> parse(
      String stream, String text, Instant checkedAt) {
    if (text == null || text.isBlank()) {
      return List.of();
    }

    String evidence = resolveEvidence(stream);
    Instant timestamp = checkedAt != null ? checkedAt : Instant.now();

    List<EngineStartupDiagnostics.Finding> findings = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    for (String rawLine : text.lines().toList()) {
      if (rawLine == null || rawLine.isBlank()) continue;

      String[] clauses = rawLine.split(";");

      for (String clause : clauses) {
        String trimmedClause = clause.trim();
        if (trimmedClause.isEmpty()) continue;
        if (!trimmedClause.toLowerCase(Locale.ROOT).contains(".dll")) continue;

        // Skip clauses expressing success or negated failures
        if (SUCCESS_WORDS.matcher(trimmedClause).find()) continue;
        if (NEGATED_FAILURE.matcher(trimmedClause).find()) continue;

        for (Rule rule : RULES) {
          Matcher matcher = rule.pattern.matcher(trimmedClause);
          if (matcher.find()) {
            String rawDll = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String dll = cleanDllBasename(rawDll);
            if (dll == null) continue;

            String outcome =
                rule.fixedOutcome != null
                    ? rule.fixedOutcome
                    : resolveLoadFailureOutcome(trimmedClause);

            String key = outcome + "|" + dll.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
              String candidate = ObservationText.boundedUtf8(rawLine.trim(), MAX_DETAIL_BYTES, 1);
              String detail = candidate;
              if (!candidate.toLowerCase(Locale.ROOT).contains(dll.toLowerCase(Locale.ROOT))) {
                String errorText =
                    trimmedClause.length() <= MAX_DETAIL_BYTES
                        ? trimmedClause
                        : trimmedClause.substring(matcher.start()).trim();
                detail = ObservationText.boundedUtf8(errorText, MAX_DETAIL_BYTES, 1);
              }
              findings.add(
                  new EngineStartupDiagnostics.Finding(outcome, dll, evidence, detail, timestamp));
            }
            break; // found matching rule for this clause
          }
        }
      }
    }

    return findings;
  }

  static String cleanDllBasename(String raw) {
    if (raw == null) return null;
    String s = raw.trim();

    // Strip enclosing quotes or brackets
    while (!s.isEmpty()) {
      char first = s.charAt(0);
      char last = s.charAt(s.length() - 1);
      if ((first == '\'' && last == '\'')
          || (first == '"' && last == '"')
          || (first == '‘' && last == '’')
          || (first == '“' && last == '”')
          || (first == '[' && last == ']')
          || (first == '<' && last == '>')
          || (first == '(' && last == ')')) {
        s = s.substring(1, s.length() - 1).trim();
      } else {
        break;
      }
    }

    // Strip trailing punctuation
    while (!s.isEmpty()) {
      char last = s.charAt(s.length() - 1);
      if (last == '.'
          || last == ','
          || last == ':'
          || last == ';'
          || last == '!'
          || last == '?'
          || last == '\''
          || last == '"'
          || last == '’'
          || last == '”'
          || last == ']'
          || last == '>') {
        if (s.toLowerCase(Locale.ROOT).endsWith(".dll")) {
          break;
        }
        s = s.substring(0, s.length() - 1).trim();
      } else {
        break;
      }
    }

    int lastSlash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
    if (lastSlash >= 0) {
      s = s.substring(lastSlash + 1).trim();
    }

    if (s.toLowerCase(Locale.ROOT).endsWith(".dll") && s.length() > 4) {
      if (!s.contains("/") && !s.contains("\\") && !s.contains(" ") && !s.contains("\t")) {
        return s;
      }
    }
    return null;
  }

  private static String resolveEvidence(String stream) {
    if ("merged".equalsIgnoreCase(stream)) return "engine-merged";
    if (stream != null && stream.toLowerCase(Locale.ROOT).contains("stdout")) {
      return "engine-stdout";
    }
    return "engine-stderr";
  }

  private static String resolveLoadFailureOutcome(String clause) {
    String text = clause.toLowerCase(Locale.ROOT);

    // Diagnose the explicit loader error, never a path-length threshold or unrelated number.
    if (WINDOWS_PATH_LENGTH_ERROR.matcher(text).find()) {
      return "path-too-long";
    }

    // Check for Entrypoint Not Found (error 127)
    if (hasErrorCode(text, 127)
        || containsAny(
            text,
            "status_entrypoint_not_found",
            "0xc0000139",
            "procedure could not be found",
            "entry point",
            "entrypoint",
            "程序输入点")) {
      return "entrypoint-not-found";
    }

    // Check for Invalid Image Format (error 193)
    if (hasErrorCode(text, 193)
        || containsAny(
            text,
            "status_invalid_image_format",
            "0xc000007b",
            "not a valid win32 application",
            "incorrect format",
            "bad image",
            "invalid image")) {
      return "invalid-image-format";
    }

    // Check for DLL Init Failed (error 1114)
    if (hasErrorCode(text, 1114)
        || containsAny(
            text,
            "status_dll_init_failed",
            "0xc0000142",
            "initialization routine failed",
            "init failed")) {
      return "dll-init-failed";
    }

    // Check for DLL Not Found (error 126)
    if (hasErrorCode(text, 126)
        || containsAny(
            text,
            "status_dll_not_found",
            "0xc0000135",
            "specified module could not be found",
            "module could not be found",
            "not found",
            "missing",
            "no such file",
            "找不到",
            "丢失")) {
      return "dll-not-found";
    }

    return "dll-load-failed";
  }

  private static boolean hasErrorCode(String lower, int code) {
    Pattern p =
        Pattern.compile(
            "(?i)(?:error(?:\\s+code)?|code|winerror|errno|err|\\(|:)\\s*#?\\s*" + code + "\\b");
    return p.matcher(lower).find();
  }

  private static boolean containsAny(String lower, String... candidates) {
    for (String c : candidates) {
      if (lower.contains(c)) return true;
    }
    return false;
  }
}
