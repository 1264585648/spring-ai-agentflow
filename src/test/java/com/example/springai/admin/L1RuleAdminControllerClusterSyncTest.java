package com.example.springai.admin;

import com.example.springai.admin.controller.L1RuleAdminController;
import com.example.springai.admin.dto.RuleCreateRequest;
import com.example.springai.admin.dto.RuleResponse;
import com.example.springai.pipeline.dispatcher.L1ToolCatalog;
import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import com.example.springai.pipeline.intent.L1RuleRegistry;
import com.example.springai.pipeline.repository.RuleDefinitionRepository;
import com.example.springai.pipeline.sync.ClusterSyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class L1RuleAdminControllerClusterSyncTest {

    @Test
    void clusterSyncFailureKeepsTheLocalSnapshot() {
        RuleDefinitionRepository repository = mock(RuleDefinitionRepository.class);
        L1RuleRegistry registry = new L1RuleRegistry(repository, Runnable::run);
        List<RuleDefinitionEntity> stored = new ArrayList<>();
        when(repository.findByRuleCode("CMD_OK")).thenReturn(Optional.empty());
        when(repository.save(any(RuleDefinitionEntity.class))).thenAnswer(invocation -> {
            RuleDefinitionEntity entity = invocation.getArgument(0);
            entity.setId(11L);
            stored.clear();
            stored.add(entity);
            return entity;
        });
        when(repository.findByIsEnabledOrderByPriorityAsc(1)).thenAnswer(invocation -> List.copyOf(stored));

        ApplicationEventPublisher publisher = event -> registry.reload();
        L1RuleAdminController controller = new L1RuleAdminController(
                repository,
                publisher,
                registry,
                mock(L1ToolCatalog.class),
                reason -> ClusterSyncResult.failed("portal down"));

        ResponseEntity<?> response = controller.createRule(validRequest());

        assertEquals(200, response.getStatusCode().value());
        RuleResponse body = (RuleResponse) response.getBody();
        assertEquals(ClusterSyncResult.FAILED, body.getClusterSync());
        assertTrue(registry.match("ping").isMatched());
        assertEquals("CMD_OK", registry.match("ping").getRuleCode());
    }

    @Test
    void invalidRegexIsRejectedBeforeSave() {
        RuleDefinitionRepository repository = mock(RuleDefinitionRepository.class);
        when(repository.findByRuleCode("CMD_BAD")).thenReturn(Optional.empty());
        L1RuleAdminController controller = new L1RuleAdminController(
                repository,
                event -> {
                },
                new L1RuleRegistry(repository, Runnable::run),
                mock(L1ToolCatalog.class),
                reason -> ClusterSyncResult.ok());

        RuleCreateRequest request = validRequest();
        request.setRuleCode("CMD_BAD");
        request.setMatchType("REGEX");
        request.setPatternExpr("(");

        ResponseEntity<?> response = controller.createRule(request);

        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertTrue(body.get("error").contains("正则表达式不合法"));
        verify(repository, never()).save(any());
    }

    private RuleCreateRequest validRequest() {
        RuleCreateRequest request = new RuleCreateRequest();
        request.setRuleCode("CMD_OK");
        request.setRuleName("探活");
        request.setMatchType("EXACT");
        request.setPatternExpr("ping");
        request.setTargetType("STATIC_TEXT");
        request.setTargetRef("pong");
        return request;
    }
}
