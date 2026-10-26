# Mock Bank Automation

Python automation layer for the Spring Boot mock bank app.

This first iteration implements deterministic replay from a hand-written JSON artifact. It does not use an LLM yet. The purpose is to prove the future production path: load a reusable capability artifact, apply input parameters, drive the UI, verify a checkpoint, and save evidence.

> **Note on paths:** evidence now lives at the repository root `/evidence/` (a graded
> deliverable). Run the commands below from `automation/` and pass `--evidence-dir ../evidence`
> (and `--report ../evidence/...`) instead of the in-folder `evidence/` shown in some examples.
> See the top-level [`../README.md`](../README.md) for the canonical demo path.

## Setup

From `/Users/harshitpotluri/mock-bank`:

```bash
cd automation
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -e .[dev]
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers python -m playwright install chromium
```

## Run the mock bank app

In another terminal:

```bash
cd /Users/harshitpotluri/mock-bank
docker compose up -d --build --wait    # PostgreSQL, Kafka, mock-bank on :8080, audit-consumer on :8081
```

Or `docker compose up -d --wait postgres kafka` and then `mvn spring-boot:run` to run the app from source.

## Replay the happy path

```bash
cd /Users/harshitpotluri/mock-bank/automation
source .venv/bin/activate
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers mock-bank-automation replay artifacts/examples/open_subaccount_happy_path.json \
  --base-url http://localhost:8080 \
  --evidence-dir ../evidence
```

Expected result: a JSON result with `status: success`, the checkpoint `CONFIRM-SUBACCOUNT-CREATED`, and extracted output values for the new account.

## Artifact Folders

Artifacts are organized by lifecycle stage:

- `artifacts/examples/`: hand-written teaching and regression artifacts.
- `artifacts/drafts/`: generated proposals from local, file-backed, or future LLM discovery.
- `artifacts/approved/`: human-approved artifacts that can run with `--require-approved`.

The example folder includes several deterministic replay scenarios:

- `examples/open_subaccount_happy_path.json`: successful sub-account opening.
- `examples/member_not_found.json`: known business outcome for missing member `00000`.
- `examples/invalid_member_id.json`: known validation outcome for malformed member ID `abc`.
- `examples/permission_denied.json`: hard failure for restricted member `77777`.
- `examples/human_approval_required.json`: handoff-required outcome for deposits above `$5,000`; with interactive pause enabled, a human can correct the request and let replay verify the final confirmation.

Run any artifact with:

```bash
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers mock-bank-automation replay artifacts/examples/member_not_found.json --base-url http://localhost:8080 --evidence-dir ../evidence
```

Replay statuses:

- `success`: checkpoint was reached and outputs were extracted.
- `business_outcome`: the app returned an expected business result, such as member not found.
- `handoff_required`: automation reached a state that requires a human operator.
- `failure`: automation hit an unrecoverable or unexpected condition.

Each run writes a dedicated folder under `evidence/`, for example `evidence/open_subaccount_happy_path-20260910-130000/`. The run folder contains result JSON, step log JSON, redacted inputs, a redacted artifact snapshot, final screenshot, final HTML, and a human-readable `summary.md`.

## Interactive Handoff Pause

The handoff artifact can now pause with Chromium still open, so a human operator can decide what to do before replay verifies the result:

```bash
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers mock-bank-automation replay artifacts/examples/human_approval_required.json \
  --base-url http://localhost:8080 \
  --evidence-dir ../evidence \
  --headed \
  --pause-on-handoff
```

When the terminal prints `HANDOFF REQUIRED`, use the Chromium window to lower the Initial deposit from `5000.01` to `100.00`, click `Open sub-account`, then return to the terminal and press Enter. Replay then checks the confirmation checkpoint and extracts the final outputs. If the human does not complete the manual step, replay returns `failure` at the checkpoint.

## Controlled Discovery

The `discover` command is now the controlled LLM-discovery seam. A provider may propose an artifact, but the automation layer validates it before saving anything. Accepted artifacts are always saved as `draft`, never `approved`.

Local deterministic draft, useful while learning or testing without an API key:

```bash
mock-bank-automation discover "Open a sub-account for member 12345" \
  --provider local \
  --output artifacts/drafts/draft_from_goal.json \
  --report ../evidence/discovery-report.json
```

File-backed LLM simulation, useful for tests and debugging a model response:

```bash
mock-bank-automation discover "Open a sub-account for member 12345" \
  --provider file \
  --llm-response-file artifacts/examples/open_subaccount_happy_path.json \
  --output artifacts/drafts/draft_from_file_provider.json \
  --report ../evidence/discovery-report.json
```

OpenAI-backed discovery, useful once you are ready to try a real model-generated artifact:

```bash
export OPENAI_API_KEY=...
mock-bank-automation discover "Open a sub-account for member 12345" \
  --provider openai \
  --model gpt-5 \
  --output artifacts/drafts/draft_from_llm.json \
  --report ../evidence/discovery-report.json
```

Controlled discovery now uses the same production artifact contract as replay. The LLM/provider is only a producer of candidate JSON; it is not trusted.

Discovery checks include:

- JSON parses into the `CapabilityArtifact` schema.
- artifact is forced to `approval_state: draft`.
- schema version and semantic version are valid.
- artifact and step names use stable snake_case identifiers.
- `input_schema` exactly matches `inputs`.
- `output_schema` exactly matches `outputs`.
- sensitive inputs, such as `password`, are marked sensitive and redacted.
- step count is bounded.
- every placeholder references a known input.
- every step passes the same replay guardrails used by deterministic replay.
- the artifact defines either a checkpoint or an expected business/handoff outcome.

After discovery, replay the saved draft exactly like any other artifact. The LLM is not used during replay.




## Browser Discovery

The `discover-browser` command drives the live app once, records the successful browser trace, and converts that trace into a draft artifact. This is the assignment-critical path: the artifact produced here can later be approved and replayed without an LLM.

Scripted provider, useful for verifying trace recording without API calls:

```bash
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers mock-bank-automation discover-browser "Open a sub-account for member 12345" \
  --provider scripted \
  --base-url http://localhost:8080 \
  --output artifacts/drafts/draft_from_browser_discovery.json \
  --evidence-dir ../evidence \
  --headed
```

Anthropic provider, used when you are ready for Claude to choose each browser action from the live page summary:

```bash
export ANTHROPIC_API_KEY=...
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers mock-bank-automation discover-browser "Open a sub-account for member 12345" \
  --provider anthropic \
  --model claude-sonnet-5 \
  --base-url http://localhost:8080 \
  --output artifacts/drafts/draft_from_claude.json \
  --evidence-dir ../evidence \
  --headed
```

OpenAI provider is also available if `OPENAI_API_KEY` is set:

```bash
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers mock-bank-automation discover-browser "Open a sub-account for member 12345" \
  --provider openai \
  --model gpt-5 \
  --base-url http://localhost:8080 \
  --output artifacts/drafts/draft_from_llm.json \
  --evidence-dir ../evidence \
  --headed
```

All providers use the same guardrails before each browser action. A run writes `trace.json`, final screenshot/HTML, redacted inputs, and `browser-discovery-report.json` under an `evidence/browser-discovery-*` folder.

## Artifact Approval

Artifacts can be valid while still being only `draft`. This is intentional: LLM discovery and hand-written changes should produce drafts first. A human review step promotes a validated draft to `approved` and writes an approval report.

```bash
mock-bank-automation approve artifacts/drafts/draft_from_goal.json \
  --output artifacts/approved/approved_open_subaccount.json \
  --report ../evidence/approval-report.json \
  --approved-by "Harshit" \
  --reason "Reviewed selectors, inputs, outputs, checkpoint, and safety policy."
```

Strict replay can then require approved artifacts:

```bash
PLAYWRIGHT_BROWSERS_PATH=.playwright-browsers mock-bank-automation replay artifacts/approved/approved_open_subaccount.json \
  --base-url http://localhost:8080 \
  --evidence-dir ../evidence \
  --require-approved
```

If `--require-approved` is used with a draft artifact, replay stops at the approval gate before launching the browser.

## Safety Guardrails

Artifacts now carry a default `safety` policy. The replay engine enforces it before each browser action. The default policy allows only the mock-bank localhost app, only the supported action types, and known local routes. It can also block or require handoff for risky selectors such as the `Open sub-account` submit button.

Current enforced checks:

- action allowlist: `goto`, `fill`, `click`, `wait_for_selector`
- host allowlist: `localhost`, `localhost:8080`, `127.0.0.1`, `127.0.0.1:8080`
- route prefix allowlist for local app paths
- risky-action policy: `allow`, `block`, or `require_handoff`
- sensitive input redaction helper for keys such as `password`, `token`, and `secret`

These guardrails are intentionally enforced during deterministic replay first so the same checks can be reused when the LLM discovery loop is allowed to drive the browser.


## Hardened Artifact Contract

Artifacts now use a stricter production-style contract in addition to the executable steps:

- `schema_version` must be `0.1`.
- `name`, step ids, and outcome names must use stable snake_case identifiers.
- `version` must use `MAJOR.MINOR.PATCH`.
- `metadata.owner`, `metadata.app`, and `metadata.app_version` must be present.
- `input_schema` must exactly match `inputs`.
- `output_schema` must exactly match `outputs`.
- sensitive inputs must be marked sensitive and included in redaction policy.
- every step must have the right fields for its action type.
- every artifact must define a checkpoint or expected outcome.
- handoff artifacts must include a handoff outcome, reason, resume selector, and operator instructions.
- safety policy must explicitly allow actions, hosts, and local routes.

The same contract is enforced whether an artifact is handwritten, locally generated, or proposed by an LLM.

## Handoff Evidence

When replay returns `handoff_required`, the engine writes `handoff.json` inside the run evidence folder. It includes the reason, operator instructions, current URL, resume selector, outcome, and completed step log. With `--pause-on-handoff`, the engine also captures `handoff.png` and `handoff.html` before waiting for the human to act.

## Stronger Safety

The route policy no longer treats `/` as a catch-all prefix. It allows exact `/` plus explicit local app prefixes such as `/login` and `/members`. Attempts to navigate to another host or an unapproved local path are blocked before Playwright acts. Evidence HTML is redacted using sensitive input keys before it is written.
