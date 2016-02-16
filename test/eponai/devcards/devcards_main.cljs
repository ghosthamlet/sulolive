(ns eponai.devcards.devcards_main
  (:require
    [cljsjs.react.dom]
    [devcards.core :as dc]
    [sablono.core :as html :refer-macros [html]]
    [eponai.devcards.ui.header_dc]
    [eponai.devcards.ui.datepicker_dc]
    [eponai.devcards.ui.tag_dc]
    [eponai.devcards.ui.add_transaction_dc]
    [eponai.devcards.ui.transaction_dc]
    [eponai.devcards.ui.all_transactions_dc]
    [eponai.devcards.ui.stripe-dc])
  (:require-macros
    [devcards.core :refer [defcard]]))

(defcard my-first-card
  (html [:h1 "Devcards is freaking awesome!"]))

