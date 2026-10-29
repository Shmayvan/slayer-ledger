/*
 * The task table below is adapted from RuneLite's core Slayer plugin
 * (net.runelite.client.plugins.slayer.Task, RuneLite client 1.13.4), which carries this notice:
 *
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
 * Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.shmayvan.slayerledger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/**
 * The icon of each Slayer task: the item whose sprite RuneLite's core Slayer plugin shows for the task in its
 * infobox (mostly the Slayer-guide pictures, {@code ItemID.SLAYERGUIDE_*}; boss tasks show their pet). Overall totals
 * draws it before a monster's name ({@link OverallTotalsSection}).
 *
 * <p>The table is a copy of the core plugin's {@code net.runelite.client.plugins.slayer.Task} enum (task name and
 * {@code itemSpriteId}) in RuneLite 1.13.4, BSD 2-Clause (notice above and in {@code NOTICE}). That enum and its
 * {@code getTask} are package-private, so a Plugin Hub plugin can't call them without reflection, which the hub
 * forbids; {@code TaskIconsTest} checks the copy against the enum (by reflection, test code only), so a RuneLite
 * update that changes it fails the build here.</p>
 *
 * <p>Names are matched by {@link TaskStatistics#normalize}. Pass the task's assigned name
 * ({@link LedgerRecord#getMonster}): a Catacombs variant or cannon group's name is no task name and has no icon here.
 * Pure (the ids are the RuneLite 1.13.4 gameval {@code ItemID} constants, plain ints).</p>
 */
final class TaskIcons
{
	/** Item id of each task's icon, by normalized task name. */
	private static final Map<String, Integer> ICONS;

	static
	{
		Map<String, Integer> map = new HashMap<>();
		put(map, "Aberrant spectres", ItemID.SLAYERGUIDE_ABERRANTSPECTER);
		put(map, "Abyssal demons", ItemID.SLAYERGUIDE_ABYSSALDEMON);
		put(map, "The Abyssal Sire", ItemID.ABYSSALSIRE_PET);
		put(map, "The Alchemical Hydra", ItemID.HYDRAPET);
		put(map, "Ankou", ItemID.ANKOU_HEAD);
		put(map, "Aquanites", ItemID.SLAYERGUIDE_AQUANITE);
		put(map, "Araxxor", ItemID.ARAXXORPET);
		put(map, "Araxytes", ItemID.POH_ARAXYTE_HEAD);
		put(map, "Aviansies", ItemID.ARCEUUS_CORPSE_AVIANSIE_INITIAL);
		put(map, "Bandits", ItemID.PICKPOCKET_GUIDE_DESERT_BANDIT);
		put(map, "Banshees", ItemID.SLAYERGUIDE_BANSHEE);
		put(map, "Barrows Brothers", ItemID.BARROWS_KARIL_HEAD);
		put(map, "Basilisks", ItemID.SLAYERGUIDE_BASILISK);
		put(map, "Bats", ItemID.RAIDS_BAT2_COOKED);
		put(map, "Bears", ItemID.ARCEUUS_CORPSE_BEAR_INITIAL);
		put(map, "Birds", ItemID.FEATHER);
		put(map, "Black demons", ItemID.BLACK_DEMON_MASK);
		put(map, "Black dragons", ItemID.DRAGONMASK_BLACK);
		put(map, "Black Knights", ItemID.BLACK_FULL_HELM);
		put(map, "Bloodveld", ItemID.SLAYERGUIDE_BLOODVELD);
		put(map, "Blue dragons", ItemID.DRAGONMASK_BLUE);
		put(map, "Brine rats", ItemID.OLAF2_BRINE_RAT_INV);
		put(map, "Callisto", ItemID.CALLISTO_PET);
		put(map, "Catablepon", ItemID.SOS_HALF_SKULL2);
		put(map, "Cave bugs", ItemID.SWAMP_CAVE_BUG);
		put(map, "Cave crawlers", ItemID.SLAYERGUIDE_CAVECRAWLER);
		put(map, "Cave horrors", ItemID.SLAYERGUIDE_HARMLESS_CAVE_HORROR);
		put(map, "Cave kraken", ItemID.CERT_EADGAR_FADE_TO_BLACK_INV);
		put(map, "Cave slimes", ItemID.SWAMP_CAVE_SLIME);
		put(map, "Cerberus", ItemID.HELL_PET);
		put(map, "Chaos druids", ItemID.ELDERCHAOS_HOOD);
		put(map, "The Chaos Elemental", ItemID.CHAOSELEPET);
		put(map, "The Chaos Fanatic", ItemID.STAFF_OF_ZAROS);
		put(map, "Cockatrice", ItemID.SLAYERGUIDE_COCKATRICE);
		put(map, "Cows", ItemID.COW_MASK);
		put(map, "Crabs", ItemID.HUNDRED_PIRATE_CRAB_SHELL_GAUNTLET);
		put(map, "Crawling hands", ItemID.SLAYERGUIDE_CRAWLINGHAND);
		put(map, "Crazy Archaeologists", ItemID.FEDORA);
		put(map, "Crocodiles", ItemID.GREEN_SALAMANDER);
		put(map, "Custodian Stalkers", ItemID.SLAYERGUIDE_CUSTODIAN_STALKER_MATURE);
		put(map, "Dagannoth", ItemID.POH_DAGGANOTH);
		put(map, "Dagannoth Kings", ItemID.PRIMEPET);
		put(map, "Dark beasts", ItemID.SLAYERGUIDE_DARK_BEAST);
		put(map, "Dark warriors", ItemID.BLACK_MED_HELM);
		put(map, "Deranged Archaeologist", ItemID.FOSSIL_SWAMP_DIARY);
		put(map, "Dogs", ItemID.POH_GUARD_DOG);
		put(map, "Drakes", ItemID.SLAYERGUIDE_DRAKE);
		put(map, "Duke Sucellus", ItemID.DUKESUCELLUSPET);
		put(map, "Dust devils", ItemID.SLAYERGUIDE_DUSTDEVIL);
		put(map, "Dwarves", ItemID.GRIM_WEAR_HELMET);
		put(map, "Earth warriors", ItemID.BRONZE_FULL_HELM_TRIM);
		put(map, "Elves", ItemID.PICKPOCKET_GUIDE_WOODELF);
		put(map, "Ents", ItemID.POH_TREE_2);
		put(map, "Fever spiders", ItemID.SLAYERGUIDE_FEVER_SPIDER);
		put(map, "Fire giants", ItemID.RTBRANDAPET);
		put(map, "Fleshcrawlers", ItemID.ARCEUUS_CORPSE_SCORPION_INITIAL);
		put(map, "Fossil island wyverns", ItemID.SLAYERGUIDE_FOSSILWYVERN);
		put(map, "Frost dragons", ItemID.FROST_DRAGON_BONES);
		put(map, "Gargoyles", ItemID.SLAYERGUIDE_GARGOYLE);
		put(map, "General Graardor", ItemID.BANDOSPET);
		put(map, "Ghosts", ItemID.AMULET_OF_GHOSTSPEAK);
		put(map, "Ghouls", ItemID.TRICK_OR_TREAT_HEAD);
		put(map, "The Giant Mole", ItemID.MOLEPET);
		put(map, "Goblins", ItemID.ARCEUUS_CORPSE_GOBLIN_INITIAL);
		put(map, "Greater demons", ItemID.GREATER_DEMON_MASK);
		put(map, "Green dragons", ItemID.DRAGONMASK_GREEN);
		put(map, "The Grotesque Guardians", ItemID.DUSKPET);
		put(map, "Gryphons", ItemID.SLAYERGUIDE_GRYPHON);
		put(map, "Harpie bug swarms", ItemID.SLAYERGUIDE_SWARM);
		put(map, "Hellhounds", ItemID.POH_HELLHOUND);
		put(map, "Hill giants", ItemID.ARCEUUS_CORPSE_GIANT_INITIAL);
		put(map, "Hobgoblins", ItemID.POH_HOBGOBLIN);
		put(map, "Hydras", ItemID.SLAYERGUIDE_HYDRA);
		put(map, "Icefiends", ItemID.FD_ICEDIAMOND);
		put(map, "Ice giants", ItemID.RTELDRICPET);
		put(map, "Ice warriors", ItemID.MITHRIL_FULL_HELM_TRIM);
		put(map, "Infernal mages", ItemID.SLAYERGUIDE_INFERNALMAGE);
		put(map, "TzTok-Jad", ItemID.JAD_PET);
		put(map, "Jellies", ItemID.SLAYERGUIDE_JELLY);
		put(map, "Jungle horrors", ItemID.ARCEUUS_CORPSE_HORROR_INITIAL);
		put(map, "Kalphites", ItemID.POH_KALPHITE_SOLDIER);
		put(map, "The Kalphite Queen", ItemID.KQPET_WALKING);
		put(map, "Killerwatts", ItemID.SLAYERGUIDE_KILLERWATT);
		put(map, "The King Black Dragon", ItemID.KBDPET);
		put(map, "The Cave Kraken Boss", ItemID.KRAKENPET);
		put(map, "Kree'arra", ItemID.ARMADYLPET);
		put(map, "K'ril Tsutsaroth", ItemID.ZAMORAKPET);
		put(map, "Kurask", ItemID.SLAYERGUIDE_KURASK);
		put(map, "Lava Dragons", ItemID.LAVA_SCALE);
		put(map, "Lesser demons", ItemID.LESSER_DEMON_MASK);
		put(map, "Lesser Nagua", ItemID.SLAYERGUIDE_LESSER_NAGUA);
		put(map, "Lizardmen", ItemID.LIZARDMAN_FANG);
		put(map, "Lizards", ItemID.SLAYERGUIDE_LIZARD);
		put(map, "The Maggot King", ItemID.MAGGOTKINGPET);
		put(map, "Magic axes", ItemID.IRON_BATTLEAXE);
		put(map, "Mammoths", ItemID.BARBASSAULT_ATT_HORN_01);
		put(map, "Metal dragons", ItemID.POH_STEEL_DRAGON);
		put(map, "Minotaurs", ItemID.ARCEUUS_CORPSE_MINOTAUR_INITIAL);
		put(map, "Mogres", ItemID.SLAYERGUIDE_MOGRE);
		put(map, "Molanisks", ItemID.SLAYERGUIDE_MOLANISK);
		put(map, "Monkeys", ItemID.ARCEUUS_CORPSE_MONKEY_INITIAL);
		put(map, "Moss giants", ItemID.MOSSY_KEY);
		put(map, "Mutated zygomites", ItemID.SLAYER_ZYGOMITE_OBJECT);
		put(map, "Nechryael", ItemID.SLAYERGUIDE_NECHRYAEL);
		put(map, "Ogres", ItemID.ARCEUUS_CORPSE_OGRE_INITIAL);
		put(map, "Otherworldly beings", ItemID.SECRET_GHOST_HAT);
		put(map, "The Phantom Muspah", ItemID.MUSPAHPET);
		put(map, "Pirates", ItemID.BREW_RED_PIRATE_HAT);
		put(map, "Pyrefiends", ItemID.SLAYERGUIDE_PYRFIEND);
		put(map, "Rats", ItemID.RATS_TAIL);
		put(map, "Red dragons", ItemID.POH_DRAGON);
		put(map, "Revenants", ItemID.WILD_CAVE_BRACELET_CHARGED);
		put(map, "Rockslugs", ItemID.SLAYERGUIDE_ROCKSLUG);
		put(map, "Rogues", ItemID.ROGUESDEN_HELM);
		put(map, "Sarachnis", ItemID.SARACHNISPET);
		put(map, "Scabarites", ItemID.NTK_SCARAB_GOLD);
		put(map, "Scorpia", ItemID.SCORPIA_PET);
		put(map, "Scorpions", ItemID.ARCEUUS_CORPSE_SCORPION_INITIAL);
		put(map, "Sea snakes", ItemID.HUNDRED_ILM_SNAKE_CORPSE);
		put(map, "Shades", ItemID.BLACKROBETOP);
		put(map, "Shadow warriors", ItemID.BLACK_FULL_HELM);
		put(map, "The Shellbane Gryphon", ItemID.GRYPHONBOSSPET_ADULT);
		put(map, "Skeletal wyverns", ItemID.SLAYERGUIDE_SKELETALWYVERN);
		put(map, "Skeletons", ItemID.POH_SKELETON_GUARD);
		put(map, "Smoke devils", ItemID.CERT_GUIDE_ICON_DUMMY);
		put(map, "Sourhogs", ItemID.PORCINE_SOURHOG_TROPHY);
		put(map, "Spiders", ItemID.POH_SPIDER);
		put(map, "Spiritual creatures", ItemID.DRAGON_BOOTS);
		put(map, "Suqahs", ItemID.SUQKA_TOOTH);
		put(map, "Terror dogs", ItemID.SLAYERGUIDE_TERRORDOG);
		put(map, "The Leviathan", ItemID.LEVIATHANPET);
		put(map, "The Whisperer", ItemID.WHISPERERPET);
		put(map, "The Thermonuclear Smoke Devil", ItemID.SMOKEPET);
		put(map, "Trolls", ItemID.POH_TROLL);
		put(map, "Turoth", ItemID.SLAYERGUIDE_TUROTH);
		put(map, "Tzhaar", ItemID.ARCEUUS_CORPSE_TZHAAR_INITIAL);
		put(map, "Vampyres", ItemID.STAKE);
		put(map, "Vardorvis", ItemID.VARDORVISPET);
		put(map, "Venators", ItemID.SLAYERGUIDE_VENATOR);
		put(map, "Venenatis", ItemID.VENENATIS_PET);
		put(map, "Vet'ion", ItemID.VETION_PET);
		put(map, "Vorkath", ItemID.VORKATHPET);
		put(map, "Wall beasts", ItemID.SWAMP_WALLBEAST);
		put(map, "Warped Creatures", ItemID.POG_SLAYER_DUMMY_WARPED_TERRORBIRD);
		put(map, "Waterfiends", ItemID.WATER_ORB);
		put(map, "Werewolves", ItemID.DAGGER_WOLFBANE);
		put(map, "Wolves", ItemID.GREY_WOLF_FUR);
		put(map, "Wyrms", ItemID.SLAYERGUIDE_WYRM);
		put(map, "Commander Zilyana", ItemID.SARADOMINPET);
		put(map, "Zombies", ItemID.TRICK_OR_TREAT_HEAD);
		put(map, "TzKal-Zuk", ItemID.INFERNOPET_ZUK);
		put(map, "Zulrah", ItemID.SNAKEPET);
		ICONS = Collections.unmodifiableMap(map);
	}

	private TaskIcons()
	{
	}

	/**
	 * The item id whose sprite is {@code taskName}'s icon, or {@code null} for a name that is no known task (blank,
	 * {@code null}, a Catacombs variant or cannon group name, or a task newer than the table).
	 */
	static Integer itemId(String taskName)
	{
		String key = TaskStatistics.normalize(taskName);
		return key == null ? null : ICONS.get(key);
	}

	/** How many tasks have an icon. Test seam: {@code TaskIconsTest} compares it with RuneLite's enum. */
	static int size()
	{
		return ICONS.size();
	}

	private static void put(Map<String, Integer> map, String task, int itemId)
	{
		map.put(TaskStatistics.normalize(task), itemId);
	}
}
