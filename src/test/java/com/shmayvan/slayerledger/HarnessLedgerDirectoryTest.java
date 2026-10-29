package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Where the ledger files live and when that is decided: the descriptor names the plugin directory and the legacy
 * directory RuneLite migrates, the directory is resolved once on the executor (never the client thread or the EDT),
 * and a directory that cannot be resolved leaves the plugin running without saving, through startUp, saves and
 * shutDown. The migration itself is RuneLite's ({@code Plugin#getPluginDirectory}) and is not exercised here: tests
 * never call it, so they never touch {@code ~/.runelite}.
 */
public class HarnessLedgerDirectoryTest
{
	private static final long ACCOUNT = PluginHarness.ACCOUNT;
	private static final int TASK = 42;
	private static final String TASK_NAME = "Abyssal demons";

	@Rule
	public final PluginHarness harness = new PluginHarness();

	/** Thread descriptions of every directory resolution: "executor", "client thread", "EDT" or "other". */
	private final List<String> resolvedOn = new CopyOnWriteArrayList<>();
	private final AtomicInteger failuresLeft = new AtomicInteger();

	@Before
	public void setUp()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		final Filepath directory = Filepath.Unchecked.getRooted(harness.ledgerDirectory().toPath());
		harness.plugin.setLedgerDirectorySource(() ->
		{
			resolvedOn.add(currentThread());
			if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
			{
				throw new IOException("plugin directory unavailable");
			}
			return directory;
		});
	}

	private String currentThread()
	{
		if (harness.executor.isRunningTask())
		{
			return "executor";
		}
		if (SwingUtilities.isEventDispatchThread())
		{
			return "EDT";
		}
		return harness.isClientThread() ? "client thread" : "other";
	}

	/** Logs in on the task, plays and kills two of the ten. */
	private void loginPlayAndKillTwo()
	{
		harness.setTask(TASK, TASK_NAME, 10, 10);
		harness.loginAndSettle(ACCOUNT);
		assertTrue(harness.play());
		harness.setRemaining(8);
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
	}

	private String[] savedFiles()
	{
		return harness.ledgerDirectory().list();
	}

	@Test
	public void descriptorNamesThePluginDirectoryAndTheLegacyDirectory()
	{
		final PluginDescriptor descriptor = SlayerLedgerPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull(descriptor);
		assertEquals("slayer-ledger", descriptor.internalName());
		assertEquals("slayer-ledger", descriptor.legacyDataDirectory());

		// getPluginDirectory joins these as single segments (the legacy one must not be a RuneLite directory); both
		// throw for an unusable name. Only paths are built here, nothing is read or written.
		final Path fakeRuneLiteDir = harness.ledgerDirectory().toPath();
		assertEquals("slayer-ledger", Filepath.Unchecked.getRooted(fakeRuneLiteDir.resolve("plugin-data"))
			.joinSegment(descriptor.internalName()).getFileName());
		assertEquals("slayer-ledger", Filepath.Unchecked.getLegacyPluginDirectory(fakeRuneLiteDir,
			descriptor.legacyDataDirectory()).getFileName());
	}

	@Test
	public void directoryIsResolvedOnceOnTheExecutorAtStartUp()
	{
		harness.startUp();
		assertEquals("resolved by startUp's executor task", 1, resolvedOn.size());
		assertEquals("executor", resolvedOn.get(0));

		loginPlayAndKillTwo();
		harness.stop();
		harness.onlyRecord(ACCOUNT);
		harness.shutDown();
		assertEquals("kept for the plugin's lifetime", 1, resolvedOn.size());
	}

	@Test
	public void failedResolutionKeepsThePluginRunningWithoutSaving()
	{
		failuresLeft.set(Integer.MAX_VALUE);
		harness.startUp();
		loginPlayAndKillTwo();
		assertEquals("tracking still works", 2, harness.tracker().getKills());

		harness.stop();
		assertEquals(SessionTracker.State.IDLE, harness.sessionState());
		assertTrue(harness.play());
		harness.setRemaining(7);
		harness.tick();
		// Shutdown with a running session tries to persist it and fails quietly (logged), like any IO failure.
		harness.shutDown();

		assertEquals("nothing written", 0, savedFiles().length);
		assertTrue("each operation tried again", resolvedOn.size() > 2);
		assertTrue(resolvedOn.stream().allMatch("executor"::equals));
	}

	@Test
	public void resolutionThatFailedAtStartUpIsRetriedByTheNextOperation()
	{
		failuresLeft.set(1);
		harness.startUp();
		assertEquals(1, resolvedOn.size());
		loginPlayAndKillTwo();
		harness.stop();

		final LedgerRecord record = harness.onlyRecord(ACCOUNT);
		assertEquals(2, record.getKills());
		assertNull(harness.activeSession(ACCOUNT));
		assertEquals("resolved by the history load after login, then kept", 2, resolvedOn.size());
		assertFalse(resolvedOn.contains("client thread"));
		assertFalse(resolvedOn.contains("EDT"));
	}
}
