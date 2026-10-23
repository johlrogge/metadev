#!/usr/bin/env bb
;; Unit tests for the pure functions in align.bb.
;; Run: bb tools/align-nixpkgs/align_test.bb   (or `just align-nixpkgs-test`)

(ns align-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]))

;; align.bb guards -main behind (= *file* babashka.file), so loading it here is side-effect free.
(load-file (str (fs/path (fs/parent (fs/absolutize *file*)) "align.bb")))
(alias 'a 'align-nixpkgs)

(def rev  "c2f38fe7f9e04d9aadd354d380f2bd40531d9737")
(def old  "256551e45f6303e142ab4a98be1bf243feb77dc0")

;; --- base-branch ------------------------------------------------------------

(deftest base-branch-test
  (testing "gitflow develop branch wins when it exists"
    (is (= "develop" (a/base-branch "develop" #{"develop" "master"}))))
  (testing "falls back to master, then main"
    (is (= "master" (a/base-branch nil #{"master" "main"})))
    (is (= "main" (a/base-branch nil #{"main" "feature/x"}))))
  (testing "configured gitflow branch that does not exist is skipped"
    (is (= "master" (a/base-branch "develop" #{"master"}))))
  (testing "blank gitflow config is ignored"
    (is (= "main" (a/base-branch "" #{"main"}))))
  (testing "nothing usable"
    (is (nil? (a/base-branch nil #{"feature/x"})))))

;; --- classify-yaml ----------------------------------------------------------

(defn yaml-of [inputs] {:inputs inputs})

(deftest classify-yaml-test
  (testing "no nixpkgs input"
    (is (= {:status :skipped :reason :no-nixpkgs-input}
           (a/classify-yaml (yaml-of {:devenv {:url "github:cachix/devenv"}}))))
    (is (= {:status :skipped :reason :no-nixpkgs-input}
           (a/classify-yaml {}))))
  (testing "nonstandard nixpkgs url"
    (is (= {:status :nonstandard :url "github:NixOS/nixpkgs/nixos-25.11"}
           (a/classify-yaml (yaml-of {:nixpkgs {:url "github:NixOS/nixpkgs/nixos-25.11"}})))))
  (testing "relative path inputs skip the project"
    (is (= {:status :skipped :reason :relative-path-input :input "local"}
           (a/classify-yaml (yaml-of {:nixpkgs {:url a/rolling-url}
                                      :local   {:url "path:../local"}}))))
    (is (= :relative-path-input
           (:reason (a/classify-yaml (yaml-of {:nixpkgs {:url a/rolling-url}
                                               :metadev {:url "path:./vendor/metadev"}}))))))
  (testing "absolute path inputs are fine"
    (let [c (a/classify-yaml (yaml-of {:nixpkgs {:url a/rolling-url}
                                       :metadev {:url "path:///home/u/projects/metadev"}}))]
      (is (= :ok (:status c)))
      (is (= ["nixpkgs" "metadev"] (:in-scope c)))
      (is (= :absolute-path (:metadev-kind c)))))
  (testing "in-scope inputs: nixpkgs plus devenv/metadev when present, in fixed order"
    (let [c (a/classify-yaml (yaml-of {:metadev {:url "github:johlrogge/metadev" :flake false}
                                       :nixpkgs {:url a/rolling-url}
                                       :devenv  {:url "github:cachix/devenv"}
                                       :rust-overlay {:url "github:oxalica/rust-overlay"}}))]
      (is (= :ok (:status c)))
      (is (= ["nixpkgs" "devenv" "metadev"] (:in-scope c)))
      (is (= :other (:metadev-kind c)))
      (is (= "github:cachix/devenv" (:devenv-url c)))))
  (testing "nixpkgs only"
    (is (= ["nixpkgs"] (:in-scope (a/classify-yaml (yaml-of {:nixpkgs {:url a/rolling-url}})))))))

(deftest input-kind-test
  (is (= :absolute-path (a/input-kind "path:///home/x")))
  (is (= :absolute-path (a/input-kind "path:/home/x")))
  (is (= :relative-path (a/input-kind "path:./x")))
  (is (= :relative-path (a/input-kind "path:../x")))
  (is (= :relative-path (a/input-kind "../x")))
  (is (= :other (a/input-kind "github:cachix/devenv")))
  (is (= :none (a/input-kind nil))))

(deftest metadev-plan-test
  (is (= {:update? false :warnings []} (a/metadev-plan nil {})))
  (is (= {:update? true :warnings []} (a/metadev-plan :other {:dirty? true})))
  (is (= {:update? true :warnings []}
         (a/metadev-plan :absolute-path {:dirty? false :branch "develop" :integration "develop"})))
  (let [p (a/metadev-plan :absolute-path {:dirty? true :branch "feature/x" :integration "develop"})]
    (is (false? (:update? p)))
    (is (= 2 (count (:warnings p))))))

;; --- lock reading -----------------------------------------------------------

(def lock
  {:nodes {:root      {:inputs {:nixpkgs "nixpkgs_2" :devenv "devenv" :metadev "metadev"
                                :followed ["devenv" "nixpkgs"]}}
           :nixpkgs   {:locked {:rev old :lastModified 1}}
           :nixpkgs_2 {:locked {:rev rev :lastModified 1789506231}}
           :devenv    {:locked {:rev "a73c5b84c9527760f009232988466763068f9f61"}}
           :metadev   {:locked {:lastModified 1789506231 :narHash "sha256-x"}}}})

(deftest lock-test
  (testing "resolves through root inputs, not node names"
    (is (= rev (a/lock-rev lock "nixpkgs"))))
  (is (= "a73c5b84c9527760f009232988466763068f9f61" (a/lock-rev lock "devenv")))
  (is (nil? (a/lock-rev lock "missing")))
  (is (nil? (a/lock-rev lock "followed")))
  (is (= 1789506231 (a/lock-last-modified lock "nixpkgs")))
  (is (= "a73c5b8" (a/lock-version lock "devenv")))
  (is (str/starts-with? (a/lock-version lock "metadev") "2026-"))
  (is (= "2026-09-15" (a/epoch->date 1789506231))))

;; --- branch-decision --------------------------------------------------------

(def wt "/p/proj/.worktrees/align-nixpkgs")
(def bref (str "refs/heads/" a/branch-name))

(defn facts [m]
  (merge {:wt-path wt
          :worktrees [{:worktree "/p/proj" :branch "refs/heads/develop"}]
          :branch-exists? false}
         m))

(deftest branch-decision-test
  (testing "no branch yet"
    (is (= :create (:decision (a/branch-decision (facts {}))))))
  (testing "branch merged into base"
    (is (= :recreate (:decision (a/branch-decision (facts {:branch-exists? true :ancestor? true}))))))
  (testing "unmerged earlier proposal made only by us"
    (let [d (a/branch-decision (facts {:branch-exists? true :ancestor? false
                                       :branch-tip "1234567890abcdef"
                                       :proposal-commits [["1234567890abcdef" a/trailer-value]]
                                       :diff-files ["devenv.lock"]}))]
      (is (= :recreate (:decision d)))
      (is (= "1234567" (:replaced d)))))
  (testing "foreign commit on the branch"
    (is (= :skip-foreign
           (:decision (a/branch-decision (facts {:branch-exists? true :ancestor? false
                                                 :proposal-commits [["aaa" a/trailer-value] ["bbb" ""]]
                                                 :diff-files ["devenv.lock"]}))))))
  (testing "our commits but touching other files"
    (is (= :skip-foreign
           (:decision (a/branch-decision (facts {:branch-exists? true :ancestor? false
                                                 :proposal-commits [["aaa" a/trailer-value]]
                                                 :diff-files ["devenv.lock" "devenv.nix"]}))))))
  (testing "branch checked out elsewhere"
    (let [d (a/branch-decision (facts {:branch-exists? true :ancestor? true
                                       :worktrees [{:worktree "/p/proj" :branch bref}]}))]
      (is (= :skip-checked-out (:decision d)))
      (is (= "/p/proj" (:where d)))))
  (testing "stale worktree from a crashed run is reaped"
    (is (= :reap-and-recreate
           (:decision (a/branch-decision (facts {:branch-exists? true :ancestor? true
                                                 :worktrees [{:worktree "/p/proj" :branch "refs/heads/develop"}
                                                             {:worktree wt :branch bref}]})))))
    (is (= :reap-and-recreate
           (:decision (a/branch-decision (facts {:branch-exists? true :ancestor? false
                                                 :worktrees [{:worktree wt :branch bref}]
                                                 :proposal-commits [["aaa" a/trailer-value]]
                                                 :diff-files ["devenv.lock"]}))))))
  (testing "stale worktree holding foreign commits is left alone"
    (is (= :skip-foreign
           (:decision (a/branch-decision (facts {:branch-exists? true :ancestor? false
                                                 :worktrees [{:worktree wt :branch bref}]
                                                 :proposal-commits [["aaa" ""]]
                                                 :diff-files ["devenv.lock"]})))))))

(deftest parse-trailer-log-test
  (is (= [["aaa" "metadev align-nixpkgs"] ["bbb" ""]]
         (a/parse-trailer-log "aaa\tmetadev align-nixpkgs\nbbb\t\n")))
  (is (= [] (a/parse-trailer-log ""))))

(deftest parse-worktree-list-test
  (is (= [{:worktree "/p" :head "abc" :branch "refs/heads/develop"}
          {:worktree "/p/.worktrees/x" :head "def" :detached true}]
         (a/parse-worktree-list "worktree /p\nHEAD abc\nbranch refs/heads/develop\n\nworktree /p/.worktrees/x\nHEAD def\ndetached\n"))))

;; --- dry-run outcome --------------------------------------------------------

(deftest dry-run-outcome-test
  (is (= :would-noop (:status (a/dry-run-outcome {:nixpkgs-before rev :rev rev}))))
  (is (= :would-align (:status (a/dry-run-outcome {:nixpkgs-before old :rev rev}))))
  (is (= :would-noop (:status (a/dry-run-outcome {:nixpkgs-before rev :rev rev
                                                  :devenv {:before "d1" :head "d1" :default? true}}))))
  (is (= :would-align (:status (a/dry-run-outcome {:nixpkgs-before rev :rev rev
                                                   :devenv {:before "d1" :head "d2" :default? true}}))))
  (testing "non-default devenv ref is unknown and counts as would-align"
    (let [o (a/dry-run-outcome {:nixpkgs-before rev :rev rev
                                :devenv {:before "d1" :head "d1" :default? false}})]
      (is (= :would-align (:status o)))
      (is (some #(str/includes? % "unknown") (:notes o))))))

;; --- commit-message ---------------------------------------------------------

(deftest commit-message-test
  (testing "nixpkgs only"
    (is (= (str "chore(deps): align nixpkgs to devenv-nixpkgs c2f38fe (2026-09-15)\n"
                "\n"
                "nixpkgs: 256551e -> c2f38fe\n"
                "\n"
                "Aligned-by: metadev align-nixpkgs\n")
           (a/commit-message {:rev rev :rev-date "2026-09-15"
                              :before {:nixpkgs old :devenv "d1" :metadev "m1"}
                              :after  {:nixpkgs rev :devenv "d1" :metadev "m1"}}))))
  (testing "devenv and metadev lines only when changed"
    (let [m (a/commit-message {:rev rev :rev-date "2026-09-15"
                               :before {:nixpkgs old :devenv "1111111aaaa" :metadev "2026-01-01T10:00"}
                               :after  {:nixpkgs rev :devenv "2222222bbbb" :metadev "2026-02-01T10:00"}})]
      (is (str/includes? m "\ndevenv: 1111111 -> 2222222\n"))
      (is (str/includes? m "\nmetadev: 2026-01-01T10:00 -> 2026-02-01T10:00\n"))
      (is (str/ends-with? m "\n\nAligned-by: metadev align-nixpkgs\n"))
      (is (not (str/includes? m "Co-Authored-By"))))))

;; --- env --------------------------------------------------------------------

(deftest base-env-test
  (let [env {"HOME" "/home/u" "PATH" "/m/.devenv/profile/bin:/m/.devenv/state/x/bin:/usr/bin"
             "DEVENV_ROOT" "/m" "DEVENV_PROFILE" "/m/.devenv/profile"
             "IN_NIX_SHELL" "impure" "DEVENV_STATE" "/m/.devenv/state" "LANG" "C.UTF-8"}
        e (a/base-env env)]
    (is (= #{"HOME" "PATH" "LANG"} (set (keys e))))
    (is (= "/m/.devenv/profile/bin:/usr/bin" (get e "PATH"))))
  (testing "outside devenv the PATH is untouched"
    (is (= "/a/.devenv/x:/usr/bin" (get (a/base-env {"PATH" "/a/.devenv/x:/usr/bin"}) "PATH")))))

;; --- projects ---------------------------------------------------------------

(deftest order-and-select-test
  (let [ps [{:name "zeta" :path "/p/zeta"} {:name "metadev" :path "/p/metadev"} {:name "alpha" :path "/p/alpha"}]]
    (is (= ["metadev" "alpha" "zeta"] (map :name (a/order-projects ps "/p/metadev"))))
    (is (= {:selected ["alpha"] :excluded [] :unknown []}
           (update (a/select-projects ps ["alpha"] []) :selected #(mapv :name %))))
    (is (= ["nope"] (:unknown (a/select-projects ps ["alpha" "nope"] []))))
    (let [s (a/select-projects ps [] ["zeta"])]
      (is (= #{"metadev" "alpha"} (set (map :name (:selected s)))))
      (is (= ["zeta"] (map :name (:excluded s)))))))

;; --- report -----------------------------------------------------------------

(deftest status-label-test
  (is (= "ALIGNED" (a/status-label {:status :aligned})))
  (is (= "WOULD-ALIGN" (a/status-label {:status :would-align})))
  (is (= "FAILED (build)" (a/status-label {:status :failed :reason :build})))
  (is (= "SKIPPED (low-disk)" (a/status-label {:status :skipped :reason :low-disk}))))

(deftest report-file-name-test
  (is (= "2026-10-26.md" (a/report-file-name "2026-10-26" #{})))
  (is (= "2026-10-26-2.md" (a/report-file-name "2026-10-26" #{"2026-10-26.md"})))
  (is (= "2026-10-26-3.md" (a/report-file-name "2026-10-26" #{"2026-10-26.md" "2026-10-26-2.md"}))))

(def sample-report
  {:date "2026-10-26" :mode :run
   :reference {:rev rev :rev-date "2026-10-24" :devenv-head "def5678000"
               :metadev {:branch "develop" :head "9f0a8e8" :dirty? false}}
   :disk {:start 5.8e9 :end 4.1e9 :gc-freed 1.2e9}
   :warnings ["global warning"]
   :results [{:project "metadev" :path "/p/metadev" :base "develop" :status :aligned
              :before {:nixpkgs old} :tip "7e8f9a0" :notes ["devenv updated"] :warnings []}
             {:project "mdma" :path "/p/mdma" :base "develop" :status :nonstandard
              :notes ["url github:NixOS/nixpkgs/nixos-25.11"] :warnings []}
             {:project "ctx" :path "/p/ctx" :base "master" :status :noop :before {:nixpkgs rev} :warnings ["w|1"]}
             {:project "corsett" :path "/p/corsett" :base "master" :status :failed :reason :build
              :before {:nixpkgs old} :log "/s/logs/2026-10-26/corsett.log" :tail "line1\nline2"}]})

(deftest render-report-test
  (let [r (a/render-report sample-report)]
    (is (str/starts-with? r "# nixpkgs alignment — 2026-10-26 (run)\n"))
    (is (str/includes? r "Reference: cachix/devenv-nixpkgs rolling @ c2f38fe (2026-10-24) · devenv HEAD def5678 · metadev develop@9f0a8e8 (clean)"))
    (is (str/includes? r "/nix free: 5.8 GB -> 4.1 GB (GC freed 1.2 GB)"))
    (is (str/includes? r "| Project | Base | nixpkgs before | Result | Branch tip | Notes |"))
    (is (str/includes? r "| metadev | develop | 256551e | ALIGNED | 7e8f9a0 | devenv updated |"))
    (is (str/includes? r "| mdma | develop | — | NONSTANDARD | — | url github:NixOS/nixpkgs/nixos-25.11 |"))
    (is (str/includes? r "| ctx | master | c2f38fe | NOOP | — |  |"))
    (is (str/includes? r "| corsett | master | 256551e | FAILED (build) | — | see below |"))
    (is (str/includes? r "### corsett (build) — full log: /s/logs/2026-10-26/corsett.log\n    line1\n    line2"))
    (is (str/includes? r "- global warning"))
    (is (str/includes? r "- ctx: w|1"))
    (is (str/includes? r "git -C /p/metadev merge --ff-only chore/align-nixpkgs"))
    (is (not (str/includes? r "git -C /p/ctx merge")))))

(deftest render-report-dry-run-test
  (let [r (a/render-report (assoc sample-report :mode :dry-run :disk {:start 5.8e9}
                                  :results [{:project "ctx" :path "/p/ctx" :base "master" :status :would-align
                                             :before {:nixpkgs old} :notes ["branch: create"]}]))]
    (is (str/includes? r "(dry-run)"))
    (is (str/includes? r "/nix free: 5.8 GB\n"))
    (is (str/includes? r "| ctx | master | 256551e | WOULD-ALIGN | — | branch: create |"))
    (is (not (str/includes? r "## To review and merge")))
    (is (not (str/includes? r "## Failures")))))

;; --- systemd ----------------------------------------------------------------

(deftest choose-devenv-test
  (is (= "/home/u/.nix-profile/bin/devenv"
         (a/choose-devenv ["/home/u/.nix-profile/bin/devenv"] "/nix/store/abc/bin/devenv")))
  (is (= "/usr/local/bin/devenv" (a/choose-devenv [] "/usr/local/bin/devenv")))
  (is (nil? (a/choose-devenv [] "/nix/store/abc-devenv/bin/devenv")))
  (is (nil? (a/choose-devenv [] "/home/u/projects/metadev/.devenv/profile/bin/devenv")))
  (is (nil? (a/choose-devenv [] nil))))

(deftest unit-rendering-test
  (is (= "/opt/bin:/home/u/.nix-profile/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin"
         (a/unit-path "/opt/bin/devenv" "/home/u")))
  (is (= "/home/u/.nix-profile/bin:/nix/var/nix/profiles/default/bin:/usr/local/bin:/usr/bin:/bin"
         (a/unit-path "/home/u/.nix-profile/bin/devenv" "/home/u")))
  (is (= "x=/d y=/m /d" (a/render-template "x=@DEVENV@ y=@METADEV@ @DEVENV@" {"DEVENV" "/d" "METADEV" "/m"}))))

;; --- CLI parsing ----------------------------------------------------------

(deftest parse-run-args-test
  (testing "defaults"
    (let [{:keys [opts error]} (a/parse-run-args [])]
      (is (nil? error))
      (is (= 4 (:min-free-gb opts)))
      (is (= 45 (:build-timeout-min opts)))
      (is (true? (:notify opts)))
      (is (not (:dry-run opts)))))
  (testing "flags, repeatable names, numbers"
    (let [{:keys [opts error]} (a/parse-run-args ["--dry-run" "--only" "ctx" "--only" "mdma"
                                                  "--exclude" "x" "--gc" "--min-free-gb" "10"
                                                  "--projects-dir" "/tmp/p"])]
      (is (nil? error))
      (is (true? (:dry-run opts)))
      (is (= ["ctx" "mdma"] (:only opts)))
      (is (= ["x"] (:exclude opts)))
      (is (true? (:gc opts)))
      (is (= 10 (:min-free-gb opts)))
      (is (= "/tmp/p" (:projects-dir opts)))))
  (testing "--no-notify"
    (is (false? (:notify (:opts (a/parse-run-args ["--no-notify"])))))
    (is (not (contains? (:opts (a/parse-run-args ["--no-notify"])) :no-notify))))
  (testing "errors"
    (is (:error (a/parse-run-args ["--bogus"])))
    (is (:error (a/parse-run-args ["stray"])))
    (is (:error (a/parse-run-args ["--min-free-gb" "lots"])))))

;; --- smoke: run-lock primitive is available in this bb ------------------------

(deftest run-lock-smoke-test
  (let [dir (str (fs/create-temp-dir))]
    (try
      (let [held (a/try-lock! dir)]
        (is (some? held) "first lock acquired")
        (is (nil? (a/try-lock! dir)) "second lock refused while held")
        (a/release-lock! held)
        (let [again (a/try-lock! dir)]
          (is (some? again) "lock re-acquired after release")
          (a/release-lock! again)))
      (finally (fs/delete-tree dir)))))

(let [{:keys [fail error]} (t/run-tests 'align-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
