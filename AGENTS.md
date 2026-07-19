# AGENTS.md

This file applies to every agent working in this repository — Claude Code, Cursor, or any other coding agent.

## Primary coding style: plain-readable-code

The primary coding style for every coding, editing, refactoring, debugging, testing, migration, scripting, configuration, and code-review task in this repository is **plain-readable-code**. It is mandatory, not optional, for every agent and every language used in this project.

The canonical copies of this style are:

- `.claude/skills/plain-readable-code/SKILL.md` — loaded by Claude Code as a skill before any coding or review task.
- `.cursor/rules/plain-readable-code.mdc` — applied automatically by Cursor (`alwaysApply: true`) before its final wrap-up pass and any other work.

Both files must stay byte-identical in substance. If you need to change the style, update both.

In short: write code the way a thoughtful new grad would — clear, simple, organized, using the basic features of the language before the advanced ones. Extract a function only when it earns its existence (reused, non-obvious, or shortens the caller). Prefer established libraries over hand-rolled auth, crypto, date, HTTP, retry, or parsing logic. See the canonical files above for the full rule set.

## Execution model

- **Claude Code** is the builder. It implements Phases 0–14: architecture, backend services, frontend, infrastructure, tests, documentation, and the final adversarial audit.
- **Cursor** is the final independent reviewer and finisher. It enters only after Claude Code has completed Phase 14 with a release-ready verdict and the working tree is clean. Cursor may simplify hard-to-read code, fix small confirmed defects, polish UI/documentation, and repair broken integration evidence. It must not redesign service boundaries, replace the stack, or add new product features.

## Project-specific rules

FulfillOps-specific engineering rules (service boundaries, data ownership, event contracts, money/quantity types, idempotency, error format, and more) live in `.cursor/rules/fulfillops.mdc` and are restated in `CLAUDE.md`. Read them before touching backend or contract code.

## Non-negotiable process rules

- Do not commit, push, rewrite Git history, provision cloud resources, or incur cost unless the user explicitly asks.
- Do not publish throughput, latency, coverage, test-count, or scale claims until a command run in this repository actually produced them.
- Keep the architecture and implementation history Claude-first: do not use Cursor to implement, fix, or continue an unfinished Claude Code phase.
