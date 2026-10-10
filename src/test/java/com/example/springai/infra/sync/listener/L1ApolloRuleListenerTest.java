package com.example.springai.infra.sync.listener;

import com.example.springai.core.routing.L1RuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class L1ApolloRuleListenerTest {

    @Mock
    private L1RuleRegistry ruleRegistry;

    @InjectMocks
    private L1ApolloRuleListener apolloRuleListener;

    @Test
    @DisplayName("验证 Apollo 监听到全集群配置变更时，准确触发本地 ruleRegistry.reload()")
    void testHandleConfigChangeTriggersReload() {
        apolloRuleListener.setRulesNamespace("agent.l1.rules");

        Set<String> changedKeys = Set.of("rules.version", "rules.refresh.timestamp");
        apolloRuleListener.handleConfigChange("agent.l1.rules", changedKeys);

        // 验证 ruleRegistry.reload() 被确切调用了 1 次
        verify(ruleRegistry, times(1)).reload();
    }
}
