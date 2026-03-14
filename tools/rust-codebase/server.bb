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

(defn format-diagnostic
  "Format a single compiler diagnostic message for human reading."
  [{:keys [reason message]}]
  (when (= reason "compiler-message")
    (let [msg      (:message message)
          level    (:level msg)
          text     (:message msg)
          rendered (:rendered msg)]
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
  (let [path   (effective-path arguments)
        result (run-cmd path "cargo" "check" "--message-format=json")]
    (if (:ok result)
      (let [messages (parse-cargo-json-messages (:out result))
            counts   (count-diagnostic-levels messages)
            diags    (format-diagnostics messages)]
        (str "cargo check: OK\n"
             (when (seq counts) (str "Counts: " (pr-str counts) "\n"))
             diags))
      (let [messages (parse-cargo-json-messages (:out result))
            diags    (format-diagnostics messages)]
        (str "cargo check: FAILED (exit " (:exit result) ")\n"
             (if (str/blank? diags)
               (:err result)
               diags))))))

(defn cargo-clippy [arguments]
  (let [path   (effective-path arguments)
        result (run-cmd path "cargo" "clippy" "--message-format=json")]
    (let [messages (parse-cargo-json-messages (:out result))
          counts   (count-diagnostic-levels messages)
          diags    (format-diagnostics messages)
          status   (if (:ok result) "OK" (str "FAILED (exit " (:exit result) ")"))]
      (str "cargo clippy: " status "\n"
           (when (seq counts) (str "Counts: " (pr-str counts) "\n"))
           diags))))

(defn cargo-metadata [arguments]
  (let [path   (effective-path arguments)
        result (run-cmd path "cargo" "metadata" "--format-version=1" "--no-deps")]
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
  (let [path   (effective-path arguments)
        result (run-cmd path "cargo" "tree")]
    (if (:ok result)
      (if (str/blank? (:out result))
        "(no output)"
        (:out result))
      (str "Error: " (:err result)))))

(defn cargo-test [arguments]
  (let [path   (effective-path arguments)
        result (run-cmd path "cargo" "test" "2>&1")]
    ;; cargo test doesn't support --message-format=json in stable, capture combined output
    (let [combined (str (:out result)
                        (when-not (str/blank? (:err result))
                          (str "\n" (:err result))))
          lines    (str/split-lines combined)
          ;; Try to extract test result summary lines
          summary  (->> lines
                        (filter #(or (str/includes? % "test result:")
                                     (str/includes? % "FAILED")
                                     (str/includes? % "ok")
                                     (str/starts-with? % "test ")))
                        (take 200))
          status   (if (:ok result) "PASSED" "FAILED")]
      (str "cargo test: " status "\n\n"
           (if (empty? summary)
             combined
             (str/join "\n" summary))))))

;; ---------------------------------------------------------------------------
;; Hygiene tools
;; ---------------------------------------------------------------------------

(defn git-changed-files [path]
  (let [result (run-cmd path "git" "diff" "--name-only")]
    (if (:ok result)
      (into #{} (filter #(not (str/blank? %)) (str/split-lines (:out result))))
      #{})))

(defn clippy-new-warnings [arguments]
  (let [path         (effective-path arguments)
        changed-files (git-changed-files path)
        result        (run-cmd path "cargo" "clippy" "--message-format=json")]
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
  (let [path         (effective-path arguments)
        changed-files (git-changed-files path)]
    ;; Try cargo llvm-cov first, fall back to cargo tarpaulin, then give up gracefully
    (let [llvm-cov-check (run-cmd path "cargo" "llvm-cov" "--version")]
      (cond
        ;; llvm-cov available
        (:ok llvm-cov-check)
        (let [result (run-cmd path "cargo" "llvm-cov" "--json" "--summary-only")]
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
            (let [result (run-cmd path "cargo" "tarpaulin" "--out" "Json" "--output-dir" "/tmp/tarpaulin-mcp")]
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
    :description "Run `cargo check` and return structured errors and warnings. Accepts an optional path to the Cargo project or workspace root."
    :inputSchema {:type       "object"
                  :properties {"path" {:type        "string"
                                       :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}}
                  :required   []}}

   {:name        "cargo_clippy"
    :description "Run `cargo clippy` and return structured diagnostics. Accepts an optional path to the Cargo project or workspace root."
    :inputSchema {:type       "object"
                  :properties {"path" {:type        "string"
                                       :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}}
                  :required   []}}

   {:name        "cargo_metadata"
    :description "Run `cargo metadata --no-deps` and return workspace structure: member crates, versions, manifest paths, and target kinds."
    :inputSchema {:type       "object"
                  :properties {"path" {:type        "string"
                                       :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}}
                  :required   []}}

   {:name        "cargo_tree"
    :description "Run `cargo tree` and return the full dependency tree as text."
    :inputSchema {:type       "object"
                  :properties {"path" {:type        "string"
                                       :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}}
                  :required   []}}

   {:name        "cargo_test"
    :description "Run `cargo test` and return test results with pass/fail counts and failure details."
    :inputSchema {:type       "object"
                  :properties {"path" {:type        "string"
                                       :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}}
                  :required   []}}

   {:name        "clippy_new_warnings"
    :description "Detect only new clippy warnings introduced by current (uncommitted) changes. Uses `git diff --name-only` to identify changed files, then filters clippy output to those files only. Returns 'clean' or a list of new warnings."
    :inputSchema {:type       "object"
                  :properties {"path" {:type        "string"
                                       :description "Absolute path to the Cargo project or workspace root (must be a git repo). Defaults to current directory."}}
                  :required   []}}

   {:name        "test_coverage_check"
    :description "Check test coverage for files touched by current git changes. Uses cargo-llvm-cov if installed, falls back to cargo-tarpaulin, or returns a graceful message if neither is available."
    :inputSchema {:type       "object"
                  :properties {"path" {:type        "string"
                                       :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}}
                  :required   []}}

   {:name        "hygiene_report"
    :description "Convenience wrapper: runs cargo_test, clippy_new_warnings, and optionally test_coverage_check. Returns a structured pass/fail summary with details from all three."
    :inputSchema {:type       "object"
                  :properties {"path"          {:type        "string"
                                                :description "Absolute path to the Cargo project or workspace root. Defaults to current directory."}
                               "skip_coverage" {:type        "string"
                                                :enum        ["true" "false"]
                                                :description "Set to 'true' to skip the coverage check (useful when no coverage tool is installed). Defaults to false."}}
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
