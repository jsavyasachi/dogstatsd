(ns dogstatsd.core
  "Clojure wrapper over the official Datadog java-dogstatsd-client.

  Build a client with `client`. Then send metrics (`increment`, `decrement`,
  `count`, `count-at`, `gauge`, `gauge-at`, `histogram`, `distribution`,
  `timing`, `set-metric`), `event`s, and `service-check`s. The client is
  `java.io.Closeable`, so use it with `with-open` or call `close`.

  Tags may be a map (`{:env \"prod\"}` -> `env:prod`) or a seq of strings
  (`[\"env:prod\"]`). Metric names may be keywords or strings."
  (:refer-clojure :exclude [count])
  (:import [com.timgroup.statsd
            StatsDClient NonBlockingStatsDClientBuilder
            StatsDClientErrorHandler TagsCardinality
            Event Event$AlertType Event$Builder Event$Priority
            ServiceCheck ServiceCheck$Status ServiceCheck$Builder]
           [java.net SocketAddress]
           [java.util.concurrent Callable]))

(set! *warn-on-reflection* true)

(defn- as-str ^String [x]
  (if (keyword? x) (name x) (str x)))

(defn- invalid-option [option value accepted]
  (throw (ex-info (format "Invalid %s option: %s (accepted: %s)"
                          (name option) (pr-str value) (pr-str accepted))
                  {:option option :value value :accepted accepted})))

(defn- validate-option [option value accepted]
  (if (contains? accepted value)
    value
    (invalid-option option value accepted)))

(defn- metric-name ^String [metric]
  (cond
    (keyword? metric) (name metric)
    (and (string? metric) (seq metric)) metric
    :else (invalid-option :metric-name metric #{:keyword String})))

(def ^:private sample-rate-range :between-0-and-1)

(defn- ^Double validate-sample-rate [rate]
  (if (and (number? rate) (<= 0.0 (double rate) 1.0))
    (double rate)
    (invalid-option :sample-rate rate sample-rate-range)))

(defn- ^Double sample-rate-or-default [rate]
  (if (some? rate) (validate-sample-rate rate) 1.0))

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
    (or (cardinalities cardinality)
        (invalid-option :cardinality cardinality (set (keys cardinalities))))
    (if (instance? TagsCardinality cardinality)
      cardinality
      (invalid-option :cardinality cardinality (set (keys cardinalities))))))

(defn- ->address-lookup ^Callable [lookup]
  (reify Callable
    (call [_] ^SocketAddress (lookup))))

(defn- client-builder
  ^NonBlockingStatsDClientBuilder
  [{:keys [prefix host port constant-tags aggregation?
           address socket-path named-pipe
           telemetry? origin-detection? entity-id container-id
           queue-size timeout-ms connection-timeout-ms buffer-pool-size
           socket-buffer-size max-packet-size processor-workers sender-workers
           blocking? telemetry-host telemetry-port telemetry-address
           address-lookup telemetry-address-lookup
           telemetry-flush-interval-ms aggregation-flush-interval-ms
           aggregation-shards error-handler cardinality thread-factory]}]
  (let [b (NonBlockingStatsDClientBuilder.)]
    (when (some? host) (.hostname b host))
    (when (some? port) (.port b (int port)))
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
    (when address-lookup (.addressLookup b (->address-lookup address-lookup)))
    (when telemetry-address-lookup
      (.telemetryAddressLookup b (->address-lookup telemetry-address-lookup)))
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
    (when (some? cardinality) (.tagsCardinality b (->cardinality cardinality)))
    b))

(defn client
  "Build a StatsDClient. Options:

    :host           agent host override (otherwise uses the SDK default)
    :port           agent port override (otherwise uses the SDK default)
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
    :address-lookup, :telemetry-address-lookup zero-argument functions
    returning a SocketAddress
    :telemetry-flush-interval-ms, :aggregation-flush-interval-ms
    :aggregation-shards, :thread-factory
    :error-handler  function that the client calls with asynchronous send
                    exceptions
    :cardinality    default tag cardinality (:default, :none, :low,
                    :orchestrator, or :high)

  The returned client is Closeable."
  ^StatsDClient
  [opts]
  (.build (client-builder opts)))

(defn close
  "Close the client by invoking its `.close()` operation.

  The bundled Java client implements `close()` by delegating to `stop()`;
  `stop!` remains available when the explicit stop operation is desired."
  [^StatsDClient client]
  (.close client))

(defn stop!
  "Stop the client by invoking its explicit `.stop()` operation.

  In the bundled Java client, this stops telemetry and metric processing and
  sending; its `close()` operation delegates to the same stop operation."
  [^StatsDClient client]
  (.stop client))

(defn increment
  "Increment a counter by 1. A trailing options map supports :sample-rate and
  :cardinality."
  ([client metric] (increment client metric nil))
  ([^StatsDClient client metric tags]
   (.increment client (metric-name metric) (->tags tags)))
  ([^StatsDClient client metric tags {:keys [sample-rate cardinality]}]
   (cond
     (some? cardinality)
     (.count client (metric-name metric) (long 1)
             (sample-rate-or-default sample-rate)
             (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.increment client (metric-name metric) (double (validate-sample-rate sample-rate)) (->tags tags))

     :else (increment client metric tags))))

(defn decrement
  "Decrement a counter by 1. A trailing options map supports :sample-rate and
  :cardinality."
  ([client metric] (decrement client metric nil))
  ([^StatsDClient client metric tags]
   (.decrement client (metric-name metric) (->tags tags)))
  ([^StatsDClient client metric tags {:keys [sample-rate cardinality]}]
   (cond
     (some? cardinality)
     (.count client (metric-name metric) (long -1)
             (sample-rate-or-default sample-rate)
             (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.decrement client (metric-name metric) (double (validate-sample-rate sample-rate)) (->tags tags))

     :else (decrement client metric tags))))

(defn count
  "Adjust a counter by delta. A trailing options map supports :sample-rate and
  :cardinality."
  ([client metric delta] (count client metric delta nil))
  ([^StatsDClient client metric delta tags]
   (if (integer? delta)
     (.count client (metric-name metric) (long delta) (->tags tags))
     (.count client (metric-name metric) (double delta) (->tags tags))))
  ([^StatsDClient client metric delta tags {:keys [sample-rate cardinality]}]
   (cond
     (some? cardinality)
     (if (integer? delta)
       (.count client (metric-name metric) (long delta)
               (sample-rate-or-default sample-rate)
               (->cardinality cardinality) (->tags tags))
       (.count client (metric-name metric) (double delta)
               (sample-rate-or-default sample-rate)
               (->cardinality cardinality) (->tags tags)))

     (some? sample-rate)
     (if (integer? delta)
       (.count client (metric-name metric) (long delta) (double (validate-sample-rate sample-rate)) (->tags tags))
       (.count client (metric-name metric) (double delta) (double (validate-sample-rate sample-rate)) (->tags tags)))

     :else (count client metric delta tags))))

(defn count-at
  "Record a counter value with a timestamp in seconds since the Unix epoch.
  A trailing options map supports :cardinality."
  ([client metric delta timestamp]
   (count-at client metric delta timestamp nil))
  ([^StatsDClient client metric delta timestamp tags]
   (if (integer? delta)
     (.countWithTimestamp client (metric-name metric) (long delta) (long timestamp)
                          (->tags tags))
     (.countWithTimestamp client (metric-name metric) (double delta) (long timestamp)
                          (->tags tags))))
  ([^StatsDClient client metric delta timestamp tags {:keys [cardinality]}]
   (if (some? cardinality)
     (if (integer? delta)
       (.countWithTimestamp client (metric-name metric) (long delta) (long timestamp)
                            (->cardinality cardinality) (->tags tags))
       (.countWithTimestamp client (metric-name metric) (double delta) (long timestamp)
                            (->cardinality cardinality) (->tags tags)))
     (count-at client metric delta timestamp tags))))

(defn gauge
  "Record the latest value of a gauge. A trailing options map supports
  :sample-rate and :cardinality."
  ([client metric value] (gauge client metric value nil))
  ([^StatsDClient client metric value tags]
   (if (integer? value)
     (.gauge client (metric-name metric) (long value) (->tags tags))
     (.gauge client (metric-name metric) (double value) (->tags tags))))
  ([^StatsDClient client metric value tags {:keys [sample-rate cardinality]}]
   (cond
     (some? cardinality)
     (if (integer? value)
       (.gauge client (metric-name metric) (long value)
               (sample-rate-or-default sample-rate)
               (->cardinality cardinality) (->tags tags))
       (.gauge client (metric-name metric) (double value)
               (sample-rate-or-default sample-rate)
               (->cardinality cardinality) (->tags tags)))

     (some? sample-rate)
     (if (integer? value)
       (.gauge client (metric-name metric) (long value) (double (validate-sample-rate sample-rate)) (->tags tags))
       (.gauge client (metric-name metric) (double value) (double (validate-sample-rate sample-rate)) (->tags tags)))

     :else (gauge client metric value tags))))

(defn gauge-at
  "Record a gauge value with a timestamp in seconds since the Unix epoch.
  A trailing options map supports :cardinality."
  ([client metric value timestamp]
   (gauge-at client metric value timestamp nil))
  ([^StatsDClient client metric value timestamp tags]
   (if (integer? value)
     (.gaugeWithTimestamp client (metric-name metric) (long value) (long timestamp)
                          (->tags tags))
     (.gaugeWithTimestamp client (metric-name metric) (double value) (long timestamp)
                          (->tags tags))))
  ([^StatsDClient client metric value timestamp tags {:keys [cardinality]}]
   (if (some? cardinality)
     (if (integer? value)
       (.gaugeWithTimestamp client (metric-name metric) (long value) (long timestamp)
                            (->cardinality cardinality) (->tags tags))
       (.gaugeWithTimestamp client (metric-name metric) (double value) (long timestamp)
                            (->cardinality cardinality) (->tags tags)))
     (gauge-at client metric value timestamp tags))))

(defn histogram
  "Record a value in a histogram (server-side statistical distribution).
  A trailing options map supports :sample-rate and :cardinality."
  ([client metric value] (histogram client metric value nil))
  ([^StatsDClient client metric value tags]
   (if (integer? value)
     (.histogram client (metric-name metric) (long value) (->tags tags))
     (.histogram client (metric-name metric) (double value) (->tags tags))))
  ([^StatsDClient client metric value tags {:keys [sample-rate cardinality]}]
   (cond
     (some? cardinality)
     (if (integer? value)
       (.recordHistogramValue client (metric-name metric) (long value)
                              (sample-rate-or-default sample-rate)
                              (->cardinality cardinality) (->tags tags))
       (.recordHistogramValue client (metric-name metric) (double value)
                              (sample-rate-or-default sample-rate)
                              (->cardinality cardinality) (->tags tags)))

     (some? sample-rate)
     (if (integer? value)
       (.recordHistogramValue client (metric-name metric) (long value)
                              (double (validate-sample-rate sample-rate)) (->tags tags))
       (.recordHistogramValue client (metric-name metric) (double value)
                              (double (validate-sample-rate sample-rate)) (->tags tags)))

     :else (histogram client metric value tags))))

(defn distribution
  "Record a value in a global distribution. A trailing options map supports
  :sample-rate and :cardinality."
  ([client metric value] (distribution client metric value nil))
  ([^StatsDClient client metric value tags]
   (if (integer? value)
     (.recordDistributionValue client (metric-name metric) (long value) (->tags tags))
     (.recordDistributionValue client (metric-name metric) (double value) (->tags tags))))
  ([^StatsDClient client metric value tags {:keys [sample-rate cardinality]}]
   (cond
     (some? cardinality)
     (if (integer? value)
       (.recordDistributionValue client (metric-name metric) (long value)
                                 (sample-rate-or-default sample-rate)
                                 (->cardinality cardinality) (->tags tags))
       (.recordDistributionValue client (metric-name metric) (double value)
                                 (sample-rate-or-default sample-rate)
                                 (->cardinality cardinality) (->tags tags)))

     (some? sample-rate)
     (if (integer? value)
       (.recordDistributionValue client (metric-name metric) (long value)
                                 (validate-sample-rate sample-rate) (->tags tags))
       (.recordDistributionValue client (metric-name metric) (double value)
                                 (validate-sample-rate sample-rate) (->tags tags)))

     :else (distribution client metric value tags))))

(defn timing
  "Record an execution time in milliseconds. A trailing options map supports
  :sample-rate and :cardinality."
  ([client metric millis] (timing client metric millis nil))
  ([^StatsDClient client metric millis tags]
   (.recordExecutionTime client (metric-name metric) (long millis) (->tags tags)))
  ([^StatsDClient client metric millis tags {:keys [sample-rate cardinality]}]
   (cond
     (some? cardinality)
     (.recordExecutionTime client (metric-name metric) (long millis)
                           (sample-rate-or-default sample-rate)
                           (->cardinality cardinality) (->tags tags))

     (some? sample-rate)
     (.recordExecutionTime client (metric-name metric) (long millis)
                           (validate-sample-rate sample-rate) (->tags tags))

     :else (timing client metric millis tags))))

(defn set-metric
  "Record a member of a set (counts unique occurrences). A trailing options
  map supports :cardinality."
  ([client metric value] (set-metric client metric value nil))
  ([^StatsDClient client metric value tags]
   (.recordSetValue client (metric-name metric) (as-str value) (->tags tags)))
  ([^StatsDClient client metric value tags {:keys [cardinality]}]
   (if (some? cardinality)
     (.recordSetValue client (metric-name metric) (as-str value)
                      (->cardinality cardinality) (->tags tags))
     (set-metric client metric value tags))))

(def ^:private alert-types
  {:error   Event$AlertType/ERROR
   :warning Event$AlertType/WARNING
   :info    Event$AlertType/INFO
   :success Event$AlertType/SUCCESS})

(def ^:private priorities
  {:normal Event$Priority/NORMAL
   :low    Event$Priority/LOW})

(defn event
  "Send an event. Options:

    :tags            map or seq of strings
    :alert-type      :error | :warning | :info | :success
    :hostname        source hostname
    :aggregation-key key to group related events
    :source-type     source type name
    :date            event timestamp in millis since epoch
    :priority        :normal | :low
    :cardinality     tag cardinality"
  ([client title text] (event client title text nil))
  ([^StatsDClient client title text
    {:keys [tags alert-type hostname aggregation-key source-type date
            priority cardinality]}]
   (let [^Event$Builder b (Event/builder)]
     (.withTitle b (as-str title))
     (.withText b (as-str text))
     (when (some? alert-type)
       (.withAlertType b (get alert-types
                              (validate-option :alert-type alert-type
                                               (set (keys alert-types))))))
     (when hostname        (.withHostname b hostname))
     (when aggregation-key (.withAggregationKey b aggregation-key))
     (when source-type     (.withSourceTypeName b source-type))
     (when date            (.withDate b (long date)))
     (when (some? priority)
       (.withPriority b (get priorities
                            (validate-option :priority priority
                                             (set (keys priorities))))))
     (when (some? cardinality)
       (.withTagsCardinality b (->cardinality cardinality)))
     (.recordEvent client (.build b) (->tags tags)))))

(def ^:private check-statuses
  {:ok       ServiceCheck$Status/OK
   :warning  ServiceCheck$Status/WARNING
   :critical ServiceCheck$Status/CRITICAL
   :unknown  ServiceCheck$Status/UNKNOWN})

(defn service-check
  "Send a service check. status is :ok | :warning | :critical | :unknown.
  Options: :tags, :message, :hostname, :timestamp, :cardinality,
  :check-run-id."
  ([client name status] (service-check client name status nil))
  ([^StatsDClient client name status
    {:keys [tags message hostname timestamp cardinality check-run-id]}]
   (let [^ServiceCheck$Builder b (ServiceCheck/builder)]
     (.withName b (as-str name))
     (.withStatus b (get check-statuses
                         (validate-option :status status
                                          (set (keys check-statuses)))))
     (when message  (.withMessage b message))
     (when hostname (.withHostname b hostname))
     (when (some? timestamp) (.withTimestamp b (int timestamp)))
     (when (some? cardinality)
       (.withTagsCardinality b (->cardinality cardinality)))
     (when (some? check-run-id) (.withCheckRunId b (int check-run-id)))
     (when (seq tags) (.withTags b (->tags tags)))
     (.recordServiceCheckRun client (.build b)))))
