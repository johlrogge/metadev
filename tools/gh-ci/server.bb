#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; gh-ci MCP server
;; Exposes GitHub Actions CI status tools: run list, view, watch, PR checks

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

(defn gh-run-list [repo branch limit]
  (let [args (cond-> ["run" "list"
                      "--json" "databaseId,displayTitle,status,conclusion,headBranch,event,createdAt"
                      "--limit" (str (or limit 10))]
               (not (str/blank? repo))   (into ["--repo" repo])
               (not (str/blank? branch)) (into ["--branch" branch]))]
    (format-result (apply run-gh args))))

(defn gh-run-view [run-id repo]
  (let [args (cond-> ["run" "view" (str run-id) "--log-failed"]
               (not (str/blank? repo)) (into ["--repo" repo]))]
    (format-result (apply run-gh args))))

(defn gh-run-watch [run-id repo]
  (let [args (cond-> ["run" "watch" (str run-id) "--exit-status"]
               (not (str/blank? repo)) (into ["--repo" repo]))]
    (format-result (apply run-gh args))))

(defn gh-pr-checks [pr-number repo]
  (when (str/blank? (str pr-number))
    (throw (ex-info "pr_number is required" {})))
  (let [args (cond-> ["pr" "checks" (str pr-number)]
               (not (str/blank? repo)) (into ["--repo" repo]))]
    (format-result (apply run-gh args))))

(defn handle-tool-call [name arguments]
  (try
    (case name
      "gh_run_list"
      (gh-run-list (:repo arguments) (:branch arguments) (:limit arguments))

      "gh_run_view"
      (gh-run-view (:run_id arguments) (:repo arguments))

      "gh_run_watch"
      (gh-run-watch (:run_id arguments) (:repo arguments))

      "gh_pr_checks"
      (gh-pr-checks (:pr_number arguments) (:repo arguments))

      (str "Unknown tool: " name))
    (catch clojure.lang.ExceptionInfo e
      (str "Error: " (ex-message e)))))

(def tools
  [{:name "gh_run_list"
    :description "List recent GitHub Actions runs. Optionally filter by branch."
    :inputSchema {:type "object"
                  :properties {"repo"   {:type "string" :description "Repository in owner/name format. Omit to use the repo in the current directory."}
                               "branch" {:type "string" :description "Filter runs by branch name."}
                               "limit"  {:type "integer" :description "Number of runs to return (default: 10)."}}
                  :required []}}

   {:name "gh_run_view"
    :description "View details and failed logs for a specific GitHub Actions run."
    :inputSchema {:type "object"
                  :properties {"run_id" {:type "string" :description "The run ID (from gh_run_list)."}
                               "repo"   {:type "string" :description "Repository in owner/name format. Omit to use the repo in the current directory."}}
                  :required ["run_id"]}}

   {:name "gh_run_watch"
    :description "Wait for a GitHub Actions run to complete and return the final status."
    :inputSchema {:type "object"
                  :properties {"run_id" {:type "string" :description "The run ID to watch."}
                               "repo"   {:type "string" :description "Repository in owner/name format. Omit to use the repo in the current directory."}}
                  :required ["run_id"]}}

   {:name "gh_pr_checks"
    :description "Show CI check status for a pull request."
    :inputSchema {:type "object"
                  :properties {"pr_number" {:type "integer" :description "The PR number."}
                               "repo"      {:type "string" :description "Repository in owner/name format. Omit to use the repo in the current directory."}}
                  :required ["pr_number"]}}])

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
                            :serverInfo {:name "gh-ci" :version "0.1.0"}}}))

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
