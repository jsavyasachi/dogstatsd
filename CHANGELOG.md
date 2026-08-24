# Changelog

## [0.4.0] - 2026-08-23

### Added

* `:address-lookup` and `:telemetry-address-lookup` client options accept a
  0-arg Clojure function for dynamic service discovery / address refresh.
* `stop!`, distinct from `close`, invoking the client's explicit `.stop()`.
* `telemetry-metric` wraps `sendTelemetryMetric` (telemetry must be enabled).
* Unix-socket (`unixstream://`), telemetry-builder, and `close` shutdown
  test coverage.
* Metric names, sample rates, cardinalities, event alert-types/priorities,
  and service-check statuses are now validated at the point of use, raising
  `ex-info` with the offending option/value/accepted-set instead of failing
  later with an opaque Java `NullPointerException`.

### Fixed

* `client` no longer unconditionally sets `localhost:8125` on the builder;
  host/port are now opt-in overrides, so the underlying Java client's own
  `DD_AGENT_HOST`/`DD_DOGSTATSD_PORT`/`DD_DOGSTATSD_URL` environment
  defaulting takes effect when the caller omits them.

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

## [0.1.2] - 2026-06-26

* Relicense to EPL 2.0 (corrects the published POM license metadata; no code change).

## [0.1.1] - 2026-06-14

* Standardize README structure and badges (docs only).

## [0.1.0] - 2026-06-07

* Initial release.
* Idiomatic Clojure wrapper over the official Datadog `java-dogstatsd-client` 4.4.5:
  `client`, `increment`, `decrement`, `count`, `gauge`, `histogram`, `distribution`,
  `timing`, `set-metric`, `event`, `service-check`.
* Tags accept a Clojure map (`{:env "prod"}`) or a seq of strings.
