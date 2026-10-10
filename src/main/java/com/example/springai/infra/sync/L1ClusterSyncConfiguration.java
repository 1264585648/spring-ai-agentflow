package com.example.springai.infra.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * portal 地址和 token 都存在时才创建 Apollo 发布器，否则使用空实现。
 */
@Configuration
public class L1ClusterSyncConfiguration {

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${apollo.openapi.portal-url:}') && T(org.springframework.util.StringUtils).hasText('${apollo.openapi.token:}')")
    public L1ClusterSync apolloL1ClusterSync(
            @Value("${app.id:spring-ai-agent}") String appId,
            @Value("${apollo.openapi.portal-url}") String portalUrl,
            @Value("${apollo.openapi.token}") String token,
            @Value("${apollo.openapi.env:DEV}") String env,
            @Value("${apollo.openapi.cluster:default}") String cluster,
            @Value("${apollo.rules.namespace:agent.l1.rules}") String namespace,
            @Value("${apollo.openapi.operator:apollo}") String operator) {
        return new ApolloL1ClusterSync(appId, portalUrl, token, env, cluster, namespace, operator);
    }

    @Bean
    @ConditionalOnMissingBean(L1ClusterSync.class)
    public L1ClusterSync noOpL1ClusterSync() {
        return reason -> ClusterSyncResult.skipped();
    }
}
