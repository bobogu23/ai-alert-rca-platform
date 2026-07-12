package com.tencent.rca.domain;

import com.tencent.rca.common.enums.EvidenceSourceType;

/**
 * 单条证据, 必须携带可追溯引用, 杜绝"裸结论" (详细设计文档 2.3).
 *
 * @param description 证据描述
 * @param type        证据来源类型
 * @param sourceRef   原始查询条件/TraceID/LogID/代码文件行号, 用于追溯
 * @param rawSnippet  原始片段(裁剪后), 供 Critic 核对引用是否准确
 */
public record Evidence(
        String description,
        EvidenceSourceType type,
        String sourceRef,
        String rawSnippet) {
}
