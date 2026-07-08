## Context

Feature 2 already builds a runnable image via `sbt-native-packager` (`Docker/publishLocal`, non-root, version-tagged, `+`→`-` sanitized). Feature 1's `release.yml` publishes the package and creates a GitHub Release on `vX.Y.Z` tags, and `ci.yml` publishes a snapshot package on `development`. Feature 5 extends this to push the **image** to Docker Hub, reusing the existing packager settings. No application code changes.

## Goals / Non-Goals

**Goals:**
- Publish `docker.io/vezril/hermesmq:X.Y.Z` and `:latest` on release tags.
- Publish a snapshot-tagged image on `development` pushes (no `latest`).
- Authenticate via `DOCKER_USERNAME` / `DOCKER_TOKEN` secrets; fail loudly if absent.
- Keep publishing gated behind a green build; never push on pull requests.
- Document secret setup and `docker pull`/run.

**Non-Goals:**
- Multi-arch (`linux/arm64`) images — single `linux/amd64` for now.
- Signing/SBOM/provenance attestation (later).
- Changing image contents or the base image.
- Publishing to registries other than Docker Hub.

## Decisions

**Publish mechanism: `sbt-native-packager` `Docker/publish`.**
The image is already defined by the packager; `Docker/publish` builds and pushes to the configured repository. Reuses Feature 2's settings (base image, non-root, exposed port, sanitized tag) with zero divergence. *Alternative:* `docker/build-push-action` (buildx) in the workflow — more features (multi-arch, cache) but would duplicate the Dockerfile the packager already generates; revisit when multi-arch is needed.

**Repository/target: `dockerRepository := Some("docker.io")`, `dockerUsername := Some(sys.env("DOCKER_USERNAME") getOrElse "vezril")`.**
Produces `docker.io/vezril/hermesmq`. Username resolved from env in CI so it follows the secret, defaulting to `vezril` locally. *Alternative:* hard-code — less flexible.

**Tagging:**
- Release (`vX.Y.Z`): push `X.Y.Z` and `latest`. `dockerUpdateLatest := true` moves `latest` only on releases.
- Development: push the sanitized dynver snapshot tag; **do not** move `latest`. Achieved by driving `dockerUpdateLatest` from context (only true on release) — set via an env/system property the workflow passes, or a second sbt invocation. Simplest: `dockerUpdateLatest := sys.env.get("DOCKER_UPDATE_LATEST").contains("true")`, with the release workflow setting it and the dev workflow not.

**Auth: `docker login` via secrets before `sbt Docker/publish`.**
The workflow runs `docker login -u $DOCKER_USERNAME -p $DOCKER_TOKEN` (or the `docker/login-action`) so the local daemon is authenticated; `Docker/publish` then pushes. Missing/invalid secrets fail `docker login` (or the push) with an auth error — loud, not silent. *Alternative:* embed credentials in sbt `credentials` — awkward for Docker; `docker login` is the idiomatic path.

**Ordering in `release.yml`: tests → package publish → image publish → GitHub Release.**
Image publish sits after tests (gate) and package publish, before the Release is created, so a Release is never created without its image. A failing image push fails the job before the Release step.

**Development image publish in `ci.yml`.**
Add an image push step conditioned on `github.event_name == 'push' && github.ref == 'refs/heads/development'` (mirrors the existing snapshot-package step), without `latest`. Pull requests never reach it.

## Risks / Trade-offs

- **Docker Hub credentials required** → the repo needs `DOCKER_USERNAME` + `DOCKER_TOKEN` secrets; documented. Without them the publish fails loudly (intended, and a tested scenario).
- **Docker Hub rate limits / immutability** → re-pushing an existing `X.Y.Z` tag: Docker Hub allows overwriting tags, but our release tags come from immutable git tags, so a given `X.Y.Z` is built once; `latest` is intentionally mutable.
- **`latest` moved by a bad release** → only release tags set `dockerUpdateLatest=true`; dev pushes never move `latest`.
- **CI time / daemon availability** → GitHub-hosted runners have Docker; building the image adds minutes to release/dev runs. Acceptable; cache later if needed.
- **Cannot fully verify without Docker Hub secrets** → locally we can verify the image builds and is tagged/named for Docker Hub (`Docker/publishLocal`, inspect the repo/tag); the actual push is verified once the user adds the secrets and a release runs.

## Migration Plan

1. Add `dockerRepository`/`dockerUsername`/context-driven `dockerUpdateLatest` to `build.sbt`; verify `Docker/publishLocal` still tags `vezril/hermesmq:<version>` and (on release context) `latest`.
2. Add the `development` image-push step to `ci.yml` (login + publish, no `latest`).
3. Add the image-publish step to `release.yml` (login + publish with `latest`), positioned after package publish and before the GitHub Release.
4. Document required secrets and `docker pull`/run.
5. User adds `DOCKER_USERNAME`/`DOCKER_TOKEN` secrets and creates the `vezril/hermesmq` Docker Hub repo; a release verifies the end-to-end push.

Rollback: workflow/build additions only; reverting removes the publish steps. Any pushed image tags can be deleted from Docker Hub.

## Open Questions

- Confirm the Docker Hub namespace is `vezril` and the access-token secret name (`DOCKER_TOKEN`).
- Should `development` snapshot images be published on every push, or only tagged milestones? (Assumption: every `development` push, matching the snapshot-package behavior.)
- Multi-arch: defer `linux/arm64` to a later change? (Assumption: yes, amd64-only now.)
