(defproject net.cgrand/xforms "0.1.0"
  :description "Extra transducers for Clojure"
  :url "https://github.com/cgrand/xforms"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48" :classifier "aot"
                  :exclusion [org.clojure/data.json]]
                 [org.clojure/data.json "0.2.6" :classifier "aot"]]
  :jvm-opts ^:replace ["-Xmx1g" "-server"]
  :clean-targets ["target" "release"]
  :target-path "target"
  :profiles
  {:dev {:source-paths           ["src" "test" "dev"]
         :dependencies           [[lein-npm "0.6.1"]]
         :npm {:dependencies [[source-map-support "0.3.2"]]}
         :plugins [[lein-npm "0.6.1"]]}}
  :aliases {"build-cljs" ["run" "-m" "user/build"]
            "node-repl"  ["run" "-m" "user/node-repl"]
            "test-cljs"  ["run" "-m" "net.cgrand.tests/test-all"]})
