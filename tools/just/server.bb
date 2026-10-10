#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json])

;; Parse --allow flag: bb server.bb --allow recipe1 recipe2 ...
;; If --allow is absent, all recipes are permitted.
(def allowed-recipes
  (let [args (vec *command-line-args*)
        idx  (.indexOf args "--allow")]
    (when (>= idx 0)
      (set (drop (inc idx) args)))))

(defn allowed? [recipe]
  (or (nil? allowed-recipes) (contains? allowed-recipes recipe)))

(defn run-just [path recipe args]
  (if-not (allowed? recipe)
    (str "Error: recipe '" recipe "' is not in the allowed list for this server")
    ;; :continue true is required — without it p/shell throws on a non-zero exit
    ;; and the recipe's output is lost before it can be reported.
    (let [cmd    (into ["just" recipe] (or args []))
          result (apply p/shell {:out :string :err :string :dir path :continue true} cmd)
          out    (clojure.string/trim (:out result))
          err    (clojure.string/trim (:err result))]
      (if (zero? (:exit result))
        (if (empty? out) "(no output)" out)
        ;; A non-zero exit is often the meaningful result (diff tools, validators,
        ;; build gates), so report both streams rather than just the failure.
        (str "Recipe failed (exit " (:exit result) ")\n"
             (when-not (empty? out) (str "\n--- stdout ---\n" out "\n"))
             (when-not (empty? err) (str "\n--- stderr ---\n" err "\n"))
             (when (and (empty? out) (empty? err)) "\n(no output captured)\n"))))))

(defn list-recipes [path]
  (let [result (p/shell {:out :string :err :string :dir path :continue true}
                        "just" "--list")]
    (if (zero? (:exit result))
      (let [all-lines (clojure.string/split-lines (:out result))
            ;; drop the "Available recipes:" header line
            lines     (remove #(clojure.string/starts-with? % "Available") all-lines)
            lines     (remove clojure.string/blank? lines)
            lines     (if allowed-recipes
                        (filter (fn [line]
                                  (let [recipe (first (clojure.string/split
                                                       (clojure.string/trim line) #"[\s#]+"))]
                                    (contains? allowed-recipes recipe)))
                                lines)
                        lines)]
        (if (empty? lines)
          "(no recipes available)"
          (clojure.string/join "\n" lines)))
      (str "Error: " (clojure.string/trim (:err result))))))

(defn handle-tool-call [name arguments]
  (let [path (:path arguments)]
    (when (nil? path)
      (throw (ex-info "Missing required parameter: path" {})))
    (case name
      "just_list"
      (list-recipes path)

      "just_run"
      (let [recipe (:recipe arguments)]
        (when (nil? recipe)
          (throw (ex-info "Missing required parameter: recipe" {})))
        (run-just path recipe (:args arguments)))

      (str "Unknown tool: " name))))

(def tools
  [{:name "just_list"
    :description "List available just recipes in a project directory. Only shows recipes permitted by this server's allowlist (if configured)."
    :inputSchema {:type "object"
                  :properties {"path" {:type "string"
                                       :description "Absolute path to the project directory containing a justfile"}}
                  :required ["path"]}}
   {:name "just_run"
    :description "Run a just recipe in a project directory. Only recipes in the server's allowlist (if configured) may be run."
    :inputSchema {:type "object"
                  :properties {"path"   {:type "string"
                                         :description "Absolute path to the project directory containing a justfile"}
                               "recipe" {:type "string"
                                         :description "Name of the just recipe to run"}
                               "args"   {:type "array"
                                         :items {:type "string"}
                                         :description "Optional positional arguments to pass to the recipe"}}
                  :required ["path" "recipe"]}}])

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
                                :serverInfo {:name "just" :version "0.1.0"}}}))

            "tools/list"
            (println (json/generate-string
                      {:jsonrpc "2.0" :id id
                       :result {:tools tools}}))

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
                         :result {:content [{:type "text" :text result}]}})))

            nil))))
    (recur)))
