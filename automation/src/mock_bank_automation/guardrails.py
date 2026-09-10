from __future__ import annotations

from urllib.parse import urljoin, urlparse

from .artifact import CapabilityArtifact, ReplayStep, SafetyPolicy
from .replay_errors import ReplayFailure


class GuardrailViolation(ReplayFailure):
    pass


def enforce_step_policy(artifact: CapabilityArtifact, step: ReplayStep, inputs: dict[str, str], base_url: str, rendered_url: str | None = None) -> None:
    policy = artifact.safety

    if step.action not in policy.allowed_actions:
        raise GuardrailViolation(step.id, f"Action {step.action!r} is not allowed by artifact safety policy.")

    if step.action == "goto":
        if not rendered_url:
            raise GuardrailViolation(step.id, "goto step has no rendered URL to validate.")
        _enforce_route_policy(step.id, rendered_url, base_url, policy)

    selector = step.selector or (step.locator.selector if step.locator else None)
    if step.action == "click" and selector in policy.risky_selectors:
        if policy.risky_action_policy == "block":
            raise GuardrailViolation(step.id, f"Risky action selector {selector!r} is blocked by policy.")
        if policy.risky_action_policy == "require_handoff" and not artifact.handoff.enabled:
            raise GuardrailViolation(step.id, f"Risky action selector {selector!r} requires handoff policy.")


def _enforce_route_policy(step_id: str, rendered_url: str, base_url: str, policy: SafetyPolicy) -> None:
    target = urlparse(urljoin(base_url.rstrip("/") + "/", rendered_url.lstrip("/")))
    base = urlparse(base_url)
    host = target.hostname or ""
    port = str(target.port or (443 if target.scheme == "https" else 80))
    host_with_port = f"{host}:{port}"

    allowed_hosts = set(policy.allowed_hosts)
    if host not in allowed_hosts and host_with_port not in allowed_hosts:
        raise GuardrailViolation(step_id, f"Host {host_with_port!r} is not in allowed_hosts.")

    if target.scheme not in {"http", "https"}:
        raise GuardrailViolation(step_id, f"URL scheme {target.scheme!r} is not allowed.")

    if base.hostname and host != base.hostname:
        raise GuardrailViolation(step_id, "Artifact attempted to navigate away from the configured base host.")

    path = target.path or "/"
    if path in policy.allowed_exact_paths:
        return
    if not any(path.startswith(prefix.rstrip("/") + "/") or path == prefix for prefix in policy.allowed_path_prefixes):
        raise GuardrailViolation(step_id, f"Path {path!r} is not allowed by safety policy.")
