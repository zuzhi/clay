(ns bar-racing
  (:require [scicloj.kindly.v4.kind :as kind]
            [tablecloth.api :as tc]))

(def simple-dataset
  (tc/dataset {:year (range 2010 2020)
               :a [30 40 50 70 100 130 170 220 260 290]
               :b [20 25 35 40 60 80 100 110 115 120]
               :c [10 21 44 73 93 110 131 165 190 225]
               :d [14 16 23 26 32 39 45 52 59 66]
               :e [10 15 20 28 34 37 41 46 50 52]}))

simple-dataset

;; ## Simple bar racing chart
(kind/echarts
 {:title {:text "bar racing chart"}
  :xAxis {:max 'dataMax'} ;; minimum value on this axis is set to be the maximum label
  :yAxis {:type :category
          :data (repeat 10 (-> simple-dataset
                               (tc/column-names (complement #{:year}))
                               vec))
          :inverse true ;; display longer bars at top
          :animationDuration 300 ;; bar reordering animation for the first time
          :animationDurationUpdate 300} ;; bar reordering animation for later times
  :series [{:type :bar
            :data (-> simple-dataset
                      (tc/select-columns (complement #{:year}))
                      tc/rows
                      vec)
            :realtimeSort true ;; enable bar race
            :label {:show true
                    :position "right"
                    :valueAnimation true}}] ;; realtime label changing

  ;; For adding data, we apply an enter animation, using `animationDuration`,
  ;; `animationEasing`, and `animationDelay` to configure the duration, easing
  ;; and delay of the animation respectively.
  :animationDuration 0
  :animationEasing :linear
  :animationDelay 0

  ;; For updating data, we will apply an update animation with `animationDurationUpdate`,
  ;; `animationEasingUpdate`, and `animationDelayUpdate` configuring the duration,
  ;; easing and delay of the animation respectively.
  :animationDurationUpdate 2000 ;; this should be the same as frequency of calling `setOption`
  :animationDelayUpdate 0
  :animationEasingUpdate :linear

  :graphic {:elements [{:type :text
                        :right 160
                        :bottom 60
                        :style {:text (simple-dataset :year)
                                :font "bolder 80px monospace"
                                :fill "rgba(100, 100, 100, 0.25)"}
                        :z 100}]}}
 {:style {:height "500px"}
  :type :bar-racing-chart} ;; add `:type` to indicate this is a bar racing chart
)

;; ### Using a github dataset

(defonce repositories-raw
  (-> (tc/dataset
       "https://raw.githubusercontent.com/github/innovationgraph/refs/heads/main/data/repositories.csv"
       {:key-fn keyword})))

(defn get-year-quarter-data []
  (let [year-quarter-pairs (-> repositories-raw
                               (tc/select-columns [:year :quarter])
                               (tc/order-by [:year :quarter] [:asc :asc])
                               (tc/unique-by)
                               (tc/rows))]
    (map (fn [[year quarter]]
           (let [repositories-top10-countries (-> repositories-raw
                                                  (tc/select-rows #(and (= (:year %) year)
                                                                        (= (:quarter %) quarter)))
                                                  (tc/order-by :repositories :desc)
                                                  (tc/head 10))
                 columns (-> repositories-top10-countries
                             (tc/select-columns :iso2_code)
                             tc/rows
                             flatten
                             vec)
                 data (-> repositories-top10-countries
                          (tc/select-columns :repositories)
                          tc/rows
                          flatten
                          vec)]
             {:year year
              :quarter quarter
              :columns columns
              :data data}))
         year-quarter-pairs)))


(def data-for-bar
  (mapv #(:data %) (get-year-quarter-data)))

(def columns-for-bar
  (mapv #(:columns %) (get-year-quarter-data)))

(def texts-for-bar
  (mapv #(str (:year %) "-" (:quarter %)) (get-year-quarter-data)))

(kind/echarts
 {:title {:text "bar racing chart - repositories"}
  :xAxis {:max "dataMax"}
  :yAxis {:type :category
          :data columns-for-bar
          :inverse true
          :animationDuration 300
          :animationDurationUpdate 300}
  :series [{:type :bar
            :data data-for-bar
            :realtimeSort true
            :name "repositories"
            :label {:show true
                    :position "right"
                    :valueAnimation true}}]

  :animationDelay 0
  :animationDelayUpdate 0
  :animationDuration 0
  :animationDurationUpdate 2000
  :animationEasing :linear
  :animationEasingUpdate :linear

  :graphic {:elements [{:type :text
                        :right 160
                        :bottom 60
                        :style {:text texts-for-bar
                                :font "bolder 80px monospace"
                                :fill "rgba(100, 100, 100, 0.25)"}
                        :z 100}]}
  :tooltip {}}
 {:type :bar-racing-chart})

;; ## Line chart

;; For a line chart, animationDuration is all you need, if you don't need dynamic columns

(def data-for-line
  (-> repositories-raw
      (tc/select-rows #(contains? (set (first columns-for-bar)) (:iso2_code %)))
      (tc/add-column :year_quarter (fn [row] (str (:year row) "-" (:quarter row))))
      (tc/order-by [:year :quarter] [:asc :asc])))

(def wider-data
  (tc/pivot->wider
   data-for-line
   :iso2_code
   :repositories))

(kind/echarts
 {:title {:text "dynamic line chart"}
  :xAxis {:type :category
          :data texts-for-bar}
  :yAxis {:type :value}
  :series (mapv (fn [col]
                  {:type :line
                   :data (get wider-data col)
                   :name col
                   :endLabel {:show true
                              :formatter col}
                   :emphasis {:focus :series}})
                (first columns-for-bar))

  :animationDuration 10000 ;; 10 seconds of enter animation
  :animationDurationUpdate 0
  :animationEasing :linear
  :animationEasingUpdate :linear

  :tooltip {:order :valueDesc
            :trigger :axis}})
