# Evidence — end-to-end demonstration

This folder is the demonstration deliverable: a **real LLM discovery run** against the live
mock-bank surface, the **draft artifact** it produced, **deterministic replay** runs (including
error/exceptional states), and a **human-approval handoff**. Every run folder contains a
`result.json` / report, a step or trace log, redacted inputs and artifact snapshot, and a
full-page screenshot + redacted HTML. Secrets/PII are redacted before anything is written here.

Each run folder's name is `<artifact-or-run>-<YYYYMMDD-HHMMSS>`.

## Discovery runs (LLM / provider in the loop)

| Folder | Provider | Result | What it shows |
|---|---|---|---|
| `browser-discovery-20260910-142503/` | **anthropic (Claude)** | ✅ success | **The genuine LLM-driven discovery run.** Claude observed the live page each turn and chose one safe action at a time until the confirmation checkpoint; the successful trace was compiled into a draft artifact (`../automation/artifacts/drafts/draft_from_claude.json`). |
| `browser-discovery-20260910-142044/` | anthropic (Claude) | ⛔ blocked | **Guardrail catching an unsafe model action.** The model proposed `goto /mock-bank`; the route allowlist rejected it before it touched the page. Demonstrates safety enforcement during discovery. |
| `browser-discovery-20260910-140331/` | openai | ⛔ failure | Graceful failure when `OPENAI_API_KEY` is unset — the provider errors cleanly instead of proceeding. |
| `browser-discovery-20260910-135905/` | scripted | ✅ success | Same observe→act loop and artifact compilation without an API key (offline pipeline check). |

## Replay runs (LLM NOT in the loop)

| Folder | Result | What it shows |
|---|---|---|
| `draft_open_subaccount_from_browser_discovery-20260910-142704/` | ✅ success | Deterministic replay of the **Claude-discovered** artifact → checkpoint `CONFIRM-SUBACCOUNT-CREATED`, typed outputs extracted. Closes the discovery→replay loop. |
| `draft_open_subaccount_from_browser_discovery-20260910-140125/` | ✅ success | Replay of the scripted-discovered artifact. |
| `draft_open_subaccount_from_goal-20260910-134528/` | ✅ success | Replay of a controlled-discovery draft, happy path. |
| `human_approval_required-20260910-130335/` | 🙋→✅ handoff then success | **Human-in-the-loop handoff.** Replay hit the >$5,000 `handoff_required` outcome, wrote `handoff.json` + `handoff.png`/`handoff.html`, paused on the live session for the operator, then resumed and verified the checkpoint. |

## Reports

- `approval-report.json`, `claude_approval_report.json` — human approval of a validated draft
  (draft → approved), with reviewer and reason.
- `discovery-report.json` — controlled whole-artifact discovery report (schema + guardrail checks).

## `_archive/`

Superseded or environment-only failures kept for completeness but out of the main listing:
two runs where headless Chromium failed to launch on the host machine (a sandbox/permissions
issue, not a system bug), and early `--require-approved` runs that correctly stopped at the
approval gate when handed a draft.
