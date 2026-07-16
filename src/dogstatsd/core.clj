(ns dogstatsd.core
  "Idiomatic Clojure wrapper over the official Datadog java-dogstatsd-client.

  Build a client with `client`, then send metrics (`increment`, `decrement`,
  `count`, `count-at`, `gauge`, `gauge-at`, `histogram`, `distribution`,
  `timing`, `set-metric`), `event`s, and `service-check`s. The client is
  `java.io.Closeable`, so use it with `with-open` or call `close`.

  Tags may be a map (`{:env \"prod\"}` -> `env:prod`) or a seq of strings
  (`[\"env:prod\"]`). Metric names may be keywords or strings."
  (:refer-clojure :exclude [count])
  (:import [com.timgroup.statsd
            StatsDClient NonBlockingStatsDClientBuilder
            StatsDClientErrorHandler TagsCardinality
            Event Event$AlertType Event$Builder
            ServiceCheck ServiceCheck$Status ServiceCheck$Builder]))

(set! *warn-on-reflection* true)

(defn- as-str ^String [x]
  (if (keyword? x) (name x) (str x)))

(defn- ^"[Ljava.lang.String;" ->tags
  "Coerce tags (a map or a seq of strings) into a String[] for the Java client."
  [tags]
  (into-array String
              (cond
                (nil? tags) nil
                (map? tags) (map (fn [[k v]]
                                   (if (nil? v) (as-str k) (str (as-str k) ":" (as-str v))))
                                 tags)
                :else       (map as-str tags))))

(def ^:private cardinalities
  {:default      TagsCardinality/DEFAULT
   :none         TagsCardinality/NONE
   :low          TagsCardinality/LOW
   :orchestrator TagsCardinality/ORCHESTRATOR
   :high         TagsCardinality/HIGH})

(defn- ->cardinality ^TagsCardinality [cardinality]
  (if (keyword? cardinality)
    (cardinalities cardinality)
    cardinality))

(defn- client-builder
  ^NonBlockingStatsDClientBuilder
  [{:keys [prefix host port constant-tags aggregation?
           address socket-path named-pipe
           telemetry? origin-detection? entity-id container-id
           queue-size timeout-ms connection-timeout-ms buffer-pool-size
           socket-buffer-size max-packet-size processor-workers sender-workers
           blocking? telemetry-host telemetry-port telemetry-address
           telemetry-flush-interval-ms aggregation-flush-interval-ms
           aggregation-shards error-handler cardinality thread-factory]
    :or   {host "localhost" port 8125}}]
  (let [b (NonBlockingStatsDClientBuilder.)]
    (.hostname b host)
    (.port b (int port))
    (when prefix (.prefix b (as-str prefix)))
    (when (seq constant-tags) (.constantTags b (->tags constant-tags)))
    (when (some? aggregation?) (.enableAggregation b (boolean aggregation?)))
    (when address (.address b address))
    (when socket-path (.address b (str "unix://" socket-path)))
    (when named-pipe (.namedPipe b named-pipe))
    (when (some? telemetry?) (.enableTelemetry b (boolean telemetry?)))
    (when (some? origin-detection?)
      (.originDetectionEnabled b (boolean origin-detection?)))
    (when entity-id (.entityID b entity-id))
    (when container-id (.containerID b container-id))
    (when (some? queue-size) (.queueSize b (int queue-size)))
    (when (some? timeout-ms) (.timeout b (int timeout-ms)))
    (when (some? connection-timeout-ms)
      (.connectionTimeout b (int connection-timeout-ms)))
    (when (some? buffer-pool-size) (.bufferPoolSize b (int buffer-pool-size)))
    (when (some? socket-buffer-size) (.socketBufferSize b (int socket-buffer-size)))
    (when (some? max-packet-size) (.maxPacketSizeBytes b (int max-packet-size)))
    (when (some? processor-workers) (.processorWorkers b (int processor-workers)))
    (when (some? sender-workers) (.senderWorkers b (int sender-workers)))
    (when (some? blocking?) (.blocking b (boolean blocking?)))
    (when telemetry-host (.telemetryHostname b telemetry-host))
    (when (some? telemetry-port) (.telemetryPort b (int telemetry-port)))
    (when telemetry-address (.telemetryAddress b telemetry-address))
    (when (some? telemetry-flush-interval-ms)
      (.telemetryFlushInterval b (int telemetry-flush-interval-ms)))
    (when (some? aggregation-flush-interval-ms)
      (.aggregationFlushInterval b (int aggregation-flush-interval-ms)))
    (when (some? aggregation-shards)
      (.aggregationShards b (int aggregation-shards)))
    (when thread-factory (.threadFactory b thread-factory))
    (when error-handler
      (.errorHandler b
                     (reify StatsDClientErrorHandler
                       (handle [_ exception] (error-handler exception)))))
    (when cardinality (.tagsCardinality b (->cardinality cardinality)))
    b))

(defn client
  "Build a StatsDClient. Options:

    :host           agent host (default \"localhost\")
    :port           agent port (default 8125)
    :prefix         prefix prepended to every metric name
    :constant-tags  tags added to every metric (map or seq of strings)
    :aggregation?   client-side aggregation (default: the client's default, true)
    :address        transport URL (udp://, unix://, unixstream://)
    :socket-path    Unix domain socket path (datagram transport)
    :named-pipe     Windows named pipe path
    :telemetry?     client telemetry enabled?
    :origin-detection?  client origin detection enabled?
    :entity-id, :container-id  origin identifiers
    :queue-size, :buffer-pool-size, :socket-buffer-size, :max-packet-size
    :processor-workers, :sender-workers, :blocking?
    :timeout-ms, :connection-timeout-ms
    :telemetry-host, :telemetry-port, :telemetry-address
    :telemetry-flush-interval-ms, :aggregation-flush-interval-ms
    :aggregation-shards, :thread-factory
    :error-handler  function called with asynchronous send exceptions
    :cardinality    default tag cardinality (:default, :none, :low,
                    :orchestrator, or :high)

  The returned client is Closeable."
  ^StatsDClient
  [opts]
  (.build (client-builder opts)))

(defn close
  "Close the client, flushing any buffered metrics."
  [^StatsDClient client]
  (.close client))

(defn increment
  "Increment a counter by 1. A trailing options map supports :sample-rate and
  :cardinality."
  ([client metric] (increment client metric nil))
  ([^StatsDClient client metric tags]
   (.increment client (as-str metric) (->tags tags)))
  ([^StatsDClient client metric tags {:keys [sample-rate cardinality]}]
   (cond
     cardinality
     (.count client (as-str metric) (long 1) (double (or sample-rate 1.0))
             (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.increment client (as-str metric) (double sample-rate) (->tags tags))

     :else (increment client metric tags))))

(defn decrement
  "Decrement a counter by 1. A trailing options map supports :sample-rate and
  :cardinality."
  ([client metric] (decrement client metric nil))
  ([^StatsDClient client metric tags]
   (.decrement client (as-str metric) (->tags tags)))
  ([^StatsDClient client metric tags {:keys [sample-rate cardinality]}]
   (cond
     cardinality
     (.count client (as-str metric) (long -1) (double (or sample-rate 1.0))
             (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.decrement client (as-str metric) (double sample-rate) (->tags tags))

     :else (decrement client metric tags))))

(defn count
  "Adjust a counter by delta. A trailing options map supports :sample-rate and
  :cardinality."
  ([client metric delta] (count client metric delta nil))
  ([^StatsDClient client metric delta tags]
   (.count client (as-str metric) (long delta) (->tags tags)))
  ([^StatsDClient client metric delta tags {:keys [sample-rate cardinality]}]
   (cond
     cardinality
     (.count client (as-str metric) (long delta) (double (or sample-rate 1.0))
             (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.count client (as-str metric) (long delta) (double sample-rate) (->tags tags))

     :else (count client metric delta tags))))

(defn count-at
  "Record a counter value with a timestamp in seconds since the Unix epoch.
  A trailing options map supports :cardinality."
  ([client metric delta timestamp]
   (count-at client metric delta timestamp nil))
  ([^StatsDClient client metric delta timestamp tags]
   (.countWithTimestamp client (as-str metric) (long delta) (long timestamp)
                        (->tags tags)))
  ([^StatsDClient client metric delta timestamp tags {:keys [cardinality]}]
   (if cardinality
     (.countWithTimestamp client (as-str metric) (long delta) (long timestamp)
                          (->cardinality cardinality) (->tags tags))
     (count-at client metric delta timestamp tags))))

(defn gauge
  "Record the latest value of a gauge. A trailing options map supports
  :sample-rate and :cardinality."
  ([client metric value] (gauge client metric value nil))
  ([^StatsDClient client metric value tags]
   (.gauge client (as-str metric) (double value) (->tags tags)))
  ([^StatsDClient client metric value tags {:keys [sample-rate cardinality]}]
   (cond
     cardinality
     (.gauge client (as-str metric) (double value) (double (or sample-rate 1.0))
             (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.gauge client (as-str metric) (double value) (double sample-rate) (->tags tags))

     :else (gauge client metric value tags))))

(defn gauge-at
  "Record a gauge value with a timestamp in seconds since the Unix epoch.
  A trailing options map supports :cardinality."
  ([client metric value timestamp]
   (gauge-at client metric value timestamp nil))
  ([^StatsDClient client metric value timestamp tags]
   (.gaugeWithTimestamp client (as-str metric) (double value) (long timestamp)
                        (->tags tags)))
  ([^StatsDClient client metric value timestamp tags {:keys [cardinality]}]
   (if cardinality
     (.gaugeWithTimestamp client (as-str metric) (double value) (long timestamp)
                          (->cardinality cardinality) (->tags tags))
     (gauge-at client metric value timestamp tags))))

(defn histogram
  "Record a value in a histogram (server-side statistical distribution).
  A trailing options map supports :sample-rate and :cardinality."
  ([client metric value] (histogram client metric value nil))
  ([^StatsDClient client metric value tags]
   (.histogram client (as-str metric) (double value) (->tags tags)))
  ([^StatsDClient client metric value tags {:keys [sample-rate cardinality]}]
   (cond
     cardinality
     (.recordHistogramValue client (as-str metric) (double value)
                            (double (or sample-rate 1.0))
                            (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.recordHistogramValue client (as-str metric) (double value)
                            (double sample-rate) (->tags tags))

     :else (histogram client metric value tags))))

(defn distribution
  "Record a value in a global distribution. A trailing options map supports
  :sample-rate and :cardinality."
  ([client metric value] (distribution client metric value nil))
  ([^StatsDClient client metric value tags]
   (.recordDistributionValue client (as-str metric) (double value) (->tags tags)))
  ([^StatsDClient client metric value tags {:keys [sample-rate cardinality]}]
   (cond
     cardinality
     (.recordDistributionValue client (as-str metric) (double value)
                               (double (or sample-rate 1.0))
                               (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.recordDistributionValue client (as-str metric) (double value)
                               (double sample-rate) (->tags tags))

     :else (distribution client metric value tags))))

(defn timing
  "Record an execution time in milliseconds. A trailing options map supports
  :sample-rate and :cardinality."
  ([client metric millis] (timing client metric millis nil))
  ([^StatsDClient client metric millis tags]
   (.recordExecutionTime client (as-str metric) (long millis) (->tags tags)))
  ([^StatsDClient client metric millis tags {:keys [sample-rate cardinality]}]
   (cond
     cardinality
     (.recordExecutionTime client (as-str metric) (long millis)
                           (double (or sample-rate 1.0))
                           (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.recordExecutionTime client (as-str metric) (long millis)
                           (double sample-rate) (->tags tags))

     :else (timing client metric millis tags))))

(defn set-metric
  "Record a member of a set (counts unique occurrences)."
  ([client metric value] (set-metric client metric value nil))
  ([^StatsDClient client metric value tags]
   (.recordSetValue client (as-str metric) (as-str value) (->tags tags))))

(def ^:private alert-types
  {:error   Event$AlertType/ERROR
   :warning Event$AlertType/WARNING
   :info    Event$AlertType/INFO
   :success Event$AlertType/SUCCESS})

(defn event
  "Send an event. Options:

    :tags            map or seq of strings
    :alert-type      :error | :warning | :info | :success
    :hostname        source hostname
    :aggregation-key key to group related events
    :source-type     source type name
    :date            event timestamp in millis since epoch"
  ([client title text] (event client title text nil))
  ([^StatsDClient client title text
    {:keys [tags alert-type hostname aggregation-key source-type date]}]
   (let [^Event$Builder b (Event/builder)]
     (.withTitle b (as-str title))
     (.withText b (as-str text))
     (when alert-type      (.withAlertType b (alert-types alert-type)))
     (when hostname        (.withHostname b hostname))
     (when aggregation-key (.withAggregationKey b aggregation-key))
     (when source-type     (.withSourceTypeName b source-type))
     (when date            (.withDate b (long date)))
     (.recordEvent client (.build b) (->tags tags)))))

(def ^:private check-statuses
  {:ok       ServiceCheck$Status/OK
   :warning  ServiceCheck$Status/WARNING
   :critical ServiceCheck$Status/CRITICAL
   :unknown  ServiceCheck$Status/UNKNOWN})

(defn service-check
  "Send a service check. status is :ok | :warning | :critical | :unknown.
  Options: :tags, :message, :hostname."
  ([client name status] (service-check client name status nil))
  ([^StatsDClient client name status {:keys [tags message hostname]}]
   (let [^ServiceCheck$Builder b (ServiceCheck/builder)]
     (.withName b (as-str name))
     (.withStatus b (check-statuses status))
     (when message  (.withMessage b message))
     (when hostname (.withHostname b hostname))
     (when (seq tags) (.withTags b (->tags tags)))
     (.recordServiceCheckRun client (.build b)))))
