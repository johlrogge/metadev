#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; git-write MCP server
;; Exposes safe, scoped git write operations: add, commit, stash, checkout, git-flow,
;; and worktree add/list/remove (confined to <repo>/.worktrees/)
;; Deliberately excludes: push, reset --hard, force operations, branch deletion
;; Exception: git_worktree_remove accepts force=true, limited to worktrees under .worktrees/

(defn shell-result
  "Normalise a babashka.process result (run with :continue true) into
   {:ok bool :output string :exit int}. On failure, prefers stderr, then stdout,
   then a synthesized message so the error is never empty."
  [result label]
  (let [out (str/trim (or (:out result) ""))
        err (str/trim (or (:err result) ""))]
    (if (zero? (:exit result))
      {:ok true :output out :exit 0}
      {:ok false :exit (:exit result)
       :output (cond (not (str/blank? err)) err
                     (not (str/blank? out)) out
                     :else (str label " exited with code " (:exit result)))})))

(defn run-git [repo-path & args]
  (let [result (apply p/shell {:out :string :err :string :continue true}
                      (into ["git" "-C" repo-path] args))]
    (shell-result result (str "git " (first args)))))

(defn format-result [{:keys [ok output]}]
  (if ok
    (if (str/blank? output)
      "Done."
      output)
    (str "Error: " (if (str/blank? output) "unknown error" output))))

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

(defn git-merge [path branch no-ff message]
  (when (str/blank? branch)
    (throw (ex-info "branch is required" {})))
  (when-not (valid-branch-name? branch)
    (throw (ex-info "branch must be a valid alphanumeric branch name" {})))
  (let [args (cond-> ["merge"]
               (not (false? no-ff)) (conj "--no-ff")
               (not (str/blank? message)) (into ["-m" message])
               true (conj branch))
        result (apply run-git path args)]
    (if (:ok result)
      (format-result result)
      ;; On conflict, include git status so the caller can see what needs resolving
      (let [status (run-git path "status" "--short")]
        (str "Error: " (:output result)
             (when-not (str/blank? (:output status))
               (str "\n\nConflicting files:\n" (:output status))))))))

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
        result       (apply p/shell {:out :string :err :string :continue true}
                            (into ["git" "-C" path "flow"] cmd-args))]
    (format-result (shell-result result (str "git flow " action)))))

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

(defn git-mv [path src dest]
  (when (str/blank? src)
    (throw (ex-info "src parameter is required" {})))
  (when (str/blank? dest)
    (throw (ex-info "dest parameter is required" {})))
  (format-result (run-git path "mv" "--" src dest)))

(defn git-resolve-conflict [path strategy files]
  (when (not (contains? #{"ours" "theirs"} strategy))
    (throw (ex-info "strategy must be 'ours' or 'theirs'" {})))
  (when (str/blank? files)
    (throw (ex-info "files parameter is required" {})))
  (let [file-list (str/split (str/trim files) #"\s+")
        args      (into ["checkout" (str "--" strategy) "--"] file-list)]
    (format-result (apply run-git path args))))

(defn git-apply [path patch check]
  (when (str/blank? patch)
    (throw (ex-info "patch parameter is required" {})))
  (let [args (cond-> ["apply"]
               check (conj "--check")
               true  (conj "--")
               true  (conj patch))]
    (format-result (apply run-git path args))))

;; --- worktrees -------------------------------------------------------------
;; All worktrees managed here live under <main-checkout>/.worktrees/<name>.

(def worktree-dir ".worktrees")

(def worktree-name-re #"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

(defn valid-worktree-name? [s]
  (and (string? s)
       (re-matches worktree-name-re s)
       (not (str/includes? s ".."))))

(defn valid-ref? [s]
  (and (string? s)
       (not (str/blank? s))
       (not (str/starts-with? s "-"))
       (not (re-find #"[\s\x00-\x1f\x7f]" s))))

(defn parse-worktree-list
  "Parse `git worktree list --porcelain` output into a vector of maps."
  [porcelain]
  (->> (str/split (str/trim (or porcelain "")) #"\n\s*\n")
       (remove str/blank?)
       (mapv (fn [block]
               (reduce (fn [m line]
                         (let [[k v] (str/split line #" " 2)]
                           (case k
                             "worktree" (assoc m :worktree v)
                             "HEAD"     (assoc m :head v)
                             "branch"   (assoc m :branch v)
                             "detached" (assoc m :detached true)
                             "bare"     (assoc m :bare true)
                             "locked"   (assoc m :locked (or v true))
                             "prunable" (assoc m :prunable (or v true))
                             m)))
                       {}
                       (str/split-lines block))))))

(defn list-worktrees [path]
  (let [result (run-git path "worktree" "list" "--porcelain")]
    (when-not (:ok result)
      (throw (ex-info (:output result) {})))
    (parse-worktree-list (:output result))))

(defn canonical [p]
  (str (fs/canonicalize p)))

(defn main-root
  "Absolute, canonical path of the main worktree of the repository containing path."
  [path]
  (let [main (first (list-worktrees path))]
    (when-not (:worktree main)
      (throw (ex-info (str "could not determine main worktree for " path) {})))
    (when (:bare main)
      (throw (ex-info "bare repositories are not supported" {})))
    (canonical (:worktree main))))

(defn worktree-target [root name]
  (str (fs/path root worktree-dir name)))

(defn ensure-worktrees-ignored!
  "Make sure /.worktrees/ is ignored in the main checkout. If no ignore rule covers it,
   append one to the repository's info/exclude (never touches tracked .gitignore)."
  [root]
  (let [check (run-git root "check-ignore" "-q" (str worktree-dir "/probe"))]
    (case (:exit check)
      0 nil
      1 (let [res (run-git root "rev-parse" "--path-format=absolute" "--git-path" "info/exclude")]
          (when-not (:ok res)
            (throw (ex-info (:output res) {})))
          (let [exclude (:output res)]
            (when-let [parent (fs/parent exclude)]
              (fs/create-dirs parent))
            (spit exclude (str "\n/" worktree-dir "/\n") :append true)))
      (throw (ex-info (:output check) {})))))

(defn- require-worktree-name! [worktree]
  (when (str/blank? worktree)
    (throw (ex-info "worktree is required" {})))
  (when-not (valid-worktree-name? worktree)
    (throw (ex-info "worktree must be a bare name matching [A-Za-z0-9][A-Za-z0-9._-]* (no '/' or '..')" {}))))

(defn git-worktree-add [path worktree ref detach]
  (when (str/blank? path)
    (throw (ex-info "path is required" {})))
  (require-worktree-name! worktree)
  (when (str/blank? ref)
    (throw (ex-info "ref is required" {})))
  (when-not (valid-ref? ref)
    (throw (ex-info "ref must not start with '-' or contain whitespace/control characters" {})))
  (let [root    (main-root path)
        target  (worktree-target root worktree)
        detach? (not (false? detach))]
    (when (or (fs/exists? target) (fs/sym-link? target))
      (throw (ex-info (str "worktree " target " already exists") {})))
    (let [[result where]
          (if detach?
            ;; resolve in the caller's checkout so HEAD means the caller's HEAD
            (let [rev (run-git path "rev-parse" "--verify" "--quiet" "--end-of-options"
                               (str ref "^{commit}"))]
              (when-not (and (:ok rev) (not (str/blank? (:output rev))))
                (throw (ex-info (str "ref '" ref "' does not resolve to a commit") {})))
              (let [sha (:output rev)]
                [(run-git root "worktree" "add" "--detach" "--" target sha)
                 (str "detached at " (subs sha 0 (min 7 (count sha))))]))
            (do
              (when-not (valid-branch-name? ref)
                (throw (ex-info "with detach=false, ref must be a valid branch name" {})))
              (when-not (:ok (run-git root "show-ref" "--verify" "--quiet" (str "refs/heads/" ref)))
                (throw (ex-info (str "branch '" ref "' does not exist (branch creation is not supported)") {})))
              [(run-git root "worktree" "add" "--" target ref)
               (str "on branch " ref)]))]
      (if (:ok result)
        (let [warning (when (fs/exists? (fs/path root worktree-dir))
                        (try
                          (ensure-worktrees-ignored! root)
                          nil
                          (catch clojure.lang.ExceptionInfo e
                            (str "Warning: could not add /" worktree-dir "/ to info/exclude: "
                                 (ex-message e)))))]
          (str "Created worktree at " target " (" where ")"
               (when-not (str/blank? (:output result))
                 (str "\n" (:output result)))
               (when warning
                 (str "\n" warning))))
        (format-result result)))))

(defn git-worktree-list [path]
  (when (str/blank? path)
    (throw (ex-info "path is required" {})))
  (format-result (run-git path "worktree" "list" "--porcelain")))

(defn git-worktree-remove [path worktree force]
  (when (str/blank? path)
    (throw (ex-info "path is required" {})))
  (require-worktree-name! worktree)
  (let [root      (main-root path)
        target    (worktree-target root worktree)
        canon-tgt (canonical target)
        managed   (str (canonical (fs/path root worktree-dir)) "/")
        not-registered (ex-info (str target " is not a registered worktree under "
                                     root "/" worktree-dir "/") {})]
    ;; Defensive: a symlink under .worktrees/ must not let us reach outside it.
    (when-not (str/starts-with? canon-tgt managed)
      (throw not-registered))
    (when-not (some #(and (:worktree %) (= (canonical (:worktree %)) canon-tgt))
                    (list-worktrees root))
      (throw not-registered))
    (when (= canon-tgt root)
      (throw (ex-info "refusing to remove the main worktree" {})))
    (let [caller (canonical path)]
      (when (or (= caller canon-tgt)
                (str/starts-with? caller (str canon-tgt "/")))
        (throw (ex-info "cannot remove the worktree you are calling from; call with the main repo path" {}))))
    (let [args (cond-> ["worktree" "remove"]
                 (true? force) (conj "--force")
                 true          (into ["--" target]))]
      (format-result (apply run-git root args)))))

(defn handle-tool-call [name arguments]
  (try
    (case name
      "git_add"
      (git-add (:path arguments) (:files arguments))

      "git_rm"
      (git-rm (:path arguments) (:files arguments) (:cached arguments) (:recursive arguments))

      "git_mv"
      (git-mv (:path arguments) (:src arguments) (:dest arguments))

      "git_merge"
      (git-merge (:path arguments) (:branch arguments) (:no_ff arguments) (:message arguments))

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

      "git_resolve_conflict"
      (git-resolve-conflict (:path arguments) (:strategy arguments) (:files arguments))

      "git_apply"
      (git-apply (:path arguments) (:patch arguments) (:check arguments))

      "git_worktree_add"
      (git-worktree-add (:path arguments) (:worktree arguments) (:ref arguments) (:detach arguments))

      "git_worktree_list"
      (git-worktree-list (:path arguments))

      "git_worktree_remove"
      (git-worktree-remove (:path arguments) (:worktree arguments) (:force arguments))

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

   {:name "git_mv"
    :description "Move or rename a file, directory, or symlink within the repository (git mv). Stages the rename automatically."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string" :description "Absolute path to the git repository root"}
                               "src"  {:type "string" :description "Source path (relative to repository root)"}
                               "dest" {:type "string" :description "Destination path (relative to repository root)"}}
                  :required ["path" "src" "dest"]}}

   {:name "git_merge"
    :description "Merge a branch into the current HEAD. Defaults to --no-ff to always create a merge commit (preserves branch history). On conflict, returns the error and a list of conflicting files."
    :inputSchema {:type "object"
                  :properties {"path"    {:type "string"  :description "Absolute path to the git repository root"}
                               "branch"  {:type "string"  :description "Name of the branch to merge into HEAD"}
                               "no_ff"   {:type "boolean" :description "If true (default), always create a merge commit even for fast-forward merges. Set to false to allow fast-forward."}
                               "message" {:type "string"  :description "Optional custom merge commit message. If omitted, git uses its default message."}}
                  :required ["path" "branch"]}}

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
                  :required ["path" "action"]}}

   {:name "git_apply"
    :description "Apply a patch file to the working tree (git apply). Useful for transferring changes from worktrees. Use check=true to test whether the patch applies cleanly without modifying files."
    :inputSchema {:type "object"
                  :properties {"path"  {:type "string" :description "Absolute path to the git repository root"}
                               "patch" {:type "string" :description "Absolute path to the patch file to apply"}
                               "check" {:type "boolean" :description "If true, only check if the patch applies cleanly without modifying files (--check). Default: false."}}
                  :required ["path" "patch"]}}

   {:name "git_resolve_conflict"
    :description "Resolve a merge or cherry-pick conflict in specific files by accepting one side. Run git_add on the resolved files afterward to mark them as resolved."
    :inputSchema {:type "object"
                  :properties {"path"     {:type "string" :description "Absolute path to the git repository root"}
                               "strategy" {:type "string" :enum ["ours" "theirs"] :description "'ours' to keep the current branch's version, 'theirs' to take the incoming version"}
                               "files"    {:type "string" :description "Space-separated list of conflicted file paths to resolve"}}
                  :required ["path" "strategy" "files"]}}

   {:name "git_worktree_add"
    :description "Create a git worktree at <repo>/.worktrees/<worktree> (main checkout, even if path is a worktree). Detached at ref by default; set detach=false to check out an existing branch. Adds /.worktrees/ to .git/info/exclude if not already ignored. Never creates branches."
    :inputSchema {:type "object"
                  :properties {"path"     {:type "string"  :description "Absolute path to the git repository root or any of its worktrees"}
                               "worktree" {:type "string"  :description "Bare worktree name, e.g. \"old-code\" -> <repo>/.worktrees/old-code. Letters, digits, '.', '_', '-'; no '/' or '..'."}
                               "ref"      {:type "string"  :description "Commit-ish to check out (branch, tag, or sha). Must not start with '-'. With detach=false it must be an existing local branch."}
                               "detach"   {:type "boolean" :description "If true (default), create a detached worktree at ref. Set to false to check out an existing local branch."}}
                  :required ["path" "worktree" "ref"]}}

   {:name "git_worktree_list"
    :description "List all worktrees of the repository (git worktree list --porcelain)."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string" :description "Absolute path to the git repository root or any of its worktrees"}}
                  :required ["path"]}}

   {:name "git_worktree_remove"
    :description "Remove a worktree previously created under <repo>/.worktrees/. Refuses the main worktree, anything outside .worktrees/, and the worktree you are calling from. Fails on uncommitted changes unless force=true."
    :inputSchema {:type "object"
                  :properties {"path"     {:type "string"  :description "Absolute path to the main repository checkout (call from the main checkout, not from the worktree being removed)"}
                               "worktree" {:type "string"  :description "Bare worktree name, as passed to git_worktree_add"}
                               "force"    {:type "boolean" :description "If true, remove even with uncommitted or untracked changes (git worktree remove --force). Destroys those changes. Default: false."}}
                  :required ["path" "worktree"]}}])

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
