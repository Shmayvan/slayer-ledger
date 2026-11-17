package com.shmayvan.slayerledger;

import static org.junit.Assert.assertTrue;
import net.runelite.client.eventbus.EventBus;
import org.junit.Rule;
import org.junit.Test;

/**
 * The event bus rejects subscriber methods not named after their event, which stops the plugin from starting. Unit
 * tests that call the plugin directly cannot see that, so register its subscribers the way the client does.
 */
public class EventSubscriberTest
{
	@Rule
	public final PluginHarness harness = new PluginHarness();

	@Test
	public void pluginSubscribersRegisterWithTheEventBus()
	{
		EventBus eventBus = new EventBus();
		SlayerLedgerPlugin plugin = new SlayerLedgerPlugin();
		eventBus.register(plugin);

		// startUp registers the plugin's other subscriber, the completion grace ticker, with the injected (real) bus;
		// a rejected one would make it throw.
		harness.startUp();
		assertTrue(harness.isRunning());
	}
}
