package com.example.springai.core.routing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleItemTest {

    @Test
    void prefixMatchesWholeTokenOnly() {
        RuleItem help = rule("CMD_HELP", RuleItem.MatchType.PREFIX, "/help", "STATIC_TEXT", "menu", null);

        assertTrue(help.matches("/help"));
        assertTrue(help.matches("/HELP extra"));
        assertFalse(help.matches("/helpful"));
        assertFalse(help.matches("help"));
    }

    @Test
    void regexRequiresFullMatch() {
        RuleItem query = rule("CMD_QUERY", RuleItem.MatchType.REGEX, "^/query\\s+user_id=(\\d+)$",
                "TOOL", "userAccountTool.queryBalance", "{\"userId\":\"$1\"}");

        assertTrue(query.matches("/query user_id=12345"));
        assertFalse(query.matches("please /query user_id=12345 now"));
        assertEquals("{\"userId\":\"12345\"}", query.extractParams("/query user_id=12345"));
    }

    @Test
    void parameterReplacementDoesNotRescanInsertedTextOrShorterGroups() {
        RuleItem item = rule("CMD_RAW", RuleItem.MatchType.REGEX, "^(.*)\\|(.*)$",
                "STATIC_TEXT", "ok", "{\"v\":\"$1\"}");
        assertEquals("{\"v\":\"a$2b\"}", item.extractParams("a$2b|Z"));

        RuleItem single = rule("CMD_ONE", RuleItem.MatchType.REGEX, "^(9)$",
                "STATIC_TEXT", "ok", "$1-$12");
        assertEquals("9-$12", single.extractParams("9"));

        RuleItem quoted = rule("CMD_QUOTE", RuleItem.MatchType.REGEX, "^(.+)$",
                "STATIC_TEXT", "ok", "$1");
        assertEquals("a$b\\c", quoted.extractParams("a$b\\c"));
    }

    @Test
    void catastrophicRegexStopsAtStepLimit() {
        RuleItem item = rule("CMD_REDOS", RuleItem.MatchType.REGEX, "(a+)+b", "STATIC_TEXT", "x", null);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertFalse(item.matches("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
    }

    private RuleItem rule(String code, RuleItem.MatchType type, String pattern, String targetType,
                          String targetRef, String template) {
        return new RuleItem(code, code, type, pattern, targetType, targetRef, template, 1, null);
    }
}
