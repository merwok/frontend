(ns frontend.parser
  (:require [compassus.core :as compassus]
            [frontend.analytics.core :as analytics]
            [frontend.components.app :as app]
            [frontend.routes :as routes]
            [om.next :as om-next]
            [om.next.impl.parser :as parser]
            [om.util :as om-util]))

(defn- recalculate-query
  "Each node of an AST has a :query key which is the query form of
  its :children. This is a kind of denormalization. If we change the :children,
  the :query becomes out of date, and Om will use the old :query rather than the
  new :children. This probably represents an Om bug of some kind.

  As a workaround, any time you change an AST's :children, run it through
  recalculate-query before returning it."
  [ast]
  (-> ast
      om-next/ast->query
      parser/expr->ast))


;; Many of our keys have the same local reading behavior, and many of our keys
;; have the same remote reading behavior, but they're not always the same keys.
;; For our app, then, it makes sense to divide the read function into two
;; multimethods: read-local and read-remote.
;;
;; The values they return will become the values of the :value and :remote keys,
;; respectively, in the read function's result.

(defmulti read-local om-next/dispatch)
(defmulti read-remote om-next/dispatch)

;; Most keys resolve locally as an ordinary db->tree or as a simple key lookup.
(defmethod read-local :default
  [{:keys [state query ast] :as env} key params]
  (let [st @state
        value-at-key (get st key)]
    (case (:type ast)
      ;; For a :prop (eg. [:some/value]), just look up and return the value.
      :prop value-at-key
      ;; For a :join (eg. [{:some/complex-value [:with/a :deeper/query]}}]),
      ;; resolve the deeper query with db->tree.
      :join (om-next/db->tree query value-at-key st))))

;; When adding a new key, be sure to add a read-remote implementation. Returning
;; true will pass the entire query on to the remote send function. Returning
;; false will send nothing to the remote. Returning a modified AST will send
;; that modified query to the remote.
(defmethod read-remote :default [env key params]
  (throw (js/Error. (str "No remote behavior defined in the parser for " (pr-str key) "."))))


(def
  ^{:private true
    :doc
    "Keys under :app/current-user which are fed by the page's renderContext,
     and shouldn't be fetched from the remote by API"}
  render-context-keys
  #{:user/login
    :user/bitbucket-authorized?})


;; Some of :app/current-user's data is never fetched by the remote, and only
;; exists in the initial app state, added from the page's renderContext. We
;; exclude those keys here so we don't try to read them remotely.
(defmethod read-remote :app/current-user
  [{:keys [ast] :as env} key params]
  (let [new-ast (update ast :children
                        (fn [children]
                          (into []
                                (remove #(contains? render-context-keys (:key %)))
                                children)))]
    ;; Only include this key in the remote query if there are any children left.
    (if (seq (:children new-ast))
      (recalculate-query new-ast)
      nil)))

;; :legacy/state reads the entire map under :legacy/state in the app state. It
;; does no db->tree massaging, because the legacy state lives in the om.core
;; world and doesn't expect anything like that.
(defmethod read-local :legacy/state
  [{:keys [state] :as env} key params]
  ;; Don't include :inputs; it's not meant to be passed into the top of the
  ;; legacy app, but instead is accessed directly by
  ;; frontend.components.inputs/get-inputs-from-app-state.
  (dissoc (get @state key) :inputs))

;; The :legacy/state is never read remotely.
(defmethod read-remote :legacy/state
  [env key params]
  nil)


;; The subpage is a purely local concern.
(defmethod read-remote :app/subpage-route
  [env key params]
  nil)

;; The keys in :app/route-data have idents for values. If we query through
;; them, we replace the key with the current ident value before passing it
;; on to the remote. That is, if the UI queries for
;;
;; [{:app/route-data [{:route-data/widget [:widget/name]}]}]
;;
;; and the app state contains
;;
;; {;; ...
;;  :app/route-data {:route-data/widget [:widget/by-id 5]}
;;  ;; ...
;;  }
;;
;; we rewrite the query for the remote to be
;;
;; [{:app/route-data [^:query-root {[:widget/by-id 5] [:widget/name]}]}]
;;
;; Then the remote will look up the name of the current widget.
;;
;; Note that the :default case already handles the read-local
;; for :app/route-data perfectly.
(defmethod read-remote :app/route-data
  [{:keys [state ast] :as env} key params]
  (let [st @state
        new-ast (update ast :children
                        (partial
                         into [] (keep
                                  #(let [ident (get-in st [key (:key %)])]
                                     (when ident
                                       (assert (om-util/ident? ident)
                                               (str "The values stored in " key " must be idents."))
                                       ;; Replace the :key and :dispatch-key with the
                                       ;; ident we've found, and make them :query-roots.
                                       (assoc %
                                              :key ident
                                              :dispatch-key (first ident)
                                              :query-root true))))))]

    ;; Only include this key in the remote query if there are any children left.
    (if (seq (:children new-ast))
      (recalculate-query new-ast)
      nil)))


(defn read [{:keys [target] :as env} key params]
  ;; Dispatch to either read-local or read-remote. Remember that, despite the
  ;; fact that a read function can return both a :value and a :remote entry in a
  ;; single map, the parser is actually only looking for one or the other during
  ;; a given call, and the presence or absence of target tells you which it is.
  (case target
    nil {:value (read-local env key params)}
    :remote {:remote (read-remote env key params)}))


(defmulti mutate om-next/dispatch)

;; frontend.routes/set-data sets the :app/route-data during navigation.
(defmethod mutate `routes/set-data
  [{:keys [state route] :as env} key {:keys [subpage route-data]}]
  {:action (fn []
             (let [route-data (cond-> {}
                                (contains? route-data :organization)
                                (assoc :route-data/organization
                                       [:organization/by-vcs-type-and-name
                                        (select-keys (:organization route-data)
                                                     [:organization/vcs-type :organization/name])]))]
               (swap! state #(-> %
                                 (assoc :app/subpage-route subpage
                                        :app/route-data route-data)

                                 ;; Clean up the legacy state so it doesn't leak
                                 ;; from the previous page. This goes away when
                                 ;; the legacy state dies. In the Om Next world,
                                 ;; all route data is in :app/route-data, and is
                                 ;; replaced completely on each route change.
                                 (update :legacy/state dissoc
                                         :navigation-point
                                         :navigation-data
                                         :current-build-data
                                         :current-org-data
                                         :current-project-data)))
               (analytics/track {:event-type :pageview
                                 :navigation-point route
                                 :subpage :default
                                 :properties {:user (get-in @state [:app/current-user :user/login])
                                              :view route
                                              :org (get-in route-data [:route-data/organization :organization/name])}})))})

(def parser (compassus/parser {:read read
                               :mutate mutate
                               :route-dispatch false}))
