package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/** {@link PetMessage}: the three pet messages core ScreenshotPlugin uses, with and without colour tags. */
public class PetMessageTest
{
	@Test
	public void recognisesThePetMessages()
	{
		assertEquals(NotableDrop.Pet.RECEIVED, PetMessage.parse(true,
			"<col=ef1020>You have a funny feeling like you're being followed.</col>"));
		assertEquals(NotableDrop.Pet.RECEIVED, PetMessage.parse(true,
			"You feel something weird sneaking into your backpack."));
		assertEquals(NotableDrop.Pet.DUPLICATE, PetMessage.parse(true,
			"<col=ef1020>You have a funny feeling like you would have been followed...</col>"));
	}

	@Test
	public void ignoresOtherMessages()
	{
		assertNull("wrong chat type", PetMessage.parse(false, "You have a funny feeling like you're being followed."));
		assertNull(PetMessage.parse(true, null));
		assertNull("quoting it", PetMessage.parse(true, "He said You have a funny feeling like you're being followed"));
		assertNull("another player's broadcast", PetMessage.parse(true,
			"Zezima has a funny feeling like he's being followed: Pet kraken at 1,000 kills."));
		assertNull(PetMessage.parse(true, "A superior foe has appeared..."));
	}
}
