# Security Policy

FulfillOps is a portfolio project. It processes **no real money and no real personal data** —
every credential, user, and payment amount in this repository is fictional and local-only.

## Reporting a concern

If you find a security issue in the code (for example, a way a service could read another
service's data, an authorization gap, or a secret accidentally committed), please open a GitHub
issue describing it, or contact the maintainer directly. There is no bug-bounty program.

Because nothing here is deployed and no real data is handled, there is no production incident
process — the goal of a report is to correct the code and the documented model.

## Scope

- The detailed security model, role matrix, and threat-model summary live in
  [`docs/SECURITY.md`](docs/SECURITY.md).
- Known boundaries and out-of-scope items are in
  [`docs/KNOWN_LIMITATIONS.md`](docs/KNOWN_LIMITATIONS.md).

## Supported versions

The project is pre-1.0 and unreleased; only `main` is maintained.
