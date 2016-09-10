(ns ^{:doc "Feature utility functions.  In this library, all references to
`panon` stand for *parsed annotation*, which is returned
from [[zensols.nlparse.parse/parse]]."
      :author "Paul Landes"}
    zensols.nlparse.feature
  (:import com.zensols.util.StringUtils)
  (:require [clojure.tools.logging :as log]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as s])
  (:require [clojure.core.matrix.stats :as stat])
  (:require [zensols.nlparse.wordnet :as wn]
            [zensols.nlparse.wordlist :as wl]
            [zensols.nlparse.parse :as pt]))

(def none-label
  "Value used for missing features."
  "<none>")

(def beginning-of-sentence-label
  "Beginning of sentence marker."
  "<bos>")

(def end-of-sentence-label
  "End of sentence marker."
  "<eos>")

;; util
(defn upper-case? [text]
 (= (count text)
    (count (take-while #(Character/isUpperCase %) text))))

(defn lc-null
  "Return the lower case version of a string or nil if nil given."
  [str]
  (if str (s/lower-case str)))

(defn or-none
  "Return **str** if non-`nil` or otherwise the sepcial [[none-label]]."
  [str]
  (or str none-label))

(defn or-0
  "Call and return the value given by **val-fn** iff **check** is non-`nil`,
  otherwise return 0."
  ([val]
   (or val 0))
  ([check val-fn]
   (if check (val-fn check) 0)))

(defn ratio-true
  "Return the ratio of **items** whose evaluation of **true-fn** is `true`."
  [items true-fn]
  (/ (->> items (map true-fn)
          (filter true?) count)
     (count items)))

;; propbank
(defn first-sent-propbank-label
  "Find the first propbank label for a sentence."
  [anon]
  (let [toks (:tokens anon)]
    (first
     (filter #(not (nil? %))
             (map (fn [tok]
                    (let [srl (:srl tok)]
                      (if (:propbank srl)
                        (log/tracef "verbnet label: %s" (:verbnet-class srl)))
                      (:propbank srl)))
                  toks)))))

(defn first-propbank-label
  "Find the first propbank label across all ."
  [anon]
  (->> (:sents anon) (map first-sent-propbank-label) (drop-while nil?) first))

(defn verb-features
  "Find the most probable key head verb in sentence **sent**."
  [sent]
  (let [tree (:dependency-parse-tree sent)
        root-tok (pt/root-dependency sent)
        root-word (lc-null (:text root-tok))
        toks (:tokens sent)
        first-tok (first toks)
        first-word-verb? (= "VB" (:pos-tag first-tok))
        elected-verb-pair
        (cond first-word-verb? [(lc-null (:text (first toks))) 1]
              (and root-word (= "VB" (:pos-tag root-tok)))
              [root-word (first (:token-range root-tok))]
              ;; too dicey
              ;;root-word (morph/to-present-tense-verb root-word)
              true [none-label nil])
        elected-verb (first elected-verb-pair)
        elected-verb-id (if (and false (not (= none-label elected-verb)))
                          (let [iword (wn/lookup-word elected-verb wn/pos-verb)]
                            (if iword
                              (first (.getSynsetOffsets iword))
                              (do
                                (log/warnf "no wordnet offset for <%s>"
                                           elected-verb)
                                -1)))
                          (.hashCode elected-verb))]
    {:elected-verb-id elected-verb-id}))

(defn verb-feature-metas []
  [[:elected-verb-id 'numeric]])

(defn wordnet-features
  "Get features generated from WordNet from **word**.

  * **word** the word to lookup
  * **pos-tag** a wordnet pos tag (see [[zensols.nlparse.wordnet/pos-tags]])"
  ([word]
   (wordnet-features word nil))
  ([word pos-tag]
   (let [iws (if pos-tag
               (wn/lookup-word word pos-tag)
               (wn/lookup-word word))
         iw (first iws)]
     (if iw (.sortSenses iw))
     (let [synset (if iw (->> iw .getSenses first))
           flags (wn/verb-frame-flags synset)]
       {:wn-offset (or-0 synset #(.getOffset %))
        :wn-word-set-count (count iws)
        :wn-is-adjective-cluster (not (nil? (wn/adjective-cluster? synset)))
        :wn-sense-word-count (or-0 synset #(-> % .getWords .size))
        :wn-sense-lex-file-num (or-0 synset #(.getLexFileNum %))
        :wn-verb-frame-flag-len (or-0 flags #(.length %))
        :wn-verb-frame-flag-size (or-0 flags #(.size %))
        :wn-verb-frame-flag-hash (or-0 flags #(.hashCode %))}))))

(defn wordnet-feature-metas []
  (->> [:wn-offset :wn-word-set-count :wn-sense-word-count
        :wn-sense-lex-file-num :wn-verb-frame-flag-len
        :wn-verb-frame-flag-size :wn-verb-frame-flag-hash]
       (map (fn [k] [k 'numeric]))
       (cons [:wn-is-adjective-cluster 'boolean])
       vec))


;; dict
(defn dictionary-features
  "Dictionary features include in/out-of-vocabulary ratio."
  [tokens]
  (let [lemmas (map :lemma tokens)]
    {:in-dict-ratio (ratio-true lemmas wn/in-dictionary?)
     :in-english-word-list-ratio (ratio-true lemmas #(wl/in-word-list? %))}))

(defn dictionary-feature-metas []
  [[:in-dict-ratio 'numeric]])

(defn- token-average-length [tokens]
  (->> (map :text tokens)
       (map count)
       (apply +)
       (#(if (> (count tokens) 0)
           (/ % (count tokens))
           0))))

(defn token-features [panon tokens]
  {:utterance-length (count (:text panon))
   :mention-count (count (:mentions panon))
   :sent-count (count (:sents panon))
   :token-count (count tokens)
   :token-average-length (token-average-length tokens)
   :stopword-count (->> tokens (map #(if (:stopword %) 1 0)) (reduce +))
   :is-question (= "?" (-> tokens last :text))})

(defn token-feature-metas []
  [[:utterance-length 'numeric]
   [:mention-count 'numeric]
   [:sent-count 'numeric]
   [:token-count 'numeric]
   [:token-average-length 'numeric]
   [:stopword-count 'numeric]
   [:is-question 'boolean]])


;; pos
(defn- pos-tag-ratio-keyword [lab]
  (-> (format "pos-tag-ratio-%s" lab) keyword))

(defn- pos-tag-count-keyword [lab]
  (-> (format "pos-tag-count-%s" lab) keyword))

(defn pos-tag-features [tokens]
  (let [pos-tag-types (pt/pos-tag-types)
        tc (count tokens)]
    (->> (map :pos-tag tokens)
         (map pt/pos-tag-type)
         (reduce (fn [ret ttype]
                   (merge ret {ttype (inc (or (get ret ttype) 0))}))
                 {})
         (merge (zipmap pos-tag-types (repeat (count pos-tag-types) 0)))
         (map (fn [[k v]]
                {(pos-tag-ratio-keyword k) (/ v tc)
                 (pos-tag-count-keyword k) v}))
         (apply merge)
         (merge {:pos-last-tag (->> tokens last :pos-tag)
                 :pos-first-tag (->> tokens first :pos-tag)}))))

(defn pos-tag-feature-metas []
  (vec (concat [[:pos-last-tag (into () (pt/pos-tags))]
                [:pos-first-tag (into () (pt/pos-tags))]]
               (map #(vector (pos-tag-ratio-keyword %) 'numeric)
                    (pt/pos-tag-types))
               (map #(vector (pos-tag-count-keyword %) 'numeric)
                    (pt/pos-tag-types)))))


;; tree
(defn- dependency-tree-id
  "Get a hash code for the dependency parse tree of sentence **sent**."
  [panon]
  (->> panon
       :sents
       (map :dependency-parse-tree)
       (map #(.hashCode %))
       (reduce +)))

(defn tree-feature-metas []
  [[:dep-tree-id 'numeric]])

(defn tree-features [panon]
  {:dep-tree-id (dependency-tree-id panon)})



;; SRL
(defn- srl-propbank-ids [toks]
  (->> toks
       (map (fn [tok]
              (-> tok :srl :propbank (#(if % (.hashCode %) 0)))))
       (reduce +)))

(defn- srl-argument-count [toks]
  (->> toks
       (map #(-> % :srl :heads first :dependency-label))
       (remove nil?)
       count))

(defn srl-feature-metas []
  [[:srl-propbank-id 'numeric]
   [:srl-argument-counts 'numeric]])

(defn srl-features [toks]
  {:srl-propbank-id (srl-propbank-ids toks)
   :srl-argument-counts (srl-argument-count toks)})



;; word counts
(def ^:dynamic *word-count-config*
  "Configuration for word-count-* and calculate-word* functions."
  {;; number of word counts for each label
   :words-by-label-count 3
   ;; function maps a top level annotation to get its label
   :anon-to-label-fn nil
   :label-format-fn #(format "label-count-%s" %)
   :pos-tags #{"JJ" "JJR" "JJS"
               "MD"
               "NN" "NNS", "NNPS"
               "VB" "VBD" "VBG" "VBN" "VBP" "VBZ"}})

(defn- word-count-candidate?
  "Return whether a token should be considered a word candidate."
  [token]
  (let [word-count-tags (:pos-tags *word-count-config*)]
    (and (not (:stopword token))
         (contains? word-count-tags (:pos-tag token)))))

(defn- word-count-form
  "Conical string word count form of a token (i.e. Running -> run)."
  [token]
  (s/lower-case (:lemma token)))

(defn- calculate-word-count-dist
  "Get the top counts for each label using the top **words-by-label-count**
  number from each."
  [by-label label-keys]
  (let [lab-count (:words-by-label-count *word-count-config*)]
    (zipmap
     label-keys
     (->> label-keys
          (map (fn [label]
                 (take lab-count
                       (sort (fn [[ak av] [bk bv]]
                               (compare bv av))
                             (get by-label label)))))
          (map (fn [counts]
                 ;; normalize into a ratio to keep a bound on the P(hat) est
                 ;; size in the feature calculation
                 (let [total (reduce + 0 (vals counts))]
                   (apply merge (map (fn [[word count]]
                                       {word (/ count total)})
                                     counts)))))))))

(defn- calculate-words-by-label [anons]
  (let [{:keys [anon-to-label-fn]} *word-count-config*]
    (->> anons
         (map (fn [anon]
                (if-let [label (anon-to-label-fn anon)]
                  (->> (pt/tokens (:parse-anon anon))
                       (filter word-count-candidate?)
                       (map #(hash-map (word-count-form %) 1))
                       (apply merge-with +)
                       (hash-map label)))))
         (remove nil?)
         (apply merge-with (fn [& ms] (apply merge-with + ms))))))

(defn calculate-feature-stats
  "Calculate feature statistics during training.

  * **anons** a sequence of parsed annotations"
  [anons]
  (let [wba (calculate-words-by-label anons)
        label-keys (keys wba)]
    {:words-by-label wba
     :word-count-dist (calculate-word-count-dist wba label-keys)}))

(defn label-word-count-feature-key [label]
  (keyword ((:label-format-fn *word-count-config*) label)))

(defn- label-word-count-scores [tokens word-count-dist]
  (->> tokens
       (map (fn [token]
              (let [word (word-count-form token)]
                (map (fn [[label dist]]
                       (let [prob (get dist word)]
                         {label (or prob 0)}))
                     word-count-dist))))
       (apply concat)
       (apply merge-with +)))

(defn label-count-score-features
  "Generate count score features from trained statistics.

  * **panon** is the parsed annotation to generate features on
  * **feature-stats** is the trained stats from [[calculate-feature-stats]]."
  [panon feature-stats]
  (let [scores (label-word-count-scores
                (pt/tokens panon)
                (:word-count-dist feature-stats))]
    (into {}
          (map (fn [[label score]]
                 {(label-word-count-feature-key label)
                  (double score)})
               scores))))

(defn top-count-scores
  "Return the top **num-counts**.

  * **panon*** is the parsed annotation to generate features on
  * **feature-stats** is the trained stats from [[calculate-feature-stats]]."
  [num-counts panon features-stats]
  (->> features-stats
       :word-count-dist
       (label-word-count-scores (pt/tokens panon))
       (sort (fn [a b]
               (compare (second b) (second a))))
       (take num-counts)
       (filter #(> (second %) 0))
       (take num-counts)
       (map first)))


;;; charaters

;; (longest) repeating characters
(defn- lrs-unique-feature-metas [unique-idx]
  [[(keyword (format "lrs-occurs-%d" unique-idx)) 'numeric]
   [(keyword (format "lrs-length-%d" unique-idx)) 'numeric]])

(defn lrs-feature-metas
  "See [[lrs-features]]."
  [count]
  (concat [[:lrs-len 'numeric]
           [:lrs-unique-chars 'numeric]]
          (->> (range 1 (inc count))
               (map lrs-unique-feature-metas)
               (apply concat))))

(defn lrs-features
  "Return the following features:

  * **:lrs-len** longest repeating string length
  * **:lrs-unique-characters** the number of unique characters in the longest
  repeating string
  * **:lrs-occurs-N** the number of times the string repeated that has N unique
  consecutive characters
  * **:lrs-length-N** the length of the string that has N unique consecutive
  characters

  All where `N` is **unique-char-repeats**, which is a range from 1 to `N` of
  the grouping of consecutive characters.  For example the string

```
          1         2         3         4         5
01234567890123456789012345678901234567890123456789012
abcabc aabb aaaaaa abcabcabcabc abcdefgabcdefgabcdefg
```

  yields:

```
{:lrs-len 14,           ; abcdefgabcdefgabcdefg (TODO: should be 21)
 :lrs-unique-chars 7,   ; abcdefg
 :lrs-length-1 1,       ; 'a'
 :lrs-occurs-1 6,       ; 'aaaaaa' at index 12
 :lrs-length-2 3,       ; ' aa'
 :lrs-occurs-2 1,       ; index: 7
 :lrs-length-3 3,       ; 'abcabc'
 :lrs-occurs-3 4,       ; indexes: 0, 19, 25
 :lrs-length-4 4,       ; ' abc'
 :lrs-occurs-4 1,
 :lrs-length-5 5,       ; 'cdefg' (has to be consecutive/non-overlapping)
 :lrs-occurs-5 1,
 :lrs-length-6 6,       ; 'bcdefg'
 :lrs-occurs-6 1,
 :lrs-length-7 7,       ; 'abcdefg'
 :lrs-occurs-7 3}       ; indexes: 32, 39, 49
```"
  [text unique-char-repeats]
  (let [text (s/replace text #"\s+" " ")
        reps (->> (StringUtils/longestRepeatedString text)
                  (map (fn [rs]
                         {:str rs
                          :length (count rs)
                          :occurs (StringUtils/countConsecutiveOccurs rs text)
                          :unique (count (StringUtils/uniqueChars rs))}))
                  (#(if (empty? %)
                      [{:str "" :length -1 :occurs -1 :unique -1}]
                      %))
                  (sort (fn [a b]
                          (compare (:occurs b) (:occurs a)))))
        lrs-features (->> reps
                          (sort (fn [a b]
                                  (compare (:length b) (:length a))))
                          (take 1)
                          (map (fn [{:keys [length unique]}]
                                 {:lrs-len length
                                  :lrs-unique-chars unique}))
                          first)
        rng (range 1 (inc unique-char-repeats))]
    ;(clojure.pprint/pprint reps)
    (->> rng
         (map (fn [ucr]
                (first (filter #(-> % :unique (= ucr)) reps))))
         (map (fn [cnt rep]
                (or rep {:unique cnt}))
              rng)
         (map (fn [{:keys [length occurs unique]}]
                (zipmap (map first (lrs-unique-feature-metas unique))
                        [(or occurs -1) (or length -1)])))
         (apply merge lrs-features))))

;; chararcter distribution
(defn char-dist-feature-metas
  "See [[char-dist-features]]."
  []
  [[:char-dist-unique 'numeric]
   [:char-dist-unique-ratio 'numeric]
   [:char-dist-variance 'numeric]
   [:char-dist-mean 'numeric]
   [:char-dist-count 'numeric]])

(defn char-dist-features
  "Return the number of unique characters in **text**."
  [text]
  (let [char-dist (->> (StringUtils/uniqueCharCounts text) vals)
        len (count text)]
   {:char-dist-unique (count char-dist)
    :char-dist-unique-ratio (if (= len 0) -1 (/ (count char-dist) len))
    :char-dist-count len
    :char-dist-variance (if (= len 0) -1 (->> char-dist stat/variance))
    :char-dist-mean (if (= len 0) -1 (->> char-dist stat/mean))}))
