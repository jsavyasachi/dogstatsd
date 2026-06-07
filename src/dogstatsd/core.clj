(ns dogstatsd.core
  "Idiomatic Clojure wrapper over the official Datadog java-dogstatsd-client.

  Build a client with `client`, then send metrics (`increment`, `decrement`,
  `count`, `gauge`, `histogram`, `distribution`, `timing`, `set-metric`),
  `event`s, and `service-check`s. The client is `java.io.Closeable`, so use it
  with `with-open` or call `close`.

  Tags may be a map (`{:env \"prod\"}` -> `env:prod`) or a seq of strings
  (`[\"env:prod\"]`). Metric names may be keywords or strings."
  (:refer-clojure :exclude [count])
  (:import [com.timgroup.statsd
            StatsDClient NonBlockingStatsDClientBuilder
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

(defn client
  "Build a StatsDClient. Options:

    :host           agent host (default \"localhost\")
    :port           agent port (default 8125)
    :prefix         prefix prepended to every metric name
    :constant-tags  tags added to every metric (map or seq of strings)
    :aggregation?   client-side aggregation (default: the client's default, true)

  The returned client is Closeable."
  ^StatsDClient
  [{:keys [prefix host port constant-tags aggregation?]
    :or   {host "localhost" port 8125}}]
  (let [b (NonBlockingStatsDClientBuilder.)]
    (.hostname b host)
    (.port b (int port))
    (when prefix (.prefix b (as-str prefix)))
    (when (seq constant-tags) (.constantTags b (->tags constant-tags)))
    (when (some? aggregation?) (.enableAggregation b (boolean aggregation?)))
    (.build b)))

(defn close
  "Close the client, flushing any buffered metrics."
  [^StatsDClient client]
  (.close client))

(defn increment
  "Increment a counter by 1."
  ([client metric] (increment client metric nil))
  ([^StatsDClient client metric tags]
   (.increment client (as-str metric) (->tags tags))))

(defn decrement
  "Decrement a counter by 1."
  ([client metric] (decrement client metric nil))
  ([^StatsDClient client metric tags]
   (.decrement client (as-str metric) (->tags tags))))

(defn count
  "Adjust a counter by delta."
  ([client metric delta] (count client metric delta nil))
  ([^StatsDClient client metric delta tags]
   (.count client (as-str metric) (long delta) (->tags tags))))

(defn gauge
  "Record the latest value of a gauge."
  ([client metric value] (gauge client metric value nil))
  ([^StatsDClient client metric value tags]
   (.gauge client (as-str metric) (double value) (->tags tags))))

(defn histogram
  "Record a value in a histogram (server-side statistical distribution)."
  ([client metric value] (histogram client metric value nil))
  ([^StatsDClient client metric value tags]
   (.histogram client (as-str metric) (double value) (->tags tags))))

(defn distribution
  "Record a value in a global distribution."
  ([client metric value] (distribution client metric value nil))
  ([^StatsDClient client metric value tags]
   (.recordDistributionValue client (as-str metric) (double value) (->tags tags))))

(defn timing
  "Record an execution time in milliseconds."
  ([client metric millis] (timing client metric millis nil))
  ([^StatsDClient client metric millis tags]
   (.recordExecutionTime client (as-str metric) (long millis) (->tags tags))))

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
