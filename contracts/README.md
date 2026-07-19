# Contracts

No production code lives here. This module exists to keep `contracts/events/` honest: `EventSchemaValidationTest` validates every fixture under `contracts/events/examples/` against the schema its filename names, and confirms every schema has a matching example. If a schema and its example ever drift apart, `./mvnw verify` fails — not a manual review.

## What "automated compatibility test" means today, honestly

This phase (Phase 3) is the first time these schemas exist, so there is no prior version to diff a new one against yet — a real backward-compatibility check (does `v2` still accept everything `v1` accepted, where it's supposed to) isn't meaningfully testable until a `v2` schema exists to check. What's real and automated today:

- Every example fixture validates against its own schema.
- Every schema has an example (`everySchemaFileHasAMatchingExample`), so a schema can't quietly go undocumented.

What's a documented convention, not yet a test: the versioning rule in `contracts/events/README.md` (a schema file is immutable once published; breaking changes get a new `vN` file). The first time a second version of some event is introduced, this module should grow a test that loads both versions and asserts the new one is a superset of the old one's acceptance — that test can't be written honestly before there's a second version to write it against.
