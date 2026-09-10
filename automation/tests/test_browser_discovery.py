from pathlib import Path

import pytest

from mock_bank_automation.artifact import CapabilityArtifact
from mock_bank_automation.browser_discovery import (
    BrowserAction,
    BrowserTraceEntry,
    ScriptedOpenSubaccountProvider,
    _action_to_step,
    artifact_from_trace,
)
from mock_bank_automation.guardrails import GuardrailViolation, enforce_step_policy
from mock_bank_automation.replay import _render


def test_scripted_provider_trace_builds_valid_draft_artifact():
    provider = ScriptedOpenSubaccountProvider()
    trace = []
    goal = "Open a sub-account for member 12345"

    for index in range(10):
        action = provider.next_action(goal, {"visible_elements": []}, trace)
        step = _action_to_step(action, index)
        trace.append(
            BrowserTraceEntry(
                step_id=step.id,
                action=step.action,
                selector=step.selector,
                url=step.url,
                value=step.value,
                reason=action.reason,
            )
        )

    artifact = artifact_from_trace(goal, trace)

    assert artifact.name == "draft_open_subaccount_from_browser_discovery"
    assert artifact.approval_state == "draft"
    assert artifact.checkpoint is not None
    assert artifact.checkpoint.text == "CONFIRM-SUBACCOUNT-CREATED"
    assert len(artifact.steps) == 10
    assert artifact.steps[0].action == "goto"
    assert artifact.steps[-1].selector == "button:has-text('Open sub-account')"
    assert artifact.input_schema["password"].sensitive is True


def test_browser_discovery_artifact_can_round_trip_through_contract(tmp_path):
    trace = [
        BrowserTraceEntry(step_id="open_login", action="goto", url="/login"),
        BrowserTraceEntry(step_id="enter_operator_id", action="fill", selector="#operatorId", value="{{operator_id}}"),
        BrowserTraceEntry(step_id="enter_password", action="fill", selector="#password", value="{{password}}"),
        BrowserTraceEntry(step_id="submit_login", action="click", selector="button:has-text('Sign in')"),
        BrowserTraceEntry(step_id="enter_member_id", action="fill", selector="#memberId", value="{{member_id}}"),
        BrowserTraceEntry(step_id="search_member", action="click", selector="button:has-text('Search member records')"),
        BrowserTraceEntry(step_id="open_subaccount_form", action="click", selector="a:has-text('Open sub-account')"),
        BrowserTraceEntry(step_id="enter_product_name", action="fill", selector="#productName", value="{{product_name}}"),
        BrowserTraceEntry(step_id="enter_initial_deposit", action="fill", selector="#initialDeposit", value="{{initial_deposit}}"),
        BrowserTraceEntry(step_id="submit_subaccount", action="click", selector="button:has-text('Open sub-account')"),
    ]
    artifact = artifact_from_trace("Open a sub-account for member 12345", trace)
    path = tmp_path / "draft.json"

    path.write_text(artifact.model_dump_json(indent=2), encoding="utf-8")
    loaded = CapabilityArtifact.load(path)

    assert loaded.name == artifact.name
    assert loaded.outputs["new_account_number"].startswith("//tr")


def test_browser_discovery_rejects_unsafe_navigation_action():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))
    action = BrowserAction(action="goto", url="https://example.com/login")
    step = _action_to_step(action, 0)

    with pytest.raises(GuardrailViolation):
        enforce_step_policy(
            artifact,
            step,
            artifact.inputs,
            "http://localhost:8080",
            rendered_url=_render(step.url or "", artifact.inputs),
        )

from mock_bank_automation.browser_discovery import AnthropicActionProvider, BrowserDiscoveryError, _anthropic_action_from_response


def test_anthropic_tool_use_response_parses_browser_action():
    response = {
        "content": [
            {
                "type": "tool_use",
                "id": "toolu_123",
                "name": "choose_browser_action",
                "input": {
                    "action": "fill",
                    "selector": "#memberId",
                    "value": "{{member_id}}",
                    "reason": "Enter the member id.",
                },
            }
        ]
    }

    action = _anthropic_action_from_response(response)

    assert action.action == "fill"
    assert action.selector == "#memberId"
    assert action.value == "{{member_id}}"


def test_anthropic_provider_requires_api_key(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    provider = AnthropicActionProvider("claude-sonnet-5")

    with pytest.raises(BrowserDiscoveryError, match="ANTHROPIC_API_KEY"):
        provider.next_action("Open a sub-account for member 12345", {"visible_elements": []}, [])
