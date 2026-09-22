package ascion.agent.aegis.core.retry;

@FunctionalInterface
public interface RetryTask<T> {
    T run() throws Throwable;
}
