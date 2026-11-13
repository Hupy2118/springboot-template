package com.cmbchina.managedb.infrastructure.sql;

import com.cmb.bee.commons.exceptions.BizException;
import com.cmbchina.managedb.domain.ResourceErrorCode;
import com.cmbchina.managedb.domain.TableTaskDispatcher;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 零等待队列的有界后台执行，不从历史记录自动恢复任务。
 */
public final class BoundedTableTaskDispatcher implements TableTaskDispatcher, AutoCloseable {
    private final Semaphore slots;
    private final ThreadPoolExecutor executor;

    /**
     * 初始化固定线程数量，线程名只包含非敏感序号。
     */
    public BoundedTableTaskDispatcher(int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("执行线程数必须为正");
        }
        slots = new Semaphore(workers);
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                task -> new Thread(task, "managed-sql-" + sequence.incrementAndGet()), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 在任何管理库受理写入之前尝试预留，避免空受理任务。
     */
    @Override
    public Reservation reserve() {
        if (!slots.tryAcquire()) {
            throw new BizException(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED);
        }
        return new Reservation() {
            private final AtomicBoolean consumed = new AtomicBoolean();

            /** 拒绝时归还一次额度，工作完成后归还一次额度。 */
            @Override
            public void dispatch(Runnable task) {
                if (!consumed.compareAndSet(false, true)) {
                    throw new BizException(ResourceErrorCode.INVALID_ARGUMENT);
                }
                try {
                    executor.execute(() -> {
                        try {
                            task.run();
                        } finally {
                            slots.release();
                        }
                    });
                } catch (RejectedExecutionException e) {
                    slots.release();
                    throw new BizException(ResourceErrorCode.EXECUTION_CAPACITY_EXHAUSTED);
                }
            }

            /** 未转交任务的预留自动释放。 */
            @Override
            public void close() {
                if (consumed.compareAndSet(false, true)) {
                    slots.release();
                }
            }
        };
    }

    /**
     * 正常停机不强制中断 DDL，未确定结果保留数据库中的占用。
     */
    @Override
    public void close() {
        executor.shutdown();
    }
}
