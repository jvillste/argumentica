(ns argumentica.server
  (:require [net.tcp         :as tcp]
            [net.ty.channel  :as channel]
            [net.ty.pipeline :as pipeline]
            [argumentica.log :as log]))

(defn create-pipeline
  [handler-adapter]
  (pipeline/channel-initializer
   [(pipeline/line-based-frame-decoder)
    pipeline/string-decoder
    pipeline/string-encoder
    pipeline/line-frame-encoder
    (pipeline/make-handler-adapter handler-adapter)]))

(defn start-server [port handler-adapter]
  (tcp/server {:handler (create-pipeline handler-adapter)}
              "localhost"
              port))
