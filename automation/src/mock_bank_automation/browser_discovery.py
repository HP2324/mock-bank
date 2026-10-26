from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Literal, Protocol
from urllib.parse import urljoin

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import Page, sync_playwright
from pydantic import BaseModel, Field, ValidationError

from .artifact import ArtifactField, CapabilityArtifact, Checkpoint, ReplayStep
from .discovery import DiscoveryValidationError, _response_output_text, _validate_discovered_artifact
from .guardrails import GuardrailViolation, enforce_step_policy
from .replay import APP_SETTLED_SCRIPT, _redact_text, _render, redact_inputs


BROWSER_DISCOVERY_SYSTEM_PROMPT = """You are controlling a mock bank back-office UI to complete a workflow once.
Return exactly one JSON action per turn. Use only the allowed routes, known selectors, and visible elements shown in the prompt.
If the browser is on about:blank or has no visible mock-bank elements, the first action must be goto /login.
Do not navigate to /mock-bank, external sites, or paths outside the allowed route list. Do not invent selectors.
Stop with done only after the confirmation checkpoint is visible.
"""

DEFAULT_DISCOVERY_INPUTS = {
    "operator_id": "operator",
    "password": "password",
    "member_id": "12345",
    "product_name": "Savings Plus",
    "initial_deposit": "100.00",
}

OUTPUT_SELECTORS = {
    "member_id": "//tr[th[normalize-space()='Member ID']]/td",
    "member_name": "//tr[th[normalize-space()='Member name']]/td",
    "new_account_number": "//tr[th[normalize-space()='New account number']]/td",
    "product": "//tr[th[normalize-space()='Product']]/td",
    "opening_balance": "//tr[th[normalize-space()='Opening balance']]/td",
}

INPUT_DESCRIPTIONS = {
    "operator_id": "Demo back-office operator username.",
    "password": "Demo back-office operator password.",
    "member_id": "Mock bank member identifier used by the workflow.",
    "product_name": "Sub-account product name entered in the form.",
    "initial_deposit": "Initial deposit amount entered for the new sub-account.",
}

OUTPUT_DESCRIPTIONS = {
    "member_id": "Member identifier shown on the confirmation page.",
    "member_name": "Member name shown on the confirmation page.",
    "new_account_number": "New sub-account number produced by the mock bank.",
    "product": "Product name confirmed by the mock bank.",
    "opening_balance": "Opening balance confirmed by the mock bank.",
}


class BrowserDiscoveryError(ValueError):
    pass


class BrowserAction(BaseModel):
    action: Literal["goto", "fill", "click", "wait_for_selector", "done"]
    selector: str | None = None
    url: str | None = None
    value: str | None = None
    reason: str = ""


class BrowserTraceEntry(BaseModel):
    step_id: str
    action: Literal["goto", "fill", "click", "wait_for_selector"]
    selector: str | None = None
    url: str | None = None
    value: str | None = None
    reason: str = ""
    page_url: str = ""


class BrowserDiscoveryReport(BaseModel):
    goal: str
    provider: str
    status: Literal["success", "failure"]
    artifact_name: str = ""
    output_path: str = ""
    trace_path: str = ""
    run_dir: str = ""
    checks: list[str] = Field(default_factory=list)
    errors: list[str] = Field(default_factory=list)
    created_at: str = Field(default_factory=lambda: datetime.now().isoformat(timespec="seconds"))


class ActionProvider(Protocol):
    name: str

    def next_action(self, goal: str, page_summary: dict[str, object], trace: list[BrowserTraceEntry]) -> BrowserAction:
        ...


class ScriptedOpenSubaccountProvider:
    name = "scripted"

    def __init__(self) -> None:
        self._actions = [
            BrowserAction(action="goto", url="/login", reason="Open login page."),
            BrowserAction(action="fill", selector="#operatorId", value="{{operator_id}}", reason="Enter operator id."),
            BrowserAction(action="fill", selector="#password", value="{{password}}", reason="Enter password."),
            BrowserAction(action="click", selector="button:has-text('Sign in')", reason="Submit login."),
            BrowserAction(action="fill", selector="#memberId", value="{{member_id}}", reason="Enter member id."),
            BrowserAction(action="click", selector="button:has-text('Search member records')", reason="Search member records."),
            BrowserAction(action="click", selector="a:has-text('Open sub-account')", reason="Open sub-account form."),
            BrowserAction(action="fill", selector="#productName", value="{{product_name}}", reason="Enter product name."),
            BrowserAction(action="fill", selector="#initialDeposit", value="{{initial_deposit}}", reason="Enter initial deposit."),
            BrowserAction(action="click", selector="button:has-text('Open sub-account')", reason="Submit sub-account form."),
            BrowserAction(action="done", reason="Confirmation checkpoint should now be visible."),
        ]

    def next_action(self, goal: str, page_summary: dict[str, object], trace: list[BrowserTraceEntry]) -> BrowserAction:
        if len(trace) >= len(self._actions):
            return BrowserAction(action="done", reason="Scripted trace is complete.")
        return self._actions[len(trace)]


class OpenAIActionProvider:
    name = "openai"

    def __init__(self, model: str) -> None:
        self.model = model

    def next_action(self, goal: str, page_summary: dict[str, object], trace: list[BrowserTraceEntry]) -> BrowserAction:
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise BrowserDiscoveryError("OPENAI_API_KEY is required when browser discovery provider is openai.")

        payload = {
            "model": self.model,
            "store": False,
            "input": json.dumps(_action_prompt(goal, page_summary, trace)),
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "browser_action",
                    "schema": BrowserAction.model_json_schema(),
                    "strict": False,
                }
            },
        }
        request = urllib.request.Request(
            "https://api.openai.com/v1/responses",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                body = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise BrowserDiscoveryError(f"OpenAI browser discovery request failed: {exc.code} {detail}") from exc
        except urllib.error.URLError as exc:
            raise BrowserDiscoveryError(f"OpenAI browser discovery request failed: {exc.reason}") from exc

        try:
            return BrowserAction.model_validate_json(_response_output_text(body))
        except (ValidationError, ValueError) as exc:
            raise BrowserDiscoveryError(f"OpenAI action response did not match BrowserAction: {exc}") from exc


class AnthropicActionProvider:
    name = "anthropic"

    def __init__(self, model: str) -> None:
        self.model = model if model != "gpt-5" else "claude-sonnet-5"

    def next_action(self, goal: str, page_summary: dict[str, object], trace: list[BrowserTraceEntry]) -> BrowserAction:
        api_key = os.getenv("ANTHROPIC_API_KEY")
        if not api_key:
            raise BrowserDiscoveryError("ANTHROPIC_API_KEY is required when browser discovery provider is anthropic.")

        payload = {
            "model": self.model,
            "max_tokens": 512,
            "system": BROWSER_DISCOVERY_SYSTEM_PROMPT,
            "messages": [
                {
                    "role": "user",
                    "content": json.dumps(_action_prompt(goal, page_summary, trace)),
                }
            ],
            "tools": [
                {
                    "name": "choose_browser_action",
                    "description": "Choose exactly one safe browser action for the mock bank workflow.",
                    "input_schema": BrowserAction.model_json_schema(),
                }
            ],
            "tool_choice": {"type": "tool", "name": "choose_browser_action"},
        }
        request = urllib.request.Request(
            "https://api.anthropic.com/v1/messages",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                body = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise BrowserDiscoveryError(f"Anthropic browser discovery request failed: {exc.code} {detail}") from exc
        except urllib.error.URLError as exc:
            raise BrowserDiscoveryError(f"Anthropic browser discovery request failed: {exc.reason}") from exc

        return _anthropic_action_from_response(body)


def discover_with_browser(
    goal: str,
    output_path: Path,
    base_url: str,
    evidence_dir: Path,
    provider_name: Literal["scripted", "openai", "anthropic"] = "scripted",
    model: str = "gpt-5",
    headless: bool = True,
    max_steps: int = 20,
    timeout_ms: int = 10_000,
) -> tuple[CapabilityArtifact, BrowserDiscoveryReport]:
    provider = _provider(provider_name, model)
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = evidence_dir / f"browser-discovery-{run_id}"
    run_dir.mkdir(parents=True, exist_ok=True)
    report = BrowserDiscoveryReport(goal=goal, provider=provider.name, status="failure", run_dir=str(run_dir))
    trace: list[BrowserTraceEntry] = []

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=headless)
            page = browser.new_page()
            page.set_default_timeout(timeout_ms)
            try:
                _run_browser_loop(page, goal, base_url, provider, trace, max_steps)
                _assert_checkpoint(page)
                artifact = artifact_from_trace(goal, trace)
                validation_report = _validate_artifact_for_browser_discovery(artifact, goal, base_url)
                output_path.parent.mkdir(parents=True, exist_ok=True)
                output_path.write_text(validation_report.model_dump_json(indent=2), encoding="utf-8")

                trace_path = run_dir / "trace.json"
                trace_path.write_text(json.dumps([entry.model_dump() for entry in trace], indent=2), encoding="utf-8")
                _capture_discovery_evidence(page, run_dir, artifact)

                report.status = "success"
                report.artifact_name = validation_report.name
                report.output_path = str(output_path)
                report.trace_path = str(trace_path)
                report.checks.extend(
                    [
                        "browser workflow reached confirmation checkpoint",
                        "trace converted into draft artifact",
                        "draft artifact passed production contract and guardrails",
                    ]
                )
                _write_browser_report(run_dir, report)
                return validation_report, report
            finally:
                browser.close()
    except Exception as exc:
        trace_path = run_dir / "trace.json"
        trace_path.write_text(json.dumps([entry.model_dump() for entry in trace], indent=2), encoding="utf-8")
        report.trace_path = str(trace_path)
        report.errors.append(str(exc))
        _write_browser_report(run_dir, report)
        raise


def artifact_from_trace(goal: str, trace: list[BrowserTraceEntry]) -> CapabilityArtifact:
    steps = [
        ReplayStep(
            id=entry.step_id,
            action=entry.action,
            selector=entry.selector,
            url=entry.url,
            value=_placeholder_value(entry.value),
            description=entry.reason,
        )
        for entry in trace
    ]
    return CapabilityArtifact(
        schema_version="0.1",
        name="draft_open_subaccount_from_browser_discovery",
        description=f"Draft artifact recorded from browser discovery goal: {goal}",
        version="0.1.0",
        approval_state="draft",
        input_schema={
            key: ArtifactField(type="string", description=INPUT_DESCRIPTIONS[key], required=True, sensitive=key == "password")
            for key in DEFAULT_DISCOVERY_INPUTS
        },
        output_schema={
            key: ArtifactField(type="string", description=OUTPUT_DESCRIPTIONS[key], required=True, sensitive=False)
            for key in OUTPUT_SELECTORS
        },
        inputs=DEFAULT_DISCOVERY_INPUTS,
        steps=steps,
        checkpoint=Checkpoint(selector="#confirmation-code", text="CONFIRM-SUBACCOUNT-CREATED"),
        outputs=OUTPUT_SELECTORS,
    )


def _run_browser_loop(page: Page, goal: str, base_url: str, provider: ActionProvider, trace: list[BrowserTraceEntry], max_steps: int) -> None:
    artifact_policy = artifact_from_trace(goal, [BrowserTraceEntry(step_id="open_login", action="goto", url="/login")])
    for index in range(max_steps):
        action = provider.next_action(goal, _page_summary(page), trace)
        if action.action == "done":
            return

        step = _action_to_step(action, index)
        rendered_url = _render(step.url, DEFAULT_DISCOVERY_INPUTS) if step.url else None
        try:
            enforce_step_policy(artifact_policy, step, DEFAULT_DISCOVERY_INPUTS, base_url, rendered_url=rendered_url)
        except GuardrailViolation as exc:
            raise BrowserDiscoveryError(
                "Provider proposed an unsafe or unsupported action at "
                f"step {index + 1}: action={action.action!r}, url={action.url!r}, "
                f"selector={action.selector!r}. {exc}"
            ) from exc
        _execute_browser_action(page, step, base_url)
        trace.append(
            BrowserTraceEntry(
                step_id=step.id,
                action=step.action,
                selector=step.selector,
                url=step.url,
                value=step.value,
                reason=action.reason,
                page_url=page.url,
            )
        )
    raise BrowserDiscoveryError(f"Browser discovery exceeded max_steps={max_steps}.")


def _execute_browser_action(page: Page, step: ReplayStep, base_url: str) -> None:
    try:
        if step.action == "goto":
            if not step.url:
                raise BrowserDiscoveryError(f"{step.id} is missing url.")
            page.goto(urljoin(base_url.rstrip("/") + "/", _render(step.url, DEFAULT_DISCOVERY_INPUTS).lstrip("/")))
            _wait_until_settled(page, step)
            return
        if not step.selector:
            raise BrowserDiscoveryError(f"{step.id} is missing selector.")
        locator = page.locator(step.selector)
        if step.action == "fill":
            locator.fill(_render(step.value or "", DEFAULT_DISCOVERY_INPUTS))
        elif step.action == "click":
            locator.click()
        elif step.action == "wait_for_selector":
            locator.wait_for(state="visible")
        else:
            raise BrowserDiscoveryError(f"Unsupported browser discovery action {step.action!r}.")
    except PlaywrightError as exc:
        raise BrowserDiscoveryError(f"{step.id} failed: {exc}") from exc
    _wait_until_settled(page, step)


def _wait_until_settled(page: Page, step: ReplayStep) -> None:
    # Observe the page only after the app has rendered the result of this action.
    try:
        page.wait_for_function(APP_SETTLED_SCRIPT, timeout=10_000)
    except PlaywrightError as exc:
        raise BrowserDiscoveryError(f"{step.id} failed: {exc}") from exc


def _action_to_step(action: BrowserAction, index: int) -> ReplayStep:
    slug_source = action.selector or action.url or action.action
    step_id = _step_id(action.action, slug_source, index)
    if action.action == "goto":
        return ReplayStep(id=step_id, action="goto", url=action.url, description=action.reason)
    if action.action in {"fill", "click", "wait_for_selector"}:
        return ReplayStep(id=step_id, action=action.action, selector=action.selector, value=action.value, description=action.reason)
    raise BrowserDiscoveryError("done actions cannot be converted to replay steps.")


def _step_id(action: str, source: str, index: int) -> str:
    source = source.replace("button:has-text('", "").replace("')", "")
    source = source.replace("a:has-text('", "")
    source = re.sub(r"[^a-zA-Z0-9]+", "_", source).strip("_").lower()
    if not source:
        source = action
    return f"step_{index + 1:02d}_{action}_{source}"[:60].rstrip("_")


def _page_summary(page: Page) -> dict[str, object]:
    visible: list[dict[str, str]] = []
    candidates = [
        ("#operatorId", "operator id input"),
        ("#password", "password input"),
        ("button:has-text('Sign in')", "sign in button"),
        ("#memberId", "member id input"),
        ("button:has-text('Search member records')", "search member records button"),
        ("a:has-text('Open sub-account')", "open sub-account link"),
        ("#productName", "product name input"),
        ("#initialDeposit", "initial deposit input"),
        ("button:has-text('Open sub-account')", "open sub-account submit button"),
        ("#confirmation-code", "confirmation code"),
        (".banner.warning", "warning banner"),
        (".banner.error", "error banner"),
    ]
    for selector, label in candidates:
        try:
            locator = page.locator(selector).first
            if locator.count() and locator.is_visible(timeout=200):
                text = locator.inner_text(timeout=200).strip() if not selector.startswith("#") else ""
                visible.append({"selector": selector, "label": label, "text": text})
        except PlaywrightError:
            continue
    return {"url": page.url, "title": page.title(), "visible_elements": visible}


def _assert_checkpoint(page: Page) -> None:
    checkpoint = page.locator("#confirmation-code")
    checkpoint.wait_for(state="visible")
    observed = checkpoint.inner_text().strip()
    if observed != "CONFIRM-SUBACCOUNT-CREATED":
        raise BrowserDiscoveryError(f"Expected confirmation checkpoint, observed {observed!r}.")


def _validate_artifact_for_browser_discovery(artifact: CapabilityArtifact, goal: str, base_url: str) -> CapabilityArtifact:
    class _Report(BaseModel):
        checks: list[str] = Field(default_factory=list)

    return _validate_discovered_artifact(artifact.model_dump(mode="json"), goal, base_url, _Report())  # type: ignore[arg-type]


def _capture_discovery_evidence(page: Page, run_dir: Path, artifact: CapabilityArtifact) -> None:
    page.screenshot(path=str(run_dir / "final.png"), full_page=True)
    (run_dir / "final.html").write_text(_redact_text(page.content(), artifact.inputs, artifact.sensitive_input_keys()), encoding="utf-8")
    (run_dir / "inputs.redacted.json").write_text(
        json.dumps(redact_inputs(artifact.inputs, artifact.sensitive_input_keys()), indent=2), encoding="utf-8"
    )


def _write_browser_report(run_dir: Path, report: BrowserDiscoveryReport) -> None:
    (run_dir / "browser-discovery-report.json").write_text(report.model_dump_json(indent=2), encoding="utf-8")


def _provider(provider_name: str, model: str) -> ActionProvider:
    if provider_name == "scripted":
        return ScriptedOpenSubaccountProvider()
    if provider_name == "openai":
        return OpenAIActionProvider(model)
    if provider_name in {"anthropic", "claude"}:
        return AnthropicActionProvider(model)
    raise BrowserDiscoveryError(f"Unsupported browser discovery provider {provider_name!r}.")


def _action_prompt(goal: str, page_summary: dict[str, object], trace: list[BrowserTraceEntry]) -> dict[str, object]:
    return {
        "goal": goal,
        "page_summary": page_summary,
        "inputs": DEFAULT_DISCOVERY_INPUTS,
        "allowed_routes": [
            "/login",
            "/members",
            "/members/{{member_id}}",
            "/members/{{member_id}}/subaccounts/new",
            "/members/{{member_id}}/subaccounts",
        ],
        "known_selectors": [
            "#operatorId",
            "#password",
            "button:has-text('Sign in')",
            "#memberId",
            "button:has-text('Search member records')",
            "a:has-text('Open sub-account')",
            "#productName",
            "#initialDeposit",
            "button:has-text('Open sub-account')",
            "#confirmation-code",
            ".banner.warning",
            ".banner.error",
        ],
        "initial_action_rule": "When page_summary.url is about:blank, call choose_browser_action with action='goto' and url='/login'.",
        "trace_so_far": [entry.model_dump(mode="json") for entry in trace],
        "required_output": "Call choose_browser_action with one BrowserAction object.",
    }


def _anthropic_action_from_response(body: dict[str, object]) -> BrowserAction:
    for content in body.get("content", []):
        if isinstance(content, dict) and content.get("type") == "tool_use" and content.get("name") == "choose_browser_action":
            try:
                return BrowserAction.model_validate(content.get("input"))
            except (ValidationError, ValueError) as exc:
                raise BrowserDiscoveryError(f"Anthropic action response did not match BrowserAction: {exc}") from exc
    raise BrowserDiscoveryError("Anthropic response did not include choose_browser_action tool_use.")


def _placeholder_value(value: str | None) -> str | None:
    if value is None:
        return None
    for key, input_value in DEFAULT_DISCOVERY_INPUTS.items():
        if value == input_value or value == f"{{{{{key}}}}}":
            return f"{{{{{key}}}}}"
    return value
