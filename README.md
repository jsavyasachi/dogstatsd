# dogstatsd

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.savya/dogstatsd.svg)](https://clojars.org/net.clojars.savya/dogstatsd)
[![cljdoc](https://cljdoc.org/badge/net.clojars.savya/dogstatsd)](https://cljdoc.org/d/net.clojars.savya/dogstatsd)
[![test](https://github.com/jsavyasachi/dogstatsd/actions/workflows/test.yml/badge.svg)](https://github.com/jsavyasachi/dogstatsd/actions/workflows/test.yml)

A small, idiomatic Clojure wrapper over the official Datadog
[`java-dogstatsd-client`](https://github.com/DataDog/java-dogstatsd-client) for
sending DogStatsD metrics, events, and service checks to a Datadog Agent.

## Stack

<a href="https://clojure.org"><img src="https://img.shields.io/badge/Clojure-5881D8?style=flat&logo=clojure&logoColor=fff" alt="Clojure" /></a>
<a href="https://clojure.org/guides/deps_and_cli"><img src="https://img.shields.io/badge/deps.edn-5881D8?style=flat&logo=clojure&logoColor=fff" alt="deps.edn" /></a>
<a href="https://clojure.github.io/tools.build/"><img src="https://img.shields.io/badge/tools.build-5881D8?style=flat&logo=clojure&logoColor=fff" alt="tools.build" /></a>
<a href="https://www.datadoghq.com"><img src="https://img.shields.io/badge/Datadog-632CA6?style=flat&logo=datadog&logoColor=fff" alt="Datadog" /></a>

## Why

The existing Clojure DogStatsD libraries are abandoned and roll their own UDP
socket. This one is a thin layer over Datadog's own actively-maintained Java
client, so you get UDS support, client-side aggregation, telemetry, and origin
detection for free - with a Clojure-shaped API.

## Installation

tools.deps (`deps.edn`):

```clojure
net.clojars.savya/dogstatsd {:mvn/version "0.1.2"}
```

Leiningen (`project.clj`):

```clojure
[net.clojars.savya/dogstatsd "0.1.2"]
```

## Usage

```clojure
(require '[dogstatsd.core :as dd])

;; Build a client (Closeable). Defaults to localhost:8125.
(def statsd
  (dd/client {:prefix "myapp"
              :constant-tags {:env "prod" :service "api"}}))

(dd/increment statsd :page.views)
(dd/increment statsd :page.views {:page "home"})
(dd/count     statsd :jobs.processed 5)
(dd/gauge     statsd :queue.depth 42)
(dd/histogram statsd :response.size 2048)
(dd/distribution statsd :request.latency 12.5)
(dd/timing    statsd :db.query 150)            ; milliseconds
(dd/set-metric statsd :users.active "user-123")

;; Tags: a map {:k "v"} -> k:v, or a seq of strings ["k:v" ...].
(dd/gauge statsd :temperature 20 {:region "eu" :unit "c"})

;; Sampling and per-call tag cardinality use a trailing options map.
(dd/count statsd :jobs.processed 5 {:queue "critical"}
          {:sample-rate 0.25 :cardinality :high})
(dd/increment statsd :page.views {:page "home"}
              {:sample-rate 0.5 :cardinality :low})

;; Events and service checks.
(dd/event statsd "Deploy" "v1.2.3 shipped" {:alert-type :success
                                            :tags {:version "1.2.3"}})
(dd/service-check statsd "api.healthy" :ok {:message "all good"})

;; Closeable: prefer with-open for short-lived clients.
(with-open [c (dd/client {:host "localhost" :port 8125})]
  (dd/increment c :ping))
```

A Datadog Agent must be listening for DogStatsD packets (UDP `8125` by default).

### Client options

Use `:socket-path` for a Unix datagram socket or `:named-pipe` for a Windows
named pipe. `:address` accepts the SDK transport URLs (`udp://`, `unix://`, or
`unixstream://`).

```clojure
(dd/client {:socket-path "/var/run/datadog/dsd.socket"
            :telemetry? false
            :origin-detection? true
            :queue-size 8192
            :max-packet-size 8192
            :sender-workers 2
            :cardinality :low
            :error-handler #(println "DogStatsD send failed:" %)})
```

The builder also exposes `:telemetry-host`, `:telemetry-port`,
`:telemetry-address`, `:entity-id`, `:container-id`, `:timeout-ms`,
`:connection-timeout-ms`, `:buffer-pool-size`, `:socket-buffer-size`,
`:processor-workers`, `:blocking?`, `:telemetry-flush-interval-ms`,
`:aggregation-flush-interval-ms`, `:aggregation-shards`, and
`:thread-factory`. Cardinality values are `:default`, `:none`, `:low`,
`:orchestrator`, and `:high`.

### API

| fn | DogStatsD type |
|---|---|
| `increment` / `decrement` | counter ±1 |
| `count` | counter by delta |
| `gauge` | gauge |
| `histogram` | histogram |
| `distribution` | distribution |
| `timing` | timer (ms) |
| `set-metric` | set |
| `event` | event |
| `service-check` | service check |

Each metric fn takes the client, a metric name (keyword or string), an optional
value, and optional tags. `count`, `gauge`, `increment`, `decrement`, `timing`,
`histogram`, and `distribution` also accept a trailing options map containing
`:sample-rate` and/or `:cardinality`. When cardinality is provided without a
sample rate, the SDK receives a sample rate of `1.0`.

## License

Copyright © 2026 Savyasachi

Distributed under the [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/).
