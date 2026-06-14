package com.schwab.gatewayservice;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, String> {
    List<Event> findByAccountIdOrderByEventTimestampDesc(String accountId);
}
