(ns bardisplay.screen-test
  (:require [bardisplay.screen :as screen]
            [clojure.test :refer [deftest is testing]]))

(def screens
  [{:id "A" :width 2560 :height 1440 :x 0 :y 0 :primary? true}
   {:id "B" :width 1920 :height 440 :x 2560 :y 0 :primary? false}
   {:id "C" :width 1920 :height 440 :x 4480 :y 0 :primary? false}
   {:id "D" :width 1920 :height 720 :x 6400 :y 0 :primary? false}])

(deftest resolve-screen-test
  (is (= {:screen :window :reason :window} (screen/resolve-screen :window screens #{})))
  (is (= {:screen (screens 1) :reason :exact}
         (screen/resolve-screen {:id "B" :width 1920 :height 440} screens #{})))
  (testing "same size, closest position"
    (is (= {:screen (screens 2) :reason :same-size}
           (screen/resolve-screen {:id "Z" :width 1920 :height 440 :x 4400 :y 0} screens #{}))))
  (testing "a taken screen is skipped"
    (is (= {:screen (screens 2) :reason :same-size}
           (screen/resolve-screen {:id "B" :width 1920 :height 440 :x 2560 :y 0} screens #{"B"}))))
  (testing "widest bar shaped, never the primary"
    (is (= {:screen (screens 1) :reason :widest}
           (screen/resolve-screen {:id "Z" :width 3840 :height 600} screens #{})))
    (is (= {:screen (screens 1) :reason :widest}
           (screen/resolve-screen {:id "Z"} screens #{}))))
  (testing "nothing left"
    (is (= {:screen nil :reason :not-found}
           (screen/resolve-screen {:id "Z"} screens #{"B" "C" "D"})))))

(deftest assign-screens-test
  (let [wanted  {:id "B" :width 1920 :height 440 :x 2560 :y 0}
        [a b c] (screen/assign-screens [{:screen wanted} {:screen wanted} {:screen :window}] screens)]
    (is (= ["B" :exact] [(:id (:screen a)) (:reason a)]))
    (is (= ["C" :same-size] [(:id (:screen b)) (:reason b)]))
    (is (= :window (:screen c)))
    (is (= {:screen :window} (:panel c)))))

(deftest screens-changed-test
  (is (not (screen/screens-changed? ["A" "B"] ["B" "A"])) "order does not matter")
  (is (screen/screens-changed? ["A"] ["A" "B"]) "a screen was plugged in")
  (is (screen/screens-changed? ["A" "B"] ["A"]) "a screen was unplugged"))

(deftest dim-alpha-test
  (is (zero? (screen/dim-alpha 100)))
  (is (zero? (screen/dim-alpha nil)))
  (is (< 0.49 (screen/dim-alpha 50) 0.51))
  (is (< 0.79 (screen/dim-alpha 5) 0.81) "clamped to 20 % brightness"))
