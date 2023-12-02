(ns scicloj.clay.v2.make
  (:require [scicloj.clay.v2.config :as config]
            [scicloj.clay.v2.files :as files]
            [scicloj.clay.v2.util.path :as path]
            [scicloj.clay.v2.read :as read]
            [scicloj.clay.v2.item :as item]
            [scicloj.clay.v2.prepare :as prepare]
            [scicloj.clay.v2.notebook :as notebook]
            [scicloj.clay.v2.page :as page]
            [scicloj.clay.v2.book :as book]
            [scicloj.clay.v2.server :as server]
            [scicloj.clay.v2.util.time :as time]
            [clojure.string :as string]
            [clojure.java.shell :as shell]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]))

(defn spec->full-source-path [{:keys [base-source-path source-path]}]
  (or (some-> base-source-path
              (str "/" source-path))
      source-path))

(defn spec->ns-form [{:keys [full-source-path]}]
  (when (-> full-source-path
            path/path->ext
            (= "clj"))
    (-> full-source-path
        slurp
        read/read-ns-form)))

(defn spec->full-target-path [{:keys [full-source-path
                                      base-target-path
                                      format
                                      ns-form]}]
  (if ns-form
    ;; a Clojure namespace
    (path/ns->target-path base-target-path
                          (-> ns-form
                              second
                              name)
                          (str (when (-> format
                                         second
                                         (= :revealjs))
                                 "-revealjs")
                               ".html"))
    ;; else, a markdown file
    (str base-target-path
         "/"
         full-source-path)))

(defn spec->ns-config [{:keys [ns-form]}]
  (some-> ns-form
          meta
          :clay))

(defn merge-ns-config [spec]
  (merge spec
         (spec->ns-config spec)))

(defn extract-specs [config spec]
  (let [{:as base-spec :keys [source-path]}
        (merge config spec) ; prioritize spec over global config
        ;;
        single-ns-specs (->> (if (sequential? source-path)
                               (->> source-path
                                    (map (partial assoc base-spec :source-path)))
                               [base-spec])
                             (map (fn [single-ns-spec]
                                    (-> single-ns-spec
                                        (config/add-field :full-source-path spec->full-source-path)
                                        (config/add-field :ns-form spec->ns-form)
                                        merge-ns-config
                                        (merge (dissoc spec :source-path)) ; prioritize spec over the ns config
                                        (config/add-field :full-target-path spec->full-target-path)))))]
    {:main-spec (-> base-spec
                    (assoc :full-target-paths
                           (->> single-ns-specs
                                (mapv :full-target-path))))
     :single-ns-specs single-ns-specs}))


(defn index-path? [path]
  (some-> path
          (string/split #"/")
          last
          (#{"index.qmd" "index.clj"})))

(defn quarto-book-config [{:as spec
                           :keys [book
                                  quarto
                                  base-target-path
                                  full-target-paths]}]
  (let [index-included? (->> full-target-paths
                             (some index-path?))]
    (-> quarto
        (select-keys [:format])
        (merge {:project {:type "book"}
                :book {:title (:title book)
                       :chapters (-> full-target-paths
                                     (->> (map
                                           (fn [path]
                                             (-> path
                                                 (string/replace
                                                  (re-pattern (str "^"
                                                                   base-target-path
                                                                   "/"))
                                                  "")
                                                 (string/replace
                                                  #"\.html$"
                                                  ".qmd")))))
                                     (cond->> index-included?
                                       (cons (str base-target-path "/index.qmd"))))}}))))

(defn write-quarto-book-config! [quarto-book-config
                                 {:keys [base-target-path]}]
  (let [config-path (str base-target-path "/_quarto.yml")]
    (io/make-parents config-path)
    (->> quarto-book-config
         yaml/generate-string
         (spit config-path))
    (prn [:wrote config-path])
    [:wrote config-path]))

(defn quarto-book-index [{{:keys [toc title]} :book}]
  (str "---\n"
       (yaml/generate-string {:format {:html {:toc toc}}})
       "\n---\n"
       "# " title))

(defn write-quarto-book-index-if-needed! [quarto-index
                                          {:keys [base-target-path]}]
  (let [main-index-path (str base-target-path "/index.qmd")]
    (if-not (-> main-index-path io/file .exists)
      (do (spit main-index-path quarto-index)
          (prn [:wrote main-index-path])
          [:wrote main-index-path])
      [:ok])))

(defn make-book! [spec]
  [(-> spec
       quarto-book-config
       (write-quarto-book-config! spec))
   (-> spec
       quarto-book-index
       (write-quarto-book-index-if-needed! spec))])

(defn handle-main-spec! [{:as spec
                          :keys [book]}]
  (if book
    (make-book! spec)
    [:ok]))

(defn handle-single-source-spec! [{:as spec
                                   :keys [ns-form
                                          format
                                          full-target-path
                                          show
                                          run-quarto]}]
  (when ns-form
    (files/init-target! full-target-path)
    (try
      (let [spec-with-items      (-> spec
                                     (config/add-field :items notebook/notebook-items))]
        (case (first format)
          :html (do (-> spec-with-items
                        (config/add-field :page page/html)
                        server/update-page!)
                    [:wrote full-target-path])
          :quarto (let [qmd-path (-> full-target-path
                                     (string/replace #"\.html$" ".qmd"))
                        output-file (-> full-target-path
                                        (string/split #"/")
                                        last)]
                    (-> spec-with-items
                        (update-in [:quarto :format]
                                   select-keys [(second format)])
                        (update-in [:quarto :format (second format)]
                                   assoc :output-file output-file)
                        page/md
                        (->> (spit qmd-path)))
                    (println [:wrote qmd-path (time/now)])
                    (if run-quarto
                      (do (->> (shell/sh "quarto" "render" qmd-path)
                               ((juxt :err :out))
                               (mapv println))
                          (println [:created full-target-path (time/now)])
                          (-> spec
                              (merge {:full-target-path full-target-path})
                              server/update-page!))
                      ;; else, just show the qmd file
                      (-> spec
                          (merge {:full-target-path qmd-path})
                          server/update-page!))
                    (vec
                     (concat [:wrote qmd-path]
                             (when run-quarto
                               [full-target-path]))))))
      (catch Exception e
        (when show
          (-> spec
              (assoc :page (-> spec
                               (assoc :items [(item/pprint e)])
                               page/html))
              server/update-page!))
        (throw e)))))

(defn sync-resources! [{:keys [base-target-path]}]
  (shell/sh "rsync" "-avu"
            "src/"
            (str base-target-path "/src"))
  (shell/sh "rsync" "-avu"
            "notebooks/"
            (str base-target-path "/notebooks")))


(defn make! [spec]
  (let [{:keys [main-spec single-ns-specs]} (extract-specs (config/config)
                                                           (merge spec))
        {:keys [show]} main-spec]
    (sync-resources! spec)
    (when show
      (-> main-spec
          (assoc :page (-> single-ns-specs
                           first
                           (assoc :items [item/loader])
                           page/html))
          server/update-page!))
    [(->> single-ns-specs
          (mapv handle-single-source-spec!))
     (-> main-spec
         handle-main-spec!)]))


(comment
  (make! {:format [:html]
          :source-path "notebooks/index.clj"})

  (make! {:format [:html]
          :source-path "notebooks/index.clj"
          :show false})

  (make! {:format [:html]
          :source-path ["notebooks/slides.clj"
                        "notebooks/index.clj"]
          :show false})

  (make! {:format      [:html]
          :source-path "notebooks/index.clj"
          :single-form '(kind/cytoscape
                         [{:style {:width "300px"
                                   :height "300px"}}
                          cytoscape-example])})

  (make! {:format [:quarto :html]
          :source-path "notebooks/index.clj"})

  (make! {:format [:quarto :html]
          :source-path "notebooks/index.clj"
          :run-quarto false})

  (make! {:format [:quarto :html]
          :source-path "notebooks/slides.clj"})

  (make! {:format [:quarto :revealjs]
          :source-path "notebooks/slides.clj"})

  (make! {:format [:quarto :html]
          :source-path "notebooks/index.clj"
          :quarto {:highlight-style :nord}})

  (make! {:format [:html]
          :base-source-path "notebooks/"
          :source-path "index.clj"})

  (make! {:format [:quarto :html]
          :base-source-path "notebooks"
          :source-path ["index.clj"
                        "chapter.clj"
                        "another_chapter.md"]
          :base-target-path "book"
          :show false
          :run-quarto false
          :book {:title "Book Example"}})

,)