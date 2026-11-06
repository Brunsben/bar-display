(ns bardisplay.analysis
  "Pure helpers that turn Beat Link track analysis (phrases and cues)
  into the small values the info block shows. Nothing here talks to the
  network, so it can be tested directly."
  (:import [org.deepsymmetry.beatlink Util]
           [org.deepsymmetry.beatlink.data CueList CueList$Entry]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$SongStructureEntry
            RekordboxAnlz$SongStructureTag]))

(defn phrases
  "Converts a rekordbox song structure (phrase analysis) tag into
  `{:end-beat n :entries [{:beat :label :color :text-color} ...]}`,
  sorted by beat. Returns `nil` when there is no tag."
  [^RekordboxAnlz$SongStructureTag tag]
  (when tag
    (let [body (.body tag)]
      {:end-beat (.endBeat body)
       :entries  (->> (.entries body)
                      (map (fn [^RekordboxAnlz$SongStructureEntry entry]
                             {:beat       (.beat entry)
                              :label      (Util/phraseLabel entry)
                              :color      (Util/phraseColor entry)
                              :text-color (Util/phraseTextColor entry)}))
                      (sort-by :beat)
                      vec)})))

(defn phrase-at
  "Finds the phrase playing at `beat`, adding `:beats-left` until the
  next phrase (or the end of the analysis). Returns `nil` outside the
  analysed range or when anything is missing."
  [{:keys [entries end-beat]} beat]
  (when (and beat (seq entries) end-beat
             (>= beat (:beat (first entries)))
             (< beat end-beat))
    (let [[before after] (split-with #(<= (:beat %) beat) entries)
          next-beat      (if-let [following (first after)] (:beat following) end-beat)]
      (assoc (last before) :beats-left (- next-beat beat)))))

(defn cues
  "Converts a cue list into `[{:ms :color} ...]` sorted by time, covering
  hot cues and memory points. Returns `nil` when there is no cue list."
  [^CueList cue-list]
  (when cue-list
    (->> (.entries cue-list)
         (map (fn [^CueList$Entry entry]
                {:ms (.cueTime entry) :color (.getColor entry)}))
         (sort-by :ms)
         vec)))

(defn next-cue
  "Finds the first cue after `now-ms`, as `{:ms-left :color}`."
  [cues now-ms]
  (when now-ms
    (when-let [cue (first (filter #(> (:ms %) now-ms) cues))]
      {:ms-left (- (:ms cue) now-ms) :color (:color cue)})))
