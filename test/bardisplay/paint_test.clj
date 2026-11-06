(ns bardisplay.paint-test
  (:require [bardisplay.paint :as paint]
            [bardisplay.theme :as theme]
            [clojure.test :refer [deftest is testing]])
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

(deftest phrase-count-test
  (is (= [3 :cell/bars] (paint/phrase-count 12 :bars)))
  (is (= [3 :cell/bars] (paint/phrase-count 9 :bars)) "bars are rounded up")
  (is (= [1 :cell/bar] (paint/phrase-count 4 :bars)))
  (is (= [12 :cell/beats] (paint/phrase-count 12 :beats)))
  (is (= [1 :cell/beat-unit] (paint/phrase-count 1 :beats))))

(deftest phrase-content-test
  (let [phrase {:label "Chorus" :color Color/RED :text-color Color/BLACK :beats-left 12 :next {:label "Down"}}
        snap   {:player 1 :phrase phrase}]
    (is (= {:text "CHORUS → DOWN · 3 bars" :fill Color/RED :text-color Color/BLACK}
           (paint/info-content :phrase snap {:phrase-unit :bars})))
    (is (= "CHORUS → DOWN · 12 beats"
           (:text (paint/info-content :phrase snap {:phrase-unit :beats}))))
    (is (= "CHORUS → END · 3 bars"
           (:text (paint/info-content :phrase (assoc-in snap [:phrase :next] nil) {}))))))

(deftest info-slots-test
  (testing "without phrase, a two by two grid"
    (is (= [[:key 0 0 146 52] [:loop 154 0 146 52] [:cue 0 60 146 52]]
           (paint/info-slots [:key :loop :cue] 300 112 8))))
  (testing "phrase takes the whole top row"
    (is (= [[:phrase 0 0 300 52] [:key 0 60 146 52] [:cue 154 60 146 52]]
           (paint/info-slots [:phrase :key :cue] 300 112 8)))
    (is (= [[:phrase 0 0 300 52] [:key 0 60 94 52] [:loop 102 60 94 52] [:cue 204 60 94 52]]
           (paint/info-slots [:phrase :key :loop :cue] 300 112 8)))
    (is (= [[:phrase 0 0 300 52]] (paint/info-slots [:phrase] 300 112 8)))))

(deftest info-content-test
  (let [snap {:player   1 :online? true
              :key      "Am"
              :looping  true :loop-beats 4
              :next-cue {:ms-left 12000 :color Color/GREEN}}]
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
