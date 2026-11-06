(ns bardisplay.sim
  "A self-contained fake player network, so the bar displays can be built
  and rehearsed without any CDJs on the desk.

  Beat Link Trigger's own shallow playback simulators cannot drive these
  displays: they hand synthetic packets straight to the trigger and show
  listeners and never reach the MetadataFinder, TimeFinder or
  WaveformFinder that a live cell reads. So we keep our own clock here,
  and `bardisplay.cell` switches its waveform components into data mode
  while a simulation is running."
  (:require [beat-link-trigger.util :as util]
            [clojure.core.async :as async]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink.data BeatGrid]))

(def tick-interval
  "How often, in milliseconds, the simulated clock advances."
  20)

(def sample-tracks
  "The sample tracks Beat Link Trigger ships for offline work."
  [1 2])

(defonce ^{:private true
           :doc "Holds the running simulation, or `nil` when stopped."}
  simulation (atom nil))

(defn active?
  "Checks whether a simulation is currently running."
  []
  (some? @simulation))

(defn- duration-ms
  "Returns the length of a simulated track in milliseconds."
  ^long [track]
  (* 1000 (long (get-in track [:metadata :duration] 0))))

(defn- make-player
  "Builds the initial state of one simulated player. The players are
  given different pitches and roles so the display has something varied
  to show."
  [number track index]
  {:player  number
   :track   track
   :time    (* index 37000)
   :playing (even? index)
   :pitch   (nth [0.0 1.4 -2.2 0.8] (mod index 4))
   :on-air  (even? index)
   :master  (zero? index)
   :synced  (pos? index)})

(defn- advance
  "Moves one player's playback position forward, wrapping at the end of
  the track."
  [{:keys [playing pitch time track] :as player} elapsed-ms]
  (if-not playing
    player
    (let [total (duration-ms track)
          moved (+ time (* elapsed-ms (+ 1.0 (/ pitch 100.0))))]
      (assoc player :time (if (and (pos? total) (>= moved total))
                            (- moved total)
                            moved)))))

(defn stop!
  "Ends the simulation."
  []
  (when-let [running (first (reset-vals! simulation nil))]
    (async/close! (:shutdown running))
    true))

(defn start!
  "Starts simulated playback on the given player numbers, cycling through
  the sample tracks. Call this before opening the displays."
  ([]
   (start! {}))
  ([{:keys [players] :or {players [1 2 3 4]}}]
   (stop!)
   (let [tracks   (mapv #(util/data-for-simulation :entry %) sample-tracks)
         shutdown (async/promise-chan)
         built    (into {} (map-indexed (fn [index number]
                                          [number (make-player number
                                                               (nth tracks (mod index (count tracks)))
                                                               index)])
                                        players))]
     (reset! simulation {:players built :shutdown shutdown})
     (async/go-loop [previous (System/nanoTime)]
       (let [timeout     (async/timeout tick-interval)
             [_ channel] (async/alts! [shutdown timeout])]
         (when (= channel timeout)
           (let [now     (System/nanoTime)
                 elapsed (/ (- now previous) 1e6)]
             (try
               (swap! simulation update :players update-vals #(advance % elapsed))
               (catch Throwable t
                 (timbre/error t "Problem advancing the bar display simulation.")))
             (recur now)))))
     (timbre/info "Bar display: simulating players" (pr-str players))
     (vec (sort (keys built))))))

(defn player-numbers
  "Returns the simulated player numbers, or an empty sequence when no
  simulation is running."
  []
  (sort (keys (:players @simulation))))

(defn track
  "Returns the simulated track data loaded on a player, which
  `bardisplay.cell` needs to build its waveform components."
  [number]
  (get-in @simulation [:players number :track]))

(defn- beat-within-bar
  "Works out the beat of the bar at a position in a simulated track."
  [track time]
  (when-let [^BeatGrid grid (:grid track)]
    (let [beat (.findBeatAtTime grid time)]
      (when (pos? beat)
        (.getBeatWithinBar grid beat)))))

(defn snapshot
  "Returns the state of a simulated player in the same shape as
  `bardisplay.state/snapshot`, or `nil` if that player is not being
  simulated."
  [number]
  (when-let [{:keys [track time playing pitch on-air master synced]}
             (get-in @simulation [:players number])]
    (let [metadata (:metadata track)
          played   (long time)]
      {:player   number
       :playing  playing
       :on-air   on-air
       :master   master
       :synced   synced
       :bpm-only false
       :beat     (beat-within-bar track played)
       :pitch    pitch
       :tempo    (when-let [tempo (:starting-tempo metadata)]
                   (* tempo (+ 1.0 (/ pitch 100.0))))
       :played   played
       :remain   (max 0 (- (duration-ms track) played))
       :precise  true
       :title    (:title metadata)
       :artist   (:artist metadata)})))
