package com.shmayvan.slayerledger;

import net.runelite.client.eventbus.EventBus;
import org.junit.Test;

/**
 * The event bus rejects subscriber methods not named after their event, which stops the plugin from starting. Unit
 * tests that call the plugin directly cannot see that, so register its subscribers the way the client does.
 */
public class EventSubscriberTest
{
	@Test
	public void pluginSubscribersRegisterWithTheEventBus()
	{
		EventBus eventBus = new EventBus();
		SlayerLedgerPlugin plugin = new SlayerLedgerPlugin();
		eventBus.register(plugin);

		eventBus.register(plugin.completionGraceTicker());
	}
}
