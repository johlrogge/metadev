#!/usr/bin/env bb
;; align-nixpkgs — weekly nixpkgs pin alignment across ~/projects devenv projects.
;;
;; For every project with a devenv.yaml whose nixpkgs input is github:cachix/devenv-nixpkgs/rolling,
;; update devenv.lock in a throwaway worktree (<P>/.worktrees/align-nixpkgs) on branch
;; chore/align-nixpkgs, verify `devenv shell -- true`, and commit only devenv.lock.
;; Never pushes, never fetches. See README.md next to this file.
;;
;; Layout: pure functions first (unit-tested in align_test.bb), effectful functions after.
;; Every external command goes through `exec!`, which applies `base-env` and captures exit/out/err.
;; Set ALIGN_NIXPKGS_DEBUG=1 to echo every command (and its exit code) to stderr.

(ns align-nixpkgs
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]
           [java.time Instant LocalDate ZoneOffset]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(def branch-name "chore/align-nixpkgs")
(def worktree-rel ".worktrees/align-nixpkgs")
(def rolling-url "github:cachix/devenv-nixpkgs/rolling")
(def devenv-default-url "github:cachix/devenv")
(def nixpkgs-remote "https://github.com/cachix/devenv-nixpkgs")
(def devenv-remote "https://github.com/cachix/devenv")
(def trailer-value "metadev align-nixpkgs")
(def trailer-line (str "Aligned-by: " trailer-value))
(def unit-name "metadev-align-nixpkgs")
(def tail-line-count 40)

(def script-file (str (fs/canonicalize *file*)))
(def tool-dir (str (fs/parent script-file)))
;; tools/align-nixpkgs/align.bb -> metadev root
(def metadev-root (str (fs/parent (fs/parent tool-dir))))

(def env-keep
  ["HOME" "USER" "LOGNAME" "PATH" "LANG" "LC_ALL" "TERM" "XDG_RUNTIME_DIR" "XDG_CACHE_HOME"
   "XDG_CONFIG_HOME" "DBUS_SESSION_BUS_ADDRESS" "NIX_PATH" "NIX_SSL_CERT_FILE" "SSL_CERT_FILE"])

;; ---------------------------------------------------------------------------
;; Pure functions
;; ---------------------------------------------------------------------------

(defn short-sha [s]
  (when (string? s) (subs s 0 (min 7 (count s)))))

(defn epoch->date
  "yyyy-mm-dd (UTC) for an epoch-seconds value, nil-safe."
  [secs]
  (when (number? secs)
    (str (.toLocalDate (.atOffset (Instant/ofEpochSecond (long secs)) ZoneOffset/UTC)))))

(defn epoch->minute
  "yyyy-mm-ddTHH:MM (UTC) for an epoch-seconds value, nil-safe."
  [secs]
  (when (number? secs)
    (let [s (str (.atOffset (Instant/ofEpochSecond (long secs)) ZoneOffset/UTC))]
      (subs s 0 (min 16 (count s))))))

(defn base-branch
  "Integration branch: gitflow develop branch if configured, else master, else main —
   the first one that exists among `existing` (set of local branch names)."
  [gitflow-develop existing]
  (->> [gitflow-develop "master" "main"]
       (remove str/blank?)
       (filter #(contains? existing %))
       first))

(defn input-kind [url]
  (cond
    (not (string? url))                    :none
    (str/starts-with? url "path:/")        :absolute-path
    (str/starts-with? url "path:")         :relative-path
    (re-find #"^\.\.?/" url)               :relative-path
    :else                                  :other))

(defn classify-yaml
  "Decide from a parsed devenv.yaml whether the project is in scope.
   Returns {:status :skipped :reason ..} | {:status :nonstandard :url ..} |
           {:status :ok :in-scope [names] :metadev-kind kind :devenv-url url}."
  [y]
  (let [inputs  (:inputs y)
        url-of  (fn [k] (get-in inputs [k :url]))
        nix-url (url-of :nixpkgs)]
    (cond
      (not (contains? inputs :nixpkgs))
      {:status :skipped :reason :no-nixpkgs-input}

      (not= nix-url rolling-url)
      {:status :nonstandard :url nix-url}

      :else
      (if-let [rel (some (fn [[k v]]
                           (when (and (map? v) (= :relative-path (input-kind (:url v))))
                             (name k)))
                         inputs)]
        {:status :skipped :reason :relative-path-input :input rel}
        {:status       :ok
         :in-scope     (filterv #(contains? inputs (keyword %)) ["nixpkgs" "devenv" "metadev"])
         :metadev-kind (when (contains? inputs :metadev) (input-kind (url-of :metadev)))
         :devenv-url   (url-of :devenv)}))))

(defn metadev-plan
  "Whether to update the metadev input. Only an absolute path: input depends on the local
   checkout state; github inputs pull the pushed default branch."
  [kind {:keys [dirty? branch integration]}]
  (case kind
    nil {:update? false :warnings []}
    :absolute-path
    (cond-> {:update? (not dirty?) :warnings []}
      dirty?
      (update :warnings conj "metadev checkout is dirty; metadev path input not updated")
      (and branch integration (not= branch integration))
      (update :warnings conj (str "metadev checkout is on " branch ", not " integration
                                  "; path input follows the checkout")))
    {:update? true :warnings []}))

(defn lock-node
  "Locked node for a top-level input, resolved through nodes.root.inputs (node names may be
   suffixed, e.g. nixpkgs_2). Follows-paths (vectors) are not resolved."
  [lock input]
  (let [nodes (:nodes lock)
        ref   (get-in nodes [:root :inputs (keyword input)])]
    (when (string? ref)
      (get nodes (keyword ref)))))

(defn lock-rev [lock input]
  (get-in (lock-node lock input) [:locked :rev]))

(defn lock-last-modified [lock input]
  (get-in (lock-node lock input) [:locked :lastModified]))

(defn lock-version
  "Human-readable version of an input: short rev, else lastModified (minute precision)."
  [lock input]
  (let [n (lock-node lock input)]
    (or (short-sha (get-in n [:locked :rev]))
        (epoch->minute (get-in n [:locked :lastModified])))))

(defn lock-versions [lock]
  {:nixpkgs (lock-rev lock "nixpkgs")
   :devenv  (lock-rev lock "devenv")
   :metadev (lock-version lock "metadev")})

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

(defn parse-trailer-log
  "Parse `git log --format=%H%x09%(trailers:key=Aligned-by,valueonly,separator=)` into [[sha value]...]."
  [out]
  (->> (str/split-lines (or out ""))
       (remove str/blank?)
       (mapv (fn [line]
               (let [[sha v] (str/split line #"\t" 2)]
                 [sha (str/trim (or v ""))])))))

(defn branch-decision
  "Decide what to do with chore/align-nixpkgs. Facts (worktree paths canonical):
   :wt-path :worktrees :branch-exists? :ancestor? :branch-tip :proposal-commits :diff-files.
   Returns {:decision d ...}, d in #{:create :recreate :reap-and-recreate :skip-checked-out :skip-foreign}."
  [{:keys [wt-path worktrees branch-exists? ancestor? branch-tip proposal-commits diff-files]}]
  (let [ref    (str "refs/heads/" branch-name)
        holder (some #(when (= ref (:branch %)) %) worktrees)
        at-wt? (boolean (and holder (= (:worktree holder) wt-path)))
        ours?  (and (seq proposal-commits)
                    (every? #(= trailer-value (second %)) proposal-commits)
                    (= ["devenv.lock"] (vec diff-files)))
        ok     (fn [d] (if at-wt? (assoc d :decision :reap-and-recreate) d))]
    (cond
      (and holder (not at-wt?)) {:decision :skip-checked-out :where (:worktree holder)}
      (not branch-exists?)      (ok {:decision :create})
      ancestor?                 (ok {:decision :recreate})
      ours?                     (ok {:decision :recreate :replaced (short-sha branch-tip)})
      :else                     {:decision :skip-foreign})))

(defn dry-run-outcome
  "Predict the result without touching anything.
   {:nixpkgs-before :rev :devenv {:before :head :default?} (nil when devenv not in scope)}."
  [{:keys [nixpkgs-before rev devenv]}]
  (let [nix-differs? (not= nixpkgs-before rev)
        devenv-state (when devenv
                       (cond
                         (not (:default? devenv))                 :unknown
                         (nil? (:head devenv))                    :unknown
                         (= (:before devenv) (:head devenv))      :same
                         :else                                    :differs))
        notes (cond-> []
                nix-differs?              (conj (str "nixpkgs -> " (short-sha rev)))
                (= :differs devenv-state) (conj (str "devenv " (short-sha (:before devenv))
                                                     " -> " (short-sha (:head devenv))))
                (= :unknown devenv-state) (conj "devenv: unknown (non-default ref or HEAD unresolved)"))]
    {:status (if (or nix-differs? (#{:differs :unknown} devenv-state)) :would-align :would-noop)
     :notes  notes}))

(defn commit-message [{:keys [rev rev-date before after]}]
  (let [changed? (fn [k] (and (some? (get after k)) (not= (get before k) (get after k))))
        disp     (fn [k v] (if (= k :metadev) (or v "none") (or (short-sha v) "none")))]
    (str (str/join "\n"
                   (concat
                    [(str "chore(deps): align nixpkgs to devenv-nixpkgs " (short-sha rev)
                          (when rev-date (str " (" rev-date ")")))
                     ""
                     (str "nixpkgs: " (disp :nixpkgs (:nixpkgs before)) " -> " (short-sha rev))]
                    (for [k [:devenv :metadev] :when (changed? k)]
                      (str (name k) ": " (disp k (get before k)) " -> " (disp k (get after k))))
                    [""
                     trailer-line]))
         "\n")))

(defn clean-path
  "Drop PATH entries under <devenv-root>/.devenv except keep-bin (metadev's profile bin)."
  [path devenv-root keep-bin]
  (if (str/blank? devenv-root)
    path
    (let [prefix (str devenv-root "/.devenv")]
      (->> (str/split path #":")
           (remove #(and (str/starts-with? % prefix) (not= % keep-bin)))
           (str/join ":")))))

(defn base-env
  "Scrubbed child environment: whitelisted vars only (no DEVENV_*, IN_NIX_SHELL, ...)."
  [env]
  (let [m (into {} (filter (fn [[k v]] (and (some? v) (some #{k} env-keep)))) env)]
    (cond-> m
      (get m "PATH") (update "PATH" clean-path (get env "DEVENV_ROOT")
                             (some-> (get env "DEVENV_PROFILE") (str "/bin"))))))

(defn order-projects
  "metadev first, rest alphabetical by name."
  [projects metadev-path]
  (vec (sort-by (juxt #(not= (:path %) metadev-path) :name) projects)))

(defn select-projects [projects only exclude]
  (let [names   (set (map :name projects))
        only    (set only)
        exclude (set exclude)
        chosen  (if (seq only) (filterv #(only (:name %)) projects) (vec projects))]
    {:selected (filterv #(not (exclude (:name %))) chosen)
     :excluded (filterv #(exclude (:name %)) chosen)
     :unknown  (vec (sort (remove names (concat only exclude))))}))

(defn status-label [{:keys [status reason]}]
  (let [base (str/upper-case (name status))]
    (if (and reason (#{:failed :skipped} status))
      (str base " (" (name reason) ")")
      base)))

(defn report-file-name [date existing]
  (->> (cons (str date ".md") (map #(str date "-" % ".md") (iterate inc 2)))
       (remove #(contains? existing %))
       first))

(defn tail-text [s n]
  (->> (str/split-lines (or s ""))
       (take-last n)
       (str/join "\n")))

(defn- gb [bytes]
  (format "%.1f GB" (/ (double bytes) 1e9)))

(defn- cell [s]
  (str/replace (str s) "|" "\\|"))

(defn- dash [s]
  (if (str/blank? (str s)) "—" (cell s)))

(defn- indent [s]
  (->> (str/split-lines (or s ""))
       (map #(str "    " %))
       (str/join "\n")))

(defn- reference-line [{:keys [rev rev-date devenv-head metadev]}]
  (str "Reference: cachix/devenv-nixpkgs rolling @ " (short-sha rev)
       (when rev-date (str " (" rev-date ")"))
       " · devenv HEAD " (or (short-sha devenv-head) "unknown")
       " · metadev "
       (if metadev
         (str (:branch metadev) "@" (:head metadev) (if (:dirty? metadev) " (dirty)" " (clean)"))
         "unknown")))

(defn- disk-line [{:keys [start end gc-freed]}]
  (when start
    (str "/nix free: " (gb start)
         (when end (str " -> " (gb end)))
         (when gc-freed (str " (GC freed " (gb gc-freed) ")")))))

(def gc-expectations
  (str "GC expectations: first runs free little — old toolchains stay rooted by each project's main "
       "checkout `.devenv/gc`. Space comes back after you merge `chore/align-nixpkgs` and re-enter "
       "`devenv shell` in the main checkout. `nix-store --gc --print-roots | grep projects` shows "
       "remaining roots."))

(defn render-report [{:keys [date mode reference disk results warnings]}]
  (let [failed  (filter #(= :failed (:status %)) results)
        aligned (filter #(= :aligned (:status %)) results)
        warns   (concat warnings
                        (for [r results, w (:warnings r)] (str (:project r) ": " w)))
        row     (fn [r]
                  (str "| " (cell (:project r))
                       " | " (dash (:base r))
                       " | " (dash (short-sha (get-in r [:before :nixpkgs])))
                       " | " (status-label r)
                       " | " (dash (:tip r))
                       " | " (cell (str/join "; " (cond-> (vec (:notes r))
                                                     (= :failed (:status r)) (conj "see below"))))
                       " |"))]
    (str
     (str/join
      "\n"
      (concat
       [(str "# nixpkgs alignment — " date " (" (name mode) ")")
        (reference-line reference)]
       (when-let [d (disk-line disk)] [d])
       [""
        "| Project | Base | nixpkgs before | Result | Branch tip | Notes |"
        "|---|---|---|---|---|---|"]
       (map row results)
       (when (seq failed)
         (concat ["" "## Failures"]
                 (mapcat (fn [r]
                           [(str "### " (:project r) " (" (some-> (:reason r) name) ")"
                                 (when (:log r) (str " — full log: " (:log r))))
                            (indent (:tail r))])
                         failed)))
       (when (seq warns)
         (concat ["" "## Warnings"] (map #(str "- " %) warns)))
       (when (seq aligned)
         (concat ["" "## To review and merge"]
                 (mapcat (fn [{:keys [project path base]}]
                           [""
                            (str "### " project)
                            (str "    git -C " path " log -p " base ".." branch-name)
                            (str "    git -C " path " merge --ff-only " branch-name "   # from " base)
                            (str "    cd " path " && devenv shell    # moves this project's GC root to the common closure")])
                         aligned)))
       (when (= mode :run) ["" gc-expectations])))
     "\n")))

(defn choose-devenv
  "Pick a stable devenv path for the systemd unit: first existing profile candidate, else
   `which devenv` unless it points into /nix/store or a devenv shell profile."
  [existing-candidates which]
  (or (first existing-candidates)
      (when (and (string? which)
                 (not (str/starts-with? which "/nix/store/"))
                 (not (str/includes? which "/.devenv/")))
        which)))

(defn unit-path [devenv-path home]
  (->> [(str (fs/parent devenv-path)) (str home "/.nix-profile/bin")
        "/nix/var/nix/profiles/default/bin" "/usr/local/bin" "/usr/bin" "/bin"]
       distinct
       (str/join ":")))

(defn render-template [tmpl subs]
  (reduce (fn [s [k v]] (str/replace s (str "@" k "@") v)) tmpl subs))

;; ---------------------------------------------------------------------------
;; Effectful edge: process execution
;; ---------------------------------------------------------------------------

(def child-env (delay (base-env (into {} (System/getenv)))))

(defn- debug? [] (some? (System/getenv "ALIGN_NIXPKGS_DEBUG")))

(defn exec!
  "Run an external command. Every child process goes through here.
   - env is replaced by base-env (never extended), stdin is empty, non-zero exit never throws
   - opts: :dir, :log (append cmd + out/err to this file instead of capturing), :timeout-ms
   Returns {:cmd [..] :exit int :out str :err str :timed-out? bool}."
  [{:keys [dir log timeout-ms]} & cmd]
  (let [cmd  (mapv str cmd)
        line (str "$ " (str/join " " cmd) (when dir (str "    # in " dir)))]
    (when (debug?) (binding [*out* *err*] (println "+" line)))
    (when log
      (fs/create-dirs (fs/parent log))
      (spit log (str "\n" line "\n") :append true))
    (let [opts   (cond-> {:env @child-env :in ""}
                   dir       (assoc :dir (str dir))
                   log       (assoc :out :append :out-file (io/file log)
                                    :err :append :err-file (io/file log))
                   (not log) (assoc :out :string :err :string))
          result (try
                   (let [proc (p/process cmd opts)
                         ^Process jproc (:proc proc)
                         done?  (if timeout-ms
                                  (.waitFor jproc (long timeout-ms) TimeUnit/MILLISECONDS)
                                  (do (.waitFor jproc) true))]
                     (if done?
                       (let [r @proc]
                         {:exit (:exit r) :out (or (when-not log (:out r)) "")
                          :err (or (when-not log (:err r)) "") :timed-out? false})
                       (do (p/destroy-tree proc)
                           (.waitFor jproc 30 TimeUnit/SECONDS)
                           {:exit -1 :out "" :err (str "timed out after " timeout-ms " ms")
                            :timed-out? true})))
                   (catch Exception e
                     {:exit 127 :out "" :err (str "could not start " (first cmd) ": " (ex-message e))
                      :timed-out? false}))]
      (when (debug?) (binding [*out* *err*] (println "  exit" (:exit result))))
      (when (and log (or (:timed-out? result) (= 127 (:exit result))))
        (spit log (str (:err result) "\n") :append true))
      (assoc result :cmd cmd))))

(defn ok? [r] (= 0 (:exit r)))

(defn- describe-failure [r]
  (str (str/join " " (:cmd r)) " exited " (:exit r)
       (let [e (str/trim (str (:err r) "\n" (:out r)))]
         (when-not (str/blank? e) (str ": " e)))))

(defn git! [repo & args]
  (apply exec! {} "git" "-C" (str repo) args))

(defn git-out!
  "git output (trimmed) or throw."
  [repo & args]
  (let [r (apply git! repo args)]
    (if (ok? r)
      (str/trim (:out r))
      (throw (ex-info (describe-failure r) {:result r})))))

(defn git-maybe
  "git output (trimmed) or nil on non-zero exit."
  [repo & args]
  (let [r (apply git! repo args)]
    (when (ok? r) (str/trim (:out r)))))

(defn canon [p] (str (fs/canonicalize p)))

(defn- log! [& xs] (apply println xs) (flush))

;; ---------------------------------------------------------------------------
;; Effectful: state, lock, references
;; ---------------------------------------------------------------------------

(defn state-dir []
  (str (fs/path (fs/home) ".local" "state" "metadev" "align-nixpkgs")))

(defn try-lock!
  "Exclusive advisory lock on <dir>/run.lock. Returns a handle or nil if held elsewhere."
  [dir]
  (fs/create-dirs dir)
  (let [ch (FileChannel/open (fs/path dir "run.lock")
                             (into-array OpenOption [StandardOpenOption/WRITE StandardOpenOption/CREATE]))
        ;; OverlappingFileLockException (same-JVM holder) is not exposed by bb; catch broadly.
        lk (try (.tryLock ch)
                (catch Exception e
                  (when-not (str/includes? (str (class e)) "OverlappingFileLock")
                    (.close ch)
                    (throw e))
                  nil))]
    (if lk
      {:channel ch :lock lk}
      (do (.close ch) nil))))

(defn release-lock!
  "Closing the channel releases its lock (FileLock methods are not reflectable in bb)."
  [{:keys [channel]}]
  (when channel (.close ^FileChannel channel)))

(defn ls-remote-sha!
  "First sha of `git ls-remote url ref`, 3 retries with 30 s backoff, nil if unresolvable."
  [url ref]
  (loop [attempt 1]
    (let [r   (exec! {} "git" "ls-remote" url ref)
          sha (when (ok? r)
                (some-> (:out r) str/split-lines first (str/split #"\s+") first))]
      (cond
        (and sha (re-matches #"[0-9a-f]{40}" sha)) sha
        (< attempt 4) (do (log! (str "ls-remote " url " " ref " failed (attempt " attempt "), retrying in 30 s"
                                     (when-not (ok? r) (str ": " (str/trim (:err r))))))
                          (Thread/sleep 30000)
                          (recur (inc attempt)))
        :else nil))))

(defn flake-last-modified! [flake-ref]
  (let [r (exec! {} "nix" "--extra-experimental-features" "nix-command flakes"
                 "flake" "metadata" "--json" flake-ref)]
    (when (ok? r)
      (:lastModified (json/parse-string (:out r) true)))))

(defn local-branches! [repo]
  (->> (str/split-lines (or (git-maybe repo "for-each-ref" "--format=%(refname)" "refs/heads/") ""))
       (keep #(when (str/starts-with? % "refs/heads/") (subs % (count "refs/heads/"))))
       set))

(defn base-branch-of! [repo]
  (base-branch (git-maybe repo "config" "--get" "gitflow.branch.develop")
               (local-branches! repo)))

(defn metadev-state! []
  (try
    {:branch      (git-out! metadev-root "rev-parse" "--abbrev-ref" "HEAD")
     :head        (git-out! metadev-root "rev-parse" "--short=7" "HEAD")
     :dirty?      (not (str/blank? (git-out! metadev-root "status" "--porcelain")))
     :integration (base-branch-of! metadev-root)}
    (catch Exception e
      (log! "warning: could not read metadev checkout state:" (ex-message e))
      nil)))

(defn free-bytes []
  (.getUsableSpace (io/file "/nix/store")))

(defn discover-projects [projects-dir]
  (->> (fs/glob projects-dir "*/devenv.yaml")
       (map #(canon (fs/parent %)))
       distinct
       (mapv (fn [path] {:name (str (fs/file-name path)) :path path}))))

;; ---------------------------------------------------------------------------
;; Effectful: per-project steps
;; ---------------------------------------------------------------------------

(defn- halt! [m] (throw (ex-info "halt" {::halt m})))

(defn- warn! [res msg] (swap! res update :warnings conj msg))
(defn- note! [res msg] (swap! res update :notes conj msg))

(defn- read-lock-file [dir]
  (json/parse-string (slurp (str (fs/path dir "devenv.lock"))) true))

(defn- log-tail [log]
  (if (and log (fs/exists? log))
    (tail-text (slurp log) tail-line-count)
    ""))

(defn branch-facts! [path wt]
  (let [worktrees (mapv #(cond-> % (:worktree %) (update :worktree (fn [w] (try (canon w) (catch Exception _ w)))))
                        (parse-worktree-list (git-out! path "worktree" "list" "--porcelain")))
        exists?   (ok? (git! path "show-ref" "--verify" "--quiet" (str "refs/heads/" branch-name)))]
    {:wt-path   wt
     :worktrees worktrees
     :branch-exists? exists?}))

(defn- add-ancestry-facts! [facts path base]
  (if-not (:branch-exists? facts)
    facts
    (let [ancestor? (ok? (git! path "merge-base" "--is-ancestor" branch-name base))]
      (cond-> (assoc facts
                     :ancestor? ancestor?
                     :branch-tip (git-maybe path "rev-parse" branch-name))
        (not ancestor?)
        (assoc :proposal-commits
               (parse-trailer-log
                (git-out! path "log" "--format=%H%x09%(trailers:key=Aligned-by,valueonly,separator=)"
                          (str base ".." branch-name)))
               :diff-files
               (vec (remove str/blank?
                            (str/split-lines
                             (git-out! path "diff" "--name-only" (str base "..." branch-name))))))))))

;; mirrors git-write ensure-worktrees-ignored!
(defn ensure-worktrees-ignored! [root]
  (let [check (git! root "check-ignore" "-q" ".worktrees/probe")]
    (case (:exit check)
      0 nil
      1 (let [exclude (git-out! root "rev-parse" "--path-format=absolute" "--git-path" "info/exclude")]
          (when-let [parent (fs/parent exclude)]
            (fs/create-dirs parent))
          (spit exclude "\n/.worktrees/\n" :append true))
      (throw (ex-info (describe-failure check) {})))))

(defn- dir-fingerprint [dir]
  (into (sorted-map)
        (for [^java.io.File f (rest (file-seq (io/file dir)))
              :let [rel (str (fs/relativize dir f))]]
          [rel (cond
                 (fs/sym-link? f)  [:link (str (fs/read-link f))]
                 (fs/directory? f) [:dir]
                 :else             [:file (fs/executable? f)
                                    (String. ^bytes (fs/read-all-bytes f) "ISO-8859-1")])])))

(defn snapshot-hooks! [state project path]
  (let [common (git-out! path "rev-parse" "--path-format=absolute" "--git-common-dir")
        hooks  (str (fs/path common "hooks"))
        snap   (str (fs/path state "tmp" (str project "-hooks")))
        exists (fs/exists? hooks)]
    (fs/delete-tree snap)
    (when exists
      (fs/create-dirs (fs/parent snap))
      (fs/copy-tree hooks snap {:copy-attributes true :nofollow-links true}))
    {:hooks       hooks
     :exists?     exists
     :snap        snap
     :fingerprint (when exists (dir-fingerprint hooks))
     :hooks-path  (git-maybe path "config" "--local" "--get" "core.hooksPath")}))

(defn restore-hooks!
  "Undo any change the build made to the shared hooks dir / core.hooksPath. Returns notes."
  [path {:keys [hooks exists? snap fingerprint hooks-path]}]
  (let [now-exists (fs/exists? hooks)
        notes      (atom [])]
    (cond
      (and (not exists?) now-exists)
      (do (fs/delete-tree hooks) (swap! notes conj "removed hooks dir created by the build"))

      (and exists? (or (not now-exists) (not= fingerprint (dir-fingerprint hooks))))
      (do (fs/delete-tree hooks)
          (fs/copy-tree snap hooks {:copy-attributes true :nofollow-links true})
          (swap! notes conj "restored git hooks changed by the build"))

      :else nil)
    (let [now (git-maybe path "config" "--local" "--get" "core.hooksPath")]
      (when (not= now hooks-path)
        (if hooks-path
          (git-out! path "config" "--local" "core.hooksPath" hooks-path)
          (git-out! path "config" "--local" "--unset" "core.hooksPath"))
        (swap! notes conj "restored core.hooksPath changed by the build")))
    (fs/delete-tree snap)
    @notes))

(defn add-gc-roots! [state project wt log res]
  (let [dir   (fs/path state "gcroots" project)
        gc    (fs/path wt ".devenv" "gc")
        links (concat (when (fs/directory? gc) (fs/list-dir gc))
                      [(fs/path wt ".devenv" "profile")])]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (doseq [link links :when (fs/exists? link)]
      (let [target (str (fs/real-path link))]
        (if (str/starts-with? target "/nix/store/")
          (let [r (exec! {:log log} "nix-store" "--add-root" (str (fs/path dir (str (fs/file-name link))))
                         "--indirect" "--realise" target)]
            (when-not (ok? r) (warn! res (str "gc root for " (fs/file-name link) " failed: " (describe-failure r)))))
          (warn! res (str "gc root skipped, " link " resolves outside /nix/store: " target)))))))

(defn- devenv-update! [wt log res input & extra]
  (let [r (apply exec! {:dir wt :log log} "devenv" "update" input extra)]
    (when-not (ok? r)
      (halt! {:status :failed :reason :update
              :tail (str "devenv update " input " exited " (:exit r) "\n" (log-tail log))}))))

(defn- align-in-worktree!
  "Steps 12–17 inside the worktree. Returns the final status map."
  [{:keys [rev state log-dir build-timeout-min]} {:keys [name]} wt scope res]
  (let [log (str (fs/path log-dir (str name ".log")))]
    (swap! res assoc :log log)
    ;; 12. update
    (devenv-update! wt log res "nixpkgs")
    (when (not= rev (lock-rev (read-lock-file wt) "nixpkgs"))
      (devenv-update! wt log res "nixpkgs" "--override-input" "nixpkgs" (str "github:cachix/devenv-nixpkgs/" rev))
      (let [got (lock-rev (read-lock-file wt) "nixpkgs")]
        (when (not= rev got)
          (halt! {:status :failed :reason :update
                  :tail (str "nixpkgs locked at " got " after override, expected " rev "\n" (log-tail log))})))
      (note! res "override used"))
    (swap! res assoc :rev-date (epoch->date (lock-last-modified (read-lock-file wt) "nixpkgs")))
    (doseq [input (remove #{"nixpkgs"} scope)]
      (devenv-update! wt log res input))
    ;; 13. no-op?
    (if (ok? (git! wt "diff" "--quiet" "--" "devenv.lock"))
      (do (fs/delete-tree (fs/path state "gcroots" name))
          {:status :noop})
      (do
        ;; 14. build
        (let [r (exec! {:dir wt :log log :timeout-ms (* 60000 build-timeout-min)} "devenv" "shell" "--" "true")]
          (when-not (ok? r)
            (halt! {:status :failed :reason :build
                    :tail (str (if (:timed-out? r)
                                 (str "build timed out after " build-timeout-min " min")
                                 (str "devenv shell exited " (:exit r)))
                               "\n" (log-tail log))})))
        ;; 15. drift / scope guard
        (let [lock  (read-lock-file wt)
              after (lock-versions lock)]
          (when (not= rev (:nixpkgs after))
            (halt! {:status :failed :reason :lock-drift
                    :tail (str "nixpkgs re-locked to " (:nixpkgs after) " during build, expected " rev)}))
          (swap! res assoc :after after)
          (let [extra (->> (str/split-lines (git-out! wt "status" "--porcelain" "--untracked-files=all"))
                           (remove str/blank?)
                           (remove #(= " M devenv.lock" %)))]
            (when (seq extra)
              (warn! res (str "worktree had other changes (not committed): "
                              (str/join ", " (take 5 extra))
                              (when (> (count extra) 5) (str " … (+" (- (count extra) 5) ")"))))))
          ;; 16. gc roots
          (add-gc-roots! state name wt log res)
          ;; 17. commit
          (let [before (:before @res)
                msg    (commit-message {:rev rev :rev-date (:rev-date @res) :before before :after after})
                tmp    (str (fs/create-temp-file {:prefix "align-nixpkgs-msg-"}))]
            (try
              (spit tmp msg)
              (let [add (git! wt "add" "--" "devenv.lock")
                    c   (when (ok? add) (git! wt "commit" "--no-verify" "-F" tmp))]
                (when-not (and (ok? add) (ok? c))
                  (halt! {:status :failed :reason :commit :tail (describe-failure (if (ok? add) c add))})))
              (finally (fs/delete-if-exists tmp)))
            (doseq [k [:devenv :metadev]
                    :when (and (get after k) (not= (get before k) (get after k)))]
              (note! res (str (clojure.core/name k) " updated")))
            {:status :aligned
             :tip    (git-out! wt "rev-parse" "--short=7" "HEAD")}))))))

(defn- run-project!
  [{:keys [rev devenv-head dry-run? state min-free-gb metadev] :as ctx} {:keys [name path] :as proj} res]
  ;; 1. git toplevel
  (let [top (git-maybe path "rev-parse" "--show-toplevel")]
    (when-not (and top (= (canon top) path))
      (halt! {:status :skipped :reason :not-git})))
  ;; 2. base branch
  (let [base (or (base-branch-of! path) (halt! {:status :skipped :reason :no-base-branch}))
        _    (swap! res assoc :base base)
        ;; 3. read from base
        yaml-s (git-maybe path "show" (str base ":devenv.yaml"))
        lock-s (git-maybe path "show" (str base ":devenv.lock"))
        _      (when-not (and yaml-s lock-s) (halt! {:status :skipped :reason :lock-untracked}))
        y      (yaml/parse-string yaml-s)
        lock   (json/parse-string lock-s true)
        ;; 4/5. classify
        c      (classify-yaml y)]
    (case (:status c)
      :skipped     (halt! (cond-> (select-keys c [:status :reason])
                            (:input c) (assoc :notes [(str "input " (:input c))])))
      :nonstandard (halt! {:status :nonstandard :notes [(str "url " (:url c))]})
      :ok          nil)
    (swap! res assoc :before (lock-versions lock))
    ;; 6. scope
    (let [plan  (metadev-plan (:metadev-kind c) metadev)
          _     (doseq [w (:warnings plan)] (warn! res w))
          scope (cond->> (:in-scope c) (not (:update? plan)) (remove #{"metadev"}))
          wt    (str (fs/path path worktree-rel))
          ;; 7. branch decision
          facts (add-ancestry-facts! (branch-facts! path wt) path base)
          d     (branch-decision facts)]
      (case (:decision d)
        :skip-checked-out (halt! {:status :skipped :reason :branch-checked-out
                                  :notes [(str "checked out at " (:where d))]})
        :skip-foreign     (halt! {:status :skipped :reason :foreign-commits})
        nil)
      (when (:replaced d) (note! res (str "replaced previous unmerged proposal " (:replaced d))))
      (if dry-run?
        ;; 8. dry run stops here
        (let [o (dry-run-outcome {:nixpkgs-before (get-in @res [:before :nixpkgs])
                                  :rev rev
                                  :devenv (when (some #{"devenv"} scope)
                                            {:before   (get-in @res [:before :devenv])
                                             :head     devenv-head
                                             :default? (= devenv-default-url (:devenv-url c))})})]
          (swap! res update :notes #(vec (concat % (:notes o)
                                                 (when (some #{"metadev"} scope) ["metadev: resolved at run time"])
                                                 [(str "branch: " (clojure.core/name (:decision d)))])))
          {:status (:status o)})
        (do
          ;; 9. disk
          (when (< (free-bytes) (* min-free-gb 1e9))
            (halt! {:status :skipped :reason :low-disk}))
          ;; 10. worktree
          (when (= :reap-and-recreate (:decision d))
            (git-out! path "worktree" "remove" "--force" wt)
            (git! path "worktree" "prune")
            (note! res "reaped stale worktree"))
          (ensure-worktrees-ignored! path)
          (when (or (fs/exists? wt) (fs/sym-link? wt))
            (halt! {:status :failed :reason :worktree-path-occupied :tail (str wt " exists and is not a registered worktree")}))
          (git-out! path "worktree" "add" "-B" branch-name "--" wt base)
          ;; 11. try/finally from here
          (let [hooks (try (snapshot-hooks! state name path)
                           (catch Exception e
                             (warn! res (str "could not snapshot hooks: " (ex-message e)))
                             nil))]
            (try
              (align-in-worktree! ctx proj wt scope res)
              (finally
                ;; 18. cleanup — errors become warnings
                (when hooks
                  (try (doseq [n (restore-hooks! path hooks)] (warn! res n))
                       (catch Exception e (warn! res (str "hook restore failed: " (ex-message e))))))
                (let [r (git! path "worktree" "remove" "--force" wt)]
                  (when-not (ok? r) (warn! res (str "worktree remove failed: " (describe-failure r)))))
                (git! path "worktree" "prune")))))))))

(defn process-project! [ctx {:keys [name path] :as proj}]
  (let [res (atom {:project name :path path :warnings [] :notes []})]
    (try
      ;; run first, then deref: run-project! accumulates :base/:before/:notes/:warnings in res
      (let [final (run-project! ctx proj res)]
        (merge @res final))
      (catch clojure.lang.ExceptionInfo e
        (if-let [h (::halt (ex-data e))]
          (merge @res h (when (:notes h) {:notes (vec (concat (:notes @res) (:notes h)))}))
          (merge @res {:status :failed :reason :exception :tail (ex-message e)})))
      (catch Exception e
        (merge @res {:status :failed :reason :exception :tail (str e)})))))

;; ---------------------------------------------------------------------------
;; Subcommands
;; ---------------------------------------------------------------------------

(def run-spec
  {:dry-run           {:coerce :boolean :desc "Predict only; write nothing"}
   :only              {:coerce [] :desc "Only this project (repeatable)"}
   :exclude           {:coerce [] :desc "Skip this project (repeatable)"}
   :projects-dir      {:desc "Directory holding projects (default ~/projects)"}
   :gc                {:coerce :boolean :desc "Run nix-collect-garbage afterwards"}
   :no-notify         {:coerce :boolean :desc "Do not call notify-send"}
   :min-free-gb       {:coerce :long :default 4 :desc "Skip builds below this much free /nix space"}
   :build-timeout-min {:coerce :long :default 45 :desc "Per-project devenv shell build timeout"}})

;; babashka.cli turns --no-notify into {:notify false}; accept both spellings.
(def allowed-run-keys (conj (set (keys run-spec)) :notify))

(defn parse-run-args
  "Parse `run` arguments. Returns {:opts {...}} or {:error msg}. :notify is false when --no-notify given."
  [args]
  (try
    (let [{:keys [opts args]} (cli/parse-args (vec args) {:spec run-spec})
          bad (remove allowed-run-keys (keys opts))]
      (cond
        (seq args) {:error (str "unexpected arguments: " (str/join " " args))}
        (seq bad)  {:error (str "unknown option(s): " (str/join " " (map #(str "--" (name %)) bad)))}
        :else      {:opts (-> opts
                              (assoc :notify (not (or (:no-notify opts) (false? (:notify opts)))))
                              (dissoc :no-notify))}))
    (catch clojure.lang.ExceptionInfo e
      {:error (str "bad arguments: " (ex-message e))})))

(defn- usage []
  (println "usage: bb tools/align-nixpkgs/align.bb <run|install-timer|uninstall-timer|status> [options]")
  (println)
  (println "run options:")
  (println (cli/format-opts {:spec run-spec})))

(defn- expand-home [s]
  (if (str/starts-with? s "~") (str (fs/home) (subs s 1)) s))

(defn- write-report! [state date text]
  (let [existing (set (map #(str (fs/file-name %)) (fs/list-dir state)))
        file     (fs/path state (report-file-name date existing))
        latest   (fs/path state "latest.md")]
    (spit (str file) text)
    (fs/delete-if-exists latest)
    (fs/create-sym-link latest (fs/file-name file))
    (str file)))

(defn run-cmd! [opts]
  (let [dry?         (boolean (:dry-run opts))
        notify?      (:notify opts)
        projects-dir (canon (expand-home (or (:projects-dir opts) (str (fs/path (fs/home) "projects")))))
        all          (order-projects (discover-projects projects-dir) metadev-root)
        {:keys [selected excluded unknown]} (select-projects all (:only opts) (:exclude opts))]
    (cond
      (seq unknown)
      (do (log! "unknown project name(s):" (str/join ", " unknown)
                "— known:" (str/join ", " (map :name all)))
          1)

      :else
      (let [state   (state-dir)
            date    (str (LocalDate/now))
            log-dir (str (fs/path state "logs" date))
            held    (when-not dry?
                      (fs/create-dirs log-dir)
                      (or (try-lock! state)
                          ::busy))]
        (if (= ::busy held)
          (do (log! "another run is active (lock held on" (str (fs/path state "run.lock")) ")") 75)
          (try
            (let [rev (ls-remote-sha! nixpkgs-remote "refs/heads/rolling")]
              (if-not rev
                (do (log! "fatal: could not resolve" nixpkgs-remote "refs/heads/rolling") 1)
                (let [devenv-head (ls-remote-sha! devenv-remote "HEAD")
                      warnings    (cond-> []
                                    (nil? devenv-head) (conj "could not resolve cachix/devenv HEAD"))
                      dry-date    (when dry? (epoch->date (flake-last-modified! (str "github:cachix/devenv-nixpkgs/" rev))))
                      md          (metadev-state!)
                      ctx         {:rev rev :devenv-head devenv-head :dry-run? dry? :state state
                                   :log-dir log-dir :metadev md
                                   :min-free-gb (:min-free-gb opts) :build-timeout-min (:build-timeout-min opts)}
                      disk-start  (free-bytes)
                      _           (log! (str "align-nixpkgs " (if dry? "dry-run" "run") ": rolling @ " (short-sha rev)
                                             ", " (count selected) " project(s) in " projects-dir))
                      results     (into (mapv (fn [p] {:project (:name p) :path (:path p) :status :skipped :reason :excluded})
                                              excluded)
                                        (for [p selected]
                                          (do (log! (str "==> " (:name p)))
                                              (let [r (process-project! ctx p)]
                                                (log! (str "    " (status-label r)
                                                           (when (seq (:notes r)) (str " — " (str/join "; " (:notes r))))))
                                                r))))
                      results     (vec (sort-by #(.indexOf ^java.util.List (mapv :name all) (:project %)) results))
                      gc-before   (free-bytes)
                      gc-ran?     (when (and (:gc opts) (not dry?))
                                    (log! "==> nix-collect-garbage")
                                    (let [r (exec! {:log (str (fs/path log-dir "gc.log"))} "nix-collect-garbage")]
                                      (if (ok? r) true (do (log! "    gc failed:" (describe-failure r)) false))))
                      disk-end    (free-bytes)
                      warnings    (cond-> warnings
                                    (false? gc-ran?) (conj (str "nix-collect-garbage failed; see " log-dir "/gc.log")))
                      report      (render-report
                                   {:date date :mode (if dry? :dry-run :run)
                                    :reference {:rev rev :devenv-head devenv-head :metadev md
                                                :rev-date (or dry-date (some :rev-date results))}
                                    :disk (cond-> {:start disk-start}
                                            (not dry?) (assoc :end disk-end)
                                            gc-ran?    (assoc :gc-freed (- disk-end gc-before)))
                                    :results results
                                    :warnings warnings})
                      failed?     (some #(= :failed (:status %)) results)]
                  (println)
                  (println report)
                  (when-not dry?
                    (let [f (write-report! state date report)]
                      (log! "report:" f)
                      (when (and notify? (fs/which "notify-send"))
                        (let [aligned (count (filter #(= :aligned (:status %)) results))
                              failed  (count (filter #(= :failed (:status %)) results))]
                          (exec! {} "notify-send" "-u" (if failed? "critical" "normal")
                                 "metadev align-nixpkgs"
                                 (str aligned " aligned, " failed " failed — " f))))))
                  (if failed? 2 0))))
            (finally
              (when (map? held) (release-lock! held)))))))))

(defn- unit-dir [] (fs/path (fs/home) ".config" "systemd" "user"))

(defn- run-loud! [& cmd]
  (let [r (apply exec! {} cmd)]
    (when-not (str/blank? (:out r)) (println (str/trim (:out r))))
    (when-not (str/blank? (:err r)) (binding [*out* *err*] (println (str/trim (:err r)))))
    r))

(defn install-timer! []
  (let [home      (str (fs/home))
        cands     [(str home "/.nix-profile/bin/devenv")
                   (str home "/.local/state/nix/profile/bin/devenv")
                   "/nix/var/nix/profiles/default/bin/devenv"]
        existing  (filterv fs/executable? cands)
        which     (some-> (fs/which "devenv") str)
        devenv    (choose-devenv existing which)]
    (if-not devenv
      (do (log! "refusing to install: no stable devenv path found (checked" (str/join ", " cands)
                "; which devenv ->" (or which "nothing") "). Install devenv into a profile first.")
          1)
      (let [dir     (unit-dir)
            svc     (fs/path dir (str unit-name ".service"))
            timer   (fs/path dir (str unit-name ".timer"))
            content (render-template (slurp (str (fs/path tool-dir "systemd" (str unit-name ".service.in"))))
                                     {"DEVENV" devenv "METADEV" metadev-root "PATH" (unit-path devenv home)})]
        (fs/create-dirs dir)
        (spit (str svc) content)
        (fs/copy (fs/path tool-dir "systemd" (str unit-name ".timer")) timer {:replace-existing true})
        (log! "wrote" (str svc) "and" (str timer) "(devenv:" devenv ")")
        (let [v (run-loud! "systemd-analyze" "--user" "verify" (str svc) (str timer))]
          (if-not (ok? v)
            (do (log! "systemd-analyze verify failed; timer not enabled") 1)
            (let [steps [["systemctl" "--user" "daemon-reload"]
                         ["systemctl" "--user" "enable" "--now" (str unit-name ".timer")]]
                  bad   (some #(let [r (apply run-loud! %)] (when-not (ok? r) r)) steps)]
              (if bad
                (do (log! "failed:" (describe-failure bad)) 1)
                (do (run-loud! "systemctl" "--user" "list-timers" "--no-pager" (str unit-name ".timer"))
                    0)))))))))

(defn uninstall-timer! []
  (let [dir (unit-dir)]
    (run-loud! "systemctl" "--user" "disable" "--now" (str unit-name ".timer"))
    (fs/delete-if-exists (fs/path dir (str unit-name ".timer")))
    (fs/delete-if-exists (fs/path dir (str unit-name ".service")))
    (let [r (run-loud! "systemctl" "--user" "daemon-reload")]
      (log! "timer uninstalled")
      (if (ok? r) 0 1))))

(defn status! []
  (run-loud! "systemctl" "--user" "list-timers" "--all" "--no-pager" (str unit-name ".timer"))
  (let [latest (fs/path (state-dir) "latest.md")]
    (log! "latest report:" (if (fs/exists? latest) (str (fs/real-path latest)) "none yet")))
  0)

(defn -main [& args]
  (let [[cmd & more] args
        code (case cmd
               "run"
               (let [{:keys [opts error]} (parse-run-args more)]
                 (if error
                   (do (log! error) (usage) 1)
                   (run-cmd! opts)))
               "install-timer"   (install-timer!)
               "uninstall-timer" (uninstall-timer!)
               "status"          (status!)
               (do (usage) 1))]
    (System/exit code)))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
