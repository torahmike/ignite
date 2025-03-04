/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.index;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.cache.query.index.IndexProcessor;
import org.apache.ignite.internal.managers.indexing.IndexesRebuildTask;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.persistence.CacheDataRow;
import org.apache.ignite.internal.processors.query.schema.SchemaIndexCacheVisitorClosure;
import org.apache.ignite.internal.processors.query.schema.SchemaIndexOperationCancellationToken;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.lang.IgniteThrowableBiPredicate;
import org.apache.ignite.internal.util.lang.IgniteThrowableConsumer;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.jetbrains.annotations.Nullable;

import static java.util.Collections.emptyMap;

/**
 * Extension {@link IndexesRebuildTask} for the tests.
 */
class IndexesRebuildTaskEx extends IndexesRebuildTask {
    /**
     * Consumer for cache rows when rebuilding indexes on a node.
     * Mapping: Node name -> Cache name -> Consumer.
     */
    private static final Map<String, Map<String, IgniteThrowableConsumer<CacheDataRow>>> cacheRowConsumer =
        new ConcurrentHashMap<>();

    /**
     * A function that should run before preparing to rebuild the cache indexes on a node.
     * Mapping: Node name -> Cache name -> Function.
     */
    private static final Map<String, Map<String, Runnable>> cacheRebuildRunner = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override protected void startRebuild(
        GridCacheContext cctx,
        GridFutureAdapter<Void> rebuildIdxFut,
        SchemaIndexCacheVisitorClosure clo,
        SchemaIndexOperationCancellationToken cancel
    ) {
        super.startRebuild(cctx, rebuildIdxFut, new SchemaIndexCacheVisitorClosure() {
            /** {@inheritDoc} */
            @Override public void apply(CacheDataRow row) throws IgniteCheckedException {
                cacheRowConsumer.getOrDefault(nodeName(cctx), emptyMap())
                    .getOrDefault(cctx.name(), r -> { }).accept(row);

                clo.apply(row);
            }
        }, cancel);
    }

    /** {@inheritDoc} */
    @Override @Nullable public IgniteInternalFuture<?> rebuild(GridCacheContext cctx, boolean force) {
        cacheRebuildRunner.getOrDefault(nodeName(cctx), emptyMap()).getOrDefault(cctx.name(), () -> { }).run();

        return super.rebuild(cctx, force);
    }

    /**
     * Cleaning of internal structures. It is recommended to clean at
     * {@code GridCommonAbstractTest#beforeTest} and {@code GridCommonAbstractTest#afterTest}.
     *
     * @param nodeNamePrefix Prefix of node name ({@link GridKernalContext#igniteInstanceName()})
     *      for which internal structures will be removed, if {@code null} will be removed for all.
     *
     * @see GridCommonAbstractTest#getTestIgniteInstanceName()
     */
    static void clean(@Nullable String nodeNamePrefix) {
        if (nodeNamePrefix == null) {
            cacheRowConsumer.clear();
            cacheRebuildRunner.clear();
        }
        else {
            cacheRowConsumer.entrySet().removeIf(e -> e.getKey().startsWith(nodeNamePrefix));
            cacheRebuildRunner.entrySet().removeIf(e -> e.getKey().startsWith(nodeNamePrefix));
        }
    }

    /**
     * Set {@link IndexesRebuildTaskEx} to {@link IndexProcessor#idxRebuildCls} before starting the node.
     */
    static void prepareBeforeNodeStart() {
        IndexProcessor.idxRebuildCls = IndexesRebuildTaskEx.class;
    }

    /**
     * Registering a consumer for cache rows when rebuilding indexes on a node.
     *
     * @param nodeName The name of the node,
     *      the value of which will return {@link GridKernalContext#igniteInstanceName()}.
     * @param cacheName Cache name.
     * @param c Cache row consumer.
     *
     * @see #nodeName(GridKernalContext)
     * @see #nodeName(IgniteEx)
     * @see #nodeName(GridCacheContext)
     * @see GridCommonAbstractTest#getTestIgniteInstanceName(int)
     */
    static void addCacheRowConsumer(String nodeName, String cacheName, IgniteThrowableConsumer<CacheDataRow> c) {
        cacheRowConsumer.computeIfAbsent(nodeName, s -> new ConcurrentHashMap<>()).put(cacheName, c);
    }

    /**
     * Registering A function that should run before preparing to rebuild the cache indexes on a node.
     *
     * @param nodeName The name of the node,
     *      the value of which will return {@link GridKernalContext#igniteInstanceName()}.
     * @param cacheName Cache name.
     * @param r A function that should run before preparing to rebuild the cache indexes.
     *
     * @see #nodeName(GridKernalContext)
     * @see #nodeName(IgniteEx)
     * @see #nodeName(GridCacheContext)
     * @see GridCommonAbstractTest#getTestIgniteInstanceName(int)
     */
    static void addCacheRebuildRunner(String nodeName, String cacheName, Runnable r) {
        cacheRebuildRunner.computeIfAbsent(nodeName, s -> new ConcurrentHashMap<>()).put(cacheName, r);
    }

    /**
     * Getting local instance name of the node.
     *
     * @param cacheCtx Cache context.
     * @return Local instance name.
     */
    static String nodeName(GridCacheContext cacheCtx) {
        return nodeName(cacheCtx.kernalContext());
    }

    /**
     * Getting local instance name of the node.
     *
     * @param n Node.
     * @return Local instance name.
     */
    static String nodeName(IgniteEx n) {
        return nodeName(n.context());
    }

    /**
     * Getting local instance name of the node.
     *
     * @param kernalCtx Kernal context.
     * @return Local instance name.
     */
    static String nodeName(GridKernalContext kernalCtx) {
        return kernalCtx.igniteInstanceName();
    }

    /**
     * Consumer for stopping rebuilding indexes of cache.
     */
    static class StopRebuildIndexConsumer implements IgniteThrowableConsumer<CacheDataRow> {
        /** Future to indicate that the rebuilding indexes has begun. */
        final GridFutureAdapter<Void> startRebuildIdxFut = new GridFutureAdapter<>();

        /** Future to wait to continue rebuilding indexes. */
        final GridFutureAdapter<Void> finishRebuildIdxFut = new GridFutureAdapter<>();

        /** Counter of visits. */
        final AtomicLong visitCnt = new AtomicLong();

        /** The maximum time to wait finish future in milliseconds. */
        final long timeout;

        /**
         * Constructor.
         *
         * @param timeout The maximum time to wait finish future in milliseconds.
         */
        StopRebuildIndexConsumer(long timeout) {
            this.timeout = timeout;
        }

        /** {@inheritDoc} */
        @Override public void accept(CacheDataRow row) throws IgniteCheckedException {
            startRebuildIdxFut.onDone();

            visitCnt.incrementAndGet();

            finishRebuildIdxFut.get(timeout);
        }

        /**
         * Resetting internal futures.
         */
        void resetFutures() {
            startRebuildIdxFut.reset();
            finishRebuildIdxFut.reset();
        }
    }

    /**
     * Consumer breaking index rebuild for the cache.
     */
    static class BreakRebuildIndexConsumer extends StopRebuildIndexConsumer {
        /** Predicate for throwing an {@link IgniteCheckedException}. */
        final IgniteThrowableBiPredicate<BreakRebuildIndexConsumer, CacheDataRow> brakePred;

        /**
         * Constructor.
         *
         * @param timeout The maximum time to wait finish future in milliseconds.
         * @param brakePred Predicate for throwing an {@link IgniteCheckedException}.
         */
        BreakRebuildIndexConsumer(
            long timeout,
            IgniteThrowableBiPredicate<BreakRebuildIndexConsumer, CacheDataRow> brakePred
        ) {
            super(timeout);

            this.brakePred = brakePred;
        }

        /** {@inheritDoc} */
        @Override public void accept(CacheDataRow row) throws IgniteCheckedException {
            startRebuildIdxFut.onDone();

            finishRebuildIdxFut.get(timeout);

            visitCnt.incrementAndGet();

            if (brakePred.test(this, row))
                throw new IgniteCheckedException("From test.");
        }
    }
}
