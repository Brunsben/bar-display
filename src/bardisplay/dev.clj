(ns bardisplay.dev
  "Helpers for working on the displays without any CDJs: an offscreen
  renderer for checking layout, and a demo that runs the simulation in
  ordinary windows."
  (:require [bardisplay.cell :as cell]
            [bardisplay.config :as config]
            [bardisplay.core :as core]
            [bardisplay.screen :as screen]
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
  {:player     2
   :online?    true
   :playing    true
   :on-air     true
   :master     true
   :synced     true
   :bpm-only   false
   :beat       1
   :pitch      0.8
   :tempo      128.04
   :played     154320
   :remain     42150
   :precise    true
   :title      "Strobe (Original Mix) - a deliberately long title to test truncation"
   :artist     "deadmau5"
   :key        "Am"
   :phrase     {:label "Chorus" :color (Color. 255 120 180) :text-color Color/BLACK :beats-left 12
                :next {:label "Down"}}
   :looping    true
   :loop-beats 4
   :next-cue   {:ms-left 12000 :color (Color. 40 220 40)}})

(defn render
  "Paints one cell at the given size and writes it to `file`. Supply a
  `:snapshot` to control what is shown; it defaults to
  `sample-snapshot`. `:blocks`, `:title-scroll` and `:scale` are passed
  to the cell, `:brightness` dims the result like a panel would. Set
  `:outline?` to trace each visible child's bounds, which makes layout
  problems obvious."
  [{:keys [width height scale snapshot file outline? blocks title-scroll brightness overlay phrase-unit]
    :or   {width 1920 height 440 scale 4 snapshot sample-snapshot}}]
  (util/load-fonts)
  ;; A closed shutdown channel stops the animation loop before its first tick,
  ;; so it cannot overwrite the snapshot we are rendering.
  (let [stopped       (doto (async/promise-chan) (async/close!))
        built         (cell/create stopped {:scale scale :blocks blocks :title-scroll title-scroll
                                            :phrase-unit phrase-unit})
        ^JPanel panel (:panel built)
        image         (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
        g             (.createGraphics image)
        alpha         (screen/dim-alpha brightness)]
    (reset! (:snapshot built) snapshot)
    (when overlay
      (reset! (:overlay built) overlay))
    (doto panel
      (.setSize width height)
      (.doLayout))
    (.setPaint g Color/BLACK)
    (.fillRect g 0 0 width height)
    (.paint panel g)
    (when outline?
      (.setPaint g Color/MAGENTA)
      (doseq [^java.awt.Component child (.getComponents panel)
              :when (.isVisible child)]
        (let [b (.getBounds child)]
          (.drawRect g (.-x b) (.-y b) (dec (.-width b)) (dec (.-height b))))))
    (when (pos? alpha)
      (.setPaint g (Color. (float 0) (float 0) (float 0) (float alpha)))
      (.fillRect g 0 0 width height))
    (.dispose g)
    (io/make-parents file)
    (ImageIO/write image "png" (io/file file))
    {:file   file
     :bounds (vec (for [^java.awt.Component child (.getComponents panel)
                        :when (.isVisible child)]
                    [(.getSimpleName (class child)) (str (.getBounds child))]))}))

(defn render-samples
  "Writes a set of cell renderings covering the display options into
  `directory`."
  [directory]
  (let [f #(str directory "/" %)]
    [(render {:width 1920 :height 440 :outline? true :file (f "cell-full-1920x440.png")})
     (render {:width 960 :height 440 :outline? true :file (f "cell-compact-960x440.png")})
     (render {:width 1920 :height 440 :blocks #{:track :time :detail} :file (f "cell-few-blocks.png")})
     (render {:width 1920 :height 440 :blocks #{:track :status :tempo :time :phrase :key :loop :cue}
              :file (f "cell-no-waveforms.png")})
     (render {:width 1920 :height 440 :snapshot {:player 3 :online? false} :file (f "cell-offline.png")})
     (render {:width 1920 :height 440 :brightness 40 :file (f "cell-dimmed.png")})
     (render {:width 1920 :height 440 :snapshot {:player 0} :file (f "cell-idle.png")})
     (render {:width 1920 :height 440 :overlay {:dim? true} :file (f "cell-off-air.png")})
     (render {:width 1920 :height 440 :overlay {:flash? true} :phrase-unit :beats
              :file (f "cell-flash-beats.png")})]))

(defn demo!
  "Starts the simulation and opens the displays in ordinary windows, so
  the whole thing can be watched running at a desk: two panels of two
  players each by default."
  ([]
   (demo! {}))
  ([{:keys [panels display]
     :or   {panels [{:screen :window :layout :column :players [1 2] :window-bounds [40 40 1100 600]}
                    {:screen :window :layout :column :players [3 4] :window-bounds [700 300 1100 600]}]}}]
   (let [settings (config/normalize {:panels panels :display display})]
     (sim/start! {:players (vec (sort (distinct (mapcat :players (:panels settings)))))})
     (core/open! (config/panels settings)))))

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
