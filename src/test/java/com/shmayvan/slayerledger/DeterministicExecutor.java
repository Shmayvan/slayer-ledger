package com.shmayvan.slayerledger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Test stand-in for RuneLite's shared {@link ScheduledExecutorService}. Tasks are queued and run only when the test
 * (or {@link PluginHarness}) calls {@link #runPending}, in submission order, on the calling thread. This keeps file
 * IO deterministic while still being asynchronous from the plugin's point of view: work handed to the executor has
 * not happened until it is drained, as in the real client.
 *
 * <p>{@link #submit} returns a {@link java.util.concurrent.FutureTask}, which completes when the task runs, so
 * {@code ClientShutdown.waitFor} sees real completion. Delayed scheduling is not used by the plugin and throws
 * {@link UnsupportedOperationException} so a future use is noticed. After {@link #shutdown()} new tasks are rejected
 * with {@link RejectedExecutionException}, as a stopped executor would.</p>
 */
final class DeterministicExecutor extends AbstractExecutorService implements ScheduledExecutorService
{
	private final Queue<Runnable> tasks = new ArrayDeque<>();
	private boolean shutdown;
	private boolean running;

	@Override
	public synchronized void execute(Runnable command)
	{
		if (shutdown)
		{
			throw new RejectedExecutionException("executor is shut down");
		}
		tasks.add(command);
	}

	/** Number of queued tasks not yet run. */
	synchronized int pendingCount()
	{
		return tasks.size();
	}

	/** Whether a task is running right now (on the draining thread). */
	synchronized boolean isRunningTask()
	{
		return running;
	}

	/**
	 * Runs queued tasks, including ones they queue, until none are left. A task that throws is reported to
	 * {@code onError} and the rest still run.
	 *
	 * @return the number of tasks run
	 */
	int runPending(Consumer<Throwable> onError)
	{
		int ran = 0;
		for (;;)
		{
			final Runnable task;
			synchronized (this)
			{
				task = tasks.poll();
				if (task == null)
				{
					return ran;
				}
				running = true;
			}
			try
			{
				task.run();
			}
			catch (RuntimeException | Error e)
			{
				onError.accept(e);
			}
			finally
			{
				synchronized (this)
				{
					running = false;
				}
			}
			ran++;
		}
	}

	@Override
	public synchronized void shutdown()
	{
		shutdown = true;
	}

	@Override
	public synchronized List<Runnable> shutdownNow()
	{
		shutdown = true;
		final List<Runnable> remaining = new ArrayList<>(tasks);
		tasks.clear();
		return remaining;
	}

	@Override
	public synchronized boolean isShutdown()
	{
		return shutdown;
	}

	@Override
	public synchronized boolean isTerminated()
	{
		return shutdown && tasks.isEmpty() && !running;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit)
	{
		return isTerminated();
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
	{
		throw new UnsupportedOperationException("delayed tasks are not supported by the test executor");
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)
	{
		throw new UnsupportedOperationException("delayed tasks are not supported by the test executor");
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
	{
		throw new UnsupportedOperationException("periodic tasks are not supported by the test executor");
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)
	{
		throw new UnsupportedOperationException("periodic tasks are not supported by the test executor");
	}
}
