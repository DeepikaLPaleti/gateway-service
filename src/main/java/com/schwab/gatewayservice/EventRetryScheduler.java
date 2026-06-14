package com.schwab.gatewayservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class EventRetryScheduler {

    private final EventRepository eventRepository;
    private final RestTemplate restTemplate;

    @Value("${account.service.url}")
    private String accountServiceUrl;

    public EventRetryScheduler(EventRepository eventRepository, RestTemplate restTemplate) {
        this.eventRepository = eventRepository;
        this.restTemplate = restTemplate;
    }

    @Scheduled(fixedDelay = 60000) // Run every 60 seconds
    public void retryPendingEvents() {
        List<Event> pendingEvents = eventRepository.findByStatus("PENDING");

        for (Event event : pendingEvents) {
            try {
                // Map the saved Event back to the EventRequest format expected by the Account Service
                EventRequest request = new EventRequest(
                        event.getEventId(),
                        event.getAccountId(),
                        event.getType(),
                        event.getAmount(),
                        event.getCurrency(),
                        event.getEventTimestamp(),
                        null // Assuming we don't need metadata for the account service or we parse the JSON string back to Map
                );

                restTemplate.postForEntity(accountServiceUrl + "/accounts/" + event.getAccountId() + "/transactions", request, Void.class);
                
                // If successful, mark as COMPLETED
                event.setStatus("COMPLETED");
                eventRepository.save(event);
            } catch (Exception e) {
                // Keep it PENDING to be retried on the next run
            }
        }
    }
}
