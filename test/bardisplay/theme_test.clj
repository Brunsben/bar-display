(ns bardisplay.theme-test
  (:require [bardisplay.theme :as theme]
            [clojure.test :refer [deftest is]]))

(deftest su-test
  (is (= 2 (theme/su 0.5 4)))
  (is (= 112 (theme/su 1.0 112))))

(deftest scale-test
  (is (= 1.0 (theme/scale 440)))
  (is (= 0.5 (theme/scale 220))))
