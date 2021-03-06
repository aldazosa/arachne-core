(ns arachne.core.module
  "The namespace used for defining and loading Arachne modules"
  (:refer-clojure :exclude [load sort])
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.spec :as spec]
            [loom.graph :as loom]
            [loom.alg :as loom-alg]
            [arachne.core.util :as u]
            [arachne.core.module.specs]))

(u/deferror ::missing-module
  "Module :module-name declared a dependency on modules :missing, which were not found on the classpath. Make sure you have configured your project dependencies correctly.")

(u/deferror ::dup-definition
  "Found two definitions for module named :dup, and the definitions were not the same. Verify you have not accidentally included two different versions of the same module.")

(defn- validate-dependencies
  "Given a set of module definitions, throw an exception if all dependencies are
  not present, given a seq of module definitions. Also checks that a module is
  not defined twice, in different ways"
  [definitions]
  (let [names (map :arachne.module/name definitions)
        dup (first (keys (filter #(< 1 (second %)) (frequencies names))))]
    (when dup
      (let [dup-defs (filter #(= dup (:arachne.module/name %)) definitions)]
        (u/error ::dup-definition {:dup dup
                                   :definitions dup-defs})))
    (doseq [{:keys [:arachne.module/name :arachne.module/dependencies]
             :as definition} definitions]
      (let [missing (set/difference (set dependencies) (set names))]
        (when-not (empty? missing)
          (u/error ::missing-module {:module-name name
                                     :module definition
                                     :missing missing}))))))

(defn- as-loom-graph
  "Convert a seq of module definitions to a loom graph"
  [definitions]
  (let [by-name (zipmap (map :arachne.module/name definitions) definitions)
        graph (zipmap (keys by-name) (map :arachne.module/dependencies
                                       (vals by-name)))]
    (loom/digraph graph)))

(u/deferror ::circular-module-deps
  "Could not sort modules because module dependencies contains circular references")

(defn- topological-sort
  "Given a set of module defintions, return a seq of module definitions in
  depenency order. Throws an exception if a dependency is missing, or if there
  are cycles in the module graph."
  [definitions]
  (validate-dependencies definitions)
  (let [by-name (zipmap (map :arachne.module/name definitions) definitions)
        loom-graph (as-loom-graph definitions)
        sorted (loom-alg/topsort loom-graph)]
    (when (nil? sorted)
      (u/error ::circular-module-deps {:definitions definitions
                                       :graph loom-graph}))
    (map by-name (reverse sorted))))

(u/deferror ::module-def-did-not-conform
  "Module definition with name :name did not conform to spec: :spec")

(defn- validate-definition
  "Throw a friendly exception if the given module definition does not conform to
  a the module spec"
  [def]
  (when-not (spec/valid? :arachne.core.module.specs/definition def)
    (let [explain-str (spec/explain-str :arachne.core.module.specs/definition def)]
      (u/error ::module-def-did-not-conform
        {:name (:arachne.module/name def)
         :explain-str explain-str
         :spec :arachne.core.module.specs/definition
         :definition def})))
  def)

(defn- discover
  "Return a set of module definitions present in the classpath (defined in
  `arachne-modules.edn`
  files)"
  []
  (->> "arachne-modules.edn"
    (.getResources (.getContextClassLoader (Thread/currentThread)))
    enumeration-seq
    (map slurp)
    (map edn/read-string)
    (apply concat)
    (map validate-definition)
    set))

(u/deferror ::missing-roots
  "Modules were specified which could not be found on the classpath: :missing-roots")

(defn- validate-missing-roots
  "Throw an error if a root is required that is not present in the given set of
  definitions"
  [roots definitions]
  (let [definition-names (set (map :arachne.module/name definitions))
        missing-roots (set/difference (set roots) definition-names)]
    (when-not (empty? missing-roots)
      (u/error ::missing-roots {:missing-roots missing-roots}))))

(defn- reachable
  "Given a seq of module definitions and a set of root modules, return only
  module definitions reachable from one of the root modules"
  [definitions root-modules]
  (validate-missing-roots root-modules definitions)
  (let [graph (as-loom-graph definitions)
        reachable (set (mapcat #(loom-alg/pre-traverse graph %) root-modules))]
    (filter #(reachable (:arachne.module/name %)) definitions)))

(defn load
  "Return a seq of validated module definitions for all modules on the classpath
  that are required by the specified modules, in dependency order."
  [root-modules]
  (u/validate-args `load root-modules)
  (->> (reachable (discover) root-modules)
       (topological-sort)))

(defn schema
  "Given a module definition, invoke the module's schema function."
  [definition]
  (u/validate-args `schema definition)
  (let [v (u/require-and-resolve (:arachne.module/schema definition))]
    (@v)))

(defn configure
  "Given a module and a configuration value, invoke the module's configure
  function."
  [definition cfg]
  (u/validate-args `configure definition cfg)
  (let [v (u/require-and-resolve (:arachne.module/configure definition))]
    (@v cfg)))
