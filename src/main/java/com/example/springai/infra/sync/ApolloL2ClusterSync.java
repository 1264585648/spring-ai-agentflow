package com.example.springai.infra.sync;

import com.ctrip.framework.apollo.openapi.client.ApolloOpenApiClient;
import com.ctrip.framework.apollo.openapi.dto.NamespaceReleaseDTO;
import com.ctrip.framework.apollo.openapi.dto.OpenItemDTO;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 Apollo OpenAPI 实现的 L2 向量库集群版本发布器
 */
@Slf4j
public class ApolloL2ClusterSync implements L2ClusterSync {

    static final String REVISION_KEY = "l2.vector.revision";

    private final ApolloOpenApiClient client;
    private final String appId;
    private final String env;
    private final String cluster;
    private final String namespace;
    private final String operator;

    public ApolloL2ClusterSync(String appId, String portalUrl, String token, String env,
                               String cluster, String namespace, String operator) {
        this.client = ApolloOpenApiClient.newBuilder()
                .withPortalUrl(portalUrl)
                .withToken(token)
                .withConnectTimeout(3000)
                .withReadTimeout(5000)
                .build();
        this.appId = appId;
        this.env = env;
        this.cluster = cluster;
        this.namespace = namespace;
        this.operator = operator;
    }

    @Override
    public ClusterSyncResult publishRevision(String reason) {
        String revision = String.valueOf(System.currentTimeMillis());
        try {
            OpenItemDTO item = new OpenItemDTO();
            item.setKey(REVISION_KEY);
            item.setValue(revision);
            item.setComment(reason);
            item.setDataChangeCreatedBy(operator);
            item.setDataChangeLastModifiedBy(operator);
            client.createOrUpdateItem(appId, env, cluster, namespace, item);

            NamespaceReleaseDTO release = new NamespaceReleaseDTO();
            release.setReleaseTitle("l2-vector-" + revision);
            release.setReleaseComment(reason);
            release.setReleasedBy(operator);
            release.setEmergencyPublish(false);
            client.publishNamespace(appId, env, cluster, namespace, release);
            log.info("[Apollo] 已发布 L2 向量库版本号 {} namespace={}", revision, namespace);
            return ClusterSyncResult.ok();
        } catch (Exception ex) {
            log.error("[Apollo] 发布 L2 向量库版本号失败: {}", ex.getMessage(), ex);
            return ClusterSyncResult.failed(ex.getMessage());
        }
    }
}
