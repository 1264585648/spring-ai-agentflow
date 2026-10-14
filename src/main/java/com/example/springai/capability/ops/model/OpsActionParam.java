package com.example.springai.capability.ops.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 运维执行参数，必须完整承载已批准的目标、容量、原因和实际操作人。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsActionParam {
    private String service;
    private String actionType;
    private String targetIdentifier;
    private String reason;
    private String operator;
    private String idempotencyKey;
    private Integer maxPoolSize;
}
