package com.shmayvan.slayerledger;

import java.util.Map;

/**
 * What the toxic blowpipe holds, for the overlay's supply runway: its darts and Zulrah's scales as its last check
 * message said ({@link BlowpipeMessage}), less the darts and scales counted as used since ({@link ChargedWeaponUsage}).
 * The client shows no live count, so until a check message is seen the contents are not known. Pure; the plugin uses
 * it on the client thread only.
 *
 * <p>The plugin keeps the contents across a time without a task and across End task, when shots aren't counted (so
 * darts fired then are not taken out), and forgets them when darts or scales are loaded, on Unload or Uncharge, at
 * logout and when the account changes.</p>
 */
final class BlowpipeContents
{
	/** An amount not known. */
	static final long UNKNOWN = -1;

	private int dartId = ChargedWeaponUsage.NO_ITEM;
	private long darts = UNKNOWN;
	private long scales = UNKNOWN;

	/**
	 * A blowpipe message: a check message sets the darts (type and count) and scales; a message that it holds no darts
	 * sets the darts to none, and the scales to none too when it says so, else keeps them.
	 */
	void messageSeen(BlowpipeMessage.Darts message)
	{
		if (message == null)
		{
			return;
		}
		if (!message.isLoaded())
		{
			dartId = ChargedWeaponUsage.NO_ITEM;
			darts = 0;
		}
		else if (message.getDartCount() >= 0)
		{
			dartId = message.getDartId();
			darts = message.getDartId() == ChargedWeaponUsage.NO_ITEM ? UNKNOWN : message.getDartCount();
		}
		if (message.getScales() >= 0)
		{
			scales = message.getScales();
		}
	}

	/** Takes what one tick's shot used ({@link ChargedWeaponUsage#update}: item id to amount) out of the contents. */
	void used(Map<Integer, Integer> used)
	{
		if (used == null || used.isEmpty())
		{
			return;
		}
		final Integer usedScales = used.get(SupplyRunway.ZULRAH_SCALES);
		if (usedScales != null && scales > 0)
		{
			scales = Math.max(0, scales - usedScales);
		}
		final Integer usedDarts = dartId == ChargedWeaponUsage.NO_ITEM ? null : used.get(dartId);
		if (usedDarts != null && darts > 0)
		{
			darts = Math.max(0, darts - usedDarts);
		}
	}

	/** The blowpipe has no Zulrah's scales left ({@link BlowpipeMessage#isOutOfScales}); its darts are as they were. */
	void outOfScales()
	{
		scales = 0;
	}

	/** Forgets the contents: darts or scales were loaded, it was unloaded or uncharged, or it may be another one. */
	void forget()
	{
		dartId = ChargedWeaponUsage.NO_ITEM;
		darts = UNKNOWN;
		scales = UNKNOWN;
	}

	/** Whether both the darts and the scales are known. */
	boolean isKnown()
	{
		return darts != UNKNOWN && scales != UNKNOWN;
	}

	/** The darts' item id, or {@link ChargedWeaponUsage#NO_ITEM} with none or an unknown type. */
	int getDartId()
	{
		return dartId;
	}

	/** Darts inside, or {@link #UNKNOWN}. */
	long getDarts()
	{
		return darts;
	}

	/** Zulrah's scales inside, or {@link #UNKNOWN}. */
	long getScales()
	{
		return scales;
	}
}
