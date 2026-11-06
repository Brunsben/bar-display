(ns bardisplay.config-test
  (:require [bardisplay.config :as config]
            [clojure.test :refer [deftest is testing]]))

(deftest parse-players-test
  (is (= {:players [1 2]} (config/parse-players "1 2")))
  (is (= {:players [3 1]} (config/parse-players " 3, 1 ")))
  (is (:error (config/parse-players "")))
  (is (:error (config/parse-players "0 2")))
  (is (:error (config/parse-players "7")))
  (is (:error (config/parse-players "a")))
  (is (:error (config/parse-players "2 2"))))

(deftest migrate-test
  (testing "old single panel settings become one panel"
    (let [m (config/migrate {:layout :row :player-count 3 :screen "\\Display2"
                             :window-bounds [1 2 3 4] :scale 6 :always-on-top? true
                             :simulate? true :warn-seconds 90 :urgent-seconds 20})]
      (is (= [{:screen {:id "\\Display2"} :layout :row :players [1 2 3] :window-bounds [1 2 3 4]}]
             (:panels m)))
      (is (= {:scale 6 :always-on-top? true :warn-seconds 90 :urgent-seconds 20} (:display m)))
      (is (true? (:simulate? m)))))
  (testing "separate windows become one panel per player"
    (let [m (config/migrate {:layout :separate :player-count 2 :screen :window
                             :window-bounds [0 0 1000 1000]})]
      (is (= [[1] [2]] (mapv :players (:panels m))))
      (is (every? #(= :window (:screen %)) (:panels m)))
      (is (apply distinct? (map :window-bounds (:panels m))))))
  (testing "new format and missing settings are left alone"
    (let [s {:panels [] :display {}}]
      (is (identical? s (config/migrate s))))
    (is (nil? (config/migrate nil)))))

(deftest normalize-test
  (let [n (config/normalize {:panels  [{:screen :window :players [1 9 1 2]}]
                             :display {:brightness 50 :blocks [:track]}})]
    (is (= [1 2] (:players (first (:panels n)))))
    (is (= :column (:layout (first (:panels n)))))
    (is (= 50 (get-in n [:display :brightness])))
    (is (= #{:track} (get-in n [:display :blocks])))
    (is (= 4 (get-in n [:display :scale])))
    (is (false? (:simulate? n))))
  (is (= 4 (count (:panels (config/normalize {:panels (repeat 6 {:players [1]})})))))
  (is (= (:panels config/defaults) (:panels (config/normalize {}))))
  (is (= (:panels config/defaults) (:panels (config/normalize nil)))))

(deftest panels-test
  (let [[a b] (config/panels
               {:panels  [{:screen :window :layout :row :players [1 2] :window-bounds [0 0 10 10]}
                          {:screen {:id "X"} :layout :grid :players [3 4 5]}]
                :display {:scale 5 :blocks #{:track} :title-scroll :off :brightness 80
                          :always-on-top? true :warn-seconds 1}})]
    (is (= {:screen :window :players [1 2] :window-bounds [0 0 10 10] :rows 1 :columns 2
            :scale 5 :blocks #{:track} :title-scroll :off :brightness 80 :always-on-top? true}
           a))
    (is (= {:rows 2 :columns 2} (select-keys b [:rows :columns])))))
