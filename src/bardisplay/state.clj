(ns bardisplay.state
  "Gathers everything the bar displays need to know about a player into
  a single immutable snapshot, so the painters never have to talk to
  Beat Link directly and the animation loop can cheaply detect which
  parts of a cell actually need repainting."
  (:require [bardisplay.analysis :as analysis]
            [bardisplay.sim :as sim]
            [beat-link-trigger.players :as players]
            [beat-link-trigger.simulator :as simulator]
            [taoensso.timbre :as timbre])
  (:import [org.deepsymmetry.beatlink CdjStatus DeviceFinder DeviceUpdate Util VirtualCdj]
           [org.deepsymmetry.beatlink.data AnalysisTagFinder MetadataFinder SearchableItem TimeFinder
            TrackMetadata TrackPositionUpdate WaveformDetail WaveformFinder]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$TaggedSection]))

(def ^VirtualCdj virtual-cdj (VirtualCdj/getInstance))
(def ^DeviceFinder device-finder (DeviceFinder/getInstance))
(def ^TimeFinder time-finder (TimeFinder/getInstance))
(def ^MetadataFinder metadata-finder (MetadataFinder/getInstance))
(def ^WaveformFinder waveform-finder (WaveformFinder/getInstance))
(def ^AnalysisTagFinder analysis-finder (AnalysisTagFinder/getInstance))

(defn- online?
  "Checks whether a player is currently announcing itself on the network."
  [n]
  (and (.isRunning device-finder)
       (some? (.getLatestAnnouncementFrom device-finder (int n)))))

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

(defn- beat-number
  "Returns the beat number a player is on within its track, if known."
  [n]
  (when (.isRunning time-finder)
    (when-let [^TrackPositionUpdate update (.getLatestPositionFor time-finder (int n))]
      (let [beat (.-beatNumber update)]
        (when (pos? beat)
          beat)))))

(defn- tempo-values
  "Returns the playback pitch percentage and effective tempo of a
  player, or `nil` if we have no update from it."
  [n]
  (when-let [^DeviceUpdate update (when (.isRunning time-finder) (.getLatestUpdateFor time-finder (int n)))]
    ;; A BPM of 65535 is how players report that the tempo is not currently valid.
    [(Util/pitchToPercentage (.getPitch update))
     (when (not= 65535 (.getBpm update)) (.getEffectiveTempo update))]))

(defonce ^{:private true
           :doc "Converted analysis per kind and player, together with the Beat Link object
  it came from, so it is only converted again when the track changes."}
  converted
  (atom {}))

(defn- cached
  "Returns `(convert source)`, reusing the previous result for this kind
  and player while `source` is the same object."
  [kind n source convert]
  (when source
    (let [[previous value] (get-in @converted [kind n])]
      (if (identical? previous source)
        value
        (let [value (convert source)]
          (swap! converted assoc-in [kind n] [source value])
          value)))))

(defn- track-info
  "Returns the title, artist, key and cues of the track loaded on a
  player."
  [n]
  (when-let [^TrackMetadata metadata (try
                                       (when (.isRunning metadata-finder)
                                         (.getLatestMetadataFor metadata-finder (int n)))
                                       (catch Exception e
                                         (timbre/debug e "Unable to read metadata for player" n)))]
    {:title  (.getTitle metadata)
     :artist (when-let [^SearchableItem artist (.getArtist metadata)] (.label artist))
     :key    (when-let [^SearchableItem k (.getKey metadata)] (.label k))
     :cues   (cached :cues n (.getCueList metadata) analysis/cues)}))

(defn- phrases
  "Returns the phrase analysis of the track loaded on a player."
  [n]
  (when (.isRunning analysis-finder)
    (when-let [^RekordboxAnlz$TaggedSection tagged
               (.getLatestTrackAnalysisFor analysis-finder (int n) ".EXT" "PSSI")]
      (cached :phrases n (.body tagged) analysis/phrases))))

(def empty-snapshot
  "The snapshot describing a cell that is not currently showing any
  player."
  {:player 0})

(defn- live-snapshot
  "Collects the current state of a real player on the network."
  [n]
  (if-not (online? n)
    {:player n :online? false}
    (let [^CdjStatus status (latest-status n)
          raw-played        (players/time-played n)
          played            (when (and raw-played (>= raw-played 0)) raw-played)
          [pitch tempo]     (tempo-values n)
          info              (track-info n)]
      {:player     n
       :online?    true
       :playing    (boolean (when status (.isPlaying status)))
       :on-air     (boolean (when status (.isOnAir status)))
       :master     (boolean (when status (.isTempoMaster status)))
       :synced     (boolean (when status (.isSynced status)))
       :bpm-only   (boolean (when status (.isBpmOnlySynced status)))
       :beat       (current-beat n)
       :pitch      pitch
       :tempo      tempo
       :played     played
       :remain     (time-left n played)
       :precise    (boolean (players/precise? n))
       :title      (:title info)
       :artist     (:artist info)
       :key        (:key info)
       :phrase     (analysis/phrase-at (phrases n) (beat-number n))
       ;; nil when the player cannot tell us (anything before the CDJ-3000).
       :looping    (when (and status (.canReportLooping status)) (.isLooping status))
       :loop-beats (when status (.getActiveLoopBeats status))
       :next-cue   (analysis/next-cue (:cues info) played)})))

(defn snapshot
  "Collects the current state of a player into a map. Passing player
  number 0 yields the idle snapshot."
  [n]
  (cond
    (not (pos? n)) empty-snapshot
    (sim/active?)  (or (sim/snapshot n) {:player n :online? false})
    :else          (live-snapshot n)))
