/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.kafka.clients.producer.BufferExhaustedException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Meter;
import org.apache.kafka.common.utils.Time;


/**
 * A pool of ByteBuffers kept under a given memory limit. This class is fairly specific to the needs of the producer. In
 * particular it has the following properties:
 * <ol>
 * <li>There is a special "poolable size" and buffers of this size are kept in a free list and recycled
 * <li>It is fair. That is all memory is given to the longest waiting thread until it has sufficient memory. This
 * prevents starvation or deadlock when a thread asks for a large chunk of memory and needs to block until multiple
 * buffers are deallocated.
 * </ol>
 * <p>
 * 在给定的内存限制下的可重用的内存缓冲池，并且对于需要内存的生产者来说这些内存的使用是公平的。拥有以下特征：
 * 1）池化技术：BufferPool内部保存了一个大小是{@value ProducerConfig#BATCH_SIZE_CONFIG}的空闲ByteBuffer的列表，
 * 如果要申请的ByteBuffer大小是{@value ProducerConfig#BATCH_SIZE_CONFIG}，
 * 当空闲ByteBuffer列表中有可用的则直接返回，且用完之后重新放入池中以复用。
 * 2）公平。对于所有的内存申请者来说，申请内存是公平的，遵循先到先得的约定。如果先到的申请者申请不到内存，
 * 会一直等待直到获得足够的内存或者等待超时，接下来才会给后来的内存申请者提供空闲内存。
 * <p>
 * 总可用内存是 nonPooledAvailableMemory 和 free * poolableSize 中的字节缓冲区数的总和
 */
public class BufferPool {

    static final String WAIT_TIME_SENSOR_NAME = "bufferpool-wait-time";

    private final long totalMemory;
    /**
     * 内存池中每个内存块的大小，使用的是批次大小的配置
     * @see ProducerConfig#BATCH_SIZE_CONFIG
     */
    private final int poolableSize;
    private final ReentrantLock lock;
    /**
     * 空闲的内存块池，本质上是一个队列
     */
    private final Deque<ByteBuffer> free;
    /**
     * 等待申请内存的队列，等待中的对象遵循先到先得内存的规则
     */
    private final Deque<Condition> waiters;
    /**
     * Total available memory is the sum of nonPooledAvailableMemory and the number of byte buffers in free * poolableSize.
     * 未池化的可用内存大小。
     * 总可用内存是 nonPooledAvailableMemory 和 free * poolableSize 中的字节缓冲区数的总和。
     */
    private long nonPooledAvailableMemory;
    private final Metrics metrics;
    private final Time time;
    private final Sensor waitTime;
    private boolean closed;

    /**
     * Create a new buffer pool
     *
     * @param memory The maximum amount of memory that this buffer pool can allocate
     * @param poolableSize The buffer size to cache in the free list rather than deallocating
     * @param metrics instance of Metrics
     * @param time time instance
     * @param metricGrpName logical group name for metrics
     */
    public BufferPool(long memory, int poolableSize, Metrics metrics, Time time, String metricGrpName) {
        this.poolableSize = poolableSize;
        this.lock = new ReentrantLock();
        this.free = new ArrayDeque<>();
        this.waiters = new ArrayDeque<>();
        this.totalMemory = memory;
        this.nonPooledAvailableMemory = memory;
        this.metrics = metrics;
        this.time = time;
        this.waitTime = this.metrics.sensor(WAIT_TIME_SENSOR_NAME);
        MetricName rateMetricName = metrics.metricName("bufferpool-wait-ratio",
                                                   metricGrpName,
                                                   "The fraction of time an appender waits for space allocation.");
        MetricName totalMetricName = metrics.metricName("bufferpool-wait-time-total",
                                                   metricGrpName,
                                                   "The total time an appender waits for space allocation.");

        Sensor bufferExhaustedRecordSensor = metrics.sensor("buffer-exhausted-records");
        MetricName bufferExhaustedRateMetricName = metrics.metricName("buffer-exhausted-rate", metricGrpName, "The average per-second number of record sends that are dropped due to buffer exhaustion");
        MetricName bufferExhaustedTotalMetricName = metrics.metricName("buffer-exhausted-total", metricGrpName, "The total number of record sends that are dropped due to buffer exhaustion");
        bufferExhaustedRecordSensor.add(new Meter(bufferExhaustedRateMetricName, bufferExhaustedTotalMetricName));

        this.waitTime.add(new Meter(TimeUnit.NANOSECONDS, rateMetricName, totalMetricName));
        this.closed = false;
    }

    /**
     * Allocate a buffer of the given size. This method blocks if there is not enough memory and the buffer pool
     * is configured with blocking mode.
     *
     * @param size The buffer size to allocate in bytes
     * @param maxTimeToBlockMs The maximum time in milliseconds to block for buffer memory to be available
     *                         这里的最大阻塞时间是消息发送过程中，除了前面已经消耗的时间之外还剩余的阻塞时间
     * @return The buffer
     * @throws InterruptedException If the thread is interrupted while blocked
     * @throws IllegalArgumentException if size is larger than the total memory controlled by the pool (and hence we would block
     *         forever)
     */
    public ByteBuffer allocate(int size, long maxTimeToBlockMs) throws InterruptedException {
        if (size > this.totalMemory)
            throw new IllegalArgumentException("Attempt to allocate " + size
                                               + " bytes, but there is a hard limit of "
                                               + this.totalMemory
                                               + " on memory allocations.");

        ByteBuffer buffer = null;
        this.lock.lock();

        if (this.closed) {
            this.lock.unlock();
            throw new KafkaException("Producer closed while allocating memory");
        }

        try {
            // check if we have a free buffer of the right size pooled
            // 如果要申请的内存刚好和内存池中每个内存块的大小相等，并且有空闲的内存块，直接返回使用
            if (size == poolableSize && !this.free.isEmpty())
                return this.free.pollFirst();

            // now check if the request is immediately satisfiable with the
            // memory on hand or if we need to block
            // 空闲内存的大小 = 空闲内存块数 * 每个内存块大小
            int freeListSize = freeSize() * this.poolableSize;
            // 如果现在总的可用内存 大于 申请的内存，则分配内存并返回
            if (this.nonPooledAvailableMemory + freeListSize >= size) {
                // we have enough unallocated or pooled memory to immediately
                // satisfy the request, but need to allocate the buffer
                // 尝试增加未池化的可用内存大小
                freeUp(size);
                // 可用内存减少
                this.nonPooledAvailableMemory -= size;
            } else {// 要申请的内存比现有可用内存大的情况下。比如可用内存只有4kb，需要申请16kb
                // we are out of memory and will have to block
                int accumulated = 0;
                Condition moreMemory = this.lock.newCondition();
                try {
                    // 这里的最大阻塞时间是消息发送过程中，除了前面已经消耗的时间之外还剩余的阻塞时间
                    long remainingTimeToBlockNs = TimeUnit.MILLISECONDS.toNanos(maxTimeToBlockMs);
                    this.waiters.addLast(moreMemory);
                    // loop over and over until we have a buffer or have reserved
                    // enough memory to allocate one
                    while (accumulated < size) {
                        long startWaitNs = time.nanoseconds();
                        long timeNs;
                        boolean waitingTimeElapsed;
                        try {
                            // 在等待时间耗尽的情况下返回false，其他情况下返回true（比如被唤醒）
                            waitingTimeElapsed = !moreMemory.await(remainingTimeToBlockNs, TimeUnit.NANOSECONDS);
                        } finally {
                            long endWaitNs = time.nanoseconds();
                            timeNs = Math.max(0L, endWaitNs - startWaitNs);
                            recordWaitTime(timeNs);
                        }

                        if (this.closed)
                            throw new KafkaException("Producer closed while allocating memory");

                        // 如果已经达到最大阻塞时间仍旧没有获取到内存，则抛出异常
                        if (waitingTimeElapsed) {
                            this.metrics.sensor("buffer-exhausted-records").record();
                            throw new BufferExhaustedException("Failed to allocate memory within the configured max blocking time " + maxTimeToBlockMs + " ms.");
                        }

                        remainingTimeToBlockNs -= timeNs;

                        // check if we can satisfy this request from the free list,
                        // otherwise allocate memory
                        // 在block过程尚未获取到内存 且 要申请的内存和内存池中内存块大小相等 且 内存池中有空闲内存块
                        if (accumulated == 0 && size == this.poolableSize && !this.free.isEmpty()) {
                            // just grab a buffer from the free list
                            buffer = this.free.pollFirst();
                            // 设置条件，退出循环
                            accumulated = size;
                        } else {
                            // we'll need to allocate memory, but we may only get
                            // part of what we need on this iteration
                            // 尝试获取内存
                            freeUp(size - accumulated);
                            // 计算在本轮循环中获取到内存，在本轮想要获取的内存和可用内存中取最小值
                            int got = (int) Math.min(size - accumulated, this.nonPooledAvailableMemory);
                            // 可用内存减少
                            this.nonPooledAvailableMemory -= got;
                            // 已获取内存增加
                            accumulated += got;
                        }
                    }
                    // Don't reclaim memory on throwable since nothing was thrown
                    // 如果代码可以执行到这一步，说明已经申请内存成功了，因此需要把accumulated置为0，
                    // 避免在接下来的finally把可用内存又增加了，造成内存泄漏
                    accumulated = 0;
                } finally {
                    // When this loop was not able to successfully terminate don't loose available memory
                    // 如果在阻塞等待获取内存的过程中，申请到了内存，则需要把这一部分已经申请到的内存回收掉
                    this.nonPooledAvailableMemory += accumulated;
                    this.waiters.remove(moreMemory);
                }
            }
        } finally {
            // signal any additional waiters if there is more memory left
            // over for them
            try {
                // 如果有可用内存，并且在等待获取内存队列不为空，则唤醒等待获取内存的对象（是一个Condition对象）
                if (!(this.nonPooledAvailableMemory == 0 && this.free.isEmpty()) && !this.waiters.isEmpty())
                    this.waiters.peekFirst().signal();
            } finally {
                // Another finally... otherwise find bugs complains
                lock.unlock();
            }
        }

        if (buffer == null)
            // 真正的分配内存操作，如果分配内存过程中报错并且等待申请内存的队列不为空，也会唤醒正在等待获取内存的对象（是一个Condition对象）
            return safeAllocateByteBuffer(size);
        else
            return buffer;
    }

    // Protected for testing
    protected void recordWaitTime(long timeNs) {
        this.waitTime.record(timeNs, time.milliseconds());
    }

    /**
     * Allocate a buffer.  If buffer allocation fails (e.g. because of OOM) then return the size count back to
     * available memory and signal the next waiter if it exists.
     */
    private ByteBuffer safeAllocateByteBuffer(int size) {
        boolean error = true;
        try {
            ByteBuffer buffer = allocateByteBuffer(size);
            error = false;
            return buffer;
        } finally {
            // 如果分配内存过程中报错并且等待申请内存的队列不为空，也会唤醒正在等待获取内存的对象（是一个Condition对象）
            if (error) {
                this.lock.lock();
                try {
                    this.nonPooledAvailableMemory += size;
                    if (!this.waiters.isEmpty())
                        this.waiters.peekFirst().signal();
                } finally {
                    this.lock.unlock();
                }
            }
        }
    }

    // Protected for testing.
    protected ByteBuffer allocateByteBuffer(int size) {
        return ByteBuffer.allocate(size);
    }

    /**
     * Attempt to ensure we have at least the requested number of bytes of memory for allocation by deallocating pooled
     * buffers (if needed)
     */
    private void freeUp(int size) {
        while (!this.free.isEmpty() && this.nonPooledAvailableMemory < size)
            this.nonPooledAvailableMemory += this.free.pollLast().capacity();
    }

    /**
     * Return buffers to the pool. If they are of the poolable size add them to the free list, otherwise just mark the
     * memory as free.
     *
     * @param buffer The buffer to return
     * @param size The size of the buffer to mark as deallocated, note that this may be smaller than buffer.capacity
     *             since the buffer may re-allocate itself during in-place compression
     */
    public void deallocate(ByteBuffer buffer, int size) {
        lock.lock();
        try {
            // 如果归还的内存大小等于内存块的标准大小，并且归还的内存大小等于归还内存块的容量，则加入到内存块池中
            if (size == this.poolableSize && size == buffer.capacity()) {
                buffer.clear();
                this.free.add(buffer);
            } else {
                this.nonPooledAvailableMemory += size;
            }
            // 等待申请内存的队列不为空，则唤醒
            Condition moreMem = this.waiters.peekFirst();
            if (moreMem != null)
                moreMem.signal();
        } finally {
            lock.unlock();
        }
    }

    public void deallocate(ByteBuffer buffer) {
        deallocate(buffer, buffer.capacity());
    }

    /**
     * the total free memory both unallocated and in the free list
     */
    public long availableMemory() {
        lock.lock();
        try {
            return this.nonPooledAvailableMemory + freeSize() * (long) this.poolableSize;
        } finally {
            lock.unlock();
        }
    }

    // Protected for testing.
    protected int freeSize() {
        return this.free.size();
    }

    /**
     * Get the unallocated memory (not in the free list or in use)
     */
    public long unallocatedMemory() {
        lock.lock();
        try {
            return this.nonPooledAvailableMemory;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The number of threads blocked waiting on memory
     */
    public int queued() {
        lock.lock();
        try {
            return this.waiters.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The buffer size that will be retained in the free list after use
     */
    public int poolableSize() {
        return this.poolableSize;
    }

    /**
     * The total memory managed by this pool
     */
    public long totalMemory() {
        return this.totalMemory;
    }

    // package-private method used only for testing
    Deque<Condition> waiters() {
        return this.waiters;
    }

    /**
     * Closes the buffer pool. Memory will be prevented from being allocated, but may be deallocated. All allocations
     * awaiting available memory will be notified to abort.
     */
    public void close() {
        this.lock.lock();
        this.closed = true;
        try {
            for (Condition waiter : this.waiters)
                waiter.signal();
        } finally {
            this.lock.unlock();
        }
    }
}
