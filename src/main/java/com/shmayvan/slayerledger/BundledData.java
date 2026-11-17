package com.shmayvan.slayerledger;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import lombok.extern.slf4j.*;

/**
 * Reads the static lookup tables bundled as JSON files next to this class: the task icons
 * ({@code task-icons.json}), clue scroll ids, the Slayer boss table, the Catacombs variants and the item id lists of
 * the supply, charge and crushed loot rules. Each table class reads its file once, when the class is first used, into
 * the same immutable collections it held when the table was Java code.
 *
 * <p><b>Parsing.</b> RuneLite asks plugins to use the client's injected Gson rather than create their own, but these
 * tables are static, used by pure classes before the plugin is injected and in tests without it. So the files are read
 * with Gson's tree parser ({@link JsonParser}: no Gson instance, no reflection, no configuration) and each table class
 * picks its fields out of the tree. The plugin's own data, such as the spot table ({@link SlayerSpots}) and the ledger
 * files, is still read with the injected Gson.</p>
 *
 * <p><b>Bad data.</b> A missing, unreadable or malformed file is logged as a warning and its table is empty, as
 * {@link SlayerSpots#NONE} is for the spot table, so bad data never breaks the plugin. Fields that only explain an
 * entry (the gameval constant an id was taken from, item and NPC names, sources) are not read; the tests check the
 * constants and the sources, not the names.</p>
 */
@Slf4j
final class BundledData
{
	private BundledData()
	{
	}

	/**
	 * Reads the JSON object in {@code resource} (relative to this class) and turns it into a table with {@code reader};
	 * {@code fallback} when the resource is missing or unreadable, or the reader fails on it.
	 */
	static <T> T load(String resource, Function<JsonObject, T> reader, T fallback)
	{
		try (InputStream stream = BundledData.class.getResourceAsStream(resource))
		{
			if (stream == null)
			{
				log.warn("Bundled data {} is missing", resource);
				return fallback;
			}
			final JsonElement root = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
			return reader.apply(root.getAsJsonObject());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Unable to load the bundled data {}", resource, e);
			return fallback;
		}
	}

	/** The objects of {@code array}, each turned into a row with {@code reader}, in order. Unmodifiable. */
	static <T> List<T> rows(JsonArray array, Function<JsonObject, T> reader)
	{
		final List<T> rows = new ArrayList<>(array.size());
		for (JsonElement element : array)
		{
			rows.add(reader.apply(element.getAsJsonObject()));
		}
		return Collections.unmodifiableList(rows);
	}

	/** The {@code id}s of the objects of {@code array}, in order. */
	static int[] ids(JsonArray array)
	{
		final int[] ids = new int[array.size()];
		for (int i = 0; i < ids.length; i++)
		{
			ids[i] = array.get(i).getAsJsonObject().get("id").getAsInt();
		}
		return ids;
	}

	/**
	 * The {@code id}s of the list {@code list} in {@code resource}, added in order to a new {@link HashSet}.
	 * Unmodifiable; empty when the file can't be read.
	 */
	static Set<Integer> idSet(String resource, String list)
	{
		return load(resource, root ->
		{
			final Set<Integer> set = new HashSet<>();
			for (int id : ids(root.getAsJsonArray(list)))
			{
				set.add(id);
			}
			return Collections.unmodifiableSet(set);
		}, Collections.emptySet());
	}
}
