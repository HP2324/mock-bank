from pathlib import Path

from mock_bank_automation.artifact import CapabilityArtifact
from mock_bank_automation.discovery import create_draft_artifact
from mock_bank_automation.replay import _render


def test_loads_all_artifacts():
    artifact_paths = sorted(Path("artifacts").glob("**/*.json"))

    assert artifact_paths
    for path in artifact_paths:
        artifact = CapabilityArtifact.load(path)
        assert artifact.name
        assert artifact.steps


def test_loads_happy_path_artifact():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))

    assert artifact.name == "open_subaccount_happy_path"
    assert len(artifact.steps) == 10
    assert artifact.checkpoint is not None
    assert artifact.checkpoint.text == "CONFIRM-SUBACCOUNT-CREATED"


def test_business_outcome_artifact_has_expected_outcome():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/member_not_found.json"))

    assert artifact.expected_outcomes[0].name == "member_not_found"
    assert artifact.expected_outcomes[0].status == "business_outcome"


def test_handoff_artifact_has_policy():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/human_approval_required.json"))

    assert artifact.expected_outcomes[0].status == "handoff_required"
    assert artifact.handoff.enabled is True
    assert artifact.handoff.resume_selector == "#confirmation-code"
    assert artifact.handoff.operator_instructions


def test_handoff_artifact_can_verify_after_manual_resume():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/human_approval_required.json"))

    assert artifact.checkpoint is not None
    assert artifact.checkpoint.text == "CONFIRM-SUBACCOUNT-CREATED"
    assert "new_account_number" in artifact.outputs


def test_renders_input_placeholders():
    assert _render("Member {{ member_id }}", {"member_id": "12345"}) == "Member 12345"


def test_local_discovery_writes_draft(tmp_path):
    output = tmp_path / "draft.json"
    artifact = create_draft_artifact("Open a sub-account for member 12345", output)

    assert artifact.name == "draft_open_subaccount_from_goal"
    assert output.exists()


def test_permission_denied_is_business_outcome():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/permission_denied.json"))

    assert artifact.expected_outcomes[0].name == "permission_denied"
    assert artifact.expected_outcomes[0].status == "business_outcome"


def test_renders_url_placeholders():
    assert _render("/members/{{member_id}}/subaccounts/new", {"member_id": "77777"}) == "/members/77777/subaccounts/new"


def test_artifact_has_contract_defaults():
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))

    assert artifact.version == "0.1.0"
    assert artifact.approval_state == "draft"
    assert artifact.metadata.app == "mock-bank"
    assert "localhost:8080" in artifact.safety.allowed_hosts


def test_contract_rejects_missing_input_schema():
    data = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json")).model_dump(mode="json")
    data["input_schema"] = {}

    try:
        CapabilityArtifact.model_validate(data)
    except ValueError as exc:
        assert "input_schema must not be empty" in str(exc)
    else:
        raise AssertionError("weak artifact contract was accepted")


def test_contract_rejects_unredacted_sensitive_input():
    data = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json")).model_dump(mode="json")
    data["safety"]["redact_input_keys"] = ["token", "secret"]

    try:
        CapabilityArtifact.model_validate(data)
    except ValueError as exc:
        assert "sensitive input password" in str(exc)
    else:
        raise AssertionError("unredacted sensitive input was accepted")


def test_contract_rejects_output_schema_mismatch():
    data = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json")).model_dump(mode="json")
    data["output_schema"].pop("new_account_number")

    try:
        CapabilityArtifact.model_validate(data)
    except ValueError as exc:
        assert "output_schema keys must match outputs keys" in str(exc)
    else:
        raise AssertionError("output schema mismatch was accepted")
