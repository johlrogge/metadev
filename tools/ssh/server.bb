#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; SSH MCP server
;;
;; Provides SSH and SCP access scoped to a project's SSH config.
;; The project is identified by the METADEV_PROJECT environment variable,
;; which is set by the project's devenv.
;;
;; SSH config lives at: ~/.metadev/projects/<project>/.ssh/
;; The -F flag is always passed; the user/system SSH config is never consulted.
;;
;; Tools:
;;   ssh_run       — run a command on a remote host
;;   scp_transfer  — transfer a file between local and remote

(defn resolve-ssh-config-dir
  "Returns the path to the project's .ssh directory, or throws with a clear
   error message if METADEV_PROJECT is unset or the directory does not exist."
  []
  (let [project (System/getenv "METADEV_PROJECT")]
    (when (or (nil? project) (str/blank? project))
      (throw (ex-info
              "METADEV_PROJECT environment variable is not set. This MCP server requires a project devenv context. Enter the project's devenv shell before using SSH tools."
              {})))
    (let [home     (System/getenv "HOME")
          ssh-dir  (str home "/.metadev/projects/" project "/.ssh")]
      (when-not (.isDirectory (java.io.File. ssh-dir))
        (throw (ex-info
                (str "SSH config directory does not exist for project '" project "': " ssh-dir
                     "\nCreate the directory and add a 'config' file (and any keys) to use SSH tools.")
                {})))
      ssh-dir)))

(defn ssh-config-file
  "Returns the path to the SSH config file within the resolved dir."
  [ssh-dir]
  (str ssh-dir "/config"))

(def ssh-opts
  "SSH options applied to every ssh/scp invocation."
  ["-o" "BatchMode=yes"
   "-o" "ConnectTimeout=10"])

(def process-timeout-ms
  "Maximum wall-clock time for any SSH/SCP invocation (30 seconds)."
  30000)

(defn run-with-timeout
  "Runs cmd with stdout/stderr capture. Destroys process and throws on timeout."
  [cmd]
  (let [proc (p/process {:out :string :err :string :cmd cmd})
        result (deref proc process-timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do
        (p/destroy-tree proc)
        (throw (ex-info
                (str "Timed out after " (/ process-timeout-ms 1000) " seconds. "
                     "The remote host may be unreachable or the command is hanging. "
                     "Command: " (str/join " " cmd))
                {:cmd cmd :timeout-ms process-timeout-ms})))
      result)))

(defn run-ssh [host command]
  (let [ssh-dir     (resolve-ssh-config-dir)
        config-file (ssh-config-file ssh-dir)
        cmd         (into ["ssh" "-F" config-file] (concat ssh-opts [host command]))
        result      (run-with-timeout cmd)]
    (str/trim
     (str "exit code: " (:exit result) "\n"
          (when-not (str/blank? (:out result))
            (str "stdout:\n" (str/trim (:out result)) "\n"))
          (when-not (str/blank? (:err result))
            (str "stderr:\n" (str/trim (:err result))))))))

(defn run-scp [src dest]
  (let [ssh-dir     (resolve-ssh-config-dir)
        config-file (ssh-config-file ssh-dir)
        cmd         (into ["scp" "-F" config-file] (concat ssh-opts [src dest]))
        result      (run-with-timeout cmd)]
    (if (zero? (:exit result))
      (str "Transfer successful: " src " -> " dest
           (when-not (str/blank? (:out result))
             (str "\n" (str/trim (:out result)))))
      (str "Transfer failed (exit " (:exit result) "): " src " -> " dest "\n"
           (when-not (str/blank? (:err result))
             (str/trim (:err result)))))))

(defn handle-tool-call [name arguments]
  (case name
    "ssh_run"
    (let [host    (:host arguments)
          command (:command arguments)]
      (when (str/blank? host)
        (throw (ex-info "Missing required parameter: host" {})))
      (when (str/blank? command)
        (throw (ex-info "Missing required parameter: command" {})))
      (run-ssh host command))

    "scp_transfer"
    (let [src  (:src arguments)
          dest (:dest arguments)]
      (when (str/blank? src)
        (throw (ex-info "Missing required parameter: src" {})))
      (when (str/blank? dest)
        (throw (ex-info "Missing required parameter: dest" {})))
      (run-scp src dest))

    (str "Unknown tool: " name)))

(def tools
  [{:name        "ssh_run"
    :description "Run a command on a remote host using the project's SSH config. The SSH config is read from ~/.metadev/projects/<project>/.ssh/config (where <project> is the METADEV_PROJECT environment variable). Only hosts defined in that config file can be reached. Returns stdout, stderr, and exit code."
    :inputSchema {:type       "object"
                  :properties {"host"    {:type        "string"
                                          :description "Hostname or alias as defined in the project SSH config"}
                               "command" {:type        "string"
                                          :description "Shell command to execute on the remote host"}}
                  :required   ["host" "command"]}}

   {:name        "scp_transfer"
    :description "Transfer a file between local and remote using the project's SSH config. Remote paths use host:path format (e.g. 'myserver:/tmp/file.txt'). The SSH config is read from ~/.metadev/projects/<project>/.ssh/config. Returns success or failure with details."
    :inputSchema {:type       "object"
                  :properties {"src"  {:type        "string"
                                       :description "Source path. Use host:path for a remote source (e.g. 'myserver:/var/log/app.log'), or a local absolute path for a local source."}
                               "dest" {:type        "string"
                                       :description "Destination path. Use host:path for a remote destination (e.g. 'myserver:/tmp/upload.txt'), or a local absolute path for a local destination."}}
                  :required   ["src" "dest"]}}])

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
                                 :serverInfo      {:name "ssh" :version "0.1.0"}}}))

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
