(ns bardisplay.core
  "Lifecycle and player assignment for the bar-type player displays.

  Open the displays with `(open!)`, close them with `(close!)`. Each
  configured panel becomes an undecorated window filling one physical
  screen, and the players currently visible on the network are
  distributed across the available cells."
  (:require [bardisplay.screen :as screen]
            [bardisplay.state :as state]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.awt GraphicsDevice GraphicsEnvironment]
           [javax.swing JFrame]
           [org.deepsymmetry.beatlink DeviceAnnouncementListener DeviceFinder]))

(def ^DeviceFinder device-finder (DeviceFinder/getInstance))

(def default-config
  "The settings used unless `open!` is given overrides.

  `:panels` describes the physical bar panels. When it is `nil` the
  panels are detected automatically (see `detect-panels`). Each entry
  takes a `:screen` (an index or platform ID string, see
  `bardisplay.screen/describe-screens`) and a `:slots` count saying how
  many players share that panel."
  {:panels         nil
   :scale          4
   :always-on-top? true})

(defonce ^{:private true
           :doc "Holds the open displays, or `nil` when they are closed."}
  displays (atom nil))

(def ^:private bar-panel-aspect
  "A screen at least this much wider than it is tall is assumed to be
  one of the bar panels rather than an ordinary monitor."
  2.5)

(defn detect-panels
  "Guesses the panel configuration by looking for attached screens that
  are much wider than they are tall and are not the primary display."
  []
  (let [^GraphicsDevice primary (.. GraphicsEnvironment getLocalGraphicsEnvironment getDefaultScreenDevice)]
    (vec (for [{:keys [id width height]} (screen/describe-screens)
               :when (and (not= id (.getIDstring primary))
                          (>= (/ (double width) (double height)) bar-panel-aspect))]
           {:screen id :rows 1 :columns 1}))))

(defn- all-cells
  "Returns every cell across all open panels, in panel order."
  [open]
  (vec (mapcat :cells (:panels open))))

(defn reassign!
  "Distributes the players currently visible on the network across the
  cells of the open displays. Cells with no player to show go idle."
  []
  (when-let [open @displays]
    (let [cells   (all-cells open)
          players (state/online-players)]
      (when (> (count players) (count cells))
        (timbre/warn "Bar display: only" (count cells) "cells for" (count players)
                     "players; not showing" (pr-str (drop (count cells) players))))
      (dotimes [i (count cells)]
        ((:set-player! (nth cells i)) (nth players i 0))))))

(defn close!
  "Closes the bar displays and releases everything they were watching."
  []
  (when-let [open (first (reset-vals! displays nil))]
    (.removeDeviceAnnouncementListener device-finder (:listener open))
    (async/close! (:shutdown-chan open))
    (seesaw/invoke-later
      (doseq [panel (:panels open)]
        (.dispose ^JFrame (:frame panel))))
    true))

(defn open!
  "Opens the bar displays, closing any that were already open. Accepts a
  map of overrides for `default-config`."
  ([]
   (open! {}))
  ([overrides]
   (close!)
   (util/load-fonts)
   (let [{:keys [panels scale always-on-top?] :as config} (merge default-config overrides)
         panels                                           (or (seq panels) (detect-panels))]
     (if-not (seq panels)
       (do (timbre/warn "Bar display: no bar panels detected. Pass :panels explicitly;"
                        "see (bardisplay.screen/describe-screens).")
           nil)
       (let [shutdown-chan (async/promise-chan)
             on-escape     #(close!)
             opened        (vec (keep (fn [panel-config]
                                        (screen/create (merge {:scale          scale
                                                               :always-on-top? always-on-top?}
                                                              panel-config)
                                                       shutdown-chan on-escape))
                                      panels))
             listener      (reify DeviceAnnouncementListener
                             (deviceFound [_ _] (reassign!))
                             (deviceLost [_ _] (reassign!)))]
         (if-not (seq opened)
           (do (async/close! shutdown-chan)
               nil)
           (do
             (reset! displays {:config        config
                               :panels        opened
                               :shutdown-chan shutdown-chan
                               :listener      listener})
             (.addDeviceAnnouncementListener device-finder listener)
             (reassign!)
             (timbre/info "Bar display: opened" (count opened) "panel(s) on"
                          (pr-str (mapv :screen opened)))
             @displays)))))))

(defn open?
  "Checks whether the bar displays are currently open."
  []
  (some? @displays))

(defn open-panels
  "Returns the open panels, each with its `:frame` and `:cells`."
  []
  (:panels @displays))

(defn toggle!
  "Opens the bar displays if they are closed, closes them if they are
  open."
  ([]
   (toggle! {}))
  ([overrides]
   (if (open?)
     (do (close!) false)
     (do (open! overrides) true))))
