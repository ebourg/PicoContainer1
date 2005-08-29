/*****************************************************************************
 * Copyright (C) NanoContainer Organization. All rights reserved.            *
 * ------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the BSD      *
 * style license a copy of which has been included with this distribution in *
 * the LICENSE.txt file.                                                     *
 *                                                                           *
 * Original code by Aslak Hellesoy & Joerg Schaible                                       *
 *****************************************************************************/
package org.picocontainer.gems.adapters;

import com.thoughtworks.proxy.ProxyFactory;
import com.thoughtworks.proxy.factory.StandardProxyFactory;
import com.thoughtworks.proxy.toys.pool.Pool;
import com.thoughtworks.proxy.toys.pool.Resetter;

import org.picocontainer.ComponentAdapter;
import org.picocontainer.PicoContainer;
import org.picocontainer.defaults.DecoratingComponentAdapter;


/**
 * {@link ComponentAdapter} implementation that pools components.
 * <p>
 * The implementation utilizes a delegated ComponentAdapter to create the instances of the pool. The pool can be
 * configured to grow unlimited or to a maximum size. If a component is requested from this adapter, the implementation
 * returns an availailabe instance from the pool or will create a new one, if the maximum pool size is not reached yet.
 * If none is available, the implementation can wait a defined time for a returned object before it throws a
 * {@link PoolException}.
 * </p>
 * <p>
 * This implementation uses the {@link Pool} toy from the <a href="http://proxytoys.codehaus.org">ProxyToys</a>
 * project. This ensures, that any component, that is out of scope will be automatically returned to the pool by the
 * garbage collector. Additionally will every component instance also implement
 * {@link com.thoughtworks.proxy.toys.pool.Poolable}, that can be used to return the instance manually. After returning
 * an instance it should not be used in client code anymore.
 * </p>
 * <p>
 * Before a returning object is added to the available instances of the pool again, it should be reinitialized to a
 * normalized state. By providing a proper Resetter implementation this can be done automatically. If the object cannot
 * be reused anymore it can also be dropped and the pool may request a new instance.
 * </p>
 * 
 * @author J&ouml;rg Schaible
 * @author Aslak Helles&oslash;y
 * @since 1.2
 */
public class PoolingComponentAdapter extends DecoratingComponentAdapter {

    private static final long serialVersionUID = 1L;

    /**
     * Context of the PoolingComponentAdapter used to initialize it.
     * 
     * @author J&ouml;rg Schaible
     * @since 1.2
     */
    public static interface Context {
        /**
         * Retrieve the maximum size of the pool. An implementation may return the maximum value or
         * {@link PoolingComponentAdapter#UNLIMITED_SIZE} for <em>unlimited</em> growth.
         * 
         * @return the maximum pool size
         * @since 1.2
         */
        int getMaxSize();

        /**
         * Retrieve the maximum number of milliseconds to wait for a returned element. An implementation may return
         * alternatively {@link PoolingComponentAdapter#BLOCK_ON_WAIT} or {@link PoolingComponentAdapter#FAIL_ON_WAIT}.
         * 
         * @return the maximum number of milliseconds to wait
         * @since 1.2
         */
        int getMaxWaitInMilliseconds();

        /**
         * Retrieve the ProxyFactory to use to create the pooling proxies.
         * 
         * @return the {@link ProxyFactory}
         * @since 1.2
         */
        ProxyFactory getProxyFactory();

        /**
         * Retrieve the {@link Resetter} of the objects returning to the pool.
         * 
         * @return the Resetter instance
         * @since 1.2
         */
        Resetter getResetter();
    }

    /**
     * The default context for a PoolingComponentAdapter.
     * 
     * @author J&ouml;rg Schaible
     * @since 1.2
     */
    public static class DefaultContext implements Context {

        /**
         * {@inheritDoc} Returns {@link PoolingComponentAdapter#DEFAULT_MAX_SIZE}.
         */
        public int getMaxSize() {
            return DEFAULT_MAX_SIZE;
        }

        /**
         * {@inheritDoc} Returns {@link PoolingComponentAdapter#FAIL_ON_WAIT}.
         */
        public int getMaxWaitInMilliseconds() {
            return FAIL_ON_WAIT;
        }

        /**
         * {@inheritDoc} Returns a {@link StandardProxyFactory}.
         */
        public ProxyFactory getProxyFactory() {
            return new StandardProxyFactory();
        }

        /**
         * {@inheritDoc} Returns the {@link PoolingComponentAdapter#DEFAULT_RESETTER}.
         */
        public Resetter getResetter() {
            return DEFAULT_RESETTER;
        }

    }

    /**
     * <code>UNLIMITED_SIZE</code> is the value to set the maximum size of the pool to unlimited ({@link Integer#MAX_VALUE}
     * in fact).
     */
    public static final int UNLIMITED_SIZE = Integer.MAX_VALUE;
    /**
     * <code>DEFAULT_MAX_SIZE</code> is the default size of the pool.
     */
    public static final int DEFAULT_MAX_SIZE = 8;
    /**
     * <code>BLOCK_ON_WAIT</code> forces the pool to wait until an object of the pool is returning in case none is
     * immediately available.
     */
    public static final int BLOCK_ON_WAIT = 0;
    /**
     * <code>FAIL_ON_WAIT</code> forces the pool to fail none is immediately available.
     */
    public static final int FAIL_ON_WAIT = -1;
    /**
     * <code>DEFAULT_RESETTER</code> is the Resetter used by default.
     * 
     * @todo Use a NoOperationResetter from ptoys.
     */
    public static final Resetter DEFAULT_RESETTER = new Resetter() {

        public boolean reset(Object object) {
            return true;
        }

    };

    private final int maxPoolSize;
    private final int waitMilliSeconds;
    private final Pool pool;

    /**
     * Construct a PoolingComponentAdapter with default settings.
     * 
     * @param delegate the delegated ComponentAdapter
     * @since 1.2
     */
    public PoolingComponentAdapter(ComponentAdapter delegate) {
        this(delegate, new DefaultContext());
    }

    /**
     * Construct a PoolingComponentAdapter. Remember, that the implementation will request new components from the
     * delegate as long as no component instance is available in the pool and the maximum pool size is not reached.
     * Therefore the delegate may not return the same component instance twice. Ensure, that the used
     * {@link ComponentAdapter} does not cache.
     * 
     * @param delegate the delegated ComponentAdapter
     * @param context the {@link Context} of the pool
     * @since 1.2
     */
    public PoolingComponentAdapter(ComponentAdapter delegate, Context context) {
        super(delegate);
        this.maxPoolSize = context.getMaxSize();
        this.waitMilliSeconds = context.getMaxWaitInMilliseconds();
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("Invalid maximum pool size");
        }
        Class type = delegate.getComponentKey() instanceof Class ? (Class)delegate.getComponentKey() : delegate
                .getComponentImplementation();
        this.pool = new Pool(type, context.getResetter(), context.getProxyFactory());
    }

    /**
     * {@inheritDoc}
     * <p>
     * As long as the maximum size of the pool is not reached and the pool is exhausted, the implementation will request
     * its delegate for a new instance, that will be managed by the pool. Only if the maximum size of the pool is
     * reached, the implementation may wait (depends on the initializing {@link Context}) for a returning object.
     * </p>
     * 
     * @throws PoolException if the pool is exhausted or waiting for a returning object timed out or was interrupted
     */
    public Object getComponentInstance(PicoContainer container) {
        Object componentInstance = null;
        long now = System.currentTimeMillis();
        synchronized (pool) {
            while ((componentInstance = pool.get()) == null) {
                if (maxPoolSize > pool.size()) {
                    pool.add(super.getComponentInstance(container));
                } else {
                    long after = System.currentTimeMillis();
                    if (waitMilliSeconds < 0) {
                        throw new PoolException("Pool exhausted");
                    }
                    if (waitMilliSeconds > 0 && after - now > waitMilliSeconds) {
                        throw new PoolException("Time ot wating for returning object into pool");
                    }
                    try {
                        wait(waitMilliSeconds); // Note, the pool notifies after an object was returned
                    } catch (InterruptedException e) {
                        // give the client code of the current thread a chance to abort also
                        Thread.currentThread().interrupt();
                        throw new PoolException("Interrupted waiting for returning object into the pool", e);
                    }
                }
            }
        }
        return componentInstance;
    }
}
