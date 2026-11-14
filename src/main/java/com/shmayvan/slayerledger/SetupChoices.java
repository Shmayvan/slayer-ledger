package com.shmayvan.slayerledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The choices of a saved task's "Setup..." dialog (the history row's right-click menu) and of the menus of the
 * live task's setup toggles ({@link SetupToggles}): for each setup tag, a first choice that clears the
 * override and shows the detected value (for example {@code Detected: ~Barrage}), then every value the player can set.
 * Unknown can't be set: it is no override. Pure (no Swing); the dialog and the menus show the choices' labels.
 */
final class SetupChoices
{
	/** One choice: its label and the override it sets ({@code null}: none, the detected value counts). */
	static final class Choice<T>
	{
		private final String label;
		private final T value;

		private Choice(String label, T value)
		{
			this.label = label;
			this.value = value;
		}

		String getLabel()
		{
			return label;
		}

		/** The override this choice sets, or {@code null} to clear it. */
		T getValue()
		{
			return value;
		}

		/** The label: what a combo box shows. */
		@Override
		public String toString()
		{
			return label;
		}
	}

	private SetupChoices()
	{
	}

	/** The cannon choices: detected, Yes, No. */
	static List<Choice<Boolean>> cannon(SetupTags tags)
	{
		final List<Choice<Boolean>> choices = new ArrayList<>();
		choices.add(detected(SetupTags.shown(SetupTags.cannonLabel(tags.isDetectedCannon()),
			tags.isDetectedCannonEstimated())));
		choices.add(new Choice<>(SetupTags.cannonLabel(true), Boolean.TRUE));
		choices.add(new Choice<>(SetupTags.cannonLabel(false), Boolean.FALSE));
		return Collections.unmodifiableList(choices);
	}

	/** The style choices: detected, then every style but Unknown. */
	static List<Choice<SetupStyle>> style(SetupTags tags)
	{
		final List<Choice<SetupStyle>> choices = new ArrayList<>();
		choices.add(detected(SetupTags.shown(tags.getDetectedStyle().getLabel(), tags.isDetectedStyleEstimated())));
		for (SetupStyle style : SetupStyle.values())
		{
			if (style != SetupStyle.UNKNOWN)
			{
				choices.add(new Choice<>(style.getLabel(), style));
			}
		}
		return Collections.unmodifiableList(choices);
	}

	/** The gear focus choices: detected, then every focus but Unknown. */
	static List<Choice<GearFocus>> gear(SetupTags tags)
	{
		final List<Choice<GearFocus>> choices = new ArrayList<>();
		choices.add(detected(SetupTags.shown(tags.getDetectedGear().getLabel(), tags.isDetectedGearEstimated())));
		for (GearFocus gear : GearFocus.values())
		{
			if (gear != GearFocus.UNKNOWN)
			{
				choices.add(new Choice<>(gear.getLabel(), gear));
			}
		}
		return Collections.unmodifiableList(choices);
	}

	/** The index of the choice that sets {@code override}; 0 (detected) when there is none or it isn't listed. */
	static <T> int indexOf(List<Choice<T>> choices, T override)
	{
		if (override == null)
		{
			return 0;
		}
		for (int i = 0; i < choices.size(); i++)
		{
			if (Objects.equals(choices.get(i).getValue(), override))
			{
				return i;
			}
		}
		return 0;
	}

	/** The dialog's first line: {@code Setup of this <task> task:}. */
	static String prompt(LedgerRecord record)
	{
		return "Setup of this " + LedgerHistory.monster(record) + " task:";
	}

	private static <T> Choice<T> detected(String shownValue)
	{
		return new Choice<>("Detected: " + shownValue, null);
	}
}
