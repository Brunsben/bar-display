(ns bardisplay.status
  "Turns what the displays are doing into a few lines of text, for the
  show window and the settings window. Pure, so it can be tested."
  (:require [clojure.string :as str]))

(def ^:private level-colors
  {:ok "#2e9e44" :warn "#c98a00" :error "#d0302a"})

(defn- header
  "The first line: where the player data comes from."
  [{:keys [online? simulating? visible]}]
  (cond
    simulating? {:level :warn :text (str "Simulating players " (str/join " " (sort visible)))}
    online?     {:level :ok :text (str "BLT online (" (count visible) " players)")}
    :else       {:level :warn :text "BLT offline – displays open when it goes online"}))

(defn- player-text
  "Lists a panel's players, marking the ones not on the network."
  [visible players]
  (str "Player " (str/join "  " (map #(str % (if (contains? visible %) " ●" " ○ offline"))
                                     players))))

(defn- panel-line
  "Describes one panel's status entry from `bardisplay.core/status`."
  [visible {:keys [index wanted actual reason open? players]}]
  (let [prefix (str "Panel " (inc index) "  ")]
    (case reason
      :not-found  {:level :error :text (str prefix wanted "  NOT FOUND – panel not opened")}
      :no-players {:level :error :text (str prefix wanted "  no players set – panel not opened")}
      (let [substitute? (contains? #{:same-size :widest} reason)
            where       (if substitute? (str wanted " → " actual " (substitute)") wanted)
            missing?    (not-every? #(contains? visible %) players)]
        (if open?
          {:level (if (or substitute? missing?) :warn :ok)
           :text  (str prefix where "  running  " (player-text visible players))}
          {:level :error :text (str prefix where "  could not be opened")})))))

(defn lines
  "Builds the status lines. `state` holds `:online?`, `:simulating?`,
  `:visible` (set of player numbers present) and `:panels` (the entries
  from `bardisplay.core/status`)."
  [{:keys [panels visible] :as state}]
  (into [(header state)]
        (if (seq panels)
          (map #(panel-line visible %) panels)
          [{:level :warn :text "Displays closed"}])))

(defn- escape
  "Escapes text for use inside Swing HTML."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn html
  "Renders status lines as HTML for a Swing label, colored by level."
  [lines]
  (str "<html>"
       (str/join "<br>" (map (fn [{:keys [level text]}]
                               (str "<span style='color:" (level-colors level) "'>" (escape text) "</span>"))
                             lines))
       "</html>"))
