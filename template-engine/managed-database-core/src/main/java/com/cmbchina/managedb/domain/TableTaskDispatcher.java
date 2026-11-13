package com.cmbchina.managedb.domain;

/**
 * 提交前预留本实例执行容量，调度实现归基础设施。
 */
public interface TableTaskDispatcher {
    /**
     * 容量满时拒绝，不创建受理记录。
     */
    Reservation reserve();

    /**
     * 未提交关闭就释放；成功转交后由工作线程释放。
     */
    interface Reservation extends AutoCloseable {
        /**
         * 一次性转交任务；明确拒绝意味着任务没有运行。
         */
        void dispatch(Runnable task);

        /**
         * 归还尚未转交的预留额度。
         */
        @Override
        void close();
    }
}
