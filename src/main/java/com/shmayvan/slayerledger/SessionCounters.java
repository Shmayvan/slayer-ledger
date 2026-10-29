package com.shmayvan.slayerledger;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

/**
 * The plain counters of one session (superior spawns, bank trips, deaths), shared by {@link SessionTracker} (which
 * counts them), {@link SessionTracker.Summary}, {@link SessionSnapshot} and {@link LedgerRecord}. Immutable; build one
 * with {@link #builder()} or {@link #toBuilder()}. No RuneLite dependencies.
 *
 * <p>Each counter is {@code null} when unknown: a snapshot or record from before it was counted. A builder starts every
 * counter at 0. The persisted classes keep these as flat fields of their own (their JSON must not change), so this
 * class copies them in and out ({@link #copyTo(SessionSnapshot)}, {@link #copyTo(LedgerRecord)},
 * {@link #from(SessionSnapshot)}).</p>
 *
 * <p>Adding a counter: add the field here (and to {@link #copyTo(SessionSnapshot)}, {@link #copyTo(LedgerRecord)},
 * {@link #from(SessionSnapshot)} and {@link #withUnknownAsZero()}), the same-named field to {@link SessionSnapshot} and
 * {@link LedgerRecord}, and count it in {@link SessionTracker} with {@code toBuilder()}.</p>
 */
@Value
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SessionCounters
{
	/** Every counter at 0: a new session. */
	static final SessionCounters ZERO = builder().build();

	/** Superior Slayer monster spawns seen while RUNNING, or {@code null} when unknown. */
	@Builder.Default
	Integer superiorsSpawned = 0;
	/** Bank trips made while RUNNING (see {@link BankTripCounter}), or {@code null} when unknown. */
	@Builder.Default
	Integer bankTrips = 0;
	/** Deaths of the local player while RUNNING, or {@code null} when unknown. */
	@Builder.Default
	Integer deaths = 0;

	/** The counters with every unknown or negative count as 0, as a restored session counts on from them. */
	SessionCounters withUnknownAsZero()
	{
		return builder()
			.superiorsSpawned(nonNegative(superiorsSpawned))
			.bankTrips(nonNegative(bankTrips))
			.deaths(nonNegative(deaths))
			.build();
	}

	/** The snapshot's counters as stored ({@code null} where the file has none). */
	static SessionCounters from(SessionSnapshot snapshot)
	{
		return builder()
			.superiorsSpawned(snapshot.getSuperiorsSpawned())
			.bankTrips(snapshot.getBankTrips())
			.deaths(snapshot.getDeaths())
			.build();
	}

	/** Sets the snapshot's counter fields. */
	void copyTo(SessionSnapshot snapshot)
	{
		snapshot.setSuperiorsSpawned(superiorsSpawned);
		snapshot.setBankTrips(bankTrips);
		snapshot.setDeaths(deaths);
	}

	/** Sets the record's counter fields. */
	void copyTo(LedgerRecord record)
	{
		record.setSuperiorsSpawned(superiorsSpawned);
		record.setBankTrips(bankTrips);
		record.setDeaths(deaths);
	}

	private static int nonNegative(Integer n)
	{
		return n == null ? 0 : Math.max(0, n);
	}
}
