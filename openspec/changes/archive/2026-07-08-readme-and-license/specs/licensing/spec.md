## ADDED Requirements

### Requirement: MIT license file

The project SHALL include an MIT `LICENSE` file at the repository root with the correct copyright holder and year, and SHALL reference the license from the README.

#### Scenario: LICENSE file is present and valid MIT
- **WHEN** the repository root is inspected
- **THEN** a `LICENSE` file exists containing the standard MIT license text with a filled-in copyright line (holder and year), not a placeholder

#### Scenario: README references the license
- **WHEN** a reader reaches the licensing section of the README
- **THEN** it states the project is MIT-licensed and links to the `LICENSE` file

#### Scenario: Edge case — build metadata declares the license
- **WHEN** `build.sbt` is inspected
- **THEN** it declares the MIT license (and project homepage) in its metadata, so published artifacts carry the correct license information

#### Scenario: Edge case — no conflicting license claims
- **WHEN** the repository's license declarations (LICENSE, README, build metadata) are compared
- **THEN** they all agree on MIT with the same holder, with no leftover "all rights reserved" or placeholder text
