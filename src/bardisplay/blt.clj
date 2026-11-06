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
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]))

(def ^:private load-order
  "The bar display namespaces, each after the ones it depends on."
  '[bardisplay.theme bardisplay.sim bardisplay.state bardisplay.paint bardisplay.cell
    bardisplay.screen bardisplay.core bardisplay.config bardisplay.settings bardisplay.blt])

(defn reload!
  "Reloads every bar display namespace from source. The show calls this
  when it is reopened in a running Beat Link Trigger, so edits (or a
  `git pull`) take effect without restarting."
  []
  (core/close!)
  (sim/stop!)
  (doseq [n load-order]
    (require n :reload)))

(defn- show-panel
  "Builds what the show window displays in place of the track list: a
  short explanation and the buttons needed to drive the displays."
  []
  (mig/mig-panel
    :constraints ["insets 16, fillx" "[grow,fill]" "[]12[]"]
    :items
    [[(seesaw/label :text (str "<html><b>Bar Display</b><br><br>"
                               "The displays open by themselves when Beat Link Trigger goes online, "
                               "using the saved settings.</html>"))
      "wrap"]
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
  track list with the bar display controls."
  [show]
  ((requiring-resolve 'beat-link-trigger.show/block-tracks) show (show-panel)))

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
  (core/close!)
  (sim/stop!))

(comment
  ;; Handy things to evaluate from Beat Link Trigger's nREPL.
  (settings/show!)                                     ; Reopen the settings window.
  ((requiring-resolve 'bardisplay.screen/describe-screens)) ; Which screen is which?
  (core/close!)
  ((requiring-resolve 'bardisplay.dev/render-samples) "target/preview") ; Check the layout offline.
  )
