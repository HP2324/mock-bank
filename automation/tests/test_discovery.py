import json
from pathlib import Path

import pytest

from mock_bank_automation.artifact import CapabilityArtifact
from mock_bank_automation.discovery import DiscoveryValidationError, discover_controlled


def test_controlled_file_discovery_accepts_valid_draft(tmp_path):
    candidate = json.loads(Path("artifacts/examples/open_subaccount_happy_path.json").read_text())
    candidate["name"] = "llm_candidate_open_subaccount"
    candidate["approval_state"] = "approved"
    response_file = tmp_path / "llm-response.json"
    output_file = tmp_path / "draft.json"
    report_file = tmp_path / "report.json"
    response_file.write_text(json.dumps(candidate), encoding="utf-8")

    artifact, report = discover_controlled(
        goal="Open a sub-account for member 12345",
        output_path=output_file,
        provider="file",
        llm_response_file=response_file,
        report_path=report_file,
    )

    saved = CapabilityArtifact.load(output_file)
    assert artifact.name == "llm_candidate_open_subaccount"
    assert saved.approval_state == "draft"
    assert saved.metadata.app == "mock-bank"
    assert report.status == "accepted"
    assert report_file.exists()


def test_controlled_file_discovery_rejects_external_navigation(tmp_path):
    candidate = json.loads(Path("artifacts/examples/open_subaccount_happy_path.json").read_text())
    candidate["steps"][0] = {"id": "leave_app", "action": "goto", "url": "https://example.com/login"}
    response_file = tmp_path / "malicious-response.json"
    output_file = tmp_path / "draft.json"
    report_file = tmp_path / "report.json"
    response_file.write_text(json.dumps(candidate), encoding="utf-8")

    with pytest.raises(DiscoveryValidationError):
        discover_controlled(
            goal="Open a sub-account for member 12345",
            output_path=output_file,
            provider="file",
            llm_response_file=response_file,
            report_path=report_file,
        )

    assert not output_file.exists()
    report = json.loads(report_file.read_text())
    assert report["status"] == "rejected"
    assert "not in allowed_hosts" in report["errors"][0]


def test_controlled_file_discovery_rejects_unknown_placeholder(tmp_path):
    candidate = json.loads(Path("artifacts/examples/open_subaccount_happy_path.json").read_text())
    candidate["steps"][4]["value"] = "{{unknown_member}}"
    response_file = tmp_path / "bad-placeholder.json"
    output_file = tmp_path / "draft.json"
    response_file.write_text(json.dumps(candidate), encoding="utf-8")

    with pytest.raises(DiscoveryValidationError):
        discover_controlled(
            goal="Open a sub-account for member 12345",
            output_path=output_file,
            provider="file",
            llm_response_file=response_file,
        )
