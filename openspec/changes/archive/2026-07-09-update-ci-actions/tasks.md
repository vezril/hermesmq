## 1. Bump actions in ci.yml

- [x] 1.1 In `.github/workflows/ci.yml`, bump `actions/checkout@v4` → v7, `actions/setup-java@v4` → v5, and `docker/login-action@v3` → v4, across both jobs; leave `sbt/setup-sbt@v1` unchanged

## 2. Bump actions in release.yml

- [x] 2.1 In `.github/workflows/release.yml`, bump the same three actions (`actions/checkout`, `actions/setup-java`, `docker/login-action`) to the identical majors used in `ci.yml`; leave `sbt/setup-sbt@v1` unchanged

## 3. Verify

- [x] 3.1 Open the PR and confirm the `Compile & Test` run is **green** and its log contains **no** "forced to run on Node.js" deprecated-runtime warning (validates the ci.yml bump end-to-end, incl. `docker/login-action` on the dev-snapshot job)
- [x] 3.2 Confirm the target majors exist (a wrong pin fails the run on action resolution); note in the PR that `release.yml`'s bump is exercised on the next release tag, and sanity-check that its action majors match `ci.yml`
