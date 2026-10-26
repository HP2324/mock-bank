"""Replay the saved artifacts against a running mock bank and check each ends in its expected status.

Used by CI against the Docker Compose stack, and handy locally after UI changes:

    python scripts/replay_smoke.py --base-url http://localhost:8080
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

from mock_bank_automation.artifact import CapabilityArtifact
from mock_bank_automation.replay import replay_artifact

ROOT = Path(__file__).resolve().parents[1]

# (artifact, expected status, outcome name or None)
CASES = [
    ("artifacts/approved/approved_open_subaccount.json", "success", None),
    ("artifacts/approved/approved_claude_open_subaccount.json", "success", None),
    ("artifacts/examples/open_subaccount_happy_path.json", "success", None),
    ("artifacts/examples/member_not_found.json", "business_outcome", "member_not_found"),
    ("artifacts/examples/invalid_member_id.json", "business_outcome", "invalid_member_id"),
    ("artifacts/examples/permission_denied.json", "business_outcome", "permission_denied"),
    ("artifacts/examples/human_approval_required.json", "handoff_required", "human_approval_required"),
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--evidence-dir", type=Path, default=None, help="Defaults to a temporary directory.")
    args = parser.parse_args()
    evidence_dir = args.evidence_dir or Path(tempfile.mkdtemp(prefix="replay-smoke-"))

    failures = 0
    for relative_path, expected_status, expected_outcome in CASES:
        artifact = CapabilityArtifact.load(ROOT / relative_path)
        result = replay_artifact(artifact, base_url=args.base_url, evidence_dir=evidence_dir)
        outcome = result.outcome["name"] if result.outcome else None
        passed = result.status == expected_status and outcome == expected_outcome
        if expected_status == "success":
            passed = passed and result.checkpoint == "CONFIRM-SUBACCOUNT-CREATED" and bool(result.outputs.get("new_account_number"))
        failures += 0 if passed else 1
        detail = result.outputs.get("new_account_number") or outcome or (result.failure or {}).get("message", "")
        print(f"{'PASS' if passed else 'FAIL'}  {relative_path}: {result.status} {detail}")

    print(f"\n{len(CASES) - failures}/{len(CASES)} artifacts replayed as expected. Evidence: {evidence_dir}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
