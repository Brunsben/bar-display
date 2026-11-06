(ns bardisplay.render-test
  "Paints real cells offscreen, in every language, so mistakes that only
  show up while painting are caught."
  (:require [bardisplay.dev :as dev]
            [bardisplay.i18n :as i18n]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(deftest render-samples-test
  (let [directory (io/file (System/getProperty "java.io.tmpdir") "bar-display-render-test")]
    (try
      (doseq [{:keys [code]} (i18n/available)]
        (testing code
          (i18n/set-language! code)
          (let [rendered (dev/render-samples (str directory "/" code))]
            (is (seq rendered))
            (is (every? #(.exists (io/file (:file %))) rendered)))))
      (finally
        (i18n/set-language! "en")))))
