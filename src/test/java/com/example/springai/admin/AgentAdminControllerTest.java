package com.example.springai.admin;

import com.example.springai.admin.controller.AgentAdminController;
import com.example.springai.admin.dto.AgentCreateRequest;
import com.example.springai.admin.dto.AgentResponse;
import com.example.springai.admin.dto.AgentUpdateRequest;
import com.example.springai.pipeline.agent.AgentPromptRegistry;
import com.example.springai.pipeline.entity.AgentDefinitionEntity;
import com.example.springai.pipeline.entity.RuleDefinitionEntity;
import com.example.springai.pipeline.event.AgentDefinitionReloadEvent;
import com.example.springai.pipeline.repository.AgentDefinitionRepository;
import com.example.springai.pipeline.repository.RuleDefinitionRepository;
import com.example.springai.pipeline.sync.ClusterSyncResult;
import com.example.springai.pipeline.sync.L1ClusterSync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentAdminControllerTest {

    private AgentDefinitionRepository agentRepository;
    private RuleDefinitionRepository ruleRepository;
    private ApplicationEventPublisher eventPublisher;
    private L1ClusterSync clusterSync;
    private AgentPromptRegistry promptRegistry;
    private AgentAdminController controller;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentDefinitionRepository.class);
        ruleRepository = mock(RuleDefinitionRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        clusterSync = mock(L1ClusterSync.class);
        promptRegistry = new AgentPromptRegistry(agentRepository);

        when(clusterSync.publishRevision(anyString()))
                .thenReturn(ClusterSyncResult.ok());

        controller = new AgentAdminController(
                agentRepository,
                ruleRepository,
                eventPublisher,
                clusterSync
        );
    }

    @Test
    @DisplayName("测试成功动态新增业务智能体，并触发事件广播与版本推送")
    void testCreateBusinessAgentSuccess() {
        AgentCreateRequest req = new AgentCreateRequest();
        req.setAgentCode("INVOICE_EXPERT");
        req.setAgentName("发票审核与开具专家");
        req.setLayer("BUSINESS");
        req.setSystemPrompt("你是一个专业增值税发票审核与开具专家。");
        req.setDispatchDesc("负责发票开具、发票抬头核验与冲红驳回逻辑");
        req.setTemperature(0.2);

        when(agentRepository.existsByAgentCode("INVOICE_EXPERT")).thenReturn(false);
        when(agentRepository.save(any(AgentDefinitionEntity.class))).thenAnswer(inv -> {
            AgentDefinitionEntity entity = inv.getArgument(0);
            entity.setId(100L);
            return entity;
        });

        ResponseEntity<?> response = controller.createAgent(req);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof AgentResponse);
        AgentResponse resp = (AgentResponse) response.getBody();
        assertEquals("INVOICE_EXPERT", resp.getAgentCode());
        assertEquals("BUSINESS_SUB", resp.getAgentType());
        assertEquals(0, resp.getIsSystemCore());
        assertEquals("ONLINE", resp.getStatus());

        // 验证触发了 Spring 热重载事件
        verify(eventPublisher, times(1)).publishEvent(any(AgentDefinitionReloadEvent.class));
        // 验证触发了集群广播
        verify(clusterSync, times(1)).publishRevision(anyString());
    }

    @Test
    @DisplayName("测试更新智能体人设与采样温度")
    void testUpdateAgentPrompt() {
        AgentDefinitionEntity existing = AgentDefinitionEntity.builder()
                .id(1L)
                .agentCode("LOG_DIAGNOSE_AGENT")
                .agentName("日志异常分析智能体")
                .systemPrompt("旧人设")
                .dispatchDesc("旧描述")
                .temperature(0.3)
                .version(1)
                .isSystemCore(0)
                .status("ONLINE")
                .build();

        when(agentRepository.findByAgentCode("LOG_DIAGNOSE_AGENT")).thenReturn(Optional.of(existing));
        when(agentRepository.save(any(AgentDefinitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentUpdateRequest req = new AgentUpdateRequest();
        req.setSystemPrompt("新人设：增加对 OOM 堆内存与线程死锁日志分析逻辑。");
        req.setTemperature(0.15);

        ResponseEntity<?> response = controller.updateAgent("LOG_DIAGNOSE_AGENT", req);
        assertEquals(200, response.getStatusCode().value());
        AgentResponse resp = (AgentResponse) response.getBody();
        assertNotNull(resp);
        assertEquals(2, resp.getVersion());
        assertEquals(0.15, resp.getTemperature());
        assertEquals("新人设：增加对 OOM 堆内存与线程死锁日志分析逻辑。", resp.getSystemPrompt());

        verify(eventPublisher, times(1)).publishEvent(any(AgentDefinitionReloadEvent.class));
    }

    @Test
    @DisplayName("测试 RULE-06 红线：严禁对智能体执行物理删除 (DELETE 接口必须返回 400)")
    void testDeleteAgentRejectedByRule06() {
        ResponseEntity<?> response = controller.deleteAgent("ANY_AGENT");
        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("RULE-06"));
        assertTrue(response.getBody().toString().contains("严禁物理删除"));

        verify(agentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("测试系统核心骨架智能体 (is_system_core=1) 受到锁定保护，禁止下线或停用")
    void testSystemCoreAgentCannotBeOfflined() {
        AgentDefinitionEntity masterCore = AgentDefinitionEntity.builder()
                .agentCode("MASTER_AGENT")
                .isSystemCore(1)
                .status("ONLINE")
                .build();

        when(agentRepository.findByAgentCode("MASTER_AGENT")).thenReturn(Optional.of(masterCore));

        Map<String, String> body = Map.of("status", "OFFLINE");
        ResponseEntity<?> response = controller.updateStatus("MASTER_AGENT", body);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("系统核心骨架智能体 (is_system_core=1) 受到锁定保护"));
    }

    @Test
    @DisplayName("测试下线业务智能体时触发依赖审计：存在 L1 规则硬依赖时阻断下线")
    void testOfflineBlockedByRuleDependency() {
        AgentDefinitionEntity subAgent = AgentDefinitionEntity.builder()
                .agentCode("DB_DIAGNOSE_AGENT")
                .isSystemCore(0)
                .status("ONLINE")
                .build();

        when(agentRepository.findByAgentCode("DB_DIAGNOSE_AGENT")).thenReturn(Optional.of(subAgent));

        // 模拟存在一条正在运行的规则，target_ref 指向 DB_DIAGNOSE_AGENT
        RuleDefinitionEntity activeRule = new RuleDefinitionEntity();
        activeRule.setRuleCode("CMD_AUTO_SLOW_SQL");
        activeRule.setIsEnabled(1);
        activeRule.setTargetRef("DB_DIAGNOSE_AGENT.checkSlowSql");
        when(ruleRepository.findAll()).thenReturn(List.of(activeRule));

        Map<String, String> body = Map.of("status", "DEPRECATED");
        ResponseEntity<?> response = controller.updateStatus("DB_DIAGNOSE_AGENT", body);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("下线阻断"));
        assertTrue(response.getBody().toString().contains("CMD_AUTO_SLOW_SQL"));
    }

    @Test
    @DisplayName("测试无依赖的业务智能体顺利流转至 DEPRECATED / OFFLINE 状态")
    void testOfflineSuccessWithoutDependency() {
        AgentDefinitionEntity subAgent = AgentDefinitionEntity.builder()
                .agentCode("SRE_COPILOT_AGENT")
                .isSystemCore(0)
                .status("ONLINE")
                .isEnabled(1)
                .version(1)
                .build();

        when(agentRepository.findByAgentCode("SRE_COPILOT_AGENT")).thenReturn(Optional.of(subAgent));
        when(ruleRepository.findAll()).thenReturn(Collections.emptyList());
        when(agentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> body = Map.of("status", "OFFLINE");
        ResponseEntity<?> response = controller.updateStatus("SRE_COPILOT_AGENT", body);

        assertEquals(200, response.getStatusCode().value());
        AgentResponse resp = (AgentResponse) response.getBody();
        assertNotNull(resp);
        assertEquals("OFFLINE", resp.getStatus());
        assertEquals(0, resp.getIsEnabled(), "下线后 isEnabled 应同步变为 0");
    }
}
