(ns bardisplay.paint
  "Resolution independent painters for the pieces of a player cell.

  Beat Link Trigger's own Player Status painters draw at hard coded
  pixel coordinates, so they cannot be reused on panels of a different
  size. These equivalents take a scale factor and lay everything out in
  design units (see `bardisplay.theme`)."
  (:require [bardisplay.theme :as theme]
            [clojure.string :as str])
  (:import [java.awt BasicStroke Color Font Graphics2D]
           [java.awt.geom Rectangle2D$Double RoundRectangle2D$Double]
           [javax.swing JComponent]))

(defn- u
  "Shorthand for scaling a design unit value."
  ^long [^double s v]
  (theme/su s v))

(defn- text-width
  "Measures a string in the graphics context's current font."
  ^long [^Graphics2D g ^String s]
  (.stringWidth (.getFontMetrics g) (or s "")))

(defn fit
  "Truncates a string with an ellipsis until it fits within `max-w`
  pixels in the graphics context's current font."
  ^String [^Graphics2D g ^String s max-w]
  (let [s (or s "")]
    (if (<= (text-width g s) max-w)
      s
      (loop [n (count s)]
        (if (<= n 1)
          "\u2026"
          (let [candidate (str (str/trimr (subs s 0 (dec n))) "\u2026")]
            (if (<= (text-width g candidate) max-w)
              candidate
              (recur (dec n)))))))))

(defn- draw-left
  "Draws a string with its left edge at `x` and its baseline at `y`."
  [^Graphics2D g ^String s x y]
  (.drawString g (or s "") (float x) (float y)))

(defn- draw-right
  "Draws a string with its right edge at `x` and its baseline at `y`."
  [^Graphics2D g ^String s x y]
  (.drawString g (or s "") (float (- x (text-width g s))) (float y)))

(defn- draw-centered
  "Draws a string centered horizontally on `x`, with its baseline at `y`."
  [^Graphics2D g ^String s x y]
  (.drawString g (or s "") (float (- x (/ (text-width g s) 2.0))) (float y)))

(defn- rounded
  ^RoundRectangle2D$Double [x y w h r]
  (RoundRectangle2D$Double. (double x) (double y) (double w) (double h) (double r) (double r)))

;;; Number formatting ----------------------------------------------------------

(defn- two-digits
  "Formats a number as a two digit string, or `--` if it is missing or
  out of range."
  ^String [v]
  (if v
    (let [v (int v)]
      (if (< -1 v 100) (format "%02d" v) "--"))
    "--"))

(defn- clock-parts
  "Splits a duration in milliseconds into the two digit minute, second
  and frame strings used by the time displays."
  [ms]
  (if ms
    (let [half-frame (mod (org.deepsymmetry.beatlink.Util/timeToHalfFrame ms) 150)]
      [(two-digits (quot ms 60000))
       (two-digits (quot (mod ms 60000) 1000))
       (two-digits (quot half-frame 2))])
    ["--" "--" "--"]))

(defn- pitch-sign
  "Returns the sign to draw in front of a pitch value. The segment font
  has no plus or minus glyph, so it is drawn separately in Teko."
  ^String [pitch]
  (cond
    (nil? pitch)                      " "
    (< (Math/abs (double pitch)) 0.025) " "
    (neg? pitch)                      "-"
    :else                             "+"))

(defn- pitch-digits
  "Formats the magnitude of a pitch percentage, always using a period as
  the decimal separator because that is all the segment font offers."
  ^String [pitch]
  (if pitch
    (let [magnitude (Math/abs (double pitch))]
      (String/format java.util.Locale/ROOT
                     (if (< magnitude 20.0) "%.2f" "%.1f")
                     (to-array [magnitude])))
    "--.--"))

(defn- format-tempo
  "Formats an effective tempo, or a placeholder when none is known."
  ^String [tempo]
  (if tempo
    (String/format java.util.Locale/ROOT "%.1f" (to-array [(double tempo)]))
    "--.-"))

;;; Painters -------------------------------------------------------------------

(defn- draw-clock
  "Draws a time value in the segment font with manually drawn colons,
  since the font has no colon glyph. Returns the total width drawn.
  When `x` is `nil` the value is right aligned on `right`."
  [^Graphics2D g ^double s {:keys [ms size baseline right frames?]}]
  (let [[mm ss ff] (clock-parts ms)
        font       (theme/font :segment Font/PLAIN s size)
        _          (.setFont g font)
        group      (text-width g "00")
        dot        (max 1 (u s (* size 0.09)))
        colon      (* 3 dot)
        total      (+ (* group (if frames? 3 2)) (* colon (if frames? 2 1)))
        x          (- right total)
        dot-high   (- baseline (u s (* size 0.62)))
        dot-low    (- baseline (u s (* size 0.28)))]
    (draw-left g mm x baseline)
    (.fill g (Rectangle2D$Double. (double (+ x group dot)) (double dot-high) (double dot) (double dot)))
    (.fill g (Rectangle2D$Double. (double (+ x group dot)) (double dot-low) (double dot) (double dot)))
    (draw-left g ss (+ x group colon) baseline)
    (when frames?
      (let [x2 (+ x group colon group)]
        (.fill g (Rectangle2D$Double. (double (+ x2 dot)) (double dot-high) (double dot) (double dot)))
        (.fill g (Rectangle2D$Double. (double (+ x2 dot)) (double dot-low) (double dot) (double dot)))
        (draw-left g ff (+ x2 colon) baseline)))
    total))

(defn badge
  "Paints the player number, play state and position within the bar."
  [{:keys [player playing beat]} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        active (and (pos? player) playing)
        tint   (if active (theme/color :playing) (theme/color :idle))
        stroke (max 1 (u s 2))]
    (.setStroke g (BasicStroke. stroke))
    (.setPaint g tint)
    (.draw g (rounded (/ stroke 2.0) (/ stroke 2.0) (- w stroke) (- h stroke) (u s 12)))
    (.setFont g (theme/font :teko Font/PLAIN s 16))
    (draw-centered g "PLAYER" (/ w 2.0) (u s 20))
    (.setFont g (theme/font :orbitron Font/BOLD s 44))
    (draw-centered g (if (pos? player) (str player) "-") (/ w 2.0) (u s 82))
    ;; The four tick marks along the bottom show the beat within the bar.
    (let [inset (u s 10)
          gap   (u s 4)
          tick-w (max 1 (quot (- w (* 2 inset) (* 3 gap)) 4))
          tick-y (- h (u s 20))
          tick-h (u s 8)]
      (dotimes [i 4]
        (.setPaint g (cond
                       (not= (inc i) beat)   (theme/color :idle)
                       (zero? i)             (theme/color :on-air)
                       :else                 (theme/color :value)))
        (.fill g (rounded (+ inset (* i (+ tick-w gap))) tick-y tick-w tick-h (u s 2)))))))

(defn track
  "Paints the title and artist of the loaded track."
  [{:keys [player title artist]} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w (.getWidth c)
        h (.getHeight c)
        s (theme/block-scale h)]
    (.setFont g (theme/font :teko Font/BOLD s 44))
    (.setPaint g (if (pos? player) (theme/color :title) (theme/color :idle)))
    (draw-left g (fit g (if (pos? player) (or title "No Track") "No Player") w) 0 (u s 48))
    (.setFont g (theme/font :teko Font/PLAIN s 32))
    (.setPaint g (if (and (pos? player) artist) (theme/color :artist) (theme/color :idle)))
    (draw-left g (fit g (or artist "\u2014") w) 0 (u s 92))))

(defn- pill
  "Draws one status indicator. It is filled when lit and merely outlined
  when not."
  [^Graphics2D g s ^String label lit? ^Color tint x y w h]
  (let [stroke (max 1 (u s 2))
        shape  (rounded x y w h (u s 6))]
    (if lit?
      (do (.setPaint g tint)
          (.fill g shape)
          (.setPaint g Color/BLACK))
      (do (.setStroke g (BasicStroke. stroke))
          (.setPaint g (theme/color :outline))
          (.draw g shape)
          (.setPaint g (theme/color :idle))))
    (.setFont g (theme/font :teko Font/BOLD s 24))
    (draw-centered g label (+ x (/ w 2.0)) (+ y (* h 0.76)))))

(defn status
  "Paints the on-air, tempo master and sync indicators."
  [{:keys [player on-air master synced bpm-only]} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w     (.getWidth c)
        h     (.getHeight c)
        s     (theme/block-scale h)
        gap   (u s 8)
        pill-h (quot (- h (* 2 gap)) 3)
        live? (pos? player)]
    (pill g s "ON AIR" (and live? on-air) (theme/color :on-air) 0 0 w pill-h)
    (pill g s "MASTER" (and live? master) (theme/color :master) 0 (+ pill-h gap) w pill-h)
    (pill g s (if bpm-only "BPM" "SYNC") (and live? synced)
          (if bpm-only (theme/color :bpm-only) (theme/color :sync))
          0 (* 2 (+ pill-h gap)) w pill-h)))

(defn tempo
  "Paints the pitch adjustment and the resulting effective tempo."
  [{:keys [player pitch tempo master]} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w     (.getWidth c)
        h     (.getHeight c)
        s     (theme/block-scale h)
        inset (u s 2)
        live? (pos? player)
        tint  (cond (not live?) (theme/color :idle)
                    master      (theme/color :master)
                    :else       (theme/color :value))]
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g "TEMPO" inset (u s 20))
    (.setPaint g (if live? (theme/color :value) (theme/color :idle)))
    (let [baseline (u s 54)
          digits   (if live? (pitch-digits pitch) "--.--")]
      (.setFont g (theme/font :teko Font/BOLD s 26))
      (draw-left g (if live? (pitch-sign pitch) " ") inset baseline)
      (.setFont g (theme/font :segment Font/PLAIN s 24))
      (let [x     (+ inset (u s 14))
            ;; Measure in the segment font, before switching to Teko for the unit.
            digits-w (text-width g digits)]
        (draw-left g digits x baseline)
        (.setFont g (theme/font :teko Font/PLAIN s 22))
        (draw-left g "%" (+ x digits-w (u s 8)) baseline)))
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 22))
    (draw-left g "BPM" inset (u s 104))
    (.setPaint g tint)
    (.setFont g (theme/font :segment Font/PLAIN s 44))
    (draw-right g (if live? (format-tempo tempo) "--.-") (- w inset) (u s 104))))

(defn- remain-color
  "Picks the color for the remaining time, warning as the track runs
  out."
  ^Color [live? remain]
  (let [{:keys [warn urgent]} @theme/thresholds]
    (cond
      (not live?)           (theme/color :idle)
      (nil? remain)         (theme/color :idle)
      (<= remain urgent)    (theme/color :remain-urgent)
      (<= remain warn)      (theme/color :remain-warn)
      :else                 (theme/color :value))))

(defn time-display
  "Paints the elapsed and remaining playback times."
  [{:keys [player played remain precise]} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w        (.getWidth c)
        h        (.getHeight c)
        s        (theme/block-scale h)
        inset    (u s 2)
        live?    (pos? player)
        compact? (< (/ w s) 260)]
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g (if precise "PRECISE TIME" "TIME") inset (u s 24))
    (.setPaint g (if live? (theme/color :value) (theme/color :idle)))
    (draw-clock g s {:ms       (when live? played)
                     :size     24
                     :baseline (u s 28)
                     :right    (- w inset)
                     :frames?  (not compact?)})
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g "REMAIN" inset (u s 62))
    (.setPaint g (remain-color live? remain))
    (draw-clock g s {:ms       (when live? remain)
                     :size     (if compact? 38 48)
                     :baseline (u s 106)
                     :right    (- w inset)
                     :frames?  false})))
