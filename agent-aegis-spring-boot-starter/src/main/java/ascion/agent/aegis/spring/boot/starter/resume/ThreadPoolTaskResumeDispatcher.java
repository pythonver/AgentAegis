package ascion.agent.aegis.spring.boot.starter.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public class ThreadPoolTaskResumeDispatcher implements TaskResumeDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolTaskResumeDispatcher.class);

    private final TaskResumeRunner taskResumeRunner;
    private final ExecutorService executorService;

    public ThreadPoolTaskResumeDispatcher(TaskResumeRunner taskResumeRunner) {
        this.taskResumeRunner = taskResumeRunner;
        this.executorService = new ThreadPoolExecutor(
                8, 16, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(512),
                new ResumeThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void dispatch(ResumeTicket ticket) {
        try {
            executorService.submit(() -> {
                try {
                    taskResumeRunner.run(ticket);
                } catch (Exception e) {
                    logger.warn("Resume task {} failed", ticket != null ? ticket.getTaskId() : null, e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.warn("Resume task {} rejected (pool full/shutting down)",
                    ticket != null ? ticket.getTaskId() : null, e);
        }
    }

    private static final class ResumeThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "agent-aegis-resume-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
