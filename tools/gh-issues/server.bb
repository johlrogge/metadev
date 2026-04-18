#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; gh-issues MCP server
;; Cross-session bug tracking via GitHub Issues: list, read, create, close, comment

(defn run-gh [& args]
  (let [cmd    (into ["gh"] args)
        result (apply p/shell {:out :string :err :string} cmd)]
    (if (zero? (:exit result))
      {:ok true :output (str/trim (:out result))}
      {:ok false :output (str/trim (str (:err result)
                                        (when-not (str/blank? (:out result))
                                          (str "\n" (:out result)))))})))

(defn format-result [{:keys [ok output]}]
  (if ok
    (if (str/blank? output) "Done." output)
    (str "Error: " output)))

(defn gh-issue-list [repo label limit]
  (let [args (cond-> ["issue" "list"
                      "--limit" (str (or limit 20))
                      "--state" "open"]
               (not (str/blank? repo))  (into ["--repo" repo])
               (not (str/blank? label)) (into ["--label" label]))]
    (format-result (apply run-gh args))))

(defn gh-issue-read [number repo]
  (when (str/blank? (str number))
    (throw (ex-info "number is required" {})))
  (let [args (cond-> ["issue" "view" (str number)
                      "--json" "number,title,state,author,labels,body,comments"]
               (not (str/blank? repo)) (into ["--repo" repo]))
        {:keys [ok output] :as r} (apply run-gh args)]
    (if ok
      (let [{:keys [number title state author labels body comments]}
            (json/parse-string output true)
            header (str "#" number " [" state "] " title "\n"
                        "by @" (:login author)
                        (when (seq labels)
                          (str "  labels: " (str/join ", " (map :name labels)))))
            comments-str (when (seq comments)
                           (str "\n\n--- Comments ---\n"
                                (str/join "\n\n"
                                          (for [c comments]
                                            (str "@" (:login (:author c)) " — " (:createdAt c) "\n"
                                                 (:body c))))))]
        (str header "\n\n" body comments-str))
      (format-result r))))

(defn gh-issue-create [title body label repo]
  (when (str/blank? title)
    (throw (ex-info "title is required" {})))
  (when (str/blank? body)
    (throw (ex-info "body is required" {})))
  (let [args (cond-> ["issue" "create"
                      "--title" title
                      "--body"  body]
               (not (str/blank? repo))  (into ["--repo" repo])
               (not (str/blank? label)) (into ["--label" label]))]
    (format-result (apply run-gh args))))

(defn gh-issue-close [number comment repo]
  (when (str/blank? (str number))
    (throw (ex-info "number is required" {})))
  (when (str/blank? comment)
    (throw (ex-info "comment is required" {})))
  ;; First add the resolution comment, then close
  (let [comment-args (cond-> ["issue" "comment" (str number) "--body" comment]
                       (not (str/blank? repo)) (into ["--repo" repo]))
        comment-result (apply run-gh comment-args)]
    (if (:ok comment-result)
      (let [close-args (cond-> ["issue" "close" (str number)]
                         (not (str/blank? repo)) (into ["--repo" repo]))
            close-result (apply run-gh close-args)]
        (format-result close-result))
      (format-result comment-result))))

(defn gh-issue-comment [number body repo]
  (when (str/blank? (str number))
    (throw (ex-info "number is required" {})))
  (when (str/blank? body)
    (throw (ex-info "body is required" {})))
  (let [args (cond-> ["issue" "comment" (str number) "--body" body]
               (not (str/blank? repo)) (into ["--repo" repo]))]
    (format-result (apply run-gh args))))

(defn handle-tool-call [name arguments]
  (try
    (case name
      "gh_issue_list"
      (gh-issue-list (:repo arguments) (:label arguments) (:limit arguments))

      "gh_issue_read"
      (gh-issue-read (:number arguments) (:repo arguments))

      "gh_issue_create"
      (gh-issue-create (:title arguments) (:body arguments) (:label arguments) (:repo arguments))

      "gh_issue_close"
      (gh-issue-close (:number arguments) (:comment arguments) (:repo arguments))

      "gh_issue_comment"
      (gh-issue-comment (:number arguments) (:body arguments) (:repo arguments))

      (str "Unknown tool: " name))
    (catch clojure.lang.ExceptionInfo e
      (str "Error: " (ex-message e)))))

(def tools
  [{:name "gh_issue_list"
    :description "List open GitHub issues. Optionally filter by label."
    :inputSchema {:type "object"
                  :properties {"repo"  {:type "string"  :description "Repository in owner/name format. Omit to use the repo in the current directory."}
                               "label" {:type "string"  :description "Filter issues by label name."}
                               "limit" {:type "integer" :description "Maximum number of issues to return (default: 20)."}}
                  :required []}}

   {:name "gh_issue_read"
    :description "Read a specific GitHub issue including all comments."
    :inputSchema {:type "object"
                  :properties {"number" {:type "integer" :description "The issue number."}
                               "repo"   {:type "string"  :description "Repository in owner/name format. Omit to use the repo in the current directory."}}
                  :required ["number"]}}

   {:name "gh_issue_create"
    :description "Create a new GitHub issue. Use this to report bugs or tasks that should persist across sessions."
    :inputSchema {:type "object"
                  :properties {"title" {:type "string" :description "Issue title."}
                               "body"  {:type "string" :description "Issue body (markdown supported)."}
                               "label" {:type "string" :description "Label to apply to the issue (e.g. 'bug', 'enhancement')."}
                               "repo"  {:type "string" :description "Repository in owner/name format. Omit to use the repo in the current directory."}}
                  :required ["title" "body"]}}

   {:name "gh_issue_close"
    :description "Close a GitHub issue with a resolution comment explaining what was done to fix it."
    :inputSchema {:type "object"
                  :properties {"number"  {:type "integer" :description "The issue number to close."}
                               "comment" {:type "string"  :description "Resolution comment — what was done to fix or address the issue."}
                               "repo"    {:type "string"  :description "Repository in owner/name format. Omit to use the repo in the current directory."}}
                  :required ["number" "comment"]}}

   {:name "gh_issue_comment"
    :description "Add a comment to an existing GitHub issue."
    :inputSchema {:type "object"
                  :properties {"number" {:type "integer" :description "The issue number."}
                               "body"   {:type "string"  :description "Comment body (markdown supported)."}
                               "repo"   {:type "string"  :description "Repository in owner/name format. Omit to use the repo in the current directory."}}
                  :required ["number" "body"]}}])

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
                            :serverInfo {:name "gh-issues" :version "0.1.0"}}}))

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
