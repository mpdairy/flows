(defproject flows "0.1.0-SNAPSHOT"
  :description "distributed computation using workflows"
  :url "http://github.com/mpdairy/flows"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.1"]]
  :main ^:skip-aot flows.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
