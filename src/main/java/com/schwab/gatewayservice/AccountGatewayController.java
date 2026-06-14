package com.schwab.gatewayservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/accounts")
public class AccountGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AccountGatewayController.class);

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;

    @Value("${account.service.url}")
    private String accountServiceUrl;

    public AccountGatewayController(RestTemplate restTemplate, CircuitBreakerFactory circuitBreakerFactory) {
        this.restTemplate = restTemplate;
        this.circuitBreaker = circuitBreakerFactory.create("accountService");
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<?> getBalance(@PathVariable String accountId) {
        log.info("Fetching balance for account: {}", accountId);

        return circuitBreaker.run(() -> {
            String url = accountServiceUrl + "/accounts/" + accountId + "/balance";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            return ResponseEntity.ok(response.getBody());
        }, throwable -> {
            log.error("Account service is unavailable for balance query on account {}.", accountId, throwable);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Account service is currently unreachable. Cannot fetch balance."));
        });
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<?> getAccountDetails(@PathVariable String accountId) {
        log.info("Fetching details for account: {}", accountId);

        return circuitBreaker.run(() -> {
            String url = accountServiceUrl + "/accounts/" + accountId;
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            return ResponseEntity.ok(response.getBody());
        }, throwable -> {
            log.error("Account service is unavailable for details query on account {}.", accountId, throwable);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Account service is currently unreachable. Cannot fetch details."));
        });
    }
}