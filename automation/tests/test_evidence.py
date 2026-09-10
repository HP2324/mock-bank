import json
from pathlib import Path

from mock_bank_automation.artifact import CapabilityArtifact
from mock_bank_automation.replay import ReplayResult, StepLog, _prepare_run_evidence, _write_summary


def test_prepare_run_evidence_redacts_sensitive_artifact_inputs(tmp_path):
    artifact = CapabilityArtifact.load(Path("artifacts/examples/open_subaccount_happy_path.json"))

    evidence = _prepare_run_evidence(artifact, tmp_path)

    redacted_inputs = json.loads(Path(evidence["redacted_inputs"]).read_text())
    artifact_snapshot = json.loads(Path(evidence["artifact_snapshot"]).read_text())
    assert redacted_inputs["password"] == "[REDACTED]"
    assert artifact_snapshot["inputs"]["password"] == "[REDACTED]"
    assert artifact_snapshot["inputs"]["member_id"] == "12345"


def test_write_summary_creates_human_readable_run_summary(tmp_path):
    result = ReplayResult(
        status="success",
        artifact_name="open_subaccount_happy_path",
        checkpoint="CONFIRM-SUBACCOUNT-CREATED",
        outputs={"new_account_number": "SAV-345-3"},
        evidence={"result": "result.json"},
        step_log=[StepLog(step_id="open_login", action="goto", status="completed")],
    )
    summary_path = tmp_path / "summary.md"

    _write_summary(summary_path, result)

    summary = summary_path.read_text()
    assert "Replay Summary" in summary
    assert "Status: `success`" in summary
    assert "new_account_number" in summary
