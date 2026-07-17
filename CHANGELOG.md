# Changelog

## [0.3.0] - 2026-07-16

### Added

* `service-check` gains `:timestamp`, `:cardinality`, and `:check-run-id` options.
* `event` gains `:cardinality` and `:priority` (`:normal`/`:low`) options.
* `set-metric` gains a per-call `:cardinality` option.

### Fixed

* Numeric metrics (`count`, `count-at`, `gauge`, `gauge-at`, `histogram`, `distribution`) now dispatch on value type: integers use the long/int overloads (preserving precision above 2^53) and floating/ratio values use the double overloads (preserving fractional values). Previously fractional deltas were truncated to `long`.

## [0.2.0] - 2026-07-16

### Added

* Expose the full `NonBlockingStatsDClientBuilder` surface through `client`.
* Add per-call `:sample-rate` and `:cardinality` options to metric functions.
* Add timestamped count and gauge metrics.

## [0.1.3] - 2026-07-12

### Changed

* Migrate the build to deps.edn and tools.build, with Leiningen supported via lein-tools-deps.

## 0.1.2 (2026-06-26)

* Relicense to EPL 2.0 (corrects the published POM license metadata; no code change).

## 0.1.1 (2026-06-14)

* Standardize README structure and badges (docs only).

## 0.1.0 (2026-06-07)

* Initial release.
* Idiomatic Clojure wrapper over the official Datadog `java-dogstatsd-client` 4.4.5:
  `client`, `increment`, `decrement`, `count`, `gauge`, `histogram`, `distribution`,
  `timing`, `set-metric`, `event`, `service-check`.
* Tags accept a Clojure map (`{:env "prod"}`) or a seq of strings.
