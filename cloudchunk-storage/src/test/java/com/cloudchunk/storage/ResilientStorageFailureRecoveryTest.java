package com.cloudchunk.storage;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic MinIO outage demonstrates fail-fast and manual half-open recovery. */
class ResilientStorageFailureRecoveryTest {
    @Test
    void storageCircuitOpensAfterFailuresAndClosesAfterRecovery() {
        StorageStrategy backend = mock(StorageStrategy.class);
        when(backend.exists("test-bucket", "synthetic"))
            .thenThrow(new IllegalStateException("MinIO down"))
            .thenThrow(new IllegalStateException("MinIO down"))
            .thenReturn(true);
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMillis(50))
            .permittedNumberOfCallsInHalfOpenState(1)
            .build();
        CircuitBreaker breaker = CircuitBreaker.of("synthetic-minio", config);
        StorageStrategy guarded = new ResilientStorageStrategy(backend, breaker);
        assertThrows(IllegalStateException.class, () -> guarded.exists("test-bucket", "synthetic"));
        assertThrows(IllegalStateException.class, () -> guarded.exists("test-bucket", "synthetic"));
        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
        assertThrows(CallNotPermittedException.class, () -> guarded.exists("test-bucket", "synthetic"));
        verify(backend, times(2)).exists("test-bucket", "synthetic");
        breaker.transitionToHalfOpenState();
        assertTrue(guarded.exists("test-bucket", "synthetic"));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        verify(backend, times(3)).exists("test-bucket", "synthetic");
    }
}
