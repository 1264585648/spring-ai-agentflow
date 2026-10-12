package com.example.springai.infra.sync;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L2 集群同步组件自动装配
 */
@Configuration
public class L2ClusterSyncConfiguration {

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${apollo.openapi.portal-url:}') && T(org.springframework.util.StringUtils).hasText('${apollo.openapi.token:}')")
    public L2ClusterSync apolloL2ClusterSync(
            @Value("${app.id:spring-ai-agent}") String appId,
            @Value("${apollo.openapi.portal-url}") String portalUrl,
            @Value("${apollo.openapi.token}") String token,
            @Value("${apollo.openapi.env:DEV}") String env,
            @Value("${apollo.openapi.cluster:default}") String cluster,
            @Value("${apollo.rules.namespace:agent.l1.rules}") String namespace,
            @Value("${apollo.openapi.operator:apollo}") String operator) {
        return new ApolloL2ClusterSync(appId, portalUrl, token, env, cluster, namespace, operator);
    }

    @Bean
    @ConditionalOnMissingBean(L2ClusterSync.class)
    public L2ClusterSync noOpL2ClusterSync() {
        return reason -> ClusterSyncResult.skipped();
    }
}
