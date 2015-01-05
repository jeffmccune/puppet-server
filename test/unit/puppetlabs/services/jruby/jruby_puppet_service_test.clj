(ns puppetlabs.services.jruby.jruby-puppet-service-test
  (:import (java.util.concurrent ExecutionException)
           (com.puppetlabs.puppetserver JRubyPuppet))
  (:require [clojure.test :refer :all]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-service :refer :all]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :as services]
            [clojure.stacktrace :as stacktrace]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]))

(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)

(def jruby-service-test-config
  {:jruby-puppet (jruby-testutils/jruby-puppet-config {:max-active-instances 1})})

(deftest test-error-during-init
  (testing
      (str "If there as an exception while putting a JRubyPuppet instance in "
           "the pool the application should shut down.")
    (logging/with-test-logging
      (with-redefs [jruby-puppet-core/create-pool-instance!
                    (fn [& _] (throw (Exception. "42")))]
                   (let [got-expected-exception (atom false)]
                     (try
                       (bootstrap/with-app-with-config
                         app
                         [jruby-puppet-pooled-service
                          profiler/puppet-profiler-service]
                         jruby-service-test-config
                         (tk/run-app app))
                       (catch Exception e
                         (let [cause (stacktrace/root-cause e)]
                           (is (= (.getMessage cause) "42"))
                           (reset! got-expected-exception true))))
                     (is (true? @got-expected-exception)
                                "Did not get expected exception."))
                   (is (logged?
                         #"^shutdown-on-error triggered because of exception!"
                                :error))))))

(deftest ^:serial test-pool-size
  (testing "The pool is created and the size is correctly reported"
    (let [pool-size 2]
      (bootstrap/with-app-with-config
        app
        [jruby-puppet-pooled-service
         profiler/puppet-profiler-service]
        {:jruby-puppet (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size})}
        (let [service (app/get-service app :JRubyPuppetService)
              all-the-instances
              (mapv (fn [_] (jruby-protocol/borrow-instance service))
                    (range pool-size))]
          (is (= 0 (jruby-protocol/free-instance-count service)))
          (is (= pool-size (count all-the-instances)))
          (doseq [instance all-the-instances]
            (is (not (nil? instance))
                "One of the JRubyPuppet instances retrieved from the pool is nil")
            (jruby-protocol/return-instance service instance))
          (is (= pool-size (jruby-protocol/free-instance-count service))))))))

(deftest ^:serial test-pool-population-during-init
  (testing "A JRuby instance can be borrowed from the 'init' phase of a service"
    (let [test-service (tk/service
                         [[:JRubyPuppetService borrow-instance return-instance]]
                         (init [this context]
                               (return-instance (borrow-instance))
                               context))]

      ; Bootstrap TK, causing the 'init' function above to be executed.
      (tk/boot-services-with-config
        [test-service jruby-puppet-pooled-service profiler/puppet-profiler-service]
        jruby-service-test-config)

      ; If execution gets here, the test passed.
      (is (true? true)))))

(deftest ^:serial test-with-jruby-puppet
  (testing "the `with-jruby-puppet macro`"
    (bootstrap/with-app-with-config
      app
      [jruby-puppet-pooled-service profiler/puppet-profiler-service]
      jruby-service-test-config
      (let [service (app/get-service app :JRubyPuppetService)]
        (with-jruby-puppet
          jruby-puppet
          service
          (is (instance? JRubyPuppet jruby-puppet))
          (is (= 0 (jruby-protocol/free-instance-count service))))
        (is (= 1 (jruby-protocol/free-instance-count service)))))))

(deftest ^:serial test-borrow-timeout-configuration
  (testing "configured :borrow-timeout is honored by the borrow-instance service function"
    (let [timeout   250
          pool-size 1
          config    {:jruby-puppet (jruby-testutils/jruby-puppet-config {:max-active-instances pool-size
                                                                         :borrow-timeout timeout})}]
      (bootstrap/with-app-with-config
        app
        [jruby-puppet-pooled-service profiler/puppet-profiler-service]
        config
        (let [service (app/get-service app :JRubyPuppetService)
              context (services/service-context service)
              pool-context (:pool-context context)
              pool (jruby-puppet-core/get-pool pool-context)]
          (jruby-testutils/drain-pool pool pool-size)
          (let [test-start-in-millis (System/currentTimeMillis)]
            (is (nil? (jruby-protocol/borrow-instance service)))
            (is (>= (- (System/currentTimeMillis) test-start-in-millis) timeout))
            (is (= (:borrow-timeout context) timeout)))))))

  (testing (str ":borrow-timeout defaults to " default-borrow-timeout " milliseconds")
    (bootstrap/with-app-with-config
      app
      [jruby-puppet-pooled-service profiler/puppet-profiler-service]
      jruby-service-test-config
      (let [service (app/get-service app :JRubyPuppetService)
            context (services/service-context service)]
        (is (= (:borrow-timeout context) default-borrow-timeout))))))
