package com.example.springai.infra.sync;

/**
 * L2 向量知识库分布式集群同步发布器 SPI (L2ClusterSync)
 * 职责:
 * 当管理端新增/更新 FAQ 后，向集群各节点广播版本号变更，通知各节点拉取 MySQL 并刷新本地内存向量快照。
 */
public interface L2ClusterSync {

    ClusterSyncResult publishRevision(String reason);
}
