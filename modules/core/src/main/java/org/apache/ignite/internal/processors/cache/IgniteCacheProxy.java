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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.CacheManager;
import org.apache.ignite.cache.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.cache.query.*;
import org.apache.ignite.internal.processors.query.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.mxbean.*;
import org.apache.ignite.plugin.security.*;
import org.apache.ignite.spi.discovery.tcp.internal.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.configuration.*;
import javax.cache.expiry.*;
import javax.cache.integration.*;
import javax.cache.processor.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.locks.*;

/**
 * Cache proxy.
 */
public class IgniteCacheProxy<K, V> extends AsyncSupportAdapter<IgniteCache<K, V>>
    implements IgniteCache<K, V>, Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** */
    private static final IgniteBiPredicate ACCEPT_ALL = new IgniteBiPredicate() {
        @Override public boolean apply(Object k, Object v) {
            return true;
        }
    };

    /** Context. */
    private GridCacheContext<K, V> ctx;

    /** Gateway. */
    private GridCacheGateway<K, V> gate;

    /** Delegate. */
    @GridToStringInclude
    private IgniteInternalCache<K, V> delegate;

    /** Projection. */
    private CacheOperationContext prjCtx;

    /** */
    @GridToStringExclude
    private GridCacheProxyImpl<K, V> legacyProxy;

    /** */
    @GridToStringExclude
    private CacheManager cacheMgr;

    /**
     * Empty constructor required for {@link Externalizable}.
     */
    public IgniteCacheProxy() {
        // No-op.
    }

    /**
     * @param ctx Context.
     * @param delegate Delegate.
     * @param prjCtx Projection.
     * @param async Async support flag.
     */
    public IgniteCacheProxy(
        GridCacheContext<K, V> ctx,
        IgniteInternalCache<K, V> delegate,
        CacheOperationContext prjCtx,
        boolean async
    ) {
        super(async);

        assert ctx != null;
        assert delegate != null;

        this.ctx = ctx;
        this.delegate = delegate;
        this.prjCtx = prjCtx;

        if (prjCtx != null && prjCtx.isKeepPortable())
            delegate.setQueryKeepPortable();

        gate = ctx.gate();

        legacyProxy = new GridCacheProxyImpl<>(ctx, delegate, prjCtx);
    }

    /**
     * @return Context.
     */
    public GridCacheContext<K, V> context() {
        return ctx;
    }

    /**
     * @return Gateway.
     */
    public GridCacheGateway<K, V> gate() {
        return gate;
    }

    /** {@inheritDoc} */
    @Override public CacheMetrics metrics() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return ctx.cache().metrics();
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public CacheMetrics metrics(ClusterGroup grp) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            List<CacheMetrics> metrics = new ArrayList<>(grp.nodes().size());

            for (ClusterNode node : grp.nodes()) {
                Map<Integer, CacheMetrics> nodeCacheMetrics = ((TcpDiscoveryNode)node).cacheMetrics();

                if (nodeCacheMetrics != null) {
                    CacheMetrics e = nodeCacheMetrics.get(context().cacheId());

                    if (e != null)
                        metrics.add(e);
                }
            }

            return new CacheMetricsSnapshot(ctx.cache().metrics(), metrics);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public CacheMetricsMXBean mxBean() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return ctx.cache().mxBean();
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
        CacheConfiguration cfg = ctx.config();

        if (!clazz.isAssignableFrom(cfg.getClass()))
            throw new IllegalArgumentException();

        return clazz.cast(cfg);
    }

    /** {@inheritDoc} */
    @Nullable @Override public Entry<K, V> randomEntry() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return ctx.cache().randomEntry();
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public IgniteCache<K, V> withExpiryPolicy(ExpiryPolicy plc) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            CacheOperationContext prj0 = prjCtx.withExpiryPolicy(plc);

            return new IgniteCacheProxy<>(ctx, delegate, prj0, isAsync());
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public IgniteCache<K, V> withSkipStore() {
        return skipStore();
    }

    /** {@inheritDoc} */
    @Override public void loadCache(@Nullable IgniteBiPredicate<K, V> p, @Nullable Object... args) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync())
                    setFuture(ctx.cache().globalLoadCacheAsync(p, args));
                else
                    ctx.cache().globalLoadCache(p, args);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public void localLoadCache(@Nullable IgniteBiPredicate<K, V> p, @Nullable Object... args) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync())
                    setFuture(delegate.localLoadCacheAsync(p, args));
                else
                    delegate.localLoadCache(p, args);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public V getAndPutIfAbsent(K key, V val) throws CacheException {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.getAndPutIfAbsentAsync(key, val));

                    return null;
                }
                else
                    return delegate.getAndPutIfAbsent(key, val);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public Lock lock(K key) throws CacheException {
        return lockAll(Collections.singleton(key));
    }

    /** {@inheritDoc} */
    @Override public Lock lockAll(final Collection<? extends K> keys) {
        return new CacheLockImpl<>(gate, delegate, prjCtx, keys);
    }

    /** {@inheritDoc} */
    @Override public boolean isLocalLocked(K key, boolean byCurrThread) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return byCurrThread ? delegate.isLockedByThread(key) : delegate.isLocked(key);
        }
        finally {
            gate.leave(prev);
        }
    }

    /**
     * @param filter Filter.
     * @param grp Optional cluster group.
     * @return Cursor.
     */
    @SuppressWarnings("unchecked")
    private QueryCursor<Entry<K,V>> query(Query filter, @Nullable ClusterGroup grp) {
        final CacheQuery<Map.Entry<K,V>> qry;
        final CacheQueryFuture<Map.Entry<K,V>> fut;

        if (filter instanceof ScanQuery) {
            IgniteBiPredicate<K, V> p = ((ScanQuery)filter).getFilter();

            qry = delegate.queries().createScanQuery(p != null ? p : ACCEPT_ALL);

            if (grp != null)
                qry.projection(grp);

            fut = qry.execute();
        }
        else if (filter instanceof TextQuery) {
            TextQuery p = (TextQuery)filter;

            qry = delegate.queries().createFullTextQuery(p.getType(), p.getText());

            if (grp != null)
                qry.projection(grp);

            fut = qry.execute();
        }
        else if (filter instanceof SpiQuery) {
            qry = ctx.queries().createSpiQuery(prjCtx != null ? prjCtx.isKeepPortable() : false);

            if (grp != null)
                qry.projection(grp);

            fut = qry.execute(((SpiQuery)filter).getArgs());
        }
        else {
            if (filter instanceof SqlFieldsQuery)
                throw new CacheException("Use methods 'queryFields' and 'localQueryFields' for " +
                    SqlFieldsQuery.class.getSimpleName() + ".");

            throw new CacheException("Unsupported query type: " + filter);
        }

        return new QueryCursorImpl<>(new GridCloseableIteratorAdapter<Entry<K,V>>() {
            /** */
            private Map.Entry<K,V> cur;

            @Override protected Entry<K,V> onNext() throws IgniteCheckedException {
                if (!onHasNext())
                    throw new NoSuchElementException();

                Map.Entry<K,V> e = cur;

                cur = null;

                return new CacheEntryImpl<>(e.getKey(), e.getValue());
            }

            @Override protected boolean onHasNext() throws IgniteCheckedException {
                return cur != null || (cur = fut.next()) != null;
            }

            @Override protected void onClose() throws IgniteCheckedException {
                fut.cancel();
            }
        });
    }

    /**
     * @param local Enforce local.
     * @return Local node cluster group.
     */
    private ClusterGroup projection(boolean local) {
        if (local || ctx.isLocal() || isReplicatedDataNode())
            return ctx.kernalContext().grid().cluster().forLocal();

        if (ctx.isReplicated())
            return ctx.kernalContext().grid().cluster().forDataNodes(ctx.name()).forRandom();

        return null;
    }

    /**
     * Executes continuous query.
     *
     * @param qry Query.
     * @param loc Local flag.
     * @return Initial iteration cursor.
     */
    @SuppressWarnings("unchecked")
    private QueryCursor<Entry<K, V>> queryContinuous(ContinuousQuery qry, boolean loc) {
        if (qry.getInitialQuery() instanceof ContinuousQuery)
            throw new IgniteException("Initial predicate for continuous query can't be an instance of another " +
                "continuous query. Use SCAN or SQL query for initial iteration.");

        if (qry.getLocalListener() == null)
            throw new IgniteException("Mandatory local listener is not set for the query: " + qry);

        try {
            final UUID routineId = ctx.continuousQueries().executeQuery(
                qry.getLocalListener(),
                qry.getRemoteFilter(),
                qry.getPageSize(),
                qry.getTimeInterval(),
                qry.isAutoUnsubscribe(),
                loc ? ctx.grid().cluster().forLocal() : null);

            final QueryCursor<Cache.Entry<K, V>> cur =
                qry.getInitialQuery() != null ? query(qry.getInitialQuery()) : null;

            return new QueryCursor<Cache.Entry<K, V>>() {
                @Override public Iterator<Cache.Entry<K, V>> iterator() {
                    return cur != null ? cur.iterator() : new GridEmptyIterator<Cache.Entry<K, V>>();
                }

                @Override public List<Cache.Entry<K, V>> getAll() {
                    return cur != null ? cur.getAll() : Collections.<Cache.Entry<K, V>>emptyList();
                }

                @Override public void close() {
                    if (cur != null)
                        cur.close();

                    try {
                        ctx.kernalContext().continuous().stopRoutine(routineId).get();
                    }
                    catch (IgniteCheckedException e) {
                        throw U.convertException(e);
                    }
                }
            };
        }
        catch (IgniteCheckedException e) {
            throw U.convertException(e);
        }
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <R> QueryCursor<R> query(Query<R> qry) {
        A.notNull(qry, "qry");

        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            ctx.checkSecurity(SecurityPermission.CACHE_READ);

            validate(qry);

            if (qry instanceof ContinuousQuery)
                return (QueryCursor<R>)queryContinuous((ContinuousQuery<K, V>)qry, qry.isLocal());

            if (qry instanceof SqlQuery) {
                SqlQuery p = (SqlQuery)qry;

                if (isReplicatedDataNode() || ctx.isLocal() || qry.isLocal())
                    return (QueryCursor<R>)new QueryCursorImpl<>(ctx.kernalContext().query().<K, V>queryLocal(ctx, p));

                return (QueryCursor<R>)ctx.kernalContext().query().queryTwoStep(ctx, p);
            }

            if (qry instanceof SqlFieldsQuery) {
                SqlFieldsQuery p = (SqlFieldsQuery)qry;

                if (isReplicatedDataNode() || ctx.isLocal() || qry.isLocal())
                    return (QueryCursor<R>)ctx.kernalContext().query().queryLocalFields(ctx, p);

                return (QueryCursor<R>)ctx.kernalContext().query().queryTwoStep(ctx, p);
            }

            return (QueryCursor<R>)query(qry, projection(qry.isLocal()));
        }
        catch (Exception e) {
            if (e instanceof CacheException)
                throw e;

            throw new CacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /**
     * @return {@code true} If this is a replicated cache and we are on a data node.
     */
    private boolean isReplicatedDataNode() {
        return ctx.isReplicated() && ctx.affinityNode();
    }

    /**
     * Checks query.
     *
     * @param qry Query
     * @throws CacheException If query indexing disabled for sql query.
     */
    private void validate(Query qry) {
        if (!GridQueryProcessor.isEnabled(ctx.config()) && !(qry instanceof ScanQuery) &&
            !(qry instanceof ContinuousQuery))
            throw new CacheException("Indexing is disabled for cache: " + ctx.cache().name());
    }

    /** {@inheritDoc} */
    @Override public Iterable<Entry<K, V>> localEntries(CachePeekMode... peekModes) throws CacheException {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return delegate.localEntries(peekModes);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public QueryMetrics queryMetrics() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return delegate.context().queries().metrics();
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void localEvict(Collection<? extends K> keys) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            delegate.evictAll(keys);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public V localPeek(K key, CachePeekMode... peekModes) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return delegate.localPeek(key, peekModes, null);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void localPromote(Set<? extends K> keys) throws CacheException {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                delegate.promoteAll(keys);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public int size(CachePeekMode... peekModes) throws CacheException {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            if (isAsync()) {
                setFuture(delegate.sizeAsync(peekModes));

                return 0;
            }
            else
                return delegate.size(peekModes);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public int localSize(CachePeekMode... peekModes) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return delegate.localSize(peekModes);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public V get(K key) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            boolean deserializePortables = prjCtx == null ? false : prjCtx.deserializePortables();

            try {
                if (isAsync()) {
                    setFuture(delegate.getAsync(key, deserializePortables));

                    return null;
                }
                else
                    return delegate.get(key, deserializePortables);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public Map<K, V> getAll(Set<? extends K> keys) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            boolean deserializePortables = prjCtx == null ? false : prjCtx.deserializePortables();

            try {
                if (isAsync()) {
                    setFuture(delegate.getAllAsync(keys, deserializePortables));

                    return null;
                }
                else
                    return delegate.getAll(keys, deserializePortables);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /**
     * @param keys Keys.
     * @return Values map.
     */
    public Map<K, V> getAll(Collection<? extends K> keys) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            boolean deserializePortables = prjCtx == null ? false : prjCtx.deserializePortables();

            try {
                if (isAsync()) {
                    setFuture(delegate.getAllAsync(keys, deserializePortables));

                    return null;
                }
                else
                    return delegate.getAll(keys, deserializePortables);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /**
     * Gets entry set containing internal entries.
     *
     * @param filter Filter.
     * @return Entry set.
     */
    public Set<Entry<K, V>> entrySetx(CacheEntryPredicate... filter) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return delegate.entrySetx(filter);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean containsKey(K key) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            if (isAsync()) {
                setFuture(delegate.containsKeyAsync(key));

                return false;
            }
            else
                return delegate.containsKey(key);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean containsKeys(Set<? extends K> keys) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            if (isAsync()) {
                setFuture(delegate.containsKeysAsync(keys));

                return false;
            }
            else
                return delegate.containsKeys(keys);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void loadAll(
        Set<? extends K> keys,
        boolean replaceExisting,
        @Nullable final CompletionListener completionLsnr
    ) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            IgniteInternalFuture<?> fut = ctx.cache().loadAll(keys, replaceExisting);

            if (completionLsnr != null) {
                fut.listen(new CI1<IgniteInternalFuture<?>>() {
                    @Override public void apply(IgniteInternalFuture<?> fut) {
                        try {
                            fut.get();

                            completionLsnr.onCompletion();
                        }
                        catch (IgniteCheckedException e) {
                            completionLsnr.onException(cacheException(e));
                        }
                    }
                });
            }
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void put(K key, V val) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync())
                    setFuture(delegate.putAsync(key, val));
                else
                    delegate.put(key, val);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public V getAndPut(K key, V val) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.getAndPutAsync(key, val));

                    return null;
                }
                else
                    return delegate.getAndPut(key, val);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public void putAll(Map<? extends K, ? extends V> map) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync())
                    setFuture(delegate.putAllAsync(map));
                else
                    delegate.putAll(map);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean putIfAbsent(K key, V val) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.putIfAbsentAsync(key, val));

                    return false;
                }
                else
                    return delegate.putIfAbsent(key, val);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean remove(K key) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.removeAsync(key));

                    return false;
                }
                else
                    return delegate.remove(key);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean remove(K key, V oldVal) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.removeAsync(key, oldVal));

                    return false;
                }
                else
                    return delegate.remove(key, oldVal);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public V getAndRemove(K key) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.getAndRemoveAsync(key));

                    return null;
                }
                else
                    return delegate.getAndRemove(key);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean replace(K key, V oldVal, V newVal) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.replaceAsync(key, oldVal, newVal));

                    return false;
                }
                else
                    return delegate.replace(key, oldVal, newVal);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean replace(K key, V val) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.replaceAsync(key, val));

                    return false;
                }
                else
                    return delegate.replace(key, val);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public V getAndReplace(K key, V val) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.getAndReplaceAsync(key, val));

                    return null;
                }
                else
                    return delegate.getAndReplace(key, val);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public void removeAll(Set<? extends K> keys) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync())
                    setFuture(delegate.removeAllAsync(keys));
                else
                    delegate.removeAll(keys);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public void removeAll() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            if (isAsync())
                setFuture(delegate.removeAllAsync());
            else
                delegate.removeAll();
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void clear(K key) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            if (isAsync())
                setFuture(delegate.clearAsync(key));
            else
                delegate.clear(key);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void clearAll(Set<? extends K> keys) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            if (isAsync())
                setFuture(delegate.clearAsync(keys));
            else
                delegate.clearAll(keys);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void clear() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            if (isAsync())
                setFuture(delegate.clearAsync());
            else
                delegate.clear();
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void localClear(K key) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            delegate.clearLocally(key);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void localClearAll(Set<? extends K> keys) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            for (K key : keys)
                delegate.clearLocally(key);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... args)
        throws EntryProcessorException {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    IgniteInternalFuture<EntryProcessorResult<T>> fut = delegate.invokeAsync(key, entryProcessor, args);

                    IgniteInternalFuture<T> fut0 = fut.chain(new CX1<IgniteInternalFuture<EntryProcessorResult<T>>, T>() {
                        @Override public T applyx(IgniteInternalFuture<EntryProcessorResult<T>> fut)
                            throws IgniteCheckedException {
                            EntryProcessorResult<T> res = fut.get();

                            return res != null ? res.get() : null;
                        }
                    });

                    setFuture(fut0);

                    return null;
                }
                else {
                    EntryProcessorResult<T> res = delegate.invoke(key, entryProcessor, args);

                    return res != null ? res.get() : null;
                }
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public <T> T invoke(K key, CacheEntryProcessor<K, V, T> entryProcessor, Object... args)
        throws EntryProcessorException {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    IgniteInternalFuture<EntryProcessorResult<T>> fut = delegate.invokeAsync(key, entryProcessor, args);

                    IgniteInternalFuture<T> fut0 = fut.chain(new CX1<IgniteInternalFuture<EntryProcessorResult<T>>, T>() {
                        @Override public T applyx(IgniteInternalFuture<EntryProcessorResult<T>> fut)
                            throws IgniteCheckedException {
                            EntryProcessorResult<T> res = fut.get();

                            return res != null ? res.get() : null;
                        }
                    });

                    setFuture(fut0);

                    return null;
                }
                else {
                    EntryProcessorResult<T> res = delegate.invoke(key, entryProcessor, args);

                    return res != null ? res.get() : null;
                }
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
                                                                   EntryProcessor<K, V, T> entryProcessor,
                                                                   Object... args) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.invokeAllAsync(keys, entryProcessor, args));

                    return null;
                }
                else
                    return delegate.invokeAll(keys, entryProcessor, args);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
        CacheEntryProcessor<K, V, T> entryProcessor,
        Object... args) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.invokeAllAsync(keys, entryProcessor, args));

                    return null;
                }
                else
                    return delegate.invokeAll(keys, entryProcessor, args);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public <T> Map<K, EntryProcessorResult<T>> invokeAll(
        Map<? extends K, ? extends EntryProcessor<K, V, T>> map,
        Object... args) {
        try {
            CacheOperationContext prev = gate.enter(prjCtx);

            try {
                if (isAsync()) {
                    setFuture(delegate.invokeAllAsync(map, args));

                    return null;
                }
                else
                    return delegate.invokeAll(map, args);
            }
            finally {
                gate.leave(prev);
            }
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public String getName() {
        return delegate.name();
    }

    /** {@inheritDoc} */
    @Override public CacheManager getCacheManager() {
        return cacheMgr;
    }

    /**
     * @param cacheMgr Cache manager.
     */
    public void setCacheManager(CacheManager cacheMgr) {
        this.cacheMgr = cacheMgr;
    }

    /** {@inheritDoc} */
    @Override public void close() {
        if (!gate.enterIfNotClosed())
            return;

        IgniteInternalFuture<?> fut;

        try {
            fut = ctx.kernalContext().cache().dynamicStopCache(ctx.name());
        }
        finally {
            gate.leave();
        }

        try {
            fut.get();
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public boolean isClosed() {
        if (!gate.enterIfNotClosed())
            return true;

        try {
            return ctx.kernalContext().cache().context().closed(ctx);
        }
        finally {
            gate.leave();
        }
    }

    /**
     *
     */
    public IgniteInternalCache delegate() {
        return delegate;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(getClass()))
            return (T)this;
        else if (clazz.isAssignableFrom(IgniteEx.class))
            return (T)ctx.grid();
        else if (clazz.isAssignableFrom(legacyProxy.getClass()))
            return (T)legacyProxy;

        throw new IllegalArgumentException("Unwrapping to class is not supported: " + clazz);
    }

    /** {@inheritDoc} */
    @Override public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> lsnrCfg) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            ctx.continuousQueries().executeJCacheQuery(lsnrCfg, false);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> lsnrCfg) {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            ctx.continuousQueries().cancelJCacheQuery(lsnrCfg);
        }
        catch (IgniteCheckedException e) {
            throw cacheException(e);
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override public Iterator<Cache.Entry<K, V>> iterator() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            return ctx.cache().igniteIterator();
        }
        finally {
            gate.leave(prev);
        }
    }

    /** {@inheritDoc} */
    @Override protected IgniteCache<K, V> createAsyncInstance() {
        return new IgniteCacheProxy<>(ctx, delegate, prjCtx, true);
    }

    /**
     * Creates projection that will operate with portable objects. <p> Projection returned by this method will force
     * cache not to deserialize portable objects, so keys and values will be returned from cache API methods without
     * changes. Therefore, signature of the projection can contain only following types: <ul> <li>{@code PortableObject}
     * for portable classes</li> <li>All primitives (byte, int, ...) and there boxed versions (Byte, Integer, ...)</li>
     * <li>Arrays of primitives (byte[], int[], ...)</li> <li>{@link String} and array of {@link String}s</li>
     * <li>{@link UUID} and array of {@link UUID}s</li> <li>{@link Date} and array of {@link Date}s</li> <li>{@link
     * java.sql.Timestamp} and array of {@link java.sql.Timestamp}s</li> <li>Enums and array of enums</li> <li> Maps,
     * collections and array of objects (but objects inside them will still be converted if they are portable) </li>
     * </ul> <p> For example, if you use {@link Integer} as a key and {@code Value} class as a value (which will be
     * stored in portable format), you should acquire following projection to avoid deserialization:
     * <pre>
     * IgniteInternalCache<Integer, GridPortableObject> prj = cache.keepPortable();
     *
     * // Value is not deserialized and returned in portable format.
     * GridPortableObject po = prj.get(1);
     * </pre>
     * <p> Note that this method makes sense only if cache is working in portable mode ({@code
     * CacheConfiguration#isPortableEnabled()} returns {@code true}. If not, this method is no-op and will return
     * current projection.
     *
     * @return Projection for portable objects.
     */
    public <K1, V1> IgniteCache<K1, V1> keepPortable() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            CacheOperationContext prj = new CacheOperationContext(
                prjCtx != null ? prjCtx.skipStore() : false,
                prjCtx != null ? prjCtx.subjectId() : null,
                true,
                prjCtx != null ? prjCtx.expiry() : null);

            return new IgniteCacheProxy<>((GridCacheContext<K1, V1>)ctx,
                (GridCacheAdapter<K1, V1>)delegate,
                prj,
                isAsync());
        }
        finally {
            gate.leave(prev);
        }
    }

    /**
     * @return Cache with skip store enabled.
     */
    public IgniteCache<K, V> skipStore() {
        CacheOperationContext prev = gate.enter(prjCtx);

        try {
            boolean skip = prjCtx != null && prjCtx.skipStore();

            if (skip)
                return this;

            CacheOperationContext prj0 = new CacheOperationContext(
                true,
                prjCtx != null ? prjCtx.subjectId() : null,
                prjCtx != null && prjCtx.isKeepPortable(),
                prjCtx != null ? prjCtx.expiry() : null);

            return new IgniteCacheProxy<>(ctx,
                delegate,
                prj0,
                isAsync());
        }
        finally {
            gate.leave(prev);
        }
    }

    /**
     * @param e Checked exception.
     * @return Cache exception.
     */
    private RuntimeException cacheException(IgniteCheckedException e) {
        return CU.convertToCacheException(e);
    }

    /**
     * @param fut Future for async operation.
     */
    private <R> void setFuture(IgniteInternalFuture<R> fut) {
        curFut.set(new IgniteFutureImpl<>(fut));
    }

    /**
     * @return Legacy proxy.
     */
    @NotNull
    public GridCacheProxyImpl<K, V> legacyProxy() {
        return legacyProxy;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(ctx);

        out.writeObject(delegate);

        out.writeObject(prjCtx);
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"unchecked"})
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        ctx = (GridCacheContext<K, V>)in.readObject();

        delegate = (IgniteInternalCache<K, V>)in.readObject();

        prjCtx = (CacheOperationContext)in.readObject();

        gate = ctx.gate();
    }

    /** {@inheritDoc} */
    @Override public IgniteFuture<?> rebalance() {
        ctx.preloader().forcePreload();

        return new IgniteFutureImpl<>(ctx.preloader().syncFuture());
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(IgniteCacheProxy.class, this);
    }

    /**
     * Closeable iterator.
     */
    private abstract static class ClIter<X, Y> extends GridCloseableIteratorAdapter<Y> {
        /** */
        private X cur;

        /** */
        private CacheQueryFuture<X> fut;

        /**
         * @param fut Future.
         */
        protected ClIter(CacheQueryFuture<X> fut) {
            this.fut = fut;
        }

        /** {@inheritDoc} */
        @Override protected Y onNext() throws IgniteCheckedException {
            if (!onHasNext())
                throw new NoSuchElementException();

            X e = cur;

            cur = null;

            return convert(e);
        }

        /**
         * @param x X.
         */
        protected abstract Y convert(X x);

        /** {@inheritDoc} */
        @Override protected boolean onHasNext() throws IgniteCheckedException {
            return cur != null || (cur = fut.next()) != null;
        }

        /** {@inheritDoc} */
        @Override protected void onClose() throws IgniteCheckedException {
            fut.cancel();
        }
    }
}
