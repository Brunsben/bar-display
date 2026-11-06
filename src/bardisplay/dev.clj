(ns bardisplay.dev
  "Helpers for working on the displays without any CDJs: an offscreen
  renderer for checking layout, and a demo that runs the simulation in
  an ordinary window."
  (:require [bardisplay.cell :as cell]
            [bardisplay.core :as core]
            [bardisplay.sim :as sim]
            [beat-link-trigger.util :as util]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [seesaw.core :as seesaw])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [javax.swing JComponent JFrame JPanel]))

(def sample-snapshot
  "A plausible player state, for checking that every element lands where
  it should and nothing collides."
  {:player   2
   :playing  true
   :on-air   true
   :master   true
   :synced   true
   :bpm-only false
   :beat     1
   :pitch    0.8
   :tempo    128.04
   :played   154320
   :remain   42150
   :precise  true
   :title    "Strobe (Original Mix) - a deliberately long title to test truncation"
   :artist   "deadmau5"})

(defn render
  "Paints one cell at the given size and writes it to `file`. Supply a
  `:snapshot` to control what is shown; it defaults to
  `sample-snapshot`. Set `:outline?` to trace each child's bounds,
  which makes layout problems obvious."
  [{:keys [width height scale snapshot file outline?]
    :or   {width 1920 height 440 scale 4 snapshot sample-snapshot}}]
  (util/load-fonts)
  ;; A closed shutdown channel stops the animation loop before its first tick,
  ;; so it cannot overwrite the snapshot we are rendering.
  (let [stopped (doto (async/promise-chan) (async/close!))
        built   (cell/create stopped scale)
        ^JPanel panel (:panel built)
        image   (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        g       (.createGraphics image)]
    (reset! (:snapshot built) snapshot)
    (doto panel
      (.setSize width height)
      (.doLayout))
    (.setPaint g Color/BLACK)
    (.fillRect g 0 0 width height)
    (.paint panel g)
    (when outline?
      (.setPaint g Color/MAGENTA)
      (doseq [^java.awt.Component child (.getComponents panel)]
        (let [b (.getBounds child)]
          (.drawRect g (.-x b) (.-y b) (dec (.-width b)) (dec (.-height b))))))
    (.dispose g)
    (io/make-parents file)
    (ImageIO/write image "png" (io/file file))
    {:file   file
     :bounds (mapv (fn [^java.awt.Component child]
                     [(.getSimpleName (class child)) (str (.getBounds child))])
                   (.getComponents panel))}))

(defn render-samples
  "Writes a full width cell and a half width (two players per panel)
  cell into `directory`."
  [directory]
  [(render {:width 1920 :height 440 :outline? true
            :file (str directory "/cell-full-1920x440.png")})
   (render {:width 960 :height 440 :outline? true
            :file (str directory "/cell-compact-960x440.png")})
   (render {:width 1920 :height 440 :snapshot {:player 0}
            :file (str directory "/cell-idle-1920x440.png")})])

(defn demo!
  "Starts the simulation and opens the displays in ordinary windows, so
  the whole thing can be watched running at a desk. Pass `:panels` to
  mirror the real rig, for example two windows of two players each."
  ([]
   (demo! {}))
  ([{:keys [players panels scale]
     :or   {players [1 2 3 4]
            panels  [{:screen :window :rows 2 :columns 1}
                     {:screen :window :rows 2 :columns 1}]
            scale   4}}]
   (sim/start! {:players players})
   (core/open! {:panels panels :scale scale :always-on-top? false})))

(defn stop-demo!
  "Closes the demo windows and stops the simulation."
  []
  (core/close!)
  (sim/stop!))

(defn capture!
  "Paints the open display panels into PNG files. This repaints their
  component trees offscreen rather than grabbing the screen, so it does
  not matter whether the windows are visible or in front."
  [directory]
  (vec (map-indexed (fn [index panel]
                      (let [^JComponent content (.getContentPane ^JFrame (:frame panel))
                            image               (BufferedImage. (max 1 (.getWidth content))
                                                                (max 1 (.getHeight content))
                                                                BufferedImage/TYPE_INT_RGB)
                            g                   (.createGraphics image)
                            file                (io/file (str directory "/display-" index ".png"))]
                        (seesaw/invoke-now (.paint content g))
                        (.dispose g)
                        (io/make-parents file)
                        (ImageIO/write image "png" file)
                        (str file)))
                    (core/open-panels))))
