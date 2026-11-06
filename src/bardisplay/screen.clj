(ns bardisplay.screen
  "Finds the screen each panel should use, and creates the window that
  fills it, divided between one or more player cells."
  (:require [bardisplay.cell :as cell]
            [bardisplay.i18n :refer [t]]
            [bardisplay.theme :as theme]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.awt Color Cursor Graphics GraphicsDevice GraphicsEnvironment GridLayout Point Rectangle
            Toolkit]
           [java.awt.event ActionListener ComponentAdapter]
           [java.awt.image BufferedImage]
           [javax.swing JComponent JFrame JPanel KeyStroke Timer]))

(def bar-panel-aspect
  "A screen at least this much wider than it is tall is taken to be a bar
  panel when looking for a substitute."
  2.5)

(defn screen-devices
  "Returns the screens attached to this machine, in the order the
  graphics environment reports them."
  []
  (vec (.. GraphicsEnvironment getLocalGraphicsEnvironment getScreenDevices)))

(defn describe-screens
  "Returns a description of every attached screen, to help work out
  which identifier belongs to which bar panel."
  []
  (let [primary (.getIDstring (.getDefaultScreenDevice (GraphicsEnvironment/getLocalGraphicsEnvironment)))]
    (vec (map-indexed (fn [index ^GraphicsDevice device]
                        (let [^Rectangle bounds (.. device getDefaultConfiguration getBounds)]
                          {:index    index
                           :id       (.getIDstring device)
                           :x        (.-x bounds)
                           :y        (.-y bounds)
                           :width    (.-width bounds)
                           :height   (.-height bounds)
                           :primary? (= primary (.getIDstring device))}))
                      (screen-devices)))))

(defn find-screen
  "Resolves a screen identifier, which may be an integer index or the
  platform's ID string, to a graphics device."
  ^GraphicsDevice [screen]
  (let [devices (screen-devices)]
    (cond
      (integer? screen) (get devices screen)
      (string? screen)  (first (filter #(= screen (.getIDstring ^GraphicsDevice %)) devices))
      :else             nil)))

(defn resolve-screen
  "Picks the screen for a panel. `wanted` is the saved screen (`{:id
  :width :height :x :y}`, only `:id` required) or `:window`; `screens`
  come from `describe-screens`; `taken` is the set of IDs other panels
  already use. Tries the same ID, then a screen of the same size (the
  nearest one), then the widest bar shaped screen that is not the
  primary one. Returns `{:screen <description or :window or nil> :reason
  <:window :exact :same-size :widest :not-found>}`."
  [wanted screens taken]
  (if (= wanted :window)
    {:screen :window :reason :window}
    (let [free     (remove #(contains? taken (:id %)) screens)
          exact    (first (filter #(= (:id %) (:id wanted)) free))
          distance (fn [s] (+ (Math/abs (long (- (:x s) (or (:x wanted) 0))))
                              (Math/abs (long (- (:y s) (or (:y wanted) 0))))))
          same     (when (and (:width wanted) (:height wanted))
                     (->> free
                          (filter #(and (= (:width %) (:width wanted)) (= (:height %) (:height wanted))))
                          (sort-by distance)
                          first))
          widest   (->> free
                        (remove :primary?)
                        (filter #(>= (/ (double (:width %)) (:height %)) bar-panel-aspect))
                        (sort-by :width >)
                        first)]
      (cond
        exact  {:screen exact :reason :exact}
        same   {:screen same :reason :same-size}
        widest {:screen widest :reason :widest}
        :else  {:screen nil :reason :not-found}))))

(defn assign-screens
  "Resolves the screen of every panel in order, so no two panels end up
  on the same screen. Returns one `resolve-screen` result per panel,
  with the panel itself under `:panel`."
  [panels screens]
  (first (reduce (fn [[results taken] panel]
                   (let [{:keys [screen] :as result} (resolve-screen (:screen panel) screens taken)]
                     [(conj results (assoc result :panel panel))
                      (cond-> taken (map? screen) (conj (:id screen)))]))
                 [[] #{}]
                 panels)))

(defn screen-ids
  "The IDs of the screens attached right now."
  []
  (mapv :id (describe-screens)))

(defn screens-changed?
  "Checks whether the attached screens differ between two lists of IDs."
  [before after]
  (not= (set before) (set after)))

(defn identify!
  "Shows each screen's number, ID and size on that screen for a few
  seconds, so the panels can be matched to the screens in the settings.
  A click closes a label early."
  []
  (doseq [{:keys [index id width height primary?]} (describe-screens)]
    (when-let [^GraphicsDevice device (find-screen id)]
      (let [^Rectangle bounds (.. device getDefaultConfiguration getBounds)
            label             (seesaw/label
                               :text (str "<html><div style='text-align:center'>"
                                          "<span style='font-size:300%'>" (inc index) "</span><br>"
                                          id "  ·  " width " × " height
                                          (when primary? (str "<br>" (t :screen/primary)))
                                          "</div></html>")
                               :halign :center :valign :center
                               :foreground Color/WHITE
                               :font {:name "SansSerif" :style :bold :size (max 18 (quot height 12))})
            ^JFrame frame     (seesaw/frame :undecorated? true :on-close :dispose
                                            :content (seesaw/border-panel :center label :background Color/BLACK))
            close             (fn [_] (.dispose frame))]
        (seesaw/listen label :mouse-clicked close)
        (doto frame
          (.setBounds bounds)
          (.setAlwaysOnTop true))
        (seesaw/show! frame)
        (doto (Timer. 4000 (reify ActionListener (actionPerformed [_ e] (close e))))
          (.setRepeats false)
          (.start))))))

(defn dim-alpha
  "How opaque the black dimming layer is for a brightness percentage
  (clamped to 20-100)."
  [brightness]
  (let [b (-> (or brightness 100) (max 20) (min 100))]
    (- 1.0 (/ b 100.0))))

(defn- dimmer
  "A glass pane that darkens everything below it, including the Beat
  Link waveform components whose colors we cannot change."
  ^JComponent [alpha]
  (let [shade (Color. (float 0) (float 0) (float 0) (float alpha))]
    (doto (proxy [JComponent] []
            (paintComponent [^Graphics g]
              (.setColor g shade)
              (.fillRect g 0 0 (.getWidth ^JComponent this) (.getHeight ^JComponent this))))
      (.setOpaque false))))

(def ^:private blank-cursor
  "An invisible cursor, so no mouse pointer is ever parked on a panel."
  (delay (.createCustomCursor (Toolkit/getDefaultToolkit)
                              (BufferedImage. 16 16 BufferedImage/TYPE_INT_ARGB)
                              (Point. 0 0)
                              "blank")))

(defn- bind-escape!
  "Arranges for the Escape key to run `on-escape`, so the displays can
  always be dismissed even though they have no window decorations."
  [^JFrame frame on-escape]
  (let [root (.getRootPane frame)]
    (.put (.getInputMap root JComponent/WHEN_IN_FOCUSED_WINDOW)
          (KeyStroke/getKeyStroke "ESCAPE") "bardisplay-close")
    (.put (.getActionMap root) "bardisplay-close"
          (seesaw/action :handler (fn [_] (on-escape))))))

(defn- report-bounds!
  "Calls `on-bounds` with `[x y width height]` half a second after the
  window was last moved or resized, so dragging does not save each step."
  [^JFrame frame on-bounds]
  (let [timer (doto (Timer. 500 (reify ActionListener
                                  (actionPerformed [_ _]
                                    (let [b (.getBounds frame)]
                                      (on-bounds [(.-x b) (.-y b) (.-width b) (.-height b)])))))
                (.setRepeats false))]
    (.addComponentListener frame (proxy [ComponentAdapter] []
                                   (componentMoved [_] (.restart timer))
                                   (componentResized [_] (.restart timer))))))

(defn create
  "Opens a window covering one bar panel and points its cells at the
  panel's players.

  `panel` keys:

  * `:screen-id` the screen's ID string, or `:window` for an ordinary
    window
  * `:rows` and `:columns` the grid of player cells
  * `:players` the player shown in each cell, in order; extra cells stay
    empty
  * `:scale`, `:blocks`, `:title-scroll` passed on to the cells
  * `:brightness` 20-100, below 100 the panel is dimmed
  * `:always-on-top?` whether full screen panels float above other
    windows
  * `:window-bounds` `[x y width height]` in `:window` mode
  * `:on-bounds` optional, called with the new `[x y width height]`
    after a window was moved or resized

  Returns a map with the `:frame`, the vector of `:cells` and a
  `:screen` label, or `nil` if the screen could not be found."
  [{:keys [screen-id rows columns players scale blocks title-scroll brightness always-on-top? window-bounds
           on-bounds]
    :as   panel}
   shutdown-chan on-escape]
  (let [rows                   (or rows 1)
        columns                (or columns 1)
        windowed?              (= screen-id :window)
        ^GraphicsDevice device (when-not windowed? (find-screen screen-id))]
    (if (and (not windowed?) (nil? device))
      (do
        (timbre/warn "Bar display: no screen found matching" (pr-str screen-id) "- skipping panel.")
        nil)
      (let [label         (if windowed? "Window" (.getIDstring device))
            options       (merge {:scale scale :blocks blocks :title-scroll title-scroll}
                                 (select-keys panel [:phrase-unit :dim-off-air? :flash-downbeat?]))
            cells         (vec (repeatedly (* rows columns) #(cell/create shutdown-chan options)))
            content       (doto (JPanel. (GridLayout. rows columns 2 2))
                            (.setOpaque true)
                            (.setBackground (theme/color :background)))
            ^JFrame frame (seesaw/frame :title (str "Bar Display " label)
                                        :undecorated? (not windowed?)
                                        :on-close :dispose)
            alpha         (dim-alpha brightness)]
        (doseq [c cells]
          (.add content ^JComponent (:panel c)))
        (doto frame
          (.setContentPane content)
          (.setBackground (theme/color :background)))
        (when (pos? alpha)
          (let [glass (dimmer alpha)]
            (.setGlassPane frame glass)
            (.setVisible glass true)))
        (if windowed?
          (let [[x y w h] (or window-bounds [80 40 1500 1100])]
            (.setBounds frame (int x) (int y) (int w) (int h)))
          (let [^Rectangle bounds (.. device getDefaultConfiguration getBounds)]
            (doto frame
              (.setCursor ^Cursor @blank-cursor)
              (.setBounds bounds)
              (.setAlwaysOnTop (boolean always-on-top?)))))
        (bind-escape! frame on-escape)
        (seesaw/show! frame)
        (when (and windowed? on-bounds)
          (report-bounds! frame on-bounds))
        (doseq [[c n] (map vector cells (concat players (repeat 0)))]
          ((:set-player! c) n))
        {:frame frame :cells cells :screen label}))))
