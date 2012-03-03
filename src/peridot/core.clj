(ns peridot.core
  (:require [ring.mock.request :as mock]
            [peridot.cookie-jar :as cj]
            [clojure.data.codec.base64 :as base64]
            [clojure.string :as string]))

(defn ^:private get-host [request]
  (string/lower-case (get (:headers request) "host")))

(defn ^:private set-post-content-type [request]
  (if (and (not (:content-type request))
           (= :post (:request-method request)))
    (assoc request :content-type "application/x-www-form-urlencoded")
    request))

(defn ^:private set-https-port [request]
  (if (= :https (:scheme request))
    (assoc request :server-port 443)
    request))

(defn ^:private to-header-key [k]
  (string/lower-case (str k)))

(defn ^:private add-headers [request headers]
  (reduce (fn [req [k v]]
            (if v
              (assoc-in req [:headers (to-header-key k)] v)
              req))
          request
          headers))

(defn ^:private add-env [request env]
  (reduce (fn [req [k v]] (assoc req k v))
          request
          env))

(defn ^:private build-request [uri env headers cookie-jar]
  (let [env (apply hash-map env)
        params (:params env)
        request (mock/request :get uri params)]
    (-> request
        (add-headers (-> headers
                         (merge (cj/cookies-for cookie-jar
                                                (:scheme request)
                                                (:uri request)
                                                (get-host request)))
                         (merge (:headers env))))
        (add-env (dissoc (dissoc env :params) :headers))
        set-post-content-type
        set-https-port)))

(defn ^:private build-url [{:keys [scheme server-name port uri query-string]}]
  (str (name scheme)
       "://"
       server-name
       (when (and port
                  (not= port (scheme {:https 443 :http 80})))
         (str ":" port))
       uri
       query-string))

(defn session [app & params]
  (assoc (apply hash-map params)
    :app app))

(defn request [{:keys [app headers cookie-jar]} uri & env]
  (let [request (build-request uri env headers cookie-jar)
        response (app request)]
    (session app
             :response response
             :request request
             :headers headers
             :cookie-jar (cj/merge-cookies (:headers response)
                                           cookie-jar
                                           (:uri request)
                                           (get-host request)))))

(defn header [state key value]
  (assoc-in state [:headers key] value))

(defn authorize [state user pass]
  (header state "authorization" (str "Basic "
                                     (String. (base64/encode
                                               (.getBytes (str user ":" pass)
                                                          "UTF-8"))
                                              "UTF-8")
                                     "\n")))

(defn follow-redirect [state]
  (let [headers (:headers (:response state))
        location (when headers (headers "Location"))]
    (if location
        (request state
           location
           :headers {"referrer" (build-url (:request state))})
        (throw (Exception. "Previous response was not a redirect")))))

(defn dofns [state & fns]
  (doseq [f fns]
    (f state))
  state)