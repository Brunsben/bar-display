;;;; Bar Display — Beat Link Trigger integration
;;;;
;;;; Live CDJ data is only available inside Beat Link Trigger. The DJ Link
;;;; protocol lets just one program per machine onto the network, so a second
;;;; process running alongside BLT would never receive a single packet from
;;;; the players. Running here costs nothing: the bar displays get their own
;;;; settings window, and after the one-time setup below you never need to
;;;; touch code again.
;;;;
;;;; 1. Copy the defn below into the Triggers window's Shared Functions
;;;;    expression, adjusting `bar-display-source` to match your machine.
;;;; 2. Add the two hooks further down to the matching expressions.

(defn bar-display-load!
  "Puts the bar display sources on the class path and loads them. Safe
  to call repeatedly: it reloads the namespaces, so edits made in an
  editor take effect without restarting Beat Link Trigger."
  []
  (let [bar-display-source "O:/Beatlink-trigger/bar-display/src"]
    (cemerick.pomegranate/add-classpath (clojure.java.io/file bar-display-source))
    (doseq [namespace '[bardisplay.theme bardisplay.sim bardisplay.state bardisplay.paint
                        bardisplay.cell bardisplay.screen bardisplay.core
                        bardisplay.config bardisplay.settings]]
      (require namespace :reload))))


;;; ---------------------------------------------------------------------------
;;; Hooks — put each of these in the matching Triggers window expression.
;;; ---------------------------------------------------------------------------

;; Global Setup Expression. Loads the code and opens the settings window.
;; Everything is configured from there, and your choices are remembered in
;; .bar-display.edn in your home folder.
#_(do (bar-display-load!)
      ((requiring-resolve 'bardisplay.settings/show!)))

;; Came Online Expression. Reopens the displays with the saved settings, so
;; they come back by themselves once the players are reachable.
#_((requiring-resolve 'bardisplay.settings/apply-saved!))

;; Global Shutdown Expression.
#_((requiring-resolve 'bardisplay.core/close!))


;;; ---------------------------------------------------------------------------
;;; Handy things to evaluate from the nREPL.
;;; ---------------------------------------------------------------------------

#_(bardisplay.settings/show!)           ; Reopen the settings window.
#_(bardisplay.screen/describe-screens)  ; Which screen is which?
#_(bardisplay.core/close!)

#_(do (bar-display-load!)               ; Pick up source edits and restart.
      (bardisplay.settings/apply-saved!))


;;; ---------------------------------------------------------------------------
;;; Working without CDJs.
;;;
;;; Tick "Simulate players" in the settings window.
;;;
;;; Beat Link Trigger's own shallow playback simulators cannot drive these
;;; displays: they hand packets straight to the trigger and show listeners and
;;; never reach the finders a live cell reads. So bardisplay.sim keeps its own
;;; clock, and the cells switch their waveform components into data mode while
;;; it is running.
;;; ---------------------------------------------------------------------------

;; Check the layout without opening anything at all:
#_((requiring-resolve 'bardisplay.dev/render-samples) "target/preview")
