(ns bardisplay.theme
  "Colors, fonts and layout metrics for the bar-type player displays.

  All layout values are expressed in *design units*, based on a
  reference cell height of 440 pixels (the native height of the 11.3\"
  1920x440 panels). At runtime every value is multiplied by a scale
  factor derived from the actual cell height, so the same code renders
  correctly on panels of any size."
  (:require [beat-link-trigger.util :as util])
  (:import [java.awt Color Font Graphics2D RenderingHints]))

(def reference-height
  "The cell height, in pixels, that the design units are based on."
  440.0)

(def header-height
  "The height, in design units, of the header block. The painters draw
  into that block rather than into the whole cell, so they scale
  against this rather than `reference-height`."
  112.0)

(def colors
  "The palette used throughout the display. Tuned for panels mounted
  above a lighting desk, where the surroundings are dark."
  {:background    Color/BLACK
   :idle          (Color. 55 55 55)
   :outline       (Color. 80 80 80)
   :label         (Color. 150 150 150)
   :title         Color/WHITE
   :artist        (Color. 110 220 140)
   :playing       (Color. 0 225 90)
   :on-air        (Color. 235 45 45)
   :master        (Color. 255 145 0)
   :sync          (Color. 90 170 255)
   :bpm-only      (Color. 235 215 60)
   :value         Color/WHITE
   :remain-warn   (Color. 255 185 0)
   :remain-urgent (Color. 255 70 70)
   :wave-emphasis (Color. 235 45 45)
   :wave-tick     Color/WHITE
   :loop          (Color. 120 220 60)})

(defn color
  "Looks up a palette entry."
  ^Color [k]
  (get colors k Color/WHITE))

(defonce ^{:doc "How much of a track has to be left, in milliseconds, before
  the remaining time is shown as a warning and then as urgent."}
  thresholds
  (atom {:warn 60000 :urgent 30000}))

(defn set-thresholds!
  "Sets the remaining time warning levels, given in seconds."
  [warn-seconds urgent-seconds]
  (reset! thresholds {:warn   (* 1000 (long warn-seconds))
                      :urgent (* 1000 (long urgent-seconds))}))

(def metrics
  "Widths and heights of the header blocks, in design units. The
  `:compact` variants are used when a cell is too narrow to fit the
  full layout, which happens when two players share one panel."
  {:margin       10
   :gap          6
   :header       112
   :preview      74
   :badge        {:full 104 :compact 92}
   :status       {:full 150 :compact 104}
   :tempo        {:full 230 :compact 178}
   :time         {:full 330 :compact 214}
   :info         {:full 300 :compact 220}
   ;; The narrowest the track title is squeezed to when the header grows to fill a cell.
   :min-track    200
   ;; Below this many design units of width, a cell switches to compact mode.
   :compact-below 1250})

(defn scale
  "Returns the factor by which design units must be multiplied for a
  cell of the given pixel height."
  ^double [height]
  (/ (double height) reference-height))

(defn block-scale
  "Returns the factor by which design units must be multiplied inside a
  header block of the given pixel height."
  ^double [height]
  (/ (double height) header-height))

(defn su
  "Scales a design unit value to pixels, rounding to an integer."
  ^long [^double s v]
  (Math/round (* s (double v))))

(defn font
  "Looks up one of Beat Link Trigger's display fonts at a size given in
  design units, scaled for the cell. `k` is `:orbitron`, `:segment` or
  `:teko`."
  ^Font [k style ^double s size]
  (util/get-display-font k style (max 1 (su s size))))

(defn prepare
  "Applies the rendering hints we want for every painter."
  [^Graphics2D g]
  (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
  (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
  (.setRenderingHint g RenderingHints/KEY_STROKE_CONTROL RenderingHints/VALUE_STROKE_PURE)
  g)
