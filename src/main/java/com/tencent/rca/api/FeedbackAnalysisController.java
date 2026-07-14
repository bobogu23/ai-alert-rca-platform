package com.tencent.rca.api;

import com.tencent.rca.governance.FeedbackAggregation;
import com.tencent.rca.governance.FeedbackAnalysisService;
import com.tencent.rca.governance.FlywheelMetrics;
import com.tencent.rca.governance.RegressionSample;
import com.tencent.rca.governance.RegressionSampleExporter;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈治理与飞轮指标查询接口 (在线自我完善方案 07 文档 3、6.2).
 * 提供: 飞轮健康度指标查询、反馈多维聚合分析、高可信 badcase 导出为回归集样本.
 * 均为运营/看板侧接口, 与告警接入主链路解耦.
 */
@RestController
@RequestMapping("/api/rca/feedback")
public class FeedbackAnalysisController {

    private static final int DEFAULT_WINDOW_DAYS = 7;

    private final FeedbackAnalysisService analysisService;
    private final RegressionSampleExporter regressionSampleExporter;

    public FeedbackAnalysisController(FeedbackAnalysisService analysisService,
                                      RegressionSampleExporter regressionSampleExporter) {
        this.analysisService = analysisService;
        this.regressionSampleExporter = regressionSampleExporter;
    }

    /**
     * 查询经验飞轮健康度指标 (采纳率等).
     *
     * @param windowDays 统计窗口天数, 默认 7
     * @return 飞轮指标
     */
    @GetMapping("/metrics")
    public ResponseEntity<FlywheelMetrics> metrics(
            @RequestParam(name = "windowDays", defaultValue = "" + DEFAULT_WINDOW_DAYS) int windowDays) {
        return ResponseEntity.ok(analysisService.computeFlywheelMetrics(windowDays));
    }

    /**
     * 查询反馈多维聚合分析.
     *
     * @param windowDays 统计窗口天数, 默认 7
     * @return 聚合结果
     */
    @GetMapping("/aggregation")
    public ResponseEntity<FeedbackAggregation> aggregation(
            @RequestParam(name = "windowDays", defaultValue = "" + DEFAULT_WINDOW_DAYS) int windowDays) {
        return ResponseEntity.ok(analysisService.aggregate(windowDays));
    }

    /**
     * 导出高可信 badcase 为回归集样本 (脱敏后), 并标记已导出.
     *
     * @param windowDays 统计窗口天数, 默认 7
     * @return 导出的回归样本列表
     */
    @PostMapping("/regression-export")
    public ResponseEntity<List<RegressionSample>> exportRegression(
            @RequestParam(name = "windowDays", defaultValue = "" + DEFAULT_WINDOW_DAYS) int windowDays) {
        return ResponseEntity.ok(regressionSampleExporter.exportRejectedBadcases(windowDays));
    }
}
