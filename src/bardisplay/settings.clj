(ns bardisplay.settings
  "The settings window. Everything the bar displays can be adjusted from
  lives here, so nothing has to be edited in source once it is running."
  (:require [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.i18n :as i18n :refer [t]]
            [bardisplay.screen :as screen]
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

(def block-keys
  "The display blocks that can be switched on and off, in the order the
  settings list them. Each has a `:block/<name>` text."
  [:track :status :tempo :time :beat :detail :preview :phrase :key :loop :cue])

(defn- scroll-choices []
  [(->Choice :off (t :scroll/off)) (->Choice :occasional (t :scroll/occasional))])

(defn- unit-choices []
  [(->Choice :bars (t :unit/bars)) (->Choice :beats (t :unit/beats))])

(defn- language-choices []
  (into [(->Choice :auto (t :language/auto))]
        (map (fn [{:keys [code name]}] (->Choice code name)) (i18n/available))))

(defn- choice-for
  "Finds the choice for `k`, or the first one."
  [choices k]
  (or (first (filter #(= (:key %) k) choices)) (first choices)))

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

(defn simulated-players
  "The players a simulation runs for: those the panels show, limited to
  1-4 like a typical four deck setup, so players 5 and 6 can be used to
  see how an offline player looks."
  [panels]
  (vec (sort (distinct (filter #(<= 1 % 4) (mapcat :players panels))))))

(defn apply-settings!
  "Brings the displays in line with a settings map, starting or stopping
  the simulation as required. Returns the open displays, or `nil`."
  [{:keys [display panels simulate? language] :as settings}]
  (i18n/set-language! language)
  (theme/set-thresholds! (:warn-seconds display) (:urgent-seconds display))
  (core/close!)
  (if simulate?
    (sim/start! {:players (simulated-players panels)})
    (sim/stop!))
  (core/open! (config/panels settings) {:on-bounds config/save-window-bounds!}))

(defn apply-saved!
  "Opens the displays using the settings last saved from the window."
  []
  (apply-settings! (config/load-settings)))

(defn- with-stored
  "Adds the language and presets saved on disk to settings read from the
  form, which does not hold them."
  [settings]
  (merge settings (select-keys (config/load-settings) [:language :presets])))

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
      (conj choices (config/->ScreenChoice (:id saved) (t :screen/not-connected (:id saved)) saved))
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
     :remove        (seesaw/button :text (t :settings/remove))
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
                  (into [[(seesaw/label :text (t :settings/screen)) ""]
                         [(seesaw/label :text (t :settings/arrangement)) ""]
                         [(seesaw/label :text (t :settings/players)) ""]
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
  "Reads one panel row. Returns `{:panel ...}` or `{:error ...}`.
  `saved-bounds` is where that panel's window was last left, which is
  newer than what the row was built with if it was moved since."
  [{:keys [screen layout players window-bounds]} saved-bounds]
  (let [{:keys [error] :as parsed} (config/parse-players (seesaw/text players))]
    (if error
      {:error error}
      {:panel {:screen        (:spec (seesaw/selection screen))
               :layout        (:key (seesaw/selection layout))
               :players       (:players parsed)
               :window-bounds (or saved-bounds window-bounds (:window-bounds config/default-panel))}})))

(defn- block-id
  "The widget ID of a block's checkbox."
  [k]
  (keyword (str "block-" (name k))))

(defn- read-form
  "Collects the window into a settings map (without language and
  presets). Returns `{:settings ...}` or `{:errors [...]}`."
  [root rows]
  (let [saved   (mapv :window-bounds (:panels (config/load-settings)))
        results (map-indexed (fn [i row] (read-panel row (get saved i))) @rows)
        errors  (keep-indexed (fn [i {:keys [error]}] (when error (t :status/panel-error (inc i) error))) results)
        pick    (fn [id] (seesaw/selection (seesaw/select root [(keyword (str "#" (name id)))])))]
    (if (seq errors)
      {:errors (vec errors)}
      {:settings (config/normalize
                  {:panels    (mapv :panel results)
                   :display   {:blocks          (set (filter #(pick (block-id %)) block-keys))
                               :brightness      (pick :brightness)
                               :title-scroll    (:key (pick :scroll))
                               :phrase-unit     (:key (pick :phrase-unit))
                               :warn-seconds    (pick :warn)
                               :urgent-seconds  (pick :urgent)
                               :scale           (pick :scale)
                               :always-on-top?  (pick :on-top)
                               :dim-off-air?    (pick :dim-off-air)
                               :flash-downbeat? (pick :flash)}
                   :simulate? (pick :simulate)})})))

;;; Display section ------------------------------------------------------------

(defn- display-section
  "Lays out the options every panel shares."
  [display]
  (mig/mig-panel
    :constraints ["insets 0, hidemode 3" "[right]12[grow,fill]" "[]8[]"]
    :items
    [[(seesaw/label :text (t :settings/show)) "top"]
     [(mig/mig-panel
        :constraints ["insets 0" "[]16[]" ""]
        :items (vec (map-indexed (fn [i k]
                                   [(seesaw/checkbox :id (block-id k) :text (t (keyword "block" (name k)))
                                                     :selected? (contains? (:blocks display) k))
                                    (if (odd? i) "wrap" "")])
                                 block-keys)))
      "wrap"]

     [(seesaw/label :text (t :settings/brightness)) ""]
     [(seesaw/slider :id :brightness :min 20 :max 100 :value (:brightness display)
                     :major-tick-spacing 20 :minor-tick-spacing 10 :paint-labels? true)
      "wrap"]

     [(seesaw/label :text (t :settings/scroll)) ""]
     [(seesaw/combobox :id :scroll :model (scroll-choices)) "wrap"]

     [(seesaw/label :text (t :settings/phrase-unit)) ""]
     [(seesaw/combobox :id :phrase-unit :model (unit-choices)) "wrap"]

     [(seesaw/label :text (t :settings/zoom)) ""]
     [(seesaw/slider :id :scale :min 1 :max 16 :value (:scale display)
                     :major-tick-spacing 5 :minor-tick-spacing 1
                     :snap-to-ticks? true :paint-labels? true)
      "wrap"]

     [(seesaw/label :text (t :settings/warn)) ""]
     [(seesaw/spinner :id :warn
                      :model (seesaw/spinner-model (:warn-seconds display) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text (t :settings/seconds)) "wrap"]

     [(seesaw/label :text (t :settings/urgent)) ""]
     [(seesaw/spinner :id :urgent
                      :model (seesaw/spinner-model (:urgent-seconds display) :from 5 :to 600 :by 5))
      "split 2"]
     [(seesaw/label :text (t :settings/seconds)) "wrap"]

     [(seesaw/label :text (t :settings/options)) "top"]
     [(seesaw/checkbox :id :dim-off-air :text (t :settings/dim-off-air)
                       :selected? (:dim-off-air? display))
      "wrap"]
     [(seesaw/label :text "") ""]
     [(seesaw/checkbox :id :flash :text (t :settings/flash)
                       :selected? (:flash-downbeat? display))
      "wrap"]
     [(seesaw/label :text "") ""]
     [(seesaw/checkbox :id :on-top :text (t :settings/on-top)
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
        (let [settings (with-stored settings)]
          (config/save-settings! settings)
          (apply-settings! settings))
        (show-status! root (status-lines))
        (catch Throwable e
          (timbre/error e "Could not apply bar display settings.")
          (show-status! root [{:level :error :text (t :status/failed (.getMessage e))}]))))))

(defn- stop!
  "Closes the displays and stops any simulation."
  [root]
  (core/close!)
  (sim/stop!)
  (show-status! root (status-lines)))

(defn- preset-names
  "The saved presets, sorted."
  []
  (vec (sort (keys (:presets (config/load-settings))))))

(defn- refresh-presets!
  "Shows the saved presets, selecting `selected` if given."
  [root selected]
  (let [names (preset-names)
        combo (seesaw/select root [:#preset])]
    (seesaw/config! combo :model names)
    (when (and selected (some #{selected} names))
      (seesaw/selection! combo selected))
    (doseq [id [:#preset :#load-preset :#delete-preset]]
      (seesaw/config! (seesaw/select root [id]) :enabled? (boolean (seq names))))))

(declare show!)

(defn- rebuild!
  "Closes the window and opens it again showing `settings`, which is how
  a preset is loaded into the form and a new language takes effect."
  [^JFrame root settings]
  (.dispose root)
  (reset! window nil)
  (show! settings))

(defn- current-form
  "The settings the window shows right now, or the saved ones if the
  form has errors."
  [root rows]
  (with-stored (or (:settings (read-form root rows)) (config/load-settings))))

(defn- save-preset!
  "Asks for a name and stores the form as a preset."
  [root rows]
  (let [{:keys [errors settings]} (read-form root rows)]
    (if (seq errors)
      (show-status! root (mapv (fn [e] {:level :error :text e}) errors))
      (when-let [preset-name (some-> (seesaw/input root (t :preset/name-prompt)) str/trim not-empty)]
        (config/save-settings! (config/save-preset (merge (config/load-settings) settings) preset-name))
        (refresh-presets! root preset-name)))))

(defn- top-section
  "The language and preset choices at the top of the window."
  []
  (mig/mig-panel
    :constraints ["insets 0" "[right]12[]12[][][]" ""]
    :items [[(seesaw/label :text (t :settings/language)) ""]
            [(seesaw/combobox :id :language :model (language-choices)) "wrap"]
            [(seesaw/label :text (t :settings/preset)) ""]
            [(seesaw/combobox :id :preset :model []) "growx"]
            [(seesaw/button :id :load-preset :text (t :preset/load)) ""]
            [(seesaw/button :id :save-preset :text (t :preset/save)) ""]
            [(seesaw/button :id :delete-preset :text (t :preset/delete)) ""]]))

(defn show!
  "Opens the bar display settings window, bringing it to the front if it
  is already open. Given `settings`, the form shows those instead of the
  saved ones (they still only take effect with Apply)."
  ([]
   (show! nil))
  ([settings]
   (if-let [^JFrame existing @window]
     (do (.toFront existing) existing)
     (let [settings (or settings (config/load-settings))
           _        (i18n/set-language! (:language settings))
           display  (:display settings)
           rows     (atom [])
           area     (mig/mig-panel :constraints ["insets 0" "[][][grow,fill][]" ""])
           content  (mig/mig-panel
                      :constraints ["fillx" "[grow,fill]" ""]
                      :items [[(top-section) "wrap 16"]
                              [(seesaw/label :text (str "<html><b>" (t :settings/panels) "</b></html>")) "wrap"]
                              [area "wrap"]
                              [(seesaw/flow-panel
                                 :align :left :hgap 0
                                 :items [(seesaw/button :id :add-panel :text (t :settings/add-panel))
                                         (seesaw/button :id :identify :text (t :settings/identify))])
                               "wrap 16"]
                              [(seesaw/label :text (str "<html><b>" (t :settings/display) "</b></html>")) "wrap"]
                              [(display-section display) "wrap 12"]
                              [(seesaw/checkbox :id :simulate :text (t :settings/simulate)
                                                :selected? (:simulate? settings))
                               "wrap 12"]
                              [(seesaw/label :id :status :text (t :settings/not-running)) "growx, wrap"]
                              [(seesaw/flow-panel :align :right
                                                  :items [(seesaw/button :id :stop :text (t :settings/stop))
                                                          (seesaw/button :id :apply :text (t :settings/apply))])
                               "growx"]])
           root     (seesaw/frame :title (t :settings/title)
                                  :content content
                                  :on-close :dispose
                                  :resizable? false)
           language (seesaw/select root [:#language])]
       (seesaw/selection! (seesaw/select root [:#scroll]) (choice-for (scroll-choices) (:title-scroll display)))
       (seesaw/selection! (seesaw/select root [:#phrase-unit]) (choice-for (unit-choices) (:phrase-unit display)))
       (seesaw/selection! language (choice-for (language-choices) (:language settings)))
       (doseq [panel (:panels settings)]
         (add-row! root area rows panel))
       (refresh-presets! root nil)

       ;; Listeners go on after the initial selections, so setting those does not fire them.
       (seesaw/listen language :selection
                      (fn [_]
                        (let [chosen (:key (seesaw/selection language))]
                          (when (not= chosen (:language settings))
                            (config/save-settings! (assoc (config/load-settings) :language chosen))
                            (rebuild! root (assoc (current-form root rows) :language chosen))))))
       (seesaw/listen (seesaw/select root [:#load-preset]) :action
                      (fn [_]
                        (when-let [preset-name (seesaw/selection (seesaw/select root [:#preset]))]
                          (rebuild! root (config/load-preset (config/load-settings) preset-name)))))
       (seesaw/listen (seesaw/select root [:#save-preset]) :action (fn [_] (save-preset! root rows)))
       (seesaw/listen (seesaw/select root [:#delete-preset]) :action
                      (fn [_]
                        (when-let [preset-name (seesaw/selection (seesaw/select root [:#preset]))]
                          (config/save-settings! (config/delete-preset (config/load-settings) preset-name))
                          (refresh-presets! root nil))))
       (seesaw/listen (seesaw/select root [:#add-panel]) :action
                      (fn [_]
                        (add-row! root area rows (new-panel (count @rows)))
                        (show-rows! root area rows)))
       (seesaw/listen (seesaw/select root [:#identify]) :action (fn [_] (screen/identify!)))
       (seesaw/listen (seesaw/select root [:#apply]) :action (fn [_] (apply! root rows)))
       (seesaw/listen (seesaw/select root [:#stop]) :action (fn [_] (stop! root)))
       (seesaw/listen root :window-closed (fn [_] (compare-and-set! window root nil)))
       (theme/set-thresholds! (:warn-seconds display) (:urgent-seconds display))
       (show-rows! root area rows)
       (show-status! root (status-lines))
       (.setLocationRelativeTo root nil)
       (seesaw/show! root)
       (reset! window root)))))
