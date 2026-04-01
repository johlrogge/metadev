#!/usr/bin/env bb

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def cwd (System/getProperty "user.dir"))
(def decisions-dir (str cwd "/docs/adr"))

(defn decisions-path []
  (io/file decisions-dir))

(defn list-adr-files []
  (let [dir (decisions-path)]
    (if (.exists dir)
      (->> (.listFiles dir)
           (filter #(re-matches #"\d{3}-.+\.md" (.getName %)))
           (sort-by #(.getName %)))
      [])))

(defn parse-status [content]
  (when-let [m (re-find #"(?m)^## Status\s*\n+([^\n#]+)" content)]
    (str/trim (second m))))

(defn parse-adr-file [file]
  (let [name (.getName file)
        m (re-matches #"(\d{3})-(.+)\.md" name)]
    (when m
      (let [num (nth m 1)
            slug (nth m 2)
            content (slurp file)
            status (or (parse-status content) "Unknown")]
        {:num num :slug slug :name name :file file :content content :status status}))))

(defn adr-list []
  (let [files (list-adr-files)
        adrs (keep parse-adr-file files)]
    (if (empty? adrs)
      "No ADRs found in docs/adr/"
      (->> adrs
           (sort-by :num)
           (map (fn [{:keys [num slug status]}]
                  (str num " " slug " (Status: " status ")")))
           (str/join "\n")))))

(defn match-adr [id adrs]
  (let [digits-only? (re-matches #"\d+" id)]
    (if digits-only?
      ;; match by number prefix
      (first (filter #(= (:num %) (format "%03d" (Integer/parseInt id))) adrs))
      ;; match by slug substring (also try full "NNN-slug" match)
      (or (first (filter #(= (:name %) (str id ".md")) adrs))
          (first (filter #(str/includes? (:slug %) id) adrs))
          (first (filter #(str/includes? (:name %) id) adrs))))))

(defn adr-read [id]
  (if (str/blank? id)
    "Error: id parameter is required"
    (let [adrs (keep parse-adr-file (list-adr-files))
          match (match-adr id adrs)]
      (if match
        (:content match)
        (str "Error: no ADR found matching id '" id "'")))))

(defn adr-search [query]
  (if (str/blank? query)
    "Error: query parameter is required"
    (let [adrs (keep parse-adr-file (list-adr-files))
          results (for [adr adrs
                        :let [lines (str/split-lines (:content adr))
                              matches (->> lines
                                           (map-indexed (fn [i line] [(inc i) line]))
                                           (filter (fn [[_ line]]
                                                     (str/includes? (str/lower-case line)
                                                                    (str/lower-case query)))))]
                        :when (seq matches)]
                    (str (:name adr) ":\n"
                         (str/join "\n"
                                   (map (fn [[n line]] (str "  " n ": " line)) matches))))]
      (if (empty? results)
        (str "No matches found for query: " query)
        (str/join "\n\n" results)))))

(defn next-adr-num []
  (let [files (list-adr-files)]
    (if (empty? files)
      1
      (let [nums (keep #(when-let [m (re-matches #"(\d{3})-.+\.md" (.getName %))]
                          (Integer/parseInt (second m)))
                       files)]
        (inc (apply max nums))))))

(defn adr-new [slug title]
  (cond
    (str/blank? slug)  "Error: slug parameter is required"
    (str/blank? title) "Error: title parameter is required"
    :else
    (let [num (next-adr-num)
          num-str (format "%03d" num)
          filename (str num-str "-" slug ".md")
          filepath (str decisions-dir "/" filename)
          dir (decisions-path)]
      (when-not (.exists dir)
        (.mkdirs dir))
      (spit filepath
            (str "# ADR-" num-str ": " title "\n"
                 "## Status\n"
                 "Proposed\n"
                 "## Decision\n"
                 "...\n"
                 "## Why\n"
                 "...\n"
                 "## Alternatives considered\n"
                 "- **X** — reason not chosen\n"
                 "## Consequences\n"
                 "- ...\n"))
      (str "Created: docs/adr/" filename))))

(def tools
  [{:name "adr_list"
    :description "List all Architecture Decision Records (ADRs). Returns one ADR per line as 'NNN slug (Status: <status>)', sorted numerically."
    :inputSchema {:type "object"
                  :properties {}
                  :required []}}
   {:name "adr_read"
    :description "Read the full contents of a specific ADR. Match by number (e.g. '001'), slug substring (e.g. 'use-polylith'), or full filename without extension (e.g. '001-use-polylith')."
    :inputSchema {:type "object"
                  :properties {"id" {:type "string"
                                     :description "The ADR identifier: a number ('001'), a slug ('use-polylith'), or full name ('001-use-polylith')"}}
                  :required ["id"]}}
   {:name "adr_search"
    :description "Search ADR content by keyword. Returns matching ADRs with matching lines shown (line number: content), grouped by file."
    :inputSchema {:type "object"
                  :properties {"query" {:type "string"
                                        :description "Search query string (case-insensitive substring match)"}}
                  :required ["query"]}}
   {:name "adr_new"
    :description "Create a new ADR from the standard template. Auto-assigns the next available number. Returns the path of the created file."
    :inputSchema {:type "object"
                  :properties {"slug"  {:type "string"
                                        :description "Kebab-case identifier for the file name, e.g. 'use-polylith'"}
                               "title" {:type "string"
                                        :description "Human-readable title, e.g. 'Use Polylith for workspace structure'"}}
                  :required ["slug" "title"]}}])

(defn handle-tool-call [name arguments]
  (case name
    "adr_list"   (adr-list)
    "adr_read"   (adr-read (:id arguments))
    "adr_search" (adr-search (:query arguments))
    "adr_new"    (adr-new (:slug arguments) (:title arguments))
    (str "Unknown tool: " name)))

(loop []
  (when-let [line (read-line)]
    (let [req (try (json/parse-string line true) (catch Exception _ nil))]
      (when req
        (let [id     (:id req)
              method (:method req)]
          (case method
            "initialize"
            (println (json/generate-string
                      {:jsonrpc "2.0" :id id
                       :result {:protocolVersion "2024-11-05"
                                :capabilities {:tools {}}
                                :serverInfo {:name "adr" :version "0.1.0"}}}))

            "tools/list"
            (println (json/generate-string
                      {:jsonrpc "2.0" :id id
                       :result {:tools tools}}))

            "tools/call"
            (let [params    (:params req)
                  tool-name (:name params)
                  arguments (:arguments params)
                  result    (try
                              (handle-tool-call tool-name arguments)
                              (catch Exception e
                                (str "Error: " (.getMessage e))))]
              (println (json/generate-string
                        {:jsonrpc "2.0" :id id
                         :result {:content [{:type "text" :text result}]}})))

            ;; notifications and unknown methods — no response needed
            nil))))
    (recur)))
