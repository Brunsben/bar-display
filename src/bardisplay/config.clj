(ns bardisplay.config
  "The settings the bar displays are driven by, the layout presets they
  offer, and loading and saving them so they survive a restart."
  (:require [bardisplay.screen :as screen]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre]))

(def settings-file
  "Where the settings are kept between sessions."
  (io/file (System/getProperty "user.home") ".bar-display.edn"))

(def defaults
  "The settings used until the user changes them."
  {:layout         :column
   :player-count   4
   :screen         :window
   :window-bounds  [80 40 1500 1100]
   :scale          4
   :always-on-top? false
   :simulate?      false
   :warn-seconds   60
   :urgent-seconds 30})

(defrecord Layout [key label describe]
  Object
  (toString [_] label))

(def layouts
  "The ways the player cells can be arranged. `describe` turns a player
  count into the grid of rows and columns to use."
  [(->Layout :row "Side by side" (fn [n] {:rows 1 :columns n}))
   (->Layout :column "Stacked" (fn [n] {:rows n :columns 1}))
   (->Layout :grid "Grid (two per row)" (fn [n] {:rows (long (Math/ceil (/ n 2.0))) :columns (min n 2)}))
   (->Layout :separate "One window per player" (fn [_] {:rows 1 :columns 1}))])

(defn layout-for
  "Looks up a layout by its key."
  [k]
  (or (first (filter #(= k (:key %)) layouts))
      (first layouts)))

(defrecord ScreenChoice [id label]
  Object
  (toString [_] label))

(defn screen-choices
  "Returns the targets a panel can be sent to: an ordinary window, or any
  of the attached screens."
  []
  (into [(->ScreenChoice :window "Window (not full screen)")]
        (map (fn [{:keys [id width height]}]
               (->ScreenChoice id (format "%s  -  %d x %d" id width height)))
             (screen/describe-screens))))

(defn- cascade-bounds
  "Spreads separate windows down the screen so they do not cover each
  other."
  [[x y w h] index count]
  (let [height (max 120 (long (/ (- h (* 34 (dec count))) count)))]
    [x (+ y (* index (+ height 34))) w height]))

(defn panels
  "Turns the settings into the panel descriptions `bardisplay.core/open!`
  expects."
  [{:keys [layout player-count screen window-bounds always-on-top?]}]
  (let [{:keys [describe]} (layout-for layout)
        common             {:screen screen :always-on-top? always-on-top?}]
    (if (= layout :separate)
      ;; Separate windows only make sense one per screen, so when they are all
      ;; going to the same place we cascade them instead of stacking them up.
      (vec (for [index (range player-count)]
             (merge common {:rows 1 :columns 1}
                    (when (= screen :window)
                      {:window-bounds (cascade-bounds window-bounds index player-count)}))))
      [(merge common (describe player-count) {:window-bounds window-bounds})])))

(defn load-settings
  "Reads the saved settings, falling back to the defaults for anything
  missing or unreadable."
  []
  (merge defaults
         (when (.exists settings-file)
           (try
             (edn/read-string {:readers {}} (slurp settings-file))
             (catch Exception e
               (timbre/warn e "Could not read" (str settings-file) "- using defaults.")
               nil)))))

(defn save-settings!
  "Writes the settings out so the next session starts the same way."
  [settings]
  (try
    (spit settings-file (pr-str (select-keys settings (keys defaults))))
    (str settings-file)
    (catch Exception e
      (timbre/error e "Could not save" (str settings-file))
      nil)))
