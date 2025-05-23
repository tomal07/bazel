// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static java.util.concurrent.TimeUnit.MINUTES;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.collect.nestedset.ArtifactNestedSetKey;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.IncrementalInMemoryNodeEntry;
import com.google.devtools.build.skyframe.NodeEntry.LifecycleState;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * SkyframeFocuser is a minimizing optimizer (i.e. garbage collector) for the Skyframe graph, based
 * on a set of known inputs known as active directories, while ensuring correct incremental builds.
 *
 * <p>This is also a subclass of {@link AbstractQueueVisitor} to take advantage of highly
 * parallelizable operations over the Skyframe graph.
 */
public final class SkyframeFocuser extends AbstractQueueVisitor {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final int RDEP_WARNING_THRESHOLD = 10_000;
  private static final int DEP_WARNING_THRESHOLD = 10_000;

  private static boolean isVerificationSetKeyType(SkyKey k) {
    return k instanceof RootedPath || k instanceof DirectoryListingStateValue.Key;
  }

  // The in-memory Skyframe graph
  private final InMemoryGraph graph;

  // Can be null with --nouse_action_cache.
  @Nullable private final ActionCache actionCache;

  private SkyframeFocuser(InMemoryGraph graph, @Nullable ActionCache actionCache) {
    super(
        /* parallelism= */ Runtime.getRuntime().availableProcessors(),
        /* keepAliveTime= */ 2,
        MINUTES,
        ExceptionHandlingMode.FAIL_FAST,
        /* poolName= */ "skyframe-focuser",
        ErrorClassifier.DEFAULT);
    this.graph = graph;
    this.actionCache = actionCache;
  }

  /**
   * Minimize the Skyframe graph by traverse it to prune nodes and edges that are not necessary for
   * the build correctness of a active directories of files. The graph focusing algorithm pseudocode
   * is as follows.
   *
   * <ol>
   *   <li>Mark all the leafs and their transitive rdeps. For each marked node, also mark all their
   *       direct dependencies. An injectable function can also mark additional nodes reachable from
   *       the node itself.
   *   <li>For each marked node, remove all direct deps edges. Also remove all rdep edges unless
   *       they point to a rdep that should be kept. This creates the "flattened verification set".
   * </ol>
   *
   * @param graph the in-memory graph to operate on
   * @param roots the SkyKeys of the roots to be kept, i.e. the top level keys.
   * @param leafs the SkyKeys of the leafs to be kept. This is the "active directories".
   * @return the set of kept SkyKeys in the in-memory graph, categorized by deps and rdeps.
   */
  public static FocusResult focus(
      InMemoryGraph graph, @Nullable ActionCache actionCache, Set<SkyKey> roots, Set<SkyKey> leafs)
      throws InterruptedException {
    SkyframeFocuser focuser = new SkyframeFocuser(graph, actionCache);
    return focuser.run(roots, leafs);
  }

  /**
   * The result of running Skyfocus. The actual changes are done in place with the in-memory graph.
   *
   * @param roots the SkyKeys of the roots to be kept, i.e. the top level keys.
   * @param leafs the SkyKeys of the leafs to be kept. This is the "active directories".
   * @param deps the SkyKeys that are in the dependencies of all roots, and rdeps from the leafs.
   *     May contain transitive dependencies, in cases where certain functions use them without
   *     establishing a Skyframe dependency.
   * @param rdeps the SkyKeys that are in the reverse dependencies of the leafs.
   * @param verificationSet the SkyKeys that are in the transitive closure of the roots, but not in
   *     the active directories. These SkyKeys are also retained in the graph, because {@link
   *     FilesystemValueChecker} uses them to check for dirty keys to be invalidated on each new
   *     build.
   * @param rdepEdgesBefore The number of reverse edges in the visited nodes by Skyfocus (before
   *     removal).
   * @param rdepEdgesAfter The number of reverse edges in the visited nodes after Skyfocus completes
   *     (after removal).
   */
  public record FocusResult(
      ImmutableSet<SkyKey> roots,
      ImmutableSet<SkyKey> leafs,
      ImmutableSet<SkyKey> rdeps,
      ImmutableSet<SkyKey> deps,
      ImmutableSet<SkyKey> verificationSet,
      long rdepEdgesBefore,
      long rdepEdgesAfter) {}

  /**
   * NodeVisitor is parallelizable graph visitor that's applied transitively upwards from leafs to
   * the roots, while marking rdeps and all direct deps of those rdeps to be kept by {@link
   * SkyframeFocuser}.
   *
   * <p>It also collects the verification set in the downward transitive closure along the way. See
   * {@link CollectVerificationSet}.
   */
  private class SkyfocusNodeVisitor implements Runnable {

    // The SkyKey that this NodeVisitor is responsible for.
    private final SkyKey key;

    // Threadsafe set of keys that depend on this key. May be modified by multiple NodeVisitors
    // concurrently.
    private final Set<SkyKey> keptRdeps;

    // Threadsafe set of (mostly direct) dep keys that this key depends on. May be modified by
    // multiple NodeVisitors concurrently.
    private final Set<SkyKey> keptDeps;

    // Threadsafe set of *leaf* keys that this key depends on, but are external to the active
    // directories.
    private final Set<SkyKey> verificationSet;

    // Threadsafe set of keys that keeps track of the keys that have been visited while
    // constructing the verification set, so we do not visit the same subgraph more than once.
    // May be modified by multiple CollectVerificationSet visitors concurrently.
    private final Set<SkyKey> verificationSetSeen;

    SkyfocusNodeVisitor(
        SkyKey key,
        Set<SkyKey> keptRdeps,
        Set<SkyKey> keptDeps,
        Set<SkyKey> verificationSet,
        Set<SkyKey> verificationSetSeen) {
      this.key = key;
      this.keptRdeps = keptRdeps;
      this.keptDeps = keptDeps;
      this.verificationSet = verificationSet;
      this.verificationSetSeen = verificationSetSeen;
    }

    @Override
    public void run() {
      InMemoryNodeEntry nodeEntry = graph.getIfPresent(key);
      if (nodeEntry == null) {
        // Throwing may be too strong if the active directories is loosely defined, e.g. an entire
        // directory instead of a single file, and there are some files in the directory that
        // are not in the TC of the roots.
        //
        // TODO: b/312819241 - handle this gracefully without throwing.
        throw new IllegalStateException("nodeEntry not found for: " + key.getCanonicalName());
      }

      if (!nodeEntry.isDone()) {
        if (nodeEntry.getLifecycleState().equals(LifecycleState.CHECK_DEPENDENCIES)) {
          // When building a new top level target, the updated BUILD_ID precomputed value will
          // invalidate all of its reverse dependencies, and depending on what's being built,
          // some of them may remain in the CHECK_DEPENDENCIES state, and not done.
          //
          // For these, just ignore them and keep them in the graph, since they may be used for
          // a subsequent build.
          keptRdeps.remove(key);
          return;
        }

        // TODO: b/312819241 - handle this gracefully without throwing.
        throw new IllegalStateException("nodeEntry not done: " + key.getCanonicalName());
      }

      int rdepCount = 0;
      for (SkyKey rdep : nodeEntry.getReverseDepsForDoneEntry()) {
        rdepCount++;
        if (!keptRdeps.add(rdep)) {
          // Memoization. Already processed.
          continue;
        }

        // Queue a traversal up the graph. This will not create duplicate NodeVisitors on the
        // same rdep due to the atomic keptRdeps.add check above.
        execute(
            new SkyfocusNodeVisitor(
                rdep, keptRdeps, keptDeps, verificationSet, verificationSetSeen));
      }
      if (rdepCount > RDEP_WARNING_THRESHOLD) {
        logger.atWarning().log(
            "%s has %d rdeps, which is more than the threshold at %d.",
            key.getCanonicalName(), rdepCount, RDEP_WARNING_THRESHOLD);
      }

      int depCount = 0;
      for (SkyKey dep : nodeEntry.getDirectDeps()) {
        depCount++;
        if (!keptDeps.add(dep)) {
          // Memoization. Already processed.
          continue;
        }

        maybeCollectVerificationSet(dep);

        // This is necessary to keep the action inputs encapsulated by a NestedSet. Otherwise,
        // those inputs will be missing. ActionExecutionFunction#lookupInput allows getting a
        // transitive dep without adding a SkyframeDependency on it.
        if (dep instanceof ArtifactNestedSetKey artifactNestedSetKey) {
          for (Artifact a : artifactNestedSetKey.expandToArtifacts()) {
            SkyKey aKey = Artifact.key(a);
            if (keptDeps.add(aKey)) {
              maybeCollectVerificationSet(aKey);
            }
          }
        }
      }
      if (depCount > DEP_WARNING_THRESHOLD) {
        logger.atWarning().log(
            "%s has %d deps, which is more than the threshold at %d.",
            key.getCanonicalName(), depCount, DEP_WARNING_THRESHOLD);
      }
    }

    /**
     * Pre-check optimizations to avoid creating new CollectVerificationSet Runnables, instead of
     * returning early after creating and executing one.
     */
    void maybeCollectVerificationSet(SkyKey k) {
      if (keptRdeps.contains(k)) {
        // In the active directories reverse TC, already visited.
        return;
      }

      if (isVerificationSetKeyType(k)) {
        verificationSet.add(k);
        return;
      }

      if (!verificationSetSeen.add(k)) {
        // This contains all visited keys, so we don't visit the same key twice if
        // CollectVerificationSet was called from multiple rdeps on the same key.
        return;
      }

      execute(new CollectVerificationSet(k));
    }

    /**
     * The verification set keeps track when a file outside the active directories is changed,
     * because those builds will not be incrementally correct unless a reanalysis is done to restore
     * the Skyframe graph of those files.
     *
     * <p>Technically, CollectVerificationSet is applied downwards on the indirect dependencies of
     * the active directories's reverse transitive closure, and is responsible for collecting the
     * necessary leaf SkyKeys, except the active directories itself.
     *
     * <p>TODO: b/327545930 - make this run faster.
     */
    private class CollectVerificationSet implements Runnable {

      private final SkyKey key;

      CollectVerificationSet(SkyKey key) {
        this.key = key;
      }

      /**
       * Continue downward traversal. The collection is done in {@link
       * SkyfocusNodeVisitor#maybeCollectVerificationSet}.
       */
      @Override
      public void run() {
        InMemoryNodeEntry nodeEntry = graph.getIfPresent(key);
        Preconditions.checkNotNull(nodeEntry);
        nodeEntry.getDirectDeps().forEach(SkyfocusNodeVisitor.this::maybeCollectVerificationSet);
      }
    }
  }


  /** Entry point of the Skyframe garbage collection algorithm. */
  private FocusResult run(Set<SkyKey> roots, Set<SkyKey> leafs) throws InterruptedException {

    Set<SkyKey> keptDeps = Sets.newConcurrentHashSet();
    Set<SkyKey> keptRdeps = Sets.newConcurrentHashSet();
    Set<SkyKey> verificationSet = Sets.newConcurrentHashSet();

    // All leafs are automatically considered as rdeps.
    keptRdeps.addAll(leafs);

    // All roots are automatically considered as deps.
    //
    // Some roots are re-evaluated on every build. These roots may not be in the reverse TC
    // of leafs (active directories), but may influence how the active directories is evaluated
    // (e.g. platform
    // mapping). If we remove them from the graph, those keys may be re-evaluated anyway (along with
    // their TC) on subsequent invocations, leading to wasted compute and RAM.
    // The exercise of ensuring which roots should be kept is left to the caller of
    // this function, but we ensure that all specified ones are kept here.
    keptDeps.addAll(roots);

    try (SilentCloseable c = Profiler.instance().profile("focus.mark")) {
      Set<SkyKey> verificationSetSeen = Sets.newConcurrentHashSet();
      // Start traversal from leafs.
      for (SkyKey leaf : leafs) {
        execute(
            new SkyfocusNodeVisitor(
                leaf, keptRdeps, keptDeps, verificationSet, verificationSetSeen));
      }
      awaitQuiescenceWithoutShutdown(true);
    }

    // Keep the rdeps transitive closure from leafs distinct from the deps.
    keptDeps.removeAll(keptRdeps);

    // Ensure that the verification set doesn't contain any direct deps to build the
    // active directories.
    verificationSet.removeAll(keptDeps);

    AtomicLong rdepEdgesBefore = new AtomicLong();
    AtomicLong rdepEdgesAfter = new AtomicLong();

    try (SilentCloseable c = Profiler.instance().profile("focus.sweep")) {
      graph.parallelForEach(
          inMemoryNodeEntry -> {
            SkyKey key = inMemoryNodeEntry.getKey();

            if (keptRdeps.contains(key)) {
              return;
            }

            if (keptDeps.contains(key)) {
              IncrementalInMemoryNodeEntry incrementalInMemoryNodeEntry =
                  (IncrementalInMemoryNodeEntry) inMemoryNodeEntry;

              // No need to keep the direct deps edges of existing deps. For example:
              //
              //    B
              //  / |\
              // A C  \
              //    \ |
              //     D
              //
              // B is the root, and A is the only leaf. We can throw out the CD edge, even
              // though both C and D are still used by B. This is because no changes are expected to
              // C and D, so it's unnecessary to maintain the edges.
              incrementalInMemoryNodeEntry.clearDirectDepsForSkyfocus();

              // No need to keep the rdep edges of the deps if they do not point to an rdep
              // reachable (hence, dirty-able) by the active directories.
              //
              // This accounts for nearly 5% of 9+GB retained heap on a large server build.
              Collection<SkyKey> existingRdeps =
                  incrementalInMemoryNodeEntry.getReverseDepsForDoneEntry();
              rdepEdgesBefore.getAndAdd(existingRdeps.size());
              int rdepEdgesKept = 0;
              for (SkyKey rdep : existingRdeps) {
                if (keptRdeps.contains(rdep)) {
                  rdepEdgesKept++;
                } else {
                  incrementalInMemoryNodeEntry.removeReverseDep(rdep);
                }
              }
              rdepEdgesAfter.getAndAdd(rdepEdgesKept);

              // This calls ReverseDepsUtility.consolidateData().
              incrementalInMemoryNodeEntry.consolidateReverseDeps();

              return;
            }

            if (verificationSet.contains(key)) {
              // TODO: b/327545930 - fsvc supports checking keys with missing values in the graph
              // using `FileSystemValueCheckerInferringAncestors#visitUnknownEntry`, so perhaps we
              // could drop the nodes here, but that doesn't (yet) work with LocalDiffAwareness.
              //
              // For now, keep the nodes in the verification set because the
              // fsvc#getDirtyKeys relies on their existence in the graph to check for
              // dirty keys to invalidate.
              //
              // Also remove all rdep edges from the verification set and make it flat.
              Collection<SkyKey> rdeps = inMemoryNodeEntry.getReverseDepsForDoneEntry();
              rdepEdgesBefore.getAndAdd(rdeps.size());
              for (SkyKey rdep : rdeps) {
                inMemoryNodeEntry.removeReverseDep(rdep);
              }

              return;
            }

            if (!inMemoryNodeEntry.isDone()) {
              // Don't remove undone nodes -- these are nodes that were already in the graph,
              // but invalidated and not evaluated in this current invocation.
              return;
            }

            if (actionCache != null
                && inMemoryNodeEntry.getValue() instanceof ActionLookupValue alv) {
              for (ActionAnalysisMetadata a : alv.getActions()) {
                for (Artifact output : a.getOutputs()) {
                  actionCache.remove(output.getExecPathString());
                }
              }
            }

            graph.remove(key);
          });

      graph.shrinkNodeMap();

      awaitQuiescence(true); // and shut down the ExecutorService.
    }

    return new FocusResult(
        ImmutableSet.copyOf(roots),
        ImmutableSet.copyOf(leafs),
        ImmutableSet.copyOf(keptRdeps),
        ImmutableSet.copyOf(keptDeps),
        ImmutableSet.copyOf(verificationSet),
        rdepEdgesBefore.get(),
        rdepEdgesAfter.get());
  }
}
