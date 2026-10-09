package com.example.springai.pipeline.intent;

import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import com.example.springai.pipeline.repository.RuleDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class L1RuleRegistryReloadTest {

    @Test
    void invalidRegexIsSkippedAndValidRuleStaysActive() {
        RuleDefinitionRepository repository = mock(RuleDefinitionRepository.class);
        RuleDefinitionEntity broken = entity("CMD_BROKEN", "REGEX", "(", 1);
        RuleDefinitionEntity healthy = entity("CMD_OK", "EXACT", "hello", 2);
        when(repository.findByIsEnabledOrderByPriorityAsc(1)).thenReturn(List.of(broken, healthy));

        L1RuleRegistry registry = new L1RuleRegistry(repository, Runnable::run);
        registry.reload();

        assertFalse(registry.isLastReloadSuccessful());
        assertEquals(List.of("CMD_BROKEN"), registry.getSkippedRuleCodes());
        assertEquals(1, registry.getActiveRules().size());
        assertEquals("CMD_OK", registry.getActiveRules().get(0).getRuleCode());
        assertTrue(registry.match("hello").isMatched());
        assertFalse(registry.match("other").isMatched());
    }

    private RuleDefinitionEntity entity(String code, String matchType, String pattern, int priority) {
        RuleDefinitionEntity entity = new RuleDefinitionEntity();
        entity.setRuleCode(code);
        entity.setRuleName(code);
        entity.setMatchType(matchType);
        entity.setPatternExpr(pattern);
        entity.setTargetType("STATIC_TEXT");
        entity.setTargetRef("reply");
        entity.setPriority(priority);
        entity.setIsEnabled(1);
        return entity;
    }
}
