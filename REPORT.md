# Design Write-up: Computer-Use Automation System

A back-office automation system that lets an LLM **discover** a workflow once against a
live UI, records it as a **typed, versioned capability artifact**, and then **replays that
artifact deterministically without the LLM** — with explicit error handling, safety
guardrails, and a human-in-the-loop handoff seam.

The concrete surface implemented is a **mock retail-bank back office** (a Spring Boot app),
and the exercised goal is *"open a new sub-account for a member and reach the confirmation
screen."* One genuine LLM-driven discovery run against that live surface is included under
`/evidence/` (provider `anthropic`), alongside replay runs that hit the happy path, expected
business outcomes, and a human-approval handoff.

---

## 1. Architecture

Two processes, one clean seam between them.

```
                 target surface (React SPA over a Spring Boot + PostgreSQL API)
                 ┌───────────────────────────────────────────────┐
                 │  Spring Boot "mock bank" back office (:8080)    │
                 │  login → member search → detail → open subacct  │
                 │  seeded, deterministic; simulates real errors   │
                 └───────────────────────────────────────────────┘
                                   ▲              ▲
              observe / act (LLP)  │              │  observe / act (no LLM)
                                   │              │
   ┌───────────────────────────────┴───┐   ┌──────┴────────────────────────────┐
   │  DISCOVERY  (LLM in the loop)      │   │  REPLAY  (LLM NOT in the loop)     │
   │  browser_discovery.py              │   │  replay.py                         │
   │  observe→decide→act loop, bounded  │   │  execute recorded steps in order   │
   │  by max_steps; every proposed      │   │  match expected_outcomes → verify  │
   │  action passes guardrails BEFORE   │   │  checkpoint → extract typed outputs │
   │  it touches the page; success is   │   │  → structured ReplayResult          │
   │  compiled into a CapabilityArtifact│   │                                    │
   └───────────────┬────────────────────┘   └──────────────┬─────────────────────┘
                   │ emits                                  │ consumes
                   ▼                                        │
            ┌─────────────────────────────────────────────┐│
            │  CapabilityArtifact (typed, versioned JSON)  │◄┘
            │  contract in artifact.py (pydantic)          │
            │  draft ──approve()──► approved                │
            └─────────────────────────────────────────────┘
```

**Key decisions and trade-offs**

- **Discovery and replay share one artifact contract and one guardrail module, but nothing
  else.** The LLM only ever produces a candidate `CapabilityArtifact`; it is never in the
  replay path. This is the load-bearing boundary: the expensive, non-deterministic model
  runs once; the cheap, deterministic executor runs forever. Everything the model "learned"
  has to survive as declarative data in the artifact, or it is lost — which forces the schema
  to be complete.
- **The model is treated as untrusted.** Both discovery paths (`discover` for a whole-artifact
  proposal, `discover-browser` for step-by-step control) validate every model output against
  the same production contract and the same `enforce_step_policy` guardrails that replay uses,
  *before* acting or saving. A proposed artifact is always saved as `draft`; promotion to
  `approved` is a separate, human-authored step (`approve.py`) that writes an approval report.
- **Single process, synchronous Playwright.** No queue, no service mesh. The brief explicitly
  does not reward scaling infrastructure, and a synchronous executor is the simplest thing that
  makes determinism observable. The abstractions (surface adapter, locator strategy, artifact
  schema) are designed so this *could* be split into services later without reshaping the data.
- **Language/runtime:** Python for the agent/replay layer (Playwright + pydantic for a typed,
  self-validating schema); Java/Spring Boot + PostgreSQL for the target app, with a React
  frontend. The frontend was rebuilt from server-rendered pages without changing the artifact
  contract: same routes, element ids, link/button text, banner classes and label/value tables, so
  every saved artifact replays unchanged (checked by `automation/scripts/replay_smoke.py` in CI).
- **App-settled signal.** A server-rendered page is complete when Playwright's click returns; an
  SPA renders *after* its fetch resolves, so an outcome check right after a click could miss the
  banner that appears 50 ms later. The app keeps `<html data-app-state>` at `busy` from the
  moment a click or page load starts work until the result is committed to the DOM, and both
  replay and discovery wait for it to leave `busy` after every step. Pages without the
  attribute are treated as settled at once, so the executor still works against non-SPA
  surfaces.

---

## 2. Artifact schema

The artifact is the focal point, so it is a strict pydantic model (`CapabilityArtifact`,
`extra="forbid"`) that validates itself on load. It expresses far more than a step list:

| Section | Purpose |
|---|---|
| `schema_version`, `version` (MAJOR.MINOR.PATCH), `approval_state` (`draft`/`approved`) | Versioned + reviewable; replay can require `approved`. |
| `metadata` (`owner`, `app`, `app_version`, `notes`) | Which app/version this capability targets — the anchor for per-tenant/version drift. |
| `input_schema` + `inputs` | **Typed input parameters**: each input has `type`, `description`, `required`, `sensitive`. Keys must match the concrete `inputs` exactly. |
| `output_schema` + `outputs` | **Typed outputs to extract**: named fields, each backed by a locator, so a caller gets a typed object back, not a screenshot. |
| `steps[]` | Ordered actions (`goto`/`fill`/`click`/`wait_for_selector`) with `selector`/`url`/`value` and `{{placeholder}}` references into inputs; each has a stable snake_case `id`. |
| `locator` (per step, optional) | `selector` + `strategy` (`css`/`text`/`xpath`) + `robustness_note` — *how* an element is identified and *why* that is robust. |
| `checkpoint` | The success condition asserted after the steps (selector + expected text). |
| `expected_outcomes[]` | The **error taxonomy, declared in the artifact**: each rule maps a selector/text to a `status` of `business_outcome`, `handoff_required`, `recoverable`, or `failure`. |
| `handoff` | Reason, `resume_selector`, and operator instructions for the escalation path. |
| `safety` | Per-artifact allowlist (actions/hosts/paths), risky-selector policy, and redaction keys. |

**Why shaped this way.** The schema deliberately separates *what the capability is* (typed
inputs/outputs, checkpoint) from *how it is executed* (steps, locators) from *what may go
wrong* (expected_outcomes) from *what is permitted* (safety). A calling agent reads the first;
a human reviewer reads all four. The contract is enforced with ~40 explicit invariants
(`validate_contract`) — e.g. password-like inputs must be `sensitive` and appear in the
redaction list, `handoff_required` outcomes require a fully specified `handoff` block,
placeholders must reference known inputs, step ids must be unique and snake_case. That means an
LLM-proposed artifact either satisfies the production contract or is rejected with a precise
reason; there is no "mostly valid" state.

---

## 3. Determinism & error handling

**Determinism.** Replay never calls a model. It walks `steps` in order, renders
`{{placeholders}}` from the supplied inputs, and targets elements by the artifact's own
selectors. Success is not "the click didn't throw" — it is an *asserted* `checkpoint`
(selector visible **and** inner text equals the expected value). Outputs are then extracted by
locator into a typed dict. Given the same artifact + inputs against the same app version, the
run is reproducible.

**Error handling is a first-class result contract, not exceptions.** After every step, replay
checks the artifact's `expected_outcomes` and classifies into one of four buckets — mirroring
the brief's distinction between business outcomes, recoverable conditions, and hard failures:

- `success` — checkpoint reached, typed outputs returned.
- `business_outcome` — a legitimate result the caller must know about ("no such member",
  "invalid member ID"). Detected via a declared outcome rule; **not** treated as a crash.
- `handoff_required` — a state that needs a human (see §5).
- `failure` — an unrecoverable/unexpected condition (timeout, missing checkpoint, guardrail
  violation, browser-launch error), reported with `{step, message}` for debugging.

The mock app is built to *produce* these states on demand so the taxonomy is exercised against
real UI, not mocked: member `00000` → not found (business outcome), `abc` → invalid id
(validation/business outcome), member `77777` → permission denied, deposit > `$5,000` → human
approval required, `/debug/expire-session` → session-timeout redirect. UI drift (the brief's
secondary concern) surfaces as a `failure` at the specific step with the Playwright locator
error, which is enough to see *what* changed; the robustness of the locators themselves is
carried in each step's `locator.robustness_note`.

**Evidence per run** (`/evidence/<artifact>-<timestamp>/`): `result.json`, `steps.json`,
redacted `inputs`/`artifact` snapshots, a full-page `final.png`, redacted `final.html`, and a
human-readable `summary.md` — the "richer signal on failure" the brief asks for.

---

## 4. Heterogeneity & multi-tenant

Implemented against **one** surface, but the seams are designed for the Section-1 reality.

- **Surface abstraction.** The artifact records *intent* — an ordered list of typed actions
  plus a locator `strategy` per target and typed inputs/outputs — not a browser transcript.
  The web executor is one binding of that intent. A legacy-frameset web app or a native
  desktop app is a different *executor* that consumes the same schema: `goto/fill/click/wait`
  map onto accessibility-tree nodes or OS-level automation, and `strategy` grows values like
  `ax_role` or `automation_id`. The seam is exactly "how we perceive/act on a surface"
  (executor + locator strategy) vs. "the recorded flow" (artifact) — the artifact does not
  know it is a browser.
- **Multi-tenant reuse.** `metadata.app` + `app_version` identify the vendor product, and
  inputs are already parameterized (`{{member_id}}`), so one artifact is not per-member. To
  reuse across tenants running the *same* vendor product, the intended model is a **base
  artifact + per-tenant overlay**: tenant overrides carry only what differs (a branded
  selector, an extra confirmation step, a different base host in `safety.allowed_hosts`),
  layered onto the base rather than re-recorded. Concrete routes/values would be canonicalized
  into patterns (`/members/12345` → `/members/{{member_id}}`) — the placeholder machinery for
  this already exists.
- **Drift management.** `app_version` gates whether a saved artifact is trusted for a tenant;
  a checkpoint mismatch on replay is the drift signal that a base or overlay needs a fresh
  discovery run. Approval state means a re-discovered artifact re-enters as `draft` and must
  be re-approved before unattended use.

This is design, not build: overlays and cross-tenant canonicalization are described here and
left as a documented cut (§7), because building multi-tenant plumbing is explicitly not rewarded.

---

## 5. Escalation & handoff

The handoff is a real control-transfer mechanism on the **same live session**, not a TODO.

- **Detect & route.** When replay matches a `handoff_required` outcome (e.g. deposit over the
  $5,000 threshold), it writes a `handoff.json` intervention request carrying the capability
  name, the reason, the current URL, the `resume_selector`, operator instructions, and the
  completed step log — enough context for a human to act. With `--pause-on-handoff` it also
  captures `handoff.png` / `handoff.html` of the exact state.
- **Take control of the *same* session.** Replay runs headed and **pauses with the browser
  still open**, printing `HANDOFF REQUIRED` and the operator instructions to the terminal. The
  human performs the manual step in that same Chromium window (the automation was using it),
  then presses Enter. Replay resumes on the same page and verifies the checkpoint — so control
  is genuinely ceded and handed back, with the step log recording the pause/resume as steps
  (`human_handoff` → `manual_resume`).
- **Two hand-back modes:** interactive (`--pause-on-handoff`, a human decides) and
  `--auto-resume-handoff` (resume automatically once the configured `resume_selector` appears,
  for a mocked/headless operator surface).

**What is mocked, deliberately:** the "operator console" is the terminal + the live headed
browser rather than a real-time co-browsing UI. The brief explicitly scopes the full operator
console out; the *control-transfer model* (pause → cede the live session → capture what the
human did → resume and verify) is the real, load-bearing part and is implemented.

---

## 6. Safety

Guardrails are enforced by one module (`guardrails.enforce_step_policy`) that runs in **both**
discovery and replay, before any action reaches the page — so the LLM cannot act outside the
policy even during discovery.

- **Configurable allowlist per artifact** (`safety`): allowed action types
  (`goto/fill/click/wait_for_selector`), allowed hosts (localhost only here), and allowed
  routes as **exact paths + explicit prefixes** — notably `/` is *not* a catch-all, so an
  attempt to reach `/mock-bank` or any external host is blocked. (This actually fired in a real
  discovery run: the Anthropic provider proposed `goto /mock-bank` and the guardrail rejected it
  — see `/evidence/browser-discovery-20260910-142044`.)
- **Risky/irreversible actions** are named by selector with a `risky_action_policy` of
  `allow` / `block` / `require_handoff`. The sub-account submit button is the marked risky
  action; policy decides whether it proceeds, is blocked, or must route to a human.
- **No secrets or raw PII persisted.** Sensitive inputs (`password`, `token`, `secret`, plus
  anything schema-marked `sensitive`) are redacted from every artifact snapshot, inputs dump,
  and captured HTML before it is written to `/evidence/`. The contract *forces* password-like
  inputs to be marked sensitive and included in the redaction list, so an artifact that would
  leak a credential fails validation.

---

## 7. Cuts — what was left out, and what is next

Deliberate cuts, each at a clean, documented seam:

- **Operator console UI** — mocked as terminal + live browser (§5). Next: a minimal web
  operator surface that renders `handoff.json` and streams the live session.
- **Multi-tenant overlays & cross-tenant canonicalization** — designed (§4), not built. Next:
  a base-artifact + per-tenant override loader and a route→pattern canonicalizer.
- **Non-web executors** — only the Playwright web executor exists; the desktop/accessibility
  executor is described (§4). The schema is executor-agnostic so this is additive.
- **LLM-assisted recovery on replay failure** — replay currently stops and reports on hard
  failure rather than asking a model to recover a single step. Next: a bounded, policy-checked,
  single-step recovery recorded as evidence (the brief's "assisted fallback" stretch goal).
- **Locator hardening** — selectors are reasonable (ids + `has-text`) but a production system
  would add ordered fallbacks per target and a self-heal signal; `locator.strategy` /
  `robustness_note` are in the schema to hold this.
- **Confidence/approval scoring & multi-run stability** — approval is currently a human boolean;
  next is scoring an artifact by replay stability over N runs and gating unattended replay on it.

The through-line held to throughout: **the model discovers, the artifact becomes a reusable
capability, and deterministic replay is how the agent invokes it in production.** Depth went
into the four load-bearing pieces — the artifact schema, deterministic replay + error taxonomy,
the safety/guardrail model, and the control-transfer handoff — rather than breadth of features.
