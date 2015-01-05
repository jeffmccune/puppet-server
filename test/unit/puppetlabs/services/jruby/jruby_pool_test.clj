(ns puppetlabs.services.jruby.jruby-pool-test
  (:import (clojure.lang ExceptionInfo))
  (:require [clojure.test :refer :all]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-core :refer :all :as core]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-agents :as jruby-agents]))

(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests

(deftest configuration-validation
  (testing "malformed configuration fails"
    (let [malformed-config {:illegal-key [1 2 3]}]
      (is (thrown-with-msg? ExceptionInfo
                            #"Input to create-pool-from-config does not match schema"
                            (create-pool-context malformed-config nil))))))

(deftest ^:serial test-jruby-service-core-funcs
  (let [pool-size        2
        config           (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})
        profiler         jruby-testutils/default-profiler
        pool-context     (create-pool-context config profiler)
        pool             (get-pool pool-context)]

    (testing "The pool should not yet be full as it is being primed in the
             background."
      (is (= (free-instance-count pool) 0)))

    (jruby-agents/prime-pool! (:pool-state pool-context) config profiler)

    (testing "Borrowing all instances from a pool while it is being primed and
             returning them."
      (let [all-the-jrubys (jruby-testutils/drain-pool pool pool-size)]
        (is (= 0 (free-instance-count pool)))
        (doseq [instance all-the-jrubys]
          (is (not (nil? instance)) "One of JRubyPuppet instances is nil"))
        (jruby-testutils/fill-drained-pool all-the-jrubys)
        (is (= pool-size (free-instance-count pool)))))

    (testing "Borrowing from an empty pool with a timeout returns nil within the
             proper amount of time."
      (let [timeout              250
            all-the-jrubys       (jruby-testutils/drain-pool pool pool-size)
            test-start-in-millis (System/currentTimeMillis)]
        (is (nil? (borrow-from-pool-with-timeout pool timeout)))
        (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout)
            "The timeout value was not honored.")
        (jruby-testutils/fill-drained-pool all-the-jrubys)
        (is (= (free-instance-count pool) pool-size)
            "All JRubyPuppet instances were not returned to the pool.")))

    (testing "Removing an instance decrements the pool size by 1."
      (let [jruby-instance (borrow-from-pool pool)]
        (is (= (free-instance-count pool) (dec pool-size)))
        (return-to-pool jruby-instance)))))

(deftest ^:serial prime-pools-failure
  (let [pool-size 2
        config        (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})
        profiler      jruby-testutils/default-profiler
        pool-context  (create-pool-context config profiler)
        pool          (get-pool pool-context)
        err-msg       (re-pattern "Unable to borrow JRuby instance from pool")]
    (with-redefs [core/create-pool-instance! (fn [_] (throw (IllegalStateException. "BORK!")))]
                 (is (thrown? IllegalStateException (jruby-agents/prime-pool! (:pool-state pool-context) config profiler))))
    (testing "borrow and borrow-with-timeout both throw an exception if the pool failed to initialize"
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (borrow-from-pool pool)))
      (is (thrown-with-msg? IllegalStateException
            err-msg
            (borrow-from-pool-with-timeout pool 120))))
    (testing "borrow and borrow-with-timeout both continue to throw exceptions on subsequent calls"
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (borrow-from-pool pool)))
      (is (thrown-with-msg? IllegalStateException
          err-msg
          (borrow-from-pool-with-timeout pool 120))))))

(deftest ^:serial test-default-pool-size
  (let [config jruby-testutils/default-config-no-size
        profiler   jruby-testutils/default-profiler
        pool       (create-pool-context config profiler)
        pool-state @(:pool-state pool)]
    (is (= core/default-pool-size (:size pool-state)))))

