import json
from pathlib import Path

import pytest

from mock_bank_automation.approval import ApprovalError, approve_artifact
from mock_bank_automation.artifact import CapabilityArtifact
from mock_bank_automation.replay import replay_artifact


def test_approve_artifact_writes_approved_copy_and_report(tmp_path):
    source = Path("artifacts/examples/open_subaccount_happy_path.json")
    output = tmp_path / "approved_open_subaccount.json"
    report = tmp_path / "approval-report.json"

    artifact, approval_report = approve_artifact(
        source_path=source,
        output_path=output,
        report_path=report,
        approved_by="Harshit",
        reason="Reviewed selectors, inputs, outputs, safety policy, and checkpoint.",
    )

    saved = CapabilityArtifact.load(output)
    report_data = json.loads(report.read_text())
    assert artifact.approval_state == "approved"
    assert saved.approval_state == "approved"
    assert "Approved by Harshit" in saved.metadata.notes
    assert approval_report.status == "approved"
    assert report_data["status"] == "approved"
    assert report_data["approved_by"] == "Harshit"


def test_approve_artifact_requires_approver_and_reason(tmp_path):
    source = Path("artifacts/examples/open_subaccount_happy_path.json")
    output = tmp_path / "approved.json"
    report = tmp_path / "approval-report.json"

    with pytest.raises(ApprovalError):
        approve_artifact(
            source_path=source,
            output_path=output,
            report_path=report,
            approved_by="",
            reason="Reviewed.",
        )

    report_data = json.loads(report.read_text())
    assert report_data["status"] == "rejected"
    assert not output.exists()


def test_strict_replay_rejects_draft_before_browser_start(tmp_path):
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))

    result = replay_artifact(
        artifact=artifact,
        base_url="http://localhost:8080",
        evidence_dir=tmp_path,
        require_approved=True,
    )

    assert result.status == "failure"
    assert result.failure is not None
    assert result.failure["step"] == "approval_gate"
    assert "requires an approved artifact" in result.failure["message"]
    assert Path(result.evidence["result"]).exists()
    assert Path(result.evidence["summary"]).exists()


def test_strict_replay_allows_approved_artifact_to_reach_browser_layer(tmp_path):
    artifact_data = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json")).model_dump(mode="json")
    artifact_data["approval_state"] = "approved"
    artifact = CapabilityArtifact.model_validate(artifact_data)

    result = replay_artifact(
        artifact=artifact,
        base_url="http://localhost:65535",
        evidence_dir=tmp_path,
        timeout_ms=500,
        require_approved=True,
    )

    assert result.status == "failure"
    assert result.failure is not None
    assert result.failure["step"] != "approval_gate"
