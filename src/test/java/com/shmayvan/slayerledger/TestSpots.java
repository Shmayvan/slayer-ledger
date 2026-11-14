package com.shmayvan.slayerledger;

import com.google.gson.Gson;

/**
 * The bundled Slayer spot table for tests, read once, and the places the location tests use. The ids and regions
 * are the table's; {@link SlayerSpotsTest} checks the table itself.
 */
final class TestSpots
{
	/** The bundled table, read with a test's own Gson (only tests create one). */
	static final SlayerSpots TABLE = SlayerSpots.load(new Gson(), SlayerSpots.SNAPSHOT_RESOURCE);

	/** The Smoke Dungeon: regions 12690, 12946 and 13202; dust devils, fire giants and pyrefiends. */
	static final int SMOKE = 12_946;
	static final String SMOKE_ID = "smoke-dungeon";
	/** The Catacombs of Kourend: regions 6556, 6557, 6812 and 6813. */
	static final int CATACOMBS = 6_557;
	static final int CATACOMBS_WEST = 6_556;
	static final String CATACOMBS_ID = "catacombs-of-kourend";
	/** The Stronghold Slayer Cave: regions 9624, 9625, 9880 and 9881. */
	static final int STRONGHOLD = 9_624;
	static final String STRONGHOLD_ID = "stronghold-slayer-cave";
	/** The Slayer Tower: regions 13623 and 13723; no dust devils. */
	static final int TOWER = 13_623;
	static final String TOWER_ID = "slayer-tower";
	/** Region 5536, shared: the Alchemical Hydra's lair on plane 0, the Karuulm Slayer Dungeon on plane 1. */
	static final int KARUULM_SHARED = 5_536;
	static final String HYDRA_LAIR_ID = "alchemical-hydra-lair";
	static final String KARUULM_ID = "karuulm-slayer-dungeon";
	/** Region 9116, shared: Kraken Cove, and the Kraken's boss room in [2268, 10020, 2303, 10047]. */
	static final int KRAKEN_SHARED = 9_116;
	static final String KRAKEN_COVE_ID = "kraken-cove";
	static final String KRAKEN_ROOM_ID = "kraken-boss-room";
	/** Region 7820, in the Corsair Cove Dungeon (the owner named it "Myth's Guild" in 1.x). */
	static final int CORSAIR = 7_820;
	static final String CORSAIR_ID = "corsair-cove-dungeon";
	/** Edgeville's region 12342, which the table doesn't list. */
	static final int UNLISTED = 12_342;

	private TestSpots()
	{
	}

	/** The spot key of a kill at {@code (x, y)} on {@code plane}, by the table's matching rule. */
	static String at(int x, int y, int plane)
	{
		return TABLE.spotAt(x, y, plane);
	}

	/** A place known by its tile, as the plugin reads it. */
	static KillPlace place(int x, int y, int plane)
	{
		return KillPlace.at(TABLE, x, y, plane);
	}

	/** A place at the centre tile of {@code region}, on plane 0, as the harness's {@code setPlayerRegion} puts it. */
	static KillPlace centre(int region)
	{
		return place(((region >> 8) << 6) + 32, ((region & 0xFF) << 6) + 32, 0);
	}
}
