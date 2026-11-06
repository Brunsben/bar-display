;;;; Runs the bar display tests. Start it with run-tests.bat. Pass test
;;;; namespace names to run only those, for example:
;;;;   run-tests.bat bardisplay.config-test

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.test :as t])

(def test-root
  (.getCanonicalFile (io/file (.getParentFile (.getCanonicalFile (io/file *file*))) "test")))

(defn- namespace-of
  "Turns a test file path into its namespace symbol."
  [^java.io.File f]
  (-> (str (.relativize (.toPath test-root) (.toPath (.getCanonicalFile f))))
      (str/replace #"\.clj$" "")
      (str/replace #"[\\/]" ".")
      (str/replace "_" "-")
      symbol))

(let [requested  (map symbol *command-line-args*)
      found      (->> (file-seq test-root)
                      (filter #(str/ends-with? (.getName ^java.io.File %) "_test.clj"))
                      (map namespace-of)
                      sort)
      namespaces (if (seq requested) requested found)]
  (apply require namespaces)
  (let [{:keys [fail error]} (apply t/run-tests namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
