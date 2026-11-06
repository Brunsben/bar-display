(ns bardisplay.cell
  "A single player's region of a bar panel: a header of painted
  indicators above a scrolling waveform detail view and a waveform
  preview acting as the progress bar.

  A cell is pointed at one player with `set-player!` and keeps showing
  that player, online or not. Which blocks appear is chosen when the
  cell is created; `layout` works out where they go."
  (:require [bardisplay.paint :as paint]
            [bardisplay.sim :as sim]
            [bardisplay.state :as state]
            [bardisplay.theme :as theme]
            [clojure.core.async :as async]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.awt BasicStroke BorderLayout Color Dimension Graphics Graphics2D]
           [javax.swing JComponent JPanel]
           [org.deepsymmetry.beatlink.data WaveformDetailComponent WaveformPreviewComponent]))

(def tick-interval
  "How often, in milliseconds, we re-examine a player's state. Matches
  the rate Beat Link Trigger's own Player Status window uses."
  20)

(def all-blocks
  "Every display block that can be switched on or off. The player badge
  is always shown and is not listed."
  #{:track :status :tempo :time :beat :detail :preview :phrase :key :loop :cue})

(def info-items
  "The optional entries of the info block, in display order."
  [:phrase :key :loop :cue])

(def header-order
  "The header blocks to the right of the track title, left to right."
  [:info :beat :status :tempo :time])

(def drop-order
  "The header blocks left out, in this order, when a cell is too narrow
  to show them all at a readable size. The badge and title always stay."
  [:tempo :status :beat :time :info])

(def dim-alpha
  "How strongly a deck that is not on air is darkened."
  0.55)

(def flash-ms
  "How long the frame lights up on a downbeat."
  150)

(defn dim?
  "Checks whether a cell should be darkened: dimming is `enabled?`, some
  deck reports being on air (`any-on-air?`), and this live deck is not."
  [snap any-on-air? enabled?]
  (boolean (and enabled? any-on-air? (paint/live? snap) (not (:on-air snap)))))

(defn downbeat?
  "Checks whether a live player has just moved onto the first beat of a
  bar."
  [previous current]
  (boolean (and (paint/live? current) (= 1 (:beat current)) (not= 1 (:beat previous)))))

(def repaint-groups
  "Which snapshot keys each painted component depends on. The animation
  loop only repaints a component when one of its keys has changed."
  {:badge  [:player :online? :playing :beat]
   :track  [:player :online? :title :artist]
   :info   [:player :online? :phrase :key :looping :loop-beats :next-cue]
   :beat   [:player :online? :beat]
   :status [:player :online? :on-air :master :synced :bpm-only]
   :tempo  [:player :online? :pitch :tempo :master]
   :time   [:player :online? :played :remain :precise]})

(defn layout-blocks
  "Turns the chosen display blocks into the set `layout` works with,
  adding `:info` when any info entry is switched on."
  [blocks]
  (let [blocks (set blocks)]
    (cond-> blocks
      (some blocks info-items) (conj :info))))

(defn layout
  "Works out where each visible block of a `w` by `h` cell goes. `blocks`
  is the result of `layout-blocks`. Returns a map from block key to
  `[x y width height]`; blocks not shown are absent. The badge is always
  present.

  Everything is derived from the cell's height, so the layout is the
  same on panels of any resolution. Header blocks keep their widths
  relative to the header height; when both waveforms are hidden the
  header grows to fill the cell, as far as the width allows."
  [w h blocks]
  (let [metrics       theme/metrics
        ;; Cells taller than the reference panel give the extra height to the
        ;; waveforms rather than enlarging the header past its design size.
        height-s      (min 1.0 (theme/scale h))
        compact?      (< (/ (double w) height-s) (:compact-below metrics))
        design-w      (fn [k] (get-in metrics [k (if compact? :compact :full)]))
        ;; The scale at which the header, with these blocks right of the title, fits
        ;; across the cell: margins, gaps, the fixed blocks, and the title's share when
        ;; it is shown. Never larger than the scale the cell's height allows.
        fit-s         (fn [right]
                        (let [fixed-du  (+ (* 2 (:margin metrics))
                                           (* (:gap metrics) (inc (count right)))
                                           (design-w :badge)
                                           (reduce + 0 (map design-w right)))
                              needed-du (if (contains? blocks :track)
                                          (/ fixed-du (- 1.0 (:min-track-share metrics)))
                                          fixed-du)]
                          (min height-s (/ (double w) needed-du))))
        ;; Leave blocks out, least important first, until the text stays readable.
        right         (loop [right (filterv blocks header-order)
                             drop  drop-order]
                        (if (or (>= (fit-s right) (* (:min-scale-share metrics) height-s))
                                (empty? drop))
                          right
                          (recur (filterv #(not= % (first drop)) right) (rest drop))))
        s             (fit-s right)
        m             (theme/su s (:margin metrics))
        gap           (theme/su s (:gap metrics))
        detail?       (contains? blocks :detail)
        preview?      (contains? blocks :preview)
        waves?        (or detail? preview?)
        natural       (theme/su s (:header metrics))
        avail-h       (- h (* 2 m))
        header-h      (if waves?
                        natural
                        (let [fixed-du (+ (design-w :badge) (reduce + 0 (map design-w right)))
                              gaps     (* gap (inc (count right)))
                              room     (- (* w (- 1.0 (:min-track-share metrics))) (* 2 m) gaps)
                              widest   (long (/ (* room (:header metrics)) fixed-du))]
                          (max natural (min avail-h widest))))
        hs            (/ (double header-h) (:header metrics))
        block-w       (fn [k] (theme/su hs (design-w k)))
        top           (if waves? m (+ m (quot (- avail-h header-h) 2)))
        [placed edge] (reduce (fn [[acc x] k]
                                (let [bw (block-w k)
                                      bx (- x bw)]
                                  [(assoc acc k [bx top bw header-h]) (- bx gap)]))
                              [{} (- w m)]
                              (reverse right))
        badge-w       (block-w :badge)
        track-x       (+ m badge-w gap)
        track-w       (max 0 (- edge track-x))
        inner-w       (- w (* 2 m))
        wave-y        (+ m header-h gap)
        wave-h        (max 0 (- h wave-y m))
        preview-h     (theme/su s (:preview metrics))
        waves         (cond
                        (and detail? preview?)
                        (let [detail-h (max 0 (- wave-h preview-h gap))]
                          {:detail  [m wave-y inner-w detail-h]
                           :preview [m (+ wave-y detail-h gap) inner-w (min preview-h wave-h)]})
                        detail?  {:detail [m wave-y inner-w wave-h]}
                        preview? {:preview [m wave-y inner-w wave-h]}
                        :else    {})]
    (merge {:badge [m top badge-w header-h]}
           (when (contains? blocks :track) {:track [track-x top track-w header-h]})
           placed
           waves)))

(defn- style-waveform!
  "Applies our color scheme to a waveform component."
  [component]
  (doto component
    (.setBackgroundColor (theme/color :background))
    (.setIndicatorColor (theme/color :wave-tick))
    (.setEmphasisColor (theme/color :wave-emphasis))))

(defn- holder
  "Builds the container a waveform component lives in. Going through a
  container lets the simulation swap components out, which it has to do
  because data mode components are bound to one track."
  ^JPanel []
  (doto (JPanel. (BorderLayout.))
    (.setOpaque true)
    (.setBackground (theme/color :background))))

(defn- live-waveforms
  "Waveform components in auto-tracking mode, which subscribe to the
  Beat Link finders themselves and repaint as the player moves. They are
  only connected to the cell's player while Beat Link is running (see
  `state/live-ready?`); each tick catches up when that changes."
  [^JPanel detail-holder ^JPanel preview-holder scale]
  (let [detail   (style-waveform! (WaveformDetailComponent. (int 0)))
        preview  (style-waveform! (WaveformPreviewComponent. (int 0)))
        wanted   (atom 0)
        attached (atom 0)
        sync!    (fn []
                   (let [target (if (state/live-ready?) @wanted 0)]
                     (when (not= target @attached)
                       (reset! attached target)
                       (.setMonitoredPlayer ^WaveformDetailComponent detail (int target))
                       (.setMonitoredPlayer ^WaveformPreviewComponent preview (int target)))))]
    (.setScale ^WaveformDetailComponent detail (int scale))
    (.add detail-holder ^JComponent detail BorderLayout/CENTER)
    (.add preview-holder ^JComponent preview BorderLayout/CENTER)
    {:attach!  (fn [n]
                 (reset! wanted n)
                 (sync!))
     :advance! (fn [_ _ _] (sync!))
     :release! (fn []
                 (reset! wanted 0)
                 (sync!))}))

(defn- simulated-waveforms
  "Waveform components in data mode, rebuilt whenever the cell is pointed
  at a different simulated track and driven by `advance!`."
  [^JPanel detail-holder ^JPanel preview-holder scale]
  (let [current (atom nil)]
    {:attach!  (fn [n]
                 (.removeAll detail-holder)
                 (.removeAll preview-holder)
                 (reset! current
                         (when-let [track (sim/track n)]
                           (let [detail  (style-waveform!
                                           (WaveformDetailComponent. (:detail track) (:cue-list track)
                                                                     (:grid track)))
                                 preview (style-waveform!
                                           (WaveformPreviewComponent. (:preview track)
                                                                      (get-in track [:metadata :duration])
                                                                      (:cue-list track)))]
                             (.setScale ^WaveformDetailComponent detail (int scale))
                             (.setSongStructure ^WaveformDetailComponent detail (:song-structure track))
                             (.setBeatGrid ^WaveformPreviewComponent preview (:grid track))
                             (.setSongStructure ^WaveformPreviewComponent preview (:song-structure track))
                             (.add detail-holder ^JComponent detail BorderLayout/CENTER)
                             (.add preview-holder ^JComponent preview BorderLayout/CENTER)
                             {:detail detail :preview preview})))
                 (doto detail-holder (.revalidate) (.repaint))
                 (doto preview-holder (.revalidate) (.repaint)))
     :advance! (fn [n played playing]
                 (when-let [{:keys [detail preview]} @current]
                   (when played
                     (.setPlaybackState ^WaveformDetailComponent detail
                                        (int n) (long played) (boolean playing))
                     (.setPlaybackState ^WaveformPreviewComponent preview
                                        (int n) (long played) (boolean playing)))))
     :release! (fn [])}))

(defn- lay-out!
  "Positions the children of a cell according to `layout`, hiding the
  blocks that are switched off."
  [^JPanel panel children blocks]
  (let [rects (layout (.getWidth panel) (.getHeight panel) blocks)]
    (doseq [[k ^JComponent child] children]
      (if-let [[x y w h] (get rects k)]
        (doto child
          (.setVisible true)
          (.setBounds (int x) (int y) (int w) (int h)))
        (.setVisible child false)))))

(defn- paint-overlay!
  "Draws what covers the whole cell: the darkening of a deck that is not
  on air, and the frame lit on a downbeat."
  [^JComponent panel ^Graphics g {:keys [dim? flash?]}]
  (let [w (.getWidth panel)
        h (.getHeight panel)]
    (when dim?
      (.setColor g (Color. (float 0) (float 0) (float 0) (float dim-alpha)))
      (.fillRect g 0 0 w h))
    (when flash?
      (let [^Graphics2D g2 g
            stroke         (max 3 (theme/su (theme/scale h) 6))]
        (.setColor g2 (theme/color :on-air))
        (.setStroke g2 (BasicStroke. (float stroke)))
        (.drawRect g2 (quot stroke 2) (quot stroke 2) (- w stroke) (- h stroke))))))

(defn- canvas
  "Creates one of the painted header components. `paint-fn` receives the
  current snapshot, the component and the graphics context."
  [paint-fn snapshot]
  (seesaw/canvas :background (theme/color :background)
                 :opaque? true
                 :paint (fn [c g] (paint-fn @snapshot c g))))

(defn create
  "Builds a cell. `shutdown-chan` is closed when the display is torn
  down, which stops the animation loop and releases the waveform
  components. `options` holds `:scale` (waveform detail zoom; larger
  values show more of the track), `:blocks` (which blocks to show, see
  `all-blocks`), `:title-scroll` (`:off` or `:occasional`),
  `:phrase-unit` (`:bars` or `:beats`), `:dim-off-air?` and
  `:flash-downbeat?`.

  Returns a map containing the Swing `:panel`, the `:snapshot` atom, the
  `:overlay` atom and a `:set-player!` function."
  [shutdown-chan {:keys [scale blocks title-scroll phrase-unit dim-off-air? flash-downbeat?]}]
  (let [scale    (or scale 4)
        chosen   (set (or blocks all-blocks))
        items    (filterv chosen info-items)
        blocks   (layout-blocks chosen)
        scroll?  (= title-scroll :occasional)
        info-opt {:phrase-unit (or phrase-unit :bars)}
        ;; The player number is owned by `set-player!`; the snapshot is owned by the
        ;; animation loop. Keeping them apart stops a tick in flight from reverting
        ;; a reassignment that happened while it was gathering state.
        player   (atom 0)
        snapshot (atom state/empty-snapshot)
        moving   (atom false)
        ;; What is drawn over the whole cell: dimming, and the downbeat flash.
        overlay  (atom {:dim? false :flash? false})
        flash-to (atom 0)
        detail   (holder)
        preview  (holder)
        children {:badge   (canvas paint/badge snapshot)
                  :track   (canvas (fn [snap c g]
                                     (paint/track snap {:scroll? scroll? :now (System/currentTimeMillis)} c g))
                                   snapshot)
                  :info    (canvas (fn [snap c g] (paint/info snap items info-opt c g)) snapshot)
                  :beat    (canvas paint/beat-counter snapshot)
                  :status  (canvas paint/status snapshot)
                  :tempo   (canvas paint/tempo snapshot)
                  :time    (canvas paint/time-display snapshot)
                  :detail  detail
                  :preview preview}
        waves    (if (sim/active?)
                   (simulated-waveforms detail preview scale)
                   (live-waveforms detail preview scale))
        panel    (proxy [JPanel] []
                   (doLayout []
                     (lay-out! ^JPanel this children blocks))
                   ;; Children repainting themselves must go through us, so the overlay
                   ;; is drawn over them too.
                   (isOptimizedDrawingEnabled [] false)
                   (paintChildren [^Graphics g]
                     (proxy-super paintChildren g)
                     (paint-overlay! this g @overlay)))]
    (doto panel
      (.setLayout nil)
      (.setOpaque true)
      (.setBackground (theme/color :background))
      (.setMinimumSize (Dimension. 320 160)))
    (doseq [child (vals children)]
      (.add panel ^JComponent child))

    (async/go-loop []
      (let [timeout     (async/timeout tick-interval)
            [_ channel] (async/alts! [shutdown-chan timeout])]
        (if (= channel timeout)
          (do
            (try
              (let [current   (state/snapshot @player)
                    previous  (deref snapshot)
                    changed   (vec (keep (fn [[component ks]]
                                           (when (some #(not= (get previous %) (get current %)) ks)
                                             component))
                                         repaint-groups))
                    scrolling (and scroll?
                                   (paint/scrolling? (paint/scroll-time (System/currentTimeMillis)
                                                                        (:player current))))
                    ;; Keep repainting the title while it scrolls, and once more when it stops.
                    stale     (cond-> changed (or scrolling @moving) (conj :track))
                    now       (System/currentTimeMillis)
                    _         (when (and flash-downbeat? (downbeat? previous current))
                                (reset! flash-to (+ now flash-ms)))
                    covering  {:dim?   (dim? current (and dim-off-air? (state/any-on-air?)) dim-off-air?)
                               :flash? (< now @flash-to)}
                    redraw?   (not= covering @overlay)]
                (reset! moving scrolling)
                (reset! overlay covering)
                (when (seq changed)
                  (reset! snapshot current))
                (seesaw/invoke-later
                  ((:advance! waves) (:player current) (:played current) (:playing current))
                  (cond
                    redraw?     (seesaw/repaint! panel)
                    (seq stale) (seesaw/repaint! (mapv children (distinct stale))))))
              (catch Throwable t
                (timbre/error t "Problem updating bar display cell.")))
            (recur))
          (seesaw/invoke-later ((:release! waves))))))

    {:panel       panel
     :snapshot    snapshot
     :overlay     overlay
     :set-player! (fn [n]
                    (let [n (long (or n 0))]
                      (when (not= n @player)
                        (reset! player n)
                        (seesaw/invoke-later
                          ((:attach! waves) n)
                          (seesaw/repaint! panel)))))}))
