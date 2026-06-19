# Changelog

All notable changes to Synapse-DB are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [1.0.0-SNAPSHOT] - 2026-06-19

### Changed
- Removed engineering-review process labels (D1, D2, Phase N, eng-review Xn) from all
  Java source comments and Javadoc — these were process artifacts with no value for
  readers of the code
- Removed Javadoc from all private methods and private fields (no Javadoc on private
  symbols is now the project convention)
- Removed inline comments that restate what variable names and method names already
  communicate
- Kept comments explaining non-obvious JVM behavior (FCNS sentinel fill, epoch-0
  empty-slot predicate), ordering constraints (FCNS prepend step order), and known
  V1 limitations (stale sibling chains, bounded walk guard)

### Added
- CHANGELOG.md (this file)
