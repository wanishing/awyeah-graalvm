(ns mal.sso-credentials-provider
  (:require
    [babashka.curl :as curl]
    [babashka.fs :as fs]
    [cheshire.core :as json]
    [com.grzm.awyeah.config :as config]
    [com.grzm.awyeah.credentials :as credentials])
  (:import
    (java.time
      Instant)
    (java.util
      Date)))


(def aws-credentials-sso-path (str (System/getenv "HOME") "/.aws/credentials.sso"))

(def aws-sso-cache-path (str (System/getenv "HOME") "/.aws/sso/cache"))


(def portal-fmt
  "https://portal.sso.%s.amazonaws.com:443/federation/credentials?account_id=%s&role_name=%s")

(defn fail [error]
  (println error)
  (System/exit 1))

(defn- read-config
  [path profile]
  (get (config/parse path) profile))


(defn- read-auth
  [path]
  (->> (fs/glob path "*.json")
       (map (comp slurp fs/file))
       (map #(json/parse-string % true))
       (some #(when (:accessToken %) %))))


(defn- expired?
  [expiresAt]
  (let [expires-inst (Instant/parse expiresAt)
        now (Instant/now)]
    (.isAfter now expires-inst)))


(defn- read-access-token
  [path]
  (let [{:keys [accessToken expiresAt]} (read-auth path)]
    (if (and accessToken (not (expired? expiresAt)))
      accessToken
      (fail (str "AWS auth missing or expired, please log via SSO: aws-sso-login")))))


(defn- fetch-credentials
  [token {:strs [sso_region sso_account_id sso_role_name]}]
  (let [endpoint (format portal-fmt
                         sso_region
                         sso_account_id
                         sso_role_name)
        headers {"x-amz-sso_bearer_token" token}
        {:keys [status body]} (curl/get endpoint {:headers headers})]
    (if (= status 200)
      (:roleCredentials (json/parse-string body true))
      (fail (format "Unable to fetch credentials with status %s: %s" status body)))))


(defn- fetch-from-profile
  [profile config-path cache-path]
  (let [sso-details (read-config config-path profile)
        access-token (read-access-token cache-path)
        {:keys [accessKeyId
                secretAccessKey
                sessionToken
                expiration]} (fetch-credentials access-token sso-details)
        expiration-inst (Date/from (Instant/ofEpochMilli expiration))]
    {:aws/access-key-id             accessKeyId
     :aws/secret-access-key         secretAccessKey
     :aws/session-token             sessionToken
     :cognitect.aws.credentials/ttl (credentials/calculate-ttl {:Expiration expiration-inst})}))


(defn provider
  "Creates a credential provider which periodically refreshes credentials
  by using the SSO profile"
  [profile]
  (credentials/cached-credentials
    (reify credentials/CredentialsProvider
      (fetch
        [_]
        (try
          (fetch-from-profile profile aws-credentials-sso-path aws-sso-cache-path)
          (catch Exception e
            (fail (format "failed to refresh profile %s: %s" profile (.getMessage e)))))))))


(comment
  (def profile "some-profile")
  (def path aws-sso-cache-path)
  (def sso-details (read-config aws-credentials-sso-path profile))
  (def access-token (read-access-token path))


  (def creds
    (let [{:keys                                        [accessKeyId
                                            secretAccessKey
                                            sessionToken
                                            expiration]} (fetch-credentials access-token sso-details)
          expiration-inst                                          (Date/from (Instant/ofEpochMilli expiration))]
      {:aws/access-key-id             accessKeyId
       :aws/secret-access-key         secretAccessKey
       :aws/session-token             sessionToken
       :cognitect.aws.credentials/ttl (credentials/calculate-ttl {:Expiration expiration-inst})}))


  creds)
