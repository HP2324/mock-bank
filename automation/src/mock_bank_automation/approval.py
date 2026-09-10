from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from pydantic import BaseModel, Field

from .artifact import CapabilityArtifact


class ApprovalError(ValueError):
    pass


class ApprovalReport(BaseModel):
    artifact_name: str
    source_path: str
    output_path: str
    status: str
    approved_by: str
    reason: str
    checks: list[str] = Field(default_factory=list)
    errors: list[str] = Field(default_factory=list)
    created_at: str = Field(default_factory=lambda: datetime.now().isoformat(timespec="seconds"))


def approve_artifact(
    source_path: Path,
    output_path: Path,
    report_path: Path,
    approved_by: str,
    reason: str,
) -> tuple[CapabilityArtifact, ApprovalReport]:
    report = ApprovalReport(
        artifact_name="",
        source_path=str(source_path),
        output_path=str(output_path),
        status="rejected",
        approved_by=approved_by,
        reason=reason,
    )
    try:
        if not approved_by.strip():
            raise ApprovalError("approved_by is required.")
        if not reason.strip():
            raise ApprovalError("reason is required.")

        artifact = CapabilityArtifact.load(source_path)
        report.artifact_name = artifact.name
        report.checks.append("source artifact passed production contract validation")

        approved_data = artifact.model_dump(mode="json")
        approved_data["approval_state"] = "approved"
        previous_notes = approved_data["metadata"].get("notes", "")
        approval_note = f"Approved by {approved_by}: {reason}"
        approved_data["metadata"]["notes"] = f"{previous_notes}\n{approval_note}".strip()
        approved_artifact = CapabilityArtifact.model_validate(approved_data)
        report.checks.append("approved artifact passed production contract validation")

        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(approved_artifact.model_dump_json(indent=2), encoding="utf-8")
        report.status = "approved"
        report.checks.append(f"wrote approved artifact to {output_path}")
        return approved_artifact, report
    except Exception as exc:
        report.errors.append(str(exc))
        raise
    finally:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report.model_dump_json(indent=2), encoding="utf-8")


def report_to_json(report: ApprovalReport) -> str:
    return json.dumps(report.model_dump(), indent=2)
