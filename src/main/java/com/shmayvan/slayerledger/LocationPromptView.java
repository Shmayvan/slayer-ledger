package com.shmayvan.slayerledger;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The inline, non-modal "Name this location" prompt below the task box of {@link SlayerLedgerPanel}, shown when a
 * kill was counted in a region without a name: an editable name with suggestions, Save and Skip. EDT only.
 */
final class LocationPromptView
{
	/** Revalidates and repaints the panel after a change. */
	private final Runnable relayout;
	/** The client's injected Gson, used to read the bundled Wiki locations. */
	private final Gson gson;
	/** Saves the name for the region; {@code true} when it was saved, so the prompt is hidden. */
	private final BiPredicate<Integer, String> save;
	/** Skips the prompt for the region on this account, until "Ask again about skipped places". */
	private final IntConsumer skip;

	private final JPanel panel = new JPanel(new BorderLayout(0, 4));
	private final JLabel label = new JLabel(SlayerLedgerPanel.LOCATION_PROMPT_TITLE);
	/**
	 * The prompt's name: typed, or picked from the suggestions (Part B: the task's bundled Wiki locations, then the
	 * player's names for other regions). Editable; its editor is limited to {@link TaskLocation#MAX_NAME_LENGTH}.
	 */
	private final JComboBox<String> box = new JComboBox<>();
	/** The text field of {@link #box}'s editor. */
	private JTextComponent field;
	/** While the view itself changes {@link #box}, so its events are not the player's. */
	private boolean updating;
	/** The bundled task location list (Part B), read once when the prompt is first shown; {@code null} if it failed. */
	private TaskLocations taskLocations;
	private boolean taskLocationsLoaded;
	/** Resource of {@link #taskLocations}; tests point it at a missing or broken file. */
	private String taskLocationsResource = TaskLocations.SNAPSHOT_RESOURCE;
	private final JButton saveButton = new JButton("Save");
	private final JButton skipButton = new JButton("Skip");
	/** Region the prompt asks about, or {@link LocationTally#NO_REGION} while it is hidden. */
	private int region = LocationTally.NO_REGION;

	LocationPromptView(Gson gson, Runnable relayout, BiPredicate<Integer, String> save, IntConsumer skip)
	{
		this.gson = gson;
		this.relayout = relayout;
		this.save = save;
		this.skip = skip;
	}

	/** Adds the prompt to {@code content}, hidden. */
	void addTo(JPanel content)
	{
		// Names for tests, which find the parts by them; no visible effect.
		panel.setName("locationPrompt");
		box.setName("locationPrompt.box");
		saveButton.setName("locationPrompt.save");
		skipButton.setName("locationPrompt.skip");
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setToolTipText("<html>Kills here are in a map region you haven't named yet.<br>The name is "
			+ "kept for this account and used for every task here.<br>Skip stops asking about this place on this"
			+ " account;<br>the Ask again about skipped places setting undoes it.</html>");
		box.setEditable(true);
		// Sized from a short name, not the longest suggestion, so a long one never widens the sidebar.
		box.setPrototypeDisplayValue("Smoke Dungeon");
		box.setToolTipText("<html>Type a name of up to " + TaskLocation.MAX_NAME_LENGTH
			+ " characters,<br>or pick one: the Wiki's locations for this task, then your other names.</html>");
		field = (JTextComponent) box.getEditor().getEditorComponent();
		LengthLimitFilter.apply(field, TaskLocation.MAX_NAME_LENGTH);
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.setOpaque(false);
		for (JButton button : new JButton[]{saveButton, skipButton})
		{
			button.setMargin(new Insets(2, 4, 2, 4));
			buttons.add(button);
		}
		panel.add(label, BorderLayout.NORTH);
		panel.add(box, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);
		panel.setVisible(false);
		saveButton.addActionListener(e -> saveTyped());
		// Enter in the field saves too. Not the combo box's own "comboBoxEdited": Swing also fires it when the field
		// loses focus (clicking Skip or the game), which would save a half-typed name. A list pick only fills the field.
		if (field instanceof JTextField)
		{
			((JTextField) field).addActionListener(e ->
			{
				if (!updating)
				{
					saveTyped();
				}
			});
		}
		skipButton.addActionListener(e ->
		{
			int skipped = region;
			hide();
			if (LocationTally.isRegion(skipped))
			{
				skip.accept(skipped);
			}
		});
		SlayerLedgerPanel.addRow(content, panel);
	}

	/** The prompt's Save: an empty name (after {@link TaskLocation#normalizeName}) is not saved and the prompt stays. */
	private void saveTyped()
	{
		int saved = region;
		if (!LocationTally.isRegion(saved) || TaskLocation.normalizeName(field.getText()) == null)
		{
			return;
		}
		if (save.test(saved, field.getText()))
		{
			hide();
		}
	}

	/**
	 * Shows the prompt for map region {@code newRegion}, with an empty field and the names it suggests
	 * ({@link TaskLocation#suggestions}): the bundled Wiki locations of {@code monster}'s task, then {@code ownNames},
	 * the player's names for other regions. Without the bundled list (it failed to load) only the player's names are
	 * suggested. Nothing changes when it is already shown for that region.
	 */
	void show(int newRegion, String monster, List<String> ownNames)
	{
		if (!LocationTally.isRegion(newRegion) || newRegion == region)
		{
			return;
		}
		region = newRegion;
		TaskLocations bundled = taskLocations();
		List<String> suggestions = TaskLocation.suggestions(
			bundled == null ? Collections.emptyList() : bundled.locationsFor(monster), ownNames);
		updating = true;
		try
		{
			box.setModel(new DefaultComboBoxModel<>(suggestions.toArray(new String[0])));
			box.setSelectedItem(null);
		}
		finally
		{
			updating = false;
		}
		field.setText("");
		panel.setVisible(true);
		relayout.run();
	}

	/** The bundled task location list, read once; {@code null} when it could not be read (logged by its load). */
	private TaskLocations taskLocations()
	{
		if (!taskLocationsLoaded)
		{
			taskLocationsLoaded = true;
			taskLocations = gson == null ? null : TaskLocations.load(gson, taskLocationsResource);
		}
		return taskLocations;
	}

	/** Hides the prompt. */
	void hide()
	{
		region = LocationTally.NO_REGION;
		if (box.isPopupVisible())
		{
			box.setPopupVisible(false);
		}
		if (panel.isVisible())
		{
			panel.setVisible(false);
			relayout.run();
		}
	}

	/** The region the prompt asks about, or {@link LocationTally#NO_REGION} while hidden. */
	int getRegion()
	{
		// Test seam (through the panel): the region is not shown in the prompt.
		return panel.isVisible() ? region : LocationTally.NO_REGION;
	}

	/** Reads the bundled task location list from {@code resource} instead, before the prompt is first shown. */
	void setTaskLocationsResource(String resource)
	{
		// Test seam (through the panel): lets a test point the prompt at a missing or broken list.
		taskLocationsResource = resource;
	}
}
