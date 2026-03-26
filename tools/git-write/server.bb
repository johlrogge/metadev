#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; git-write MCP server
;; Exposes safe, scoped git write operations: add, commit, stash, checkout, git-flow
;; Deliberately excludes: push, reset --hard, force operations, branch deletion

(defn run-git [repo-path & args]
  (let [cmd (into ["git" "-C" repo-path] args)
        result (apply p/shell {:out :string :err :string} cmd)]
    (if (zero? (:exit result))
      {:ok true :output (str/trim (:out result))}
      {:ok false :output (str/trim (:err result))})))

(defn format-result [{:keys [ok output]}]
  (if ok
    (if (str/blank? output)
      "Done."
      output)
    (str "Error: " output)))

(def forbidden-files #{"-A" "." "*"})

(defn git-add [path files]
  (when (str/blank? files)
    (throw (ex-info "files parameter is required" {})))
  (let [file-list (str/split (str/trim files) #"\s+")]
    (when (some forbidden-files file-list)
      (throw (ex-info "Use specific file paths, not wildcards" {})))
    (format-result (apply run-git path "add" "--" file-list))))

(defn git-commit [path message]
  (when (str/blank? message)
    (throw (ex-info "Commit message must not be empty" {})))
  (format-result (run-git path "commit" "-m" message)))

(defn git-stash [path action]
  (when (not (contains? #{"push" "pop"} action))
    (throw (ex-info (str "action must be 'push' or 'pop', got: " action) {})))
  (format-result (run-git path "stash" action)))

(defn valid-branch-name? [s]
  (and (not (str/blank? s))
       (re-matches #"[a-zA-Z0-9._/-]+" s)))

(defn git-checkout [path branch]
  (when (str/blank? branch)
    (throw (ex-info "branch is required" {})))
  (when-not (valid-branch-name? branch)
    (throw (ex-info "branch must be a valid alphanumeric branch name" {})))
  (format-result (run-git path "checkout" branch)))

(def allowed-flow-actions
  #{"feature start" "feature finish" "feature list"
    "release start" "release finish"
    "hotfix start" "hotfix finish"
    "init"})

(defn git-cherry-pick [path commits no-commit]
  (when (str/blank? commits)
    (throw (ex-info "commits parameter is required" {})))
  (let [commit-list (str/split (str/trim commits) #"\s+")
        args        (cond-> ["cherry-pick"]
                      no-commit (conj "-n")
                      true      (into commit-list))
        result      (apply run-git path args)]
    (if (:ok result)
      (format-result result)
      ;; On conflict, include git status so the caller can see what needs resolving
      (let [status (run-git path "status" "--short")]
        (str "Error: " (:output result)
             (when-not (str/blank? (:output status))
               (str "\n\nConflicting files:\n" (:output status))))))))

(defn git-flow [path action name]
  (when (str/blank? action)
    (throw (ex-info "action is required" {})))
  (when-not (contains? allowed-flow-actions action)
    (throw (ex-info (str "action must be one of: " (str/join ", " (sort allowed-flow-actions))) {})))
  (let [action-parts (str/split action #"\s+")
        cmd-args     (if (str/blank? name)
                       action-parts
                       (conj (vec action-parts) name))
        result       (apply p/shell {:out :string :err :string :dir path}
                            (into ["git" "flow"] cmd-args))]
    (format-result (if (zero? (:exit result))
                     {:ok true :output (str/trim (:out result))}
                     {:ok false :output (str/trim (:err result))}))))

(defn git-rm [path files cached recursive]
  (when (str/blank? files)
    (throw (ex-info "files parameter is required" {})))
  (let [file-list (str/split (str/trim files) #"\s+")]
    (when (some forbidden-files file-list)
      (throw (ex-info "Use specific file paths, not wildcards" {})))
    (let [args (cond-> ["rm"]
                 cached    (conj "--cached")
                 recursive (conj "-r")
                 true      (conj "--")
                 true      (into file-list))]
      (format-result (apply run-git path args)))))

(defn handle-tool-call [name arguments]
  (try
    (case name
      "git_add"
      (git-add (:path arguments) (:files arguments))

      "git_rm"
      (git-rm (:path arguments) (:files arguments) (:cached arguments) (:recursive arguments))

      "git_commit"
      (git-commit (:path arguments) (:message arguments))

      "git_stash"
      (git-stash (:path arguments) (:action arguments))

      "git_checkout"
      (git-checkout (:path arguments) (:branch arguments))

      "git_cherry_pick"
      (git-cherry-pick (:path arguments) (:commits arguments) (:no_commit arguments))

      "git_flow"
      (git-flow (:path arguments) (:action arguments) (:name arguments))

      (str "Unknown tool: " name))
    (catch clojure.lang.ExceptionInfo e
      (str "Error: " (ex-message e)))))

(def tools
  [{:name "git_add"
    :description "Stage specific files for commit. Never accepts wildcards like -A, ., or *. Use explicit file paths only."
    :inputSchema {:type "object"
                  :properties {"path"  {:type "string" :description "Absolute path to the git repository root"}
                               "files" {:type "string" :description "Space-separated list of file paths to stage. Must be explicit paths — never '-A', '.', or '*'."}}
                  :required ["path" "files"]}}
   {:name "git_commit"
    :description "Create a commit with the staged changes."
    :inputSchema {:type "object"
                  :properties {"path"    {:type "string" :description "Absolute path to the git repository root"}
                               "message" {:type "string" :description "Commit message. Must not be empty."}}
                  :required ["path" "message"]}}
   {:name "git_stash"
    :description "Push or pop the git stash."
    :inputSchema {:type "object"
                  :properties {"path"   {:type "string" :description "Absolute path to the git repository root"}
                               "action" {:type "string" :enum ["push" "pop"] :description "'push' to stash current changes, 'pop' to restore the most recent stash"}}
                  :required ["path" "action"]}}

   {:name "git_checkout"
    :description "Checkout an existing branch."
    :inputSchema {:type "object"
                  :properties {"path"   {:type "string" :description "Absolute path to the git repository root"}
                               "branch" {:type "string" :description "Branch name to checkout. Must already exist."}}
                  :required ["path" "branch"]}}

   {:name "git_cherry_pick"
    :description "Cherry-pick one or more commits onto the current branch."
    :inputSchema {:type "object"
                  :properties {"path"      {:type "string" :description "Absolute path to the git repository root"}
                               "commits"   {:type "string" :description "Space-separated list of commit hashes or refs to cherry-pick (e.g. \"abc1234\" or \"abc1234 def5678\")"}
                               "no_commit" {:type "boolean" :description "If true, apply changes but do not create a commit (-n flag), leaving them staged. Default: false."}}
                  :required ["path" "commits"]}}

   {:name "git_rm"
    :description "Remove files from the index (and optionally the working tree). Use cached=true to remove from index only without deleting the file (the common case for fixing .gitignore misses)."
    :inputSchema {:type "object"
                  :properties {"path"      {:type "string"  :description "Absolute path to the git repository root"}
                               "files"     {:type "string"  :description "Space-separated list of file paths to remove. Must be explicit paths — never '-A', '.', or '*'."}
                               "cached"    {:type "boolean" :description "If true, remove from index only, leaving the file on disk (git rm --cached). Default: false."}
                               "recursive" {:type "boolean" :description "If true, allow recursive removal of directories (-r). Default: false."}}
                  :required ["path" "files"]}}

   {:name "git_flow"
    :description "Run a git flow command. Supports feature/release/hotfix start and finish, feature list, and init."
    :inputSchema {:type "object"
                  :properties {"path"   {:type "string" :description "Absolute path to the git repository root"}
                               "action" {:type "string"
                                         :enum ["feature start" "feature finish" "feature list"
                                                "release start" "release finish"
                                                "hotfix start" "hotfix finish"
                                                "init"]
                                         :description "The git flow sub-command to run"}
                               "name"   {:type "string" :description "Branch or version name. Required for start/finish actions; omit for 'feature list' and 'init'."}}
                  :required ["path" "action"]}}])

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
                            :serverInfo {:name "git-write" :version "0.1.0"}}}))

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
