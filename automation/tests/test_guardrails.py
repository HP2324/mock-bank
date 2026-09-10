from pathlib import Path

import pytest

from mock_bank_automation.artifact import CapabilityArtifact, ReplayStep
from mock_bank_automation.guardrails import GuardrailViolation, enforce_step_policy
from mock_bank_automation.replay import _redact_text, redact_inputs


def test_blocks_navigation_to_unapproved_host():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))
    step = ReplayStep(id="bad_nav", action="goto", url="https://example.com/login")

    with pytest.raises(GuardrailViolation):
        enforce_step_policy(artifact, step, artifact.inputs, "http://localhost:8080", rendered_url=step.url)


def test_blocks_unapproved_local_path():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))
    step = ReplayStep(id="bad_path", action="goto", url="/admin/delete-all")

    with pytest.raises(GuardrailViolation):
        enforce_step_policy(artifact, step, artifact.inputs, "http://localhost:8080", rendered_url=step.url)


def test_allows_approved_local_path():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))
    step = ReplayStep(id="member_path", action="goto", url="/members/12345")

    enforce_step_policy(artifact, step, artifact.inputs, "http://localhost:8080", rendered_url=step.url)


def test_blocks_disallowed_action():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))
    artifact.safety.allowed_actions = ["goto"]
    step = ReplayStep(id="bad_fill", action="fill", selector="#memberId", value="12345")

    with pytest.raises(GuardrailViolation):
        enforce_step_policy(artifact, step, artifact.inputs, "http://localhost:8080")


def test_risky_action_can_require_handoff_policy():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))
    artifact.safety.risky_action_policy = "require_handoff"
    step = ReplayStep(id="submit_subaccount", action="click", selector="button:has-text('Open sub-account')")

    with pytest.raises(GuardrailViolation):
        enforce_step_policy(artifact, step, artifact.inputs, "http://localhost:8080")


def test_handoff_artifact_allows_risky_action_with_handoff_policy():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/human_approval_required.json"))
    artifact.safety.risky_action_policy = "require_handoff"
    step = ReplayStep(id="submit_subaccount", action="click", selector="button:has-text('Open sub-account')")

    enforce_step_policy(artifact, step, artifact.inputs, "http://localhost:8080")


def test_redacts_sensitive_inputs():
    assert redact_inputs({"password": "password", "member_id": "12345"}, ["password"]) == {
        "password": "[REDACTED]",
        "member_id": "12345",
    }


def test_redacts_sensitive_values_from_html():
    html = '<input name="password" value="password"><p>12345</p>'
    redacted = _redact_text(html, {"password": "password", "member_id": "12345"}, ["password"])

    assert "password" not in redacted
    assert "[REDACTED]" in redacted
    assert "12345" in redacted
