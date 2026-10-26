# Design Write-up: Computer-Use Automation System

This is a back-office automation system. An LLM **discovers** a workflow once against a live UI,
the system records it as a **typed, versioned capability artifact**, and then **replays that
artifact deterministically without the LLM**. Replay has explicit error handling, safety
guardrails, and a human-in-the-loop handoff.

The system automates a **mock retail-bank back office**: a React staff console over a Spring Boot
API, backed by PostgreSQL. Every audited action also flows through Kafka to a separate consumer
service. The exercised goal is *"open a new sub-account for a member and reach the confirmation
screen."* `/evidence/` holds one genuine LLM-driven discovery run (provider `anthropic`), plus
replay runs covering the happy path, expected business outcomes and a human-approval handoff.

The target app started as server-rendered pages on an in-memory H2 database. It was rebuilt as a
React SPA on PostgreSQL, Flyway and Kafka, **without changing a single saved artifact**. That
migration is itself a test of the design (§6).

| § | Topic |
|---|---|
| 1 | Architecture |
| 2 | Artifact schema |
| 3 | Determinism & error handling |
| 4 | Target application: API and data |
| 5 | Audit events: Kafka |
| 6 | Heterogeneity, drift & multi-tenant |
| 7 | Escalation & handoff |
| 8 | Safety |
| 9 | Testing & delivery |
| 10 | Cuts, and what is next |

---

## 1. Architecture

The system has two halves: the **automation layer**, which discovers and replays, and the
**target platform** it automates. The only coupling between them is the UI.

```
  AUTOMATION (Python)                                  TARGET PLATFORM
  ┌───────────────────────────────────┐
  │ DISCOVERY  (LLM in the loop)       │    Playwright    ┌──────────── mock-bank :8080 ───────────┐
  │ browser_discovery.py               │ ───────────────▶ │ React SPA ──/api──▶ Spring Boot API     │
  │ observe → decide → act, bounded;   │  observe / act   │ login → search → detail → open subacct  │
  │ guardrails check every action      │                  └──────┬──────────────────────┬──────────┘
  └─────────────┬─────────────────────┘                          │ JPA + SQL            │ after commit
                │ emits draft                                    ▼                      ▼
                ▼                                         PostgreSQL (Flyway)    Kafka mockbank.audit-events
  ┌───────────────────────────────────┐                   db mockbank                   │
  │ CapabilityArtifact (typed JSON)    │                                                 ▼
  │ draft ──approve()──▶ approved      │                   PostgreSQL ◀──────── audit-consumer :8081
  └─────────────┬─────────────────────┘                   db audit_consumer      └─▶ .DLT on bad input
                │ consumes
                ▼
  ┌───────────────────────────────────┐    Playwright
  │ REPLAY  (LLM NOT in the loop)      │ ───────────────▶  (same UI)
  │ replay.py: steps → outcomes →      │
  │ checkpoint → typed outputs         │
  └───────────────────────────────────┘
```

**Key decisions and trade-offs**

- **Discovery and replay share one artifact contract and one guardrail module, but nothing
  else.** The LLM only ever produces a candidate `CapabilityArtifact`; it is never in the replay
  path. This is the load-bearing boundary: the expensive, non-deterministic model runs once, and
  the cheap, deterministic executor runs forever. Anything the model "learned" has to survive as
  declarative data in the artifact or it is lost, which forces the schema to be complete.
- **The model is treated as untrusted.** Both discovery paths (`discover` for a whole-artifact
  proposal, `discover-browser` for step-by-step control) check every model output against the
  same production contract and the same `enforce_step_policy` guardrails that replay uses,
  *before* acting or saving. A proposed artifact is always saved as `draft`. Promotion to
  `approved` is a separate, human-authored step (`approve.py`) that writes an approval report.
- **The automation layer stays a single synchronous process.** It has no queue and no services.
  A synchronous Playwright executor is the simplest thing that makes determinism observable. The
  distributed parts (database, event stream, consumer) belong to the *target* platform, where
  they make the automated surface realistic rather than complicating the automation.
- **The target is a realistic stack, not a toy page.** A React SPA over a JSON API, a migrated
  relational schema, and an event stream are what back-office automation actually meets. The SPA
  is also *harder* to automate than server-rendered pages, because results render after a fetch
  rather than on a page load. §3 shows how replay stays deterministic anyway.
- **Languages:** Python for the agent and replay layer (Playwright, plus pydantic for a typed,
  self-validating schema). Java 17 / Spring Boot 3.5 for the API and consumer. React 18 + Vite
  for the console.

---

## 2. Artifact schema

The artifact is the focal point, so it is a strict pydantic model (`CapabilityArtifact`,
`extra="forbid"`) that validates itself on load. It expresses far more than a step list:

| Section | Purpose |
|---|---|
| `schema_version`, `version` (MAJOR.MINOR.PATCH), `approval_state` (`draft`/`approved`) | Versioned and reviewable; replay can require `approved`. |
| `metadata` (`owner`, `app`, `app_version`, `notes`) | Which app and version this capability targets. The anchor for per-tenant and per-version drift. |
| `input_schema` + `inputs` | **Typed input parameters**: each input has `type`, `description`, `required`, `sensitive`. Keys must match the concrete `inputs` exactly. |
| `output_schema` + `outputs` | **Typed outputs to extract**: named fields, each backed by a locator, so a caller gets a typed object back, not a screenshot. |
| `steps[]` | Ordered actions (`goto`/`fill`/`click`/`wait_for_selector`) with `selector`/`url`/`value` and `{{placeholder}}` references into inputs; each has a stable snake_case `id`. |
| `locator` (per step, optional) | `selector` + `strategy` (`css`/`text`/`xpath`) + `robustness_note`: *how* an element is identified and *why* that is robust. |
| `checkpoint` | The success condition asserted after the steps (selector + expected text). |
| `expected_outcomes[]` | The **error taxonomy, declared in the artifact**: each rule maps a selector and text to a `status` of `business_outcome`, `handoff_required`, `recoverable` or `failure`. |
| `handoff` | Reason, `resume_selector`, and operator instructions for the escalation path. |
| `safety` | Per-artifact allowlist (actions, hosts, paths), risky-selector policy, and redaction keys. |

**Why shaped this way.** The schema separates four concerns:

- *what the capability is*: typed inputs and outputs, and the checkpoint;
- *how it is executed*: steps and locators;
- *what may go wrong*: expected outcomes;
- *what is permitted*: safety.

A calling agent reads the first; a human reviewer reads all four. About 40 explicit invariants
(`validate_contract`) enforce the contract. For example, password-like inputs must be `sensitive`
and appear in the redaction list, `handoff_required` outcomes need a fully specified `handoff`
block, placeholders must reference known inputs, and step ids must be unique snake_case. An
LLM-proposed artifact either satisfies the production contract or is rejected with a precise
reason; there is no "mostly valid" state.

---

## 3. Determinism & error handling

**Determinism.** Replay never calls a model. It walks `steps` in order, renders
`{{placeholders}}` from the supplied inputs, and targets elements by the artifact's own
selectors. Success is not "the click didn't throw". It is an *asserted* `checkpoint`: the
selector is visible **and** its text equals the expected value. Outputs are then extracted by
locator into a typed dict. The same artifact and inputs against the same app version give a
reproducible run.

**The app-settled signal.** With server-rendered pages, a click that submits a form returns
only after the next page has loaded, so the outcome is already on screen. An SPA renders the
outcome *after* its fetch resolves, so an outcome check run right after the click can miss a
banner that appears 50 ms later. That is exactly the error that turns "member not found" into a
false `failure`. The fix is a small, explicit contract between the app and the executor:

- The React app sets `<html data-app-state="busy">` as soon as work starts. The page loads with
  it already set; a click or submit handler sets it synchronously, before Playwright's click
  returns.
- It applies results with `flushSync` and only then clears the flag, so `idle` means the DOM
  already shows the result. Navigation in React Router is kept synchronous for the same reason.
- After every step, replay and discovery wait for `dataset.appState !== 'busy'`. Pages without
  the attribute count as settled immediately, so the executor still works against non-SPA
  surfaces unchanged.

This is preferable to fixed sleeps (slow and still racy) and to `networkidle` (Playwright
resolves it immediately for in-page fetches once the page has loaded). The Vitest test
`domContract.test.jsx` pins the behaviour: it asserts the flag stays `busy` while a request is
pending and returns to `idle` once its result is rendered.

**Error handling is a first-class result contract, not exceptions.** After every step, replay
checks the artifact's `expected_outcomes` and classifies the run into one of four buckets:

- `success`: checkpoint reached, typed outputs returned.
- `business_outcome`: a legitimate result the caller must know about ("no such member",
  "invalid member ID", "permission denied"). Detected through a declared outcome rule and **not**
  treated as a crash.
- `handoff_required`: a state that needs a human (§7).
- `failure`: an unrecoverable or unexpected condition (timeout, missing checkpoint, guardrail
  violation, browser-launch error, an app that never settles), reported with `{step, message}`.

The mock produces these states on demand, so the taxonomy runs against a real UI. The API
returns typed errors (`{code, message}`), and the UI renders each as the banner the artifacts
expect:

| Input | API | UI |
|---|---|---|
| member `00000` | 404 `MEMBER_NOT_FOUND` | `.banner.warning` |
| member `abc` | 400 `INVALID_MEMBER_ID` | `.banner.error` |
| member `77777` | restricted member / 403 `PERMISSION_DENIED` | `.banner.error` |
| deposit > $5,000 | 409 `HUMAN_APPROVAL_REQUIRED` | `.banner.warning` |
| `/debug/expire-session` | session invalidated, 401 on next call | `/login?reason=expired` |

UI drift surfaces as a `failure` at the specific step, with the Playwright locator error saying
*what* changed. Each step's `locator.robustness_note` records why its selector should be stable.

**Evidence per run** (`/evidence/<artifact>-<timestamp>/`): `result.json`, `steps.json`, redacted
`inputs` and `artifact` snapshots, a full-page `final.png`, redacted `final.html`, and a
human-readable `summary.md`.

---

## 4. Target application: API and data

**API.** The Spring Boot app exposes a small JSON API (`/api/session`, `/api/members`,
`/api/audit-events`, `/api/reports`) and serves the React build for the client-side routes. The
session is the servlet session cookie, and an interceptor rejects unauthenticated `/api` calls
with a JSON 401. Two server routes remain because artifacts and drills navigate to them directly:
`/logout` and `/debug/expire-session`.

Business rules live in `SubAccountPolicy`, a plain class with no HTTP or persistence
dependencies, so they are unit-tested exhaustively. Its checks run in the same order as the
original form: amount format, product name, negative amount, then the $5,000 human-approval
threshold. The service layer re-checks the restriction, so a controller bug cannot open an
account for a restricted member.

**Schema ownership: Flyway, not Hibernate.** The schema is versioned SQL in
`db/migration`: `V1` creates the tables, `V2` seeds the demo members, and `V3` adds the reporting
indexes. Hibernate runs with `ddl-auto=validate`, so it only confirms that the entities still
match. That gives three things:

- reviewable DDL;
- the same schema in every environment (local, Testcontainers, CI, EC2);
- a hard startup failure, rather than silent drift, if code and schema disagree.

**The database enforces invariants itself:**

- member IDs must be five digits (`CHECK`);
- account numbers are unique;
- balances can't be negative;
- an audit event that names a member must reference a real one (foreign key).

The integration tests insert violating rows directly over JDBC to prove each one holds even if
application code is bypassed.

**Reporting is hand-written SQL** (`ReportingRepository`, `NamedParameterJdbcTemplate`). Reports
are read-only aggregates where SQL is clearer than JPQL and the exact join semantics matter:

- **member portfolios:** `members LEFT JOIN accounts … GROUP BY`. A `LEFT` join keeps members
  with no accounts, with `COALESCE(SUM(balance), 0)`.
- **recent sub-account openings:** `accounts JOIN members JOIN audit_events`, matched on account
  number and event type. This attributes each opening to the operator who made it, and it
  naturally excludes seeded accounts, which have no audit row.
- **member activity:** `members LEFT JOIN audit_events` with `COUNT(*) FILTER (WHERE event_type
  = …)`, one pass producing a column per event type, ordered `NULLS LAST`.

**Persistence vs. determinism.** H2 was re-seeded on every start; PostgreSQL keeps state across
restarts. Replay stays deterministic because artifacts never assert generated values: the new
account number is an *extracted output*, and the checkpoint is a fixed string.
`docker compose down -v` returns to the Flyway seed for a clean demo.

---

## 5. Audit events: Kafka

Every audited action writes an `audit_events` row and is published to `mockbank.audit-events` as
JSON. That covers sign-in and sign-out, failed sign-ins, openings, approval pauses and permission
denials.

- **Publish after commit.** The service raises a Spring application event inside the
  transaction. `KafkaAuditEventPublisher` handles it with
  `@TransactionalEventListener(AFTER_COMMIT)`, so a rolled-back action is never announced. An
  integration test rolls back a transaction and checks that its event never appears on the topic.
- **Keyed by member ID** (falling back to the operator ID), so one member's events land on one
  partition and stay in order.
- **The broker is not on the critical path.** If Kafka is down, the operator's request still
  succeeds and the failure is logged. `max.block.ms=3000` keeps a dead broker from stalling a UI
  request for the default 60 s.
- **The trade-off, stated plainly:** publishing after commit is *at-most-once* if the broker is
  unavailable at that moment or the process dies between the commit and the send. The database
  row remains the source of truth. The standard fix is a transactional outbox (§10).

**`audit-consumer`** is a separate Spring Boot service with its **own database**
(`audit_consumer`). It never reads mock-bank's tables; the only contract between the two is the
JSON payload.

- **Idempotent:** Kafka delivers at least once, so the consumer inserts with
  `ON CONFLICT (event_id) DO NOTHING`. A redelivered event is a no-op, which a test proves by
  sending the same payload twice.
- **Poison messages go to a dead-letter topic.** A payload that can't be parsed or lacks
  required fields raises `InvalidAuditEventException`. It is marked not-retryable and published
  straight to `mockbank.audit-events.DLT`, so it never blocks the partition. Other failures, such
  as the database restarting, are retried with backoff before dead-lettering.
- **Forward-compatible:** unknown JSON fields are ignored, so the producer can add fields
  without a coordinated release.
- It stores each event's Kafka partition and offset for traceability, and exposes
  `/api/events` and a `GROUP BY` summary at `/api/events/summary`.

---

## 6. Heterogeneity, drift & multi-tenant

The system is implemented against **one** surface, but the seams are designed for many.

- **Surface abstraction.** The artifact records *intent*: an ordered list of typed actions, a
  locator `strategy` per target, and typed inputs and outputs. It does not record a browser
  transcript, and the web executor is just one binding of that intent. A frameset-era web app or
  a native desktop app would be a different *executor* consuming the same schema:
  `goto/fill/click/wait` map onto accessibility-tree nodes or OS automation, and `strategy` grows
  values like `ax_role` or `automation_id`.
- **A real drift test: server-rendered → React.** The target app's whole rendering model
  changed: full-page form posts became client-side routing, server-built HTML became JSX, and the
  H2 database became PostgreSQL. All seven saved artifacts (including the one Claude discovered)
  replay unchanged, for two reasons:
  - the frontend deliberately kept the surface contract: routes, element ids, link and button
    text, banner classes, and `<th>`/`<td>` label tables;
  - the one behaviour that did change, when results appear, was handled once in the executor
    (§3) rather than in every artifact.

  `replay_smoke.py` runs every artifact against the live stack in CI, and the Vitest DOM-contract
  tests fail before a UI change can silently break them.
- **Multi-tenant reuse.** `metadata.app` and `app_version` identify the vendor product, and
  inputs are already parameterized (`{{member_id}}`), so an artifact is not tied to one member.
  To reuse it across tenants of the *same* product, the intended model is a **base artifact plus
  a per-tenant overlay**. An overlay carries only what differs (a branded selector, an extra
  confirmation step, a different host in `safety.allowed_hosts`) and is layered onto the base
  instead of being re-recorded.
- **Drift management.** `app_version` gates whether a saved artifact is trusted for a tenant,
  and a checkpoint mismatch on replay signals that a base or overlay needs fresh discovery. A
  re-discovered artifact re-enters as `draft` and must be approved again.

Overlays and cross-tenant canonicalization are designed, not built (§10).

---

## 7. Escalation & handoff

The handoff is a real transfer of control on the **same live session**.

- **Detect & route.** When replay matches a `handoff_required` outcome (a deposit over $5,000),
  it writes a `handoff.json` intervention request. The request carries the capability name, the
  reason, the current URL, the `resume_selector`, operator instructions, and the completed step
  log. With `--pause-on-handoff` it also captures `handoff.png` / `handoff.html` of the exact
  state. The server side records the pause as a `HUMAN_APPROVAL_REQUIRED` audit event, which is
  published to Kafka like any other.
- **Take control of the *same* session.** Replay runs headed and **pauses with the browser still
  open**, printing `HANDOFF REQUIRED` and the instructions. The human performs the manual step in
  that same Chromium window, then presses Enter. Replay resumes on the same page and verifies the
  checkpoint. The step log records the pause and resume as steps (`human_handoff` →
  `manual_resume`). The React form keeps the typed values after the approval banner appears, so
  the human edits the request instead of starting over.
- **Two hand-back modes:** interactive (`--pause-on-handoff`, a human decides) and
  `--auto-resume-handoff`, which resumes automatically once the `resume_selector` appears.

**Deliberately mocked:** the "operator console" is the terminal plus the live headed browser, not
a co-browsing UI. The control-transfer model is the load-bearing part, and it is real: pause,
cede the live session, let the human act, then resume and verify.

---

## 8. Safety

**Automation guardrails.** One module (`guardrails.enforce_step_policy`) runs in **both**
discovery and replay, before any action reaches the page, so the LLM cannot act outside policy
even during discovery.

- **Allowlist per artifact** (`safety`): allowed actions, allowed hosts (localhost here), and
  allowed routes as **exact paths plus explicit prefixes**. `/` is *not* a catch-all. This fired
  in a real run: Claude proposed `goto /mock-bank` and the guardrail rejected it (see
  `/evidence/browser-discovery-20260910-142044`).
- **Risky actions** are named by selector, with a `risky_action_policy` of `allow`, `block` or
  `require_handoff`. The sub-account submit button is the marked risky action.
- **No secrets or raw PII persisted.** Sensitive inputs are redacted from every snapshot, inputs
  dump and captured HTML before anything is written to `/evidence/`. The contract *forces*
  password-like inputs to be marked sensitive, so an artifact that would leak a credential fails
  validation.

**Platform defaults.**

- Every port in `docker-compose.yml` binds to `127.0.0.1`. The EC2 guide opens only SSH and
  reaches the app through an SSH tunnel, so the demo login is never internet-facing.
- The EC2 user-data script generates a random `POSTGRES_PASSWORD` into a git-ignored `.env`.
- Containers run as non-root users.
- The API returns typed, user-safe error messages. Unexpected exceptions are logged server-side
  and reach the client only as a generic `INTERNAL_ERROR`.
- The JSON error handler is scoped to the API package, so a missing static file is a plain 404
  rather than a logged 500.

---

## 9. Testing & delivery

The tests are layered so each concern is checked at the cheapest level that can actually see it.

| Layer | Where | What it proves |
|---|---|---|
| Pure unit | `SubAccountPolicyTest`, `AccountNumbersTest`, `AuditEventParserTest` | Business rules and parsing, including edge cases (exponents, `$`, commas, too many decimals, missing fields) |
| Mocked collaborators | `JpaBankServiceTest`, `KafkaAuditEventPublisherTest` | Service wiring, timestamp truncation to PostgreSQL precision, broker failures never reaching the caller |
| Web slice | `@WebMvcTest` controller tests | HTTP status, error codes and JSON shape for every outcome; auth interceptor; SPA routes |
| Real infrastructure | `*IT` with Testcontainers PostgreSQL 17 and Kafka 3.9 | Migrations and DB constraints, JPA against the real schema, reporting SQL, full API flow, publish-after-commit, consumer idempotency and dead-lettering |
| UI contract | Vitest + Testing Library | Every id, text, banner class and label the artifacts depend on, plus the app-settled signal |
| Automation | pytest | Artifact contract, guardrails, discovery, approval, evidence, the settle wait |
| End to end | `replay_smoke.py` against Docker Compose | Every saved artifact against the real stack in Chromium |

The Java side has 108 JUnit test runs, up from the original 4:

| Service | Unit | Integration |
|---|---|---|
| mock-bank | 66 | 22 |
| audit-consumer | 14 | 6 |

Alongside them are 18 Vitest and 37 pytest tests. The integration tests share one PostgreSQL
container per JVM. Each test either runs in a rolled-back transaction or asserts only on data it
created, so test order doesn't matter.

**Delivery.**

- **Images:** multi-stage Dockerfiles. Node builds the React app; Maven builds the jar with the
  frontend embedded; the runtime image is a JRE that runs as a non-root user and has a health
  check.
- **Compose:** `docker-compose.yml` runs PostgreSQL, Kafka (KRaft mode, no ZooKeeper), mock-bank
  and audit-consumer with health-gated startup. An init script creates the consumer's separate
  database.
- **CI** (`.github/workflows/ci.yml`, Ubuntu): four parallel jobs (backend, consumer, frontend,
  automation). An `e2e` job then builds and starts the Compose stack, replays every artifact,
  confirms audit events reached the consumer through Kafka, and uploads the replay evidence.
- **AWS:** `deploy/ec2/` holds a user-data script for Ubuntu 24.04 that installs Docker, clones
  the repo, generates secrets and starts the stack. The CI job already covers "runs on Linux".

---

## 10. Cuts, and what is next

Deliberate cuts, each at a clean seam:

**Automation**

- **Operator console UI:** mocked as the terminal plus the live browser (§7). Next: a small web
  surface that renders `handoff.json` and streams the live session.
- **Multi-tenant overlays and canonicalization:** designed (§6), not built. Next: a base +
  per-tenant override loader and a route → pattern canonicalizer.
- **Non-web executors:** only the Playwright executor exists. The schema is executor-agnostic, so
  this would be additive.
- **LLM-assisted recovery on replay failure:** replay stops and reports instead of asking a model
  to repair one step. Next: a bounded, policy-checked, single-step recovery recorded as evidence.
- **Locator hardening:** selectors are ids plus `has-text`. Next: ordered fallbacks per target
  and a self-heal signal, using the `locator.strategy` and `robustness_note` fields already in
  the schema.
- **Approval scoring:** approval is a human boolean. Next: score an artifact by its stability
  over N replays, and gate unattended use on that score.
- **Evidence refresh:** `/evidence/` was recorded against the server-rendered version. CI proves
  the artifacts still pass against React, but the committed screenshots show the old pages.

**Platform**

- **Transactional outbox:** publish-after-commit can drop an event if the broker is down at that
  instant (§5). Next: write events to an `outbox` table in the same transaction, and have a relay
  (or Debezium CDC) publish them, giving at-least-once delivery end to end.
- **Real authentication:** a single fixed demo operator signs in through the servlet session,
  with no CSRF token. Next: Spring Security with operator accounts and a supervisor role that can
  actually grant the "supervisor override" the permission-denied message refers to.
- **Account numbering:** numbers are `prefix-last3-(count+1)`. Two concurrent openings for one
  member, or two members sharing their last three digits, would collide. The unique constraint
  turns that into a failed request rather than corrupt data. Next: a database sequence.
- **Infrastructure as code:** EC2 setup is a user-data script, with no TLS, Terraform or managed
  services. Next: Terraform with RDS for PostgreSQL, Amazon MSK for Kafka, and HTTPS behind an
  ALB.

The through-line held throughout: **the model discovers, the artifact becomes a reusable
capability, and deterministic replay is how the agent invokes it in production.** Rebuilding the
whole target platform underneath that line, without touching an artifact, is the strongest
evidence that the line is in the right place.
