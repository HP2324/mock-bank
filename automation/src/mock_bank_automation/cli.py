from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .approval import approve_artifact, report_to_json
from .browser_discovery import discover_with_browser
from .artifact import CapabilityArtifact
from .discovery import create_draft_artifact, discover_controlled
from .replay import replay_artifact, result_to_json


def main() -> None:
    parser = argparse.ArgumentParser(description="Mock bank automation runner")
    subparsers = parser.add_subparsers(dest="command", required=True)

    replay_parser = subparsers.add_parser("replay", help="Replay a saved capability artifact")
    replay_parser.add_argument("artifact", type=Path)
    replay_parser.add_argument("--base-url", default="http://localhost:8080")
    replay_parser.add_argument("--evidence-dir", type=Path, default=Path("evidence"))
    replay_parser.add_argument("--headed", action="store_true", help="Show the browser while replaying")
    replay_parser.add_argument("--timeout-ms", type=int, default=10_000)
    replay_parser.add_argument("--require-approved", action="store_true", help="Reject draft artifacts before replaying")
    handoff_group = replay_parser.add_mutually_exclusive_group()
    handoff_group.add_argument("--auto-resume-handoff", action="store_true", help="Continue when the configured handoff resume selector appears")
    handoff_group.add_argument("--pause-on-handoff", action="store_true", help="Pause in the terminal so a human can act in the headed browser")

    discover_parser = subparsers.add_parser("discover", help="Create a validated draft artifact from a goal through the controlled discovery seam")
    discover_parser.add_argument("goal")
    discover_parser.add_argument("--output", type=Path, default=Path("artifacts/drafts/draft_from_goal.json"))
    discover_parser.add_argument("--base-url", default="http://localhost:8080")
    discover_parser.add_argument("--provider", choices=["local", "file", "openai"], default="local")
    discover_parser.add_argument("--llm-response-file", type=Path, help="Read a proposed artifact JSON response from a file")
    discover_parser.add_argument("--report", type=Path, default=Path("evidence/discovery-report.json"))
    discover_parser.add_argument("--model", default="gpt-5")

    browser_parser = subparsers.add_parser("discover-browser", help="Drive the live app once, record a trace, and write a draft artifact")
    browser_parser.add_argument("goal")
    browser_parser.add_argument("--output", type=Path, default=Path("artifacts/drafts/draft_from_browser_discovery.json"))
    browser_parser.add_argument("--base-url", default="http://localhost:8080")
    browser_parser.add_argument("--evidence-dir", type=Path, default=Path("evidence"))
    browser_parser.add_argument("--provider", choices=["scripted", "openai", "anthropic"], default="scripted")
    browser_parser.add_argument("--model", default="gpt-5")
    browser_parser.add_argument("--headed", action="store_true", help="Show the browser while discovering")
    browser_parser.add_argument("--max-steps", type=int, default=20)
    browser_parser.add_argument("--timeout-ms", type=int, default=10_000)

    approve_parser = subparsers.add_parser("approve", help="Validate a draft artifact and write an approved copy")
    approve_parser.add_argument("artifact", type=Path)
    approve_parser.add_argument("--output", type=Path, required=True)
    approve_parser.add_argument("--report", type=Path, default=Path("evidence/approval-report.json"))
    approve_parser.add_argument("--approved-by", required=True)
    approve_parser.add_argument("--reason", required=True)

    args = parser.parse_args()

    if args.command == "replay":
        artifact = CapabilityArtifact.load(args.artifact)
        result = replay_artifact(
            artifact=artifact,
            base_url=args.base_url,
            evidence_dir=args.evidence_dir,
            headless=not args.headed,
            timeout_ms=args.timeout_ms,
            auto_resume_handoff=args.auto_resume_handoff,
            pause_on_handoff=args.pause_on_handoff,
            handoff_callback=_terminal_handoff_callback if args.pause_on_handoff else None,
            require_approved=args.require_approved,
        )
        print(result_to_json(result))
    elif args.command == "discover":
        artifact, report = discover_controlled(
            goal=args.goal,
            output_path=args.output,
            base_url=args.base_url,
            provider=args.provider,
            llm_response_file=args.llm_response_file,
            report_path=args.report,
            model=args.model,
        )
        print(artifact.model_dump_json(indent=2))
        print(f"Discovery report: {args.report}", file=sys.stderr)
    elif args.command == "discover-browser":
        artifact, report = discover_with_browser(
            goal=args.goal,
            output_path=args.output,
            base_url=args.base_url,
            evidence_dir=args.evidence_dir,
            provider_name=args.provider,
            model=args.model,
            headless=not args.headed,
            max_steps=args.max_steps,
            timeout_ms=args.timeout_ms,
        )
        print(artifact.model_dump_json(indent=2))
        print(f"Browser discovery run: {report.run_dir}", file=sys.stderr)
    elif args.command == "approve":
        artifact, report = approve_artifact(
            source_path=args.artifact,
            output_path=args.output,
            report_path=args.report,
            approved_by=args.approved_by,
            reason=args.reason,
        )
        print(artifact.model_dump_json(indent=2))
        print(f"Approval report: {args.report}", file=sys.stderr)


def _terminal_handoff_callback(artifact: CapabilityArtifact, result, current_url: str) -> None:
    sys.stderr.write("\nHANDOFF REQUIRED\n")
    sys.stderr.write(f"Artifact: {artifact.name}\n")
    sys.stderr.write(f"Reason: {artifact.handoff.reason}\n")
    if result.outcome:
        sys.stderr.write(f"Outcome: {result.outcome.get('message', '')}\n")
    if artifact.handoff.operator_instructions:
        sys.stderr.write(f"Instructions: {artifact.handoff.operator_instructions}\n")
    sys.stderr.write(f"Current browser URL: {current_url}\n")
    sys.stderr.write("Complete the manual action in Chromium, then press Enter here to continue verification...\n")
    sys.stderr.flush()
    input()


if __name__ == "__main__":
    main()
