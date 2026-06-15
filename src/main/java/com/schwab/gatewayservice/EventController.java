package com.schwab.gatewayservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/events")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final EventRepository eventRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Counter eventsCreatedCounter;

    @Value("${account.service.url}")
    private String accountServiceUrl;

    public EventController(EventRepository eventRepository, RestTemplate restTemplate, ObjectMapper objectMapper, CircuitBreakerFactory circuitBreakerFactory, MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreakerFactory.create("accountService");
        this.eventsCreatedCounter = meterRegistry.counter("events.created.total");
    }

    @PostMapping
    public ResponseEntity<?> createEvent(@Valid @RequestBody EventRequest request) {
        log.info("Received event creation request: {}", request.eventId());

        // Idempotency check
        Optional<Event> existingEvent = eventRepository.findById(request.eventId());
        if (existingEvent.isPresent()) {
            log.info("Event {} already exists, returning existing event.", request.eventId());
            return ResponseEntity.ok(existingEvent.get());
        }

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(request.metadata());
        } catch (JsonProcessingException e) {
            log.error("Invalid metadata format for event {}", request.eventId(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid metadata format.");
        }

        // The event is saved locally first. This ensures that even if the downstream call fails, we have a record of the attempt.
        Event event = new Event(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                metadataJson
        );
        eventRepository.save(event);

        return circuitBreaker.run(() -> {
            // Call Account Service
            String url = accountServiceUrl + "/accounts/" + request.accountId() + "/transactions";
            restTemplate.postForEntity(url, request, Void.class);

            eventsCreatedCounter.increment();
            log.info("Successfully processed event {}.", request.eventId());
            return ResponseEntity.status(HttpStatus.CREATED).body(event);
        }, throwable -> {
            // Fallback: Executed when the circuit is open or the call fails
            log.error("Account service is unavailable for event {}.", request.eventId(), throwable);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Account service is currently unavailable.");
        });
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEventById(@PathVariable String id) {
        log.info("Fetching event by id: {}", id);
        Optional<Event> event = eventRepository.findById(id);
        return event.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Event>> getEventsByAccount(@RequestParam("account") String accountId) {
        log.info("Fetching events for account: {}", accountId);
        List<Event> events = eventRepository.findByAccountIdOrderByEventTimestampDesc(accountId);
        return ResponseEntity.ok(events);
    }
}