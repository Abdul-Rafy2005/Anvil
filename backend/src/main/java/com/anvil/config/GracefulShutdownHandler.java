package com.anvil.config;

import com.anvil.worker.WorkerRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class GracefulShutdownHandler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final WorkerRunner workerRunner;
    private volatile boolean running = false;

    public GracefulShutdownHandler(WorkerRunner workerRunner) {
        this.workerRunner = workerRunner;
    }

    @Override
    public void start() {
        this.running = true;
        log.info("Graceful shutdown handler started");
    }

    @Override
    public void stop() {
        log.info("Graceful shutdown initiated");
        workerRunner.shutdown();

        long deadline = System.currentTimeMillis() + 30_000;
        while (workerRunner.isRunning() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (workerRunner.isRunning()) {
            log.warn("Worker did not finish within shutdown timeout, forcing stop");
        } else {
            log.info("Worker finished current job, shutdown complete");
        }

        this.running = false;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
