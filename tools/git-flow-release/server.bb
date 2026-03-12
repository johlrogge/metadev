#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; git-flow-release MCP server — release and hotfix operations
;; These operations merge to main, create tags, and affect production.
;; More restricted than features — require explicit version + release message.
;; Deliberately excludes: feature operations (those are in git-flow)

(defn run-git-flow [path & args]
  (let [cmd    (into ["git" "flow"] args)
        result (apply p/shell {:out :string :err :string :dir path} cmd)]
    (if (zero? (:exit result))
      {:ok true :output (str/trim (:out result))}
      {:ok false :output (str/trim (:err result))})))

(defn fmt [{:keys [ok output]}]
  (if ok
    (if (str/blank? output) "Done." output)
    (str "Error: " output)))

(defn valid-version? [s]
  (and (not (str/blank? s))
       (re-matches #"[a-zA-Z0-9._-]+" s)))

(defn handle-tool-call [name arguments]
  (try
    (let [path (:path arguments)]
      (when (str/blank? path)
        (throw (ex-info "path is required" {})))
      (case name
        "gitflow_release_start"
        (let [version (:version arguments)]
          (when-not (valid-version? version)
            (throw (ex-info "version must be a valid version string, e.g. 1.2.0" {})))
          (fmt (run-git-flow path "release" "start" version)))

        "gitflow_release_finish"
        (let [version (:version arguments)
              message (:message arguments)]
          (when-not (valid-version? version)
            (throw (ex-info "version must be a valid version string, e.g. 1.2.0" {})))
          (when (str/blank? message)
            (throw (ex-info "message is required for release finish (used as tag annotation)" {})))
          ;; -m sets the tag message, -n skips the merge commit message prompt
          (fmt (run-git-flow path "release" "finish" "-m" message version)))

        "gitflow_hotfix_start"
        (let [version (:version arguments)]
          (when-not (valid-version? version)
            (throw (ex-info "version must be a valid version string, e.g. 1.2.1" {})))
          (fmt (run-git-flow path "hotfix" "start" version)))

        "gitflow_hotfix_finish"
        (let [version (:version arguments)
              message (:message arguments)]
          (when-not (valid-version? version)
            (throw (ex-info "version must be a valid version string, e.g. 1.2.1" {})))
          (when (str/blank? message)
            (throw (ex-info "message is required for hotfix finish (used as tag annotation)" {})))
          (fmt (run-git-flow path "hotfix" "finish" "-m" message version)))

        (str "Unknown tool: " name)))
    (catch clojure.lang.ExceptionInfo e
      (str "Error: " (ex-message e)))))

(def tools
  [{:name        "gitflow_release_start"
    :description "Start a release branch from develop. Creates release/<version>. Use this to freeze features and prepare for release."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type "string" :description "Absolute path to the git repository root"}
                               "version" {:type "string" :description "Release version, e.g. '1.2.0'"}}
                  :required   ["path" "version"]}}

   {:name        "gitflow_release_finish"
    :description "Finish a release: merges release/<version> into main AND develop, creates an annotated tag, deletes the release branch. This is a production-affecting operation — confirm with the user before calling."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type "string" :description "Absolute path to the git repository root"}
                               "version" {:type "string" :description "Release version, e.g. '1.2.0'"}
                               "message" {:type "string" :description "Tag annotation message summarizing what this release contains"}}
                  :required   ["path" "version" "message"]}}

   {:name        "gitflow_hotfix_start"
    :description "Start a hotfix branch from main. Creates hotfix/<version>. Use for urgent production fixes only."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type "string" :description "Absolute path to the git repository root"}
                               "version" {:type "string" :description "Hotfix version, e.g. '1.2.1'"}}
                  :required   ["path" "version"]}}

   {:name        "gitflow_hotfix_finish"
    :description "Finish a hotfix: merges hotfix/<version> into main AND develop, creates an annotated tag, deletes the hotfix branch. This is a production-affecting operation — confirm with the user before calling."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type "string" :description "Absolute path to the git repository root"}
                               "version" {:type "string" :description "Hotfix version, e.g. '1.2.1'"}
                               "message" {:type "string" :description "Tag annotation message describing the fix"}}
                  :required   ["path" "version" "message"]}}])

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
                                :serverInfo {:name "git-flow-release" :version "0.1.0"}}}))

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
