#!/usr/bin/env bb
(ns mal.core
  (:gen-class)
  (:require
   [clojure.java.io :as io]
   [com.grzm.awyeah.client.api :as aws]
   [mal.sso-credentials-provider :refer [provider]]))


(defn- client
  [profile]
  (aws/client {:api                  :s3
               :region               "us-east-1"
               :credentials-provider (provider profile)}))


(defn put
  [{:keys [profile bucket key path]}]
  (aws/invoke (client profile) {:op :PutObject
                                :request
                                {:Bucket bucket
                                 :Key    key
                                 :Body   (io/input-stream path)}}))


(defn -main
  [& _]
  (put {:profile "some-profile"
        :bucket "some-bucket"
        :key    "some-key"
        :path   "some-path"}))


(when (= *file* (System/getProperty "babashka.file"))
  (-main))
