(ns bardisplay.i18n-test
  (:require [bardisplay.i18n :as i18n]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- texts [code]
  (edn/read-string (slurp (io/resource (str "bardisplay/i18n/" code ".edn")))))

(deftest lookup-test
  (is (= "Hallo" (i18n/lookup {:a/b "Hallo"} {:a/b "Hello"} :a/b)))
  (is (= "Hello" (i18n/lookup {} {:a/b "Hello"} :a/b)) "falls back to English")
  (is (= "b" (i18n/lookup {} {} :a/b)) "falls back to the key name"))

(deftest resolve-code-test
  (is (= "de" (i18n/resolve-code "de" #{"en" "de"} "fr")))
  (is (= "de" (i18n/resolve-code :auto #{"en" "de"} "de")) "automatic uses the system language")
  (is (= "en" (i18n/resolve-code :auto #{"en" "de"} "fr")) "unknown system language gives English")
  (is (= "en" (i18n/resolve-code "xx" #{"en" "de"} "fr")) "a missing file gives English")
  (is (= "en" (i18n/resolve-code nil #{"en" "de"} "fr"))))

(deftest t-test
  (testing "English is the default"
    (is (= "Displays closed" (i18n/t :status/closed))))
  (is (= "Panel 3" (i18n/t :status/panel 3)) "formats arguments"))

(deftest translations-complete-test
  (let [english (texts "en")
        german  (texts "de")]
    (is (empty? (remove (set (keys german)) (keys english))) "every English key is translated to German")
    (is (string? (:language/name german)))))

(deftest available-test
  (let [codes (set (map :code (i18n/available)))]
    (is (contains? codes "en"))
    (is (contains? codes "de"))))
