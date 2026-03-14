#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; mcp-test MCP server
;;
;; Lets you test other MCP servers without needing Bash access.
;; Each tool call spawns the target server as a subprocess, performs the
;; MCP handshake, sends one request, reads the response, and returns it.
;;
;; Tools:
;;   mcp_list_tools   — initialize + tools/list against a server
;;   mcp_call_tool    — initialize + tools/list + tools/call against a server
;;   mcp_raw_request  — send a raw JSON-RPC request and return the raw response

(defn write-line [stream s]
  (.write stream (str s "\n"))
  (.flush stream))

(defn read-json-line
  "Read lines from reader until one parses as JSON, skipping blank lines.
   Returns the parsed map or throws after max-attempts."
  [reader max-attempts]
  (loop [n 0]
    (when (> n max-attempts)
      (throw (ex-info "No JSON response from server after many attempts" {})))
    (let [line (.readLine reader)]
      (if (nil? line)
        (throw (ex-info "Server closed its stdout without sending a response" {}))
        (if (str/blank? line)
          (recur (inc n))
          (let [parsed (try (json/parse-string line true) (catch Exception _ ::skip))]
            (if (= parsed ::skip)
              (recur (inc n))
              parsed)))))))

(defn with-mcp-server
  "Spawn `server-cmd` (a vector of strings), perform the MCP initialize
   handshake, then call `f` with [process writer reader].
   Kills the process when f returns or throws."
  [server-cmd f]
  (let [proc   (apply p/process {:in :pipe :out :pipe :err :pipe} server-cmd)
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. (:in proc)))
        reader (java.io.BufferedReader.
                (java.io.InputStreamReader. (:out proc)))]
    (try
      ;; Send initialize
      (write-line writer
                  (json/generate-string
                   {:jsonrpc "2.0" :id 0 :method "initialize"
                    :params  {:protocolVersion "2024-11-05"
                              :capabilities    {}
                              :clientInfo      {:name "mcp-test" :version "0.1.0"}}}))
      (let [init-resp (read-json-line reader 20)]
        (when-not (= "2.0" (:jsonrpc init-resp))
          (throw (ex-info (str "Bad initialize response: " (pr-str init-resp)) {})))
        ;; Send initialized notification (no id, no response expected)
        (write-line writer
                    (json/generate-string
                     {:jsonrpc "2.0" :method "notifications/initialized"}))
        (f proc writer reader))
      (finally
        (try (.close writer) (catch Exception _))
        (p/destroy proc)))))

(defn mcp-list-tools [server-cmd]
  (with-mcp-server server-cmd
    (fn [_proc writer reader]
      (write-line writer
                  (json/generate-string
                   {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}}))
      (let [resp  (read-json-line reader 20)
            tools (get-in resp [:result :tools])]
        (if (seq tools)
          (str "Tools provided by server (" (count tools) "):\n\n"
               (str/join "\n\n"
                         (map (fn [{:keys [name description inputSchema]}]
                                (let [props    (get inputSchema :properties {})
                                      required (set (get inputSchema :required []))]
                                  (str "  " name "\n"
                                       "    " description "\n"
                                       (when (seq props)
                                         (str "    Parameters:\n"
                                              (str/join "\n"
                                                        (map (fn [[k v]]
                                                               (let [kname (clojure.core/name k)]
                                                                 (str "      " kname
                                                                      " (" (:type v) ")"
                                                                      (when (contains? required kname) " [required]")
                                                                      " — " (:description v))))
                                                             props)))))))
                              tools)))
          (str "Server responded with no tools.\nRaw response: " (pr-str resp)))))))

(defn mcp-call-tool [server-cmd tool-name arguments-map]
  (with-mcp-server server-cmd
    (fn [_proc writer reader]
      ;; Verify the tool exists first
      (write-line writer
                  (json/generate-string
                   {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}}))
      (let [list-resp  (read-json-line reader 20)
            tool-names (set (map :name (get-in list-resp [:result :tools])))]
        (when-not (contains? tool-names tool-name)
          (throw (ex-info (str "Tool '" tool-name "' not found. Available: "
                               (str/join ", " (sort tool-names)))
                          {}))))
      ;; Now call the tool
      (write-line writer
                  (json/generate-string
                   {:jsonrpc "2.0" :id 2 :method "tools/call"
                    :params  {:name tool-name :arguments arguments-map}}))
      (let [resp (read-json-line reader 20)]
        (if (:error resp)
          (str "MCP error: " (pr-str (:error resp)))
          (let [content (get-in resp [:result :content])]
            (if (seq content)
              (str/join "\n" (keep :text content))
              (str "No content in response.\nRaw: " (pr-str resp)))))))))

(defn mcp-raw-request [server-cmd request-map]
  (with-mcp-server server-cmd
    (fn [_proc writer reader]
      (write-line writer (json/generate-string request-map))
      (let [resp (read-json-line reader 20)]
        (json/generate-string resp {:pretty true})))))

;; ---------------------------------------------------------------------------
;; Parse server-cmd string into a vector of tokens
;; ---------------------------------------------------------------------------

(defn parse-server-cmd [s]
  ;; Simple whitespace split — sufficient for "bb /path/to/server.bb"
  (str/split (str/trim s) #"\s+"))

;; ---------------------------------------------------------------------------
;; Tool dispatch
;; ---------------------------------------------------------------------------

(defn handle-tool-call [name arguments]
  (let [server-cmd-str (:server_cmd arguments)]
    (when (str/blank? server-cmd-str)
      (throw (ex-info "Missing required parameter: server_cmd" {})))
    (let [server-cmd (parse-server-cmd server-cmd-str)]
      (case name
        "mcp_list_tools"
        (mcp-list-tools server-cmd)

        "mcp_call_tool"
        (let [tool-name (:tool_name arguments)]
          (when (str/blank? tool-name)
            (throw (ex-info "Missing required parameter: tool_name" {})))
          (let [args-raw  (:arguments arguments "{}")
                args-map  (try (json/parse-string args-raw true)
                               (catch Exception e
                                 (throw (ex-info (str "arguments must be valid JSON: " (.getMessage e)) {}))))]
            (mcp-call-tool server-cmd tool-name args-map)))

        "mcp_raw_request"
        (let [request-raw (:request arguments)]
          (when (str/blank? request-raw)
            (throw (ex-info "Missing required parameter: request" {})))
          (let [request-map (try (json/parse-string request-raw true)
                                 (catch Exception e
                                   (throw (ex-info (str "request must be valid JSON: " (.getMessage e)) {}))))]
            (mcp-raw-request server-cmd request-map)))

        (str "Unknown tool: " name)))))

;; ---------------------------------------------------------------------------
;; Tool definitions
;; ---------------------------------------------------------------------------

(def tools
  [{:name        "mcp_list_tools"
    :description "Connect to an MCP server and return the list of tools it exposes, with their descriptions and parameter schemas. Use this to verify a newly built server is wired up correctly."
    :inputSchema {:type       "object"
                  :properties {"server_cmd" {:type        "string"
                                             :description "Shell command to launch the MCP server, e.g. 'bb /abs/path/to/server.bb'. Tokens are split on whitespace."}}
                  :required   ["server_cmd"]}}

   {:name        "mcp_call_tool"
    :description "Connect to an MCP server, call a named tool with the given arguments, and return the text response. Use this to exercise individual tools during development."
    :inputSchema {:type       "object"
                  :properties {"server_cmd" {:type        "string"
                                             :description "Shell command to launch the MCP server, e.g. 'bb /abs/path/to/server.bb'."}
                               "tool_name"  {:type        "string"
                                             :description "Name of the tool to call, exactly as returned by mcp_list_tools."}
                               "arguments"  {:type        "string"
                                             :description "JSON object of arguments to pass to the tool, e.g. '{\"path\": \"/tmp\"}'. Defaults to '{}' if omitted."}}
                  :required   ["server_cmd" "tool_name"]}}

   {:name        "mcp_raw_request"
    :description "Send a raw JSON-RPC request to an MCP server (after the initialize handshake) and return the raw JSON response. Useful for testing edge cases and error handling."
    :inputSchema {:type       "object"
                  :properties {"server_cmd" {:type        "string"
                                             :description "Shell command to launch the MCP server."}
                               "request"    {:type        "string"
                                             :description "A complete JSON-RPC request object as a JSON string, e.g. '{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"tools/list\",\"params\":{}}'."}}
                  :required   ["server_cmd" "request"]}}])

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
                                 :serverInfo      {:name "mcp-test" :version "0.1.0"}}}))

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

            nil))))
    (recur)))
