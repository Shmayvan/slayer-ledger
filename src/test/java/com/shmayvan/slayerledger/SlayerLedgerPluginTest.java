package com.shmayvan.slayerledger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SlayerLedgerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SlayerLedgerPlugin.class);
		RuneLite.main(args);
	}
}
