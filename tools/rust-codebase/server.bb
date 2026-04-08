#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; rust-codebase MCP server
;; Exploration and hygiene tools for Rust/Cargo projects.
;; All tools accept an optional `path` parameter (defaults to current directory).

(defn effective-path [arguments]
  (let [path (:path arguments)]
    (if (or (nil? path) (str/blank? path))
      (System/getProperty "user.dir")
      path)))

(defn effective-profile [arguments]
  (let [profile (:profile arguments)]
    (if (or (nil? profile) (str/blank? profile))
      "dev"
      profile)))

(defn polylith?
  "Returns true when Polylith.toml exists at path — indicates a polylith workspace."
  [path]
  (.exists (java.io.File. path "Polylith.toml")))

(defn cargo-cmd
  "Returns a command vector for a cargo subcommand.
   In polylith workspaces wraps as: cargo polylith cargo --profile <profile> <sub> <args...>
   In regular workspaces passes through: cargo <sub> <args...>"
  [path profile subcommand & args]
  (if (polylith? path)
    (apply vector "cargo" "polylith" "cargo" "--profile" profile subcommand args)
    (apply vector "cargo" subcommand args)))

(defn run-cmd
  "Run a shell command in the given directory. Returns {:ok bool :out string :err string :exit int}."
  [dir & args]
  (let [result (apply p/shell {:out :string :err :string :dir dir :continue true} args)]
    {:ok   (zero? (:exit result))
     :out  (str/trim (:out result))
     :err  (str/trim (:err result))
     :exit (:exit result)}))

;; ---------------------------------------------------------------------------
;; Structured output helpers
;; ---------------------------------------------------------------------------

(defn parse-cargo-json-messages
  "Parse newline-delimited JSON from cargo --message-format=json output.
   Returns a sequence of maps."
  [text]
  (->> (str/split-lines text)
       (keep (fn [line]
               (when-not (str/blank? line)
                 (try (json/parse-string line true)
                      (catch Exception _ nil)))))))

(defn non-json-stdout
  "Return non-JSON, non-blank lines from cargo stdout — raw rustc/linker errors."
  [text]
  (->> (str/split-lines text)
       (remove str/blank?)
       (remove (fn [line]
                 (try (json/parse-string line) true
                      (catch Exception _ false))))
       (str/join "\n")))

(defn format-diagnostic
  "Format a single compiler diagnostic message for human reading."
  [{:keys [reason message]}]
  (when (= reason "compiler-message")
    (let [level    (:level message)
          text     (:message message)
          rendered (:rendered message)]
      (or rendered (str "[" level "] " text)))))

(defn format-diagnostics [messages]
  (let [diags (->> messages
                   (keep format-diagnostic)
                   (distinct))]
    (if (empty? diags)
      "No issues found."
      (str/join "\n\n" diags))))

(defn count-diagnostic-levels [messages]
  (reduce (fn [acc msg]
            (when (= (:reason msg) "compiler-message")
              (let [level (get-in msg [:message :level])]
                (update acc level (fnil inc 0))))
            acc)
          {}
          messages))

;; ---------------------------------------------------------------------------
;; Tool implementations
;; ---------------------------------------------------------------------------

(defn cargo-check [arguments]
  (let [path    (effective-path arguments)
        profile (effective-profile arguments)
        result  (apply run-cmd path (cargo-cmd path profile "check" "--message-format=json"))]
    (if (:ok result)
      (let [messages (parse-cargo-json-messages (:out result))
            counts   (count-diagnostic-levels messages)
            diags    (format-diagnostics messages)]
        (str "cargo check: OK\n"
             (when (seq counts) (str "Counts: " (pr-str counts) "\n"))
             diags))
      (let [messages      (parse-cargo-json-messages (:out result))
            compiler-msgs (seq (keep format-diagnostic messages))
            diags         (format-diagnostics messages)]
        (str "cargo check: FAILED (exit " (:exit result) ")\n"
             (if compiler-msgs
               diags
               (let [raw (str/join "\n" (filter not-empty [(non-json-stdout (:out result))
                                                            (:err result)]))]
                 (or (not-empty raw) "No output captured."))))))))

(defn cargo-clippy [arguments]
  ;; Clippy lints the whole workspace regardless of polylith profiles — bypass polylith routing.
  (let [path   (effective-path arguments)
        result (run-cmd path "cargo" "clippy" "--workspace" "--message-format=json" "--" "-D" "warnings")]
    (let [messages      (parse-cargo-json-messages (:out result))
          compiler-msgs (seq (keep format-diagnostic messages))
          counts        (count-diagnostic-levels messages)
          diags         (format-diagnostics messages)
          status        (if (:ok result) "OK" (str "FAILED (exit " (:exit result) ")"))]
      (str "cargo clippy: " status "\n"
           (when (seq counts) (str "Counts: " (pr-str counts) "\n"))
           (if (or (:ok result) compiler-msgs)
             diags
             (let [raw (str/join "\n" (filter not-empty [(non-json-stdout (:out result))
                                                          (:err result)]))]
               (or (not-empty raw) "No output captured.")))))))

(defn cargo-metadata [arguments]
  (let [path    (effective-path arguments)
        profile (effective-profile arguments)
        result  (apply run-cmd path (cargo-cmd path profile "metadata" "--format-version=1" "--no-deps"))]
    (if (:ok result)
      (let [meta      (json/parse-string (:out result) true)
            workspace (:workspace_root meta)
            members   (->> (:packages meta)
                           (map (fn [pkg]
                                  {:name    (:name pkg)
                                   :version (:version pkg)
                                   :path    (:manifest_path pkg)
                                   :targets (mapv :kind (:targets pkg))})))]
        (str "Workspace root: " workspace "\n\n"
             "Members (" (count members) "):\n"
             (str/join "\n" (map (fn [{:keys [name version path targets]}]
                                   (str "  " name "@" version
                                        " [" (str/join "," (flatten targets)) "]"
                                        "\n    " path))
                                 members))))
      (str "Error: " (:err result)))))

(defn cargo-tree [arguments]
  (let [path    (effective-path arguments)
        profile (effective-profile arguments)
        result  (apply run-cmd path (cargo-cmd path profile "tree"))]
    (if (:ok result)
      (if (str/blank? (:out result))
        "(no output)"
        (:out result))
      (str "Error: " (:err result)))))

(defn cargo-test [arguments]
  (let [path    (effective-path arguments)
        profile (effective-profile arguments)
        result  (apply run-cmd path (cargo-cmd path profile "test" "--no-fail-fast"))]
    ;; cargo test doesn't support --message-format=json in stable, so we filter text output.
    ;; Only keep: summary lines, failure names, failure details, and runner headers.
    (let [combined (str (:out result)
                        (when-not (str/blank? (:err result))
                          (str "\n" (:err result))))
          lines    (str/split-lines combined)
          summaries (->> lines
                         (filter #(str/includes? % "test result:")))
          failed-tests (->> lines
                            (filter #(and (str/starts-with? % "test ")
                                         (str/ends-with? % "FAILED"))))
          runner-headers (->> lines
                              (filter #(str/starts-with? % "     Running ")))
          in-failure-block (volatile! false)
          failure-details  (persistent!
                            (reduce (fn [acc line]
                                      (cond
                                        (str/starts-with? line "---- ")
                                        (do (vreset! in-failure-block true)
                                            (conj! acc line))

                                        (and @in-failure-block
                                             (or (str/starts-with? line "failures:")
                                                 (str/includes? line "test result:")))
                                        (do (vreset! in-failure-block false)
                                            (conj! acc line))

                                        @in-failure-block
                                        (conj! acc line)

                                        :else acc))
                                    (transient [])
                                    lines))
          status   (if (:ok result) "PASSED" "FAILED")]
      (str "cargo test: " status "\n\n"
           (when (seq runner-headers)
             (str (str/join "\n" runner-headers) "\n\n"))
           (str/join "\n" summaries)
           (when (seq failed-tests)
             (str "\n\nFailed tests:\n" (str/join "\n" failed-tests)))
           (when (seq failure-details)
             (str "\n\nFailure details:\n" (str/join "\n" failure-details)))
           (when (and (empty? summaries) (empty? failed-tests))
             (str "\n(No test output captured)\n" combined))))))

;; ---------------------------------------------------------------------------
;; Hygiene tools
;; ---------------------------------------------------------------------------

(defn git-changed-files [path]
  (let [result (run-cmd path "git" "diff" "--name-only")]
    (if (:ok result)
      (into #{} (filter #(not (str/blank? %)) (str/split-lines (:out result))))
      #{})))

(defn clippy-new-warnings [arguments]
  (let [path          (effective-path arguments)
        profile       (effective-profile arguments)
        changed-files (git-changed-files path)
        result        (run-cmd path "cargo" "clippy" "--workspace" "--message-format=json" "--" "-D" "warnings")]
    (if (empty? changed-files)
      "No changed files detected by git diff. Run on a branch with uncommitted changes."
      (let [messages  (parse-cargo-json-messages (:out result))
            new-diags (->> messages
                           (filter (fn [msg]
                                     (when (= (:reason msg) "compiler-message")
                                       (let [spans (get-in msg [:message :spans])]
                                         (some (fn [span]
                                                 (let [file (:file_name span)]
                                                   (some #(str/ends-with? file %) changed-files)))
                                               spans)))))
                           (keep format-diagnostic)
                           (distinct))]
        (if (empty? new-diags)
          (str "Clean — no new clippy warnings in changed files.\nChanged files: "
               (str/join ", " changed-files))
          (str "New warnings in changed files (" (count new-diags) "):\n"
               "Changed files: " (str/join ", " changed-files) "\n\n"
               (str/join "\n\n" new-diags)))))))

(defn test-coverage-check [arguments]
  (let [path          (effective-path arguments)
        profile       (effective-profile arguments)
        changed-files (git-changed-files path)]
    ;; Try cargo llvm-cov first, fall back to cargo tarpaulin, then give up gracefully
    (let [llvm-cov-check (run-cmd path "cargo" "llvm-cov" "--version")]
      (cond
        ;; llvm-cov available
        (:ok llvm-cov-check)
        (let [result (apply run-cmd path (cargo-cmd path profile "llvm-cov" "--json" "--summary-only"))]
          (if (:ok result)
            (let [cov-data (try (json/parse-string (:out result) true) (catch Exception _ nil))]
              (if cov-data
                (let [totals (get-in cov-data [:data 0 :totals])
                      lines  (:lines totals)
                      pct    (get lines :percent 0)]
                  (str "Coverage (llvm-cov): " (format "%.1f" pct) "%\n"
                       (if (empty? changed-files)
                         "No changed files detected by git diff."
                         (str "Changed files: " (str/join ", " changed-files)
                              "\n(Note: llvm-cov --summary-only reports workspace totals; "
                              "per-file filtering requires --json without --summary-only)"))))
                (str "llvm-cov output could not be parsed.\nRaw: " (:out result))))
            (str "cargo llvm-cov failed: " (:err result))))

        ;; Try tarpaulin
        :else
        (let [tarp-check (run-cmd path "cargo" "tarpaulin" "--version")]
          (if (:ok tarp-check)
            (let [result (apply run-cmd path (cargo-cmd path profile "tarpaulin" "--out" "Json" "--output-dir" "/tmp/tarpaulin-mcp"))]
              (if (:ok result)
                (let [json-files (run-cmd "/tmp/tarpaulin-mcp" "ls")]
                  (str "tarpaulin ran. Output in /tmp/tarpaulin-mcp\n"
                       "Changed files: " (str/join ", " (or (seq changed-files) ["(none)"])) "\n"
                       (:out result)))
                (str "cargo tarpaulin failed: " (:err result))))
            "Neither cargo-llvm-cov nor cargo-tarpaulin is installed.\nInstall one with:\n  cargo install cargo-llvm-cov\n  cargo install cargo-tarpaulin"))))))

(defn hygiene-report [arguments]
  (let [path (effective-path arguments)
        skip-coverage (= "true" (str (:skip_coverage arguments)))]
    (str "=== Hygiene Report ===\n"
         "Path: " path "\n\n"

         "--- Tests ---\n"
         (cargo-test arguments)
         "\n\n"

         "--- Clippy (new warnings only) ---\n"
         (clippy-new-warnings arguments)
         "\n"

         (when-not skip-coverage
           (str "\n--- Coverage ---\n"
                (test-coverage-check arguments)
                "\n")))))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def tools
  [{:name        "cargo_check"
    :description "Polylith-aware. Run `cargo check` and return structured errors and warnings. Accepts an optional path to the Cargo project or workspace root."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type        "string"
                                          :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "profile" {:type        "string"
                                          :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}

   {:name        "cargo_clippy"
    :description "Polylith-aware. Run `cargo clippy -- -D warnings` and return structured diagnostics. Warnings are treated as errors, matching CI strictness. Accepts an optional path to the Cargo project or workspace root."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type        "string"
                                          :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "profile" {:type        "string"
                                          :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}

   {:name        "cargo_metadata"
    :description "Polylith-aware. Run `cargo metadata --no-deps` and return workspace structure: member crates, versions, manifest paths, and target kinds."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type        "string"
                                          :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "profile" {:type        "string"
                                          :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}

   {:name        "cargo_tree"
    :description "Polylith-aware. Run `cargo tree` and return the full dependency tree as text."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type        "string"
                                          :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "profile" {:type        "string"
                                          :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}

   {:name        "cargo_test"
    :description "Polylith-aware. Run `cargo test` and return test results with pass/fail counts and failure details."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type        "string"
                                          :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "profile" {:type        "string"
                                          :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}

   {:name        "clippy_new_warnings"
    :description "Polylith-aware. Detect only new clippy warnings introduced by current (uncommitted) changes. Uses `git diff --name-only` to identify changed files, then filters clippy output to those files only. Returns 'clean' or a list of new warnings."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type        "string"
                                          :description "Absolute path to the Cargo project or workspace root (must be a git repo). Defaults to current directory."}
                               "profile" {:type        "string"
                                          :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}

   {:name        "test_coverage_check"
    :description "Polylith-aware. Version probes run bare; actual coverage invocations are profile-routed. Check test coverage for files touched by current git changes. Uses cargo-llvm-cov if installed, falls back to cargo-tarpaulin, or returns a graceful message if neither is available."
    :inputSchema {:type       "object"
                  :properties {"path"    {:type        "string"
                                          :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "profile" {:type        "string"
                                          :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}

   {:name        "hygiene_report"
    :description "Polylith-aware. Convenience wrapper: runs cargo_test, clippy_new_warnings, and optionally test_coverage_check. Returns a structured pass/fail summary with details from all three."
    :inputSchema {:type       "object"
                  :properties {"path"          {:type        "string"
                                                :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "skip_coverage" {:type        "string"
                                                :enum        ["true" "false"]
                                                :description "Set to 'true' to skip the coverage check (useful when no coverage tool is installed). Defaults to false."}
                               "profile"       {:type        "string"
                                                :description "Polylith profile to use (e.g. 'dev', 'production'). Only applies in Polylith workspaces (Polylith.toml detected). Defaults to 'dev'. Ignored for non-Polylith projects."}}
                  :required   []}}])

;; ---------------------------------------------------------------------------
;; Tool dispatch
;; ---------------------------------------------------------------------------

(defn handle-tool-call [name arguments]
  (case name
    "cargo_check"           (cargo-check arguments)
    "cargo_clippy"          (cargo-clippy arguments)
    "cargo_metadata"        (cargo-metadata arguments)
    "cargo_tree"            (cargo-tree arguments)
    "cargo_test"            (cargo-test arguments)
    "clippy_new_warnings"   (clippy-new-warnings arguments)
    "test_coverage_check"   (test-coverage-check arguments)
    "hygiene_report"        (hygiene-report arguments)
    (str "Unknown tool: " name)))

;; ---------------------------------------------------------------------------
;; MCP protocol loop
;; ---------------------------------------------------------------------------

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
                       :result  {:protocolVersion "2024-11-05"
                                 :capabilities    {:tools {}}
                                 :serverInfo      {:name "rust-codebase" :version "0.1.0"}}}))

            "tools/list"
            (println (json/generate-string
                      {:jsonrpc "2.0" :id id
                       :result  {:tools tools}}))

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
                         :result  {:content [{:type "text" :text result}]}})))

            ;; notifications and unknown methods — no response needed
            nil))))
    (recur)))
