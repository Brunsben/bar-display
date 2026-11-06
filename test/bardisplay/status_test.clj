(ns bardisplay.status-test
  (:require [bardisplay.status :as status]
            [clojure.test :refer [deftest is testing]]))

(deftest lines-test
  (testing "offline with nothing open"
    (is (= [{:level :warn :text "BLT offline – displays open when it goes online"}
            {:level :warn :text "Displays closed"}]
           (status/lines {:online? false :simulating? false :visible #{} :panels []}))))
  (testing "simulation header"
    (is (= {:level :warn :text "Simulating players 1 2"}
           (first (status/lines {:online? false :simulating? true :visible #{2 1} :panels []})))))
  (testing "panel states"
    (let [[head ok sub missing empty-panel]
          (status/lines {:online? true :simulating? false :visible #{1 2 3}
                         :panels  [{:index 0 :wanted "\\Display2" :actual "\\Display2" :reason :exact
                                    :open? true :players [1 2]}
                                   {:index 1 :wanted "\\Display3" :actual "\\Display4" :reason :same-size
                                    :open? true :players [3 4]}
                                   {:index 2 :wanted "\\Display5" :actual nil :reason :not-found
                                    :open? false :players [5]}
                                   {:index 3 :wanted "Window" :actual "Window" :reason :no-players
                                    :open? false :players []}]})]
      (is (= {:level :ok :text "BLT online (3 players)"} head))
      (is (= {:level :ok :text "Panel 1  \\Display2  running  Player 1 ●  2 ●"} ok))
      (is (= {:level :warn
              :text  "Panel 2  \\Display3 → \\Display4 (substitute)  running  Player 3 ●  4 ○ offline"}
             sub))
      (is (= {:level :error :text "Panel 3  \\Display5  NOT FOUND – panel not opened"} missing))
      (is (= {:level :error :text "Panel 4  Window  no players set – panel not opened"} empty-panel)))))

(deftest html-test
  (is (= (str "<html><span style='color:#2e9e44'>a &lt;b&gt; &amp;</span><br>"
              "<span style='color:#d0302a'>c</span></html>")
         (status/html [{:level :ok :text "a <b> &"} {:level :error :text "c"}]))))
