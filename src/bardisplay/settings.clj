(ns bardisplay.settings
  "The settings window. Everything the bar displays can be adjusted from
  lives here, so nothing has to be edited in source once it is running."
  (:require [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.sim :as sim]
            [bardisplay.theme :as theme]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

(defonce ^{:private true
           :doc "Holds the settings window while it is open."}
  window (atom nil))

(defn- read-form
  "Collects the current state of the window into a settings map."
  [root]
  (let [bounds (:window-bounds (config/load-settings))]
    {:layout         (:key (seesaw/selection (seesaw/select root [:#layout])))
     :player-count   (seesaw/selection (seesaw/select root [:#players]))
     :screen         (:id (seesaw/selection (seesaw/select root [:#screen])))
     :window-bounds  bounds
     :scale          (seesaw/selection (seesaw/select root [:#scale]))
     :always-on-top? (seesaw/selection (seesaw/select root [:#on-top]))
     :simulate?      (seesaw/selection (seesaw/select root [:#simulate]))
     :warn-seconds   (seesaw/selection (seesaw/select root [:#warn]))
     :urgent-seconds (seesaw/selection (seesaw/select root [:#urgent]))}))

(defn- describe
  "Explains in words what the current settings will produce, so the
  effect of a change is obvious before applying it."
  [{:keys [layout player-count screen]}]
  (let [where (if (= screen :window) "a window" (str "screen " screen))]
    (str player-count " players, "
         (case layout
           :row      (str "side by side across " where)
           :column   (str "stacked down " where)
           :grid     (str "in a two column grid on " where)
           :separate "each in its own window")
         ".")))

(defn apply-settings!
  "Brings the displays in line with a settings map, starting or stopping
  the simulation as required. Returns the open displays, or `nil`."
  [settings]
  (theme/set-thresholds! (:warn-seconds settings) (:urgent-seconds settings))
  (core/close!)
  (if (:simulate? settings)
    (sim/start! {:players (vec (range 1 (inc (:player-count settings))))})
    (sim/stop!))
  (core/open! {:panels (config/panels settings)
               :scale  (:scale settings)}))

(defn apply-saved!
  "Opens the displays using the settings last saved from the window.
  This is what Beat Link Trigger calls when it comes online, so the
  displays return by themselves without anyone opening the window."
  []
  (apply-settings! (config/load-settings)))

(defn- apply!
  "Applies what the window currently shows, and remembers it."
  [root]
  (let [settings (read-form root)
        status   (seesaw/select root [:#status])]
    (try
      (config/save-settings! settings)
      (seesaw/config! status :text (if (apply-settings! settings)
                                     (str "Running: " (describe settings))
                                     "Nothing opened - check the screen selection."))
      (catch Throwable t
        (timbre/error t "Could not apply bar display settings.")
        (seesaw/config! status :text (str "Failed: " (.getMessage t)))))))

(defn- stop!
  "Closes the displays and stops any simulation."
  [root]
  (core/close!)
  (sim/stop!)
  (seesaw/config! (seesaw/select root [:#status]) :text "Stopped."))

(defn- build-panel
  "Lays out the settings form."
  [settings]
  (mig/mig-panel
    :constraints ["hidemode 3, fillx" "[right]12[grow,fill]" "[]8[]"]
    :items
    [[(seesaw/label :text "Arrangement") ""]
     [(seesaw/combobox :id :layout :model config/layouts) "wrap"]

     [(seesaw/label :text "Players") ""]
     [(seesaw/spinner :id :players
                      :model (seesaw/spinner-model (:player-count settings) :from 1 :to 6 :by 1))
      "wrap"]

     [(seesaw/label :text "Show on") ""]
     [(seesaw/combobox :id :screen :model (config/screen-choices)) "wrap"]

     [(seesaw/label :text "Waveform zoom") ""]
     [(seesaw/slider :id :scale :min 1 :max 16 :value (:scale settings)
                     :major-tick-spacing 5 :minor-tick-spacing 1
                     :snap-to-ticks? true :paint-labels? true)
      "wrap"]

     [(seesaw/label :text "Warn when left") ""]
     [(seesaw/spinner :id :warn
                      :model (seesaw/spinner-model (:warn-seconds settings) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text "seconds") "wrap"]

     [(seesaw/label :text "Urgent when left") ""]
     [(seesaw/spinner :id :urgent
                      :model (seesaw/spinner-model (:urgent-seconds settings) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text "seconds") "wrap"]

     [(seesaw/label :text "Options") ""]
     [(seesaw/checkbox :id :on-top :text "Keep above other windows"
                       :selected? (:always-on-top? settings))
      "wrap"]
     [(seesaw/label :text "") ""]
     [(seesaw/checkbox :id :simulate :text "Simulate players (no CDJs needed)"
                       :selected? (:simulate? settings))
      "wrap 12"]

     [(seesaw/label :id :status :text "Not running.") "span, growx, wrap"]
     [(seesaw/flow-panel :align :right
                         :items [(seesaw/button :id :stop :text "Stop")
                                 (seesaw/button :id :apply :text "Apply")])
      "span, growx"]]))

(defn show!
  "Opens the bar display settings window, bringing it to the front if it
  is already open."
  []
  (if-let [^JFrame existing @window]
    (do (.toFront existing) existing)
    (let [settings (config/load-settings)
          content  (build-panel settings)
          root     (seesaw/frame :title "Bar Display Settings"
                                 :content content
                                 :on-close :dispose
                                 :resizable? false)]
      (seesaw/selection! (seesaw/select root [:#layout]) (config/layout-for (:layout settings)))
      (when-let [choice (first (filter #(= (:id %) (:screen settings)) (config/screen-choices)))]
        (seesaw/selection! (seesaw/select root [:#screen]) choice))
      (seesaw/listen (seesaw/select root [:#apply]) :action (fn [_] (apply! root)))
      (seesaw/listen (seesaw/select root [:#stop]) :action (fn [_] (stop! root)))
      (seesaw/listen root :window-closed (fn [_] (reset! window nil)))
      (theme/set-thresholds! (:warn-seconds settings) (:urgent-seconds settings))
      (seesaw/pack! root)
      (.setLocationRelativeTo root nil)
      (seesaw/show! root)
      (reset! window root))))
