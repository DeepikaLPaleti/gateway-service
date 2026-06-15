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
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = "spring.cloud.circuitbreaker.enabled=false")
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
                .andExpect(jsonPath("$.eventId").value(eventId));

        mockServer.verify();
    }

    @Test
    void testCreateEvent_Idempotency() throws Exception {
        String eventId = "evt-101";
        EventRequest request = new EventRequest(
                eventId, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), null
        );

        // Pre-insert the event
        eventRepository.save(new Event(eventId, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), null));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId));

        // Expect NO calls to the account service
        mockServer.verify();
    }

    @Test
    void testCreateEvent_ValidationFailure() throws Exception {
        EventRequest request = new EventRequest(
                null, null, "INVALID_TYPE", new BigDecimal("-100.00"), null, null, null
        );

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testCreateEvent_WhenAccountServiceIsDown() throws Exception {
        String eventId = "evt-fail-1";
        EventRequest request = new EventRequest(
                eventId, "acct-1", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), null
        );

        // Simulate Account Service being down
        mockServer.expect(ExpectedCount.once(), requestTo("http://localhost:8081/accounts/acct-1/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable());

        mockServer.verify();
    }
}
