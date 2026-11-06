(ns bardisplay.paint
  "Resolution independent painters for the pieces of a player cell.

  Beat Link Trigger's own Player Status painters draw at hard coded
  pixel coordinates, so they cannot be reused on panels of a different
  size. These equivalents take a scale factor and lay everything out in
  design units (see `bardisplay.theme`)."
  (:require [bardisplay.i18n :refer [t]]
            [bardisplay.theme :as theme]
            [clojure.string :as str])
  (:import [java.awt BasicStroke Color Font Graphics2D]
           [java.awt.geom Ellipse2D$Double Rectangle2D$Double RoundRectangle2D$Double]
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
          "…"
          (let [candidate (str (str/trimr (subs s 0 (dec n))) "…")]
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

;;; Shared helpers -------------------------------------------------------------

(defn live?
  "Checks whether a snapshot describes a player that is assigned and on
  the network. Snapshots without `:online?` count as online."
  [{:keys [player online?]}]
  (and (pos? (or player 0)) (not (false? online?))))

(def scroll-period
  "How often, in milliseconds, a title too long to fit scrolls through."
  30000)

(def scroll-run
  "How long, in milliseconds, one scroll takes."
  4000)

(def scroll-hold
  "How long, in milliseconds, the end of the text stays visible before it
  jumps back to the start."
  1500)

(defn scroll-time
  "The clock a cell's scrolling runs on. Offset by player number so the
  cells of a panel do not all move at once."
  ^long [now player]
  (+ (long now) (* 7000 (long (or player 0)))))

(defn scrolling?
  "Checks whether an occasional scroll is under way at time `t`."
  [t]
  (< (mod (long t) scroll-period) (+ scroll-run scroll-hold)))

(defn scroll-offset
  "How far, in pixels, text `text-w` wide is shifted left at time `t`
  when only `avail-w` pixels are available."
  ^long [t text-w avail-w]
  (let [overflow (- (long text-w) (long avail-w))
        phase    (mod (long t) scroll-period)]
    (cond
      (<= overflow 0)                      0
      (< phase scroll-run)                 (long (* overflow (/ (double phase) scroll-run)))
      (< phase (+ scroll-run scroll-hold)) overflow
      :else                                0)))

(defn format-countdown
  "Formats a duration in milliseconds as `m:ss`."
  ^String [ms]
  (let [ms (max 0 (long ms))]
    (format "%d:%02d" (quot ms 60000) (quot (mod ms 60000) 1000))))

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
    (nil? pitch)                        " "
    (< (Math/abs (double pitch)) 0.025) " "
    (neg? pitch)                        "-"
    :else                               "+"))

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
  since the font has no colon glyph. Returns the total width drawn. The
  value is right aligned on `right`."
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
  [{:keys [player playing beat] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        active (and (live? snap) playing)
        tint   (if active (theme/color :playing) (theme/color :idle))
        stroke (max 1 (u s 2))]
    (.setStroke g (BasicStroke. stroke))
    (.setPaint g tint)
    (.draw g (rounded (/ stroke 2.0) (/ stroke 2.0) (- w stroke) (- h stroke) (u s 12)))
    (.setFont g (theme/font :teko Font/PLAIN s 16))
    (draw-centered g (t :cell/player) (/ w 2.0) (u s 20))
    (.setFont g (theme/font :orbitron Font/BOLD s 44))
    (draw-centered g (if (pos? (or player 0)) (str player) "-") (/ w 2.0) (u s 82))
    ;; The four tick marks along the bottom show the beat within the bar.
    (let [inset  (u s 10)
          gap    (u s 4)
          tick-w (max 1 (quot (- w (* 2 inset) (* 3 gap)) 4))
          tick-y (- h (u s 20))
          tick-h (u s 8)]
      (dotimes [i 4]
        (.setPaint g (cond
                       (not (live? snap))  (theme/color :idle)
                       (not= (inc i) beat) (theme/color :idle)
                       (zero? i)           (theme/color :on-air)
                       :else               (theme/color :value)))
        (.fill g (rounded (+ inset (* i (+ tick-w gap))) tick-y tick-w tick-h (u s 2)))))))

(defn- draw-line
  "Draws one line of the track block: truncated with an ellipsis, or
  shifted left while an occasional scroll is under way."
  [^Graphics2D g ^String text w baseline scroll? t]
  (let [offset (if scroll? (scroll-offset t (text-width g text) w) 0)]
    (if (pos? offset)
      (draw-left g text (- offset) baseline)
      (draw-left g (fit g text w) 0 baseline))))

(defn track
  "Paints the title and artist of the loaded track. `opts` holds
  `:scroll?` (occasional scrolling on) and `:now` (current time in ms)."
  [{:keys [player title artist] :as snap} {:keys [scroll? now]} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        active (live? snap)
        t      (scroll-time (or now 0) player)]
    (.setFont g (theme/font :teko Font/BOLD s 44))
    (.setPaint g (if active (theme/color :title) (theme/color :idle)))
    (draw-line g (cond
                   (not (pos? (or player 0))) (t :cell/no-player)
                   (not active)               (t :cell/offline)
                   :else                      (or title (t :cell/no-track)))
               w (u s 48) scroll? t)
    (.setFont g (theme/font :teko Font/PLAIN s 32))
    (.setPaint g (if (and active artist) (theme/color :artist) (theme/color :idle)))
    (draw-line g (if active (or artist "—") "—") w (u s 92) scroll? t)))

(defn phrase-count
  "Turns the beats left in a phrase into the number shown and the text
  key of its unit. `unit` is `:bars` (rounded up, four beats each) or
  `:beats`."
  [beats-left unit]
  (if (= unit :beats)
    [beats-left (if (= 1 beats-left) :cell/beat-unit :cell/beats)]
    (let [bars (long (Math/ceil (/ beats-left 4.0)))]
      [bars (if (= 1 bars) :cell/bar :cell/bars)])))

(defn info-content
  "Decides what one entry of the info block shows. Returns `:text`, and
  optionally `:fill` (lit background), `:text-color` and `:dot` (a
  colored marker before the text). `snap` is `nil` when the player is
  not live. `opts` holds `:phrase-unit` (`:bars` or `:beats`)."
  ([item snap]
   (info-content item snap {}))
  ([item snap {:keys [phrase-unit] :or {phrase-unit :bars}}]
   (case item
     :phrase (if-let [{:keys [label color text-color beats-left next]} (:phrase snap)]
               (let [[n unit] (phrase-count beats-left phrase-unit)]
                 {:text       (str (str/upper-case label) " → "
                                   (if next (str/upper-case (:label next)) (t :cell/end))
                                   " · " n " " (t unit))
                  :fill       color
                  :text-color text-color})
               {:text (str (t :cell/phrase) " –")})
     :key    (if-let [k (:key snap)]
               {:text (str (t :cell/key) " " k) :text-color (theme/color :value)}
               {:text (str (t :cell/key) " –")})
     :loop   (case (:looping snap)
               true  {:text       (let [beats (:loop-beats snap)]
                                    (if (and beats (pos? beats)) (str (t :cell/loop) " " beats) (t :cell/loop)))
                      :fill       (theme/color :loop)
                      :text-color Color/BLACK}
               false {:text (t :cell/loop)}
               {:text (str (t :cell/loop) " –")})
     :cue    (if-let [{:keys [ms-left color]} (:next-cue snap)]
               {:text (str (t :cell/cue) " " (format-countdown ms-left)) :text-color (theme/color :value) :dot color}
               {:text (str (t :cell/cue) " –")}))))

(defn info-slots
  "Places the info entries `items` in a block `w` by `h` pixels with
  `gap` between them. The phrase, when present, spans the top row and
  the rest share the bottom row; otherwise they fill a two by two grid.
  Returns `[[item x y width height] ...]`."
  [items w h gap]
  (let [row-h (quot (- h gap) 2)
        low-y (+ row-h gap)]
    (if (= :phrase (first items))
      (let [others (rest items)
            n      (count others)
            cell-w (when (pos? n) (quot (- w (* gap (dec n))) n))]
        (into [[:phrase 0 0 w row-h]]
              (map-indexed (fn [i item] [item (* i (+ cell-w gap)) low-y cell-w row-h]) others)))
      (let [cell-w (quot (- w gap) 2)]
        (vec (map-indexed (fn [i item]
                            [item (* (mod i 2) (+ cell-w gap)) (* (quot i 2) low-y) cell-w row-h])
                          items))))))

(defn- info-item
  "Draws one entry of the info block as a pill."
  [^Graphics2D g s {:keys [text fill text-color dot]} x y w h]
  (let [stroke (max 1 (u s 2))
        shape  (rounded x y w h (u s 6))]
    (if fill
      (do (.setPaint g fill)
          (.fill g shape))
      (do (.setStroke g (BasicStroke. stroke))
          (.setPaint g (theme/color :outline))
          (.draw g shape)))
    (.setFont g (theme/font :teko Font/BOLD s 24))
    (let [dot-d    (u s 10)
          dot-gap  (u s 6)
          text     (fit g text (- w (u s 8) (if dot (+ dot-d dot-gap) 0)))
          total    (+ (text-width g text) (if dot (+ dot-d dot-gap) 0))
          left     (+ x (/ (- w total) 2.0))
          baseline (+ y (* h 0.72))]
      (when dot
        (.setPaint g dot)
        (.fill g (Ellipse2D$Double. (double left) (double (- (+ y (/ h 2.0)) (/ dot-d 2.0)))
                                    (double dot-d) (double dot-d))))
      (.setPaint g (or text-color (theme/color :idle)))
      (draw-left g text (if dot (+ left dot-d dot-gap) left) baseline))))

(defn info
  "Paints the info block: the entries `items` (drawn from `:phrase :key
  :loop :cue`, in that order) laid out by `info-slots`. `opts` is passed
  to `info-content`."
  [snap items opts ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [h    (.getHeight c)
        s    (theme/block-scale h)
        snap (when (live? snap) snap)]
    (doseq [[item x y w h] (info-slots items (.getWidth c) h (u s 8))]
      (info-item g s (info-content item snap opts) x y w h))))

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
  [{:keys [on-air master synced bpm-only] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        gap    (u s 8)
        pill-h (quot (- h (* 2 gap)) 3)
        active (live? snap)]
    (pill g s (t :cell/on-air) (and active on-air) (theme/color :on-air) 0 0 w pill-h)
    (pill g s (t :cell/master) (and active master) (theme/color :master) 0 (+ pill-h gap) w pill-h)
    (pill g s (if bpm-only (t :cell/bpm-sync) (t :cell/sync)) (and active synced)
          (if bpm-only (theme/color :bpm-only) (theme/color :sync))
          0 (* 2 (+ pill-h gap)) w pill-h)))

(defn tempo
  "Paints the pitch adjustment and the resulting effective tempo."
  [{:keys [pitch tempo master] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w      (.getWidth c)
        h      (.getHeight c)
        s      (theme/block-scale h)
        inset  (u s 2)
        active (live? snap)
        tint   (cond (not active) (theme/color :idle)
                     master       (theme/color :master)
                     :else        (theme/color :value))]
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g (t :cell/tempo) inset (u s 20))
    (.setPaint g (if active (theme/color :value) (theme/color :idle)))
    (let [baseline (u s 54)
          digits   (if active (pitch-digits pitch) "--.--")]
      (.setFont g (theme/font :teko Font/BOLD s 26))
      (draw-left g (if active (pitch-sign pitch) " ") inset baseline)
      (.setFont g (theme/font :segment Font/PLAIN s 24))
      (let [x        (+ inset (u s 14))
            ;; Measure in the segment font, before switching to Teko for the unit.
            digits-w (text-width g digits)]
        (draw-left g digits x baseline)
        (.setFont g (theme/font :teko Font/PLAIN s 22))
        (draw-left g "%" (+ x digits-w (u s 8)) baseline)))
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 22))
    (draw-left g (t :cell/bpm) inset (u s 104))
    (.setPaint g tint)
    (.setFont g (theme/font :segment Font/PLAIN s 44))
    (draw-right g (if active (format-tempo tempo) "--.-") (- w inset) (u s 104))))

(defn- remain-color
  "Picks the color for the remaining time, warning as the track runs
  out."
  ^Color [active remain]
  (let [{:keys [warn urgent]} @theme/thresholds]
    (cond
      (not active)       (theme/color :idle)
      (nil? remain)      (theme/color :idle)
      (<= remain urgent) (theme/color :remain-urgent)
      (<= remain warn)   (theme/color :remain-warn)
      :else              (theme/color :value))))

(defn time-display
  "Paints the elapsed and remaining playback times."
  [{:keys [played remain precise] :as snap} ^JComponent c ^Graphics2D g]
  (theme/prepare g)
  (let [w        (.getWidth c)
        h        (.getHeight c)
        s        (theme/block-scale h)
        inset    (u s 2)
        active   (live? snap)
        compact? (< (/ w s) 260)]
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g (if precise (t :cell/precise-time) (t :cell/time)) inset (u s 24))
    (.setPaint g (if active (theme/color :value) (theme/color :idle)))
    (draw-clock g s {:ms       (when active played)
                     :size     24
                     :baseline (u s 28)
                     :right    (- w inset)
                     :frames?  (not compact?)})
    (.setPaint g (theme/color :label))
    (.setFont g (theme/font :teko Font/PLAIN s 18))
    (draw-left g (t :cell/remain) inset (u s 62))
    (.setPaint g (remain-color active remain))
    (draw-clock g s {:ms       (when active remain)
                     :size     (if compact? 38 48)
                     :baseline (u s 106)
                     :right    (- w inset)
                     :frames?  false})))
