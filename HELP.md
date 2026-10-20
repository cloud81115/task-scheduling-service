# Setup & Usage

## Prerequisites

- Java 21 and Docker with Compose v2
- Apple Silicon: enable "Use Rosetta for x86_64/amd64 emulation" in Docker Desktop. The RocketMQ containers run as amd64.

## 1. Start the infrastructure

```bash
docker compose up -d
docker compose ps    # wait until mysql and rocketmq-namesrv are healthy
```

On its first start, MySQL creates the `taskdb` database and runs `init.sql`, which creates the `scheduled_task` table. The app only validates the schema; it never creates tables.

The broker can take 30–60 seconds to register (longer under emulation). Wait until this lists `127.0.0.1:10911`:

```bash
docker exec rocketmq-broker sh mqadmin clusterList -n rocketmq-namesrv:9876
```

Create the topic. This is optional: without it the broker creates the topic on the first publish, but with 4 queues instead of 8.

```bash
docker exec rocketmq-broker sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c DefaultCluster -t task-schedule-topic
```

## 2. Run the app

```bash
./mvnw spring-boot:run    # the README's `./mvn` should be `./mvnw`
```

Swagger UI: http://localhost:8080/swagger-ui.html

## 3. Try it

Pick a time 30 seconds from now:

```bash
AT=$(date -u -v+30S +%Y-%m-%dT%H:%M:%SZ)               # macOS
AT=$(date -u -d '+30 seconds' +%Y-%m-%dT%H:%M:%SZ)     # Linux
```

Create a task:

```bash
curl -i -X POST localhost:8080/tasks -H 'Content-Type: application/json' -d "{
  \"taskId\": \"abc-123\",
  \"executeAt\": \"$AT\",
  \"payload\": {\"type\": \"email\", \"target\": \"hello@example.com\", \"message\": \"This is a scheduled task\"}
}"
```

```
HTTP/1.1 201
Location: http://localhost:8080/tasks/abc-123

{"taskId":"abc-123","status":"pending","executeAt":"2026-10-20T04:20:01Z","payload":{...},
 "createdAt":"2026-10-20T04:19:31.579Z","updatedAt":"2026-10-20T04:19:31.579Z","triggeredAt":null}
```

Query and cancel:

```bash
curl localhost:8080/tasks/abc-123                            # status turns "triggered" once executeAt passes
curl 'localhost:8080/tasks?status=pending&page=0&size=20'    # status: pending (default), triggered, cancelled, failed
curl -X DELETE localhost:8080/tasks/abc-123                  # 409 if already triggered
```

Check the message reached RocketMQ (the message key is the taskId):

```bash
docker exec rocketmq-broker sh mqadmin queryMsgByKey -n rocketmq-namesrv:9876 -t task-schedule-topic -k abc-123
docker exec rocketmq-broker sh mqadmin queryMsgByUniqueKey -n rocketmq-namesrv:9876 -t task-schedule-topic -i <Message ID from above>
```

The message body:

```json
{"taskId":"abc-123","executeAt":"2026-10-20T04:20:01Z","triggeredAt":"2026-10-20T04:20:01.612Z","payload":{...}}
```

### Request rules

| Field | Rule |
|---|---|
| `taskId` | Up to 64 characters: letters, digits, `.` `_` `:` `-`, starting with a letter or digit |
| `executeAt` | ISO-8601 with a time zone (`Z` or `+08:00`), in the future. The README example (`2025-07-21T15:00:00Z`) is in the past, so it is rejected |
| `payload` | A non-empty JSON object, at most 64 KB as JSON |

### Statuses

| Status | Meaning |
|---|---|
| `pending` | Not triggered yet. Includes tasks that are due but not yet published: normally for under a second, longer while the broker is unavailable |
| `triggered` | Published to RocketMQ |
| `cancelled` | Cancelled before it was triggered |
| `failed` | Can never be published (e.g. the message exceeds the broker's size limit) |

### Errors

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details. Validation errors also list the failing fields:

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"Request validation failed",
 "instance":"/tasks","errors":{"executeAt":"must be a future date"}}
```

## 4. Run the tests

```bash
./mvnw test
```

- The compose services must be running: the tests use the real MySQL and Redis.
- Stop the app first. Its poller would pick up the due tasks the tests create.
- Existing data is kept: tests delete only the rows they create, or roll back.

## Changes to the provided files

| File | Change | Why |
|---|---|---|
| `broker.conf` | `brokerIP1 = 127.0.0.1` | Otherwise the broker registers its container IP, which the app on the host cannot reach |
| `docker-compose.yaml` | `platform: linux/amd64` on the RocketMQ services | Run on Apple Silicon |
| | namesrv healthcheck: TCP check instead of `curl` | Port 9876 speaks RocketMQ's protocol, not HTTP, so the `curl` check always failed |
| `pom.xml` | Added web, validation, JPA, Redis, cache, MySQL driver, RocketMQ starter, springdoc, Lombok | Needed by the service |
| | Removed `rocketmq-client` and the grpc version pins | The starter brings a compatible client; the pins only affect the gRPC proxy mode, which is not used |
| `init.sql` | `scheduled_task` table | Was empty |
| `application.yaml` | Connection and app settings | Spring Boot 3 uses `spring.data.redis`, not `spring.redis` as in the README |
| `.gitignore` | Removed the `README.md` entry | The README is part of the submission |

## Configuration

| Property | Default | |
|---|---|---|
| `app.scheduler.enabled` | `true` | Turn the poller off, e.g. for API-only instances |
| `app.scheduler.poll-interval-ms` | `1000` | Delay between polls |
| `app.scheduler.batch-size` | `100` | Max due tasks handled per poll |
| `app.scheduler.topic` | `task-schedule-topic` | |
| `app.cache.task-ttl` | `1m` | How long a task stays cached |

Any property can be overridden with an environment variable, e.g. `APP_SCHEDULER_ENABLED=false`.

## Troubleshooting

- `init.sql` only runs when the MySQL volume is first created. After changing it, run `docker compose down -v` (this deletes all data) and start again.
