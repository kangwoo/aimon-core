# Security Policy

## Supported Versions

AIMON Core is pre-1.0. Security fixes land on the **latest released version only** —
there are no maintenance branches for older `0.x` releases.

| Version | Supported |
|---------|-----------|
| Latest `0.x` release | ✅ |
| Any earlier release | ❌ — upgrade to the latest |

Releases are published to [Maven Central](https://central.sonatype.com/artifact/at.aimon.core/aimon-core).
See [CHANGELOG.md](CHANGELOG.md) for what changed.

## Reporting a Vulnerability

**Please do not open a public issue for a security vulnerability.**

Use one of these private channels:

1. **GitHub Security Advisory** (preferred) — open a private report at
   [Security → Report a vulnerability](https://github.com/kangwoo/aimon-core/security/advisories/new).
   This keeps the discussion private until a fix is released.
2. **Email** — `kangwoo@gmail.com` with `[SECURITY]` in the subject line.

Please include, as far as you can:

- The affected module and version (`aimon-core 0.2.2`, `aimon-sandbox-docker`, …)
- What an attacker gains — the impact, not just the mechanism
- Reproduction steps or a minimal proof of concept
- Any configuration required to reach the vulnerable path (which tools are registered,
  whether a sandbox is in use, which LLM provider)

### What to expect

This project has a [single maintainer](MAINTAINERS.md), so please read these as good-faith
targets rather than a contractual SLA.

| Stage | Target |
|-------|--------|
| Acknowledgement that the report was received | 5 business days |
| Initial assessment (accepted / needs info / not a vulnerability) | 10 business days |
| Fix released for an accepted report | Depends on severity — coordinated with you |

We will credit you in the advisory and the changelog unless you ask us not to.
Please give us a reasonable window to ship a fix before disclosing publicly.

## Scope

AIMON is a framework for building agents that execute tools — including shell
commands, filesystem writes, and network calls — on behalf of an LLM. That makes
the boundary between "working as designed" and "vulnerability" worth stating
explicitly.

### In scope

- **Permission bypass** — a tool invocation that the configured allow-list
  (`AllowedTool` patterns, `PermissionSubject`, `CustomToolPermissionRule`) should have
  rejected but did not. Path-pattern escapes (`..` traversal, normalization gaps) and
  command-pattern escapes (shell metacharacter injection) belong here
- **Sandbox escape** — code running under `aimon-sandbox-docker` or
  `aimon-sandbox-kubernetes` reaching the host or another tenant
- **Credential leakage** — secrets from `CredentialStore`, environment, or provider
  configuration appearing in traces, logs, transcripts, or LLM requests where the
  redaction layer was expected to remove them
- **Session isolation failure** — one `SessionId` reading or writing another session's
  record, transcript, inbox, or approvals; approval scope (`SessionApprovalStore` /
  `AgentApprovalStore`) reaching further than documented
- **Injection into framework-controlled surfaces** — skill/hook loading, MCP server
  configuration, or scheduled-task definitions that execute attacker-controlled input
  outside the intended permission checks
- **Denial of service** in the framework itself — an unbounded loop or allocation
  reachable from ordinary agent input despite `ExecutionBudget` limits

### Out of scope

These are known properties of the system, not vulnerabilities:

- **Prompt injection that stays inside the permission boundary.** An LLM can be
  manipulated into calling any tool it has been granted. The mitigation is the
  permission system and the sandbox, not the model. A report is in scope only if the
  injection reaches something the configuration should have blocked
- **An agent doing damage with permissions it was granted.** Registering `BashTool`
  with no pattern restriction and no sandbox is a configuration choice; the framework
  documents it as such
- **LLM output quality** — hallucination, refusal, incorrect reasoning
- **Vulnerabilities in third-party dependencies** — report those upstream. Do tell us
  if AIMON's usage makes an upstream issue exploitable when it otherwise would not be
- **Findings that require the attacker to already control the host, the JVM, or the
  agent's configuration files**

If you are unsure which side of the line a finding falls on, report it privately and
we will work it out together.

## Hardening Guidance

If you are deploying AIMON with untrusted input reaching the agent:

- Run tool execution inside a sandbox (`aimon-sandbox-docker` / `aimon-sandbox-kubernetes`)
  rather than the local shell
- Constrain every tool with an explicit pattern — note that registering both `"Read"` and
  `"Read(/tmp/**)"` is *not* unrestricted access, and that a tool with a configured
  pattern but no resolvable subject is **denied**
  (see [tool-development-guide.md](docs/features/tool/tool-development-guide.md))
- Set `SchemaValidationMode` to `ENFORCE` for tools you own — the default is `WARN`,
  which logs the violation and runs the tool anyway
- Review what your tracing exporter receives — see
  [execution-tracing-guide.md](docs/features/observability/execution-tracing-guide.md)
