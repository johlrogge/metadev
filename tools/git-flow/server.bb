#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; git-flow MCP server — feature branch operations
;; Covers: init, feature start/finish/list, status
;; Deliberately excludes: release, hotfix (those are in git-flow-release)

(defn run-git-flow [path & args]
  (let [cmd    (into ["git" "-C" path "flow"] args)
        result (apply p/shell {:out :string :err :string} cmd)]
    (if (zero? (:exit result))
      {:ok true :output (str/trim (:out result))}
      {:ok false :output (str/trim (:err result))})))

(defn fmt [{:keys [ok output]}]
  (if ok
    (if (str/blank? output) "Done." output)
    (str "Error: " output)))

(defn valid-name? [s]
  (and (not (str/blank? s))
       (re-matches #"[a-zA-Z0-9._/-]+" s)))

(defn handle-tool-call [name arguments]
  (try
    (let [path (:path arguments)]
      (when (str/blank? path)
        (throw (ex-info "path is required" {})))
      (case name
        "gitflow_init"
        (fmt (run-git-flow path "init" "-d"))

        "gitflow_feature_start"
        (let [branch (:branch arguments)]
          (when-not (valid-name? branch)
            (throw (ex-info "branch must be a valid alphanumeric name" {})))
          (fmt (run-git-flow path "feature" "start" branch)))

        "gitflow_feature_finish"
        (let [branch (:branch arguments)]
          (when-not (valid-name? branch)
            (throw (ex-info "branch must be a valid alphanumeric name" {})))
          (fmt (run-git-flow path "feature" "finish" branch)))

        "gitflow_feature_list"
        (fmt (run-git-flow path "feature" "list"))

        "gitflow_status"
        (let [branches (apply p/shell {:out :string :err :string}
                               ["git" "-C" path "branch" "-a" "--no-color"])]
          (if (zero? (:exit branches))
            (str/trim (:out branches))
            (str "Error: " (str/trim (:err branches)))))

        (str "Unknown tool: " name)))
    (catch clojure.lang.ExceptionInfo e
      (str "Error: " (ex-message e)))))

(def tools
  [{:name        "gitflow_init"
    :description "Initialize git flow in a repository with default branch names (main, develop, feature/, release/, hotfix/)."
    :inputSchema {:type       "object"
                  :properties {"path" {:type "string" :description "Absolute path to the git repository root"}}
                  :required   ["path"]}}

   {:name        "gitflow_feature_start"
    :description "Start a new feature branch from develop. Creates feature/<branch>."
    :inputSchema {:type       "object"
                  :properties {"path"   {:type "string" :description "Absolute path to the git repository root"}
                               "branch" {:type "string" :description "Feature branch name (no 'feature/' prefix)"}}
                  :required   ["path" "branch"]}}

   {:name        "gitflow_feature_finish"
    :description "Finish a feature branch: merges feature/<branch> into develop and deletes the branch."
    :inputSchema {:type       "object"
                  :properties {"path"   {:type "string" :description "Absolute path to the git repository root"}
                               "branch" {:type "string" :description "Feature branch name (no 'feature/' prefix)"}}
                  :required   ["path" "branch"]}}

   {:name        "gitflow_feature_list"
    :description "List all active feature branches."
    :inputSchema {:type       "object"
                  :properties {"path" {:type "string" :description "Absolute path to the git repository root"}}
                  :required   ["path"]}}

   {:name        "gitflow_status"
    :description "Show all branches, including current feature/release/hotfix branches in flight."
    :inputSchema {:type       "object"
                  :properties {"path" {:type "string" :description "Absolute path to the git repository root"}}
                  :required   ["path"]}}])

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
                                :serverInfo {:name "git-flow" :version "0.1.0"}}}))

            "tools/list"
            (println (json/generate-string
                      {:jsonrpc "2.0" :id id
                       :result {:tools tools}}))

            "tools/call"
            (let [params (:params req)
                  result (try
                           (handle-tool-call (:name params) (:arguments params))
                           (catch Exception e (str "Error: " (.getMessage e))))]
              (println (json/generate-string
                        {:jsonrpc "2.0" :id id
                         :result {:content [{:type "text" :text result}]}})))

            nil))))
    (recur)))
