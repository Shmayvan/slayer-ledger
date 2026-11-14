package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * The record-fast task line ({@link FastestTaskMessage}): what counts as a record (3 earlier completed tasks of the same
 * name, strictly faster per kill; not a tie, a partial task, another cannon use or Catacombs version), the margin at
 * this task's kills, and the line's words.
 */
public class FastestTaskMessageTest
{
	private static final long MINUTE = 60_000L;
	private static final AtomicInteger IDS = new AtomicInteger();

	@Test
	public void theDesignShape()
	{
		// Three earlier 200-kill tasks, the best 56 min; this one 52 min.
		LedgerRecord task = devils(200, 52 * MINUTE);
		List<LedgerRecord> history = Arrays.asList(devils(200, 56 * MINUTE), devils(200, 60 * MINUTE),
			devils(200, 70 * MINUTE), task);

		FastestTaskMessage.Record record = FastestTaskMessage.find(task, history);

		assertNotNull(record);
		assertEquals("Fastest Dust devils task yet: 52 min, 4 min under your old best.",
			FastestTaskMessage.line(record));
		assertEquals("<colHIGHLIGHT>Fastest Dust devils task yet:<colNORMAL> 52 min, 4 min under your old best.",
			FastestTaskMessage.chatMessage(record));
	}

	@Test
	public void needsThreeEarlierCompletedTasks()
	{
		LedgerRecord task = devils(200, 30 * MINUTE);

		assertNull(FastestTaskMessage.find(task, Collections.singletonList(task)));
		assertNull(FastestTaskMessage.find(task, Arrays.asList(devils(200, 60 * MINUTE), devils(200, 60 * MINUTE))));
		// A partial task is not one to beat.
		assertNull(FastestTaskMessage.find(task, Arrays.asList(devils(200, 60 * MINUTE), devils(200, 60 * MINUTE),
			partial(devils(200, 60 * MINUTE)))));
		assertNotNull(FastestTaskMessage.find(task, Arrays.asList(devils(200, 60 * MINUTE), devils(200, 60 * MINUTE),
			devils(200, 60 * MINUTE))));
	}

	@Test
	public void aTieOrSlowerIsNoRecord()
	{
		List<LedgerRecord> history = Arrays.asList(devils(200, 50 * MINUTE), devils(200, 60 * MINUTE),
			devils(200, 70 * MINUTE));

		assertNull("a tie", FastestTaskMessage.find(devils(200, 50 * MINUTE), history));
		// The same time per kill on a task of another size is a tie too.
		assertNull("a tie per kill", FastestTaskMessage.find(devils(100, 25 * MINUTE), history));
		assertNull("slower", FastestTaskMessage.find(devils(200, 51 * MINUTE), history));
		assertNotNull("a second faster", FastestTaskMessage.find(devils(200, 50 * MINUTE - 1_000), history));
	}

	@Test
	public void aPartialTaskIsNoRecord()
	{
		List<LedgerRecord> history = Arrays.asList(devils(200, 50 * MINUTE), devils(200, 60 * MINUTE),
			devils(200, 70 * MINUTE));

		assertNull(FastestTaskMessage.find(partial(devils(200, 10 * MINUTE)), history));
		LedgerRecord notCompleted = devils(200, 10 * MINUTE);
		notCompleted.setCompleted(false);
		assertNull(FastestTaskMessage.find(notCompleted, history));
		assertNull(FastestTaskMessage.find(null, history));
		assertNull(FastestTaskMessage.find(devils(200, 10 * MINUTE), null));
	}

	@Test
	public void onlyTheSameCannonUseAndVersionCount()
	{
		LedgerRecord task = devils(200, 30 * MINUTE);
		List<LedgerRecord> cannon = Arrays.asList(cannon(devils(200, 60 * MINUTE)), cannon(devils(200, 60 * MINUTE)),
			cannon(devils(200, 60 * MINUTE)));

		assertNull("cannon tasks are another group", FastestTaskMessage.find(task, cannon));
		assertNotNull(FastestTaskMessage.find(cannon(devils(200, 30 * MINUTE)), cannon));
		assertEquals("Fastest Dust devils (cannon) task yet: 30 min, 30 min under your old best.",
			FastestTaskMessage.line(FastestTaskMessage.find(cannon(devils(200, 30 * MINUTE)), cannon)));

		List<LedgerRecord> spectres = Arrays.asList(spectres(null, 60), spectres(null, 60), spectres(null, 60));
		assertNull("the Catacombs variant is another group",
			FastestTaskMessage.find(spectres("Deviant spectres", 30), spectres));
		assertNotNull(FastestTaskMessage.find(spectres(null, 30), spectres));
		// Matched as the history groups names: case and spaces don't matter.
		LedgerRecord upper = spectres(null, 30);
		upper.setMonster(" ABERRANT SPECTRES ");
		assertNotNull(FastestTaskMessage.find(upper, spectres));
	}

	@Test
	public void theMarginIsAtThisTasksKills()
	{
		// The best earlier task did 100 kills in 30 min (18 s a kill): 200 kills at that pace take 60 min.
		List<LedgerRecord> history = Arrays.asList(devils(100, 30 * MINUTE), devils(150, 60 * MINUTE),
			devils(300, 120 * MINUTE));

		FastestTaskMessage.Record record = FastestTaskMessage.find(devils(200, 52 * MINUTE), history);

		assertEquals(52 * MINUTE, record.getActiveMillis());
		assertEquals(8 * MINUTE, record.getMarginMillis());
		assertEquals("Fastest Dust devils task yet: 52 min, 8 min under your old best.", FastestTaskMessage.line(record));
	}

	@Test
	public void shortAndLongTimes()
	{
		List<LedgerRecord> history = Arrays.asList(devils(200, 90 * MINUTE + 20_000), devils(200, 100 * MINUTE),
			devils(200, 120 * MINUTE));

		assertEquals("Fastest Dust devils task yet: 1 h 30 min, <1 min under your old best.",
			FastestTaskMessage.line(FastestTaskMessage.find(devils(200, 90 * MINUTE), history)));
		assertEquals("Fastest Dust devils task yet: 20 min, 1 h 10 min under your old best.",
			FastestTaskMessage.line(FastestTaskMessage.find(devils(200, 20 * MINUTE + 20_000), history)));
	}

	@Test
	public void theTaskItselfIsLeftOutOfTheHistory()
	{
		LedgerRecord task = devils(200, 30 * MINUTE);
		LedgerRecord copy = devils(200, 30 * MINUTE);
		copy.setId(task.getId());
		List<LedgerRecord> history = new ArrayList<>(Arrays.asList(devils(200, 60 * MINUTE), devils(200, 60 * MINUTE),
			task, copy));

		assertNull("two others only", FastestTaskMessage.find(task, history));
		history.add(devils(200, 60 * MINUTE));
		assertNotNull(FastestTaskMessage.find(task, history));
	}

	@Test
	public void aLongName()
	{
		List<LedgerRecord> history = new ArrayList<>();
		for (int i = 0; i < 3; i++)
		{
			history.add(thermy(60));
		}
		assertEquals("Fastest Thermonuclear smoke devil task yet: 30 min, 30 min under your old best.",
			FastestTaskMessage.line(FastestTaskMessage.find(thermy(30), history)));
	}

	/** A completed Dust devils task without a cannon. */
	private static LedgerRecord devils(int kills, long activeMillis)
	{
		LedgerRecord record = new LedgerRecord();
		record.setId("task-" + IDS.incrementAndGet());
		record.setCreatureId(49);
		record.setMonster("Dust devils");
		record.setKills(kills);
		record.setActiveMillis(activeMillis);
		record.setCompleted(true);
		SetupTags.detected(false, SetupStyle.BARRAGE, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	private static LedgerRecord cannon(LedgerRecord record)
	{
		SetupTags.detected(true, SetupStyle.MELEE, GearFocus.UNKNOWN, SetupOverrides.NONE).copyTo(record);
		return record;
	}

	private static LedgerRecord partial(LedgerRecord record)
	{
		record.setPartial(true);
		return record;
	}

	/** A completed 100-kill Aberrant spectres task, on {@code variant} when given. */
	private static LedgerRecord spectres(String variant, int minutes)
	{
		LedgerRecord record = devils(100, minutes * MINUTE);
		record.setCreatureId(7);
		record.setMonster("Aberrant spectres");
		record.setVariant(variant);
		return record;
	}

	/** A completed 50-kill Smoke devils task done on the Thermonuclear smoke devil. */
	private static LedgerRecord thermy(int minutes)
	{
		LedgerRecord record = devils(50, minutes * MINUTE);
		record.setMonster("Smoke devils");
		record.setVariant("Thermonuclear smoke devil");
		return record;
	}
}
