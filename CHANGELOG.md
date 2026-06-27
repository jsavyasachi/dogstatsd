# Changelog

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
