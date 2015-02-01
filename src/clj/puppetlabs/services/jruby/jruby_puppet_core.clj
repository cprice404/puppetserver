(ns puppetlabs.services.jruby.jruby-puppet-core
  (:require [me.raynes.fs :as fs]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.services.jruby.jruby-puppet-schemas :as jruby-schemas]
            [puppetlabs.services.jruby.puppet-environments :as puppet-env]
            [clojure.tools.logging :as log])
  (:import (java.util.concurrent LinkedBlockingDeque TimeUnit BlockingDeque)
           (java.util HashMap)
           (org.jruby RubyInstanceConfig$CompileMode CompatVersion)
           (org.jruby.embed ScriptingContainer LocalContextScope)
           (com.puppetlabs.puppetserver PuppetProfiler JRubyPuppet)
           (puppetlabs.services.jruby.jruby_puppet_schemas PoisonPill JRubyPuppetInstance)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Constants

(def default-borrow-timeout
  "Default timeout when borrowing instances from the JRuby pool in
   milliseconds. Current value is 1200000ms, or 20 minutes."
  1200000)

(def default-http-connect-timeout
  "The default number of milliseconds that the client will wait for a connection
  to be established. Currently set to 2 minutes."
  (* 2 60 1000))

(def default-http-socket-timeout
  "The default number of milliseconds that the client will allow for no data to
  be available on the socket. Currently set to 20 minutes."
  (* 20 60 1000))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Definitions

(def jruby-puppet-env
  "The environment variables that should be passed to the Puppet JRuby interpreters.
  We don't want them to read any ruby environment variables, like $GEM_HOME or
  $RUBY_LIB or anything like that, so pass it an empty environment map - except -
  Puppet needs HOME and PATH for facter resolution, so leave those."
  (select-keys (System/getenv) ["HOME" "PATH"]))

(def ruby-code-dir
  "The name of the directory containing the ruby code in this project.
  This directory lives under src/ruby/"
  "puppet-server-lib")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn default-pool-size
  "Calculate the default size of the JRuby pool, based on the number of cpus."
  [num-cpus]
  (->> (- num-cpus 1)
       (max 1)
       (min 4)))

(defn prep-scripting-container
  [scripting-container ruby-load-path gem-home]
  (doto scripting-container
    (.setLoadPaths (cons ruby-code-dir
                         (map fs/absolute-path ruby-load-path)))
    (.setCompatVersion (CompatVersion/RUBY1_9))
    (.setCompileMode RubyInstanceConfig$CompileMode/OFF)
    (.setEnvironment (merge {"GEM_HOME" gem-home
                             "JARS_NO_REQUIRE" "true"}
                            jruby-puppet-env))))

(defn empty-scripting-container
  "Creates a clean instance of `org.jruby.embed.ScriptingContainer` with no code loaded."
  [ruby-load-path gem-home]
  {:pre [(sequential? ruby-load-path)
         (every? string? ruby-load-path)
         (string? gem-home)]
   :post [(instance? ScriptingContainer %)]}
  (-> (ScriptingContainer. LocalContextScope/SINGLETHREAD)
      (prep-scripting-container ruby-load-path gem-home)))

(defn create-scripting-container
  "Creates an instance of `org.jruby.embed.ScriptingContainer` and loads up the
  puppet and facter code inside it."
  [ruby-load-path gem-home]
  {:pre [(sequential? ruby-load-path)
         (every? string? ruby-load-path)
         (string? gem-home)]
   :post [(instance? ScriptingContainer %)]}
  ;; for information on other legal values for `LocalContextScope`, there
  ;; is some documentation available in the JRuby source code; e.g.:
  ;; https://github.com/jruby/jruby/blob/1.7.11/core/src/main/java/org/jruby/embed/LocalContextScope.java#L58
  ;; I'm convinced that this is the safest and most reasonable value
  ;; to use here, but we could potentially explore optimizations in the future.
  (doto (empty-scripting-container ruby-load-path gem-home)
    (.runScriptlet "require 'puppet/server/master'")))

(schema/defn ^:always-validate
  create-pool-instance! :- JRubyPuppetInstance
  "Creates a new JRubyPuppet instance and adds it to the pool."
  [pool     :- jruby-schemas/pool-queue-type
   id       :- schema/Int
   config   :- jruby-schemas/JRubyPuppetConfig
   profiler :- (schema/maybe PuppetProfiler)]
  (let [{:keys [ruby-load-path gem-home master-conf-dir master-var-dir
                http-client-ssl-protocols http-client-cipher-suites
                http-client-connect-timeout-milliseconds
                http-client-idle-timeout-milliseconds]} config]
    (when-not ruby-load-path
      (throw (Exception.
               "JRuby service missing config value 'ruby-load-path'")))
    (let [scripting-container   (create-scripting-container ruby-load-path gem-home)
          env-registry          (puppet-env/environment-registry)
          ruby-puppet-class     (.runScriptlet scripting-container "Puppet::Server::Master")
          puppet-config         (HashMap.)
          puppet-server-config  (HashMap.)]
      (when master-conf-dir
        (.put puppet-config "confdir" (fs/absolute-path master-conf-dir)))
      (when master-var-dir
        (.put puppet-config "vardir" (fs/absolute-path master-var-dir)))

      (when http-client-ssl-protocols
        (.put puppet-server-config "ssl_protocols" (into-array String http-client-ssl-protocols)))
      (when http-client-cipher-suites
        (.put puppet-server-config "cipher_suites" (into-array String http-client-cipher-suites)))
      (.put puppet-server-config "profiler" profiler)
      (.put puppet-server-config "environment_registry" env-registry)
      (.put puppet-server-config "http_connect_timeout_milliseconds"
            http-client-connect-timeout-milliseconds)
      (.put puppet-server-config "http_idle_timeout_milliseconds"
            http-client-idle-timeout-milliseconds)

      (let [instance (jruby-schemas/map->JRubyPuppetInstance
                       {:pool                 pool
                        :id                   id
                        :state                (atom {:request-count 0})
                        :jruby-puppet         (.callMethod scripting-container
                                                           ruby-puppet-class
                                                           "new"
                                                           (into-array Object
                                                                       [puppet-config puppet-server-config])
                                                           JRubyPuppet)
                        :scripting-container  scripting-container
                        :environment-registry env-registry})]
        (.putLast pool instance)
        instance))))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  @(:pool-state context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRubyPuppet pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (:pool (get-pool-state context)))

(schema/defn ^:always-validate
  pool->vec :- [JRubyPuppetInstance]
  [context :- jruby-schemas/PoolContext]
  (-> (get-pool context)
      .iterator
      iterator-seq
      vec))

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRubyPuppet's."
  [size]
  {:post [(instance? jruby-schemas/pool-queue-type %)]}
  (LinkedBlockingDeque. size))

(defn verify-config-found!
  [config]
  (if (or (not (map? config))
          (empty? config))
    (throw (IllegalArgumentException. (str "No configuration data found.  Perhaps "
                                           "you did not specify the --config option?")))))

(schema/defn ^:always-validate
  create-pool-from-config :- jruby-schemas/PoolState
  "Create a new PoolData based on the config input."
  [{size :max-active-instances} :- jruby-schemas/JRubyPuppetConfig]
  (let [size (if size
               size
               (let [default-size (default-pool-size (ks/num-cpus))]
                 (log/warn (str "No configuration value found for jruby-puppet "
                                "max-active-instances; using default value of "
                                default-size ".  Please consider setting this "
                                "value explicitly in the jruby-puppet section "
                                "of your Puppet Server config files."))
                 default-size))]
    {:pool         (instantiate-free-pool size)
     :size         size}))

(schema/defn borrow-from-pool!* :- (schema/maybe jruby-schemas/JRubyPuppetInstanceOrRetry)
  "Given a borrow function and a pool, attempts to borrow a JRuby instance from a pool.
  If successful, updates the state information and returns the JRuby instance.
  Returns nil if the borrow function returns nil; throws an exception if
  the borrow function's return value indicates an error condition."
  [borrow-fn :- (schema/pred ifn?)
   pool :- jruby-schemas/pool-queue-type]
  (let [instance (borrow-fn pool)]
    (cond (instance? PoisonPill instance)
          (do
            (.putFirst pool instance)
            (throw (IllegalStateException.
                     "Unable to borrow JRuby instance from pool"
                     (:err instance))))

          (jruby-schemas/jruby-puppet-instance? instance)
          (do
            (swap! (:state instance) (fn [m] (update-in m [:request-count] inc)))
            instance)

          ((some-fn nil? jruby-schemas/retry-poison-pill?) instance)
          instance

          :else
          (throw (IllegalStateException.
                   (str "Borrowed unrecognized object from pool!: " instance))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  initialize-config :- jruby-schemas/JRubyPuppetConfig
  [config :- {schema/Keyword schema/Any}]
  (-> (get-in config [:jruby-puppet])
      (assoc :ruby-load-path (get-in config [:os-settings :ruby-load-path]))
      (assoc :http-client-ssl-protocols
             (get-in config [:http-client :ssl-protocols]))
      (assoc :http-client-cipher-suites
             (get-in config [:http-client :cipher-suites]))
      (assoc :http-client-connect-timeout-milliseconds
             (get-in config [:http-client :connect-timeout-milliseconds]
                            default-http-connect-timeout))
      (assoc :http-client-idle-timeout-milliseconds
             (get-in config [:http-client :idle-timeout-milliseconds]
                            default-http-socket-timeout))
      (update-in [:borrow-timeout] #(or % default-borrow-timeout))
      (update-in [:master-conf-dir] #(or % nil))
      (update-in [:master-var-dir] #(or % nil))
      (update-in [:max-active-instances] #(or % (default-pool-size (ks/num-cpus))))
      (update-in [:max-requests-per-instance] #(or % 0))))

(schema/defn ^:always-validate
  create-pool-context :- jruby-schemas/PoolContext
  "Creates a new JRubyPuppet pool context with an empty pool. Once the JRubyPuppet
  pool object has been created, it will need to be filled using `prime-pool!`."
  [config profiler]
  {:config     config
   :profiler   profiler
   :pool-state (atom (create-pool-from-config config))})

(schema/defn ^:always-validate
  free-instance-count
  "Returns the number of JRubyPuppet instances available in the pool."
  [pool :- jruby-schemas/pool-queue-type]
  {:post [(>= % 0)]}
  (.size pool))

(schema/defn ^:always-validate
  instance-state :- jruby-schemas/JRubyInstanceState
  "Get the state metadata for a JRubyPuppet instance."
  [jruby-puppet :- (schema/pred jruby-schemas/jruby-puppet-instance?)]
  @(:state jruby-puppet))

(schema/defn ^:always-validate
  mark-all-environments-expired!
  [context :- jruby-schemas/PoolContext]
  (doseq [jruby-instance (pool->vec context)]
    (-> jruby-instance
        :environment-registry
        puppet-env/mark-all-environments-expired!)))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyPuppetInstanceOrRetry
  "Borrows a JRubyPuppet interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool :- jruby-schemas/pool-queue-type]
  (let [borrow-fn #(.takeFirst %)]
    (borrow-from-pool!* borrow-fn pool)))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- (schema/maybe jruby-schemas/JRubyPuppetInstanceOrRetry)
  "Borrows a JRubyPuppet interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool :- jruby-schemas/pool-queue-type
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (let [borrow-fn #(.pollFirst % timeout TimeUnit/MILLISECONDS)]
    (borrow-from-pool!* borrow-fn pool)))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool."
  [instance :- jruby-schemas/JRubyPuppetInstanceOrRetry]
  (.putFirst (:pool instance) instance))
