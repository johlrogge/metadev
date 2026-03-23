#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; gh-repo MCP server
;; Exposes GitHub repo tools for tracking upstream releases and dependencies

(defn run-gh-api
  "Call `gh api` with the given args. Returns {:ok bool :output string}."
  [& args]
  (let [cmd    (into ["gh" "api"] args)
        result (apply p/shell {:out :string :err :string} cmd)]
    (if (zero? (:exit result))
      {:ok true :output (str/trim (:out result))}
      {:ok false :output (str/trim (str (:err result)
                                        (when-not (str/blank? (:out result))
                                          (str "\n" (:out result)))))})))

(defn fetch-json
  "Call `gh api` and parse the JSON response into a Clojure map."
  [& args]
  (let [result (apply run-gh-api args)]
    (if (:ok result)
      {:ok true :data (json/parse-string (:output result) true)}
      {:ok false :error (:output result)})))

;; ── Tool implementations ──────────────────────────────────────────────────────

(defn gh-repo-tags [repo limit]
  (let [n      (or limit 10)
        ;; Use jq -r to emit raw (unquoted) tag names, one per line
        result (run-gh-api (str "/repos/" repo "/tags")
                           "-q" (str ".[0:" n "] | .[].name"))]
    (if (:ok result)
      (if (str/blank? (:output result))
        "(no tags found)"
        (:output result))
      (str "Error: " (:output result)))))

(defn gh-tag-commit [repo tag]
  ;; /git/ref/tags/<tag> returns the ref object.
  ;; .object.type is either "commit" (lightweight tag) or "tag" (annotated tag).
  ;; For annotated tags follow the chain via /git/tags/<sha> until we hit a commit.
  (let [ref-result (fetch-json (str "/repos/" repo "/git/ref/tags/" tag))]
    (if-not (:ok ref-result)
      (str "Error: " (:error ref-result))
      (let [obj      (get-in ref-result [:data :object])
            obj-type (:type obj)
            obj-sha  (:sha obj)]
        (if (= obj-type "commit")
          obj-sha
          (loop [sha obj-sha]
            (let [tag-result (fetch-json (str "/repos/" repo "/git/tags/" sha))]
              (if-not (:ok tag-result)
                (str "Error: " (:error tag-result))
                (let [inner-obj  (get-in tag-result [:data :object])
                      inner-type (:type inner-obj)
                      inner-sha  (:sha inner-obj)]
                  (if (= inner-type "commit")
                    inner-sha
                    (recur inner-sha)))))))))))

(defn gh-compare [repo base head]
  ;; Use jq to extract the first line of each commit message
  (let [result (run-gh-api (str "/repos/" repo "/compare/" base "..." head)
                           "-q" ".commits[].commit.message | split(\"\\n\")[0]")]
    (if (:ok result)
      (if (str/blank? (:output result))
        "(no commits between these refs)"
        (:output result))
      (str "Error: " (:output result)))))

(defn gh-file [repo path ref]
  (let [result (fetch-json (str "/repos/" repo "/contents/" path "?ref=" ref))]
    (if-not (:ok result)
      (str "Error: " (:error result))
      (let [content  (get-in result [:data :content])
            encoding (get-in result [:data :encoding])]
        (cond
          (nil? content)
          (str "Error: no content field in response (path may be a directory)")

          (= encoding "base64")
          (String. (.decode (java.util.Base64/getMimeDecoder)
                            ^String (str/replace content "\n" "")))

          :else
          content)))))

;; ── Tool dispatch ─────────────────────────────────────────────────────────────

(defn handle-tool-call [name arguments]
  (try
    (case name
      "gh_repo_tags"   (gh-repo-tags  (:repo arguments) (:limit arguments))
      "gh_tag_commit"  (gh-tag-commit (:repo arguments) (:tag  arguments))
      "gh_compare"     (gh-compare    (:repo arguments) (:base arguments) (:head arguments))
      "gh_file"        (gh-file       (:repo arguments) (:path arguments) (:ref  arguments))
      (str "Unknown tool: " name))
    (catch Exception e
      (str "Error: " (.getMessage e)))))

;; ── Tool schemas ──────────────────────────────────────────────────────────────

(def tools
  [{:name "gh_repo_tags"
    :description "List tags for a GitHub repository, most recent first."
    :inputSchema {:type "object"
                  :properties {"repo"  {:type "string"  :description "Repository in owner/name format, e.g. \"owner/repo\"."}
                               "limit" {:type "integer" :description "Maximum number of tags to return (default: 10)."}}
                  :required ["repo"]}}

   {:name "gh_tag_commit"
    :description "Resolve a tag to its commit SHA. Handles both lightweight and annotated tags by following the object chain until a commit is reached."
    :inputSchema {:type "object"
                  :properties {"repo" {:type "string" :description "Repository in owner/name format, e.g. \"owner/repo\"."}
                               "tag"  {:type "string" :description "The tag name, e.g. \"v1.2.3\" or \"0.8.0\"."}}
                  :required ["repo" "tag"]}}

   {:name "gh_compare"
    :description "Get the first line of each commit message between two refs (tags, branches, or SHAs). Uses three-dot diff (base...head)."
    :inputSchema {:type "object"
                  :properties {"repo" {:type "string" :description "Repository in owner/name format, e.g. \"owner/repo\"."}
                               "base" {:type "string" :description "The base ref (older), e.g. \"0.7.1\"."}
                               "head" {:type "string" :description "The head ref (newer), e.g. \"0.8.0\"."}}
                  :required ["repo" "base" "head"]}}

   {:name "gh_file"
    :description "Fetch a file's content from a specific ref (tag, branch, or commit SHA). Base64-encoded content is automatically decoded."
    :inputSchema {:type "object"
                  :properties {"repo" {:type "string" :description "Repository in owner/name format, e.g. \"owner/repo\"."}
                               "path" {:type "string" :description "Path to the file within the repo, e.g. \"Cargo.toml\"."}
                               "ref"  {:type "string" :description "The ref to fetch from: a tag, branch name, or commit SHA."}}
                  :required ["repo" "path" "ref"]}}])

;; ── MCP protocol loop ─────────────────────────────────────────────────────────

(loop []
  (when-let [line (read-line)]
    (let [req    (json/parse-string line true)
          id     (:id req)
          method (:method req)]
      (case method
        "initialize"
        (println (json/generate-string
                  {:jsonrpc "2.0"
                   :id id
                   :result {:protocolVersion "2024-11-05"
                            :capabilities {:tools {}}
                            :serverInfo {:name "gh-repo" :version "0.1.0"}}}))

        "tools/list"
        (println (json/generate-string
                  {:jsonrpc "2.0"
                   :id id
                   :result {:tools tools}}))

        "tools/call"
        (let [params (:params req)
              result (handle-tool-call (:name params) (:arguments params))]
          (println (json/generate-string
                    {:jsonrpc "2.0"
                     :id id
                     :result {:content [{:type "text" :text result}]}})))

        nil))
    (recur)))
