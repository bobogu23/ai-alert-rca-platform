package com.tencent.rca.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencent.rca.common.enums.ChannelType;
import com.tencent.rca.common.enums.ConfidenceLevel;
import com.tencent.rca.common.enums.SendStatus;
import com.tencent.rca.repository.NotificationRecordRepository;
import com.tencent.rca.repository.entity.NotificationRecordEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * NotificationGateway 单元测试: 校验路由、失败隔离与落库. 渠道与仓储 mock.
 */
class NotificationGatewayTest {

    private final NotificationRecordRepository recordRepository = Mockito.mock(NotificationRecordRepository.class);

    @Test
    void shouldDispatchToMatchingChannelAndPersistSuccess() {
        NotificationChannel wecom = channel(ChannelType.WECOM, new NotificationResult(true, null));
        NotificationGateway gateway = new NotificationGateway(List.of(wecom), recordRepository);

        Map<ChannelType, NotificationResult> results =
                gateway.dispatch(message("1"), List.of(ChannelType.WECOM));

        assertThat(results.get(ChannelType.WECOM).success()).isTrue();
        ArgumentCaptor<NotificationRecordEntity> captor = ArgumentCaptor.forClass(NotificationRecordEntity.class);
        verify(recordRepository).save(captor.capture());
        assertThat(captor.getValue().getSendStatus()).isEqualTo(SendStatus.SUCCESS);
        assertThat(captor.getValue().getCaseId()).isEqualTo(1L);
    }

    @Test
    void shouldReturnFailWhenChannelNotRegistered() {
        NotificationGateway gateway = new NotificationGateway(List.of(), recordRepository);

        Map<ChannelType, NotificationResult> results =
                gateway.dispatch(message("1"), List.of(ChannelType.WECOM));

        assertThat(results.get(ChannelType.WECOM).success()).isFalse();
    }

    @Test
    void shouldIsolateChannelExceptionAndPersistFailure() {
        NotificationChannel throwing = Mockito.mock(NotificationChannel.class);
        when(throwing.channelType()).thenReturn(ChannelType.WECOM);
        when(throwing.send(any())).thenThrow(new RuntimeException("boom"));
        NotificationGateway gateway = new NotificationGateway(List.of(throwing), recordRepository);

        Map<ChannelType, NotificationResult> results =
                gateway.dispatch(message("5"), List.of(ChannelType.WECOM));

        assertThat(results.get(ChannelType.WECOM).success()).isFalse();
        ArgumentCaptor<NotificationRecordEntity> captor = ArgumentCaptor.forClass(NotificationRecordEntity.class);
        verify(recordRepository).save(captor.capture());
        assertThat(captor.getValue().getSendStatus()).isEqualTo(SendStatus.FAILED);
    }

    private NotificationChannel channel(ChannelType type, NotificationResult result) {
        NotificationChannel channel = Mockito.mock(NotificationChannel.class);
        when(channel.channelType()).thenReturn(type);
        when(channel.send(any())).thenReturn(result);
        return channel;
    }

    private NotificationMessage message(String caseId) {
        return new NotificationMessage(caseId, "标题", "摘要", ConfidenceLevel.HIGH, null, "body");
    }
}
