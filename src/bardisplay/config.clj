(ns bardisplay.config
  "The settings the bar displays are driven by: their shape, migrating
  older settings files, checking what the user typed, and loading and
  saving them so they survive a restart.

  Settings look like this:

      {:panels    [{:screen        {:id \"\\\\Display2\" :width 1920 :height 440 :x 0 :y 0}
                                   ; or :window for an ordinary window
                    :layout        :row          ; :row, :column or :grid
                    :players       [1 2]         ; fixed, in cell order
                    :window-bounds [80 40 1500 1100]}]
       :display   {:blocks #{...} :brightness 100 :title-scroll :off
                   :warn-seconds 60 :urgent-seconds 30 :scale 4 :always-on-top? false}
       :simulate? false}"
  (:require [bardisplay.cell :as cell]
            [bardisplay.i18n :refer [t]]
            [bardisplay.screen :as screen]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]))

(def settings-file
  "Where the settings are kept between sessions."
  (io/file (System/getProperty "user.home") ".bar-display.edn"))

(def max-panels
  "How many panels can be configured."
  4)

(def default-panel
  "A panel as it starts out: an ordinary, bar shaped window with four
  players stacked. Once moved or resized, the window remembers where it
  was (see `save-window-bounds!`)."
  {:screen        :window
   :layout        :column
   :players       [1 2 3 4]
   :window-bounds [80 40 1920 480]})

(def defaults
  "The settings used until the user changes them."
  {:panels    [default-panel]
   :display   {:blocks         cell/all-blocks
               :brightness     100
               :title-scroll   :off
               :warn-seconds   60
               :urgent-seconds 30
               :scale          4
               :always-on-top? false
               :phrase-unit     :bars
               :dim-off-air?    true
               :flash-downbeat? false}
   :simulate? false})

(defrecord Layout [key label describe]
  Object
  (toString [_] (t label)))

(def layouts
  "The ways a panel's cells can be arranged. `label` is the text key;
  `describe` turns a player count into the grid of rows and columns."
  [(->Layout :row :layout/row (fn [n] {:rows 1 :columns n}))
   (->Layout :column :layout/column (fn [n] {:rows n :columns 1}))
   (->Layout :grid :layout/grid (fn [n] {:rows (long (Math/ceil (/ n 2.0))) :columns (min n 2)}))])

(defn layout-for
  "Looks up a layout by its key."
  [k]
  (or (first (filter #(= k (:key %)) layouts))
      (first layouts)))

(defrecord ScreenChoice [id label spec]
  Object
  (toString [_] label))

(defn screen-choices
  "Returns the targets a panel can be sent to: an ordinary window, or any
  of the attached screens. `spec` is what gets saved."
  []
  (into [(->ScreenChoice :window (t :screen/window) :window)]
        (map (fn [{:keys [id width height x y]}]
               (->ScreenChoice id (format "%s  -  %d x %d" id width height)
                               {:id id :width width :height height :x x :y y}))
             (screen/describe-screens))))

(defn- cascade-bounds
  "Spreads separate windows down the screen so they do not cover each
  other."
  [[x y w h] index count]
  (let [height (max 120 (long (/ (- h (* 34 (dec count))) count)))]
    [x (+ y (* index (+ height 34))) w height]))

(defn migrate
  "Converts settings saved by the single panel version into the panel
  list form. Anything already in the new form (or `nil`) is returned
  unchanged."
  [settings]
  (if (or (nil? settings) (contains? settings :panels))
    settings
    (let [{:keys [layout player-count screen window-bounds]} settings
          n      (or player-count 4)
          target (if (string? screen) {:id screen} :window)
          bounds (or window-bounds (:window-bounds default-panel))
          panels (if (= layout :separate)
                   (vec (for [index (range n)]
                          {:screen        target
                           :layout        :row
                           :players       [(inc index)]
                           :window-bounds (cascade-bounds bounds index n)}))
                   [{:screen        target
                     :layout        (if (#{:row :column :grid} layout) layout :column)
                     :players       (vec (range 1 (inc n)))
                     :window-bounds bounds}])]
      {:panels    panels
       :display   (into {} (filter (comp some? val))
                        (select-keys settings [:scale :always-on-top? :warn-seconds :urgent-seconds]))
       :simulate? (boolean (:simulate? settings))})))

(defn clean-players
  "Keeps only valid player numbers (1-6), each once, in order."
  [players]
  (->> players
       (filter #(and (integer? %) (<= 1 % 6)))
       distinct
       vec))

(defn parse-players
  "Reads the player list typed for a panel, such as `1 2` or `3, 4`.
  Returns `{:players [...]}` or `{:error \"...\"}`."
  [s]
  (let [tokens  (remove str/blank? (str/split (or s "") #"[\s,;]+"))
        numbers (map parse-long tokens)]
    (cond
      (empty? tokens)
      {:error (t :players/none)}

      (some #(or (nil? %) (not (<= 1 % 6))) numbers)
      {:error (t :players/range)}

      (not= (count numbers) (count (distinct numbers)))
      {:error (t :players/twice)}

      :else
      {:players (vec numbers)})))

(def preset-keys
  "What a preset holds: everything except the language and the presets
  themselves."
  [:panels :display :simulate?])

(defn normalize
  "Fills in defaults and drops anything invalid, so the rest of the code
  can rely on the settings' shape."
  [settings]
  (let [display (merge (:display defaults) (:display settings))]
    {:panels    (->> (or (seq (:panels settings)) (:panels defaults))
                     (take max-panels)
                     (mapv #(update (merge default-panel %) :players clean-players)))
     :display   (update display :blocks set)
     :simulate? (boolean (:simulate? settings))
     :language  (or (:language settings) :auto)
     :presets   (into {} (map (fn [[preset-name preset]]
                                [preset-name (select-keys (normalize (dissoc preset :presets)) preset-keys)]))
                      (:presets settings))}))

(defn save-preset
  "Stores the current panels, display options and simulation under
  `preset-name`."
  [settings preset-name]
  (assoc-in settings [:presets preset-name] (select-keys settings preset-keys)))

(defn load-preset
  "Replaces the panels, display options and simulation with those of a
  preset. Unknown names change nothing."
  [settings preset-name]
  (if-let [preset (get-in settings [:presets preset-name])]
    (normalize (merge settings preset))
    settings))

(defn delete-preset
  "Forgets a preset."
  [settings preset-name]
  (update settings :presets dissoc preset-name))

(defn panels
  "Turns the settings into the panel descriptions `bardisplay.core/open!`
  expects: each panel's screen, players and grid, plus the display
  options every panel shares."
  [{:keys [panels display]}]
  (mapv (fn [{:keys [layout players] :as panel}]
          (merge (select-keys panel [:screen :players :window-bounds])
                 ((:describe (layout-for layout)) (max 1 (count players)))
                 (select-keys display [:scale :blocks :title-scroll :brightness :always-on-top?
                                       :phrase-unit :dim-off-air? :flash-downbeat?])))
        panels))

(defn load-settings
  "Reads the saved settings, migrating older files and falling back to
  the defaults for anything missing or unreadable."
  []
  (normalize
   (migrate
    (when (.exists settings-file)
      (try
        (edn/read-string {:readers {}} (slurp settings-file))
        (catch Exception e
          (timbre/warn e "Could not read" (str settings-file) "- using defaults.")
          nil))))))

(defn save-settings!
  "Writes the settings out so the next session starts the same way."
  [settings]
  (try
    (spit settings-file (pr-str (select-keys settings [:panels :display :simulate? :language :presets])))
    (str settings-file)
    (catch Exception e
      (timbre/error e "Could not save" (str settings-file))
      nil)))

(defn update-window-bounds
  "Records where the window of panel `index` now is, if that panel is an
  ordinary window."
  [settings index bounds]
  (if (= :window (get-in settings [:panels index :screen]))
    (assoc-in settings [:panels index :window-bounds] (vec bounds))
    settings))

(defn save-window-bounds!
  "Remembers a moved or resized window, so it opens there next time."
  [index bounds]
  (let [settings (load-settings)
        updated  (update-window-bounds settings index bounds)]
    (when (not= settings updated)
      (save-settings! updated))))
