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
        if (eventRepository.existsById(request.eventId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Event with ID " + request.eventId() + " already exists.");
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

        // Create and save the event
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

        // Call Account Service
        try {
            restTemplate.postForEntity(accountServiceUrl + "/accounts/" + request.accountId() + "/transactions", request, Void.class);
        } catch (Exception e) {
            // Handle Account Service call failure
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error calling Account Service: " + e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(event);
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
