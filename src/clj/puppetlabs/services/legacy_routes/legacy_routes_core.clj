(ns puppetlabs.services.legacy-routes.legacy-routes-core
  (:require [puppetlabs.services.ca.certificate-authority-core :as ca-core]
            [compojure.core :as compojure]
            [ring.util.codec :as ring-codec]
            [puppetlabs.services.master.master-core :as master-core]
            [compojure.route :as route]
            [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Handler wrapper

(defn request-compatibility-wrapper
  "Given a Puppet 3.X CA request, convert it to a Puppet 4.X CA request."
  [handler mount-point api-version request]
  (let [{{environment :environment} :params
         path-info                  :path-info
         query-string               :query-string} request
        query-params (if query-string (ring-codec/form-decode query-string) {})]
    (let [compat-request
          (-> request
              (assoc :path-info    (str "/" api-version path-info)
                     :context      mount-point
                     :uri          (str mount-point "/" api-version path-info)
                     :query-string (ring-codec/form-encode
                                     (merge
                                       query-params
                                       (when environment {:environment environment}))))
              (dissoc :params :route-params))]
      (handler compat-request))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Routing

(defn v2_0-routes
  "Creates the compojure routes to handle the 3.X master's '/v2.0' routes."
  [request-handler]
  (compojure/routes
    (compojure/GET "/environments" request
                   (request-handler request))))

(defn legacy-master-routes
  [master-request-handler ca-request-handler]
  (compojure/routes
    (compojure/GET "/node/*" request (master-request-handler request))
    (compojure/GET "/facts/*" request (master-request-handler request))
    (compojure/GET "/file_content/*" request (master-request-handler request))
    (compojure/GET "/file_metadatas/*" request (master-request-handler request))
    (compojure/GET "/file_metadata/*" request (master-request-handler request))
    (compojure/GET "/file_bucket_file/*" request (master-request-handler request))

    ;; TODO: file_bucket_file request PUTs from Puppet agents currently use a
    ;; Content-Type of 'text/plain', which, per HTTP specification, would imply
    ;; a default character encoding of ISO-8859-1 or US-ASCII be used to decode
    ;; the data.  This would be incorrect to do in this case, however, because
    ;; the actual payload is "binary".  Coercing this to
    ;; "application/octet-stream" for now as this is synonymous with "binary".
    ;; This should be removed when/if Puppet agents start using an appropriate
    ;; Content-Type to describe the input payload - see PUP-3812 for the core
    ;; Puppet work and SERVER-294 for the related Puppet Server work that
    ;; would be done.
    (compojure/PUT "/file_bucket_file/*" request
                   (master-request-handler (assoc request
                                            :content-type
                                            "application/octet-stream")))

    (compojure/HEAD "/file_bucket_file/*" request (master-request-handler request))
    (compojure/GET "/catalog/*" request (master-request-handler request))
    (compojure/POST "/catalog/*" request (master-request-handler request))
    (compojure/PUT "/report/*" request (master-request-handler request))
    (compojure/GET "/resource_type/*" request (master-request-handler request))
    (compojure/GET "/resource_types/*" request (master-request-handler request))
    (compojure/GET "/status/*" request (master-request-handler request))

    ;; TODO: when we get rid of the legacy dashboard after 3.4, we should remove
    ;; this endpoint as well.  It makes more sense for this type of query to be
    ;; directed to PuppetDB.
    (compojure/GET "/facts_search/*" request (master-request-handler request))

    ;; legacy CA routes
    (compojure/ANY "/certificate_status/*" request (ca-request-handler request))
    (compojure/ANY "/certificate_statuses/*" request (ca-request-handler request))
    (compojure/GET "/certificate/*" request (ca-request-handler request))
    (compojure/GET "/certificate_revocation_list/*" request (ca-request-handler request))
    (compojure/GET "/certificate_request/*" request (ca-request-handler request))
    (compojure/GET "/certificate_requests/*" request (ca-request-handler request))))

(defn legacy-routes
  "Creates all of the compojure routes for the master."
  [master-request-handler ca-request-handler]
  (compojure/routes
    (compojure/context "/v2.0" request
                       (v2_0-routes master-request-handler))
    (compojure/context "/:environment" [environment]
                       (legacy-master-routes master-request-handler ca-request-handler))
    (route/not-found "Not Found")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn build-ring-handler
  [master-handler master-mount master-api-verion
   ca-handler ca-mount ca-api-version]
  (log/info "Building ring handler for legacy service")
  (legacy-routes
    #(request-compatibility-wrapper master-handler master-mount master-api-verion %)
    #(request-compatibility-wrapper ca-handler ca-mount ca-api-version %)))
