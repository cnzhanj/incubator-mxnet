(ns bert-qa.bert-sentence-classification
  (:require [clojure.string :as string]
            [clojure.reflect :as r]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [org.apache.clojure-mxnet.dtype :as dtype]
            [org.apache.clojure-mxnet.context :as context]
            [org.apache.clojure-mxnet.layout :as layout]
            [org.apache.clojure-mxnet.ndarray :as ndarray]
            [org.apache.clojure-mxnet.symbol :as sym]
            [org.apache.clojure-mxnet.module :as m]
            [clojure.pprint :as pprint]
            [clojure-csv.core :as csv]
            [bert-qa.infer :as bert-infer]))


(def model-path-prefix "model/bert-base")
;; epoch number of the model
(def epoch 0)
;; the vocabulary used in the model
(def model-vocab "model/vocab.json")
;; the input question
;; the maximum length of the sequence
(def seq-length 384)


(defn fine-tune-model
  "msymbol: the pretrained network symbol
    arg-params: the argument parameters of the pretrained model
    num-classes: the number of classes for the fine-tune datasets"
  [msymbol num-classes]
  (as-> msymbol data
    (sym/flatten "flatten-finetune" {:data data})
    (sym/fully-connected "fc-finetune" {:data data :num-hidden num-classes})
    (sym/softmax-output "softmax" {:data data})))

;;; Data preprocessing

#_(defn fit [devs msymbol arg-params aux-params]
  (let [mod (-> (m/module msymbol {:contexts devs})
                (m/bind {:data-shapes (mx-io/provide-data-desc train-iter) :label-shapes (mx-io/provide-label-desc val-iter)})
                (m/init-params {:arg-params arg-params :aux-params aux-params
                                :allow-missing true}))]
    (m/fit mod
           {:train-data train-iter
            :eval-data val-iter
            :num-epoch 1
            :fit-params (m/fit-params {:intializer (init/xavier {:rand-type "gaussian"
                                                                 :factor-type "in"
                                                                 :magnitude 2})
                                       :batch-end-callback (callback/speedometer batch-size 10)})})))

#_(defn fine-tune! [devs]
  (let [{:keys [msymbol arg-params aux-params] :as model} (get-model)
        new-model (fine-tune-model (merge model {:num-classes 2}))]
    new-model
    #_(fit devs net new-args arg-params)))


(comment

  ;;; load the pre-trained BERT model using the module api
  (def bert-base (m/load-checkpoint {:prefix model-path-prefix :epoch 0}))
  ;;; now that we have loaded the BERT model we need to attach an additional layer for classification which is a dense layer with 2 classes
  (def model (fine-tune-model (m/symbol bert-base) 2))

  ;;; Data Preprocessing for BERT

  ;; For demonstration purpose, we use the dev set of the Microsoft Research Paraphrase Corpus dataset. The file is named ‘dev.tsv’. Let’s take a look at the raw dataset.
  ;; it contains 5 columns seperated by tabs
  (def raw-file (->> (string/split (slurp "dev.tsv") #"\n")
                     (map #(string/split % #"\t") )))
  (def raw-file (csv/parse-csv (slurp "dev.tsv") :delimiter \tab))
  (take 3 raw-file)
 ;; (["﻿Quality" "#1 ID" "#2 ID" "#1 String" "#2 String"]
 ;; ["1"
 ;;  "1355540"
 ;;  "1355592"
 ;;  "He said the foodservice pie business doesn 't fit the company 's long-term growth strategy ."
 ;;  "\" The foodservice pie business does not fit our long-term growth strategy ."]
 ;; ["0"
 ;;  "2029631"
 ;;  "2029565"
 ;;  "Magnarelli said Racicot hated the Iraqi regime and looked forward to using his long years of training in the war ."
 ;;  "His wife said he was \" 100 percent behind George Bush \" and looked forward to using his years of training in the war ."])

  ;;; for our task we are only interested in the 0 3rd and 4th column
  (vals (select-keys (first raw-file) [3 4 0]))
  ;=> ("#1 String" "#2 String" "﻿Quality")
  (def data-train-raw (->> raw-file
                           (map #(vals (select-keys % [3 4 0])))
                           (rest) ;;drop header
                           ))
  (def sample (first data-train-raw))
  (nth sample 0) ;;;sentence a
  ;=> "He said the foodservice pie business doesn 't fit the company 's long-term growth strategy ."
  (nth sample 1) ;; sentence b
  "\" The foodservice pie business does not fit our long-term growth strategy ."

  (nth sample 2) ; 1 means equivalent, 0 means not equivalent
   ;=> "1"

  ;;; Now we need to turn these into ndarrays to make a Data Iterator
  (def vocab (bert-infer/get-vocab))
  (def idx->token (:idx->token vocab))
  (def token->idx (:token->idx vocab))

  (defn pre-processing [ctx idx->token token->idx train-item]
    (let [[sentence-a sentence-b label] train-item
       ;;; pre-processing tokenize sentence
          token-1 (bert-infer/tokenize (string/lower-case sentence-a))
          token-2 (bert-infer/tokenize (string/lower-case sentence-b))
          valid-length (+ (count token-1) (count token-2))
        ;;; generate token types [0000...1111...0000]
          qa-embedded (into (bert-infer/pad [] 0 (count token-1))
                            (bert-infer/pad [] 1 (count token-2)))
          token-types (bert-infer/pad qa-embedded 0 seq-length)
        ;;; make BERT pre-processing standard
          token-2 (conj token-2 "[SEP]")
          token-1 (into [] (concat ["[CLS]"] token-1 ["[SEP]"] token-2))
          tokens (bert-infer/pad token-1 "[PAD]" seq-length)
        ;;; pre-processing - token to index translation
          indexes (bert-infer/tokens->idxs token->idx tokens)]
    {:input-batch [(ndarray/array indexes [1 seq-length] {:context ctx})
                   (ndarray/array token-types [1 seq-length] {:context ctx})
                   (ndarray/array [valid-length] [1] {:context ctx})]
     :label (if (= "0" label)
              (ndarray/array [1 0] [2] {:ctx ctx})
              (ndarray/array [0 1] [2] {:ctx ctx}))
     :tokens tokens
     :train-item train-item}))

  ;;; our sample item
  (pre-processing (context/default-context) idx->token token->idx sample)
 

  )