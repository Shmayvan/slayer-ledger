package com.shmayvan.slayerledger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

/**
 * The static tables read from bundled JSON files ({@link BundledData}): each loads exactly the table it held as
 * Java code before, in the same order, and stays unmodifiable; every id in the files is the gameval constant it names;
 * and a missing or malformed file gives an empty table instead of an error. The expected tables are the old Java code,
 * copied here with its gameval constants, so a wrong id in a file fails.
 */
public class BundledDataTest
{
	/** Every bundled table file read through {@link BundledData}. */
	static final List<String> FILES = Arrays.asList("task-icons.json", "clue-scroll-ids.json", "slayer-bosses.json",
		"catacombs-variants.json", "charge-exclusions.json", "supply-items.json", "crushed-loot.json");

	/** The JSON object in bundled file {@code name}, read for the test. */
	static JsonObject file(String name)
	{
		try (InputStream stream = BundledData.class.getResourceAsStream(name))
		{
			assertNotNull(name, stream);
			return new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		catch (java.io.IOException e)
		{
			throw new AssertionError(e);
		}
	}

	@Test
	public void everyIdIsTheGamevalConstantItNames() throws ReflectiveOperationException
	{
		int checked = 0;
		for (String name : FILES)
		{
			checked += checkConstants(name, file(name));
		}
		assertEquals(152 + 660 + 26 + 8 + 45 + 144 + 34, checked);
	}

	/** Checks each object's {@code constant} against its {@code id} or {@code npcId}; returns how many it checked. */
	private static int checkConstants(String file, JsonElement element) throws ReflectiveOperationException
	{
		int checked = 0;
		if (element.isJsonArray())
		{
			for (JsonElement child : element.getAsJsonArray())
			{
				checked += checkConstants(file, child);
			}
		}
		else if (element.isJsonObject())
		{
			final JsonObject object = element.getAsJsonObject();
			if (object.has("constant"))
			{
				final String[] constant = object.get("constant").getAsString().split("\\.");
				final JsonElement id = object.has("id") ? object.get("id") : object.get("npcId");
				assertEquals(file + " " + object, gameval(constant[0], constant[1]), id.getAsInt());
				checked++;
			}
			for (Map.Entry<String, JsonElement> child : object.entrySet())
			{
				checked += checkConstants(file, child.getValue());
			}
		}
		return checked;
	}

	/**
	 * The value of gameval constant {@code type.name} ({@code ItemID} or {@code NpcID}). Reflection reads the constant a
	 * file names; the files are data, so the compiler can't check them.
	 */
	private static int gameval(String type, String name) throws ReflectiveOperationException
	{
		assertTrue(type, type.equals("ItemID") || type.equals("NpcID"));
		final Field field = Class.forName("net.runelite.api.gameval." + type).getField(name);
		return field.getInt(null);
	}

	@Test
	public void taskIconsAreTheOldTable()
	{
		final Map<String, Integer> expected = new LinkedHashMap<>();
		expected.put("Aberrant spectres", ItemID.SLAYERGUIDE_ABERRANTSPECTER);
		expected.put("Abyssal demons", ItemID.SLAYERGUIDE_ABYSSALDEMON);
		expected.put("The Abyssal Sire", ItemID.ABYSSALSIRE_PET);
		expected.put("The Alchemical Hydra", ItemID.HYDRAPET);
		expected.put("Ankou", ItemID.ANKOU_HEAD);
		expected.put("Aquanites", ItemID.SLAYERGUIDE_AQUANITE);
		expected.put("Araxxor", ItemID.ARAXXORPET);
		expected.put("Araxytes", ItemID.POH_ARAXYTE_HEAD);
		expected.put("Aviansies", ItemID.ARCEUUS_CORPSE_AVIANSIE_INITIAL);
		expected.put("Bandits", ItemID.PICKPOCKET_GUIDE_DESERT_BANDIT);
		expected.put("Banshees", ItemID.SLAYERGUIDE_BANSHEE);
		expected.put("Barrows Brothers", ItemID.BARROWS_KARIL_HEAD);
		expected.put("Basilisks", ItemID.SLAYERGUIDE_BASILISK);
		expected.put("Bats", ItemID.RAIDS_BAT2_COOKED);
		expected.put("Bears", ItemID.ARCEUUS_CORPSE_BEAR_INITIAL);
		expected.put("Birds", ItemID.FEATHER);
		expected.put("Black demons", ItemID.BLACK_DEMON_MASK);
		expected.put("Black dragons", ItemID.DRAGONMASK_BLACK);
		expected.put("Black Knights", ItemID.BLACK_FULL_HELM);
		expected.put("Bloodveld", ItemID.SLAYERGUIDE_BLOODVELD);
		expected.put("Blue dragons", ItemID.DRAGONMASK_BLUE);
		expected.put("Brine rats", ItemID.OLAF2_BRINE_RAT_INV);
		expected.put("Callisto", ItemID.CALLISTO_PET);
		expected.put("Catablepon", ItemID.SOS_HALF_SKULL2);
		expected.put("Cave bugs", ItemID.SWAMP_CAVE_BUG);
		expected.put("Cave crawlers", ItemID.SLAYERGUIDE_CAVECRAWLER);
		expected.put("Cave horrors", ItemID.SLAYERGUIDE_HARMLESS_CAVE_HORROR);
		expected.put("Cave kraken", ItemID.CERT_EADGAR_FADE_TO_BLACK_INV);
		expected.put("Cave slimes", ItemID.SWAMP_CAVE_SLIME);
		expected.put("Cerberus", ItemID.HELL_PET);
		expected.put("Chaos druids", ItemID.ELDERCHAOS_HOOD);
		expected.put("The Chaos Elemental", ItemID.CHAOSELEPET);
		expected.put("The Chaos Fanatic", ItemID.STAFF_OF_ZAROS);
		expected.put("Cockatrice", ItemID.SLAYERGUIDE_COCKATRICE);
		expected.put("Cows", ItemID.COW_MASK);
		expected.put("Crabs", ItemID.HUNDRED_PIRATE_CRAB_SHELL_GAUNTLET);
		expected.put("Crawling hands", ItemID.SLAYERGUIDE_CRAWLINGHAND);
		expected.put("Crazy Archaeologists", ItemID.FEDORA);
		expected.put("Crocodiles", ItemID.GREEN_SALAMANDER);
		expected.put("Custodian Stalkers", ItemID.SLAYERGUIDE_CUSTODIAN_STALKER_MATURE);
		expected.put("Dagannoth", ItemID.POH_DAGGANOTH);
		expected.put("Dagannoth Kings", ItemID.PRIMEPET);
		expected.put("Dark beasts", ItemID.SLAYERGUIDE_DARK_BEAST);
		expected.put("Dark warriors", ItemID.BLACK_MED_HELM);
		expected.put("Deranged Archaeologist", ItemID.FOSSIL_SWAMP_DIARY);
		expected.put("Dogs", ItemID.POH_GUARD_DOG);
		expected.put("Drakes", ItemID.SLAYERGUIDE_DRAKE);
		expected.put("Duke Sucellus", ItemID.DUKESUCELLUSPET);
		expected.put("Dust devils", ItemID.SLAYERGUIDE_DUSTDEVIL);
		expected.put("Dwarves", ItemID.GRIM_WEAR_HELMET);
		expected.put("Earth warriors", ItemID.BRONZE_FULL_HELM_TRIM);
		expected.put("Elves", ItemID.PICKPOCKET_GUIDE_WOODELF);
		expected.put("Ents", ItemID.POH_TREE_2);
		expected.put("Fever spiders", ItemID.SLAYERGUIDE_FEVER_SPIDER);
		expected.put("Fire giants", ItemID.RTBRANDAPET);
		expected.put("Fleshcrawlers", ItemID.ARCEUUS_CORPSE_SCORPION_INITIAL);
		expected.put("Fossil island wyverns", ItemID.SLAYERGUIDE_FOSSILWYVERN);
		expected.put("Frost dragons", ItemID.FROST_DRAGON_BONES);
		expected.put("Gargoyles", ItemID.SLAYERGUIDE_GARGOYLE);
		expected.put("General Graardor", ItemID.BANDOSPET);
		expected.put("Ghosts", ItemID.AMULET_OF_GHOSTSPEAK);
		expected.put("Ghouls", ItemID.TRICK_OR_TREAT_HEAD);
		expected.put("The Giant Mole", ItemID.MOLEPET);
		expected.put("Goblins", ItemID.ARCEUUS_CORPSE_GOBLIN_INITIAL);
		expected.put("Greater demons", ItemID.GREATER_DEMON_MASK);
		expected.put("Green dragons", ItemID.DRAGONMASK_GREEN);
		expected.put("The Grotesque Guardians", ItemID.DUSKPET);
		expected.put("Gryphons", ItemID.SLAYERGUIDE_GRYPHON);
		expected.put("Harpie bug swarms", ItemID.SLAYERGUIDE_SWARM);
		expected.put("Hellhounds", ItemID.POH_HELLHOUND);
		expected.put("Hill giants", ItemID.ARCEUUS_CORPSE_GIANT_INITIAL);
		expected.put("Hobgoblins", ItemID.POH_HOBGOBLIN);
		expected.put("Hydras", ItemID.SLAYERGUIDE_HYDRA);
		expected.put("Icefiends", ItemID.FD_ICEDIAMOND);
		expected.put("Ice giants", ItemID.RTELDRICPET);
		expected.put("Ice warriors", ItemID.MITHRIL_FULL_HELM_TRIM);
		expected.put("Infernal mages", ItemID.SLAYERGUIDE_INFERNALMAGE);
		expected.put("TzTok-Jad", ItemID.JAD_PET);
		expected.put("Jellies", ItemID.SLAYERGUIDE_JELLY);
		expected.put("Jungle horrors", ItemID.ARCEUUS_CORPSE_HORROR_INITIAL);
		expected.put("Kalphites", ItemID.POH_KALPHITE_SOLDIER);
		expected.put("The Kalphite Queen", ItemID.KQPET_WALKING);
		expected.put("Killerwatts", ItemID.SLAYERGUIDE_KILLERWATT);
		expected.put("The King Black Dragon", ItemID.KBDPET);
		expected.put("The Cave Kraken Boss", ItemID.KRAKENPET);
		expected.put("Kree'arra", ItemID.ARMADYLPET);
		expected.put("K'ril Tsutsaroth", ItemID.ZAMORAKPET);
		expected.put("Kurask", ItemID.SLAYERGUIDE_KURASK);
		expected.put("Lava Dragons", ItemID.LAVA_SCALE);
		expected.put("Lesser demons", ItemID.LESSER_DEMON_MASK);
		expected.put("Lesser Nagua", ItemID.SLAYERGUIDE_LESSER_NAGUA);
		expected.put("Lizardmen", ItemID.LIZARDMAN_FANG);
		expected.put("Lizards", ItemID.SLAYERGUIDE_LIZARD);
		expected.put("The Maggot King", ItemID.MAGGOTKINGPET);
		expected.put("Magic axes", ItemID.IRON_BATTLEAXE);
		expected.put("Mammoths", ItemID.BARBASSAULT_ATT_HORN_01);
		expected.put("Metal dragons", ItemID.POH_STEEL_DRAGON);
		expected.put("Minotaurs", ItemID.ARCEUUS_CORPSE_MINOTAUR_INITIAL);
		expected.put("Mogres", ItemID.SLAYERGUIDE_MOGRE);
		expected.put("Molanisks", ItemID.SLAYERGUIDE_MOLANISK);
		expected.put("Monkeys", ItemID.ARCEUUS_CORPSE_MONKEY_INITIAL);
		expected.put("Moss giants", ItemID.MOSSY_KEY);
		expected.put("Mutated zygomites", ItemID.SLAYER_ZYGOMITE_OBJECT);
		expected.put("Nechryael", ItemID.SLAYERGUIDE_NECHRYAEL);
		expected.put("Ogres", ItemID.ARCEUUS_CORPSE_OGRE_INITIAL);
		expected.put("Otherworldly beings", ItemID.SECRET_GHOST_HAT);
		expected.put("The Phantom Muspah", ItemID.MUSPAHPET);
		expected.put("Pirates", ItemID.BREW_RED_PIRATE_HAT);
		expected.put("Pyrefiends", ItemID.SLAYERGUIDE_PYRFIEND);
		expected.put("Rats", ItemID.RATS_TAIL);
		expected.put("Red dragons", ItemID.POH_DRAGON);
		expected.put("Revenants", ItemID.WILD_CAVE_BRACELET_CHARGED);
		expected.put("Rockslugs", ItemID.SLAYERGUIDE_ROCKSLUG);
		expected.put("Rogues", ItemID.ROGUESDEN_HELM);
		expected.put("Sarachnis", ItemID.SARACHNISPET);
		expected.put("Scabarites", ItemID.NTK_SCARAB_GOLD);
		expected.put("Scorpia", ItemID.SCORPIA_PET);
		expected.put("Scorpions", ItemID.ARCEUUS_CORPSE_SCORPION_INITIAL);
		expected.put("Sea snakes", ItemID.HUNDRED_ILM_SNAKE_CORPSE);
		expected.put("Shades", ItemID.BLACKROBETOP);
		expected.put("Shadow warriors", ItemID.BLACK_FULL_HELM);
		expected.put("The Shellbane Gryphon", ItemID.GRYPHONBOSSPET_ADULT);
		expected.put("Skeletal wyverns", ItemID.SLAYERGUIDE_SKELETALWYVERN);
		expected.put("Skeletons", ItemID.POH_SKELETON_GUARD);
		expected.put("Smoke devils", ItemID.CERT_GUIDE_ICON_DUMMY);
		expected.put("Sourhogs", ItemID.PORCINE_SOURHOG_TROPHY);
		expected.put("Spiders", ItemID.POH_SPIDER);
		expected.put("Spiritual creatures", ItemID.DRAGON_BOOTS);
		expected.put("Suqahs", ItemID.SUQKA_TOOTH);
		expected.put("Terror dogs", ItemID.SLAYERGUIDE_TERRORDOG);
		expected.put("The Leviathan", ItemID.LEVIATHANPET);
		expected.put("The Whisperer", ItemID.WHISPERERPET);
		expected.put("The Thermonuclear Smoke Devil", ItemID.SMOKEPET);
		expected.put("Trolls", ItemID.POH_TROLL);
		expected.put("Turoth", ItemID.SLAYERGUIDE_TUROTH);
		expected.put("Tzhaar", ItemID.ARCEUUS_CORPSE_TZHAAR_INITIAL);
		expected.put("Vampyres", ItemID.STAKE);
		expected.put("Vardorvis", ItemID.VARDORVISPET);
		expected.put("Venators", ItemID.SLAYERGUIDE_VENATOR);
		expected.put("Venenatis", ItemID.VENENATIS_PET);
		expected.put("Vet'ion", ItemID.VETION_PET);
		expected.put("Vorkath", ItemID.VORKATHPET);
		expected.put("Wall beasts", ItemID.SWAMP_WALLBEAST);
		expected.put("Warped Creatures", ItemID.POG_SLAYER_DUMMY_WARPED_TERRORBIRD);
		expected.put("Waterfiends", ItemID.WATER_ORB);
		expected.put("Werewolves", ItemID.DAGGER_WOLFBANE);
		expected.put("Wolves", ItemID.GREY_WOLF_FUR);
		expected.put("Wyrms", ItemID.SLAYERGUIDE_WYRM);
		expected.put("Commander Zilyana", ItemID.SARADOMINPET);
		expected.put("Zombies", ItemID.TRICK_OR_TREAT_HEAD);
		expected.put("TzKal-Zuk", ItemID.INFERNOPET_ZUK);
		expected.put("Zulrah", ItemID.SNAKEPET);
		for (Map.Entry<String, Integer> icon : expected.entrySet())
		{
			assertEquals(icon.getKey(), icon.getValue(), TaskIcons.itemId(icon.getKey()));
		}
		assertEquals(152, expected.size());
		assertEquals("and no other", expected.size(), file("task-icons.json").getAsJsonArray("tasks").size());
	}

	@Test
	public void clueScrollIdsAreTheOldTable()
	{
		assertArrayEquals(new int[]{
			23182,
		}, ClueScrollIds.BEGINNER);
		assertArrayEquals(new int[]{
			2677, 2678, 2679, 2680, 2681, 2682, 2683, 2684, 2685, 2686, 2687, 2688,
			2689, 2690, 2691, 2692, 2693, 2694, 2695, 2696, 2697, 2698, 2699, 2700,
			2701, 2702, 2703, 2704, 2705, 2706, 2707, 2708, 2709, 2710, 2711, 2712,
			2713, 2716, 2719, 3490, 3491, 3492, 3493, 3494, 3495, 3496, 3497, 3498,
			3499, 3500, 3501, 3502, 3503, 3504, 3505, 3506, 3507, 3508, 3509, 3510,
			3512, 3513, 3514, 3515, 3516, 3518, 7236, 7238, 10180, 10182, 10184, 10186,
			10188, 10190, 10192, 10194, 10196, 10198, 10200, 10202, 10204, 10206, 10208, 10210,
			10212, 10214, 10216, 10218, 10220, 10222, 10224, 10226, 10228, 10230, 10232, 12162,
			12164, 12166, 12167, 12168, 12169, 12170, 12172, 12173, 12174, 12175, 12176, 12177,
			12178, 12179, 12181, 12182, 12183, 12184, 12185, 12186, 12187, 12188, 12189, 12190,
			12191, 12192, 19814, 19816, 19817, 19818, 19819, 19820, 19821, 19822, 19823, 19824,
			19825, 19826, 19828, 19829, 19830, 19831, 19833, 22001, 23149, 23150, 23151, 23152,
			23153, 23154, 23155, 23156, 23157, 23158, 23159, 23160, 23161, 23162, 23163, 23164,
			23165, 23166, 25788, 25789, 28913, 28914, 29853, 29854, 30928, 31268,
		}, ClueScrollIds.EASY);
		assertArrayEquals(new int[]{
			2801, 2803, 2805, 2807, 2809, 2811, 2813, 2815, 2817, 2819, 2821, 2823,
			2825, 2827, 2829, 2831, 2833, 2835, 2837, 2839, 2841, 2843, 2845, 2847,
			2848, 2849, 2851, 2853, 2855, 2856, 2857, 2858, 3582, 3584, 3586, 3588,
			3590, 3592, 3594, 3596, 3598, 3599, 3601, 3602, 3604, 3605, 3607, 3609,
			3610, 3611, 3612, 3613, 3614, 3615, 3616, 3617, 3618, 7274, 7276, 7278,
			7280, 7282, 7284, 7286, 7288, 7290, 7292, 7294, 7296, 7298, 7300, 7301,
			7303, 7304, 7305, 7307, 7309, 7311, 7313, 7315, 7317, 10254, 10256, 10258,
			10260, 10262, 10264, 10266, 10268, 10270, 10272, 10274, 10276, 10278, 12021, 12023,
			12025, 12027, 12029, 12031, 12033, 12035, 12037, 12039, 12041, 12043, 12045, 12047,
			12049, 12051, 12053, 12055, 12057, 12059, 12061, 12063, 12065, 12067, 12069, 12071,
			19734, 19736, 19738, 19740, 19742, 19744, 19746, 19748, 19750, 19752, 19754, 19756,
			19758, 19760, 19762, 19764, 19766, 19768, 19770, 19772, 19774, 19776, 19778, 19780,
			23046, 23131, 23133, 23135, 23136, 23137, 23138, 23139, 23140, 23141, 23142, 23143,
			25783, 25784, 28907, 28908, 28909, 29857, 29858, 30933, 30935, 31274, 31275,
		}, ClueScrollIds.MEDIUM);
		assertArrayEquals(new int[]{
			2722, 2723, 2725, 2727, 2729, 2731, 2733, 2735, 2737, 2739, 2741, 2743,
			2745, 2747, 2773, 2774, 2776, 2778, 2780, 2782, 2783, 2785, 2786, 2788,
			2790, 2792, 2793, 2794, 2796, 2797, 2799, 3520, 3522, 3524, 3525, 3526,
			3528, 3530, 3532, 3534, 3536, 3538, 3540, 3542, 3544, 3546, 3548, 3550,
			3552, 3554, 3556, 3558, 3560, 3562, 3564, 3566, 3568, 3570, 3572, 3573,
			3574, 3575, 3577, 3579, 3580, 7239, 7241, 7243, 7245, 7247, 7248, 7249,
			7250, 7251, 7252, 7253, 7254, 7255, 7256, 7258, 7260, 7262, 7264, 7266,
			7268, 7270, 7272, 10234, 10236, 10238, 10240, 10242, 10244, 10246, 10248, 10250,
			10252, 12542, 12544, 12546, 12548, 12550, 12552, 12554, 12556, 12558, 12560, 12562,
			12564, 12566, 12568, 12570, 12572, 12574, 12576, 12578, 12581, 12584, 12587, 12590,
			19840, 19842, 19844, 19846, 19848, 19850, 19852, 19853, 19854, 19856, 19857, 19858,
			19860, 19862, 19864, 19866, 19868, 19870, 19872, 19874, 19876, 19878, 19880, 19882,
			19884, 19886, 19888, 19890, 19892, 19894, 19896, 19898, 19900, 19902, 19904, 19906,
			19908, 19910, 21526, 21527, 23045, 23167, 23168, 23169, 23170, 23172, 23174, 23175,
			23176, 23177, 23178, 23179, 23180, 23181, 24493, 25790, 25791, 25792, 26566, 28915,
			28916, 28918, 29859, 30929, 30931, 31272, 31273,
		}, ClueScrollIds.HARD);
		assertArrayEquals(new int[]{
			12073, 12074, 12075, 12076, 12077, 12078, 12079, 12080, 12081, 12082, 12083, 12085,
			12086, 12087, 12088, 12089, 12090, 12091, 12092, 12093, 12094, 12095, 12096, 12097,
			12098, 12099, 12100, 12101, 12102, 12103, 12104, 12105, 12106, 12107, 12108, 12109,
			12110, 12111, 12113, 12114, 12115, 12116, 12117, 12118, 12119, 12120, 12121, 12122,
			12123, 12124, 12125, 12126, 12127, 12130, 12132, 12133, 12134, 12135, 12136, 12137,
			12138, 12140, 12141, 12142, 12143, 12144, 12145, 12146, 12147, 12148, 12149, 12150,
			12151, 12152, 12153, 12154, 12155, 12156, 12157, 12158, 12159, 19782, 19783, 19784,
			19785, 19786, 19787, 19788, 19789, 19790, 19791, 19792, 19793, 19794, 19795, 19796,
			19797, 19798, 19799, 19800, 19801, 19802, 19803, 19804, 19805, 19806, 19807, 19808,
			19809, 19810, 19811, 19813, 21524, 21525, 22000, 23144, 23145, 23146, 23147, 23148,
			23770, 24253, 24773, 25498, 25499, 25786, 25787, 26943, 26944, 28910, 28911, 28912,
			29855, 29856, 30932, 31269, 31270, 31271,
		}, ClueScrollIds.ELITE);
		assertArrayEquals(new int[]{
			19835,
		}, ClueScrollIds.MASTER);
	}

	@Test
	public void slayerBossesAreTheOldTableInItsOrder()
	{
		final List<String> rows = new ArrayList<>();
		for (SlayerBosses.Boss boss : SlayerBosses.all())
		{
			rows.add(boss.getTask() + " / " + boss.getName() + " / " + boss.getNpcNames());
		}
		assertEquals(Arrays.asList(
			"Gargoyles / Grotesque Guardians / [Dusk, Dawn]",
			"Cave kraken / Kraken / [Kraken]",
			"Hellhounds / Cerberus / [Cerberus]",
			"Smoke devils / Thermonuclear smoke devil / [Thermonuclear smoke devil]",
			"Abyssal demons / Abyssal Sire / [Abyssal Sire]",
			"Hydras / Alchemical Hydra / [Alchemical Hydra]",
			"Araxytes / Araxxor / [Araxxor]",
			"Spiders / Araxxor / [Araxxor]",
			"Gryphons / Shellbane gryphon / [Shellbane gryphon]"), rows);
		assertUnmodifiable(SlayerBosses.all());
		assertUnmodifiable(SlayerBosses.all().get(0).getNpcNames());
		// The superiors, which the table keeps as names to match: the file lists these, and each one is read.
		final List<String> superiors = Arrays.asList("Marble gargoyle", "Nuclear smoke devil", "Greater abyssal demon",
			"Colossal Hydra", "Dreadborn Araxyte", "Dire gryphon", "Abhorrent spectre", "Repugnant spectre",
			"Nechryarch", "Insatiable Bloodveld", "Insatiable mutated Bloodveld", "Vitreous Jelly",
			"Vitreous Warped Jelly", "Vitreous Chilled Jelly", "Screaming banshee", "Screaming twisted banshee");
		final List<String> listed = new ArrayList<>();
		for (JsonElement npc : file("slayer-bosses.json").getAsJsonArray("superiors"))
		{
			listed.add(npc.getAsJsonObject().get("name").getAsString());
		}
		assertEquals(superiors, listed);
		for (String name : superiors)
		{
			assertTrue(name, SlayerBosses.isSuperiorName(name));
		}
	}

	@Test
	public void catacombsVariantsAreTheOldTableInItsOrder()
	{
		final List<String> expected = Arrays.asList(
			variant("Aberrant spectres", NpcID.KOUREND_SPECTRE, "Deviant spectre", "Deviant spectres"),
			variant("Nechryael", NpcID.KOUREND_NECHRYAEL, "Greater Nechryael", "Greater nechryael"),
			variant("Bloodveld", NpcID.KOUREND_BLOODVELD, "Mutated Bloodveld", "Mutated bloodveld"),
			variant("Jellies", NpcID.KOUREND_JELLY, "Warped Jelly", "Warped jellies"),
			variant("Banshees", NpcID.KOUREND_BANSHEE, "Twisted Banshee", "Twisted banshees"),
			variant("Black dragons", NpcID.KOUREND_BLACK_DRAGON, "Brutal black dragon", "Brutal black dragons"),
			variant("Blue dragons", NpcID.KOUREND_BLUE_DRAGON, "Brutal blue dragon", "Brutal blue dragons"),
			variant("Red dragons", NpcID.KOUREND_RED_DRAGON, "Brutal red dragon", "Brutal red dragons"));
		final List<String> rows = new ArrayList<>();
		for (CatacombsVariants.Variant variant : CatacombsVariants.all())
		{
			rows.add(variant(variant.getTask(), variant.getNpcId(), variant.getNpcName(), variant.getName()));
		}
		assertEquals(expected, rows);
		assertUnmodifiable(CatacombsVariants.all());
	}

	private static String variant(String task, int npcId, String npcName, String name)
	{
		return task + " / " + npcId + " / " + npcName + " / " + name;
	}

	@Test
	public void chargeExclusionsAreTheOldSets()
	{
		assertSameSet(addedInOrder(
			ItemID.TOXIC_BLOWPIPE, ItemID.TOXIC_BLOWPIPE_LOADED, ItemID.TOXIC_BLOWPIPE_ORNAMENT,
			ItemID.TOXIC_BLOWPIPE_LOADED_ORNAMENT,
			ItemID.TOTS, ItemID.TOTS_CHARGED, ItemID.TOTS_UNCHARGED, ItemID.TOTS_I_CHARGED, ItemID.TOTS_I_UNCHARGED,
			ItemID.TOTS_ORN, ItemID.TOTS_CHARGED_ORN, ItemID.TOTS_UNCHARGED_ORN, ItemID.TOTS_I_CHARGED_ORN,
			ItemID.TOTS_I_UNCHARGED_ORN,
			ItemID.TOXIC_TOTS_CHARGED, ItemID.TOXIC_TOTS_UNCHARGED, ItemID.TOXIC_TOTS_I_CHARGED,
			ItemID.TOXIC_TOTS_I_UNCHARGED, ItemID.TOXIC_TOTS_CHARGED_ORN, ItemID.TOXIC_TOTS_UNCHARGED_ORN,
			ItemID.TOXIC_TOTS_I_CHARGED_ORN, ItemID.TOXIC_TOTS_I_UNCHARGED_ORN,
			ItemID.SANGUINESTI_STAFF, ItemID.SANGUINESTI_STAFF_UNCHARGED, ItemID.SANGUINESTI_STAFF_OR,
			ItemID.SANGUINESTI_STAFF_UNCHARGED_OR, ItemID.TUMEKENS_SHADOW, ItemID.TUMEKENS_SHADOW_UNCHARGED), ChargeExclusions.WEAPONS);
		assertSameSet(addedInOrder(
			ItemID.BRONZE_DART, ItemID.IRON_DART, ItemID.STEEL_DART, ItemID.BLACK_DART, ItemID.MITHRIL_DART,
			ItemID.ADAMANT_DART, ItemID.RUNE_DART, ItemID.AMETHYST_DART, ItemID.DRAGON_DART,
			ItemID.SNAKEBOSS_SCALE, ItemID.DEATHRUNE, ItemID.CHAOSRUNE, ItemID.FIRERUNE, ItemID.COINS,
			ItemID.BLOODRUNE, ItemID.SOULRUNE, ItemID.AETHERRUNE), ChargeExclusions.MATERIALS);
	}

	@Test
	public void supplyItemsAreTheOldTables()
	{
		final Map<Integer, int[]> food = new HashMap<>();
		// Pizzas: full, then 1/2.
		addStages(food, ItemID.PLAIN_PIZZA, ItemID.HALF_PLAIN_PIZZA);
		addStages(food, ItemID.MEAT_PIZZA, ItemID.HALF_MEAT_PIZZA);
		addStages(food, ItemID.ANCHOVIE_PIZZA, ItemID.HALF_ANCHOVIE_PIZZA);
		addStages(food, ItemID.PINEAPPLE_PIZZA, ItemID.HALF_PINEAPPLE_PIZZA);
		// Pies: full, then half (then the pie dish, which is not food).
		addStages(food, ItemID.REDBERRY_PIE, ItemID.HALF_A_REDBERRY_PIE);
		addStages(food, ItemID.MEAT_PIE, ItemID.HALF_A_MEAT_PIE);
		addStages(food, ItemID.APPLE_PIE, ItemID.HALF_AN_APPLE_PIE);
		addStages(food, ItemID.GARDEN_PIE, ItemID.HALF_GARDEN_PIE);
		addStages(food, ItemID.FISH_PIE, ItemID.HALF_FISH_PIE);
		addStages(food, ItemID.BOTANICAL_PIE, ItemID.HALF_BOTANICAL_PIE);
		addStages(food, ItemID.MUSHROOM_PIE, ItemID.HALF_MUSHROOM_PIE);
		addStages(food, ItemID.ADMIRAL_PIE, ItemID.HALF_ADMIRAL_PIE);
		addStages(food, ItemID.DRAGONFRUIT_PIE, ItemID.HALF_DRAGONFRUIT_PIE);
		addStages(food, ItemID.WILD_PIE, ItemID.HALF_WILD_PIE);
		addStages(food, ItemID.SUMMER_PIE, ItemID.HALF_SUMMER_PIE);
		// Cakes: full, then 2/3, then a slice.
		addStages(food, ItemID.CAKE, ItemID.PARTIAL_CAKE, ItemID.CAKE_SLICE);
		addStages(food, ItemID.CHOCOLATE_CAKE, ItemID.PARTIAL_CHOCOLATE_CAKE, ItemID.CHOCOLATE_SLICE);
		assertSameStages(food, SupplyTracker.MULTI_BITE_FOOD);

		final Map<Integer, int[]> items = new HashMap<>();
		addStages(items, ItemID.RING_OF_DUELING_8, ItemID.RING_OF_DUELING_7, ItemID.RING_OF_DUELING_6,
			ItemID.RING_OF_DUELING_5, ItemID.RING_OF_DUELING_4, ItemID.RING_OF_DUELING_3, ItemID.RING_OF_DUELING_2,
			ItemID.RING_OF_DUELING_1);
		// Games necklace.
		addStages(items, ItemID.NECKLACE_OF_MINIGAMES_8, ItemID.NECKLACE_OF_MINIGAMES_7,
			ItemID.NECKLACE_OF_MINIGAMES_6, ItemID.NECKLACE_OF_MINIGAMES_5, ItemID.NECKLACE_OF_MINIGAMES_4,
			ItemID.NECKLACE_OF_MINIGAMES_3, ItemID.NECKLACE_OF_MINIGAMES_2, ItemID.NECKLACE_OF_MINIGAMES_1);
		addStages(items, ItemID.AMULET_OF_GLORY_6, ItemID.AMULET_OF_GLORY_5, ItemID.AMULET_OF_GLORY_4,
			ItemID.AMULET_OF_GLORY_3, ItemID.AMULET_OF_GLORY_2, ItemID.AMULET_OF_GLORY_1);
		// Amulet of glory (t).
		addStages(items, ItemID.TRAIL_AMULET_OF_GLORY_6, ItemID.TRAIL_AMULET_OF_GLORY_5,
			ItemID.TRAIL_AMULET_OF_GLORY_4, ItemID.TRAIL_AMULET_OF_GLORY_3, ItemID.TRAIL_AMULET_OF_GLORY_2,
			ItemID.TRAIL_AMULET_OF_GLORY_1);
		// Skills necklace.
		addStages(items, ItemID.JEWL_NECKLACE_OF_SKILLS_6, ItemID.JEWL_NECKLACE_OF_SKILLS_5,
			ItemID.JEWL_NECKLACE_OF_SKILLS_4, ItemID.JEWL_NECKLACE_OF_SKILLS_3, ItemID.JEWL_NECKLACE_OF_SKILLS_2,
			ItemID.JEWL_NECKLACE_OF_SKILLS_1);
		// Combat bracelet.
		addStages(items, ItemID.JEWL_BRACELET_OF_COMBAT_6, ItemID.JEWL_BRACELET_OF_COMBAT_5,
			ItemID.JEWL_BRACELET_OF_COMBAT_4, ItemID.JEWL_BRACELET_OF_COMBAT_3, ItemID.JEWL_BRACELET_OF_COMBAT_2,
			ItemID.JEWL_BRACELET_OF_COMBAT_1);
		addStages(items, ItemID.RING_OF_WEALTH_5, ItemID.RING_OF_WEALTH_4, ItemID.RING_OF_WEALTH_3,
			ItemID.RING_OF_WEALTH_2, ItemID.RING_OF_WEALTH_1);
		// Ring of wealth (i).
		addStages(items, ItemID.RING_OF_WEALTH_I5, ItemID.RING_OF_WEALTH_I4, ItemID.RING_OF_WEALTH_I3,
			ItemID.RING_OF_WEALTH_I2, ItemID.RING_OF_WEALTH_I1);
		addStages(items, ItemID.NECKLACE_OF_PASSAGE_5, ItemID.NECKLACE_OF_PASSAGE_4, ItemID.NECKLACE_OF_PASSAGE_3,
			ItemID.NECKLACE_OF_PASSAGE_2, ItemID.NECKLACE_OF_PASSAGE_1);
		// Digsite pendant.
		addStages(items, ItemID.NECKLACE_OF_DIGSITE_5, ItemID.NECKLACE_OF_DIGSITE_4, ItemID.NECKLACE_OF_DIGSITE_3,
			ItemID.NECKLACE_OF_DIGSITE_2, ItemID.NECKLACE_OF_DIGSITE_1);
		addStages(items, ItemID.BURNING_AMULET_5, ItemID.BURNING_AMULET_4, ItemID.BURNING_AMULET_3,
			ItemID.BURNING_AMULET_2, ItemID.BURNING_AMULET_1);
		addStages(items, ItemID.SLAYER_RING_8, ItemID.SLAYER_RING_7, ItemID.SLAYER_RING_6, ItemID.SLAYER_RING_5,
			ItemID.SLAYER_RING_4, ItemID.SLAYER_RING_3, ItemID.SLAYER_RING_2, ItemID.SLAYER_RING_1);
		addStages(items, ItemID.RING_OF_RETURNING_5, ItemID.RING_OF_RETURNING_4, ItemID.RING_OF_RETURNING_3,
			ItemID.RING_OF_RETURNING_2, ItemID.RING_OF_RETURNING_1);
		// Fungicide spray 10 down to 1 ("Fungicide spray 0" is empty).
		addStages(items, ItemID.SLAYER_SPRAY_PUMP_10, ItemID.SLAYER_SPRAY_PUMP_9, ItemID.SLAYER_SPRAY_PUMP_8,
			ItemID.SLAYER_SPRAY_PUMP_7, ItemID.SLAYER_SPRAY_PUMP_6, ItemID.SLAYER_SPRAY_PUMP_5,
			ItemID.SLAYER_SPRAY_PUMP_4, ItemID.SLAYER_SPRAY_PUMP_3, ItemID.SLAYER_SPRAY_PUMP_2,
			ItemID.SLAYER_SPRAY_PUMP_1);
		assertSameStages(items, SupplyTracker.CHARGED_ITEMS);

		assertSameSet(new HashSet<>(Arrays.asList(
			ItemID.TELEPORTSCROLL_NARDAH, ItemID.TELEPORTSCROLL_DIGSITE, ItemID.TELEPORTSCROLL_FELDIP,
			ItemID.TELEPORTSCROLL_LUNARISLE, ItemID.TELEPORTSCROLL_MORTTON, ItemID.TELEPORTSCROLL_PESTCONTROL,
			ItemID.TELEPORTSCROLL_PISCATORIS, ItemID.TELEPORTSCROLL_TAIBWO, ItemID.TELEPORTSCROLL_ELF,
			ItemID.TELEPORTSCROLL_MOSLES, ItemID.TELEPORTSCROLL_LUMBERYARD, ItemID.TELEPORTSCROLL_ZULANDRA,
			ItemID.TELEPORTSCROLL_CERBERUS, ItemID.TELEPORTSCROLL_REVENANTS, ItemID.TELEPORTSCROLL_WATSON,
			ItemID.TELEPORTSCROLL_ARDEAGLAIS, ItemID.TELEPORTSCROLL_CHASMOFFIRE, ItemID.TELEPORTSCROLL_COLOSSAL_WYRM,
			ItemID.TELEPORTSCROLL_GUTHIXIAN_TEMPLE, ItemID.TELEPORTSCROLL_SPIDERCAVE)), SupplyTracker.TELEPORT_SCROLLS);
	}

	@Test
	public void crushedLootSetsAreTheOldSets()
	{
		assertSameSet(new HashSet<>(Arrays.asList(
			ItemID.BONES, ItemID.BAT_BONES, ItemID.BIG_BONES, ItemID.BABYDRAGON_BONES, ItemID.DRAGON_BONES,
			ItemID.WOLF_BONES, ItemID.TBWT_BEAST_BONES, ItemID.TBWT_JOGRE_BONES,
			ItemID.MM_SMALL_NINJA_MONKEY_BONES, ItemID.MM_MEDIUM_NINJA_MONKEY_BONES, ItemID.MM_NORMAL_GORILLA_MONKEY_BONES,
			ItemID.MM_BEARDED_GORILLA_MONKEY_BONES, ItemID.MM_NORMAL_MONKEY_BONES, ItemID.MM_SMALL_ZOMBIE_MONKEY_BONES,
			ItemID.MM_LARGE_ZOMBIE_MONKEY_BONES,
			ItemID.ZOGRE_BONES, ItemID.ZOGRE_ANCESTRAL_BONES_FAYG, ItemID.ZOGRE_ANCESTRAL_BONES_RAURG,
			ItemID.ZOGRE_ANCESTRAL_BONES_OURG, ItemID.DAGANNOTH_KING_BONES, ItemID.WYVERN_BONES, ItemID.LAVA_DRAGON_BONES,
			ItemID.DRAGON_BONES_SUPERIOR, ItemID.WYRM_BONES, ItemID.DRAKE_BONES, ItemID.HYDRA_BONES,
			ItemID.BABYWYRM_BONES, ItemID.STRYKEWYRM_BONES, ItemID.FROST_DRAGON_BONES)), CrushedLootFilter.BONES);
		assertSameSet(new HashSet<>(Arrays.asList(
			ItemID.FIENDISH_ASHES, ItemID.VILE_ASHES, ItemID.MALICIOUS_ASHES, ItemID.ABYSSAL_ASHES, ItemID.INFERNAL_ASHES)), CrushedLootFilter.DEMONIC_ASHES);
	}

	@Test
	public void aMissingOrMalformedFileGivesTheFallback()
	{
		final Map<String, Integer> fallback = Collections.emptyMap();
		final Function<JsonObject, Map<String, Integer>> reader = root -> Collections.singletonMap("read", 1);
		assertSame(fallback, BundledData.load("no-such-table.json", reader, fallback));
		assertSame(fallback, BundledData.load("slayer-spots-malformed.json", reader, fallback));
		// Valid JSON without the list a table reads.
		assertSame(fallback, BundledData.load("slayer-spots-invalid.json",
			root -> Collections.singletonMap("rows", root.getAsJsonArray("tasks").size()), fallback));
		assertEquals(Collections.singletonMap("read", 1), BundledData.load("task-icons.json", reader, fallback));

		final Set<Integer> none = BundledData.idSet("no-such-table.json", "ids");
		assertTrue(none.isEmpty());
		assertUnmodifiable(none);
		assertTrue(BundledData.idSet("task-icons.json", "no-such-list").isEmpty());
		final JsonArray empty = new JsonArray();
		assertEquals(0, BundledData.ids(empty).length);
		assertTrue(BundledData.rows(empty, row -> row).isEmpty());
	}

	/** The old tables' sets: a new {@link HashSet}, added to in order. */
	private static Set<Integer> addedInOrder(int... ids)
	{
		final Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return set;
	}

	/** The same ids, iterated in the same order, and unmodifiable. */
	private static void assertSameSet(Set<Integer> expected, Set<Integer> actual)
	{
		assertEquals(expected, actual);
		assertEquals(new ArrayList<>(expected), new ArrayList<>(actual));
		assertUnmodifiable(actual);
	}

	/** The same stages, iterated in the same order, and unmodifiable. */
	private static void assertSameStages(Map<Integer, int[]> expected, Map<Integer, int[]> actual)
	{
		assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
		for (Map.Entry<Integer, int[]> stage : expected.entrySet())
		{
			assertArrayEquals(String.valueOf(stage.getKey()), stage.getValue(), actual.get(stage.getKey()));
		}
		try
		{
			actual.put(1, new int[0]);
			fail("modifiable");
		}
		catch (UnsupportedOperationException expectedException)
		{
			// Unmodifiable, as before.
		}
	}

	@SuppressWarnings("unchecked")
	private static void assertUnmodifiable(java.util.Collection<?> collection)
	{
		try
		{
			((java.util.Collection<Object>) collection).add(null);
			fail("modifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// Unmodifiable, as before.
		}
	}

	/** The old tables' addStages: an item's stages, from the full item down to the last bite (or charge). */
	private static void addStages(Map<Integer, int[]> map, int... stages)
	{
		for (int i = 0; i < stages.length; i++)
		{
			map.put(stages[i], new int[]{stages[0], stages.length - i, stages.length});
		}
	}
}
