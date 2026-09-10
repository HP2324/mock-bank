from __future__ import annotations

import re
from pathlib import Path
from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ContractValidationError(ValueError):
    pass


_NAME_PATTERN = re.compile(r"^[a-z][a-z0-9_]*$")
_VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+$")
_PLACEHOLDER_PATTERN = re.compile(r"{{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*}}")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ArtifactMetadata(StrictModel):
    owner: str = "mock-bank-automation"
    app: str = "mock-bank"
    app_version: str = "local-dev"
    notes: str = ""


class ArtifactField(StrictModel):
    type: Literal["string", "number", "boolean"] = "string"
    description: str = ""
    required: bool = True
    sensitive: bool = False


class LocatorSpec(StrictModel):
    selector: str
    strategy: Literal["css", "text", "xpath"] = "css"
    robustness_note: str = ""


class SafetyPolicy(StrictModel):
    allowed_actions: list[Literal["goto", "fill", "click", "wait_for_selector"]] = Field(
        default_factory=lambda: ["goto", "fill", "click", "wait_for_selector"]
    )
    allowed_hosts: list[str] = Field(default_factory=lambda: ["localhost", "localhost:8080", "127.0.0.1", "127.0.0.1:8080"])
    allowed_exact_paths: list[str] = Field(default_factory=lambda: ["/"])
    allowed_path_prefixes: list[str] = Field(default_factory=lambda: ["/login", "/members", "/logout", "/debug", "/ops", "/h2-console"])
    risky_action_policy: Literal["allow", "block", "require_handoff"] = "allow"
    risky_selectors: list[str] = Field(default_factory=lambda: ["button:has-text('Open sub-account')"])
    redact_input_keys: list[str] = Field(default_factory=lambda: ["password", "token", "secret"])


class ReplayStep(StrictModel):
    id: str
    action: Literal["goto", "fill", "click", "wait_for_selector"]
    selector: str | None = None
    url: str | None = None
    value: str | None = None
    locator: LocatorSpec | None = None
    description: str = ""


class Checkpoint(StrictModel):
    selector: str
    text: str


class OutcomeRule(StrictModel):
    name: str
    status: Literal["business_outcome", "handoff_required", "recoverable", "failure"]
    selector: str
    contains_text: str | None = None
    message: str


class HandoffPolicy(StrictModel):
    enabled: bool = False
    reason: str = "Human intervention required."
    resume_selector: str | None = None
    operator_instructions: str = "Review the live browser state, take the required manual action, then resume automation."


class CapabilityArtifact(StrictModel):
    schema_version: str
    name: str
    description: str
    version: str = "0.1.0"
    approval_state: Literal["draft", "approved"] = "draft"
    metadata: ArtifactMetadata = Field(default_factory=ArtifactMetadata)
    input_schema: dict[str, ArtifactField] = Field(default_factory=dict)
    output_schema: dict[str, ArtifactField] = Field(default_factory=dict)
    inputs: dict[str, str] = Field(default_factory=dict)
    steps: list[ReplayStep]
    checkpoint: Checkpoint | None = None
    expected_outcomes: list[OutcomeRule] = Field(default_factory=list)
    handoff: HandoffPolicy = Field(default_factory=HandoffPolicy)
    safety: SafetyPolicy = Field(default_factory=SafetyPolicy)
    outputs: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_contract(self) -> Self:
        errors: list[str] = []

        _require(self.schema_version == "0.1", "schema_version must be 0.1", errors)
        _require(bool(_NAME_PATTERN.fullmatch(self.name)), "name must be snake_case and start with a letter", errors)
        _require(bool(_VERSION_PATTERN.fullmatch(self.version)), "version must use MAJOR.MINOR.PATCH", errors)
        _require(bool(self.description.strip()), "description is required", errors)
        _require(bool(self.metadata.owner.strip()), "metadata.owner is required", errors)
        _require(self.metadata.app == "mock-bank", "metadata.app must be mock-bank", errors)
        _require(bool(self.metadata.app_version.strip()), "metadata.app_version is required", errors)

        _require(bool(self.inputs), "inputs must not be empty", errors)
        _require(bool(self.input_schema), "input_schema must not be empty", errors)
        _require(set(self.input_schema) == set(self.inputs), "input_schema keys must match inputs keys", errors)
        for key, field in self.input_schema.items():
            _require(bool(field.description.strip()), f"input_schema.{key}.description is required", errors)
            if field.required:
                _require(key in self.inputs and self.inputs[key] != "", f"required input {key} must have a value", errors)
            if "password" in key.lower():
                _require(field.sensitive, f"password-like input {key} must be marked sensitive", errors)

        _require(set(self.output_schema) == set(self.outputs), "output_schema keys must match outputs keys", errors)
        for key, field in self.output_schema.items():
            _require(bool(field.description.strip()), f"output_schema.{key}.description is required", errors)

        _require(1 <= len(self.steps) <= 30, "steps must contain between 1 and 30 entries", errors)
        step_ids = [step.id for step in self.steps]
        _require(len(step_ids) == len(set(step_ids)), "step ids must be unique", errors)
        for step in self.steps:
            _validate_step(step, self.inputs, errors)

        _require(self.checkpoint is not None or bool(self.expected_outcomes), "artifact must define checkpoint or expected_outcomes", errors)
        if self.checkpoint:
            _require(bool(self.checkpoint.selector.strip()), "checkpoint.selector is required", errors)
            _require(bool(self.checkpoint.text.strip()), "checkpoint.text is required", errors)
        for outcome in self.expected_outcomes:
            _require(bool(_NAME_PATTERN.fullmatch(outcome.name)), f"outcome {outcome.name!r} must use snake_case", errors)
            _require(bool(outcome.selector.strip()), f"outcome {outcome.name}.selector is required", errors)
            _require(bool(outcome.message.strip()), f"outcome {outcome.name}.message is required", errors)

        handoff_outcomes = [rule for rule in self.expected_outcomes if rule.status == "handoff_required"]
        if self.handoff.enabled or handoff_outcomes:
            _require(self.handoff.enabled, "handoff_required outcomes require handoff.enabled", errors)
            _require(bool(handoff_outcomes), "handoff.enabled requires a handoff_required expected outcome", errors)
            _require(bool((self.handoff.resume_selector or "").strip()), "handoff.resume_selector is required", errors)
            _require(bool(self.handoff.reason.strip()), "handoff.reason is required", errors)
            _require(bool(self.handoff.operator_instructions.strip()), "handoff.operator_instructions is required", errors)

        _require(bool(self.safety.allowed_actions), "safety.allowed_actions must not be empty", errors)
        _require(bool(self.safety.allowed_hosts), "safety.allowed_hosts must not be empty", errors)
        _require(bool(self.safety.allowed_exact_paths or self.safety.allowed_path_prefixes), "safety must allow at least one path", errors)
        if self.safety.risky_action_policy != "allow":
            _require(bool(self.safety.risky_selectors), "non-allow risky_action_policy requires risky_selectors", errors)
        sensitive_schema_keys = {key for key, field in self.input_schema.items() if field.sensitive}
        redact_keys = {key.lower() for key in self.safety.redact_input_keys}
        for key in sensitive_schema_keys:
            _require(key.lower() in redact_keys, f"sensitive input {key} must be included in safety.redact_input_keys", errors)

        if errors:
            raise ContractValidationError("; ".join(errors))
        return self

    @classmethod
    def load(cls, path: Path) -> "CapabilityArtifact":
        return cls.model_validate_json(path.read_text(encoding="utf-8"))

    def sensitive_input_keys(self) -> list[str]:
        schema_keys = [key for key, field in self.input_schema.items() if field.sensitive]
        return sorted(set(self.safety.redact_input_keys + schema_keys))


def _validate_step(step: ReplayStep, inputs: dict[str, str], errors: list[str]) -> None:
    _require(bool(_NAME_PATTERN.fullmatch(step.id)), f"step id {step.id!r} must use snake_case", errors)
    if step.action == "goto":
        _require(bool((step.url or "").strip()), f"goto step {step.id} requires url", errors)
        _require(step.selector is None, f"goto step {step.id} must not define selector", errors)
    elif step.action == "fill":
        _require(bool((step.selector or "").strip()), f"fill step {step.id} requires selector", errors)
        _require(step.value is not None, f"fill step {step.id} requires value", errors)
    elif step.action in {"click", "wait_for_selector"}:
        _require(bool((step.selector or "").strip()), f"{step.action} step {step.id} requires selector", errors)
        _require(step.value is None, f"{step.action} step {step.id} must not define value", errors)

    for source, value in {"url": step.url, "value": step.value}.items():
        if value is None:
            continue
        for placeholder in _PLACEHOLDER_PATTERN.findall(value):
            _require(placeholder in inputs, f"step {step.id} {source} references unknown input {placeholder!r}", errors)


def _require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)
