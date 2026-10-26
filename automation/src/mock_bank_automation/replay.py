from __future__ import annotations

import json
import re
from collections.abc import Callable
from datetime import datetime
from pathlib import Path
from urllib.parse import urljoin

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright
from pydantic import BaseModel, Field

from .artifact import CapabilityArtifact, OutcomeRule, ReplayStep
from .guardrails import enforce_step_policy
from .replay_errors import ReplayFailure


class StepLog(BaseModel):
    step_id: str
    action: str
    status: str
    detail: str = ""


class ReplayResult(BaseModel):
    status: str
    artifact_name: str
    checkpoint: str | None = None
    outcome: dict[str, str] | None = None
    outputs: dict[str, str] = Field(default_factory=dict)
    evidence: dict[str, str] = Field(default_factory=dict)
    step_log: list[StepLog] = Field(default_factory=list)
    failure: dict[str, str] | None = None


def replay_artifact(
    artifact: CapabilityArtifact,
    base_url: str,
    evidence_dir: Path,
    headless: bool = True,
    timeout_ms: int = 10_000,
    auto_resume_handoff: bool = False,
    pause_on_handoff: bool = False,
    handoff_callback: Callable[[CapabilityArtifact, ReplayResult, str], None] | None = None,
    require_approved: bool = False,
) -> ReplayResult:
    evidence_dir.mkdir(parents=True, exist_ok=True)
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = evidence_dir / f"{artifact.name}-{run_id}"
    run_dir.mkdir(parents=True, exist_ok=True)
    screenshot_path = run_dir / "final.png"
    html_path = run_dir / "final.html"
    step_log_path = run_dir / "steps.json"
    result_path = run_dir / "result.json"
    summary_path = run_dir / "summary.md"
    common_evidence = _prepare_run_evidence(artifact, run_dir)
    common_evidence["summary"] = str(summary_path)
    handoff_request_path: Path | None = None
    handoff_evidence: dict[str, str] = {}
    step_log: list[StepLog] = []

    if require_approved and artifact.approval_state != "approved":
        result = ReplayResult(
            status="failure",
            artifact_name=artifact.name,
            failure={
                "step": "approval_gate",
                "message": "Strict replay requires an approved artifact.",
            },
        )
        result.evidence.update(common_evidence)
        result.evidence["result"] = str(result_path)
        _write_summary(summary_path, result)
        result_path.write_text(result.model_dump_json(indent=2), encoding="utf-8")
        return result

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=headless)
            page = browser.new_page()
            page.set_default_timeout(timeout_ms)

            try:
                for step in artifact.steps:
                    _execute_step(page, step, artifact, artifact.inputs, base_url)
                    wait_for_app_settled(page, step.id, timeout_ms)
                    step_log.append(StepLog(step_id=step.id, action=step.action, status="completed"))

                    outcome = _match_expected_outcome(page, artifact.expected_outcomes)
                    if outcome:
                        if outcome.status == "handoff_required" and pause_on_handoff:
                            paused_result = ReplayResult(
                                status="handoff_required",
                                artifact_name=artifact.name,
                                outcome={"name": outcome.name, "message": outcome.message},
                                step_log=step_log,
                            )
                            paused_result.evidence.update(common_evidence)
                            handoff_request_path = run_dir / "handoff.json"
                            _write_handoff_request(page, paused_result, artifact, handoff_request_path)
                            handoff_evidence["handoff_request"] = str(handoff_request_path)
                            handoff_evidence.update(_capture_named_page(page, artifact, run_dir, "handoff"))
                            paused_result.evidence.update(handoff_evidence)
                            pause_entry = StepLog(
                                step_id="handoff_pause",
                                action="human_handoff",
                                status="waiting",
                                detail="Replay paused for human operator intervention.",
                            )
                            step_log.append(pause_entry)
                            if handoff_callback:
                                handoff_callback(artifact, paused_result, page.url)
                            else:
                                input("Handoff required. Complete the manual action in the browser, then press Enter to continue...")
                            pause_entry.status = "completed"
                            step_log.append(StepLog(
                                step_id="handoff_resume",
                                action="manual_resume",
                                status="completed",
                                detail="Human operator resumed replay.",
                            ))
                            continue
                        if outcome.status == "handoff_required" and auto_resume_handoff:
                            _wait_for_handoff_resume(page, artifact, timeout_ms)
                            step_log.append(StepLog(
                                step_id="handoff_resume",
                                action="wait_for_selector",
                                status="completed",
                                detail="Human handoff was auto-resumed by detecting the configured resume selector.",
                            ))
                            continue
                        result = ReplayResult(
                            status=outcome.status,
                            artifact_name=artifact.name,
                            outcome={"name": outcome.name, "message": outcome.message},
                            step_log=step_log,
                        )
                        result.evidence.update(common_evidence)
                        return _finalize_result(page, result, artifact, screenshot_path, html_path, step_log_path, result_path, summary_path)

                checkpoint_text = None
                if artifact.checkpoint:
                    checkpoint = page.locator(artifact.checkpoint.selector)
                    checkpoint.wait_for(state="visible")
                    checkpoint_text = checkpoint.inner_text().strip()
                    if checkpoint_text != artifact.checkpoint.text:
                        raise ReplayFailure(
                            "checkpoint",
                            f"Expected checkpoint {artifact.checkpoint.text!r}, observed {checkpoint_text!r}.",
                        )

                if artifact.expected_outcomes and not artifact.checkpoint:
                    expected_names = ", ".join(rule.name for rule in artifact.expected_outcomes)
                    raise ReplayFailure(
                        "expected_outcome",
                        f"Expected one of these outcomes but none matched: {expected_names}.",
                    )

                outputs = {
                    name: page.locator(selector).first.inner_text().strip()
                    for name, selector in artifact.outputs.items()
                }
                result = ReplayResult(
                    status="success",
                    artifact_name=artifact.name,
                    checkpoint=checkpoint_text,
                    outputs=outputs,
                    step_log=step_log,
                )
                result.evidence.update(common_evidence)
                if handoff_request_path:
                    result.evidence.update(handoff_evidence)
                return _finalize_result(page, result, artifact, screenshot_path, html_path, step_log_path, result_path, summary_path)
            except (ReplayFailure, PlaywrightError, PlaywrightTimeoutError) as exc:
                result = ReplayResult(
                    status="failure",
                    artifact_name=artifact.name,
                    step_log=step_log,
                    failure={
                        "step": getattr(exc, "step_id", "unknown"),
                        "message": str(exc),
                    },
                )
                result.evidence.update(common_evidence)
                if handoff_request_path:
                    result.evidence.update(handoff_evidence)
                return _finalize_result(page, result, artifact, screenshot_path, html_path, step_log_path, result_path, summary_path)
            finally:
                browser.close()
    except Exception as exc:
        result = ReplayResult(
            status="failure",
            artifact_name=artifact.name,
            step_log=step_log,
            failure={"step": "browser_startup", "message": str(exc)},
        )
        result.evidence.update(common_evidence)
        result.evidence["result"] = str(result_path)
        _write_summary(summary_path, result)
        result_path.write_text(result.model_dump_json(indent=2), encoding="utf-8")
        return result


def _execute_step(page, step: ReplayStep, artifact: CapabilityArtifact, inputs: dict[str, str], base_url: str) -> None:
    try:
        if step.action == "goto":
            if not step.url:
                raise ReplayFailure(step.id, "goto step is missing url.")
            rendered_url = _render(step.url, inputs)
            enforce_step_policy(artifact, step, inputs, base_url, rendered_url=rendered_url)
            page.goto(urljoin(base_url.rstrip("/") + "/", rendered_url.lstrip("/")))
            return

        if not step.selector:
            raise ReplayFailure(step.id, f"{step.action} step is missing selector.")

        enforce_step_policy(artifact, step, inputs, base_url)
        locator = page.locator(step.selector)
        if step.action == "fill":
            locator.fill(_render(step.value or "", inputs))
        elif step.action == "click":
            locator.click()
        elif step.action == "wait_for_selector":
            locator.wait_for(state="visible")
        else:
            raise ReplayFailure(step.id, f"Unsupported action {step.action!r}.")
    except (PlaywrightError, PlaywrightTimeoutError) as exc:
        raise ReplayFailure(step.id, str(exc)) from exc


# Single-page apps render after their fetches resolve, so a banner can appear after a click has
# already returned. The mock bank marks <html data-app-state="busy"> while it is loading and
# applying data; pages without the attribute (server-rendered apps) count as settled immediately.
APP_SETTLED_SCRIPT = "() => document.documentElement.dataset.appState !== 'busy'"


def wait_for_app_settled(page, step_id: str, timeout_ms: int) -> None:
    try:
        page.wait_for_function(APP_SETTLED_SCRIPT, timeout=timeout_ms)
    except (PlaywrightError, PlaywrightTimeoutError) as exc:
        raise ReplayFailure(step_id, f"Page did not finish loading after this step: {exc}") from exc


def _match_expected_outcome(page, rules: list[OutcomeRule]) -> OutcomeRule | None:
    for rule in rules:
        locator = page.locator(rule.selector).first
        try:
            if locator.count() == 0:
                continue
            text = locator.inner_text(timeout=500).strip()
            if rule.contains_text is None or rule.contains_text in text:
                return rule
        except PlaywrightError:
            continue
    return None


def _wait_for_handoff_resume(page, artifact: CapabilityArtifact, timeout_ms: int) -> None:
    if not artifact.handoff.resume_selector:
        raise ReplayFailure("handoff_resume", "Handoff has no resume selector configured.")
    page.locator(artifact.handoff.resume_selector).wait_for(state="visible", timeout=timeout_ms)


def _finalize_result(page, result: ReplayResult, artifact: CapabilityArtifact, screenshot_path: Path, html_path: Path, step_log_path: Path, result_path: Path, summary_path: Path) -> ReplayResult:
    try:
        page.screenshot(path=str(screenshot_path), full_page=True)
        html_path.write_text(_redact_text(page.content(), artifact.inputs, artifact.sensitive_input_keys()), encoding="utf-8")
        result.evidence["screenshot"] = str(screenshot_path)
        result.evidence["html"] = str(html_path)
    except Exception as exc:
        result.evidence["capture_error"] = str(exc)

    step_log_path.write_text(json.dumps([entry.model_dump() for entry in result.step_log], indent=2), encoding="utf-8")
    result.evidence["step_log"] = str(step_log_path)
    if result.status == "handoff_required" and "handoff_request" not in result.evidence:
        handoff_path = result_path.with_name("handoff.json")
        _write_handoff_request(page, result, artifact, handoff_path)
        result.evidence["handoff_request"] = str(handoff_path)
    result.evidence["result"] = str(result_path)
    _write_summary(summary_path, result)
    result_path.write_text(result.model_dump_json(indent=2), encoding="utf-8")
    return result


def _prepare_run_evidence(artifact: CapabilityArtifact, run_dir: Path) -> dict[str, str]:
    inputs_path = run_dir / "inputs.redacted.json"
    artifact_path = run_dir / "artifact.redacted.json"
    inputs_path.write_text(json.dumps(redact_inputs(artifact.inputs, artifact.sensitive_input_keys()), indent=2), encoding="utf-8")
    artifact_data = artifact.model_dump(mode="json")
    artifact_data["inputs"] = redact_inputs(artifact.inputs, artifact.sensitive_input_keys())
    artifact_path.write_text(json.dumps(artifact_data, indent=2), encoding="utf-8")
    return {
        "run_dir": str(run_dir),
        "redacted_inputs": str(inputs_path),
        "artifact_snapshot": str(artifact_path),
    }


def _capture_named_page(page, artifact: CapabilityArtifact, run_dir: Path, name: str) -> dict[str, str]:
    paths = {
        f"{name}_screenshot": run_dir / f"{name}.png",
        f"{name}_html": run_dir / f"{name}.html",
    }
    try:
        page.screenshot(path=str(paths[f"{name}_screenshot"]), full_page=True)
        paths[f"{name}_html"].write_text(_redact_text(page.content(), artifact.inputs, artifact.sensitive_input_keys()), encoding="utf-8")
        return {key: str(value) for key, value in paths.items()}
    except Exception as exc:
        return {f"{name}_capture_error": str(exc)}


def _write_summary(summary_path: Path, result: ReplayResult) -> None:
    outcome = result.outcome["name"] if result.outcome else "none"
    failure = result.failure["message"] if result.failure else "none"
    lines = [
        f"# Replay Summary: {result.artifact_name}",
        "",
        f"- Status: `{result.status}`",
        f"- Checkpoint: `{result.checkpoint or 'none'}`",
        f"- Outcome: `{outcome}`",
        f"- Failure: `{failure}`",
        f"- Steps completed: `{len([entry for entry in result.step_log if entry.status == 'completed'])}`",
        "",
        "## Outputs",
    ]
    if result.outputs:
        lines.extend(f"- `{key}`: `{value}`" for key, value in result.outputs.items())
    else:
        lines.append("- none")
    lines.append("")
    lines.append("## Evidence")
    lines.extend(f"- `{key}`: `{value}`" for key, value in sorted(result.evidence.items()))
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _write_handoff_request(page, result: ReplayResult, artifact: CapabilityArtifact, handoff_path: Path) -> None:
    handoff_path.write_text(json.dumps(_handoff_request(page, result, artifact), indent=2), encoding="utf-8")


def _handoff_request(page, result: ReplayResult, artifact: CapabilityArtifact) -> dict[str, object]:
    return {
        "artifact_name": artifact.name,
        "status": result.status,
        "reason": artifact.handoff.reason,
        "operator_instructions": artifact.handoff.operator_instructions,
        "resume_selector": artifact.handoff.resume_selector,
        "current_url": page.url,
        "outcome": result.outcome,
        "step_log": [entry.model_dump() for entry in result.step_log],
        "created_at": datetime.now().isoformat(timespec="seconds"),
    }


def _redact_text(text: str, inputs: dict[str, str], redact_keys: list[str]) -> str:
    redacted = text
    for value in redact_inputs(inputs, redact_keys).values():
        if value == "[REDACTED]":
            continue
    for key, value in inputs.items():
        if key.lower() in {item.lower() for item in redact_keys} and value:
            redacted = redacted.replace(value, "[REDACTED]")
    return redacted


def _render(value: str, inputs: dict[str, str]) -> str:
    pattern = re.compile(r"{{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*}}")
    return pattern.sub(lambda match: inputs.get(match.group(1), match.group(0)), value)


def redact_inputs(inputs: dict[str, str], redact_keys: list[str]) -> dict[str, str]:
    lowered = {key.lower() for key in redact_keys}
    return {
        key: "[REDACTED]" if key.lower() in lowered else value
        for key, value in inputs.items()
    }


def result_to_json(result: ReplayResult) -> str:
    return json.dumps(result.model_dump(), indent=2)
