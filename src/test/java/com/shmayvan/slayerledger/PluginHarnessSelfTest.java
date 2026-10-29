package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import org.junit.Rule;
import org.junit.Test;

/**
 * The threading semantics {@link PluginHarness} promises, so plugin-level tests can rely on them: client-thread
 * invokes behave as in the client, executor and EDT work happens only when drained, errors the client would only
 * log fail the test, and the real LootManager's loot lands where the event bus orders it.
 */
public class PluginHarnessSelfTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void invokeRunsAtOnceOnTheClientThreadAndInvokeLaterIsQueued()
	{
		AtomicInteger ran = new AtomicInteger();
		harness.clientThread.invoke(ran::incrementAndGet);
		assertEquals(1, ran.get());

		harness.clientThread.invokeLater(ran::incrementAndGet);
		assertEquals(1, ran.get());
		harness.runClientThread();
		assertEquals(2, ran.get());
	}

	@Test
	public void invokeFromTheEdtIsQueuedForTheClientThread()
	{
		AtomicBoolean onClientThread = new AtomicBoolean();
		harness.onEdt(() -> harness.clientThread.invoke(() -> onClientThread.set(harness.client.isClientThread())));
		assertFalse("queued, not run on the EDT", onClientThread.get());
		harness.drain();
		assertTrue(onClientThread.get());
	}

	@Test
	public void invokeReturningFalseIsRunAgainOnTheNextFrame()
	{
		AtomicInteger calls = new AtomicInteger();
		harness.clientThread.invoke(() -> calls.incrementAndGet() >= 3);
		assertEquals(1, calls.get());
		harness.runClientThread();
		assertEquals(2, calls.get());
		harness.runClientThread();
		assertEquals(3, calls.get());
		harness.runClientThread();
		assertEquals("done after returning true", 3, calls.get());
	}

	@Test
	public void inlineInvokeReturningFalseIsDrainedOnTheNextFrame()
	{
		AtomicInteger calls = new AtomicInteger();
		harness.clientThread.invoke(() -> calls.incrementAndGet() >= 2);
		assertEquals(1, calls.get());
		harness.drain();
		assertEquals(2, calls.get());
	}

	@Test
	public void tickEndWorkRunsAfterTheInvokeQueueAndIsDrained()
	{
		List<String> order = new ArrayList<>();
		harness.clientThread.invokeAtTickEnd(() -> order.add("tick end"));
		harness.clientThread.invokeLater(() -> order.add("invoke"));
		harness.drain();
		assertEquals(Arrays.asList("invoke", "tick end"), order);
	}

	@Test
	public void drainFailsInsteadOfHangingOnAnEndlesslyRetriedTask()
	{
		AtomicBoolean done = new AtomicBoolean();
		harness.clientThread.invokeLater(done::get);
		try
		{
			harness.drain();
			fail("drain should have given up");
		}
		catch (AssertionError expected)
		{
			// Let the task finish so the harness can clean up.
			done.set(true);
		}
	}

	@Test
	public void executorWorkRunsOnlyWhenDrainedAndNotAsTheClientThread() throws Exception
	{
		AtomicBoolean onClientThread = new AtomicBoolean(true);
		Future<?> future = harness.executor.submit(() -> onClientThread.set(harness.client.isClientThread()));
		assertFalse(future.isDone());
		harness.drain();
		assertTrue(future.isDone());
		future.get();
		assertFalse(onClientThread.get());
	}

	@Test
	public void clientCallsOffTheClientThreadAreReported()
	{
		harness.onEdt(() -> harness.client.getGameState());
		harness.executor.execute(() -> harness.itemManager.getItemPrice(1));
		harness.drain();
		List<Throwable> errors = harness.takeErrors();
		assertEquals(2, errors.size());
		assertTrue(errors.get(0).getMessage().contains("Client.getGameState called off the client thread"));
		assertTrue(errors.get(1).getMessage().contains("ItemManager.getItemPrice called off the client thread"));
	}

	@Test
	public void otherClientFakesAreCheckedToo()
	{
		harness.setInventory(PluginHarness.item(ItemID.BONES, 1));
		harness.openInterface(InterfaceID.BANKMAIN);
		ItemContainer inventory = harness.client.getItemContainer(InventoryID.INV);
		Player player = harness.client.getLocalPlayer();
		Widget widget = harness.client.getWidget(InterfaceID.BANKMAIN, 0);
		harness.onEdt(() ->
		{
			inventory.getItems();
			player.getName();
			widget.isHidden();
		});
		String all = harness.takeErrors().toString();
		assertTrue(all, all.contains("ItemContainer.getItems called off the client thread"));
		assertTrue(all, all.contains("Player.getName called off the client thread"));
		assertTrue(all, all.contains("Widget.isHidden called off the client thread"));
	}

	@Test
	public void edtWorkPostedByEdtWorkIsDrainedToo()
	{
		AtomicBoolean ran = new AtomicBoolean();
		SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() ->
		{
			harness.client.getGameState();
			ran.set(true);
		})));
		harness.drain();
		assertTrue("the innermost runnable ran before drain returned", ran.get());
		List<Throwable> errors = harness.takeErrors();
		assertEquals(errors.toString(), 1, errors.size());
		assertTrue(errors.get(0).getMessage().contains("Client.getGameState called off the client thread"));
	}

	@Test
	public void uncaughtExceptionsOnOtherThreadsAreReported() throws InterruptedException
	{
		Thread thread = new Thread(() ->
		{
			throw new IllegalStateException("thread failed");
		}, "harness-self-test");
		thread.start();
		thread.join();
		String all = harness.takeErrors().toString();
		assertTrue(all, all.contains("uncaught on harness-self-test"));
		assertTrue(all, all.contains("thread failed"));
	}

	/**
	 * The real LootManager posts {@link ServerNpcLoot} from its default-priority {@code onGameTick}. The event bus runs
	 * it after the plugin's own default-priority handler (equal priority: class-name order, and {@code com.shmayvan}
	 * sorts before {@code net.runelite}) and before the plugin's priority -1 grace ticker. So the plugin has already
	 * held the completed task when the completion tick's loot arrives, and the ticker releases it only after the last
	 * grace tick's loot.
	 */
	@Test
	public void lootManagerLootArrivesAfterThePluginsTickHandlerAndBeforeTheGraceTicker()
	{
		harness.defineItem(ItemID.BONES, "Bones", 100, 1);
		harness.startUp();
		harness.setTask(42, "Abyssal demons", 1, 3);
		harness.loginAndSettle(PluginHarness.ACCOUNT);
		assertTrue(harness.play());

		List<String> lootSeen = new ArrayList<>();
		EventBus.Subscriber recorder = harness.eventBus.register(ServerNpcLoot.class, loot -> lootSeen.add(
			loot.getComposition().getName() + " held=" + harness.tracker().hasPendingCompletion()), 0);

		harness.completeTask();
		harness.lootOnNextTick(ItemID.BONES, 1);
		harness.tick();
		for (int i = 0; i < SessionTracker.COMPLETION_LOOT_GRACE_TICKS; i++)
		{
			assertTrue("held until the window ends", harness.tracker().hasPendingCompletion());
			harness.lootOnNextTick(ItemID.BONES, 1);
			harness.tick();
		}
		harness.eventBus.unregister(recorder);

		// Each tick's loot came from LootManager (named by the NPC definition it looked up) while the task was held.
		List<String> expected = new ArrayList<>();
		for (int i = 0; i <= SessionTracker.COMPLETION_LOOT_GRACE_TICKS; i++)
		{
			expected.add(PluginHarness.LOOT_NPC_NAME + " held=true");
		}
		assertEquals(expected, lootSeen);
		assertFalse("released after the last grace tick's loot", harness.tracker().hasPendingCompletion());
		LedgerRecord record = harness.onlyRecord(PluginHarness.ACCOUNT);
		assertTrue(record.isCompleted());
		assertEquals(Long.valueOf(1 + SessionTracker.COMPLETION_LOOT_GRACE_TICKS), record.getLoot().get(ItemID.BONES));
	}

	public static final class ThrowingSubscriber
	{
		@Subscribe
		public void onGameTick(GameTick tick)
		{
			throw new IllegalStateException("handler failed");
		}
	}

	@Test
	public void exceptionsTheClientWouldOnlyLogAreReported()
	{
		EventBus bus = harness.eventBus;
		ThrowingSubscriber subscriber = new ThrowingSubscriber();
		bus.register(subscriber);
		harness.tick();
		bus.unregister(subscriber);
		harness.clientThread.invokeLater(() ->
		{
			throw new IllegalStateException("client thread task failed");
		});
		harness.executor.execute(() ->
		{
			throw new IllegalStateException("executor task failed");
		});
		SwingUtilities.invokeLater(() ->
		{
			throw new IllegalStateException("EDT task failed");
		});
		harness.drain();

		String all = harness.takeErrors().toString();
		assertTrue(all, all.contains("handler failed"));
		assertTrue(all, all.contains("client thread task failed"));
		assertTrue(all, all.contains("executor task failed"));
		assertTrue(all, all.contains("EDT task failed"));
	}
}
