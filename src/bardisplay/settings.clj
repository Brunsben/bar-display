(ns bardisplay.settings
  "The settings window. Everything the bar displays can be adjusted from
  lives here, so nothing has to be edited in source once it is running."
  (:require [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.sim :as sim]
            [bardisplay.status :as status]
            [bardisplay.theme :as theme]
            [beat-link-trigger.util :as util]
            [clojure.string :as str]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre])
  (:import [javax.swing JFrame]))

(defonce ^{:private true
           :doc "Holds the settings window while it is open."}
  window (atom nil))

(defrecord Choice [key label]
  Object
  (toString [_] label))

(def block-choices
  "The display blocks that can be switched on and off, with their labels."
  [[:track "Title and artist"]
   [:status "On air / master / sync"]
   [:tempo "Tempo"]
   [:time "Time"]
   [:detail "Scrolling waveform"]
   [:preview "Waveform overview"]
   [:phrase "Phrase"]
   [:key "Key"]
   [:loop "Loop"]
   [:cue "Next cue"]])

(def scroll-choices
  "The title scrolling modes."
  [(->Choice :off "Off") (->Choice :occasional "Occasionally")])

;;; Running the displays -------------------------------------------------------

(defn visible-players
  "The player numbers currently providing data: the simulated ones, or
  the real players on the network."
  []
  (cond
    (sim/active?)  (set (sim/player-numbers))
    (util/online?) (set (filter #(<= 1 % 6) (util/visible-player-numbers)))
    :else          #{}))

(defn status-lines
  "Describes what the displays are doing right now."
  []
  (status/lines {:online?     (util/online?)
                 :simulating? (sim/active?)
                 :visible     (visible-players)
                 :panels      (core/status)}))

(defn apply-settings!
  "Brings the displays in line with a settings map, starting or stopping
  the simulation as required. Returns the open displays, or `nil`."
  [{:keys [display panels simulate?] :as settings}]
  (theme/set-thresholds! (:warn-seconds display) (:urgent-seconds display))
  (core/close!)
  (if simulate?
    (sim/start! {:players (vec (sort (distinct (mapcat :players panels))))})
    (sim/stop!))
  (core/open! (config/panels settings)))

(defn apply-saved!
  "Opens the displays using the settings last saved from the window."
  []
  (apply-settings! (config/load-settings)))

;;; Panel rows -----------------------------------------------------------------

(defn- saved-id
  "The ID a saved screen is matched by."
  [saved]
  (if (map? saved) (:id saved) saved))

(defn- screen-model
  "The screen choices, plus the saved screen if it is not connected right
  now, so opening the window does not lose it."
  [saved]
  (let [choices (config/screen-choices)]
    (if (and (map? saved) (not-any? #(= (:id %) (:id saved)) choices))
      (conj choices (config/->ScreenChoice (:id saved) (str (:id saved) "  (not connected)") saved))
      choices)))

(defn- panel-row
  "Builds the widgets for one panel."
  [panel]
  (let [model  (screen-model (:screen panel))
        screen (seesaw/combobox :model model)
        layout (seesaw/combobox :model config/layouts)]
    (when-let [choice (first (filter #(= (:id %) (saved-id (:screen panel))) model))]
      (seesaw/selection! screen choice))
    (seesaw/selection! layout (config/layout-for (:layout panel)))
    {:screen        screen
     :layout        layout
     :players       (seesaw/text :text (str/join " " (:players panel)) :columns 8)
     :remove        (seesaw/button :text "Remove")
     :window-bounds (:window-bounds panel)}))

(defn- new-panel
  "A fresh panel for the `index`th row, its window offset so it does not
  cover the others."
  [index]
  (let [[x y w h] (:window-bounds config/default-panel)]
    (assoc config/default-panel
           :players []
           :window-bounds [(+ x (* 60 index)) (+ y (* 60 index)) w h])))

(defn- show-rows!
  "Lays the panel rows out again after one was added or removed."
  [root area rows]
  (seesaw/config! area :items
                  (into [[(seesaw/label :text "Screen") ""]
                         [(seesaw/label :text "Arrangement") ""]
                         [(seesaw/label :text "Players") ""]
                         [(seesaw/label :text "") "wrap"]]
                        (mapcat (fn [{:keys [screen layout players remove]}]
                                  [[screen ""] [layout ""] [players "growx"] [remove "wrap"]])
                                @rows)))
  (doseq [row @rows]
    (seesaw/config! (:remove row) :enabled? (> (count @rows) 1)))
  (seesaw/config! (seesaw/select root [:#add-panel]) :enabled? (< (count @rows) config/max-panels))
  (seesaw/pack! root))

(defn- add-row!
  "Adds a row for `panel`, wiring up its Remove button."
  [root area rows panel]
  (let [row (panel-row panel)]
    (seesaw/listen (:remove row) :action
                   (fn [_]
                     (swap! rows (fn [current] (filterv #(not (identical? % row)) current)))
                     (show-rows! root area rows)))
    (swap! rows conj row)))

(defn- read-panel
  "Reads one panel row. Returns `{:panel ...}` or `{:error ...}`."
  [{:keys [screen layout players window-bounds]}]
  (let [{:keys [error] :as parsed} (config/parse-players (seesaw/text players))]
    (if error
      {:error error}
      {:panel {:screen        (:spec (seesaw/selection screen))
               :layout        (:key (seesaw/selection layout))
               :players       (:players parsed)
               :window-bounds (or window-bounds (:window-bounds config/default-panel))}})))

(defn- block-id
  "The widget ID of a block's checkbox."
  [k]
  (keyword (str "block-" (name k))))

(defn- read-form
  "Collects the window into a settings map. Returns `{:settings ...}` or
  `{:errors [...]}`."
  [root rows]
  (let [results (map read-panel @rows)
        errors  (keep-indexed (fn [i {:keys [error]}] (when error (str "Panel " (inc i) ": " error))) results)
        pick    (fn [id] (seesaw/selection (seesaw/select root [(keyword (str "#" (name id)))])))]
    (if (seq errors)
      {:errors (vec errors)}
      {:settings (config/normalize
                  {:panels    (mapv :panel results)
                   :display   {:blocks         (set (for [[k _] block-choices :when (pick (block-id k))] k))
                               :brightness     (pick :brightness)
                               :title-scroll   (:key (pick :scroll))
                               :warn-seconds   (pick :warn)
                               :urgent-seconds (pick :urgent)
                               :scale          (pick :scale)
                               :always-on-top? (pick :on-top)}
                   :simulate? (pick :simulate)})})))

;;; Display section ------------------------------------------------------------

(defn- display-section
  "Lays out the options every panel shares."
  [display]
  (mig/mig-panel
    :constraints ["insets 0, hidemode 3" "[right]12[grow,fill]" "[]8[]"]
    :items
    [[(seesaw/label :text "Show") "top"]
     [(mig/mig-panel
        :constraints ["insets 0" "[]16[]" ""]
        :items (vec (map-indexed (fn [i [k label]]
                                   [(seesaw/checkbox :id (block-id k) :text label
                                                     :selected? (contains? (:blocks display) k))
                                    (if (odd? i) "wrap" "")])
                                 block-choices)))
      "wrap"]

     [(seesaw/label :text "Brightness") ""]
     [(seesaw/slider :id :brightness :min 20 :max 100 :value (:brightness display)
                     :major-tick-spacing 20 :minor-tick-spacing 10 :paint-labels? true)
      "wrap"]

     [(seesaw/label :text "Scroll long titles") ""]
     [(seesaw/combobox :id :scroll :model scroll-choices) "wrap"]

     [(seesaw/label :text "Waveform zoom") ""]
     [(seesaw/slider :id :scale :min 1 :max 16 :value (:scale display)
                     :major-tick-spacing 5 :minor-tick-spacing 1
                     :snap-to-ticks? true :paint-labels? true)
      "wrap"]

     [(seesaw/label :text "Warn when left") ""]
     [(seesaw/spinner :id :warn
                      :model (seesaw/spinner-model (:warn-seconds display) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text "seconds") "wrap"]

     [(seesaw/label :text "Urgent when left") ""]
     [(seesaw/spinner :id :urgent
                      :model (seesaw/spinner-model (:urgent-seconds display) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text "seconds") "wrap"]

     [(seesaw/label :text "Options") ""]
     [(seesaw/checkbox :id :on-top :text "Keep above other windows"
                       :selected? (:always-on-top? display))
      "wrap"]]))

;;; The window -----------------------------------------------------------------

(defn- show-status!
  "Shows status lines at the bottom of the window."
  [root lines]
  (seesaw/config! (seesaw/select root [:#status]) :text (status/html lines)))

(defn- apply!
  "Applies what the window currently shows, and remembers it."
  [root rows]
  (let [{:keys [errors settings]} (read-form root rows)]
    (if (seq errors)
      (show-status! root (mapv (fn [e] {:level :error :text e}) errors))
      (try
        (config/save-settings! settings)
        (apply-settings! settings)
        (show-status! root (status-lines))
        (catch Throwable t
          (timbre/error t "Could not apply bar display settings.")
          (show-status! root [{:level :error :text (str "Failed: " (.getMessage t))}]))))))

(defn- stop!
  "Closes the displays and stops any simulation."
  [root]
  (core/close!)
  (sim/stop!)
  (show-status! root (status-lines)))

(defn show!
  "Opens the bar display settings window, bringing it to the front if it
  is already open."
  []
  (if-let [^JFrame existing @window]
    (do (.toFront existing) existing)
    (let [settings (config/load-settings)
          display  (:display settings)
          rows     (atom [])
          area     (mig/mig-panel :constraints ["insets 0" "[][][grow,fill][]" ""])
          content  (mig/mig-panel
                     :constraints ["fillx" "[grow,fill]" ""]
                     :items [[(seesaw/label :text "<html><b>Panels</b></html>") "wrap"]
                             [area "wrap"]
                             [(seesaw/button :id :add-panel :text "Add panel") "growx 0, left, wrap 16"]
                             [(seesaw/label :text "<html><b>Display</b></html>") "wrap"]
                             [(display-section display) "wrap 12"]
                             [(seesaw/checkbox :id :simulate :text "Simulate players (no CDJs needed)"
                                               :selected? (:simulate? settings))
                              "wrap 12"]
                             [(seesaw/label :id :status :text "Not running.") "growx, wrap"]
                             [(seesaw/flow-panel :align :right
                                                 :items [(seesaw/button :id :stop :text "Stop")
                                                         (seesaw/button :id :apply :text "Apply")])
                              "growx"]])
          root     (seesaw/frame :title "Bar Display Settings"
                                 :content content
                                 :on-close :dispose
                                 :resizable? false)]
      (seesaw/selection! (seesaw/select root [:#scroll])
                         (or (first (filter #(= (:key %) (:title-scroll display)) scroll-choices))
                             (first scroll-choices)))
      (doseq [panel (:panels settings)]
        (add-row! root area rows panel))
      (seesaw/listen (seesaw/select root [:#add-panel]) :action
                     (fn [_]
                       (add-row! root area rows (new-panel (count @rows)))
                       (show-rows! root area rows)))
      (seesaw/listen (seesaw/select root [:#apply]) :action (fn [_] (apply! root rows)))
      (seesaw/listen (seesaw/select root [:#stop]) :action (fn [_] (stop! root)))
      (seesaw/listen root :window-closed (fn [_] (reset! window nil)))
      (theme/set-thresholds! (:warn-seconds display) (:urgent-seconds display))
      (show-rows! root area rows)
      (show-status! root (status-lines))
      (.setLocationRelativeTo root nil)
      (seesaw/show! root)
      (reset! window root))))
