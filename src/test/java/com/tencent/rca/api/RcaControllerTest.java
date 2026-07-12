package com.tencent.rca.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tencent.rca.governance.CaseService;
import com.tencent.rca.governance.DedupEngine;
import com.tencent.rca.governance.DedupResult;
import com.tencent.rca.governance.SuppressionEngine;
import com.tencent.rca.governance.SuppressionResult;
import com.tencent.rca.notify.NotificationGateway;
import com.tencent.rca.orchestrator.Orchestrator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RcaController Web 层测试: 校验接口契约、参数校验(400)、抑制/归并/限流分支. 服务层全部 mock.
 */
@WebMvcTest(RcaController.class)
class RcaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DedupEngine dedupEngine;
    @MockBean
    private SuppressionEngine suppressionEngine;
    @MockBean
    private Orchestrator orchestrator;
    @MockBean
    private CaseService caseService;
    @MockBean
    private NotificationGateway notificationGateway;

    private static final String VALID_BODY = """
            {
              "alertId": "a1",
              "serviceName": "svc",
              "env": "prod",
              "alertSource": "CLS",
              "alertType": "LOG_THRESHOLD",
              "timeWindow": {"start": "2026-07-10T10:00:00+08:00", "end": "2026-07-10T10:05:00+08:00"},
              "rawAlertPayload": {"k": "v"}
            }
            """;

    @Test
    void shouldReturn202AndAnalyzingForNewAlert() throws Exception {
        when(suppressionEngine.evaluate(any())).thenReturn(new SuppressionResult(false, null));
        when(dedupEngine.check(any())).thenReturn(new DedupResult(true, 10L));
        when(orchestrator.analyzeAsync(anyLong(), any())).thenReturn(true);

        mockMvc.perform(post("/api/rca/analyze").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.caseId").value("10"))
                .andExpect(jsonPath("$.status").value("ANALYZING"));
    }

    @Test
    void shouldReturnSuppressedWhenSuppressed() throws Exception {
        when(suppressionEngine.evaluate(any())).thenReturn(new SuppressionResult(true, "MAINTENANCE"));

        mockMvc.perform(post("/api/rca/analyze").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"));
    }

    @Test
    void shouldReturnMergedWhenDuplicate() throws Exception {
        when(suppressionEngine.evaluate(any())).thenReturn(new SuppressionResult(false, null));
        when(dedupEngine.check(any())).thenReturn(new DedupResult(false, 99L));

        mockMvc.perform(post("/api/rca/analyze").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("MERGED"));
    }

    @Test
    void shouldReturn503WhenRejectedByConcurrency() throws Exception {
        when(suppressionEngine.evaluate(any())).thenReturn(new SuppressionResult(false, null));
        when(dedupEngine.check(any())).thenReturn(new DedupResult(true, 11L));
        when(orchestrator.analyzeAsync(anyLong(), any())).thenReturn(false);

        mockMvc.perform(post("/api/rca/analyze").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void shouldReturn400WhenMissingRequiredField() throws Exception {
        String invalid = "{\"serviceName\": \"svc\"}";

        mockMvc.perform(post("/api/rca/analyze").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn404WhenCaseNotFound() throws Exception {
        when(caseService.getById(anyLong())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/rca/cases/123"))
                .andExpect(status().isNotFound());
    }
}
