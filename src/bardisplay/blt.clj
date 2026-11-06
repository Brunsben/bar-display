(ns bardisplay.blt
  "The glue between the bar displays and Beat Link Trigger.

  BarDisplay.bls, at the top of the repository, is a Beat Link Trigger
  show whose expressions do nothing but put the `src` folder next to it
  on the class path and call the functions below. All the real logic
  lives here, so the show file itself almost never needs to change.

  Open the show once with File → Open Show; Beat Link Trigger reopens
  it by itself every time it starts."
  (:require [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.settings :as settings]
            [bardisplay.sim :as sim]
            [bardisplay.status :as status]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [java.awt.event ActionListener]
           [javax.swing Timer]))

(def ^:private load-order
  "The bar display namespaces, each after the ones it depends on."
  '[bardisplay.theme bardisplay.analysis bardisplay.sim bardisplay.state bardisplay.paint
    bardisplay.cell bardisplay.screen bardisplay.core bardisplay.config bardisplay.status
    bardisplay.settings bardisplay.blt])

(defonce ^{:private true
           :doc "The timer refreshing the status in the show window."}
  status-timer
  (atom nil))

(defn- stop-status-timer!
  "Stops refreshing the show window status."
  []
  (when-let [^Timer timer (first (reset-vals! status-timer nil))]
    (.stop timer)))

(defn- start-status-timer!
  "Refreshes `label` with the current status every second."
  [label]
  (stop-status-timer!)
  (let [timer (Timer. 1000 (reify ActionListener
                             (actionPerformed [_ _]
                               (try
                                 (seesaw/config! label :text (status/html (settings/status-lines)))
                                 (catch Throwable t
                                   (timbre/error t "Bar display: could not update the status."))))))]
    (.setInitialDelay timer 0)
    (.start timer)
    (reset! status-timer timer)))

(defn reload!
  "Reloads every bar display namespace from source. The show calls this
  when it is reopened in a running Beat Link Trigger, so edits (or a
  `git pull`) take effect without restarting."
  []
  (stop-status-timer!)
  (core/close!)
  (sim/stop!)
  (doseq [n load-order]
    (require n :reload)))

(defn- show-panel
  "Builds what the show window displays in place of the track list: the
  current status and the buttons needed to drive the displays."
  [status-label]
  (mig/mig-panel
    :constraints ["insets 16, fillx" "[grow,fill]" "[]12[]12[]"]
    :items
    [[(seesaw/label :text (str "<html><b>Bar Display</b><br>"
                               "The displays open by themselves when Beat Link Trigger goes online, "
                               "using the saved settings.</html>"))
      "wrap"]
     [status-label "wrap"]
     [(seesaw/flow-panel
        :align :left
        :items [(seesaw/button :text "Settings…"
                               :listen [:action (fn [_] (settings/show!))])
                (seesaw/button :text "Open Displays"
                               :listen [:action (fn [_] (settings/apply-saved!))])
                (seesaw/button :text "Close Displays"
                               :listen [:action (fn [_] (core/close!) (sim/stop!))])])
      "wrap"]]))

(defn setup!
  "Called from the show's Global Setup Expression. Replaces the show's
  track list with the bar display controls and status."
  [show]
  (let [status-label (seesaw/label :text "")]
    ((requiring-resolve 'beat-link-trigger.show/block-tracks) show (show-panel status-label))
    (start-status-timer! status-label)))

(defn online!
  "Called from the show's Came Online Expression. Opens the displays with
  the saved settings. Real players are now reachable, so a simulation
  left switched on from testing at home is ignored."
  []
  (let [saved (config/load-settings)]
    (when (:simulate? saved)
      (timbre/info "Bar display: online, ignoring the saved Simulate players setting."))
    (settings/apply-settings! (assoc saved :simulate? false))))

(defn shutdown!
  "Called from the show's Global Shutdown Expression."
  []
  (stop-status-timer!)
  (core/close!)
  (sim/stop!))

(comment
  ;; Handy things to evaluate from Beat Link Trigger's nREPL.
  (settings/show!)                                          ; Reopen the settings window.
  ((requiring-resolve 'bardisplay.screen/describe-screens)) ; Which screen is which?
  (core/status)                                             ; What happened to each panel?
  (core/close!)
  ((requiring-resolve 'bardisplay.dev/render-samples) "target/preview") ; Check the layout offline.
  )
