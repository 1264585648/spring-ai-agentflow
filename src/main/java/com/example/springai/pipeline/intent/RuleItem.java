package com.example.springai.pipeline.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存中编译后的单条 L1 匹配规则运行时对象
 */
public class RuleItem {

    private static final Logger log = LoggerFactory.getLogger(RuleItem.class);

    /**
     * 单次匹配允许访问的字符次数。超限视为未命中，避免回溯表达式占住调用线程。
     */
    static final int MAX_REGEX_STEPS = 100_000;

    private static final Pattern PARAM_TOKEN = Pattern.compile("\\$(\\d+)");

    public enum MatchType {
        /**
         * 精准全词匹配 (忽略前后空白，大小写不敏感)
         */
        EXACT,

        /**
         * 正则表达式匹配 (支持捕获组提取入参)
         */
        REGEX,

        /**
         * 前缀匹配
         */
        PREFIX
    }

    private String ruleCode;
    private String ruleName;
    private MatchType matchType;
    private String patternExpr;
    private String targetType;
    private String targetRef;
    private String paramTemplate;
    private int priority = 100;

    private String description;

    /**
     * 预编译后的正则表达式 Pattern 实例 (避免运行时重复编译，耗时从几毫秒降低至微秒级)
     */
    private Pattern compiledRegex;

    public RuleItem() {}

    public RuleItem(String ruleCode, String ruleName, MatchType matchType, String patternExpr,
                    String targetType, String targetRef, String paramTemplate, int priority, String description) {
        this.ruleCode = ruleCode;
        this.ruleName = ruleName;
        this.matchType = matchType;
        this.patternExpr = patternExpr;
        this.targetType = targetType;
        this.targetRef = targetRef;
        this.paramTemplate = paramTemplate;
        this.priority = priority;
        this.description = description;
        if (matchType == MatchType.REGEX && patternExpr != null && !patternExpr.isEmpty()) {
            this.compiledRegex = Pattern.compile(patternExpr, Pattern.CASE_INSENSITIVE);
        }
    }

    public RuleItem(String ruleCode, String ruleName, MatchType matchType, String patternExpr,
                    String targetType, String targetRef, String paramTemplate, int priority) {
        this(ruleCode, ruleName, matchType, patternExpr, targetType, targetRef, paramTemplate, priority, null);
    }

    /**
     * 向后兼容构造器
     */
    public RuleItem(String pattern, MatchType matchType, String targetIntent, String directReply) {
        this(targetIntent, targetIntent, matchType, pattern, "STATIC_TEXT", directReply, null, 100);
    }

    /**
     * 判断用户输入是否命中本规则
     */
    public boolean matches(String query) {
        if (query == null || patternExpr == null) {
            return false;
        }
        String trimmedQuery = query.trim();

        switch (matchType) {
            case EXACT:
                return trimmedQuery.equalsIgnoreCase(patternExpr.trim());
            case PREFIX:
                return matchesPrefix(trimmedQuery, patternExpr.trim());
            case REGEX:
                return matchesRegex(trimmedQuery);
            default:
                return false;
        }
    }

    /**
     * 前缀必须是完整词：整段相等，或下一个字符是空白。/help 不命中 /helpful。
     */
    private boolean matchesPrefix(String trimmedQuery, String prefix) {
        if (trimmedQuery.equalsIgnoreCase(prefix)) {
            return true;
        }
        if (trimmedQuery.length() <= prefix.length()) {
            return false;
        }
        if (!trimmedQuery.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        return Character.isWhitespace(trimmedQuery.charAt(prefix.length()));
    }

    private boolean matchesRegex(String trimmedQuery) {
        try {
            ensureCompiled();
            if (compiledRegex == null) {
                return false;
            }
            return compiledRegex.matcher(new StepLimitedCharSequence(trimmedQuery, MAX_REGEX_STEPS)).matches();
        } catch (IllegalStateException ex) {
            log.warn("[RuleItem] 正则步数超限，按未命中处理, ruleCode={}, pattern={}", ruleCode, patternExpr);
            return false;
        }
    }

    /**
     * 根据正则分组变量提取并插桩参数
     * 例如: pattern = ^/query\s+user_id=(\d+)$
     *      query = /query user_id=10001
     *      paramTemplate = {"userId": "$1"}
     * 输出: {"userId": "10001"}
     */
    public String extractParams(String query) {
        if (paramTemplate == null || paramTemplate.trim().isEmpty() || matchType != MatchType.REGEX) {
            return paramTemplate;
        }
        if (query == null || patternExpr == null) {
            return paramTemplate;
        }
        try {
            ensureCompiled();
            if (compiledRegex == null) {
                return paramTemplate;
            }
            Matcher matcher = compiledRegex.matcher(new StepLimitedCharSequence(query.trim(), MAX_REGEX_STEPS));
            if (!matcher.matches()) {
                return paramTemplate;
            }
            // 只扫描原始模板中的 $n。$12 不会被 $1 截断，写入的捕获值也不会再次替换。
            Matcher token = PARAM_TOKEN.matcher(paramTemplate);
            StringBuilder result = new StringBuilder();
            while (token.find()) {
                int index = Integer.parseInt(token.group(1));
                String replacement;
                if (index >= 1 && index <= matcher.groupCount()) {
                    String value = matcher.group(index);
                    replacement = value != null ? value : "";
                } else {
                    replacement = token.group();
                }
                token.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
            token.appendTail(result);
            return result.toString();
        } catch (IllegalStateException | NumberFormatException ex) {
            log.warn("[RuleItem] 参数提取失败，保留原模板, ruleCode={}: {}", ruleCode, ex.getMessage());
            return paramTemplate;
        }
    }

    private void ensureCompiled() {
        if (compiledRegex == null && patternExpr != null && !patternExpr.isEmpty()) {
            compiledRegex = Pattern.compile(patternExpr, Pattern.CASE_INSENSITIVE);
        }
    }

    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String ruleCode) { this.ruleCode = ruleCode; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public MatchType getMatchType() { return matchType; }
    public void setMatchType(MatchType matchType) { this.matchType = matchType; }

    public String getPatternExpr() { return patternExpr; }
    public void setPatternExpr(String patternExpr) {
        this.patternExpr = patternExpr;
        if (this.matchType == MatchType.REGEX && patternExpr != null) {
            this.compiledRegex = Pattern.compile(patternExpr, Pattern.CASE_INSENSITIVE);
        }
    }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetRef() { return targetRef; }
    public void setTargetRef(String targetRef) { this.targetRef = targetRef; }

    public String getParamTemplate() { return paramTemplate; }
    public void setParamTemplate(String paramTemplate) { this.paramTemplate = paramTemplate; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Pattern getCompiledRegex() { return compiledRegex; }

    /**
     * 提取指令简写前缀 (例如 /help, #ping, /query)
     */
    public String getCommandPrefix() {
        if (patternExpr == null) return "";
        String cleaned = patternExpr.startsWith("^") ? patternExpr.substring(1) : patternExpr;
        int spaceIdx = cleaned.indexOf("\\s+");
        if (spaceIdx == -1) {
            spaceIdx = cleaned.indexOf(" ");
        }
        if (spaceIdx != -1) {
            return cleaned.substring(0, spaceIdx).trim();
        }
        return cleaned.replaceAll("[$^]", "").trim();
    }

    /**
     * 提取补全模板 (例如 /help, #ping, /query user_id=)
     */
    public String getCommandTemplate() {
        if (patternExpr == null) return "";
        if (matchType == MatchType.EXACT || matchType == MatchType.PREFIX) {
            return patternExpr.trim();
        }
        // REGEX 表达式提取模板: ^/query\s+user_id=(\d+)$ -> /query user_id=
        String cleaned = patternExpr.replaceAll("^\\^", "").replaceAll("\\$$", "");
        cleaned = cleaned.replaceAll("\\\\s\\+", " ");
        cleaned = cleaned.replaceAll("\\([^)]*\\)", "");
        return cleaned.trim();
    }

    // 兼容原旧方法
    public String getPattern() { return patternExpr; }
    public String getTargetIntent() { return ruleCode; }
    public String getDirectReply() { return "STATIC_TEXT".equalsIgnoreCase(targetType) ? targetRef : null; }

    /**
     * 统计 Matcher 对字符的访问次数，子序列与原序列共享计数。
     */
    private static final class StepLimitedCharSequence implements CharSequence {

        private final CharSequence inner;
        private final int maxSteps;
        private final int[] steps;

        private StepLimitedCharSequence(CharSequence inner, int maxSteps) {
            this(inner, maxSteps, new int[1]);
        }

        private StepLimitedCharSequence(CharSequence inner, int maxSteps, int[] steps) {
            this.inner = inner;
            this.maxSteps = maxSteps;
            this.steps = steps;
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public char charAt(int index) {
            if (++steps[0] > maxSteps) {
                throw new IllegalStateException("regex step limit exceeded");
            }
            return inner.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new StepLimitedCharSequence(inner.subSequence(start, end), maxSteps, steps);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
