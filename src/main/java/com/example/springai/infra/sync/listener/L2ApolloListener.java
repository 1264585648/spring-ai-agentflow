package com.example.springai.infra.sync.listener;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.example.springai.core.routing.L2VectorRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Apollo 分布式配置中心 L2 向量库热重载监听器 (L2ApolloListener)
 */
@Component
@ConditionalOnProperty(name = "apollo.bootstrap.enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class L2ApolloListener {

    private final L2VectorRegistry vectorRegistry;

    @Value("${apollo.rules.namespace:agent.l1.rules}")
    private String namespace;

    @PostConstruct
    public void init() {
        try {
            log.info("[Apollo] 正在注册 L2 向量库分布式监听器, Namespace: {}", namespace);
            Config config = ConfigService.getConfig(namespace);
            config.addChangeListener((ConfigChangeEvent changeEvent) -> {
                if (changeEvent.isChanged("l2.vector.revision")) {
                    handleConfigChange(changeEvent.getNamespace(), changeEvent.changedKeys());
                }
            });
            log.info("[Apollo] ✅ L2 向量库分布式监听器已成功启动");
        } catch (Exception e) {
            log.error("[Apollo] ❌ 注册 Apollo L2 监听器失败: {}", e.getMessage(), e);
        }
    }

    public void handleConfigChange(String namespace, Set<String> changedKeys) {
        log.info("[Apollo] 监听到 L2 向量库全集群配置变更通知, Namespace: {}, 变更键: {}", namespace, changedKeys);
        vectorRegistry.reload();
    }
}
