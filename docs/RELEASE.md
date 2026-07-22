# Release and versioning

## Versioning scheme

FulfillOps uses [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- The backend version lives in the root `pom.xml` (`<version>`), inherited by all four
  service modules — they always release together as one platform version.
- The console version lives in `apps/ops-console/package.json`.

While the project is pre-1.0, the version stays `0.x` and the API is allowed to change
between minor versions.

## Cutting a release

Releases are driven entirely by a git tag a human pushes. Nothing is released
automatically from a branch.

1. Make sure `main` is green and the working tree is clean.
2. Set the release version (drop `-SNAPSHOT`) in the root `pom.xml`, commit.
3. Tag it: `git tag v0.2.0 && git push origin v0.2.0`.
4. `.github/workflows/release.yml` runs on the tag: it runs a full `verify` (so a tag is
   never released without its tests and coverage gate passing), builds the four service
   jars, and attaches them to a GitHub Release with generated notes.
5. Bump the version to the next `-SNAPSHOT` on `main` and commit.

## Container image tags

Images are built by CI but **never pushed to a registry** by any automated workflow (see
below). The tags used are deliberately predictable, never `latest`:

| Context | Tag | Set by |
| --- | --- | --- |
| CI build/scan/SBOM | `fulfillops/<service>:ci` | `.github/workflows/ci.yml` |
| Local Compose demo / kind | `fulfillops/<service>:local` | `docker-compose.apps.yml`, `kind-deploy.sh` |
| Local ad-hoc build | `fulfillops/<service>` | `make docker-build` |

Each image also carries OCI labels (`org.opencontainers.image.version`, `.revision`,
`.created`, `.title`, `.licenses`, ...). CI passes the real git SHA and build date as
build arguments; a bare local build uses the defaults baked into the Dockerfile.

### Why images are not pushed automatically

This project does not own a container registry and does not publish images as part of its
build — consistent with the rule that it never provisions external resources or incurs cost
on its own. To publish manually to a registry you control:

```
docker build -f services/order-service/Dockerfile \
  -t <registry>/fulfillops/order-service:v0.2.0 \
  --build-arg SERVICE_VERSION=0.2.0 \
  --build-arg VCS_REF="$(git rev-parse HEAD)" \
  --build-arg BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" .
docker push <registry>/fulfillops/order-service:v0.2.0
```

## Refreshing the base image digest

The service Dockerfiles pin `eclipse-temurin` by immutable digest, not just tag. When
bumping the JDK, refresh both the JDK (build stage) and JRE (runtime stage) digests:

```
docker buildx imagetools inspect eclipse-temurin:21-jdk-noble --format '{{.Manifest.Digest}}'
docker buildx imagetools inspect eclipse-temurin:21-jre-noble --format '{{.Manifest.Digest}}'
```

Update the `@sha256:...` in every service Dockerfile. Dependabot (`.github/dependabot.yml`)
also proposes base-image updates on a schedule.

## Pinning GitHub Actions

Third-party actions in the workflows are pinned to released version tags and kept current
by Dependabot's `github-actions` updates. Pinning each to an immutable commit SHA is the
stronger supply-chain posture; it is the recommended next hardening step and can be applied
across the workflows in one pass (Dependabot understands SHA pins and keeps them updated).

## One-command local verification

Before tagging, run everything this machine can check:

```
make verify-all      # or: ./scripts/verify-all.sh
```

It runs the backend build, the frontend build, Kustomize builds, Terraform fmt/validate,
and Compose config validation — skipping (not failing) any whose tooling is not installed.
