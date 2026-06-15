# Event Ledger System

This project is a distributed system composed of two microservices, designed to process financial transaction events with a focus on resiliency, observability, and operational readiness.

## Architecture

The system consists of two services:

- **Event Gateway API (`gateway-service`)**: The public-facing entry point for all client requests. It is responsible for:
    - Validating incoming event payloads.
    - Enforcing idempotency to prevent duplicate event processing.
    - Storing a record of every event received.
    - Calling the internal Account Service to apply the transaction.
    - Implementing resiliency patterns (Circuit Breaker, Timeouts) for calls to the Account Service.
    - Propagating trace context to the Account Service.

- **Account Service (`account-service`)**: An internal service that manages the core business logic of accounts and transactions. It is responsible for:
    - Applying credit and debit transactions to an account.
    - Calculating account balances on the fly from the transaction history.
    - Ensuring idempotency at the transaction level.
    - Providing account details and transaction history.

The services communicate via synchronous REST APIs. The Gateway acts as a proxy and orchestrator for the Account Service.

```
                          ┌──────────────────────┐       ┌─────────────────────┐
Browser / Client ──────→  │  Event Gateway API   │──────→│  Gateway Database   │
                          │  (public-facing)     │       └─────────────────────┘
                          └──────┬───────────────┘
                                 │ REST (sync)
                                 | Circuit Breaker Pattern
                                 ▼
                          ┌──────────────────────┐
                          │  Account Service     │       ┌─────────────────────────────┐
                          │  (internal)          │──────→│  Account Service Database   │
                          └──────────────────────┘       └─────────────────────────────┘
```
### Prerequisites

- Java 17
- Maven 3.8+
- Docker & Docker Compose

## Setup and Running the Application

Create a folder "Project" and clone both gateway-service and account-service to this folder.
Compile both projects.
Account Service
```bash
   cd accounts-service
   mvn clean install
```
Gateway Service 
```bash
   cd gateway-service
   mvn clean install
```
Copy the docker-compose.yml from gateway-service to Project folder

### How to Start (Docker Compose)

From "Project" folder, run
```bash
docker-compose up --build
```

This command will:
1. Build the Docker images for both `gateway-service` and `account-service`.
2. Start both services.
3. The Gateway will be available at `http://localhost:8080`.
4. The Account Service will be available at `http://localhost:8081`.

### How to Start (Manual)

To run 

1. **Start the Account Service:**
   ```bash
   cd accounts-service
   mvn spring-boot:run
   ```

2. **Start the Gateway Service (in a new terminal):**
   ```bash
   cd gateway-service
   mvn spring-boot:run
   ```

## How to Run the Tests

mvn clean install will also run the tests. But if we need to run tests alone

From the project root directory:

```bash
# Run tests for the gateway
(cd gateway-service && mvn test)

# Run tests for the account service
(cd account-service && mvn test)
```

## Resiliency Pattern Choice: Circuit Breaker

For the resiliency requirement, I considered between 2 options .
(1) Transactional Outbox Pattern
(2) Circuit Breaker Pattern

### Transactional Outbox Pattern
Status variable in gateway service tracks the PENDING/COMPLETED status of the events. Scheduler will run at scheduled times to resend requests for all the PENDING events. Client is not notified of any failures and even if accounts-service is down for hours, then the requests will be stored at gateway and handled once the accounts-service is up.
### Circuit Breaker Pattern
Circuit breaker pattern follows the OPEN/CLOSED/HALF-OPEN for the circuit based on the success/failure of the events. It checks based on the sliding window, the requests will be processing continuously if all the requests are succeeding , if 50% or more of the requests are failing, then the circuit goes to OPEN state rejecting all subsequent requests and after the configured wait time , it enters HALF-OPEN state allowing a few test requests to pass through.

I chose to implement the circuit breaker pattern as this will not overload the accounts-service with traffic which can ultimately make gateway service also to break. And also, this gives a clear indication (graceful shutdown) to the client that there is a failure so client can decide on the next steps to be taken instead of being unaware if the request was processed or not. 



