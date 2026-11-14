package com.shmayvan.slayerledger;

/**
 * The advisor's metric switch (V2 plan 6): what the plan's recommendation is best at ({@link TaskPlanner}) and
 * what the Skip / block badge ranks by ({@link TaskAdvisor#badgeMetric}). Public because the plugin's "Advisor metric"
 * setting saves the choice ({@link SlayerLedgerConfig#advisorMetric}); the default is {@link #FASTEST_FINISH}. Pure (no
 * RuneLite types).
 */
public enum AdvisorMetric
{
	/** Least time for the kills left. The badge ranks by Slayer XP per hour. */
	FASTEST_FINISH("Fastest finish", "Fastest"),
	/** Most profit per hour (loot in the "Loot price" mode minus supplies at GE). The badge ranks by the same. */
	PROFIT_PER_HOUR("Profit per hour", "Profit/h"),
	/** Least supply cost for the kills left, at today's GE prices. The badge ranks by profit per hour. */
	CHEAPEST("Cheapest", "Cheapest");

	/** The metric the advisor uses when none is set. */
	static final AdvisorMetric DEFAULT = FASTEST_FINISH;

	private final String label;
	private final String shortLabel;

	AdvisorMetric(String label, String shortLabel)
	{
		this.label = label;
		this.shortLabel = shortLabel;
	}

	/** {@code metric}, or {@link #DEFAULT} for {@code null}. */
	static AdvisorMetric orDefault(AdvisorMetric metric)
	{
		return metric == null ? DEFAULT : metric;
	}

	/** The short name for the advisor card's tabs: "Fastest", "Profit/h" or "Cheapest". */
	String getShortLabel()
	{
		return shortLabel;
	}

	/** The name as the settings show it: "Fastest finish", "Profit per hour" or "Cheapest". */
	@Override
	public String toString()
	{
		return label;
	}
}
