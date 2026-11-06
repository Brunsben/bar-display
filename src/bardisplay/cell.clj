(ns bardisplay.cell
  "A single player's region of a bar panel: a header of painted
  indicators above a scrolling waveform detail view and a waveform
  preview acting as the progress bar.

  A cell is retargetable. Rather than rebuilding the UI when the set of
  online players changes, `set-player!` points the existing cell (and
  its waveform components) at a different player number, which avoids
  flicker and keeps the expensive components alive."
  (:require [bardisplay.paint :as paint]
            [bardisplay.sim :as sim]
            [bardisplay.state :as state]
            [bardisplay.theme :as theme]
            [clojure.core.async :as async]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.awt BorderLayout Dimension]
           [javax.swing JComponent JPanel]
           [org.deepsymmetry.beatlink.data WaveformDetailComponent WaveformPreviewComponent]))

(def tick-interval
  "How often, in milliseconds, we re-examine a player's state. Matches
  the rate Beat Link Trigger's own Player Status window uses."
  20)

(def all-blocks
  "Every display block that can be switched on or off. The player badge
  is always shown and is not listed."
  #{:track :status :tempo :time :detail :preview :phrase :key :loop :cue})

(def repaint-groups
  "Which snapshot keys each painted component depends on. The animation
  loop only repaints a component when one of its keys has changed."
  {:badge  [:player :playing :beat]
   :track  [:player :title :artist]
   :status [:player :on-air :master :synced :bpm-only]
   :tempo  [:player :pitch :tempo :master]
   :time   [:player :played :remain :precise]})

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
  Beat Link finders themselves and repaint as the player moves."
  [^JPanel detail-holder ^JPanel preview-holder scale]
  (let [detail  (style-waveform! (WaveformDetailComponent. (int 0)))
        preview (style-waveform! (WaveformPreviewComponent. (int 0)))]
    (.setScale ^WaveformDetailComponent detail (int scale))
    (.add detail-holder ^JComponent detail BorderLayout/CENTER)
    (.add preview-holder ^JComponent preview BorderLayout/CENTER)
    {:attach!  (fn [n]
                 (.setMonitoredPlayer ^WaveformDetailComponent detail (int n))
                 (.setMonitoredPlayer ^WaveformPreviewComponent preview (int n)))
     :advance! (fn [_ _ _])
     :release! (fn []
                 (.setMonitoredPlayer ^WaveformDetailComponent detail (int 0))
                 (.setMonitoredPlayer ^WaveformPreviewComponent preview (int 0)))}))

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

(defn- block-width
  "Looks up a header block width in design units, honoring compact mode."
  [k compact?]
  (get-in theme/metrics [k (if compact? :compact :full)]))

(defn- lay-out!
  "Positions the children of a cell. Everything is derived from the
  cell's own height, so the layout is identical on panels of any
  resolution."
  [^JPanel panel {:keys [badge track status tempo time detail preview]}]
  (let [w         (.getWidth panel)
        h         (.getHeight panel)
        s         (theme/scale h)
        m         (theme/su s (:margin theme/metrics))
        gap       (theme/su s (:gap theme/metrics))
        compact?  (< (/ (double w) s) (:compact-below theme/metrics))
        header-h  (theme/su s (:header theme/metrics))
        preview-h (theme/su s (:preview theme/metrics))
        badge-w   (theme/su s (block-width :badge compact?))
        status-w  (theme/su s (block-width :status compact?))
        tempo-w   (theme/su s (block-width :tempo compact?))
        time-w    (theme/su s (block-width :time compact?))
        time-x    (- w m time-w)
        tempo-x   (- time-x gap tempo-w)
        status-x  (- tempo-x gap status-w)
        track-x   (+ m badge-w gap)
        track-w   (max 0 (- status-x gap track-x))
        detail-y  (+ m header-h gap)
        detail-h  (max 0 (- h detail-y gap preview-h m))]
    (.setBounds ^JComponent badge m m badge-w header-h)
    (.setBounds ^JComponent track track-x m track-w header-h)
    (.setBounds ^JComponent status status-x m status-w header-h)
    (.setBounds ^JComponent tempo tempo-x m tempo-w header-h)
    (.setBounds ^JComponent time time-x m time-w header-h)
    (.setBounds ^JComponent detail m detail-y (- w (* 2 m)) detail-h)
    (.setBounds ^JComponent preview m (+ detail-y detail-h gap) (- w (* 2 m)) preview-h)))

(defn- canvas
  "Creates one of the painted header components."
  [paint-fn snapshot]
  (seesaw/canvas :background (theme/color :background)
                 :opaque? true
                 :paint (fn [c g] (paint-fn @snapshot c g))))

(defn create
  "Builds a cell. `shutdown-chan` is closed when the display is torn
  down, which stops the animation loop and releases the waveform
  components. `scale` is the waveform detail zoom level (larger values
  show more of the track).

  Returns a map containing the Swing `:panel` and a `:set-player!`
  function."
  [shutdown-chan scale]
  (let [;; The player number is owned by `set-player!`; the snapshot is owned by the
        ;; animation loop. Keeping them apart stops a tick in flight from reverting
        ;; a reassignment that happened while it was gathering state.
        player   (atom 0)
        snapshot (atom state/empty-snapshot)
        badge    (canvas paint/badge snapshot)
        track    (canvas paint/track snapshot)
        status   (canvas paint/status snapshot)
        tempo    (canvas paint/tempo snapshot)
        time     (canvas paint/time-display snapshot)
        detail   (holder)
        preview  (holder)
        waves    (if (sim/active?)
                   (simulated-waveforms detail preview scale)
                   (live-waveforms detail preview scale))
        children {:badge badge :track track :status status :tempo tempo
                  :time time :detail detail :preview preview}
        panel    (proxy [JPanel] []
                   (doLayout []
                     (lay-out! ^JPanel this children)))]
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
              (let [current  (state/snapshot @player)
                    previous (deref snapshot)
                    stale    (keep (fn [[component ks]]
                                     (when (some #(not= (get previous %) (get current %)) ks)
                                       component))
                                   repaint-groups)]
                (when (seq stale)
                  (reset! snapshot current))
                (seesaw/invoke-later
                  ((:advance! waves) (:player current) (:played current) (:playing current))
                  (when (seq stale)
                    (seesaw/repaint! (mapv children stale)))))
              (catch Throwable t
                (timbre/error t "Problem updating bar display cell.")))
            (recur))
          (seesaw/invoke-later ((:release! waves))))))

    {:panel       panel
     :snapshot    snapshot
     :set-player! (fn [n]
                    (let [n (long (or n 0))]
                      (when (not= n @player)
                        (reset! player n)
                        (seesaw/invoke-later
                          ((:attach! waves) n)
                          (seesaw/repaint! panel)))))}))
