(ns bardisplay.state
  "Gathers everything the bar displays need to know about a player into
  a single immutable snapshot, so the painters never have to talk to
  Beat Link directly and the animation loop can cheaply detect which
  parts of a cell actually need repainting."
  (:require [beat-link-trigger.players :as players]
            [beat-link-trigger.simulator :as simulator]
            [beat-link-trigger.util :as util]
            [bardisplay.sim :as sim]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink CdjStatus DeviceUpdate Util VirtualCdj]
           [org.deepsymmetry.beatlink.data MetadataFinder SearchableItem TimeFinder
            TrackMetadata TrackPositionUpdate WaveformDetail WaveformFinder]))

(def ^VirtualCdj virtual-cdj (VirtualCdj/getInstance))
(def ^TimeFinder time-finder (TimeFinder/getInstance))
(def ^MetadataFinder metadata-finder (MetadataFinder/getInstance))
(def ^WaveformFinder waveform-finder (WaveformFinder/getInstance))

(defn- latest-status
  "Returns the most recent status packet from a player, or `nil` if we
  have none. Absorbs the exceptions thrown while going offline."
  ^CdjStatus [n]
  (when (and (pos? n) (.isRunning virtual-cdj))
    (try
      (.getLatestStatusFor virtual-cdj (int n))
      (catch Exception _))))

(defn- time-left
  "Returns the number of milliseconds left to play on a player, given
  the time it has already played."
  [n played]
  (when played
    (when-let [^WaveformDetail detail (or (get-in (simulator/for-player n) [:track :detail])
                                          (when (.isRunning waveform-finder)
                                            (.getLatestDetailFor waveform-finder (int n))))]
      (max 0 (- (.getTotalTime detail) played)))))

(defn- current-beat
  "Determines the beat within the musical bar that a player is on, if
  known. Falls back to the status packet, because CDJ-3000s report a
  position even when no beat grid is available."
  [n]
  (let [precise (when (.isRunning time-finder)
                  (when-let [^TrackPositionUpdate update (.getLatestPositionFor time-finder (int n))]
                    (.getBeatWithinBar update)))
        beat    (if (and precise (pos? precise))
                  precise
                  (when-let [^CdjStatus status (latest-status n)]
                    (.getBeatWithinBar status)))]
    (when (and beat (<= 1 beat 4))
      beat)))

(defn- tempo-values
  "Returns the playback pitch percentage and effective tempo of a
  player, or `nil` if we have no update from it."
  [n]
  (when-let [^DeviceUpdate update (when (.isRunning time-finder) (.getLatestUpdateFor time-finder (int n)))]
    ;; A BPM of 65535 is how players report that the tempo is not currently valid.
    [(Util/pitchToPercentage (.getPitch update))
     (when (not= 65535 (.getBpm update)) (.getEffectiveTempo update))]))

(defn- track-labels
  "Returns the title and artist of the track loaded on a player."
  [n]
  (when-let [^TrackMetadata metadata (try
                                       (when (.isRunning metadata-finder)
                                         (.getLatestMetadataFor metadata-finder (int n)))
                                       (catch Exception e
                                         (timbre/debug e "Unable to read metadata for player" n)))]
    [(.getTitle metadata)
     (when-let [^SearchableItem artist (.getArtist metadata)] (.label artist))]))

(def empty-snapshot
  "The snapshot describing a cell that is not currently showing any
  player."
  {:player 0})

(defn- live-snapshot
  "Collects the current state of a real player on the network."
  [n]
  (let [^CdjStatus status (latest-status n)
        played            (players/time-played n)
        [pitch tempo]     (tempo-values n)
        [title artist]    (track-labels n)]
    {:player   n
     :playing  (boolean (when status (.isPlaying status)))
     :on-air   (boolean (when status (.isOnAir status)))
     :master   (boolean (when status (.isTempoMaster status)))
     :synced   (boolean (when status (.isSynced status)))
     :bpm-only (boolean (when status (.isBpmOnlySynced status)))
     :beat     (current-beat n)
     :pitch    pitch
     :tempo    tempo
     :played   (when (and played (>= played 0)) played)
     :remain   (time-left n (when (and played (>= played 0)) played))
     :precise  (boolean (players/precise? n))
     :title    title
     :artist   artist}))

(defn snapshot
  "Collects the current state of a player into a map. Passing player
  number 0 yields the idle snapshot."
  [n]
  (cond
    (not (pos? n)) empty-snapshot
    (sim/active?)  (or (sim/snapshot n) empty-snapshot)
    :else          (live-snapshot n)))

(defn online-players
  "Returns the sorted numbers of the players the displays should show:
  the simulated ones while a simulation is running, otherwise the real
  players currently visible on the network."
  []
  (cond
    (sim/active?) (sim/player-numbers)
    (util/online?) (sort (filter #(<= 1 % 6) (util/visible-player-numbers)))
    :else []))
