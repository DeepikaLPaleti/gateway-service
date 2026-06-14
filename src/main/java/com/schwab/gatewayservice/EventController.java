package com.schwab.gatewayservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventRepository eventRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${account.service.url}")
    private String accountServiceUrl;

    public EventController(EventRepository eventRepository, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> createEvent(@Valid @RequestBody EventRequest request) {
        // Idempotency check
        Optional<Event> existingEvent = eventRepository.findById(request.eventId());
        if (existingEvent.isPresent()) {
            // Return 200 OK or 409 Conflict depending on requirements. We return 200 to acknowledge idempotency.
            return ResponseEntity.ok(existingEvent.get());
        }

        // Convert metadata to string
        String metadataJson = null;
        if (request.metadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.metadata());
            } catch (JsonProcessingException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid metadata format.");
            }
        }

        // Create and save the event as PENDING
        Event event = new Event(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                metadataJson,
                "PENDING"
        );
        eventRepository.save(event);

        // Call Account Service synchronously
        try {
            restTemplate.postForEntity(accountServiceUrl + "/accounts/" + request.accountId() + "/transactions", request, Void.class);
            // If successful, update status to COMPLETED
            event.setStatus("COMPLETED");
            eventRepository.save(event);
            return ResponseEntity.status(HttpStatus.CREATED).body(event);
        } catch (Exception e) {
            // If Account Service is down, leave as PENDING and return 202 Accepted
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(event);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEventById(@PathVariable String id) {
        Optional<Event> event = eventRepository.findById(id);
        return event.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Event>> getEventsByAccount(@RequestParam("account") String accountId) {
        List<Event> events = eventRepository.findByAccountIdOrderByEventTimestampDesc(accountId);
        return ResponseEntity.ok(events);
    }
}
