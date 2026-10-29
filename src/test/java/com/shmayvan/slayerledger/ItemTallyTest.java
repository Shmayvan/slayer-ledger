package com.shmayvan.slayerledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ItemTallyTest
{
	@Test
	public void addAccumulatesAndIgnoresNonPositive()
	{
		ItemTally t = new ItemTally();
		t.add(995, 100);
		t.add(995, 50);
		t.add(526, 1);
		t.add(526, 0);
		t.add(1, -5);
		assertEquals(150, t.get(995));
		assertEquals(1, t.get(526));
		assertEquals(0, t.get(1));
		assertEquals(2, t.size());
		assertEquals(Arrays.asList(526, 995), Arrays.asList(t.itemIds().toArray()));
	}

	@Test
	public void mergeAddsQuantities()
	{
		ItemTally a = new ItemTally();
		a.add(995, 100);
		a.add(526, 2);
		ItemTally b = new ItemTally();
		b.add(995, 25);
		b.add(1215, 1);
		a.merge(b);
		assertEquals(125, a.get(995));
		assertEquals(2, a.get(526));
		assertEquals(1, a.get(1215));
		// the merged-in tally is unchanged
		assertEquals(25, b.get(995));
		assertEquals(2, b.size());

		a.merge(null);
		assertEquals(3, a.size());
	}

	@Test
	public void mergeIntoItselfDoubles()
	{
		ItemTally a = new ItemTally();
		a.add(995, 10);
		a.add(526, 1);
		a.merge(a);
		assertEquals(20, a.get(995));
		assertEquals(2, a.get(526));
	}

	@Test
	public void addSaturatesInsteadOfOverflowing()
	{
		ItemTally t = new ItemTally();
		t.add(995, Long.MAX_VALUE - 1);
		t.add(995, 10);
		assertEquals(Long.MAX_VALUE, t.get(995));
	}

	@Test
	public void copyIsIndependent()
	{
		ItemTally a = new ItemTally();
		a.add(995, 10);
		ItemTally copy = new ItemTally(a);
		a.add(995, 5);
		assertEquals(10, copy.get(995));
		assertEquals(0, new ItemTally(null).size());
	}

	@Test
	public void mapFormRoundTripsThroughJson()
	{
		ItemTally t = new ItemTally();
		t.add(995, 12_345);
		t.add(1216, 3);
		Map<Integer, Long> map = t.toMap();
		Gson gson = new Gson();
		Type type = new TypeToken<Map<Integer, Long>>()
		{
		}.getType();
		String json = gson.toJson(map, type);
		assertEquals("{\"995\":12345,\"1216\":3}", json);
		Map<Integer, Long> back = gson.fromJson(json, type);
		assertEquals(t, ItemTally.fromMap(back));
	}

	@Test
	public void emptyTallyHasNoMapAndNullMapIsEmpty()
	{
		assertNull(new ItemTally().toMap());
		assertTrue(ItemTally.fromMap(null).isEmpty());

		Map<Integer, Long> withJunk = new HashMap<>();
		withJunk.put(995, 5L);
		withJunk.put(526, 0L);
		withJunk.put(1, null);
		withJunk.put(null, 4L);
		ItemTally t = ItemTally.fromMap(withJunk);
		assertEquals(1, t.size());
		assertEquals(5, t.get(995));
	}
}
