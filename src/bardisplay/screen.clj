(ns bardisplay.screen
  "Creates the undecorated full screen window that fills one physical
  bar panel, and divides it between one or more player cells."
  (:require [bardisplay.cell :as cell]
            [bardisplay.theme :as theme]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre])
  (:import [java.awt Cursor GraphicsDevice GraphicsEnvironment GridLayout Point Rectangle Toolkit]
           [java.awt.image BufferedImage]
           [javax.swing JComponent JFrame JPanel KeyStroke]))

(defn screen-devices
  "Returns the screens attached to this machine, in the order the
  graphics environment reports them."
  []
  (vec (.. GraphicsEnvironment getLocalGraphicsEnvironment getScreenDevices)))

(defn describe-screens
  "Returns a description of every attached screen, to help work out
  which identifier belongs to which bar panel."
  []
  (vec (map-indexed (fn [index ^GraphicsDevice device]
                      (let [^Rectangle bounds (.. device getDefaultConfiguration getBounds)]
                        {:index  index
                         :id     (.getIDstring device)
                         :x      (.-x bounds)
                         :y      (.-y bounds)
                         :width  (.-width bounds)
                         :height (.-height bounds)}))
                    (screen-devices))))

(defn find-screen
  "Resolves a screen identifier, which may be an integer index or the
  platform's ID string, to a graphics device."
  ^GraphicsDevice [screen]
  (let [devices (screen-devices)]
    (cond
      (integer? screen) (get devices screen)
      (string? screen)  (first (filter #(= screen (.getIDstring ^GraphicsDevice %)) devices))
      :else             nil)))

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

(defn create
  "Opens a window covering one bar panel.

  `panel-config` keys:

  * `:screen` which screen to occupy, as an index or ID string. The
    keyword `:window` instead opens an ordinary window, for working at a
    desk with no bar panels attached
  * `:rows` and `:columns` the grid of player cells on this panel. A real
    bar panel uses a single row; an ordinary monitor standing in for
    several panels uses a single column or a square grid
  * `:scale` waveform detail zoom level (defaults to 4)
  * `:always-on-top?` whether to float above other windows (defaults true)
  * `:window-size` `[width height]` used in `:window` mode
  * `:window-bounds` `[x y width height]` to place a window explicitly,
    rather than centering it

  Returns a map with the `:frame` and the vector of `:cells`, or `nil`
  if the requested screen could not be found."
  [{:keys [screen rows columns scale always-on-top? window-size window-bounds]
    :or   {rows 1 columns 1 scale 4 always-on-top? true window-size [1600 440]}}
   shutdown-chan on-escape]
  (let [windowed?                 (= screen :window)
        ^GraphicsDevice device    (when-not windowed? (find-screen screen))]
    (if (and (not windowed?) (nil? device))
      (do
        (timbre/warn "Bar display: no screen found matching" (pr-str screen) "- skipping panel.")
        nil)
      (let [label         (if windowed? "Window" (.getIDstring device))
            cells         (vec (repeatedly (* rows columns) #(cell/create shutdown-chan scale)))
            content       (doto (JPanel. (GridLayout. rows columns 2 2))
                            (.setOpaque true)
                            (.setBackground (theme/color :background)))
            ^JFrame frame (seesaw/frame :title (str "Bar Display " label)
                                        :undecorated? (not windowed?)
                                        :on-close :dispose)]
        (doseq [c cells]
          (.add content ^JComponent (:panel c)))
        (doto frame
          (.setContentPane content)
          (.setBackground (theme/color :background)))
        (if windowed?
          (if window-bounds
            (let [[x y w h] window-bounds]
              (.setBounds frame (int x) (int y) (int w) (int h)))
            (doto frame
              (.setSize (int (first window-size)) (int (second window-size)))
              (.setLocationRelativeTo nil)))
          (let [^Rectangle bounds (.. device getDefaultConfiguration getBounds)]
            (doto frame
              (.setCursor ^Cursor @blank-cursor)
              (.setBounds bounds)
              (.setAlwaysOnTop (boolean always-on-top?)))))
        (bind-escape! frame on-escape)
        (seesaw/show! frame)
        {:frame frame :cells cells :screen label}))))
