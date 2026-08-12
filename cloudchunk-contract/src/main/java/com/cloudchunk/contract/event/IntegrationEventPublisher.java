package com.cloudchunk.contract.event;

/**
 * Application port for durable integration-event publication.
 * Implementations must enqueue in the caller's database transaction.
 */
public interface IntegrationEventPublisher {

    void enqueue(String destination, String aggregateKey, IntegrationEvent event);
}
