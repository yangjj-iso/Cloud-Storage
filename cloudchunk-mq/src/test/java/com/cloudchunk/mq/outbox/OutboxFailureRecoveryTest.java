package com.cloudchunk.mq.outbox;

import com.cloudchunk.infra.outbox.OutboxEvent;
import com.cloudchunk.infra.outbox.OutboxEventMapper;
import com.cloudchunk.infra.tracing.IntegrationEventTracing;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic broker send failure: durable retry and recovery without real MQ or user events. */
class OutboxFailureRecoveryTest {
    @Test
    void brokerOutagePersistsSafeRetryAndSubsequentAcknowledgementPublishes() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        RocketMQTemplate rocket = mock(RocketMQTemplate.class);
        IntegrationEventTracing tracing = mock(IntegrationEventTracing.class);
        IntegrationEventTracing.TraceScope scope = mock(IntegrationEventTracing.TraceScope.class);
        when(tracing.startProducer(any(), anyString())).thenReturn(scope);
        OutboxEvent row = new OutboxEvent();
        row.setId(71L);
        row.setEventId("ci-synthetic-event");
        row.setEventType("FileAvailable");
        row.setSchemaVersion(1);
        row.setDestination("ci-test-topic");
        row.setAggregateKey("ci-test-file");
        row.setPayload("{\"eventId\":\"ci-synthetic-event\",\"fileId\":\"ci-test-file\"}");
        row.setAttemptCount(0);
        when(mapper.findReady(any(LocalDateTime.class), any(LocalDateTime.class), eq(100)))
            .thenReturn(List.of(row));
        when(mapper.claim(eq(71L), anyString(), any(), any())).thenReturn(1);
        when(rocket.syncSend(eq("ci-test-topic"), any(org.springframework.messaging.Message.class), eq(3000L)))
            .thenThrow(new IllegalStateException("broker-url-with-secret"))
            .thenReturn(sendOk());

        var publisher = new OutboxPublisher(mapper, rocket, new ObjectMapper(),
            tracing, new SimpleMeterRegistry());
        publisher.publishReady();
        var error = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).markRetry(eq(71L), anyString(), any(LocalDateTime.class), error.capture());
        assertThat(error.getValue()).isEqualTo("SEND_FAILED");
        verify(mapper, never()).markPublished(anyLong(), anyString(), any());

        publisher.publishReady();
        verify(mapper).markPublished(eq(71L), anyString(), any(LocalDateTime.class));
        verify(rocket, times(2)).syncSend(eq("ci-test-topic"),
            any(org.springframework.messaging.Message.class), eq(3000L));
    }

    private static SendResult sendOk() {
        SendResult result = mock(SendResult.class);
        when(result.getSendStatus()).thenReturn(SendStatus.SEND_OK);
        return result;
    }
}
