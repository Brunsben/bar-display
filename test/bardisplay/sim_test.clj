(ns bardisplay.sim-test
  (:require [bardisplay.sim :as sim]
            [bardisplay.state :as state]
            [clojure.test :refer [deftest is]]))

(deftest snapshot-test
  (sim/start! {:players [1 2 3]})
  (try
    (let [a (sim/snapshot 1)
          b (sim/snapshot 2)
          c (sim/snapshot 3)]
      (is (true? (:online? a)))
      (is (string? (:key a)))
      (is (every? #(contains? a %) [:phrase :next-cue :looping :loop-beats]))
      (is (false? (:looping a)))
      (is (true? (:looping b)))
      (is (nil? (:looping c)) "player 3 pretends it cannot report loops")
      (let [moving [:played :remain :beat :phrase :next-cue]]
        (is (= (apply dissoc (state/snapshot 2) moving) (apply dissoc b moving))
            "state/snapshot passes simulated players through")))
    (finally
      (sim/stop!))))

(deftest unassigned-snapshot-test
  (is (= {:player 0} (state/snapshot 0))))
