;; Licensed to the Apache Software Foundation (ASF) under one
;; or more contributor license agreements.  See the NOTICE file
;; distributed with this work for additional information
;; regarding copyright ownership.  The ASF licenses this file
;; to you under the Apache License, Version 2.0 (the
;; "License"); you may not use this file except in compliance
;; with the License.  You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns org.apache.storm.timer
  (:import [org.apache.storm.utils Utils Time])
  (:import [java.util PriorityQueue Comparator Random])
  (:import [java.util.concurrent Semaphore])
  (:use [org.apache.storm util log]))

;; The timer defined in this file is very similar to java.util.Timer, except
;; it integrates with Storm's time simulation capabilities. This lets us test
;; code that does asynchronous work on the timer thread

(defnk mk-timer [:kill-fn (fn [& _] ) :timer-name nil]
  (let [queue (PriorityQueue. 10 (reify Comparator
                                   (compare
                                     [this o1 o2]
                                     (- (first o1) (first o2)))
                                   (equals
                                     [this obj]
                                     true)))
        active (atom true)
        lock (Object.)
        notifier (Semaphore. 0)
        thread-name (if timer-name timer-name "timer")
        timer-thread (Thread.
                       (fn []
                         (while @active
                           (try
                             (let [[time-millis _ _ :as elem] (locking lock (.peek queue))]
                               (if (and elem (>= (Time/currentTimeMillis) time-millis))
                                 ;; It is imperative to not run the function
                                 ;; inside the timer lock. Otherwise, it is
                                 ;; possible to deadlock if the fn deals with
                                 ;; other locks, like the submit lock.
                                 (let [afn (locking lock (second (.poll queue)))]
                                   (afn))
                                 (if time-millis
                                   ;; If any events are scheduled, sleep until
                                   ;; event generation. If any recurring events
                                   ;; are scheduled then we will always go
                                   ;; through this branch, sleeping only the
                                   ;; exact necessary amount of time. We give
                                   ;; an upper bound, e.g. 1000 millis, to the
                                   ;; sleeping time, to limit the response time
                                   ;; for detecting any new event within 1 secs.
                                   (Time/sleep (min 1000 (- time-millis (Time/currentTimeMillis))))
                                   ;; Otherwise poll to see if any new event
                                   ;; was scheduled. This is, in essence, the
                                   ;; response time for detecting any new event
                                   ;; schedulings when there are no scheduled
                                   ;; events.
                                   (Time/sleep 1000))))
                             (catch Throwable t
                               ;; Because the interrupted exception can be
                               ;; wrapped in a RuntimeException.
                               (when-not (Utils/exceptionCauseIsInstanceOf InterruptedException t)
                                 (kill-fn t)
                                 (reset! active false)
                                 (throw t)))))
                         (.release notifier)) thread-name)]
    (.setDaemon timer-thread true)
    (.setPriority timer-thread Thread/MAX_PRIORITY)
    (.start timer-thread)
    {:timer-thread timer-thread
     :queue queue
     :active active
     :lock lock
     :random (Random.)
     :cancel-notifier notifier}))

(defn- check-active!
  [timer]
  (when-not @(:active timer)
    (throw (IllegalStateException. "Timer is not active"))))

(defnk schedule
  [timer delay-secs afn :check-active true :jitter-ms 0]
  (when check-active (check-active! timer))
  (let [id (Utils/uuid)
        ^PriorityQueue queue (:queue timer)
        end-time-ms (+ (Time/currentTimeMillis) (Time/secsToMillisLong delay-secs))
        end-time-ms (if (< 0 jitter-ms) (+ (.nextInt (:random timer) jitter-ms) end-time-ms) end-time-ms)]
    (locking (:lock timer)
      (.add queue [end-time-ms afn id]))))

(defn schedule-recurring
  [timer delay-secs recur-secs afn]
  (schedule timer
            delay-secs
            (fn this []
              (afn)
              ; This avoids a race condition with cancel-timer.
              (schedule timer recur-secs this :check-active false))))

(defn schedule-recurring-with-jitter
  [timer delay-secs recur-secs jitter-ms afn]
  (schedule timer
            delay-secs
            (fn this []
              (afn)
              ; This avoids a race condition with cancel-timer.
              (schedule timer recur-secs this :check-active false :jitter-ms jitter-ms))))

(defn cancel-timer
  [timer]
  (check-active! timer)
  (locking (:lock timer)
    (reset! (:active timer) false)
    (.interrupt (:timer-thread timer)))
  (.acquire (:cancel-notifier timer)))

(defn timer-waiting?
  [timer]
  (Time/isThreadWaiting (:timer-thread timer)))
