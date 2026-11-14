package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import java.util.Collections;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * {@link SlayerBracelets}: which game messages use a charge of the bracelet of slaughter or the expeditious bracelet,
 * and how a charge is valued. {@link HarnessSupplyCoverageTest} covers the plugin's wiring.
 */
public class SlayerBraceletsTest
{
	@Test
	public void itemIdsMatchTheGameValues()
	{
		// Checked against net.runelite.api.gameval.ItemID in RuneLite 1.13.4.
		assertEquals(21183, ItemID.BRACELET_OF_SLAUGHTER);
		assertEquals(21177, ItemID.EXPEDITIOUS_BRACELET);
		assertEquals(30, SlayerBracelets.CHARGES_PER_BRACELET);
	}

	@Test
	public void eachActivationUsesOneCharge()
	{
		assertEquals(ItemID.BRACELET_OF_SLAUGHTER, SlayerBracelets.chargeUsed(
			"Your bracelet of slaughter prevents your slayer count from decreasing. It has 29 charges left."));
		assertEquals(ItemID.BRACELET_OF_SLAUGHTER, SlayerBracelets.chargeUsed(
			"Your bracelet of slaughter prevents your slayer count from decreasing. It has 1 charge left."));
		assertEquals("the last charge", ItemID.BRACELET_OF_SLAUGHTER, SlayerBracelets.chargeUsed(
			"Your bracelet of slaughter prevents your slayer count from decreasing. It then crumbles to dust."));
		assertEquals("the last charge, regenerated", ItemID.BRACELET_OF_SLAUGHTER, SlayerBracelets.chargeUsed(
			"Your bracelet of slaughter prevents your slayer count from decreasing. It then regenerates itself to full "
				+ "charge!"));
		assertEquals(ItemID.EXPEDITIOUS_BRACELET, SlayerBracelets.chargeUsed(
			"Your expeditious bracelet helps you progress your slayer task faster. It has 12 charges left."));
		assertEquals(ItemID.EXPEDITIOUS_BRACELET, SlayerBracelets.chargeUsed(
			"Your expeditious bracelet helps you progress your slayer faster. It then crumbles to dust."));
		assertEquals("with colour tags", ItemID.EXPEDITIOUS_BRACELET, SlayerBracelets.chargeUsed(
			"<col=ef1020>Your expeditious bracelet helps you progress your slayer task faster. It then regenerates "
				+ "itself to full charge!</col>"));
	}

	@Test
	public void ignoresJagexColourMarkup()
	{
		assertEquals(ItemID.BRACELET_OF_SLAUGHTER, SlayerBracelets.chargeUsed(
			"@mes_hl_red@Your bracelet of slaughter prevents your slayer count from decreasing. It has 9 charges left.</col>"));
	}

	@Test
	public void checksAndOtherMessagesUseNothing()
	{
		assertEquals(SlayerBracelets.NONE, SlayerBracelets.chargeUsed("Your bracelet of slaughter has 12 charges left."));
		assertEquals(SlayerBracelets.NONE, SlayerBracelets.chargeUsed("Your expeditious bracelet has 1 charge left."));
		assertEquals(SlayerBracelets.NONE, SlayerBracelets.chargeUsed("Your Bracelet of Slaughter has crumbled to dust."));
		assertEquals(SlayerBracelets.NONE, SlayerBracelets.chargeUsed("You eat the shark."));
		assertEquals(SlayerBracelets.NONE, SlayerBracelets.chargeUsed(""));
		assertEquals(SlayerBracelets.NONE, SlayerBracelets.chargeUsed(null));
	}

	@Test
	public void aChargeIsWorthAThirtiethOfTheBracelet()
	{
		final ItemTally used = new ItemTally();
		used.add(ItemID.BRACELET_OF_SLAUGHTER, 3);
		assertEquals(300L, ItemValuation.total(used, Collections.singletonMap(ItemID.BRACELET_OF_SLAUGHTER, 3_000L),
			Collections.singletonMap(ItemID.BRACELET_OF_SLAUGHTER, SlayerBracelets.CHARGES_PER_BRACELET)));
	}
}
