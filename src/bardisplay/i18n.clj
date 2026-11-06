(ns bardisplay.i18n
  "Translations of every text the bar displays show.

  Each language is a file `src/bardisplay/i18n/<code>.edn` mapping
  namespaced keywords to text, plus `:language/name`. To add a language,
  copy `en.edn`, translate the texts and save it under the new code; it
  is offered in the settings automatically. Texts missing from a
  language fall back to English, and then to the key's name."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as timbre])
  (:import [java.io File]
           [java.util Locale]))

(def ^:private folder
  "Where the language files are on the class path."
  "bardisplay/i18n/")

(defn- read-language
  "Reads one language file, or returns `nil` if it is missing or broken."
  [code]
  (when-let [resource (io/resource (str folder code ".edn"))]
    (try
      (edn/read-string (slurp resource))
      (catch Exception e
        (timbre/warn e "Bar display: could not read language file" code)
        nil))))

(defn available
  "Lists the languages there are files for, as `{:code :name}` maps
  sorted by name."
  []
  (let [resource (io/resource folder)
        codes    (if (and resource (= "file" (.getProtocol resource)))
                   (->> (.listFiles (io/file resource))
                        (map #(.getName ^File %))
                        (filter #(str/ends-with? % ".edn"))
                        (map #(subs % 0 (- (count %) 4))))
                   ["en" "de"])]
    (->> codes
         (keep (fn [code]
                 (when-let [texts (read-language code)]
                   {:code code :name (get texts :language/name code)})))
         (sort-by :name)
         vec)))

(defn system-language
  "The language code of the operating system's locale, such as \"de\"."
  []
  (.getLanguage (Locale/getDefault)))

(defn resolve-code
  "Decides which language to use. `setting` is a language code or
  `:auto`; `codes` the set of available codes; `system` the system's
  language code. Falls back to English."
  [setting codes system]
  (cond
    (and (string? setting) (contains? codes setting)) setting
    (and (= :auto setting) (contains? codes system))  system
    :else                                             "en"))

(defn lookup
  "Finds the text for `k` in `texts`, then in `fallback`, else uses the
  key's name."
  [texts fallback k]
  (or (get texts k) (get fallback k) (name k)))

(defonce ^{:private true
           :doc "The language in use: its code, its texts and the English fallback."}
  current
  (atom (let [english (read-language "en")]
          {:code "en" :texts english :fallback english})))

(defn current-code
  "The code of the language in use."
  []
  (:code @current))

(defn set-language!
  "Switches to the language a setting names (a code, or `:auto` for the
  system language). Returns the code now in use."
  [setting]
  (let [code    (resolve-code (or setting :auto) (set (map :code (available))) (system-language))
        english (read-language "en")]
    (reset! current {:code code :texts (or (read-language code) english) :fallback english})
    code))

(defn t
  "Returns the text for `k` in the current language, formatted with
  `args` when there are any."
  [k & args]
  (let [{:keys [texts fallback]} @current
        text                     (lookup texts fallback k)]
    (if (seq args)
      (apply format text args)
      text)))
