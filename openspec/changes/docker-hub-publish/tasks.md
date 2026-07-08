## 1. Docker Hub image settings

- [ ] 1.1 In `build.sbt`, set `dockerRepository := Some("docker.io")` and keep `dockerUsername` resolving from `DOCKER_USERNAME` (default `vezril`), so `Docker/publish` targets `docker.io/vezril/hermesmq`
- [ ] 1.2 Drive `dockerUpdateLatest` from context (e.g. `sys.env.get("DOCKER_UPDATE_LATEST").contains("true")`) so only releases move `latest`
- [ ] 1.3 Verify locally: `sbt Docker/publishLocal` produces `vezril/hermesmq:<version>` (and `latest` only when `DOCKER_UPDATE_LATEST=true`); inspect image name/tags

## 2. Release workflow — image publish

- [ ] 2.1 Add a Docker Hub login step to `release.yml` using `DOCKER_USERNAME` / `DOCKER_TOKEN` secrets
- [ ] 2.2 Add an image publish step (`DOCKER_UPDATE_LATEST=true sbt Docker/publish`) positioned after tests + package publish and before the GitHub Release step
- [ ] 2.3 Confirm YAML validity and step ordering (tests gate → package → image → release)

## 3. Development workflow — snapshot image publish

- [ ] 3.1 Add a Docker Hub login + image publish step to `ci.yml`, conditioned on push to `development` only (no `latest`), mirroring the snapshot-package step
- [ ] 3.2 Confirm pull-request builds do not reach the publish step (condition excludes PRs)
- [ ] 3.3 Confirm YAML validity

## 4. Documentation

- [ ] 4.1 Update `README.md`: required Docker Hub secrets (`DOCKER_USERNAME`, `DOCKER_TOKEN`), the published image coordinates, and `docker pull vezril/hermesmq:<version>` / run example
- [ ] 4.2 Note the tagging scheme (release → `X.Y.Z` + `latest`; development → snapshot tag, no `latest`) and the amd64-only scope

## 5. Verification

- [ ] 5.1 Local: image builds and is named `vezril/hermesmq` with the sanitized version tag; `latest` present only under release context
- [ ] 5.2 Static: both workflows are valid YAML and publishing is correctly gated (green build; not on PRs)
- [ ] 5.3 Run `openspec validate docker-hub-publish`
- [ ] 5.4 End-to-end (requires user to add Docker Hub secrets + repo): a `development` push publishes a snapshot image; a `vX.Y.Z` tag publishes `X.Y.Z` + `latest` — documented as the post-merge verification step
