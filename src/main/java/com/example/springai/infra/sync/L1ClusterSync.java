package com.example.springai.infra.sync;

/**
 * 把本机规则变更通知到其他节点。规则正文仍在 MySQL，这里只广播一个版本号。
 */
public interface L1ClusterSync {

    ClusterSyncResult publishRevision(String reason);
}
