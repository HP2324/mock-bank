# Computer-Use Automation System

A back-office automation system that lets an LLM **discover** a UI workflow once against a live
application, records it as a **typed, versioned capability artifact**, and then **replays that
artifact deterministically without the LLM** — with an explicit error taxonomy, safety
guardrails (allowlist + redaction), and a human-in-the-loop handoff that transfers control on
the *same* live session.

The concrete target surface is a **mock retail-bank back office**: a Spring Boot JSON API on
PostgreSQL (Flyway migrations) with a React single-page frontend, which publishes every audit-log
event to Kafka for a small consumer service. It keeps the table markup and simulated runtime
errors of a legacy back-office tool. The exercised goal is *"open a new sub-account for a member
and reach the confirmation screen."*

- **Design write-up:** [`REPORT.md`](REPORT.md) (architecture, artifact schema, determinism &
  error handling, heterogeneity/multi-tenant, escalation & handoff, safety, cuts).
- **End-to-end demonstration:** [`evidence/`](evidence/) — a real LLM discovery run, replay
  runs, and a human-approval handoff, with logs and screenshots. See
  [`evidence/README.md`](evidence/README.md).
- **Deep automation reference:** [`automation/README.md`](automation/README.md) — every command,
  provider, and flag in detail.

---

## Repository layout

```
/README.md            ← you are here (setup + demo path)
/REPORT.md            ← design write-up (7 sections)
/evidence/            ← discovery + replay demonstration (committed)
/automation/          ← Python agent-loop, artifact contract, replay engine, guardrails
    src/mock_bank_automation/
        browser_discovery.py   observe→decide→act loop (LLM in the loop)
        artifact.py            typed CapabilityArtifact contract (pydantic)
        replay.py              deterministic replay + error taxonomy (no LLM)
        guardrails.py          allowlist / risky-action / route policy
        discovery.py           whole-artifact controlled-discovery seam
        approval.py            draft → approved promotion
    artifacts/{examples,drafts,approved}/   saved capability artifacts
    tests/                     pytest suite
    README.md                  detailed command reference
/src, /pom.xml        ← the mock bank Spring Boot API (the target surface)
    src/main/resources/db/migration/   Flyway migrations (schema, seed data, indexes)
    .../reporting/ReportingRepository  hand-written SQL reports (joins + aggregates)
    .../audit/                         audit events → Kafka after commit
/frontend/            ← React + Vite staff console (served by Spring Boot on :8080)
/audit-consumer/      ← Spring Boot Kafka consumer; stores audit events in its own database
/docker-compose.yml   ← PostgreSQL, Kafka, mock-bank, audit-consumer
/.github/workflows/   ← CI: unit + Testcontainers tests, frontend, pytest, compose + replay e2e
/deploy/ec2/          ← running the stack on an Ubuntu EC2 instance
```

### How the pieces fit

```
React SPA ──/api──▶ mock-bank (Spring Boot) ──JPA/JDBC──▶ PostgreSQL  (db: mockbank, Flyway)
                         │ audit row committed
                         ▼
                   Kafka topic mockbank.audit-events ──▶ audit-consumer ──▶ PostgreSQL (db: audit_consumer)
                                                              └─ malformed messages ──▶ mockbank.audit-events.DLT
```

---

## Prerequisites

- **Docker Desktop** (or Docker Engine on Linux) — runs PostgreSQL and Kafka, and the
  Testcontainers integration tests.
- **Java 17+** and **Maven** (for the mock bank app). Node.js is optional: Maven downloads its own
  copy to build the frontend; install Node 22 only if you want the Vite dev server.
- **Python 3.11+** (for the automation layer).
- Model API access is needed **only** for a real LLM discovery run:
  `ANTHROPIC_API_KEY` (Claude) or `OPENAI_API_KEY` (OpenAI). Everything else — including a full
  discovery-to-replay demo — runs **without any key** using the `scripted`/`local` providers.

---

## Setup

**1. Start the target app.** Either run everything in Docker:

```bash
cd mock-bank            # repo root
docker compose up -d --build --wait
# http://localhost:8080  staff console     demo login: operator / password
# http://localhost:8081  audit-consumer    e.g. /api/events/summary
```

…or run only PostgreSQL and Kafka in Docker and the app from Maven (terminal 1):

```bash
docker compose up -d --wait postgres kafka
mvn spring-boot:run     # builds the React app into the jar's static files, serves :8080
```

Running `MockBankApplication` from IntelliJ also works: the IDE skips the Maven frontend step,
so the app serves the last build in `frontend/dist` instead (`cd frontend && npm run build` to
refresh it). Stop the Docker copy first (`docker compose stop mock-bank`) so only one app owns
port 8080.

To work on the UI with hot reload, also run `cd frontend && npm install && npm run dev` and open
http://localhost:5173 (API calls are proxied to :8080). Data now persists in the `postgres-data`
volume; `docker compose down -v` resets everything to the Flyway seed data.

**2. Set up the automation layer** (terminal 2):

```bash
cd mock-bank/automation
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e .[dev]
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers python -m playwright install chromium
```

All automation commands below are run from `mock-bank/automation` with the venv active. They
write evidence to the repo-root `/evidence/` folder via `--evidence-dir ../evidence`.

---

## Demo path (the core thread, end to end)

This runs the exact through-line the project is about: **goal → LLM discovery run → saved
artifact → approve → deterministic replay with typed outputs**.

### Option A — real LLM discovery (needs an API key)

```bash
# 1) DISCOVER: let Claude drive the live app once and record the successful trace as a draft.
export ANTHROPIC_API_KEY=...
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation discover-browser "Open a sub-account for member 12345" \
  --provider anthropic --model claude-sonnet-5 \
  --base-url http://localhost:8080 \
  --output artifacts/drafts/draft_from_claude.json \
  --evidence-dir ../evidence --headed

# 2) APPROVE: a human promotes the validated draft to an approved capability.
mock-bank-automation approve artifacts/drafts/draft_from_claude.json \
  --output artifacts/approved/approved_open_subaccount.json \
  --report ../evidence/approval-report.json \
  --approved-by "Harshit" \
  --reason "Reviewed selectors, inputs, outputs, checkpoint, and safety policy."

# 3) REPLAY (no LLM): run the approved artifact deterministically and verify the checkpoint.
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation replay artifacts/approved/approved_open_subaccount.json \
  --base-url http://localhost:8080 \
  --evidence-dir ../evidence --require-approved
```

`OPENAI_API_KEY` + `--provider openai` works identically. To use OpenAI, swap the provider/model.

### Option B — no API key (scripted discovery, same pipeline)

The `scripted` provider drives the same observe→act loop and produces a real recorded artifact,
so you can exercise the full pipeline offline:

```bash
# 1) DISCOVER (scripted): drive the live app and record a draft artifact.
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation discover-browser "Open a sub-account for member 12345" \
  --provider scripted --base-url http://localhost:8080 \
  --output artifacts/drafts/draft_from_browser_discovery.json \
  --evidence-dir ../evidence --headed

# 2) APPROVE
mock-bank-automation approve artifacts/drafts/draft_from_browser_discovery.json \
  --output artifacts/approved/approved_open_subaccount.json \
  --report ../evidence/approval-report.json \
  --approved-by "Harshit" --reason "Reviewed draft."

# 3) REPLAY (no LLM)
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation replay artifacts/approved/approved_open_subaccount.json \
  --base-url http://localhost:8080 --evidence-dir ../evidence --require-approved
```

**Expected replay result:** JSON with `status: success`, checkpoint
`CONFIRM-SUBACCOUNT-CREATED`, and typed `outputs` (new account number, product, opening
balance). A per-run evidence folder is written under `/evidence/`.

---

## Exercising the error taxonomy & handoff (replay against pre-built artifacts)

The mock app produces real runtime states on demand. These replay the saved example artifacts:

```bash
# Expected BUSINESS OUTCOME — member not found (00000): a legitimate result, not a crash.
mock-bank-automation replay artifacts/examples/member_not_found.json \
  --base-url http://localhost:8080 --evidence-dir ../evidence

# Expected BUSINESS OUTCOME — invalid member id (abc).
mock-bank-automation replay artifacts/examples/invalid_member_id.json \
  --base-url http://localhost:8080 --evidence-dir ../evidence

# HARD FAILURE — permission denied (restricted member 77777).
mock-bank-automation replay artifacts/examples/permission_denied.json \
  --base-url http://localhost:8080 --evidence-dir ../evidence

# HUMAN HANDOFF — deposit over $5,000 pauses on the live session for a human, then resumes.
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers \
mock-bank-automation replay artifacts/examples/human_approval_required.json \
  --base-url http://localhost:8080 --evidence-dir ../evidence \
  --headed --pause-on-handoff
# When the terminal prints HANDOFF REQUIRED: in the Chromium window lower the Initial deposit
# to 100.00, click "Open sub-account", then press Enter in the terminal to resume verification.
```

### Mock app states (for manual exploration at http://localhost:8080)

| Input | State demonstrated |
|---|---|
| Member `12345` (Jordan Lee), deposit < `$5,000` | Happy path → confirmation |
| Member `00000` | Member not found (business outcome) |
| Member `abc` | Invalid member ID (validation) |
| Member `77777` (Riley Morgan, restricted) | Permission denied |
| Deposit > `$5,000` | Human approval required (handoff) |
| Visit `/debug/expire-session` after login | Session-timeout redirect |

---

## Tests

```bash
cd mock-bank/automation && source .venv/bin/activate
pytest                       # artifact contract, guardrails, discovery, replay, approval, evidence

cd mock-bank
mvn -DskipFrontend test      # unit tests: policy, service, Kafka publisher, MockMvc controllers
mvn -DskipFrontend verify    # + Testcontainers ITs: Flyway, JPA, reporting SQL, API, Kafka publishing
mvn -f audit-consumer/pom.xml verify   # consumer unit tests + Kafka/PostgreSQL Testcontainers ITs
cd frontend && npm test      # Vitest: API client, formatting, DOM contract the artifacts rely on

# With the stack running: replay every saved artifact and check its expected outcome
cd automation && PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers python scripts/replay_smoke.py
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs all of the above on Ubuntu,
then starts the Docker Compose stack and replays the artifacts against it with Chromium.

---

## Notes

- The app stores data in PostgreSQL. Seed members come from a Flyway migration, so
  `docker compose down -v` returns to a known state. Replays don't depend on a clean database:
  account numbers are extracted as outputs, not asserted.
- The React frontend keeps the old pages' URLs, element ids, button/link text, `.banner.*`
  classes and `<th>`/`<td>` tables, so artifacts recorded against the server-rendered version
  replay unchanged. While it is fetching, it sets `<html data-app-state="busy">`, and replay waits
  for that to clear after each step (see REPORT §1).
- Running on Linux / AWS: see [`deploy/ec2/README.md`](deploy/ec2/README.md).
- Secrets are never committed or persisted: sensitive inputs are redacted from all evidence, and
  the artifact contract *requires* password-like inputs to be marked sensitive and redacted.
- No real bank, real credentials, or real PII is used anywhere. The target is a local mock only.
