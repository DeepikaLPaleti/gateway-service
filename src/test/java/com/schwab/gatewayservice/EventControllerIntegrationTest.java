package com.schwab.gatewayservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void testCreateEvent_Success() throws Exception {
        String eventId = "evt-100";
        EventRequest request = new EventRequest(
                eventId, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), Map.of("source", "test")
        );

        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/accounts/acct-1/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockServer.verify();
    }

    @Test
    void testCreateEvent_Idempotency() throws Exception {
        String eventId = "evt-101";
        EventRequest request = new EventRequest(
                eventId, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), null
        );

        // Pre-insert the event
        eventRepository.save(new Event(eventId, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), null, "COMPLETED"));

        // Expect NO calls to the account service
        mockServer.expect(ExpectedCount.never(), requestTo("http://localhost:8081/accounts/acct-1/transactions"))
                .andRespond(withSuccess());

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk()) // Returns 200 OK, not 201 Created
                .andExpect(jsonPath("$.eventId").value(eventId));

        mockServer.verify();
    }

    @Test
    void testCreateEvent_ValidationFailure() throws Exception {
        // Missing required fields
        EventRequest request = new EventRequest(
                null, null, "INVALID_TYPE", new BigDecimal("-100.00"), null, null, null
        );

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testCreateEvent_CircuitBreakerOpens() throws Exception {
        String eventIdPrefix = "evt-fail-";

        // Simulate Account Service repeatedly failing (500 Internal Server Error)
        mockServer.expect(ExpectedCount.manyTimes(), requestTo("http://localhost:8081/accounts/acct-1/transactions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        // Send 5 requests to trip the circuit breaker (based on our slidingWindowSize: 5 config)
        for (int i = 1; i <= 5; i++) {
            EventRequest request = new EventRequest(
                    eventIdPrefix + i, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), null
            );

            mockMvc.perform(post("/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isServiceUnavailable()); // Fallback response
        }

        mockServer.verify();

        // The 6th request should fail IMMEDIATELY without calling the MockServer
        // (If it did call the MockServer, verify() would fail because we didn't add another expectation)
        EventRequest request6 = new EventRequest(
                eventIdPrefix + 6, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), null
        );

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request6)))
                .andExpect(status().isServiceUnavailable());
    }
}