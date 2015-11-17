(ns eponai.client.core
  (:require [eponai.client.app :as app]
            [eponai.client.backend :as backend]))

(enable-console-print!)

(println "Hello console")

(app/run
  (backend/data-provider))

