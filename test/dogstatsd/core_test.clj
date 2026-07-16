(ns dogstatsd.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dogstatsd.core :as dd])
  (:import [com.timgroup.statsd
           NonBlockingStatsDClientBuilder StatsDClient StatsDClientErrorHandler
           TagsCardinality]
           [java.lang.reflect InvocationHandler Method Proxy]
           [java.net DatagramSocket DatagramPacket]
           [java.util.concurrent ThreadFactory]))

(defn- recv
  "Block for one UDP datagram on sock and return its body as a string. The
  client newline-terminates each datagram, so trim the trailing newline."
  [^DatagramSocket sock]
  (let [buf (byte-array 8192)
        p   (DatagramPacket. buf (alength buf))]
    (.receive sock p)
    (str/trimr (String. buf 0 (.getLength p)))))

(defmacro with-listener
  "Bind a UDP socket and a client pointed at it; run body. extra is merged into
  client opts. Aggregation is disabled so each call yields one datagram."
  [[sock client opts] & body]
  `(with-open [~sock (doto (DatagramSocket. 0) (.setSoTimeout 2000))]
     (with-open [~client (dd/client (merge {:host "localhost"
                                            :port (.getLocalPort ~sock)
                                            :aggregation? false}
                                           ~opts))]
       ~@body)))

(defn- configured-builder [opts]
  (when-let [f (ns-resolve 'dogstatsd.core 'client-builder)]
    (f opts)))

(defn- recording-client []
  (let [calls (atom [])
        handler (reify InvocationHandler
                  (invoke [_ _ method args]
                    (let [^Method method method]
                      (swap! calls conj
                             {:method (.getName method)
                              :args (mapv (fn [arg]
                                            (if (and arg (.isArray (class arg)))
                                              (vec arg)
                                              arg))
                                          args)}))))
        client (Proxy/newProxyInstance
                (.getClassLoader StatsDClient)
                (into-array Class [StatsDClient])
                handler)]
    [(cast StatsDClient client) calls]))

(defn- supported-call? [f]
  (try
    (f)
    true
    (catch clojure.lang.ArityException _ false)))

(deftest client-builder-options-test
  (let [handled (atom nil)
        failure (Exception. "send failed")
        thread-factory (reify ThreadFactory
                         (newThread [_ runnable] (Thread. runnable)))
        ^NonBlockingStatsDClientBuilder b
        (configured-builder
         {:socket-path "/var/run/datadog/dsd.socket"
          :telemetry? false
          :origin-detection? false
          :entity-id "entity-123"
          :container-id "container-456"
          :queue-size 8192
          :timeout-ms 250
          :connection-timeout-ms 2000
          :buffer-pool-size 1024
          :socket-buffer-size 16384
          :max-packet-size 8192
          :processor-workers 2
          :sender-workers 3
          :blocking? true
          :telemetry-host "telemetry.local"
          :telemetry-port 9125
          :telemetry-address "udp://localhost:9126"
          :telemetry-flush-interval-ms 15000
          :aggregation-flush-interval-ms 250
          :aggregation-shards 4
          :thread-factory thread-factory
          :error-handler #(reset! handled %)
          :cardinality :high})]
    (is (some? b) "client-builder should expose configured builder state")
    (when b
      (is (some? (.-addressLookup b)))
      (is (false? (.-enableTelemetry b)))
      (is (false? (.-originDetectionEnabled b)))
      (is (= "entity-123" (.-entityID b)))
      (is (= "container-456" (.-containerID b)))
      (is (= 8192 (.-queueSize b)))
      (is (= 250 (.-timeout b)))
      (is (= 2000 (.-connectionTimeout b)))
      (is (= 1024 (.-bufferPoolSize b)))
      (is (= 16384 (.-socketBufferSize b)))
      (is (= 8192 (.-maxPacketSizeBytes b)))
      (is (= 2 (.-processorWorkers b)))
      (is (= 3 (.-senderWorkers b)))
      (is (true? (.-blocking b)))
      (is (= "telemetry.local" (.-telemetryHostname b)))
      (is (= 9125 (.-telemetryPort b)))
      (is (some? (.-telemetryAddressLookup b)))
      (is (= 15000 (.-telemetryFlushInterval b)))
      (is (= 250 (.-aggregationFlushInterval b)))
      (is (= 4 (.-aggregationShards b)))
      (is (identical? thread-factory (.-threadFactory b)))
      (is (= TagsCardinality/HIGH (.-tagsCardinality b)))
      (.handle ^StatsDClientErrorHandler (.-errorHandler b) failure)
      (is (identical? failure @handled)))))

(deftest named-pipe-client-builder-option-test
  (let [^NonBlockingStatsDClientBuilder b
        (configured-builder {:named-pipe "\\\\.\\pipe\\dogstatsd"})]
    (is (some? b) "client-builder should expose configured builder state")
    (when b
      (is (= "\\\\.\\pipe\\dogstatsd" (.-namedPipe b))))))

(deftest sampled-metrics-with-cardinality-test
  (let [[c calls] (recording-client)
        opts {:sample-rate 0.25 :cardinality :high}
        sends [#(dd/count c :requests 7 {:env "test"} opts)
               #(dd/gauge c :depth 42 {:env "test"} opts)
               #(dd/increment c :hits {:env "test"} opts)
               #(dd/decrement c :hits {:env "test"} opts)
               #(dd/timing c :latency 150 {:env "test"} opts)
               #(dd/histogram c :size 256 {:env "test"} opts)
               #(dd/distribution c :global-latency 12.5 {:env "test"} opts)]]
    (doseq [send sends]
      (is (supported-call? send) "metric should accept a trailing options map"))
    (is (= ["count" "gauge" "count" "count" "recordExecutionTime"
            "recordHistogramValue" "recordDistributionValue"]
           (mapv :method @calls)))
    (doseq [{:keys [args]} @calls]
      (is (= 5 (clojure.core/count args)))
      (is (= 0.25 (nth args 2)))
      (is (= TagsCardinality/HIGH (nth args 3)))
      (is (= ["env:test"] (nth args 4))))
    (when (= 7 (clojure.core/count @calls))
      (is (= 1 (second (:args (nth @calls 2)))))
      (is (= -1 (second (:args (nth @calls 3))))))))

(deftest sampled-metric-without-cardinality-test
  (let [[c calls] (recording-client)]
    (is (supported-call?
         #(dd/gauge c :depth 42 nil {:sample-rate 0.5})))
    (is (= {:method "gauge" :args ["depth" 42.0 0.5 []]}
           (first @calls)))))

(deftest cardinality-without-sample-rate-test
  (let [[c calls] (recording-client)]
    (is (supported-call?
         #(dd/timing c :latency 150 nil {:cardinality :low})))
    (is (= {:method "recordExecutionTime"
            :args ["latency" 150 1.0 TagsCardinality/LOW []]}
           (first @calls)))))

(deftest increment-decrement-test
  (with-listener [sock c nil]
    (dd/increment c :page.views)
    (is (= "page.views:1|c" (recv sock)))
    (dd/decrement c "page.views")
    (is (= "page.views:-1|c" (recv sock)))))

(deftest count-test
  (with-listener [sock c nil]
    (dd/count c :requests 7)
    (is (= "requests:7|c" (recv sock)))))

(deftest gauge-histogram-distribution-test
  (with-listener [sock c nil]
    (dd/gauge c :queue.depth 42)
    (is (= "queue.depth:42|g" (recv sock)))
    (dd/histogram c :resp.size 256.0)
    (is (= "resp.size:256|h" (recv sock)))
    (dd/distribution c :lat 12.5)
    (is (= "lat:12.5|d" (recv sock)))))

(deftest timing-and-set-test
  (with-listener [sock c nil]
    (dd/timing c :db.query 150)
    (is (= "db.query:150|ms" (recv sock)))
    (dd/set-metric c :users.unique "u123")
    (is (= "users.unique:u123|s" (recv sock)))))

(deftest tags-test
  (testing "tags from a map"
    (with-listener [sock c nil]
      (dd/increment c :hits {:env "test"})
      (is (= "hits:1|c|#env:test" (recv sock)))))
  (testing "tags from a seq of strings (order not guaranteed by the client)"
    (with-listener [sock c nil]
      (dd/gauge c :temp 20 ["env:test" "region:eu"])
      (let [s (recv sock)]
        (is (str/starts-with? s "temp:20|g|#"))
        (is (= #{"env:test" "region:eu"}
               (set (str/split (subs s (inc (str/index-of s "#"))) #","))))))))

(deftest prefix-and-constant-tags-test
  (with-listener [sock c {:prefix "svc" :constant-tags {:env "test"}}]
    (dd/increment c :hits)
    (is (= "svc.hits:1|c|#env:test" (recv sock)))))

(deftest event-test
  (with-listener [sock c nil]
    (dd/event c "deploy" "v1.2.3 shipped" {:tags {:env "test"}})
    (let [s (recv sock)]
      (is (re-find #"^_e\{\d+,\d+\}:deploy\|v1\.2\.3 shipped" s))
      (is (re-find #"#env:test" s)))))

(deftest service-check-test
  (with-listener [sock c nil]
    (dd/service-check c "api.up" :ok {:tags {:env "test"}})
    (let [s (recv sock)]
      (is (re-find #"^_sc\|api\.up\|0" s))
      (is (re-find #"#env:test" s)))))
