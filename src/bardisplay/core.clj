(ns bardisplay.core
  "Lifecycle of the bar displays. `open!` takes the panel descriptions
  built by `bardisplay.config/panels`, finds a screen for each and opens
  it with its players fixed in place. `status` reports what happened to
  each panel."
  (:require [bardisplay.screen :as screen]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

(defonce ^{:private true
           :doc "Holds the open displays, or `nil` when they are closed."}
  displays (atom nil))

(defonce ^{:private true
           :doc "One entry per configured panel describing what happened when the
  displays were last opened. Empty while closed."}
  panel-status (atom []))

(defn close!
  "Closes the bar displays and releases everything they were watching."
  []
  (reset! panel-status [])
  (when-let [open (first (reset-vals! displays nil))]
    (async/close! (:shutdown-chan open))
    (seesaw/invoke-later
      (doseq [panel (:panels open)]
        (.dispose ^JFrame (:frame panel))))
    true))

(defn- screen-label
  "Names a screen for the status display."
  [screen]
  (cond
    (= screen :window) "Window"
    (map? screen)      (:id screen)
    :else              (str screen)))

(defn- open-panel
  "Opens one panel whose screen has been resolved. Returns its status
  entry and the opened panel (or `nil`). `on-bounds`, if given, is called
  with the panel index and new bounds when a window is moved or resized."
  [shutdown-chan on-bounds index {:keys [panel screen reason]}]
  (let [entry {:index   index
               :players (:players panel)
               :wanted  (screen-label (:screen panel))
               :actual  (when screen (screen-label screen))
               :reason  reason}]
    (cond
      (empty? (:players panel))
      [(assoc entry :reason :no-players :open? false) nil]

      (nil? screen)
      [(assoc entry :open? false) nil]

      :else
      (let [screen-id (if (= screen :window) :window (:id screen))
            opened    (screen/create (cond-> (assoc panel :screen-id screen-id)
                                       on-bounds (assoc :on-bounds (partial on-bounds index)))
                                     shutdown-chan close!)]
        [(assoc entry :open? (some? opened)) opened]))))

(defn open!
  "Opens the displays described by `panels` (see
  `bardisplay.config/panels`), closing any that were already open.
  `options` may hold `:on-bounds`, called with a panel index and
  `[x y width height]` whenever a windowed panel is moved or resized.
  Returns the open displays, or `nil` if no panel could be opened."
  ([panels]
   (open! panels {}))
  ([panels {:keys [on-bounds]}]
   (close!)
   (util/load-fonts)
   (let [shutdown-chan (async/promise-chan)
         resolved      (screen/assign-screens panels (screen/describe-screens))
         results       (vec (map-indexed (partial open-panel shutdown-chan on-bounds) resolved))
         opened        (vec (keep second results))]
     (reset! panel-status (mapv first results))
     (if (seq opened)
       (do
         (reset! displays {:panels opened :shutdown-chan shutdown-chan})
         (timbre/info "Bar display: opened" (count opened) "panel(s) on" (pr-str (mapv :screen opened)))
         @displays)
       (do
         (async/close! shutdown-chan)
         (timbre/warn "Bar display: no panel could be opened.")
         nil)))))

(defn open?
  "Checks whether the bar displays are currently open."
  []
  (some? @displays))

(defn open-panels
  "Returns the open panels, each with its `:frame` and `:cells`."
  []
  (:panels @displays))

(defn status
  "Returns one entry per configured panel: `:index`, `:players`,
  `:wanted` and `:actual` screen labels, `:reason` and `:open?`."
  []
  @panel-status)
