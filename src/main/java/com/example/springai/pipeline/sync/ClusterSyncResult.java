package com.example.springai.pipeline.sync;

/**
 * 集群版本号广播结果。失败不回滚已经在本机生效的规则。
 */
public final class ClusterSyncResult {

    public static final String OK = "OK";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    private final String status;
    private final String detail;

    private ClusterSyncResult(String status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    public static ClusterSyncResult ok() {
        return new ClusterSyncResult(OK, null);
    }

    public static ClusterSyncResult skipped() {
        return new ClusterSyncResult(SKIPPED, null);
    }

    public static ClusterSyncResult failed(String detail) {
        return new ClusterSyncResult(FAILED, detail);
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }
}
