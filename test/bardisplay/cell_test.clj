(ns bardisplay.cell-test
  (:require [bardisplay.cell :as cell]
            [bardisplay.theme :as theme]
            [clojure.test :refer [deftest is testing]]))

(defn- overlap?
  [[ax ay aw ah] [bx by bw bh]]
  (and (< ax (+ bx bw)) (< bx (+ ax aw)) (< ay (+ by bh)) (< by (+ ay ah))))

(def sizes
  ;; The last ones are tall, narrow cells, as in side by side windows that are higher than wide.
  [[1920 440] [960 440] [1500 275] [800 300] [500 1100] [320 600] [640 480]])

(def block-sets
  [cell/all-blocks
   #{:track :time :detail}
   #{:track :status :tempo :time :phrase :key :loop :cue}
   #{}
   #{:detail}
   #{:preview}])

(deftest layout-fits-test
  (doseq [[w h] sizes
          blocks block-sets]
    (let [rects (cell/layout w h (cell/layout-blocks blocks))]
      (testing (str w "x" h " " (sort blocks))
        (is (contains? rects :badge))
        (doseq [[k [x y rw rh]] rects]
          (is (and (>= x 0) (>= y 0) (>= rw 0) (>= rh 0)) (str k " has negative geometry"))
          (is (<= (+ x rw) w) (str k " sticks out on the right"))
          (is (<= (+ y rh) h) (str k " sticks out at the bottom")))
        (doseq [[ka a] rects
                [kb b] rects
                :when (neg? (compare ka kb))]
          (is (not (overlap? a b)) (str ka " overlaps " kb)))))))

(deftest layout-blocks-test
  (is (contains? (cell/layout-blocks #{:key}) :info))
  (is (not (contains? (cell/layout-blocks #{:track}) :info))))

(deftest hidden-blocks-test
  (is (= #{:badge :track :time} (set (keys (cell/layout 1920 440 (cell/layout-blocks #{:track :time})))))))

(deftest header-grows-without-waveforms-test
  (let [with    (cell/layout 1920 440 (cell/layout-blocks cell/all-blocks))
        without (cell/layout 1920 440 (cell/layout-blocks #{:track :time}))]
    (is (> (get-in without [:badge 3]) (get-in with [:badge 3])))))

(deftest narrow-cells-drop-blocks-test
  (testing "a narrow cell keeps readable text by leaving blocks out, tempo first"
    (let [rects   (cell/layout 500 300 (cell/layout-blocks cell/all-blocks))
          natural (theme/su (theme/scale 300) (:header theme/metrics))]
      (is (not (contains? rects :tempo)))
      (is (contains? rects :badge))
      (is (contains? rects :track))
      (is (>= (get-in rects [:badge 3]) (* 0.8 natural)))))
  (testing "a tall cell does not blow the header up and lose blocks over it"
    (let [rects (cell/layout 740 470 (cell/layout-blocks #{:track :time :loop :cue :detail :preview}))]
      (is (contains? rects :time))
      (is (<= (get-in rects [:badge 3]) (:header theme/metrics)))))
  (testing "a wide cell keeps everything"
    (is (= #{:badge :track :info :beat :status :tempo :time :detail :preview}
           (set (keys (cell/layout 1920 440 (cell/layout-blocks cell/all-blocks))))))))

(deftest dim-test
  (let [off-air {:player 1 :online? true :on-air false}]
    (is (cell/dim? off-air true true))
    (is (not (cell/dim? (assoc off-air :on-air true) true true)) "the deck on air stays bright")
    (is (not (cell/dim? off-air false true)) "nothing is dimmed when no deck reports on air")
    (is (not (cell/dim? off-air true false)) "switched off")
    (is (not (cell/dim? {:player 1 :online? false} true true)) "offline cells are grey already")
    (is (not (cell/dim? {:player 0} true true)))))

(deftest downbeat-test
  (is (cell/downbeat? {:player 1 :beat 4} {:player 1 :beat 1}))
  (is (not (cell/downbeat? {:player 1 :beat 1} {:player 1 :beat 1})) "only on the change")
  (is (not (cell/downbeat? {:player 1 :beat 1} {:player 1 :beat 2})))
  (is (not (cell/downbeat? {:player 1 :beat 4} {:player 1 :online? false :beat 1}))))

(deftest single-waveform-takes-the-space-test
  (is (> (get-in (cell/layout 1920 440 #{:preview}) [:preview 3]) 200))
  (is (> (get-in (cell/layout 1920 440 #{:detail}) [:detail 3]) 200)))
