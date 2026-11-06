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

(def screen-check-interval
  "How often, in milliseconds, the attached screens are checked for
  changes while the displays are running."
  3000)

(declare open!)

(defn- watch-screens!
  "Reopens the displays with the same panels whenever a screen is
  plugged in or unplugged, so a panel finds its screen however late it
  is connected. Stops when `shutdown-chan` closes."
  [shutdown-chan panels options]
  (let [ids (screen/screen-ids)]
    (async/go-loop []
      (let [[_ channel] (async/alts! [shutdown-chan (async/timeout screen-check-interval)])]
        (when-not (= channel shutdown-chan)
          (if (screen/screens-changed? ids (screen/screen-ids))
            (do (timbre/info "Bar display: screens changed, reopening the displays.")
                (seesaw/invoke-later (open! panels options)))
            (recur)))))))

(defn open!
  "Opens the displays described by `panels` (see
  `bardisplay.config/panels`), closing any that were already open.
  `options` may hold `:on-bounds`, called with a panel index and
  `[x y width height]` whenever a windowed panel is moved or resized.
  Until `close!` is called, the displays are reopened whenever the
  attached screens change. Returns the open displays, or `nil` if no
  panel could be opened right now."
  ([panels]
   (open! panels {}))
  ([panels {:keys [on-bounds] :as options}]
   (close!)
   (util/load-fonts)
   (let [shutdown-chan (async/promise-chan)
         resolved      (screen/assign-screens panels (screen/describe-screens))
         results       (vec (map-indexed (partial open-panel shutdown-chan on-bounds) resolved))
         opened        (vec (keep second results))]
     (reset! panel-status (mapv first results))
     ;; Kept even when nothing opened, so the screen watch can still find them a screen.
     (reset! displays {:panels opened :shutdown-chan shutdown-chan})
     (watch-screens! shutdown-chan panels options)
     (if (seq opened)
       (do
         (timbre/info "Bar display: opened" (count opened) "panel(s) on" (pr-str (mapv :screen opened)))
         @displays)
       (do
         (timbre/warn "Bar display: no panel could be opened yet.")
         nil)))))

(defn open?
  "Checks whether any bar display is currently open."
  []
  (boolean (seq (:panels @displays))))

(defn open-panels
  "Returns the open panels, each with its `:frame` and `:cells`."
  []
  (:panels @displays))

(defn status
  "Returns one entry per configured panel: `:index`, `:players`,
  `:wanted` and `:actual` screen labels, `:reason` and `:open?`."
  []
  @panel-status)
