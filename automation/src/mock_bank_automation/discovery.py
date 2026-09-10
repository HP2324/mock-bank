from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, ValidationError

from .artifact import CapabilityArtifact
from .guardrails import GuardrailViolation, enforce_step_policy
from .replay import _render


DISCOVERY_SYSTEM_PROMPT = """You are creating a deterministic UI automation artifact for the mock bank app.
Return only JSON that matches the CapabilityArtifact schema.
Use only these actions: goto, fill, click, wait_for_selector.
Use only the allowed mock-bank localhost routes and selectors shown in the context.
The artifact must be replayable without an LLM after it is saved.
Set approval_state to draft. Never mark an artifact approved.
Never include production credentials, tokens, external URLs, or destructive actions.
"""

_ALLOWED_UI_CONTEXT = {
    "app": "mock-bank",
    "base_url": "http://localhost:8080",
    "allowed_routes": [
        "/login",
        "/members",
        "/members/{member_id}",
        "/members/{member_id}/subaccounts/new",
        "/members/{member_id}/subaccounts",
    ],
    "known_selectors": {
        "login.operator_id": "#operatorId",
        "login.password": "#password",
        "login.submit": "button:has-text('Sign in')",
        "member_search.member_id": "#memberId",
        "member_search.submit": "button:has-text('Search member records')",
        "member_detail.open_subaccount": "a:has-text('Open sub-account')",
        "subaccount.product_name": "#productName",
        "subaccount.initial_deposit": "#initialDeposit",
        "subaccount.submit": "button:has-text('Open sub-account')",
        "confirmation.code": "#confirmation-code",
        "warning.banner": ".banner.warning",
        "error.banner": ".banner.error",
    },
    "demo_inputs": {
        "operator_id": "operator",
        "password": "password",
        "member_id": "12345",
        "product_name": "Savings Plus",
        "initial_deposit": "100.00",
    },
    "expected_success_checkpoint": {
        "selector": "#confirmation-code",
        "text": "CONFIRM-SUBACCOUNT-CREATED",
    },
}


class DiscoveryValidationError(ValueError):
    pass


class DiscoveryReport(BaseModel):
    goal: str
    provider: str
    artifact_name: str
    status: Literal["accepted", "rejected"]
    checks: list[str] = Field(default_factory=list)
    errors: list[str] = Field(default_factory=list)
    created_at: str = Field(default_factory=lambda: datetime.now().isoformat(timespec="seconds"))


def create_draft_artifact(goal: str, output_path: Path, base_url: str = "http://localhost:8080") -> CapabilityArtifact:
    """Backward-compatible local draft creation used by the original discover command."""
    artifact, _ = discover_controlled(
        goal=goal,
        output_path=output_path,
        base_url=base_url,
        provider="local",
    )
    return artifact


def discover_controlled(
    goal: str,
    output_path: Path,
    base_url: str = "http://localhost:8080",
    provider: Literal["local", "file", "openai"] = "local",
    llm_response_file: Path | None = None,
    report_path: Path | None = None,
    model: str = "gpt-5",
) -> tuple[CapabilityArtifact, DiscoveryReport]:
    """Ask a bounded provider for an artifact, validate it, then save it as draft.

    The provider is intentionally less trusted than replay. It can propose JSON, but
    this function owns schema validation, safety validation, and draft status.
    """
    report = DiscoveryReport(goal=goal, provider=provider, artifact_name="", status="rejected")
    try:
        candidate = _candidate_from_provider(goal, provider, llm_response_file, model)
        artifact = _validate_discovered_artifact(candidate, goal, base_url, report)
    except Exception as exc:
        report.errors.append(str(exc))
        if report_path:
            _write_report(report_path, report)
        raise

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(artifact.model_dump_json(indent=2), encoding="utf-8")
    report.artifact_name = artifact.name
    report.status = "accepted"
    report.checks.append(f"wrote draft artifact to {output_path}")
    if report_path:
        _write_report(report_path, report)
    return artifact, report


def _candidate_from_provider(goal: str, provider: str, llm_response_file: Path | None, model: str) -> dict:
    if provider == "local":
        return json.loads(_local_draft(goal).model_dump_json())
    if provider == "file":
        if not llm_response_file:
            raise DiscoveryValidationError("--llm-response-file is required when provider is file.")
        return _extract_json(llm_response_file.read_text(encoding="utf-8"))
    if provider == "openai":
        return _call_openai_for_artifact(goal, model)
    raise DiscoveryValidationError(f"Unknown discovery provider: {provider}")


def _call_openai_for_artifact(goal: str, model: str) -> dict:
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise DiscoveryValidationError("OPENAI_API_KEY is required when provider is openai.")

    prompt = {
        "system": DISCOVERY_SYSTEM_PROMPT,
        "goal": goal,
        "ui_context": _ALLOWED_UI_CONTEXT,
        "artifact_schema": CapabilityArtifact.model_json_schema(),
    }
    payload = {
        "model": model,
        "input": json.dumps(prompt),
        "store": False,
        "text": {
            "format": {
                "type": "json_schema",
                "name": "capability_artifact",
                "schema": CapabilityArtifact.model_json_schema(),
                "strict": False,
            }
        },
    }
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise DiscoveryValidationError(f"OpenAI discovery request failed: {exc.code} {detail}") from exc
    except urllib.error.URLError as exc:
        raise DiscoveryValidationError(f"OpenAI discovery request failed: {exc.reason}") from exc

    return _extract_json(_response_output_text(body))


def _response_output_text(body: dict) -> str:
    if isinstance(body.get("output_text"), str):
        return body["output_text"]
    chunks: list[str] = []
    for item in body.get("output", []):
        for content in item.get("content", []):
            text = content.get("text")
            if isinstance(text, str):
                chunks.append(text)
    if not chunks:
        raise DiscoveryValidationError("OpenAI response did not include text output.")
    return "\n".join(chunks)


def _extract_json(text: str) -> dict:
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as exc:
        raise DiscoveryValidationError(f"LLM response was not valid JSON: {exc}") from exc
    if not isinstance(data, dict):
        raise DiscoveryValidationError("LLM response JSON must be an object.")
    return data


def _validate_discovered_artifact(candidate: dict, goal: str, base_url: str, report: DiscoveryReport) -> CapabilityArtifact:
    try:
        artifact = CapabilityArtifact.model_validate(candidate)
    except (ValidationError, ValueError) as exc:
        raise DiscoveryValidationError(str(exc)) from exc
    report.checks.append("candidate matches CapabilityArtifact schema")

    artifact.approval_state = "draft"
    artifact.metadata.app = "mock-bank"
    artifact.metadata.owner = "mock-bank-automation"
    artifact.metadata.notes = f"Controlled LLM discovery draft for goal: {goal}"

    if artifact.schema_version != "0.1":
        raise DiscoveryValidationError("schema_version must be 0.1 for this project.")
    report.checks.append("schema version accepted")

    if not artifact.steps:
        raise DiscoveryValidationError("artifact must contain at least one replay step.")
    if len(artifact.steps) > 20:
        raise DiscoveryValidationError("artifact has too many steps for controlled discovery.")
    report.checks.append("step count accepted")

    if not artifact.checkpoint and not artifact.expected_outcomes:
        raise DiscoveryValidationError("artifact must define a checkpoint or expected outcome.")
    report.checks.append("completion condition exists")

    for step in artifact.steps:
        _validate_placeholders(step.value, artifact.inputs, step.id)
        _validate_placeholders(step.url, artifact.inputs, step.id)
        try:
            enforce_step_policy(
                artifact,
                step,
                artifact.inputs,
                base_url,
                rendered_url=_render(step.url, artifact.inputs) if step.url else None,
            )
        except GuardrailViolation as exc:
            raise DiscoveryValidationError(str(exc)) from exc
    report.checks.append("all steps passed replay guardrails")

    if artifact.handoff.enabled and not artifact.expected_outcomes:
        raise DiscoveryValidationError("handoff-enabled artifacts must define an expected handoff outcome.")
    report.checks.append("handoff contract accepted")

    return artifact


def _validate_placeholders(value: str | None, inputs: dict[str, str], step_id: str) -> None:
    if value is None:
        return
    for placeholder in re.findall(r"{{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*}}", value):
        if placeholder not in inputs:
            raise DiscoveryValidationError(f"Step {step_id!r} references unknown input {placeholder!r}.")


def _write_report(report_path: Path, report: DiscoveryReport) -> None:
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report.model_dump_json(indent=2), encoding="utf-8")


def _local_draft(goal: str) -> CapabilityArtifact:
    lower = goal.lower()
    if "open" not in lower or "sub-account" not in lower:
        raise ValueError("Local draft discovery only knows the open sub-account happy path. Use provider=openai or provider=file for arbitrary goals.")

    template = Path(__file__).resolve().parents[2] / "artifacts" / "examples" / "open_subaccount_happy_path.json"
    data = json.loads(template.read_text(encoding="utf-8"))
    data["name"] = "draft_open_subaccount_from_goal"
    data["description"] = f"Draft artifact produced from goal: {goal}"
    return CapabilityArtifact.model_validate(data)
