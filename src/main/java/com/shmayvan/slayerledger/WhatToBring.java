package com.shmayvan.slayerledger;

import java.util.List;

/** What to bring for the kills left: the recommendation and its supplies, named and sorted. */
final class WhatToBring
{
	private final SupplyRecommendation.Estimate estimate;
	private final List<BringRow> rows;

	WhatToBring(SupplyRecommendation.Estimate estimate, List<BringRow> rows)
	{
		this.estimate = estimate;
		this.rows = rows;
	}

	SupplyRecommendation.Estimate getEstimate()
	{
		return estimate;
	}

	List<BringRow> getRows()
	{
		return rows;
	}
}
