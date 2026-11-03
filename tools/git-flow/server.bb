#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; git-flow MCP server — feature branch operations
;; Covers: init, feature start/finish/list, status
;; Deliberately excludes: release, hotfix (those are in git-flow-release)

(defn run-git-flow
  "Run a git flow subcommand. GIT_EDITOR=true is a hang guard: any git step
   that falls back to an interactive editor would block forever here, since
   an MCP server has no terminal."
  [path & args]
  (let [cmd    (into ["git" "-C" path "flow"] args)
        result (apply p/shell {:out :string :err :string :continue true
                               :extra-env {"GIT_EDITOR" "true"}}
                      cmd)]
    (if (zero? (:exit result))
      {:ok true :output (str/trim (:out result))}
      ;; git flow writes progress to stdout and the reason to stderr, so a
      ;; failure needs both to be actionable.
      {:ok false :output (str/trim (str/join "\n" (remove str/blank?
                                                          [(:err result)
                                                           (:out result)])))})))

(defn run-git
  "Run a plain git command in path. Same {:ok :output} shape as run-git-flow.
   GIT_EDITOR=true is the same hang guard."
  [path & args]
  (let [result (apply p/shell {:out :string :err :string :continue true
                               :extra-env {"GIT_EDITOR" "true"}}
                      (into ["git" "-C" path] args))]
    (if (zero? (:exit result))
      {:ok true :output (str/trim (:out result))}
      {:ok false :output (str/trim (str/join "\n" (remove str/blank?
                                                          [(:err result)
                                                           (:out result)])))})))

(defn fmt [{:keys [ok output]}]
  (if ok
    (if (str/blank? output) "Done." output)
    (str "Error: " output)))

(defn valid-name? [s]
  (and (not (str/blank? s))
       (re-matches #"[a-zA-Z0-9._/-]+" s)
       (not (str/starts-with? s "-"))
       (not (str/includes? s ".."))
       (not (str/ends-with? s "/"))
       (not (str/ends-with? s ".lock"))))

(defn gitflow-init
  "Set up git flow with plain git (git flow AVH init has no flags for branch
   names and does not reliably honour pre-set config)."
  [path production development tag-prefix]
  (let [production  (if (str/blank? production) "master" production)
        development (if (str/blank? development) "develop" development)
        tag-prefix  (or tag-prefix "")]
    (when-not (valid-name? production)
      (throw (ex-info "production must be a valid branch name" {})))
    (when-not (valid-name? development)
      (throw (ex-info "development must be a valid branch name" {})))
    (when (= production development)
      (throw (ex-info "production and development must differ" {})))
    (when-not (or (= "" tag-prefix) (and (re-matches #"[a-zA-Z0-9._/-]+" tag-prefix)
                                        (not (str/starts-with? tag-prefix "-"))))
      (throw (ex-info "tag_prefix must match [a-zA-Z0-9._/-]+, not start with '-', or be empty" {})))
    (let [existing (run-git path "config" "--get" "gitflow.branch.master")]
      (if (:ok existing)
        {:ok false
         :output (str "already initialised: production=" (:output existing)
                      ", development="
                      (let [d (run-git path "config" "--get" "gitflow.branch.develop")]
                        (if (:ok d) (:output d) "?")))}
        (let [unborn? (not (:ok (run-git path "rev-parse" "--verify" "-q" "HEAD")))
              prod-step
              (if unborn?
                (let [r (run-git path "symbolic-ref" "HEAD" (str "refs/heads/" production))]
                  (if (:ok r)
                    (run-git path "commit" "--allow-empty" "-m" "Initial commit")
                    r))
                (if (:ok (run-git path "rev-parse" "--verify" "-q" (str "refs/heads/" production)))
                  {:ok true}
                  (let [bs (run-git path "branch" "--format=%(refname:short)")]
                    {:ok false
                     :output (str "production branch '" production "' does not exist. "
                                  "Existing local branches: "
                                  (if (str/blank? (:output bs))
                                    "(none)"
                                    (str/join ", " (str/split-lines (:output bs))))
                                  ". Pass `production` explicitly (e.g. \"main\").")})))]
          (if-not (:ok prod-step)
            prod-step
            (let [dev-exists? (:ok (run-git path "rev-parse" "--verify" "-q"
                                            (str "refs/heads/" development)))
                  dev-step (if dev-exists?
                             {:ok true}
                             (run-git path "branch" development production))]
              (if-not (:ok dev-step)
                dev-step
                (let [cfg [["gitflow.branch.master" production]
                           ["gitflow.branch.develop" development]
                           ["gitflow.prefix.feature" "feature/"]
                           ["gitflow.prefix.bugfix" "bugfix/"]
                           ["gitflow.prefix.release" "release/"]
                           ["gitflow.prefix.hotfix" "hotfix/"]
                           ["gitflow.prefix.support" "support/"]
                           ["gitflow.prefix.versiontag" tag-prefix]]
                      failed (some (fn [[k v]]
                                     (let [r (run-git path "config" k v)]
                                       (when-not (:ok r) r)))
                                   cfg)]
                  (or failed
                      (let [co (run-git path "checkout" development)]
                        (if-not (:ok co)
                          co
                          {:ok true
                           :output (str "git flow initialised: production=" production
                                        ", development=" development
                                        (if dev-exists? " (existing)" " (created)")
                                        ", tag prefix="
                                        (if (= "" tag-prefix) "(none)" tag-prefix))}))))))))))))

(defn handle-tool-call [name arguments]
  (try
    (let [path (:path arguments)]
      (when (str/blank? path)
        (throw (ex-info "path is required" {})))
      (case name
        "gitflow_init"
        (fmt (gitflow-init path (:production arguments) (:development arguments)
                           (:tag_prefix arguments)))

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
    :description "Set up git flow with the given branch names (production defaults to `master`, development to `develop`). Errors if git flow is already initialised, or if the production branch is missing on a non-empty repo (pass `production` explicitly, e.g. \"main\"). On an empty repo it creates the production branch with an empty initial commit."
    :inputSchema {:type       "object"
                  :properties {"path"        {:type "string" :description "Absolute path to the git repository root"}
                               "production"  {:type "string" :description "Production branch name (default: master)"}
                               "development" {:type "string" :description "Development branch name (default: develop)"}
                               "tag_prefix"  {:type "string" :description "Version tag prefix, e.g. \"v\" (default: empty)"}}
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
