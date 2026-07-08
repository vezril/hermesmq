## Why

Feature 2 produces a runnable Docker image locally, but there is no way for anyone to *pull* HermesMQ — the image never leaves the build machine. To make releases consumable (and to enable deployment examples in the Feature-6 README), the release pipeline must build and publish the image to a public registry. Docker Hub is the requested target.

## What Changes

- Configure the image to publish to **Docker Hub** under the `vezril` namespace (`docker.io/vezril/hermesmq`), using `sbt-native-packager`'s `Docker/publish`.
- Extend the **release workflow**: on a `vX.Y.Z` tag, after the package is published, build and push the image tagged `vX.Y.Z` → `X.Y.Z` **and** `latest` to Docker Hub, authenticating with `DOCKER_USERNAME` / `DOCKER_TOKEN` GitHub secrets.
- Publish a Docker Hub image on **`development`** pushes too: a snapshot-tagged image (dynver version, `+`→`-` sanitized) so experimental builds are pullable, but **not** tagged `latest`.
- Fail the publish loudly if credentials are missing/invalid; never leave a GitHub Release without its image, and never overwrite an existing immutable released tag silently.
- Document how to configure the Docker Hub secrets and how to pull/run the published image (a deployment example lands more fully in Feature 6).

Scope note: single-platform (`linux/amd64`) image for now; multi-arch builds are a later enhancement. This feature is CI/publishing only — the image contents are unchanged from Feature 2.

## Capabilities

### New Capabilities
- `image-publishing`: Publishing the Docker image to Docker Hub — the versioned + `latest` tagging scheme, credential handling, and the release/development publish triggers.

### Modified Capabilities
- `ci-cd-pipeline`: The release workflow gains a Docker Hub build-and-push step (gated after tests/package publish), and the CI workflow pushes a snapshot image on `development`. New required secrets (`DOCKER_USERNAME`, `DOCKER_TOKEN`).

## Impact

- **Workflows**: `release.yml` gains docker login + `sbt Docker/publish` (or buildx push) for `X.Y.Z` and `latest`; `ci.yml` gains a `development`-only image push.
- **build.sbt**: set `dockerRepository := Some("docker.io")` / `dockerUsername := Some("vezril")` so `Docker/publish` targets Docker Hub; keep the version-sanitized tag and `dockerUpdateLatest` behavior scoped to releases.
- **Secrets/infra**: GitHub repo secrets `DOCKER_USERNAME` and `DOCKER_TOKEN` (a Docker Hub access token), and a `vezril/hermesmq` Docker Hub repository.
- **Consumers**: after a release, `docker pull vezril/hermesmq:X.Y.Z` (and `:latest`) works; after a `development` push, the snapshot tag is pullable.
- **No application code change**: image contents identical to Feature 2.
