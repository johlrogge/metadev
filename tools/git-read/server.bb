#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

(defn run-git [path & args]
  (let [cmd (concat ["git" "-C" path] args)
        result (apply p/shell {:out :string :err :string} cmd)]
    (if (zero? (:exit result))
      (let [out (clojure.string/trim (:out result))]
        (if (empty? out) "(no output)" out))
      (str "Error: " (clojure.string/trim (:err result))))))

(defn handle-tool-call [name arguments]
  (let [path (:path arguments)]
    (when (nil? path)
      (throw (ex-info "Missing required parameter: path" {})))
    (case name
      "git_status"
      (run-git path "status" "--short")

      "git_diff"
      (let [args-str (:args arguments "")]
        (if (empty? args-str)
          (run-git path "diff")
          (apply run-git path "diff" (clojure.string/split args-str #"\s+"))))

      "git_log"
      (let [n   (:n arguments 10)
            ref (:ref arguments)]
        (if (clojure.string/blank? ref)
          (run-git path "log" "--oneline" (str "-" n))
          (run-git path "log" "--oneline" (str "-" n) ref)))

      "git_show"
      (let [ref (:ref arguments)]
        (when (nil? ref)
          (throw (ex-info "Missing required parameter: ref" {})))
        (run-git path "show" ref))

      "git_branch"
      (if (:all arguments)
        (run-git path "branch" "-a")
        (run-git path "branch"))

      (str "Unknown tool: " name))))

(def tools
  [{:name "git_status"
    :description "Show the working tree status (short format) for a git repository."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "Absolute path to the git repository"}}
                  :required ["path"]}}
   {:name "git_diff"
    :description "Show changes between commits, working tree, etc. Optionally pass extra args like '--staged', 'HEAD~1', or a commit hash."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "Absolute path to the git repository"}
                               "args" {:type "string"
                                       :description "Optional git diff arguments, e.g. '--staged', 'HEAD~1', 'main..feature'"}}
                  :required ["path"]}}
   {:name "git_log"
    :description "Show recent commit log in oneline format. Pass 'ref' to inspect a specific branch, tag, or range (e.g. 'develop', 'main..feature/x'); defaults to HEAD."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "Absolute path to the git repository"}
                               "n" {:type "number"
                                    :description "Number of commits to show (default: 10)"}
                               "ref" {:type "string"
                                      :description "Branch, tag, commit, or range to log. Defaults to HEAD."}}
                  :required ["path"]}}
   {:name "git_show"
    :description "Show a commit or object (patch + metadata). Pass a ref like 'HEAD', a commit hash, or a tag."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "Absolute path to the git repository"}
                               "ref" {:type "string"
                                      :description "Git ref to show, e.g. 'HEAD', a commit hash, or tag"}}
                  :required ["path" "ref"]}}
   {:name "git_branch"
    :description "List branches in a git repository. Local branches by default; pass all=true to include remote-tracking branches."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "Absolute path to the git repository"}
                               "all"  {:type "boolean"
                                       :description "Include remote-tracking branches (git branch -a). Defaults to false."}}
                  :required ["path"]}}])

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
                                :serverInfo {:name "git-read" :version "0.1.0"}}}))

            "tools/list"
            (println (json/generate-string
                      {:jsonrpc "2.0" :id id
                       :result {:tools tools}}))

            "tools/call"
            (let [params (:params req)
                  tool-name (:name params)
                  arguments (:arguments params)
                  result (try
                           (handle-tool-call tool-name arguments)
                           (catch Exception e
                             (str "Error: " (.getMessage e))))]
              (println (json/generate-string
                        {:jsonrpc "2.0" :id id
                         :result {:content [{:type "text" :text result}]}})))

            ;; notifications and unknown methods — no response needed
            nil))))
    (recur)))
