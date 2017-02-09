(ns circleci.test
  (:require [circleci.test.report :as report]
            [clojure.test :as test]))

;; Running tests; low-level fns

(defn- nanos->seconds
  [nanos]
  (/ nanos 1e9))

(defn- test*
  [v]
  (when-let [t (:test (meta v))]
    (test/do-report {:type :begin-test-var, :var v})
    (t)))

(defn- test-var*
  [v]
  (assert (var? v) (format "v must be a var. got %s" (class v)))
  (let [ns (-> v meta :ns)
        each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
    (when (:test (meta v))
      (binding [test/*testing-vars* (conj test/*testing-vars* v)]
        (let [start-time (System/nanoTime)]
          (try
            (each-fixture-fn (fn [] (test* v)))
            (catch Throwable e
              (test/do-report {:type :error,
                               :message "Uncaught exception, not in assertion."
                               :expected nil, :actual e}))
            (finally
              (let [stop-time (System/nanoTime)
                    elapsed (-> stop-time (- start-time) nanos->seconds)]
                (test/do-report {:type :end-test-var,
                                 :var v
                                 :elapsed elapsed})))))))))

(defn test-var
  [v]
  ;; Make sure calling any nested test fns invokes _our_ test-var, not
  ;; clojure.test's
  (binding [test/test-var test-var*]
    (test-var* v)))

(defn test-all-vars
  [ns]
  (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))]
    (once-fixture-fn
      (fn []
        (doseq [v (vals (ns-interns ns))]
          (when (:test (meta v))
            (test-var v)))))))

(defn test-ns
  "If the namespace defines a function named test-ns-hook, calls that.
  Otherwise, calls test-all-vars on the namespace.  'ns' is a
  namespace object or a symbol.

  Internally binds *report-counters* to a ref initialized to
  *initial-report-counters*.  Returns the final, dereferenced state of
  *report-counters*."
  [ns]
  (binding [test/*report-counters* (ref test/*initial-report-counters*)
            test/report report/report]
    (let [ns-obj (the-ns ns)]
      (test/do-report {:type :begin-test-ns, :ns ns-obj})
      ;; If the namespace has a test-ns-hook function, call that:
      (if-let [v (find-var (symbol (str (ns-name ns-obj)) "test-ns-hook"))]
        ((var-get v))
        ;; Otherwise, just test every var in the namespace.
        (test-all-vars ns-obj))
      (test/do-report {:type :end-test-ns, :ns ns-obj}))
    @test/*report-counters*))


;; Running tests; high-level fns

(defn run-tests
  "Runs all tests in the given namespaces; prints results.
  Defaults to current namespace if none given.  Returns a map
  summarizing test results."
  ([] (run-tests *ns*))
  ([& namespaces]
    (let [summary (assoc (apply merge-with + (map test-ns namespaces))
                         :type :summary)]
      (test/do-report summary)
      summary)))

(defn run-all-tests
  "Runs all tests in all namespaces; prints results.
  Optional argument is a regular expression; only namespaces with
  names matching the regular expression (with re-matches) will be
  tested."
  ([] (apply run-tests (all-ns)))
  ([re] (apply run-tests (filter #(re-matches re (name (ns-name %))) (all-ns)))))

(defn -main
  [& ns-strings]
  (let [nses (map read-string ns-strings)]
    (if-not (seq nses)
      (throw (ex-info "Must pass a list of namespaces to test" {}))
      (let [_ (apply require :reload nses)
            summary (apply run-tests nses)]
        (System/exit (+ (:error summary)
                        (:fail summary)))))))
