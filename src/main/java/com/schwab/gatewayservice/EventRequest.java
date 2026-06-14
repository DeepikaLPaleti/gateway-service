package com.schwab.gatewayservice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record EventRequest(
        @NotBlank(message = "eventId is required")
        String eventId,
        
        @NotBlank(message = "accountId is required")
        String accountId,
        
        @NotBlank(message = "type is required")
        @Pattern(regexp = "^(CREDIT|DEBIT)$", message = "Type must be CREDIT or DEBIT")
        String type,
        
        @NotNull(message = "amount is required")
        @Positive(message = "Amount must be greater than 0")
        BigDecimal amount,
        
        @NotBlank(message = "currency is required")
        String currency,
        
        @NotNull(message = "eventTimestamp is required")
        Instant eventTimestamp,

        Map<String, Object> metadata
) {}
