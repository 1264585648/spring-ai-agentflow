package com.example.springai.pipeline.intent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存中编译后的单条 L1 匹配规则运行时对象
 */
public class RuleItem {

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

    /**
     * 预编译后的正则表达式 Pattern 实例 (避免运行时重复编译，耗时从几毫秒降低至微秒级)
     */
    private Pattern compiledRegex;

    public RuleItem() {}

    public RuleItem(String ruleCode, String ruleName, MatchType matchType, String patternExpr,
                    String targetType, String targetRef, String paramTemplate, int priority) {
        this.ruleCode = ruleCode;
        this.ruleName = ruleName;
        this.matchType = matchType;
        this.patternExpr = patternExpr;
        this.targetType = targetType;
        this.targetRef = targetRef;
        this.paramTemplate = paramTemplate;
        this.priority = priority;
        if (matchType == MatchType.REGEX && patternExpr != null && !patternExpr.isEmpty()) {
            this.compiledRegex = Pattern.compile(patternExpr, Pattern.CASE_INSENSITIVE);
        }
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
                return trimmedQuery.toLowerCase().startsWith(patternExpr.trim().toLowerCase());
            case REGEX:
                if (compiledRegex == null) {
                    compiledRegex = Pattern.compile(patternExpr, Pattern.CASE_INSENSITIVE);
                }
                return compiledRegex.matcher(trimmedQuery).find();
            default:
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
        if (compiledRegex == null) {
            compiledRegex = Pattern.compile(patternExpr, Pattern.CASE_INSENSITIVE);
        }
        Matcher matcher = compiledRegex.matcher(query.trim());
        if (!matcher.find()) {
            return paramTemplate;
        }

        String result = paramTemplate;
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String val = matcher.group(i);
            result = result.replace("$" + i, val != null ? val : "");
        }
        return result;
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

    public Pattern getCompiledRegex() { return compiledRegex; }

    // 兼容原旧方法
    public String getPattern() { return patternExpr; }
    public String getTargetIntent() { return ruleCode; }
    public String getDirectReply() { return "STATIC_TEXT".equalsIgnoreCase(targetType) ? targetRef : null; }
}
