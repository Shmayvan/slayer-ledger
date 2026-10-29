package com.shmayvan.slayerledger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CompletionSignalTest
{
	@Test
	public void firstReadingIsBaselineOnly()
	{
		CompletionSignal signal = new CompletionSignal();
		assertFalse(signal.update(10, 0, 0));
		assertTrue(signal.update(11, 0, 0));
		assertFalse(signal.update(11, 0, 0));
	}

	@Test
	public void resetRestoresBaselineBehaviour()
	{
		CompletionSignal signal = new CompletionSignal();
		signal.update(10, 0, 0);
		signal.reset();
		assertFalse(signal.update(11, 0, 0));
	}

	@Test
	public void lateLoginSyncIsNotACompletion()
	{
		CompletionSignal signal = new CompletionSignal();
		// counters read as zero before the server resynced them
		assertFalse(signal.update(0, 0, 0));
		assertFalse(signal.update(57, 0, 0));
		assertFalse(signal.update(57, 3, 0));
		assertTrue(signal.update(58, 3, 0));
	}

	@Test
	public void resyncAlongsideAnotherCounterIsNotACompletion()
	{
		CompletionSignal signal = new CompletionSignal();
		signal.update(0, 0, 0);
		assertFalse(signal.update(1, 40, 0));
	}

	@Test
	public void decreasesAreNotCompletions()
	{
		CompletionSignal signal = new CompletionSignal();
		signal.update(25, 0, 0);
		assertFalse(signal.update(0, 0, 0)); // streak reset
		assertTrue(signal.update(0, 0, 1)); // another counter increments by one
	}
}
