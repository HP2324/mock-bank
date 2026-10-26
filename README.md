# Computer-Use Automation System

[![CI](https://github.com/HP2324/mock-bank/actions/workflows/ci.yml/badge.svg)](https://github.com/HP2324/mock-bank/actions/workflows/ci.yml)

An LLM **discovers** a back-office UI workflow once against a live application. The system
records it as a **typed, versioned capability artifact**, then **replays that artifact
deterministically without the LLM**. Replay comes with an explicit error taxonomy, safety
guardrails (allowlist + redaction), and a human-in-the-loop handoff on the *same* live browser
session.

The system automates a **mock retail-bank back office** with a production-style stack:

- **React** staff console (Vite), served by a **Spring Boot** JSON API
- **PostgreSQL** with **Flyway** migrations and hand-written reporting SQL
- every audit-log event published to **Kafka**, read by a separate **consumer service**
- **Docker Compose** for the whole stack, **GitHub Actions** CI, and a guide for running it on an
  **Ubuntu EC2** instance

The exercised goal is *"open a new sub-account for a member and reach the confirmation screen."*

| Read this | For |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | Design write-up: architecture, artifact schema, determinism, data and events, safety, testing, cuts |
| [`automation/README.md`](automation/README.md) | Every automation command, provider and flag |
| [`automation/evidence/README.md`](automation/evidence/README.md) | A real Claude discovery run, replays and a human handoff, with screenshots |
| [`docs/deploy-ec2.md`](docs/deploy-ec2.md) | Running the stack on Ubuntu / AWS EC2 |
| http://localhost:8080/swagger-ui.html | Interactive API docs (once the stack is running) |

---

## Architecture

```
                         ┌──────────────── automation/ (Python) ────────────────┐
                         │  discover-browser  ── LLM picks one action per turn   │
                         │  approve           ── human promotes draft → approved │
                         │  replay            ── no LLM; checkpoint + typed output│
                         └───────────────┬──────────────────────────────────────┘
                                         │ Playwright (Chromium)
                                         ▼
 ┌───────────── mock-bank :8080 ─────────────┐        ┌──────── PostgreSQL ────────┐
 │ React SPA  ──/api──▶  Spring Boot API      │──JPA──▶│ db mockbank (Flyway V1–V3) │
 │                       │ audit row commits  │──SQL──▶│  members, accounts,        │
 └───────────────────────┼────────────────────┘        │  audit_events              │
                         ▼                             │                            │
            Kafka topic mockbank.audit-events          │ db audit_consumer          │
                         │                             │  consumed_audit_events     │
                         ▼                             └────────────▲───────────────┘
            audit-consumer :8081 ───────────────────────────────────┘
                         └── unreadable messages ──▶ mockbank.audit-events.DLT
```

---

## Repository layout

```
services/
  bank-api/                 Spring Boot API for the staff console (Java 17)
    src/main/java/io/github/hp2324/mockbank/
      api/                  JSON endpoints + typed error responses
      service/              business rules (SubAccountPolicy) and JPA service
      reporting/            ReportingRepository: hand-written SQL with joins
      audit/                publishes committed audit events to Kafka
      web/                  session handling, SPA routes, /logout, /debug/expire-session
      config/               OpenAPI (Swagger) and clock configuration
    src/main/resources/db/migration/   Flyway V1 schema, V2 seed data, V3 indexes
    src/test/java/          unit tests (*Test) and Testcontainers integration tests (*IT)
    Dockerfile              builds the React app, then the jar (context: repository root)
  audit-consumer/           Spring Boot Kafka consumer with its own database, tests and Dockerfile
frontend/                   React + Vite staff console, Vitest tests
automation/                 Python discovery, artifact contract, replay engine, guardrails
  artifacts/{examples,drafts,approved}/   saved capability artifacts
  evidence/                 committed demonstration runs (see automation/evidence/README.md)
  scripts/replay_smoke.py   replays every artifact and checks its outcome
docs/                       DESIGN.md (design write-up), deploy-ec2.md
deploy/ec2/user-data.sh     EC2 bootstrap script
docker/postgres/init/       creates the audit_consumer database on first start
.github/                    CI workflow and Dependabot config
docker-compose.yml          PostgreSQL, Kafka, bank-api (service "mock-bank"), audit-consumer
pom.xml                     parent pom: builds and tests both Java services
Makefile                    everyday commands (`make help`)
mvnw                        Maven wrapper; no Maven install needed
```

---

## Quick start

Needs Docker Desktop (or Docker Engine on Linux).

```bash
git clone https://github.com/HP2324/mock-bank.git && cd mock-bank
docker compose up -d --build --wait      # or: make up
```

Then open **http://localhost:8080** and sign in with **`operator` / `password`**.

The first build takes a few minutes. `--wait` returns once all four services report healthy.

---

## Prerequisites

| Tool | Needed for |
|---|---|
| Docker Desktop / Docker Engine | The stack, and the Testcontainers integration tests |
| Java 17+ | Building or running the Java services outside Docker. Maven is optional: `./mvnw` downloads the pinned version (3.9.9). |
| Node.js 22 | Optional. Only for the Vite dev server or running `npm test` directly. Maven downloads its own Node to build the frontend. |
| Python 3.11+ | The automation layer |
| `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` | Only for a real LLM discovery run. Everything else runs offline. |

---

## Running the application

### Everything in Docker

```bash
docker compose up -d --build --wait     # start or rebuild
docker compose ps                       # all four services should be "healthy"
docker compose logs -f mock-bank        # follow one service's logs
docker compose down                     # stop, keep data
docker compose down -v                  # stop and reset to the Flyway seed data
```

### Make shortcuts

`make help` lists them all. The common ones:

| Command | Does |
|---|---|
| `make up` / `make down` / `make reset` | Start the stack / stop it / wipe data and restart |
| `make infra` | Only PostgreSQL and Kafka |
| `make run` / `make run-consumer` | Run a service from source |
| `make dev` | Vite dev server with hot reload |
| `make test` / `make verify` | Fast tests / all Java tests including Testcontainers |
| `make replay` | Replay every saved artifact against the running app |

### The app from source, infrastructure in Docker

```bash
docker compose up -d --wait postgres kafka
./mvnw -pl services/bank-api spring-boot:run         # builds the React app, serves :8080
./mvnw -pl services/audit-consumer spring-boot:run   # optional, serves :8081
```

**From IntelliJ:** running `MockBankApplication` also works. The IDE skips Maven's frontend
step, so the app serves the last build in `frontend/dist`. Refresh that build with
`cd frontend && npm run build`.

**Frontend with hot reload:** keep the API running on :8080, then:

```bash
cd frontend && npm install && npm run dev     # http://localhost:5173, /api proxied to :8080
```

Only one copy of mock-bank can use port 8080. If the Docker copy is running, stop it first with
`docker compose stop mock-bank`.

### Ports

| Port | Service | Notes |
|---|---|---|
| 8080 | mock-bank | Staff console and `/api` |
| 8081 | audit-consumer | `/api/events`, `/api/events/summary` |
| 5432 | PostgreSQL | user `mockbank`, databases `mockbank` and `audit_consumer` |
| 9092 | Kafka | for apps on the host. Containers use `kafka:19092`. |

All ports bind to `127.0.0.1`, so nothing is reachable from other machines.

---

## Using the staff console

| Page | URL | What it shows |
|---|---|---|
| Sign in | `/login` | Demo login `operator` / `password` |
| Member lookup | `/members/search` | Search by five-digit member ID |
| Member detail | `/members/{id}` | Profile, accounts, "Open sub-account" link |
| Open sub-account | `/members/{id}/subaccounts/new` | Form, then confirmation with checkpoint `CONFIRM-SUBACCOUNT-CREATED` |
| Audit log | `/ops/audit` | Every audited action (also published to Kafka) |
| Reports | `/ops/reports` | Member portfolios, recent openings, per-member activity (SQL joins) |

The mock produces each automation state on demand:

| Input | State | Shown as |
|---|---|---|
| Member `12345` (Jordan Lee), deposit ≤ $5,000 | Happy path → confirmation | `#confirmation-code` |
| Member `00000` | Member not found (business outcome) | `.banner.warning` |
| Member `abc` | Invalid member ID (validation) | `.banner.error` |
| Member `77777` (Riley Morgan, restricted) | Permission denied | `.banner.error` |
| Deposit > $5,000 | Human approval required (handoff) | `.banner.warning` |
| `/debug/expire-session` while signed in | Session-timeout redirect | `/login?reason=expired` |

---

## API

**Interactive docs:** with the app running, open **http://localhost:8080/swagger-ui.html**. Use
`POST /api/session` there to sign in, and "Try it out" works on every other endpoint. The raw
OpenAPI spec is at `/v3/api-docs`. audit-consumer has its own at
http://localhost:8081/swagger-ui.html.

The React app talks to these endpoints. Signed-in endpoints use the `JSESSIONID` session cookie.
Errors always come back as `{"code": "...", "message": "..."}`.

| Method & path | Purpose | Notable responses |
|---|---|---|
| `POST /api/session` | Sign in `{operatorId, password}` | 401 `INVALID_CREDENTIALS` |
| `GET /api/session` / `DELETE /api/session` | Current operator / sign out | 401 `AUTHENTICATION_REQUIRED` |
| `GET /api/members/lookup?memberId=` | Search-form lookup | 400 `INVALID_MEMBER_ID`, 404 `MEMBER_NOT_FOUND` |
| `GET /api/members/{id}` | Member with accounts | as above |
| `POST /api/members/{id}/subaccounts` | Open `{productName, initialDeposit}` | 201 confirmation, 400 `VALIDATION_FAILED`, 403 `PERMISSION_DENIED`, 409 `HUMAN_APPROVAL_REQUIRED` |
| `GET /api/audit-events?limit=` | Newest audit events | |
| `GET /api/reports/member-portfolios` | Accounts and balances per member | |
| `GET /api/reports/subaccount-openings?limit=` | Console openings with member and operator | |
| `GET /api/reports/member-activity` | Audited actions per member | |
| `GET /actuator/health` | Health check (both services) | |

audit-consumer: `GET /api/events?limit=` and `GET /api/events/summary`.

---

## Data: PostgreSQL and Flyway

Flyway owns the schema, and Hibernate only validates that the entities still match it
(`ddl-auto=validate`).

| Migration | Contents |
|---|---|
| `V1__create_core_schema.sql` | `members`, `accounts`, `audit_events`, with check, unique and foreign-key constraints |
| `V2__seed_demo_members.sql` | The demo members and accounts the automation artifacts use |
| `V3__index_audit_events_for_reporting.sql` | Indexes behind the reporting queries |

To add a schema change, create `V4__describe_change.sql` in
`services/bank-api/src/main/resources/db/migration/`.
Never edit a migration that has already run.

The reports in [`ReportingRepository`](services/bank-api/src/main/java/io/github/hp2324/mockbank/reporting/ReportingRepository.java)
are plain SQL:

- **member portfolios:** `members LEFT JOIN accounts`, grouped, so members with no accounts still appear
- **recent sub-account openings:** `accounts JOIN members JOIN audit_events`, adding who opened each account
- **member activity:** `members LEFT JOIN audit_events` with `COUNT(*) FILTER (WHERE …)` per event type

Inspect the database directly:

```bash
docker compose exec postgres psql -U mockbank -d mockbank
```

Data persists in the `postgres-data` volume, so new account numbers keep counting up
(`SAV-345-3`, `-4`, …). `docker compose down -v` resets to the seed data.

---

## Events: Kafka and the audit consumer

Every audit row (sign-in, sign-out, opening, approval pause, permission denial) is published to
`mockbank.audit-events` **after its database transaction commits**. The key is the member ID
(or the operator ID if there is no member), so one member's events stay in order:

```json
{"eventId":"26ddbf08-…","type":"SUBACCOUNT_OPENED","operatorId":"operator","memberId":"12345",
 "accountNumber":"SAV-345-6","message":"Opened Savings Plus SAV-345-6 for member 12345",
 "occurredAt":"2026-10-26T18:04:48.238746Z"}
```

`audit-consumer` stores each event once in its own database; it ignores a redelivered
`eventId`. It retries transient failures and sends unreadable messages to
`mockbank.audit-events.DLT`, so they don't block the partition.

```bash
curl -s localhost:8081/api/events/summary                 # counts per event type
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic mockbank.audit-events \
  --from-beginning --property print.key=true                # watch the raw topic
```

If Kafka is down, the operator's action still succeeds and the failed publish is logged. The
database row is the source of truth (see [DESIGN §5](docs/DESIGN.md#5-audit-events-kafka) for the trade-off). Set `KAFKA_ENABLED=false`
to run without Kafka.

---

## Automation

### Setup

```bash
cd automation
python3 -m venv .venv && source .venv/bin/activate
python -m pip install -e ".[dev]"
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers python -m playwright install chromium
```

Run the commands below from `automation/` with the venv active and the app running on :8080.
They write evidence to `automation/evidence/`.

### Demo path: discover → approve → replay

**Option A, real LLM discovery** (needs an API key):

```bash
# 1) DISCOVER: Claude drives the live app once; the successful trace becomes a draft artifact.
export ANTHROPIC_API_KEY=...
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation discover-browser "Open a sub-account for member 12345" \
  --provider anthropic --model claude-sonnet-5 \
  --base-url http://localhost:8080 \
  --output artifacts/drafts/draft_from_claude.json \
  --evidence-dir evidence --headed

# 2) APPROVE: a human promotes the validated draft.
mock-bank-automation approve artifacts/drafts/draft_from_claude.json \
  --output artifacts/approved/approved_open_subaccount.json \
  --report evidence/approval-report.json \
  --approved-by "Harshit" \
  --reason "Reviewed selectors, inputs, outputs, checkpoint, and safety policy."

# 3) REPLAY (no LLM): run the approved artifact and verify the checkpoint.
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation replay artifacts/approved/approved_open_subaccount.json \
  --base-url http://localhost:8080 --evidence-dir evidence --require-approved
```

For OpenAI, use `--provider openai` with an OpenAI model and `OPENAI_API_KEY`.

**Option B, no API key:** the `scripted` provider drives the same observe → act loop. Use
`--provider scripted` in step 1 with output `artifacts/drafts/draft_from_browser_discovery.json`,
then approve and replay that file as above.

**Expected replay result:** `status: success`, checkpoint `CONFIRM-SUBACCOUNT-CREATED`, and typed
`outputs` (member, new account number, product, opening balance).

### Error taxonomy and human handoff

```bash
# Business outcomes: legitimate results, not crashes
mock-bank-automation replay artifacts/examples/member_not_found.json  --base-url http://localhost:8080 --evidence-dir evidence
mock-bank-automation replay artifacts/examples/invalid_member_id.json --base-url http://localhost:8080 --evidence-dir evidence
mock-bank-automation replay artifacts/examples/permission_denied.json --base-url http://localhost:8080 --evidence-dir evidence

# Human handoff: a deposit over $5,000 pauses on the live browser session
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation replay artifacts/examples/human_approval_required.json \
  --base-url http://localhost:8080 --evidence-dir evidence --headed --pause-on-handoff
# At HANDOFF REQUIRED: in Chromium, change Initial deposit to 100.00, click "Open sub-account",
# then press Enter in the terminal to resume verification.
```

### Replay every artifact at once

```bash
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers python scripts/replay_smoke.py --base-url http://localhost:8080
```

This replays all seven saved artifacts and checks each against its expected status (success,
business outcome or handoff). CI runs the same script.

### The UI contract artifacts depend on

The frontend is React, but it keeps what the saved artifacts select on: the same URLs, element
ids (`#memberId`, `#productName`, `#confirmation-code`, …), button and link text,
`.banner.success|warning|error` classes, and `<th>`/`<td>` label tables. While it is loading or
applying data, it sets `<html data-app-state="busy">`. Replay and discovery wait for that to clear
after every step ([DESIGN §3](docs/DESIGN.md#3-determinism--error-handling)). Vitest tests in `frontend/src/__tests__/domContract.test.jsx` fail if
this contract drifts.

---

## Tests

| Suite | Command | What it covers |
|---|---|---|
| bank-api unit | `./mvnw -DskipFrontend -pl services/bank-api test` | Sub-account rules, account numbering, JPA service (Mockito), Kafka publisher, MockMvc controller tests |
| bank-api integration | `./mvnw -DskipFrontend -pl services/bank-api verify` | Testcontainers PostgreSQL + Kafka: Flyway migrations and constraints, JPA, reporting SQL, full API flow, publish-after-commit |
| audit-consumer | `./mvnw -pl services/audit-consumer verify` | Payload parsing, REST API, and with Testcontainers: idempotent storage, summary SQL, dead-letter routing |
| frontend | `cd frontend && npm test` | API client, formatting, DOM contract used by the artifacts |
| automation | `cd automation && pytest` | Artifact contract, guardrails, discovery, replay, approval, evidence, settle wait |
| end to end | `python automation/scripts/replay_smoke.py` | Every saved artifact against the running stack |

Run everything Java at once from the root with `./mvnw -DskipFrontend verify` (or `make verify`).
Integration tests (`*IT.java`) need Docker running. `-DskipFrontend` skips the React build, which
the Java tests don't need.

### Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push and pull request, on
Ubuntu:

1. `backend`, `audit-consumer`, `frontend` and `automation` run the suites above in parallel.
2. `e2e` then starts the Docker Compose stack and replays every artifact in Chromium. It also
   checks that audit events reached the consumer through Kafka, and uploads the replay evidence.

---

## Deploying to Linux / AWS EC2

The stack runs the same on any Linux host with Docker. CI already exercises it on Ubuntu.
[`deploy/ec2/user-data.sh`](deploy/ec2/user-data.sh) is a user-data script for Ubuntu 24.04 that installs Docker,
clones this repo, sets a random database password and starts the stack. The instance exposes
only SSH, and you reach the app through an SSH tunnel. See
[`docs/deploy-ec2.md`](docs/deploy-ec2.md).

---

## Configuration

| Variable | Default | Used by |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/mockbank` (consumer: `…/audit_consumer`) | both services |
| `DB_USERNAME` / `DB_PASSWORD` | `mockbank` / `mockbank` | both services |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | both services |
| `KAFKA_ENABLED` | `true` | mock-bank: publish audit events to Kafka |
| `POSTGRES_PASSWORD` | `mockbank` | docker compose, read from `.env` (git-ignored) |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `No static resource index.html` | The app was built without the frontend. Run `cd frontend && npm run build`, or start it with `make run`. |
| Page doesn't match recent code / two apps on 8080 | `docker compose stop mock-bank` before running from Maven or IntelliJ |
| `Connection refused` to 5432 or 9092 | `docker compose up -d --wait postgres kafka` |
| Integration tests can't find Docker | Start Docker Desktop. Testcontainers needs a running engine. |
| Want a clean database | `docker compose down -v && docker compose up -d --wait` |

---

## Notes

- **Evidence predates the React frontend.** The runs in `automation/evidence/` were recorded in September
  2026 against the earlier server-rendered pages. The same artifacts replay unchanged against
  the React app; CI checks this on every push.
- **Secrets:** sensitive inputs are redacted from all evidence, and the artifact contract
  requires password-like inputs to be marked sensitive. `.env` is git-ignored.
- **Demo only:** the sign-in is a fixed demo account. No real bank, credentials or personal
  data are used anywhere.

---

## License

[MIT](LICENSE)
