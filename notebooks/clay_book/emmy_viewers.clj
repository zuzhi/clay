;; # Emmy-viewers

;; This namespace discusses Clay's support for
;; [Emmy-viewers](https://github.com/mentat-collective/emmy-viewers).

(ns clay-book.emmy-viewers
  (:require
   [scicloj.kindly.v4.kind :as kind]
   [emmy.env :as e :refer :all #_[D square cube tanh cos sin up down]]
   [emmy.viewer :as ev]
   [emmy.mafs :as mafs]
   [emmy.mathbox.plot :as plot]
   [emmy.leva :as leva]))

;; ## Usage

;; Here, we will not explain the full usage of Emmy-viewers.
;; It is recommended to look into the project' [docs](https://github.com/mentat-collective/emmy-viewers).

;; In Clay, forms generated by emmy-viewers are recognized
;; and displayed accordingly.

(mafs/of-x e/sin {:color :blue})


;; ## A few detials behind the scenes

;; In the example above, we used emmy-viewers
;; to generate a Clojurescript expression
;; that can be interpreted as a Reagent component.
;; Here is the actual expression:

(kind/pprint
 (mafs/of-x e/sin))

;; By default, it is inferred to be of `:kind/emmy-viewers`,
;; and is handle accordingly.

;; Equivalently, we could also handle it more explicitly with `:kind/reagent`:

(kind/reagent
 [`(fn []
     ~(ev/expand (mafs/of-x e/sin)))]
 {:html/deps [:emmy-viewers]})

;; ## More examples

(ev/with-let [!phase [0 0]]
  (let [shifted (ev/with-params {:atom !phase :params [0]}
                  (fn [shift]
                    (fn [x]
                      (((cube D) tanh) (e/- x shift)))))]
    (mafs/mafs
     {:height 400}
     (mafs/cartesian)
     (mafs/of-x shifted)
     (mafs/movable-point
      {:atom !phase :constrain "horizontal"})
     (mafs/inequality
      {:y {:<= shifted :> cos} :color :blue}))))

;;
;; Try moving the pink mark. 👆

(defn my-fn [x]
  (+ -1
     (square (sin x))
     (square (cos (* 2 x)))))

(plot/of-x {:z my-fn :samples 256})
