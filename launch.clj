;;;; Bar Display launcher.
;;;;
;;;; Start it with run-display.bat (double-click, or from a terminal). It opens
;;;; the settings window, where everything is configured; your choices are
;;;; remembered in .bar-display.edn in your home folder.
;;;;
;;;; Note that this standalone launcher can only show simulated players. Live
;;;; CDJ data is available only inside Beat Link Trigger, because the DJ Link
;;;; protocol lets just one program per machine onto the network. See
;;;; blt/shared_functions.clj for running it there.

(require 'bardisplay.settings)

(bardisplay.settings/show!)

(println)
(println "Settings window open. Close this terminal to quit.")

;; Keep the JVM alive while the windows are up.
@(promise)
