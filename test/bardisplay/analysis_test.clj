(ns bardisplay.analysis-test
  (:require [bardisplay.analysis :as analysis]
            [beat-link-trigger.util :as util]
            [clojure.test :refer [deftest is]]))

(def sample-phrases
  {:end-beat 100
   :entries  [{:beat 1 :label "Intro"} {:beat 33 :label "Chorus"} {:beat 65 :label "Outro"}]})

(deftest phrase-at-test
  (is (= {:beat 1 :label "Intro" :beats-left 32 :next {:beat 33 :label "Chorus"}}
         (analysis/phrase-at sample-phrases 1)))
  (is (= {:beat 1 :label "Intro" :beats-left 1 :next {:beat 33 :label "Chorus"}}
         (analysis/phrase-at sample-phrases 32)))
  (is (= {:beat 33 :label "Chorus" :beats-left 32 :next {:beat 65 :label "Outro"}}
         (analysis/phrase-at sample-phrases 33)))
  (is (= {:beat 65 :label "Outro" :beats-left 1 :next nil} (analysis/phrase-at sample-phrases 99))
      "the last phrase has nothing after it")
  (is (nil? (analysis/phrase-at sample-phrases 100)))
  (is (nil? (analysis/phrase-at sample-phrases 0)))
  (is (nil? (analysis/phrase-at sample-phrases nil)))
  (is (nil? (analysis/phrase-at nil 10))))

(deftest next-cue-test
  (let [cues [{:ms 1000 :color :a} {:ms 5000 :color :b}]]
    (is (= {:ms-left 1000 :color :a} (analysis/next-cue cues 0)))
    (is (= {:ms-left 4000 :color :b} (analysis/next-cue cues 1000)))
    (is (nil? (analysis/next-cue cues 5000)))
    (is (nil? (analysis/next-cue cues nil)))
    (is (nil? (analysis/next-cue nil 0)))))

(deftest sample-track-conversion-test
  ;; Sample track 2 shipped with Beat Link Trigger has phrase analysis and cues.
  (let [track   (util/data-for-simulation :entry 2)
        phrases (analysis/phrases (:song-structure track))
        cues    (analysis/cues (:cue-list track))]
    (is (seq (:entries phrases)))
    (is (every? string? (map :label (:entries phrases))))
    (is (apply <= (map :beat (:entries phrases))))
    (is (pos? (:end-beat phrases)))
    (is (seq cues))
    (is (apply <= (map :ms cues))))
  (is (nil? (analysis/phrases nil)))
  (is (nil? (analysis/cues nil))))
