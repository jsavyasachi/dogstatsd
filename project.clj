(defproject net.clojars.savya/dogstatsd "0.1.1"
  :description "Idiomatic Clojure wrapper over the official Datadog java-dogstatsd-client (DogStatsD metrics, events, service checks)."
  :url "https://github.com/jsavyasachi/dogstatsd"
  :scm {:name "git" :url "https://github.com/jsavyasachi/dogstatsd"}
  :license {:name "Eclipse Public License 2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [com.datadoghq/java-dogstatsd-client "4.4.5"]]
  :global-vars {*warn-on-reflection* true}
  :profiles {:clojure-1-10 {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :clojure-1-11 {:dependencies [[org.clojure/clojure "1.11.4"]]}
             :clojure-1-12 {:dependencies [[org.clojure/clojure "1.12.0"]]}}
  :aliases {"all" ["with-profile" "+clojure-1-10:+clojure-1-11:+clojure-1-12"]})
