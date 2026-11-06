(ns bardisplay.paint-test
  (:require [bardisplay.paint :as paint]
            [bardisplay.theme :as theme]
            [clojure.test :refer [deftest is]])
  (:import [java.awt Color]))

(deftest live-test
  (is (paint/live? {:player 1}))
  (is (paint/live? {:player 1 :online? true}))
  (is (not (paint/live? {:player 1 :online? false})))
  (is (not (paint/live? {:player 0}))))

(deftest scroll-offset-test
  (is (= 0 (paint/scroll-offset 2000 100 200)) "text that fits never moves")
  (is (= 0 (paint/scroll-offset 0 300 200)))
  (is (= 50 (paint/scroll-offset 2000 300 200)))
  (is (= 100 (paint/scroll-offset 4500 300 200)) "holds at the end")
  (is (= 0 (paint/scroll-offset 10000 300 200)) "rests at the start")
  (is (= 50 (paint/scroll-offset 32000 300 200)) "repeats every period"))

(deftest scrolling-test
  (is (paint/scrolling? 1000))
  (is (paint/scrolling? 5000))
  (is (not (paint/scrolling? 6000))))

(deftest scroll-time-test
  (is (= 1000 (paint/scroll-time 1000 0)))
  (is (= 15000 (paint/scroll-time 1000 2))))

(deftest format-countdown-test
  (is (= "0:12" (paint/format-countdown 12500)))
  (is (= "2:05" (paint/format-countdown 125000)))
  (is (= "0:00" (paint/format-countdown 0)))
  (is (= "0:00" (paint/format-countdown -5))))

(deftest info-content-test
  (let [snap {:player   1 :online? true
              :phrase   {:label "Chorus" :color Color/RED :text-color Color/BLACK :beats-left 12}
              :key      "Am"
              :looping  true :loop-beats 4
              :next-cue {:ms-left 12000 :color Color/GREEN}}]
    (is (= {:text "CHORUS · 12" :fill Color/RED :text-color Color/BLACK}
           (paint/info-content :phrase snap)))
    (is (= {:text "KEY Am" :text-color (theme/color :value)} (paint/info-content :key snap)))
    (is (= {:text "LOOP 4" :fill (theme/color :loop) :text-color Color/BLACK}
           (paint/info-content :loop snap)))
    (is (= {:text "CUE 0:12" :text-color (theme/color :value) :dot Color/GREEN}
           (paint/info-content :cue snap)))
    (is (= {:text "LOOP" :fill (theme/color :loop) :text-color Color/BLACK}
           (paint/info-content :loop (assoc snap :loop-beats 0))))
    (is (= {:text "LOOP"} (paint/info-content :loop (assoc snap :looping false))))
    (is (= {:text "LOOP –"} (paint/info-content :loop (dissoc snap :looping)))))
  (is (= {:text "PHRASE –"} (paint/info-content :phrase {:player 1})))
  (is (= {:text "KEY –"} (paint/info-content :key nil)))
  (is (= {:text "CUE –"} (paint/info-content :cue {}))))
