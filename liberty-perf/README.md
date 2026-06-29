# liberty-perf

> **Liberty Performance Benchmark** — a WebSphere Liberty / Open Liberty application that simulates transactional HTTP workloads (banking + airline booking) with **tunable chaos knobs** for memory pressure, connection-pool exhaustion, and query latency.

---

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Chaos Knobs](#chaos-knobs)
4. [API Reference](#api-reference)
5. [Health Checks](#health-checks)
6. [Metrics (Prometheus)](#metrics-prometheus)
7. [Building the Application](#building-the-application)
8. [Docker Build](#docker-build)
9. [Kubernetes Deployment](#kubernetes-deployment)
10. [Load Generator](#load-generator)
11. [Troubleshooting](#troubleshooting)
12. [Configuration Reference](#configuration-reference)

---

## Overview

`liberty-perf` is a benchmark application that mimics a high-concurrency transactional backend — think an online banking system combined with an airline reservation engine. Its primary purpose is to **demonstrate and diagnose memory-related scaling issues** that arise from poor HTTP/database connection management, even under low-to-moderate load.

The app deliberately ships with configurable "chaos knobs" so that operators and SRE teams can:

- Reproduce production-grade connection-pool exhaustion on demand
- Observe heap growth caused by an unbounded in-process response cache
- Tune server parameters to measure the remediation effect

---

## Architecture

```
                  ┌─────────────────────────────────────────────┐
 HTTP Clients ──► │  Open Liberty  (JAX-RS / MicroProfile)      │
                  │  ┌────────────┐  ┌────────────────────────┐ │
                  │  │ /api/      │  │ /health  /metrics       │ │
                  │  │ accounts   │  │ (MicroProfile Health    │ │
                  │  │ bookings   │  │  + Prometheus)          │ │
                  │  └─────┬──────┘  └────────────────────────┘ │
                  │        │                                     │
                  │  ┌─────▼──────────────────────────────────┐ │
                  │  │  TransactionService / BookingService    │ │
                  │  │  (chaos memory-leak cache lives here)   │ │
                  │  └─────┬──────────────────────────────────┘ │
                  │        │  JDBC (WLP ConnectionPool)          │
                  │  ┌─────▼──────────────────────────────────┐ │
                  │  │  AccountRepository / BookingRepository  │ │
                  │  │  (chaos connection-leak lives here)     │ │
                  │  └─────┬──────────────────────────────────┘ │
                  │        │                                     │
                  └────────┼────────────────────────────────────┘
                           │  JDBC
                  ┌────────▼──────────────────────────────────┐
                  │  H2 (embedded, dev) / PostgreSQL (prod)   │
                  └───────────────────────────────────────────┘
```

### Package structure

```
src/main/java/ai/causa/libertyperf/
├── health/           # MicroProfile Health checks (liveness, readiness, startup)
├── load/             # LoadGenerator — standalone load driver (run as K8s Job)
├── metrics/          # Custom Prometheus gauge registration
├── model/            # Domain objects: Account, Transaction, Booking, ApiResponse
├── repository/       # JDBC repositories with chaos instrumentation
├── rest/             # JAX-RS resources + Application class
└── service/          # Business logic + chaos simulation
```

---

## Chaos Knobs

All chaos settings are **externalized as environment variables** (no code changes required).

| Environment Variable           | Default | Effect when `true` / non-zero                                      |
|--------------------------------|---------|--------------------------------------------------------------------|
| `CHAOS_DB_LEAK_ENABLED`        | `false` | DB connections are acquired but **never returned** to the pool. Under sustained load the pool is exhausted and requests start failing or timing out. |
| `CHAOS_DB_SLOW_QUERY_MS`       | `0`     | Each JDBC call sleeps for this many milliseconds before executing. Forces threads to hold connections longer, amplifying starvation. |
| `CHAOS_MEMORY_CACHE_ENABLED`   | `false` | A static `ConcurrentHashMap` accumulates transaction responses and is **never evicted**. Heap grows linearly with request throughput. |
| `CHAOS_MEMORY_OBJECTS_PER_TX`  | `1`     | Number of 64 KB byte-arrays leaked per transaction when the cache is enabled. Increase to accelerate heap exhaustion. |
| `DB_MAX_POOL_SIZE`             | `20`    | Maximum JDBC connections. Set to `5` or lower to trigger starvation even without leak mode. |
| `THREAD_POOL_MAX`              | `200`   | Liberty thread pool upper bound. Reducing this reveals thread starvation. |

### Typical chaos scenarios

**Scenario 1 — Slow connection exhaustion (pool leak)**
```sh
CHAOS_DB_LEAK_ENABLED=true
DB_MAX_POOL_SIZE=10
CONCURRENT_USERS=20      # load generator
```
Expected: after ~10 requests the pool is empty; subsequent requests receive HTTP 500 / timeout. Readiness probe fails and Kubernetes stops routing traffic.

**Scenario 2 — Memory pressure from response cache**
```sh
CHAOS_MEMORY_CACHE_ENABLED=true
CHAOS_MEMORY_OBJECTS_PER_TX=5
```
Expected: heap grows at ~320 KB/request. Liberty OOM-kills the JVM (configured with `-XX:+HeapDumpOnOutOfMemoryError`). Liveness probe reports DOWN before restart.

**Scenario 3 — Slow backend amplifies thread starvation**
```sh
CHAOS_DB_SLOW_QUERY_MS=500
THREAD_POOL_MAX=50
```
Expected: with 50ms/query threads pile up; with 50 threads the system stalls under moderate load.

---

## API Reference

All endpoints return `application/json` with the `ApiResponse<T>` envelope:
```json
{
  "success": true,
  "correlationId": "uuid",
  "processingTimeMs": 12,
  "data": { ... }
}
```

OpenAPI spec available at: `GET /openapi` or `GET /openapi/ui`

### Accounts & Transactions

| Method | Path                                    | Description                                |
|--------|-----------------------------------------|--------------------------------------------|
| `GET`  | `/api/accounts`                         | List all accounts (up to 100)              |
| `GET`  | `/api/accounts/{accountId}`             | Get account details                        |
| `GET`  | `/api/accounts/{accountId}/transactions?limit=20` | Transaction history           |
| `POST` | `/api/accounts/{accountId}/transactions` | Submit a transaction                      |

**Submit transaction body:**
```json
{
  "type": "DEBIT",
  "amount": 250.00,
  "currency": "USD",
  "description": "ATM withdrawal"
}
```

### Flight Bookings

| Method | Path                            | Description                      |
|--------|---------------------------------|----------------------------------|
| `POST` | `/api/bookings`                 | Create a booking                 |
| `GET`  | `/api/bookings/{bookingRef}`    | Retrieve booking by reference    |
| `GET`  | `/api/bookings?passengerId=PAX-0001` | List bookings for passenger |

**Create booking body:**
```json
{
  "passengerId": "PAX-0001",
  "passengerName": "Alice Johnson",
  "origin": "JFK",
  "destination": "LAX"
}
```

---

## Health Checks

| Endpoint            | Probe type | Fails when                                              |
|---------------------|------------|---------------------------------------------------------|
| `GET /health/live`  | Liveness   | Heap usage > 90% — Kubernetes restarts the pod         |
| `GET /health/ready` | Readiness  | DB ping fails (pool exhausted) — traffic stops         |
| `GET /health/start` | Startup    | Always UP once Liberty finishes startup                 |
| `GET /health`       | Combined   | Aggregated status for all probes                       |

---

## Metrics (Prometheus)

Metrics are exposed at `GET /metrics` in Prometheus text format.

### Key metrics

| Metric                             | Type    | Description                           |
|------------------------------------|---------|---------------------------------------|
| `liberty_perf_heap_used_bytes`     | Gauge   | Current JVM heap usage                |
| `liberty_perf_heap_max_bytes`      | Gauge   | Configured JVM max heap               |
| `liberty_perf_heap_used_pct`       | Gauge   | Heap utilisation (0–1)                |
| `liberty_perf_leak_cache_entries`  | Gauge   | Leaked objects in memory cache        |
| `liberty_perf_leak_cache_bytes`    | Gauge   | Estimated bytes held by leak cache    |
| `transactions_submitted_total`     | Counter | Total transactions submitted          |
| `bookings_created_total`           | Counter | Total bookings created                |
| `transaction_process_time_*`       | Timer   | Transaction processing latency        |
| `booking_create_time_*`            | Timer   | Booking creation latency              |
| `account_lookup_time_*`            | Timer   | Account lookup latency                |

---

## Building the Application

### Prerequisites

- Java 21+ (IBM Semeru or Eclipse Temurin)
- Maven 3.9+

```bash
cd liberty-perf

# Build and package (creates target/liberty-perf.zip with embedded Liberty server)
mvn clean package

# Run locally
mvn liberty:run

# Test health
curl http://localhost:9080/health
curl http://localhost:9080/api/accounts

# Test a transaction
curl -X POST http://localhost:9080/api/accounts/ACC-001/transactions \
  -H 'Content-Type: application/json' \
  -d '{"type":"CREDIT","amount":100,"currency":"USD","description":"Test"}'
```

---

## Docker Build

```bash
cd liberty-perf

# Build image using IBM Semeru runtime
docker build -t liberty-perf:1.0.0 .

# Run locally
docker run -p 9080:9080 \
  -e CHAOS_DB_LEAK_ENABLED=false \
  -e CHAOS_MEMORY_CACHE_ENABLED=false \
  liberty-perf:1.0.0
```

---

## Kubernetes Deployment

```bash
# Apply all manifests (namespace, configmap, deployment, service, HPA)
kubectl apply -f manifests/deploy.yaml

# Verify 3 replicas are running
kubectl get pods -n chaos-test -l app=liberty-perf

# Check application health
kubectl port-forward svc/liberty-perf-svc 9080:9080 -n chaos-test &
curl http://localhost:9080/health

# Apply monitoring (requires prometheus-operator)
kubectl apply -f manifests/monitoring.yaml

# Run the load generator
kubectl apply -f manifests/load-gen-job.yaml

# Follow load generator logs
kubectl logs -n chaos-test -l app=liberty-perf-load-gen -f
```

### Activating chaos scenarios via ConfigMap patch

```bash
# Enable connection leak chaos
kubectl patch configmap liberty-perf-config -n chaos-test \
  --type merge -p '{"data":{"CHAOS_DB_LEAK_ENABLED":"true","DB_MAX_POOL_SIZE":"5"}}'

# Trigger rolling restart to pick up new config
kubectl rollout restart deployment/liberty-perf -n chaos-test

# Watch pods and readiness
kubectl get pods -n chaos-test -w

# Restore normal operation
kubectl patch configmap liberty-perf-config -n chaos-test \
  --type merge -p '{"data":{"CHAOS_DB_LEAK_ENABLED":"false","DB_MAX_POOL_SIZE":"20"}}'
kubectl rollout restart deployment/liberty-perf -n chaos-test
```

---

## Load Generator

The load generator is a self-contained Java application (`LoadGenerator.java`) that runs as a Kubernetes Job.

### Load generator environment variables

| Variable           | Default              | Description                                            |
|--------------------|----------------------|--------------------------------------------------------|
| `TARGET_HOST`      | `liberty-perf-svc`   | Kubernetes service hostname                           |
| `TARGET_PORT`      | `9080`               | HTTP port                                              |
| `CONCURRENT_USERS` | `20`                 | Parallel virtual threads                              |
| `DURATION_SECONDS` | `300`                | Total run duration                                     |
| `REQUEST_DELAY_MS` | `100`                | Pause between requests per thread (0 = no delay)       |
| `BOOKING_RATIO`    | `50`                 | % of requests that are booking (rest are transactions) |

```bash
# Deploy load generator with custom profile
kubectl apply -f manifests/load-gen-job.yaml

# Override profile inline
kubectl set env job/liberty-perf-load-gen \
  CONCURRENT_USERS=100 DURATION_SECONDS=900 REQUEST_DELAY_MS=0 \
  -n chaos-test
```

---

## Troubleshooting

### Application won't start / readiness probe failing

```bash
# Check Liberty server logs
kubectl logs -n chaos-test deployment/liberty-perf

# Check DB datasource initialisation
kubectl logs -n chaos-test deployment/liberty-perf | grep -i "schema\|datasource\|pool"
```

### Connection pool exhaustion

**Symptoms:** HTTP 500 errors, readiness probe DOWN, `getConnection()` timeout errors in logs.

**Diagnosis:**
```bash
# Look for chaos log lines
kubectl logs deployment/liberty-perf -n chaos-test | grep '\[CHAOS\]'

# Check pool utilisation via Liberty admin metrics
kubectl port-forward svc/liberty-perf-svc 9080:9080 -n chaos-test &
curl http://localhost:9080/metrics | grep connectionpool
```

**Resolution:** Set `DB_MAX_POOL_SIZE` to a higher value, or set `CHAOS_DB_LEAK_ENABLED=false` in the ConfigMap and restart the deployment.

### Memory pressure / OOM

**Symptoms:** Liveness probe DOWN (`heap.used.pct > 90%`), pod restarting, heap dump in `/dumps/`.

**Diagnosis:**
```bash
# Check heap metrics
curl http://localhost:9080/metrics | grep liberty_perf_heap

# Check liveness check data
curl http://localhost:9080/health/live | jq .

# Copy heap dump from pod
kubectl cp chaos-test/<pod>:/dumps/heapdump.hprof ./heapdump.hprof
```

**Resolution:** Set `CHAOS_MEMORY_CACHE_ENABLED=false` and restart; or increase pod memory limit if the workload is genuine.

### Performance degradation (slow queries)

**Symptoms:** High latency on all endpoints, thread pool exhaustion.

```bash
# Check slow query chaos setting
kubectl get configmap liberty-perf-config -n chaos-test -o yaml | grep SLOW

# Disable
kubectl patch configmap liberty-perf-config -n chaos-test \
  --type merge -p '{"data":{"CHAOS_DB_SLOW_QUERY_MS":"0"}}'
kubectl rollout restart deployment/liberty-perf -n chaos-test
```

---

## Configuration Reference

All Liberty server-level settings are configured via **environment variables** that override defaults in [`server.xml`](src/main/liberty/config/server.xml).

| Environment Variable            | server.xml default | Description                                     |
|---------------------------------|--------------------|-------------------------------------------------|
| `THREAD_POOL_CORE`              | `10`               | Liberty executor core threads                   |
| `THREAD_POOL_MAX`               | `50`               | Liberty executor max threads                    |
| `DB_MAX_POOL_SIZE`              | `20`               | JDBC connection pool max                        |
| `DB_MIN_POOL_SIZE`              | `2`                | JDBC connection pool min (eager creation)       |
| `DB_CONNECTION_TIMEOUT_MS`      | `5000`             | Max wait for a free connection                  |
| `DB_MAX_IDLE_TIME_S`            | `60`               | Close idle connections after N seconds          |
| `DB_REAP_TIME_S`                | `180`              | Interval for idle connection scan               |
| `DB_AGED_TIMEOUT_S`             | `3600`             | Force-close connections older than N seconds    |
| `HTTP_MAX_KEEPALIVE_REQUESTS`   | `200`              | Requests per keep-alive connection              |
| `HTTP_PERSIST_TIMEOUT_S`        | `30`               | Keep-alive socket timeout                       |
| `CHAOS_DB_LEAK_ENABLED`         | `false`            | Enable connection-leak chaos mode               |
| `CHAOS_DB_SLOW_QUERY_MS`        | `0`                | Per-query artificial delay in ms                |
| `CHAOS_MEMORY_CACHE_ENABLED`    | `false`            | Enable unbounded response-cache memory leak     |
| `CHAOS_MEMORY_OBJECTS_PER_TX`   | `1`                | 64 KB objects leaked per transaction            |

---

*Made with IBM Bob*
