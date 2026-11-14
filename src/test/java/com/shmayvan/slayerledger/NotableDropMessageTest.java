package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * The notable drop line ({@link NotableDropMessage}): the two variants by kill number, the "paid for" clause only when
 * it is true, which drop of a kill it names, the NPC's article, quantities, a long name and the chat message's
 * escaping.
 */
public class NotableDropMessageTest
{
	private static final NotableDrop CHAINBODY = NotableDrop.item(3140, "Dragon chainbody", 1, 148_000L, 72_000L, 0);
	private static final NotableDrop WHIP = NotableDrop.item(4151, "Abyssal whip", 1, 1_400_000L, 72_000L, 0);

	@Test
	public void theDesignShapes()
	{
		assertEquals("Dragon chainbody (148K) from a dust devil. That's the task paid for.",
			NotableDropMessage.line(CHAINBODY, LootPriceMode.GE, "Dust devil", 141, 201, true));
		assertEquals("Abyssal whip (1.4M) at kill 140 of 201.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, "Abyssal demon", 140, 201, false));
	}

	@Test
	public void theVariantFollowsTheKillNumber()
	{
		// Odd: where it came from; even: the kill number. The same kill always gives the same line.
		assertEquals("Abyssal whip (1.4M) from an abyssal demon.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, "Abyssal demon", 139, 201, false));
		assertEquals("Abyssal whip (1.4M) at kill 140 of 201.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, "Abyssal demon", 140, 201, false));
		assertEquals("Abyssal whip (1.4M) from an abyssal demon.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, "Abyssal demon", 141, 201, false));
		// Kill numbers have commas.
		assertEquals("Abyssal whip (1.4M) at kill 1,000 of 1,200.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, "Abyssal demon", 1_000, 1_200, false));
		// The kill number unknown (0), or past the task's size: where it came from.
		assertEquals("Abyssal whip (1.4M) from an abyssal demon.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, "Abyssal demon", 0, 0, false));
		assertEquals("Abyssal whip (1.4M) from an abyssal demon.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, "Abyssal demon", 202, 201, false));
	}

	@Test
	public void withoutTheNpcsName()
	{
		assertEquals("the kill number on an odd kill too", "Abyssal whip (1.4M) at kill 139 of 201.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, null, 139, 201, false));
		assertEquals("Abyssal whip (1.4M).", NotableDropMessage.line(WHIP, LootPriceMode.GE, "  ", 0, 0, false));
		assertEquals("Abyssal whip (1.4M). That's the task paid for.",
			NotableDropMessage.line(WHIP, LootPriceMode.GE, null, 0, 0, true));
	}

	@Test
	public void theNpcsArticleAndBosses()
	{
		assertEquals("a dust devil", NotableDropMessage.source("Dust devil"));
		assertEquals("an abyssal demon", NotableDropMessage.source("Abyssal demon"));
		assertEquals("a greater nechryael", NotableDropMessage.source("Greater Nechryael"));
		assertEquals("an ankou", NotableDropMessage.source("<col=ffff00>Ankou</col>"));
		// A Slayer boss goes by its own name.
		assertEquals("Cerberus", NotableDropMessage.source("Cerberus"));
		assertEquals("Abyssal Sire", NotableDropMessage.source("Abyssal Sire"));
		assertNull(NotableDropMessage.source(null));
		assertEquals("Primordial crystal (28.1M) from Cerberus.", NotableDropMessage.line(
			NotableDrop.item(13231, "Primordial crystal", 1, 28_100_000L, 120_000L, 0), LootPriceMode.GE, "Cerberus", 3,
			5, false));
	}

	@Test
	public void quantitiesAndThePriceMode()
	{
		NotableDrop bars = NotableDrop.item(2363, "Runite bar", 25, 300_000L, 192_000L, 0);

		assertEquals("Runite bar x25 (300K) from a kurask.",
			NotableDropMessage.line(bars, LootPriceMode.GE, "Kurask", 1, 0, false));
		assertEquals("Runite bar x25 (192K) from a kurask.",
			NotableDropMessage.line(bars, LootPriceMode.HIGH_ALCH, "Kurask", 1, 0, false));
		assertEquals("Runite bar x1,500 (18M) from a kurask.", NotableDropMessage.line(
			NotableDrop.item(2363, "Runite bar", 1_500, 18_000_000L, null, 0), LootPriceMode.GE, "Kurask", 1, 0, false));
		// No value in the mode or no name: no line.
		assertNull(NotableDropMessage.line(NotableDrop.item(2363, "Runite bar", 25, 300_000L, null, 0),
			LootPriceMode.HIGH_ALCH, "Kurask", 1, 0, false));
		assertNull(NotableDropMessage.line(NotableDrop.item(2363, null, 25, 300_000L, null, 0), LootPriceMode.GE,
			"Kurask", 1, 0, false));
	}

	@Test
	public void theTaskIsPaidForOnlyWhenTheDropCoversTheSuppliesSoFar()
	{
		assertTrue(NotableDropMessage.paysForTask(148_000, 148_000L));
		assertTrue(NotableDropMessage.paysForTask(148_000, 90_000L));
		assertFalse("more than the drop", NotableDropMessage.paysForTask(148_000, 148_001L));
		assertFalse("no supplies used: nothing to pay for", NotableDropMessage.paysForTask(148_000, 0L));
		assertFalse("cost unknown or approximate", NotableDropMessage.paysForTask(148_000, null));

		assertEquals("Dragon chainbody (148K) from a dust devil.",
			NotableDropMessage.line(CHAINBODY, LootPriceMode.GE, "Dust devil", 141, 201, false));
	}

	@Test
	public void oneLinePerKillForItsMostValuableItem()
	{
		NotableDrop pet = NotableDrop.pet(NotableDrop.Pet.RECEIVED, 0);
		NotableDrop unnamed = NotableDrop.item(1, null, 1, 9_000_000L, null, 0);

		assertSame(WHIP, NotableDropMessage.pick(Arrays.asList(CHAINBODY, pet, WHIP, unnamed), LootPriceMode.GE));
		// At high alch the two are equal: the first.
		assertSame(CHAINBODY, NotableDropMessage.pick(Arrays.asList(CHAINBODY, WHIP), LootPriceMode.HIGH_ALCH));
		assertNull("a pet gets no line", NotableDropMessage.pick(Collections.singletonList(pet), LootPriceMode.GE));
		assertNull(NotableDropMessage.pick(null, LootPriceMode.GE));
	}

	@Test
	public void aLongNameStaysWhole()
	{
		NotableDrop drop = NotableDrop.item(1, "Imbued heart", 1, 3_000_000L, null, 0);
		assertEquals("Imbued heart (3M) from a thermonuclear smoke devil's long lost cousin.",
			NotableDropMessage.line(drop, LootPriceMode.GE, "Thermonuclear smoke devil's long lost cousin", 1, 0,
				false));
	}

	@Test
	public void theChatMessageHighlightsTheItemAndEscapesNames()
	{
		assertEquals("<colHIGHLIGHT>Dragon chainbody (148K)<colNORMAL> from a dust devil. That's the task paid for.",
			NotableDropMessage.chatMessage(CHAINBODY, LootPriceMode.GE, "Dust devil", 141, 201, true));
		NotableDrop tagged = NotableDrop.item(1, "Odd <b>item", 1, 200_000L, null, 0);
		assertEquals("<colHIGHLIGHT>Odd <lt>b<gt>item (200K)<colNORMAL> at kill 2 of 10.",
			NotableDropMessage.chatMessage(tagged, LootPriceMode.GE, null, 2, 10, false));
		assertNull(NotableDropMessage.chatMessage(null, LootPriceMode.GE, null, 2, 10, false));
	}
}
