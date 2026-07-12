package com.tencent.rca.archive;

import com.tencent.rca.domain.EvidenceBundle;
import com.tencent.rca.domain.RcaReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 证据快照归档服务 (详细设计文档 8.3).
 * 将报告与原始证据快照归档并返回可访问 URL, 用于回填 alert_case.report_url.
 * 优先级低: 第一版返回本地占位 URL, 后续接入腾讯云 COS. 归档失败不阻塞主流程.
 */
@Slf4j
@Service
public class EvidenceArchiveService {

    private static final String PLACEHOLDER_URL_PREFIX = "local://rca-reports/case-";

    /**
     * 归档报告与证据快照.
     *
     * @param caseId 案卷 ID
     * @param report 归因报告
     * @param bundle 证据集合
     * @return 报告访问 URL (归档失败时返回空字符串)
     */
    public String archive(Long caseId, RcaReport report, EvidenceBundle bundle) {
        try {
            int evidenceCount = bundle == null ? 0 : bundle.allEvidences().size();
            int bodyLength = report == null || report.markdownBody() == null ? 0 : report.markdownBody().length();
            log.info("归档案卷 {} 报告快照: 正文 {} 字符, 证据 {} 条", caseId, bodyLength, evidenceCount);
            // 第一版占位: 返回确定性本地 URL, 后续替换为 COS 上传后的真实地址
            return PLACEHOLDER_URL_PREFIX + caseId + ".md";
        } catch (RuntimeException ex) {
            log.warn("案卷 {} 报告归档失败, 不阻塞主流程: {}", caseId, ex.getMessage());
            return "";
        }
    }
}
