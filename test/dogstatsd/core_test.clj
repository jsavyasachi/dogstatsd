(ns dogstatsd.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dogstatsd.core :as dd])
  (:import [java.net DatagramSocket DatagramPacket]))

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
