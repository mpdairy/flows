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

  (make-worker bus)

  (put! input-chan 10)

  (<!! output-chan)

  ((bus :in) {:task :a
              :workflow [[:a :b] [:b [:c :d]] [:d :print]]})



  ((bus :in) {:task task-a
              :workflow [[task-a [task-b]] [task-b [task-c]]]})

  ((bus :out))

  (def jim (chan))
  (close! jim)
  ((bus :out))

  )


(defn plus3 [data]
  (+ data 3))

(defn minus5 [data]
  (- data 5))

(defn times2 [data]
  (* data 2))

(def in (chan))

(def out (chan))

(defn prep-function
  ([fun] (prep-function fun {}))
  ([fun chans]
     (let [in (or (:in chans) (chan))
           out (or (:out chans) (chan))]
       (thread (loop []
                 (when-let [data-in (<! in)]
                   (>! out (fun data-in))
                   (recur))))
       {:in in :out out})))

(defn chain-tasks [tasks in out]
  (when-not (empty? tasks)
    (let [fun-chans
          (prep-function (first tasks)
                         (merge {:in in}
                                (when (= (count tasks) 1)
                                  {:out out})))]
      (recur (rest tasks) (:out fun-chans) out))))

(defn tasker-from-function [fun]
  {:type :function
   :id fun
   :in (chan) :out (chan)})

(defn taskers-from-workflow [workflow]
  (map tasker-from-function
       (vec (set (flatten workflow)))))


(comment

  [[[A [B C]] [[B C] D]]]

  )
(comment
  (defn chain-track [taskers track in out]
    (when-not (empty? tasks)
      (let [fun-chans
            (prep-function (first tasks)
                           (merge {:in in}
                                  (when (= (count tasks) 1)
                                    {:out out})))]
        (recur (rest tasks) (:out fun-chans) out)))))

(comment
  (taskers-from-workflow [plus3 minus5 times2 minus5 minus5])

  ;;segment
  {:path [minus5 times2 times2 out]
   :data 34}

  )


;; each tasker has :in and :out
;; something has to take the out from one and stick it into the in's
;; of the next one in the segment's path.
;; if this were centralized:
;; - something needs to tell the segment where to go after it executes
;; - task sends segment to central controller
;; - controller sends segment to next taskers

;; if decentralized:
;; - must sync tasker list with address/location of each tasker
;; - send out task to next tasker directly.

;; in onyx:
;; - include track with function names for where to send everything
;; - have flow conditions to skip things

;;;

;;; have a reservoir of taskers
;;; when a segment leaves one task it has to find an available one
;;; each segment needs a unique id

;;; whenever one is busy it will try to find another free one in the
;;; list.  they move up in list
;;; use the running time and number of times its been run to determine
;;; how close to the top it goes.  The ones at the botom are ditched.
;;; or maybe the busy attempts/time waiting determine the proportion
;;; of taskers.

;;; to make it easy at the start, and to test, we'll make each task
;;; just have one tasker.

(comment
  (def bozo (prep-function plus3 {:in in}))
  (def caleb (prep-function times2 {:in (:out bozo) :out out}))
  (thread (>!! in 9))
  (thread (println "data: " (<!! out)))

  (chain-tasks [plus3 minus5 minus5 minus5 times2 times2 times2] in out)

  )

(+ (if true 7 3) 5)

(def workflow [in plus3 minus5 times2 out])

(def workflow [plus3 minus5 times2])


(defn input-from-chan [chan]
  )

(defn prep-task [task]
  (cond
   (function? task) (prep-function task)

   (= (type task) clojure.core.async.impl.channels.ManyToManyChannel)
   task

   :else task))

(=
 (type (chan))
 clojure.core.async.impl.channels.ManyToManyChannel)

;;;
(defn submit-job [workflow]
  (loop [workflow (map prep-task workflow)]


    )
  )

(defn hotdog-machine-v2
  [hotdog-count]
  (let [in (chan)
        out (chan)]
    (go (loop [hc hotdog-count]
          (if (> hc 0)
            (let [input (<! in)]
              (if (= 3 input)
                (do (>! out "hotdog")
                    (recur (dec hc)))
                (do (>! out "wilted lettuce")
                    (recur hc))))
            (do (close! in)
                (close! out)))))
    {:in in
     :out out}))

(comment

  (def hotdog-machine (hotdog-machine-v2 4))

  (>!! (:in hotdog-machine) 1)

  (<!! (:out hotdog-machine))

  (def echo-chan (chan))
  echo-chan


  (go (loop []
        (when-let [msg (<! echo-chan)]
          (println msg)
          (recur))))

  (>!! echo-chan "bojangle")

  (def jimthread (thread (do  "jim doug jones" (Thread/sleep 10000) "pete")))

  (<!! jimthread)
  (>!! jimthread "big lugger")



  )


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))
