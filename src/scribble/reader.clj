(ns scribble.reader
  (:use [clojure.set :only [intersection]])
  (:require [chiara.reader.utils :as reader-methods]
            [scribble.types :refer :all]
            [scribble.postprocess :refer :all]
            [scribble.settings :refer :all])
  (:import [scribble.settings Settings]))


(defn- reader-error
  "Throws an ExceptionInfo with the given message.
  If `reader` provides line/column metadata,
  it will be included in the exception."
  [reader & msg]
  (throw
    (ex-info
      (clojure.string/join msg)
      (merge {:type :reader-exception}
             (when-let [[l c] (reader-methods/reader-position reader)]
               {:line l :column c})))))


(defn- here-markers
  [here-str]
  (if (nil? here-str)
    [[] []]
    [
      (str \space here-str)
      (str \space (inverse-str here-str))]))


(declare read-entry)

(defn- read-body
  "Returns a vector of strings and nested forms.
  The strings are separated as
  [leading whitespace, contents, trailing whitespace, newline]
  (for the ease of further processing)."
  [^Settings settings reader here-str]
  (let [[here-start here-end] (here-markers here-str)
        here-marker-len (count here-start)
        escaped (not (nil? here-str))
        entry-char (.entry-char settings)
        body-start-char (.body-start-char settings)
        body-end-char (.body-end-char settings)
        escape-start-char (.escape-start-char settings)
        escape-end-char (.escape-end-char settings)]
  (loop [body-accum []
         str-accum (make-str-accum)
         ; FIXME: using a custom type will be faster
         state {:leading-ws true
                :brace-level 0
                :here-str-pos 0}]
    (let [c (reader-methods/read-1 reader)]
      (cond

        (and escaped
             (= c escape-end-char)
             (= (- (:here-str-pos state)) here-marker-len))
          (if (zero? (:brace-level state))
            (dump-string body-accum (str-accum-pop str-accum here-marker-len))
            (recur body-accum (str-accum-push str-accum c)
              (assoc state
                :brace-level (dec (:brace-level state))
                :here-str-pos 0)))

        (and escaped (= c escape-start-char))
          (let [[body-accum str-accum]
                  (if (:leading-ws state)
                    [(dump-leading-ws body-accum str-accum) [c]]
                    [body-accum (str-accum-push str-accum c)])]
              (recur body-accum str-accum
                (assoc state
                  :here-str-pos 1
                  :leading-ws false)))

        (and escaped (= c body-end-char))
          (let [[body-accum str-accum]
                  (if (:leading-ws state)
                    [(dump-leading-ws body-accum str-accum) [c]]
                    [body-accum (str-accum-push str-accum c)])]
              (recur body-accum str-accum
                (assoc state
                  :here-str-pos -1
                  :leading-ws false)))

        (and escaped
             (= c body-start-char)
             (= (:here-str-pos state) here-marker-len))
          (recur body-accum (str-accum-push str-accum c)
            (assoc state :here-str-pos 0
                         :brace-level (inc (:brace-level state))))

        (and escaped
             (= c entry-char)
             (= (:here-str-pos state) here-marker-len))
          (let [nested-form (read-entry settings reader c)
                str-accum (str-accum-pop str-accum here-marker-len)
                [body-accum str-accum]
                  (if (identical? nested-form reader)
                    [body-accum str-accum]
                    (dump-nested-form
                      body-accum str-accum nested-form (:leading-ws state)))]
            (recur body-accum str-accum
              (assoc state :here-str-pos 0)))

        (and escaped
             (pos? (:here-str-pos state))
             (< (:here-str-pos state) here-marker-len)
             (= c (nth here-start (:here-str-pos state))))
          (recur body-accum (str-accum-push str-accum c)
            (update-in state [:here-str-pos] inc))

        (and escaped
             (neg? (:here-str-pos state))
             (< (- (:here-str-pos state)) here-marker-len)
             (= c (nth here-end (- (:here-str-pos state)))))
          (recur body-accum (str-accum-push str-accum c)
            (update-in state [:here-str-pos] dec))


        ; Starting body part symbol
        ; We allow them to appear un-escaped if they are balanced
        (and (not escaped) (= c body-start-char) (:leading-ws state))
          (recur (dump-leading-ws body-accum str-accum) [c]
            (assoc state :leading-ws false
                         :brace-level (inc (:brace-level state))))

        (and (not escaped) (= c body-start-char))
          (recur body-accum (str-accum-push str-accum c)
            (update-in state [:brace-level] inc))

        (and (not escaped)
             (= c body-end-char)
             (zero? (:brace-level state)))
          (if (:leading-ws state)
            (dump-leading-ws body-accum str-accum)
            (dump-string body-accum str-accum))
        (and (not escaped) (= c body-end-char))
          (if (:leading-ws state)
            (recur (dump-leading-ws body-accum str-accum) [c]
              (assoc state :leading-ws false
                           :brace-level (dec (:brace-level state))))
            (recur body-accum (str-accum-push str-accum c)
              (update-in state [:brace-level] dec)))

        (and (not escaped) (= c entry-char))
          (let [nested-form (read-entry settings reader c)
                [body-accum str-accum]
                  (if (identical? nested-form reader)
                    [body-accum str-accum]
                    (dump-nested-form
                      body-accum str-accum nested-form (:leading-ws state)))]
            (recur body-accum str-accum
              (assoc state :leading-ws false)))



        ; unexpected EOF
        (nil? c)
          (reader-error reader "Unexpected EOF while reading a body part")

        ; newline encountered: dump accumulator,
        ; turn the leading whitespace mode on
        (= c \newline)
          (let [body-accum
                 (-> body-accum
                   (dump-string str-accum)
                   append-newline)]
            (recur
              body-accum
              (make-str-accum)
              (assoc state :leading-ws true :here-str-pos 0)))

        ; in leading whitespace mode, whitespace character encountered
        (and (whitespace? c) (:leading-ws state))
          (recur
            body-accum
            (str-accum-push str-accum c)
            (assoc state :leading-ws true :here-str-pos 0))
        (:leading-ws state)
          (recur (dump-leading-ws body-accum str-accum) [c]
            (assoc state :leading-ws false :here-str-pos 0))

        ; reading characters
        :else (recur body-accum (str-accum-push str-accum c)
          (assoc state :here-str-pos 0)))))))

(defn- validate-here-str
  "Checks that `here-str` does not contain escape-start/end chars,
  entry char, or body-end char (it does not contain the body-start char
  because of the way it was read).
  This makes it easier to watch for it when reading the body part."
  [^Settings settings reader here-str]
  (if (nil? here-str)
    here-str
    (let [prohibited-chars (set [(.entry-char settings)
                                 (.body-end-char settings)
                                 (.escape-start-char settings)
                                 (.escape-end-char settings)])
          here-str-chars (set here-str)]
      (if (empty? (intersection prohibited-chars here-str-chars))
        here-str
        (reader-error reader "Here-string contains invalid characters")))))

(defn- read-body-part
  "Reads a body part, escaped by `here-str`
  (i.e. looking like `` `<here-str>{text here}<inverse-here-str>` ``).
  If `here-str` is `nil`, the body part is considered to be non-escaped."
  [^Settings settings reader here-str]
  (let [here-str (validate-here-str settings reader here-str)
        [_ c] (reader-methods/reader-position reader)
        column (if (nil? c) 0 c)
        body-accum (read-body settings reader here-str)
        body-part (text-postprocess body-accum column)]
    body-part))

(defn- read-until
  "Reads and returns a string until (and not including) `delim`."
  [reader delim]
  (loop [str-accum (make-str-accum)]
    (let [c (reader-methods/read-1 reader)]
      (cond
        (nil? c) (str-accum-finalize str-accum)
        (= delim c)
          (do
            (reader-methods/unread reader c)
            (str-accum-finalize str-accum))
        :else (recur (str-accum-push str-accum c))))))

(defn- read-parts
  "Reads datum and body parts of the form,
  until EOF or whitespace is encountered."
  [^Settings settings reader]
  (let [body-start-char (.body-start-char settings)
        datum-start-char (.datum-start-char settings)
        datum-end-char (.datum-end-char settings)
        escape-start-char (.escape-start-char settings)]
    (loop [forms-read []
           ; We want to make a difference between `@foo[]`
           ; (reads as `'(foo)`) and `@foo` (reads as `'foo`).
           ; This flag will be set to `true` when
           ; either datum or body part is encountered.
           forms-present false]
      (let [c (reader-methods/read-1 reader)]
        (cond
          (and (= c escape-start-char)
               (= (reader-methods/peek reader) body-start-char))
            (do
              (reader-methods/read-1 reader)
              (recur
                (conj forms-read (read-body-part settings reader ""))
                true))
          (= c escape-start-char)
            (let [here-str (read-until reader body-start-char)
                  next-c (reader-methods/read-1 reader)]
              (if (nil? next-c)
                (reader-error reader
                  "Unexpected EOF while reading a here-string")
                (let [body-part (read-body-part settings reader here-str)]
                  (recur
                    (conj forms-read body-part)
                    true))))
          (= c body-start-char)
            (recur
              (conj forms-read (read-body-part settings reader nil))
              true)
          (= c datum-start-char)
            (let [forms (reader-methods/read-delimited-list
                           datum-end-char reader)]
              (recur (vec (concat forms-read forms)) true))
          (nil? c)
            (if forms-present
              (list* forms-read)
              reader)
          :else
            (do
              (reader-methods/unread reader c)
              (if forms-present
                (list* forms-read)
                reader)))))))

(defn- skip-to-newline
  "Reads from `reader` until `\\newline` or `EOF` is encountered
  (the final `\\newline` is not consumed).
  Returns `nil`."
  [reader]
  (loop []
    (let [c (reader-methods/read-1 reader)]
      (cond
        (nil? c) nil
        (= \newline c) (do (reader-methods/unread reader c) nil)
        :else (recur)))))

(defn- skip-to-meaningful-char
  "Reads from `reader` until `\\newline` is encountered
  and then until the first non-whitespace character is encountered
  (the final character is not consumed), or until `EOF` is encountered.
  Returns `nil`."
  [reader]
  (loop [newline-encountered false]
    (let [c (reader-methods/read-1 reader)]
      (cond
        (nil? c) nil
        (and newline-encountered (not (whitespace? c)))
          (do (reader-methods/unread reader c) nil)
        (= \newline c) (recur true)
        :else (recur newline-encountered)))))

(defn read-entry
  "The entry point of the reader macro."
  [^Settings settings reader _]
  (let [body-start-char (.body-start-char settings)
        datum-start-char (.datum-start-char settings)
        escape-start-char (.escape-start-char settings)
        escape-end-char (.escape-end-char settings)
        comment-char (.comment-char settings)
        c (reader-methods/read-1 reader)]
    (cond
      (or (= c body-start-char)
          (= c datum-start-char))
        (do
          (reader-methods/unread reader c)
          (read-parts settings reader))
      (= c comment-char)
        (let [next-c (reader-methods/peek reader)]
          (condp = next-c
            comment-char (skip-to-meaningful-char reader)
            body-start-char (read-parts settings reader)
            (skip-to-newline reader))
          ; By convention, if the reader function has read nothing,
          ; it returns the reader.
          reader)
      (whitespace? c)
        (reader-error reader
          "Unexpected whitespace at the start of a Scribble form")
      (nil? c)
        (reader-error reader
          "Unexpected EOF at the start of a Scribble form")
      (= c escape-start-char)
        (let [next-c (reader-methods/peek reader)]
          (if (= next-c escape-start-char)
            (read-parts settings reader)
            (mark-for-splice
              (reader-methods/read-delimited-list
                escape-end-char reader))))
      :else
        (do
          (reader-methods/unread reader c)
          (let [command (reader-methods/read-next reader)
                forms (read-parts settings reader)]
            (cond
              (identical? reader forms) command
              (empty? forms) (list command)
              :else (cons command forms)))))))
