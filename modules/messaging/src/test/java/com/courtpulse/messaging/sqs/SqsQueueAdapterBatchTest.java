package com.courtpulse.messaging.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.courtpulse.messaging.queue.QueueSendRequest;
import com.courtpulse.messaging.queue.SendOutcome;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import software.amazon.awssdk.services.sqs.model.SqsException;

class SqsQueueAdapterBatchTest {
    private final SqsClient client = mock(SqsClient.class);
    private final SqsQueueAdapter adapter = new SqsQueueAdapter(client, "https://queue.example/q.fifo");

    @Test
    void mapsEachEntryToItsOwnOutcomeInRequestOrder() {
        when(client.sendMessageBatch(any(SendMessageBatchRequest.class))).thenReturn(
                SendMessageBatchResponse.builder()
                        .successful(SendMessageBatchResultEntry.builder().id("2").messageId("m-2").build(),
                                SendMessageBatchResultEntry.builder().id("0").messageId("m-0").build())
                        .failed(BatchResultErrorEntry.builder().id("1").code("InternalError")
                                        .senderFault(false).build(),
                                BatchResultErrorEntry.builder().id("3").code("InvalidParameterValue")
                                        .senderFault(true).build())
                        .build());

        List<SendOutcome> outcomes = adapter.sendBatch(requests(5));

        assertEquals("m-0", outcomes.get(0).providerMessageId());
        assertTrue(outcomes.get(1).failure().retryable(), "server-side entry failure is retried");
        assertEquals("m-2", outcomes.get(2).providerMessageId());
        assertFalse(outcomes.get(3).failure().retryable(), "sender fault is permanent");
        assertTrue(outcomes.get(4).failure().retryable(), "an entry missing from the response is retried");
        assertEquals("SQS batch entry failed: InvalidParameterValue", outcomes.get(3).failure().getMessage());

        ArgumentCaptor<SendMessageBatchRequest> sent = ArgumentCaptor.forClass(SendMessageBatchRequest.class);
        verify(client).sendMessageBatch(sent.capture());
        assertEquals(SqsQueueAdapter.SEND_ATTEMPT_TIMEOUT, sent.getValue().overrideConfiguration()
                .flatMap(override -> override.apiCallAttemptTimeout()).orElseThrow());
        var entry = sent.getValue().entries().get(1);
        assertEquals("group-1", entry.messageGroupId());
        assertEquals("dedup-1", entry.messageDeduplicationId());
        assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                entry.messageAttributes().get("traceparent").stringValue());
    }

    @Test
    void wholeCallFailureFailsEveryEntryWithTheClassifiedRetryability() {
        when(client.sendMessageBatch(any(SendMessageBatchRequest.class)))
                .thenThrow(SqsException.builder().statusCode(503).message("unavailable").build());

        List<SendOutcome> outcomes = adapter.sendBatch(requests(3));

        assertEquals(3, outcomes.size());
        assertTrue(outcomes.stream().noneMatch(SendOutcome::succeeded));
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.failure().retryable()));
    }

    @Test
    void rejectsMoreThanTheSqsBatchLimitAndIgnoresEmptyBatches() {
        assertThrows(IllegalArgumentException.class, () -> adapter.sendBatch(requests(11)));
        assertEquals(List.of(), adapter.sendBatch(List.of()));
    }

    private static List<QueueSendRequest> requests(int count) {
        return IntStream.range(0, count).mapToObj(index -> new QueueSendRequest("body-" + index,
                "group-" + index, "dedup-" + index,
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")).toList();
    }
}
