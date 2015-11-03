(ns flows.core
  (:require [clojure.core.async
             :refer [>! <! >!! <!! go chan buffer close! thread put!
                     alts! alts!! timeout]
             :as async]
            [clojure.tools.logging :as log]
            [clojure.test :refer [function?]]))

(defn thread-in-fn [c]
  (fn [item] (>!! c item)))

(defn thread-out-fn [c]
  (fn [] (<!! c)))

(defn close-chans-fn [& chans]
  (fn []
    (map close! chans)))

(defn prepare-segment [])

(defn make-task-bus [buffer]
  (let [in (chan buffer)
        out (chan buffer)]
    (thread
      (loop []
        (when-let [segment (<!! in)]
          (println "_________bus_________: " segment)
          ;;might do some filtering here at some point
          (>!! out segment)
          (recur))))
    {:in (thread-in-fn in)
     :out (thread-out-fn out)
     :in-chan in
     :out-chan out
     :close (close-chans-fn out in)}))

(defn find-next-tasks [task workflow]
  (->> workflow
       (filter #(= task (first %)))
       (map (fn [[_ c]] (if (coll? c) c [c])))
       (apply concat)))

(defn prune-workflow [task workflow]
  (remove #(= task (first %)) workflow))

(defn task-a [segment] (println "task-a") segment)
(defn task-b [segment] (println "task-b") segment)
(defn task-c [segment] (println "task-c") segment)

(defn default-input-transform [segment data]
  (assoc segment :data data))


(comment
  ;;input entry:

  {:name               :bozo
   :type               :input
   :input-type         :async
   :input-transform-fn (fn [segment data])}

  )

(defn make-async-input [bus entry catalog workflow]
  (thread
    (loop []
      (when-let [input-data (<!! (:chan entry))]
        ((bus :in)
         ((if-let [f (:input-transform-fn entry)]
            f
            default-input-transform)
          {:task (:name entry)
           :workflow workflow
           :catalog catalog}
          input-data))
        (recur)))))

(defn default-output-transform [segment]
  (:data segment))

(defn make-async-output [entry]
  (let [input-chan (chan)]
    (thread
      (loop []
        (when-let [segment (<!! input-chan)]
          (when-let [transformed ((if-let [f (:output-transform-fn entry)]
                                    f
                                    default-output-transform)
                                  segment)]
            (>!!
             (:chan entry)
             transformed))
          (recur))))
    (assoc entry :input-chan input-chan)))

(defn find-in-catalog [catalog matching]
  (->>
   (filter (fn [entry]
             (when (reduce (fn [a b] (and a b))
                           (map (fn [[k v]] (= (k entry) v)) matching))
               entry))
           catalog)
   (remove nil?)
   (first)))

(defn in-catalog? [catalog matching]
  (not (empty?
        (filter (fn [entry]
                  (reduce (fn [a b] (and a b))
                          (map (fn [[k v]] (= (k entry) v)) matching)))
                catalog))))

(defn input? [catalog task-name]
  (in-catalog? catalog {:name task-name :type :input}))

(defn output? [catalog task-name]
  (in-catalog? catalog {:name task-name :type :output}))

(defn find-task-fn [catalog task]
  (cond
   (keyword? task) (fn [a] (Thread/sleep 1000) a)
   (function? task) task
   :else (fn [a] a)))

(defn make-worker [bus]
  (thread
    (loop []
      (when-let [{:keys [catalog task workflow] :as segment} ((bus :out))]
        (if-let [{:keys [input-chan] :as output-config}
                 (find-in-catalog catalog {:name task :type :output})]
          (>!! input-chan segment)
          (let [next-tasks     (find-next-tasks task workflow)
                tasked-segment (cond
                                (input? catalog task)  segment

                                :else
                                ((find-task-fn catalog task) segment))]
            (doall
             (map
              (fn [next-task]
                ((bus :in)
                 (assoc tasked-segment
                   :workflow (prune-workflow task workflow)
                   :task next-task)))
              next-tasks))
            (recur)))))))

(comment

  (defn make-input [{:keys [input-type] :as entry}]
    (case input-type
      :async (make-async-input-listener entry)))

  (defn realize-entry [{:keys [type name] :as entry}]
    (case type
      :input  (make-input entry)
      :output (make-output entry)
      :task   (make-task entry)))


  (defn realize-catalog [catalog]
    (->> catalog
         (map realize-entry))))


(defn plus5 [segment]
  (update-in segment [:data] + 5))

(defn times2 [segment]
  (update-in segment [:data] * 2))

(defn minus4 [segment]
  (update-in segment [:data] - 4))

(minus4 (times2 (plus5 {:data 5})))


(comment

  ;;; here is where you can sort-of play with it

  (def catalog [{:name :in
                 :type :input
                 :input-type :async}])

  (def workflow [[:in :a] [:a :b] [:b [:c :d]] [:d :out]])

  (def workflow [[:in plus5]
                 [plus5 times2]
                 [times2 minus4]
                 [minus4 :out]])

  ((bus :close))
  (def bus (make-task-bus 10))

  (def input-chan (chan))
  (def output-chan (chan))
  (make-async-input bus {:name :in
                         :chan input-chan}
                    (cons (make-async-output {:name :out
                                              :type :output
                                              :output-type :async
                                              :chan output-chan})
                          catalog)
                    workflow)

  ;; run this multiple times to get more workers
  (make-worker bus)

  (put! input-chan 10)

  (<!! output-chan)


  )
