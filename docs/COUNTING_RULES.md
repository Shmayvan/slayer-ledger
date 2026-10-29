# Slayer Ledger: counting rules and details

This page explains exactly what Slayer Ledger counts, how it values it, and the known limits of each feature. The [README](../README.md) gives the overview; read this when a number in the panel surprises you.

## Contents

- [Sessions and saving](#sessions-and-saving)
- [Loot tracking](#loot-tracking), [Bonecrusher and ash sanctifier](#bonecrusher-and-ash-sanctifier), [Ignored loot](#ignored-loot)
- [Supplies tracking](#supplies-tracking)
- [XP gained](#xp-gained)
- [Superiors, bank trips and deaths](#superiors-bank-trips-and-deaths)
- [Master, task type, Slayer points and streak](#master-task-type-slayer-points-and-streak)
- [Boss kill count](#boss-kill-count)
- [Keys, clue scrolls and notable drops](#keys-clue-scrolls-and-notable-drops)
- [Task notes](#task-notes), [Task locations](#task-locations), [Catacombs variants](#catacombs-variants) and [Cannon tasks](#cannon-tasks)
- [Loot and supply values](#loot-and-supply-values), [Loot price](#loot-price), [Ground Items' hidden items](#ground-items-hidden-items), [Item lists](#item-lists)
- [Time left](#time-left), [Historical task estimates](#historical-task-estimates), [What to bring](#what-to-bring)
- [Task history](#task-history), including the chat lines
- [Trends](#trends) and [Task advisor](#task-advisor)
- [Task overlay](#task-overlay), including the Not tracking reminder
- [Panel sections](#panel-sections), [Overall totals](#overall-totals), [Luck meter](#luck-meter)
- [Skipped and blocked tasks](#skipped-and-blocked-tasks)

## Sessions and saving

Start, pause and stop a session from the sidebar panel to record kills and active time for your current Slayer task. Saved tasks are stored locally per account under `~/.runelite/plugin-data/slayer-ledger/<account-hash>.json`. Data from earlier versions in `~/.runelite/slayer-ledger` is moved there automatically the first time the new version runs.

The task box at the top of the sidebar takes at most three lines: the task and its kills left, for example `Abyssal demons · 172 left` (or its Catacombs variant once your kills show it, for example `Deviant spectres · 120 left`; see [Catacombs variants](#catacombs-variants); with `(cannon)` once you fire a cannonball, for example `Dust devils (cannon) · 80 left`; see [Cannon tasks](#cannon-tasks)); then the master, the task type (only when it isn't a regular task), the location and the next bonus task, for example `Konar · Kraken Cove · bonus in 3`, each left out while unknown; then the task advice, only when it isn't **Do**, or which version of a task with a Catacombs variant is better, or whether it has been better with a cannon or without (see [Task advisor](#task-advisor)). Hover over the first two lines to see each detail in full.

Logging out or hopping worlds **pauses** a running session, and it stays paused until you press **Resume** in the sidebar: kills, loot, supplies and XP after the hop or login are not counted until then. The overlay shows an orange **Paused** row while the session is paused, and when you are back in game one chat line says why, for example `Slayer Ledger paused (world hop). Press Resume to keep tracking.` A session you paused yourself shows the same row but gets no chat line.

An in-progress session is not lost when you log out, close the client, turn the plugin off, or switch accounts. It is saved as that account's *active session* (`~/.runelite/plugin-data/slayer-ledger/<account-hash>.active.json`) instead of becoming a ledger record. On the next login to the same account, if your task is still the same (same monster, and the remaining count has not gone up), the session comes back **Paused** with its kills and time, and the panel shows "Restored previous session". Press Resume to continue. Kills made while the plugin was not running are not counted. If the task changed, was completed, or there is no task, the old session is saved as a partial record and the panel offers a Discard button.

If a task can't be saved (for example another program holds the ledger file open, or the disk is full), the panel says "Couldn't save the last task. See the client log." The task is kept beside the ledger (`<account-hash>.unsaved-<id>.json`) and added to it once, by the next save or login that can write the ledger. This also covers a ledger written by a newer version of the plugin: the task is kept until this version can write the ledger, or a newer one with the same format does.

## Loot tracking

While a session is **running**, Slayer Ledger tallies the loot you receive from NPC kills, using the same in-game loot data as RuneLite's Loot Tracker (pickpocket loot is ignored). Loot received while the session is paused, or when no session is running, is not counted. When a running task completes, the save waits 2 game ticks so loot from the final kill that arrives just after the task count updates is still included; the task's kills and time are final at the moment it completed, and logging out, hopping, stopping or closing the client during those ticks saves it right away. Loot from any NPC you kill while running counts, not only your task monster, so pause the session if you fight something unrelated. The tally is kept with the active session, so it survives pausing, logging out and restarting the client.

When the task is saved, the loot is valued at that moment: the Grand Exchange price from RuneLite's price data and the high-alchemy value. Coins count at face value, noted items are valued like the unnoted item, and untradeable items have no GE value. Older saved tasks, from before loot tracking, simply have no loot.

If an item's GE price is unavailable when it is valued (RuneLite has not loaded its price data yet, or a tradeable item is missing from it), the price is recorded as unknown rather than as 0. RuneLite reports both cases as a price of 0 and has no "prices loaded" signal, so any tradeable item priced at 0 is treated as unknown. Totals add up the prices that are known, and the panel marks a total that leaves something out with `~`; its tooltip says how many items had no price. Untradeable items are a known 0, and coins and platinum tokens are always known. Known limitations: a tradeable item that genuinely has no GE price is always shown as unknown (`~`), and an untradeable item that RuneLite values through its tradeable parts (for example some degraded items) reads as a known 0 if it is valued before prices have loaded.

Saving loot raised the ledger file version, so an older version of the plugin can still read your ledger but refuses to change it, rather than silently dropping the loot. While you use an older version, tasks you finish are not saved.

### Bonecrusher and ash sanctifier

The game's loot data still lists bones that a bonecrusher crushed (and demonic ashes an ash sanctifier scattered), so Slayer Ledger leaves them out itself. Each time loot arrives it checks what you carry at that moment, so taking the crusher off or banking it mid-task works:

- a **bonecrusher** in your inventory, or a **bonecrusher necklace** in your inventory or worn: bones are left out of that kill's loot;
- an **ash sanctifier** in your inventory: demonic ashes (fiendish, vile, malicious, abyssal and infernal) are left out.

Other loot is unaffected, and so are plain ashes and noted bones. The **Bonecrusher handling** setting changes this:

- **Auto** (default): as above;
- **Always exclude**: bones and demonic ashes are always left out, whatever you carry;
- **Never exclude**: bones and demonic ashes always count as loot.

Auto does not check the crusher's charges; it assumes a carried crusher is charged. If yours runs out of charges, use **Never exclude** until it is recharged. Crushed bones are not recorded anywhere. Tasks saved before this change keep their bones.

### Ignored loot

The **Ignored loot** setting lists items you never want counted as loot, separated by commas, for example `Bones, Coins, *seed, 526`:

- an item **name** matches the whole name and ignores capitals, so `bones` leaves out Bones but not Big bones;
- `*` matches anything, so `*seed` leaves out every seed and `*bones*` every kind of bones;
- a **number** is an item id;
- a noted drop matches the unnoted item, so `Dragon bones` (or its unnoted id) also leaves out noted dragon bones.

Ignored items are left out when the loot arrives, so they are never counted or valued. Changing the setting only affects loot received from then on: loot already counted in the current session, and saved tasks, are not changed. To leave items out of the value without dropping them from the loot, use [Ground Items' hidden items](#ground-items-hidden-items) instead.

## Supplies tracking

While a session is **running**, Slayer Ledger also counts the supplies you use. Once per game tick it compares your inventory, your worn ammo, weapon, amulet, ring and gloves slots, Dizana's quiver, your rune pouch (while it is in your inventory; the divine rune pouch uses the same slots) and your cannon's loaded cannonballs with the previous tick. These are added together, so moving items between them is never counted as use: filling or emptying the rune pouch, equipping ammo or jewellery, loading the quiver, and loading or picking up the cannon all net to zero. Only these kinds of item are counted:

- **Food**: any item with an *Eat* or *Drink* option that is not a potion (for example sharks, karambwans or jugs of wine). Other items that leave your inventory, such as bones you bury, items you alch or equipment you put on, are not counted.
- **Food eaten in parts, in bites**: pizzas (2 bites: the pizza, then the 1/2 pizza), pies (2 bites: the pie, then the half pie; the pie dish left over is not counted) and cakes and chocolate cakes (3 bites: the cake, the 2/3 cake, then the slice). Like potion doses, bites are counted per food and saved under the whole item, each valued at the whole item's GE price divided by its bites, so eating a whole pizza costs exactly one pizza and finishing a half pizza you already had costs half of one.
- **Potions, in doses**: an item named like `Prayer potion(4)` with a *Drink* option. Doses are counted per potion, so drinking a (4) into a (3) is one dose, and decanting or combining potions uses nothing. Doses are saved under the highest-dose version of that potion seen since the plugin started and valued per dose (the 4-dose price divided by 4, rounded to the nearest coin for the total).
- **Runes**: runes cast from your inventory or rune pouch. Elemental staves and tomes simply use no runes.
- **Ammo**: arrows, bolts, darts, knives, chinchompas and other ammo or thrown weapons that leave your ammo slot, weapon slot, quiver or inventory. Ammo you pick up again is taken off what you used for that item (never below zero). This includes blowpipe darts: picking up dropped darts of the type loaded in your blowpipe reduces its counted darts.
- **Cannonballs fired**: cannonballs that leave your cannon without coming back to your inventory. Granite cannonballs are told apart from regular ones by what you load. If tracking starts with your cannon already loaded (for example after restarting the plugin), regular cannonballs are assumed until a load or the pickup shows the type; picking up a granite-loaded cannon then counts only the granite cannonballs it fired, and one that never fired counts none.
- **Slayer consumables**: bags of salt and ice coolers, one per item used. Tools such as the rock hammer are not used up, so they are not counted.
- **Teleport tablets**: one per tablet broken. A tablet is an item with a *Break* option and "teleport" in its name, for example Varrock teleport, Teleport to house or Salve graveyard teleport. Other tablets (enchanting, Bones to bananas) are not counted.
- **Teleport jewellery and the fungicide spray, in charges**: rings of dueling, games necklaces, amulets of glory and glory (t), skills necklaces, combat bracelets, rings of wealth and wealth (i), necklaces of passage, digsite pendants, burning amulets, slayer rings, rings of returning, and the fungicide spray (10 charges). Like potion doses, charges are counted per item type, whether the item is in your inventory or worn, and saved under its highest-charge version (for example the amulet of glory(6)), each valued at that version's GE price divided by its charges. The last charge counts too, whether the item then crumbles (a ring of dueling or games necklace) or becomes an uncharged item (an amulet of glory, combat bracelet, skills necklace or ring of wealth, or Fungicide spray 0). Recharging, for example at the Fountain of Heroes, is not a use, and later charges count as normal. Refilling the spray with Fungicide is not a use either, and the Fungicide refill itself is not counted, so the spray's cost is counted once, per charge you spray. The amulet of eternal glory and the slayer ring (eternal) never run out, so they are not counted.
- **Toxic blowpipe and tridents, as estimates**: the game doesn't tell plugins how many darts, scales or charges are left, so each attack is counted from your character's attack animation while the weapon is wielded, at most one per game tick, and multiplied by its average cost. A blowpipe shot uses 2/3 of a Zulrah's scale and part of a dart, depending on what you wear in your cape slot: a whole dart with nothing, 0.4 with Ava's attractor, 0.28 with Ava's accumulator, 0.2 with Ava's assembler (including the Masori and max cape versions) or Dizana's quiver, and 0.28 with the ranging cape or max cape (0.2 with the **Ranging/max cape has Vorkath's head upgrade** setting on). Parts are carried over, so three shots use two scales, and are saved as whole items. A cast of a charged trident of the seas uses 1 death rune, 1 chaos rune, 5 fire runes and 10 coins; a trident of the swamp uses 1 death rune, 1 chaos rune, 5 fire runes and 1 Zulrah's scale. These are counted as the runes, coins and scales themselves, so they are valued and saved like other supplies. Loading or unloading the blowpipe, or charging or uncharging a trident, is not counted: those items are skipped for a short while after you *Use* them on the weapon or choose *Unload* or *Uncharge*, until the game confirms it (or after 30 seconds). Unloading the blowpipe prints no confirmation, so after an *Unload* (or a *Use* the game rejects, or a cancelled charge) those items are skipped for the full 30 seconds: darts and death, chaos and fire runes used in that time, including for other spells, are not counted.

Supplies are never counted while the session is paused, stopped or not started; the count starts afresh when you press Play or Resume. They are also not counted:

- while the bank, a deposit box, group storage, the seed vault, the Grand Exchange, a shop, a trade, or Death's office or coffer is open, and for 2 game ticks after it was last open (so a deposit made just as you close the bank is not counted);
- for an item you chose *Drop* on in the last 2 game ticks (dropping a rune pouch skips those ticks entirely);
- for 10 game ticks after you die, while your items move to your gravestone;
- for items you gain, such as loot you pick up (apart from the ammo pickup above).

The blowpipe's dart type is learned from the message you get when you *Check* it (or add darts), and remembered for your account. Until it is known, for example the first time you use the plugin or after you unload the darts or run out, shots count their scales only and the panel shows **Check your blowpipe to count darts** once a shot has been counted without a dart type, while the session is running and the blowpipe is wielded; *Check* the blowpipe once and darts are counted from then on.

Supplies used after a task completes are not added to that task, even during the short wait for its final loot. Like loot, supplies are kept with the active session (so they survive pausing, logging out and restarting the client), saved with the task, and valued when the task is saved. Older saved tasks have no supplies.

Known limitations:

- Only the categories above are counted. Powered staves other than the tridents (for example the sanguinesti staff and Tumeken's shadow), and charged items not listed above (for example the abyssal bracelet or teleport crystal), are not tracked. Teleport scrolls (such as the Zul-andra or Revenant cave teleport) are not tablets, so they are not counted either.
- A recharged item's charges are valued at the GE price like any other charge, even though recharging was free. Untradeable charged items (such as the ring of wealth (i)) have a GE price of 0, so their charges are counted but add nothing to the supplies value. Imbuing a ring of wealth while a task is running counts its charges as used, because the imbued ring is a separate item.
- Only the pizzas, pies and cakes listed above are counted in bites. Any other food eaten in parts counts each part as one item when it is eaten, so its value is overstated.
- Blowpipe and trident use is an estimate from the average rates. The Ava's device rates are assumed, not read from the game; Dizana's quiver is always assumed to save 80%, whatever its setting; and max cape variants other than the plain, accumulator, assembler and Dizana's max capes (such as the fire, infernal or imbued god max capes) count as no device. Whether a trident cast that splashes, and the blowpipe's special attack, are counted correctly has not been checked in game yet.
- Pouring a potion out with *Empty*, or using food, ammo or another counted supply on something (other than loading the blowpipe or charging a trident), counts as using it. Moving supplies into a looting bag or another storage item not listed above also counts as using them.
- If the cannon holds one cannonball type and you load another, or the cannon decays while loaded, the cannonballs may be counted under the wrong type or as fired. If tracking started with the cannon already loaded, the cannonballs it fires count as regular ones until you pick it up or load it; a task saved before that, or paused between the firing and the pickup, keeps them as regular cannonballs. They also stay regular when the cannon ran empty before you picked it up or loaded it, when the load was not a top-up of the cannonballs already in it, or when half or fewer of the cannonballs it last held came back on the pickup. Cannonballs of the other type looted as the cannon empties can only be taken for the cannon's type when they are more than half of what it last held.
- An ammo pickup is only taken off ammo of the same item already counted as used; it is not checked against where that ammo came from.

## XP gained

While a session is **running**, Slayer Ledger adds up the XP you gain in every skill, for example Slayer, your combat style, Hitpoints, Magic from barrage and Prayer. While a session is running or paused the panel shows the total below the time left, with XP per hour of active time once the session has a minute of it, for example `XP: 152K (48.2K/h)`. Hover over it for the exact total and every skill. The **XP gained** list below the item lists shows the XP skill by skill, highest first, with the skill's small icon before its name, as RuneLite's XP tracker shows; it starts closed, so click its title to show or hide it. A skill this version of RuneLite doesn't know (for example from a task saved by a newer client) has no icon, and its name stays lined up with the others.

- XP is read once per game tick and compared with the reading before. Only XP gained while the session runs counts: XP gained before **Play**, while paused or after **Stop** is never added later, and counting starts with the first tick after you press **Play** or **Resume**.
- When a running task completes, its XP is final: XP from the final kill (on the tick the task completes) counts, and XP during the short wait for the final loot does not.
- After logging in, a world hop or a lost connection, the first reading is only a starting point, so the stats the game sends when you log in are never counted as XP gained. XP gained in the moments before that first reading is not counted.

The XP is kept with the active session (so it survives pausing, logging out and restarting the client) and saved with the task. A session restored from before XP was tracked counts from the moment it was restored, so that one task's XP per hour comes out low: its XP covers only the time after the restore, but the rate uses the whole session's time.

## Superiors, bank trips and deaths

While a session is **running**, Slayer Ledger also counts:

- **Superiors**: each `A superior foe has appeared...` game message (the one RuneLite's Slayer plugin notifies on). A superior that appears while the session is paused, or after the task completed (during the short wait for its final loot), is not counted.
- **Bank trips**: each time you open the bank, a deposit box or group storage. Opening one again within 30 seconds (50 game ticks) of last having one open counts as the same trip, so going back for a forgotten item, or from a deposit box to the bank, counts once. Banking while the session is paused or stopped is never counted, and a bank opened while paused starts that 30-second window too.
- **Deaths**: each time you die. A death while the session is paused, or after the task completed, is not counted. Only the number of deaths is kept; what a death cost you (Death's fee or items lost) is not tracked.

While a session is running or paused the panel shows `Superiors: N`, `Deaths: N` and `Bank trips: N` on one line below the time left. All three are kept with the active session (so they survive pausing, logging out and restarting the client) and saved with the task. Tasks saved before they were counted show `-`; a session restored from before they were counted counts from the moment it was restored.

## Master, task type, Slayer points and streak

The task box's second line shows who assigned your current task and, unless it is a regular task, what kind of task it is, for example `Krystilia · Wilderness` or `Duradel/Kuradal · Boss`. Konar's line shows her name once, with the location she gave: `Konar · Kraken Cove`. Hover over the line to see `Master: Konar` and `Type: Konar (Kraken Cove)` in full. Each session saves them with the task, and a completed task also saves the Slayer points it gave and your task streak afterwards. Hover over a saved task in the history to see them.

- **Master**: read from the game when the task is first seen. A master the plugin does not know yet shows as `Unknown master (n)`. Some masters share one game value, so they are shown together, for example `Turael/Aya`, `Duradel/Kuradal` and `Nieve/Steve`.
- **Type**: `Boss` for a boss task, whoever gave it; `Wilderness` for Krystilia's tasks; `Konar`, with the location Konar gave, for Konar's tasks; otherwise `Regular`. The type is worked out from the saved master and task, so it is not stored separately.
- **Points earned**: the change in your Slayer points from just before the task completed to just after. Points you spend during the task don't affect it. If your points went down when the task completed, or the plugin had no reading from before the completion (for example straight after logging in), no value is saved rather than a wrong one. A task completed while the session was paused still saves its points and streak, since they belong to the task.
- **Task streak**: the streak the completion raised, as it stood afterwards: your normal streak, the Wilderness streak for Krystilia, or Mortimer's streak.

Partial tasks have a master and type but no points or streak. Tasks saved before this was recorded show none of these lines.

The same line also shows when your next bonus-points task comes up, for example `bonus in 3`, with `Next bonus: task 50 (in 3)` in its tooltip: the next task in a row that gives bonus points, and how many tasks are left until it, counting the current task. Bonus points come after every 10th, 50th, 100th, 250th and 1,000th task in a row ([OSRS Wiki, "Slayer reward point"](https://oldschool.runescape.wiki/w/Slayer_reward_point?oldid=15372070), revision of 13 October 2026), so the next one is always the next multiple of 10. The streak is the one for the current task's master: the Wilderness streak for Krystilia, Mortimer's streak for Mortimer, otherwise your normal streak. Right after a task completes, it shows your new streak (from the master who gave the completed task) until the next task. It is left out while the streak is unknown: when you are not logged in, just after logging in or hopping, when no task has been seen since logging in, and when the master is unknown or not in the plugin's list (the plugin then can't tell which of the three streaks applies). Turael, Aya and Spria give no points, even on a bonus task (same Wiki revision).

Known limitations:

- Whether a task was extended is not recorded: RuneLite has no reliable link from a task to its extend unlock.
- The master names come from the OSRS wiki's list of game values (checked October 2026). RuneLite's own Slayer plugin confirms Krystilia's and Mortimer's values; the rest have not been checked in game yet.
- Whether a task was **extended** (with a "longer task" Slayer reward) is not recorded. The game data RuneLite exposes doesn't link each task to its extend unlock, and having the unlock doesn't prove the task was extended, so the plugin can't tell reliably.
- The wiki's bonus-points rule names normal masters and Krystilia; that Mortimer's streak gives bonus points on the same tasks has not been checked yet.
- A task completed **while paused** is saved straight away, without the 2-tick wait. If the game ever updated your points a tick after the streak, such a task would save 0 points rather than the real value. Whether the two always arrive on the same tick has not been checked in game yet.

## Boss kill count

On a boss task, the panel and the saved task use the boss's name, as RuneLite's own Slayer plugin shows it, for example `Vorkath`. While a session is running or paused, the panel also shows the boss's kill count from the game's kill count message (`Your Vorkath kill count is: 152.`), above the keys and notable drops, for example:

```
Boss KC: 152 (+12)
```

The number is your kill count now; `+12` is the kills counted on this task. When the task gives kill counts under another name, each is listed with its name, for example on a Dagannoth Kings task:

```
Dagannoth Rex KC: 51 (+2)
Dagannoth Prime KC: 20 (+1)
```

Saved tasks show the same lines in their history tooltip, when there is data for them.

- **Which messages count**: on a boss task, every boss's kill count message. On any other task, only a message whose boss name is the task's name, for example a TzTok-Jad or TzKal-Zuk task (these stay type `Regular`). Names are compared ignoring capitals, colours and extra spaces; nothing looser, so a Black demons task never counts a demon boss.
- Only boss kill counts count: `<boss> kill count` and plain `<boss> count` (such as `Barrows chest count`). Raid completions, Wintertodt, Gauntlet, lap counts and other counts the game reports in the same way are ignored.
- Like loot, a message counts only while the session is running, or in the short wait for the final kill's loot after a task completes. A message repeating a count already seen counts once.

Known limitations:

- Kills while the session is paused are not in the `+N`, but the kill count shown includes them once the next kill is counted. Your kill count from before the task is not saved: it can't be told reliably (kills before you pressed Play, or while paused).
- A task lists at most 8 bosses; on a boss task, kills of an unrelated boss while the session runs (for example Tempoross) are listed too.
- The message formats are the ones RuneLite's own Chat Commands plugin reads. The boss names for Jad and Zuk tasks, and the messages themselves, have not been checked in game yet.

## Keys, clue scrolls and notable drops

While a session is running or paused, the panel lists the Slayer keys and clue scrolls you have received during the task and its notable drops, below the loot and supply values, for example:

```
Keys: 2 brimstone
Clues: 1 hard, 2 elite
Notable drops:
  Abyssal whip: 1.5M
  Pet
```

Each line appears only when there is something to show. The three newest notable drops are listed; `+N more` means there are others, and hovering over the box lists them all with their exact values. Saved tasks show the same lines in their history tooltip, when there is data for them.

- **Keys and clue scrolls** are counted from the task's loot: brimstone keys (Konar's tasks), Larran's keys (Krystilia's Wilderness tasks) and clue scrolls by tier (beginner, easy, medium, hard, elite and master). Nothing extra is saved, so they follow the loot rules above (only while the session is running, after the bonecrusher and **Ignored loot** settings), and tasks saved with loot before this version show their keys and clues too.
- **Notable drops**: a loot item stack from one kill worth at least the **Notable drop value** setting (default 100,000 gp) at the **Loot price** setting, for example one Abyssal whip, or 25 rune bars from one kill. Stacks of the same item in one kill count as one stack; an untradeable item counts at its high alch value, as in the loot value. Each drop keeps its item, quantity and value (GE and high alch) from when it dropped, so later price changes don't change it, and the panel shows the value at the current **Loot price** setting. Changing the setting only affects drops from then on.
- **Pets** are always notable: the game's pet message (`You have a funny feeling like you're being followed`, or `You feel something weird sneaking into your backpack`) adds `Pet`, and the message for a pet you already have adds `Pet (already owned)`. The message doesn't say which pet it is.

Like loot, these count only while the session is running, or in the short wait for the final kill's loot after a task completes. They are kept with the active session and saved with the task.

Known limitations:

- Keys and clue scrolls only count when they come from NPC loot. A clue from anything else, for example a clue bottle, nest or geode while skilling, or an impling jar, is not counted, and nor are keys or clues you leave out with **Ignored loot**.
- A task keeps at most 20 notable drops so its saved file stays small. Beyond that, the least valuable item is left out (pets are always kept).
- An item whose price is unavailable when it drops (RuneLite hasn't loaded its prices yet) is never notable.
- Rare drops worth less than the threshold (so-called uniques) are not listed: RuneLite has no reliable list of which drops are uniques, and the plugin doesn't guess one.
- Whether the pet messages appear as expected, and whether loot reports a clue scroll by its own item id, has not been checked in game yet. The pet messages are the ones RuneLite's own Screenshot plugin uses.

## Task notes

While a session is running or paused, the panel shows a **Note** box below the session's progress, for a short note on how you are doing the task, for example `Barrage`, `Prayer gear` or `Cannon`. Type a note and press Enter (or click elsewhere), or open the box's list to pick a recent one: it offers your most recent distinct notes from saved tasks, newest first, followed by `Prayer gear`, `Strength gear`, `Barrage`, `Range pray` and `Cannon`, up to 8 in all, so the same labels are reused. Notes are tidied when saved: extra spaces and line breaks are removed, and they are cut to 60 characters. Notes that differ only in capitals count once in the list, which uses the newest spelling. An empty note means no note.

The note is kept with the active session (so it survives pausing, logging out and restarting the client) and saved with the task. A note entered just as the task completes, while the plugin waits a moment for the final kill's loot, is still saved with that task; one sent after the task was saved is written to its saved record instead. Tasks saved before notes existed have none.

## Task locations

The plugin records where you killed your task, so you can tell, for example, dust devils in the Smoke Dungeon from dust devils in the Catacombs. While a session is running, each kill the task count records is counted in the map region you are standing in (inside an instance, the region the instance copies). The task's location is the region with the most kills; on a tie, the one you killed in first. Kills while paused, or before Play, are not counted, as for the kill count itself. The kills per region are kept with the active session, so they survive pausing, logging out and restarting the client.

Regions have no names in the game, so you name them yourself, once each:

- The first time a kill is counted in a region without a name, a small **Name this location** box appears below the task box. Type a name, for example `Smoke Dungeon` (the box takes up to 40 characters), or pick one from its list, and click **Save** (or press Enter). The list suggests the task's locations from the bundled Old School RuneScape Wiki list (below), then the names you already gave other regions (one place can span several regions); picking one only fills in the box. You can still type any name. Names are tidied like notes: extra spaces are removed; an empty name isn't saved.
- **Skip** hides the box for that region and stops asking about it on this account, also after a restart (it is kept in RuneLite's per-account settings, like the names). One region is asked about at a time.
- To be asked again about the regions you skipped, tick **Ask again about skipped places** in the **Tracking** section of the plugin's configuration. Tick it while logged in: it forgets the skips of that account and then clears itself (the box shows unticked the next time you open the settings).
- A saved name is kept for your account (in RuneLite's per-account settings) and used for the running task, every later task there, and, on display, tasks already saved there without a name. Saved files are not rewritten for this.
- Konar's and Krystilia's tasks already come with their assigned area (see [Master, task type, Slayer points and streak](#master-task-type-slayer-points-and-streak)), so they are never asked about: the area is their location.

While a session is running or paused, the task box's second line shows the location's name once it has one, with `Location: <name>` in its tooltip; otherwise it shows a Konar or Krystilia task's assigned area. Each saved task stores its region and the location's name when it was saved. In the history, hover over a task to see its `Location:` line; it reads `Unknown` for tasks saved before locations were recorded and for regions nobody named. The location is in the tooltip only, because no history line has room for it in the sidebar.

Known limitations:

- A region is the game's 64 by 64 tile map square, so one area may span several regions (each needs a name, or you give them the same name), and one region may hold two places.
- The location is where you stood when the task count went down, which for ranged or magic kills may be a few tiles from the monster.
- Kills made while you are on a Sailing boat aren't given a location: they still count, but not toward any region.
- The suggested names cover the regular Slayer tasks in the bundled list; boss tasks and Revenants have none, and only your own names are suggested for them.

## Catacombs variants

Some Slayer task monsters have a stronger variant in the Catacombs of Kourend, with its own drops, for example Deviant spectres on an Aberrant spectres task. The plugin tells them apart by itself, from the monsters you kill; there is nothing to set.

| Task | Catacombs variant | Shown as |
|---|---|---|
| Aberrant spectres | Deviant spectre | Deviant spectres |
| Nechryael | Greater Nechryael | Greater nechryael |
| Bloodveld | Mutated Bloodveld | Mutated bloodveld |
| Jellies | Warped Jelly | Warped jellies |
| Banshees | Twisted Banshee | Twisted banshees |
| Black dragons | Brutal black dragon | Brutal black dragons |
| Blue dragons | Brutal blue dragon | Brutal blue dragons |
| Red dragons | Brutal red dragon | Brutal red dragons |

Each variant is recognised by the NPC id it has in the Catacombs (RuneLite's NPC ids, checked against RuneLite 1.13.4). The same names elsewhere, such as Greater Nechryael in the Wilderness Slayer Cave, Mutated Bloodveld in Meiyerditch or brutal dragons outside the Catacombs, are not counted as the Catacombs variant.

- **Which kills count.** While a session is **running**, every NPC you hit (a hitsplat of yours) that then dies counts as one kill of its name. NPCs only others hit, NPCs that leave the screen before they die, and NPCs you hit before Play, while paused or before a world hop or login are not counted. The kills per name are kept with the active session, so they survive pausing, logging out and restarting the client.
- **The majority decides.** The task counts as its variant if your counted kills of the variant are more than half of the session's task kills (the kills the game's task counter counted). Other monsters you kill, such as an Ankou on the way, the death spawns of Greater Nechryael or anything else caught in a barrage, don't count for the variant; only the task kills are compared. A superior (for example a Repugnant spectre) is a task kill but not the variant, so it counts as a standard kill; one superior barely moves the share. A tie, or no task kills yet, is the standard monster. A task that was in progress when you updated to this version has no tally for its earlier kills, so it usually saves as the standard monster. The same rule and numbers decide what the task box shows while you play and what is saved with the task, also after a restored session. The task count itself is unchanged: kills still come from the game's task counter.
- **Task box and overlay.** While the session runs or is paused, the task box shows the variant as soon as the kills so far say so, for example `Deviant spectres · 120 left`; hover over it to see the task and `Doing: Deviant spectres (from your kills)`. The [overlay](#task-overlay)'s Task row shows the same name.
- **History, Trends and totals.** A variant task is its own monster: the history row, its **Monster** filter, the [Trends](#trends) list and chart show it as `Deviant spectres`, apart from `Aberrant spectres`, so their profit, time and XP aren't mixed. Its tooltip says `Catacombs variant of Aberrant spectres`. [Overall totals](#overall-totals) **By monster** shows it as its own group too. By master's **Monsters assigned** counts it as its task, `Aberrant spectres`, because that is what the master assigned.
- **History estimate.** For your current task, the [History estimate](#historical-task-estimates), What to bring and the historical time left use both versions' tasks until the running or paused session has its first task kill, then only the version your kills say you are doing, by the rule above (the estimate's tooltip says which).
- **Unchanged.** The new-task chat preview, the [Task advisor](#task-advisor)'s main advice (its tooltip says so) and the [Luck meter](#luck-meter) still pool both versions by task, and so do the Skipped / blocked entries. Tasks saved before this version have no kill data, so they stay standard.

## Cannon tasks

A task counts as a **cannon task** when you fired any cannonballs, regular or granite, during it: when its [supplies](#supplies-tracking) include cannonballs fired. The plugin decides this by itself; there is nothing to set.

- **From the supplies.** Nothing extra is saved: a saved task is a cannon task when its supplies include fired cannonballs. So every task saved with supplies is classified the same way, including tasks saved before this version, and a task saved before supplies were tracked counts as a task without a cannon. Cannonballs that come back to your inventory (picking the cannon up, or unloading it) are not fired, so setting up and loading a cannon you never fire doesn't make a cannon task.
- **Names.** A cannon task is its own monster, named with `(cannon)`: `Dust devils (cannon)`, apart from `Dust devils`. Done on a [Catacombs variant](#catacombs-variants), both apply: `Deviant spectres (cannon)`.
- **Task box and overlay.** While the session runs or is paused, the task box shows `(cannon)` as soon as the session has fired a cannonball, for example `Dust devils (cannon) · 80 left`; hover over it to see `Cannon: cannonballs fired this session`. The [overlay](#task-overlay)'s Task row shows the same name.
- **History, Trends and totals.** The history row, its **Monster** filter, the task-complete chat line and the [Trends](#trends) list and chart show a cannon task under its cannon name, so the cannonball costs and the faster kills don't mix with your tasks without a cannon. So does [Overall totals](#overall-totals) **By monster**. By master's **Monsters assigned** counts it as its task, `Dust devils`.
- **History estimate.** For your current task, the [History estimate](#historical-task-estimates), What to bring and the historical time left use tasks with and without a cannon until the running or paused session fires its first cannonball, then only your cannon tasks of the monster (of the version your kills show, for a task with a [Catacombs variant](#catacombs-variants)), when you have at least one completed cannon task of it. With none yet, they keep using both after the cannonball too. The estimate's tooltip says which: `Only your cannon tasks count.`, or `No cannon tasks yet, so tasks without a cannon count too.` Without a cannonball they keep using both, however many kills you make, because you can still set up a cannon later in the task. The overlay's time left uses the same tasks as the sidebar's.
- **Unchanged.** The new-task chat preview, the [Task advisor](#task-advisor)'s main advice and the [Luck meter](#luck-meter) still pool tasks with and without a cannon, and so do the Skipped / blocked entries.

## Loot and supply values

While a session is running or paused, the panel shows its value below the kills and time:

- **Loot**: the value of the loot so far, at the **Loot price** setting (below), labelled `Loot (GE)` or `Loot (HA)` for high alch;
- **Supplies**: the GE value of the supplies used so far (potions per dose, pizzas, pies and cakes per bite, jewellery and the fungicide spray per charge);
- **Profit**: loot minus supplies (green for a profit, red for a loss), labelled `Profit (GE)` or `Profit (HA)` like the loot.

Amounts are shortened as in RuneLite's Loot Tracker (for example `9,450 gp`, `350K gp`, `1.23M gp`); hover over them for the exact GE and high-alchemy figures, and for how many loot and supply items had no price (counted separately, so an item that is both loot and a supply shows on each side). A GE figure that leaves out an item is marked `~` there too, so with **Loot price** set to **High alch** a missing GE price is still shown, and counted in the note as `loot at GE`. They update every couple of game ticks, using the prices first seen during the session, and disappear when the session ends. The task's saved values are taken again from current prices when it is saved.

### Loot price

The **Loot price** setting chooses how loot is valued, in the panel and in the task history:

- **GE** (the default): the Grand Exchange price.
- **High alch**: the high-alchemy value.

An earlier version also had a **Higher of the two** option; if you had chosen it, the setting goes back to **GE**.

Untradeable loot (a GE price of 0, for example Brimstone keys) counts at its high-alchemy value in both settings, including **GE**, so the GE figure isn't left at 0 for it. Its price is known, so it isn't marked `~`. An item whose GE price is unavailable is still left out and the total marked `~`. This also applies to saved tasks, which are valued at today's prices (their saved files are not changed). Supplies are not affected.

Supplies are always valued at GE prices, because you buy them. Profit follows the setting: loot at the chosen price minus supplies at GE. Changing the setting updates the panel and the history straight away; saved tasks themselves are not changed.

### Ground Items' hidden items

So you don't keep two lists, Slayer Ledger can use the hidden-items list of RuneLite's **Ground Items** plugin (owner decision, 2026-10-28). With the **Hide Ground Items' hidden items** setting on (in the **Values** section, on by default), every item that list hides is:

- left out of the **Loot received** icons: live, in a saved task's details and in **Overall totals**. The list's title counts what is left out, for example `Loot received (12, 3 hidden)`, and its tooltip says why; a list whose items are all hidden says `All hidden` instead of `None`;
- left out of the **loot value**, and so of **profit**, everywhere loot is valued, **except coins**: the live values and the overlay's profit, the history rows and their tooltips, **Overall totals**, **Trends** (profit/h and profit/kill, their chart, and the rows of [Catacombs variants](#catacombs-variants) and [cannon tasks](#cannon-tasks)), the **History estimate** (its profit per kill too, also when it counts only a variant's or cannon tasks), the task advisor's profit goal (its variant and cannon advice too), the task-complete chat line and the new-task chat preview. A hidden item whose price is unavailable no longer marks a value `~`.

**Coins always count in the loot value** (owner decision, 2026-10-28). If the list hides Coins, they are left out of the **Loot received** icons and counted in the title's `hidden` number like any other hidden item, but their value still counts in loot and profit everywhere. The tooltip says so: `3 items are hidden by your Ground Items hidden list; hidden items are left out of loot value, except coins.`

Nothing else changes: the items are still in the task's loot, the **Supplies used** lists are never filtered, and kills, keys and clue scrolls, notable drops and the **Luck meter** (which counts drops, not value) still see them. Slayer Ledger only reads Ground Items' settings; it never changes them.

**Your profit depends on your Ground Items list while the setting is on.** Saved tasks keep all their loot, so the numbers follow the list and the setting: edit the list in Ground Items, turn Ground Items off, or turn the setting off, and every value, saved tasks included, is worked out again at once. Two limits:

- Tasks saved without per-item prices have no saved high-alchemy value per item, so in **High alch** mode hidden items can't be left out of their value; tasks saved without items (only totals) keep their saved totals (see [Task history](#task-history)), hidden items included.
- Item names come from RuneLite's item data, so right after the client starts, a saved task's value may include a hidden item for a moment until its name has been looked up.

The list is matched as Ground Items matches it (RuneLite 1.13.4):

- entries are separated by commas; spaces around them are ignored;
- a name matches the whole name and ignores capitals, so `bones` hides Bones but not Big bones;
- `*` matches anything, so `*seed` hides every seed (RuneLite's own wildcard matcher);
- a noted drop matches like the unnoted item, since a note has the item's name;
- Ground Items' **Highlighted items** list wins over the hidden one, as in Ground Items: a highlighted name is never hidden, and a highlighted wildcard such as `Dragon*` beats a hidden wildcard such as `*bones`, but not a hidden full name;
- an entry with a quantity, such as `Coins < 1000` or `Bones > 5`, is ignored: Ground Items checks it against each pile on the ground, and Slayer Ledger only keeps a task's totals.

The list only applies while the **Ground Items** plugin is turned on: with it off, it hides nothing on the ground, so Slayer Ledger hides nothing either. If you never edited the list, Ground Items uses its built-in one, `Vial, Ashes, Coins, Bones, Bucket, Jug, Seaweed`, and so does Slayer Ledger: coins and bones are then hidden from the loot icons, and **bones are left out of your loot value** (coins still count). Remove them from the Ground Items list, or turn the setting off, to count them.

### Item lists

Below the loot, supplies and profit, two lists show what the task used and dropped item by item: **Supplies used** and **Loot received**. Both start closed, with the number of items in the title, for example `Supplies used (12)`; click a list's title to show or hide it. Like RuneLite's Loot Tracker, each list is a grid of item icons, five to a row, with the quantity drawn on the icon. A list with nothing in it says `None`. **Loot received** leaves out the items [Ground Items hides](#ground-items-hidden-items) and counts them in its title, for example `Loot received (59, 5 hidden)`: 59 items shown and 5 hidden. Every item list in the panel (the live lists, a history task's details and Overall totals) uses this title format. **Monsters assigned** shows no count in its title, because its lines count assignments, not monsters.

- Hover over an icon for the item's name, the exact quantity and its value, the item's GE price and high-alchemy value, and whether alching it pays (see below).
- Potions are counted in doses, pizzas, pies and cakes in bites, and teleport jewellery and the fungicide spray in charges, with the value per dose, bite or charge, as in the supplies total. Their icon shows the full item (for example a 4-dose potion) with the number of doses, bites or charges drawn on it; the tooltip says which it is, for example `Quantity: 22 doses`.
- Supplies are valued at GE prices and loot at the **Loot price** setting (an untradeable item at its high-alchemy value), using the same prices as the totals above, so the items add up to them. An item whose price is unavailable says `Value (GE): unavailable` in its tooltip.
- Icons are sorted by value, highest first, with unpriced items last. Every item is shown; there is no `+N more`.
- **Alch or sell?** A loot icon with a thin orange border is an item whose high-alchemy value, less the nature rune the cast uses (at today's GE price), is higher than its GE price; its tooltip explains it under `(alch)`. The GE tax is not taken into account. Untradeable items, which are already valued at high alch, are never marked, and nothing is marked while the nature rune's price is unavailable.

The icons come from RuneLite's item data and may appear a moment after the list opens. Item names are looked up once per item, so a tooltip may show `Item <id>` for a moment until its name arrives. A noted drop is shown separately from the unnoted item, as a bank note icon.

## Time left

While a session is running or paused, the panel shows an estimate of how long the rest of the task will take below the elapsed time, for example `Time left: ~42m`. It is shown in whole minutes, rounded to the nearest minute: `~42m` under an hour, `~1h 05m` from an hour on, and `~<1m` when less than half a minute is left. It is the session's average active time per kill so far multiplied by the kills left on the task, so it assumes you keep the same pace; it goes up if the task is extended and stands still while the session is paused. A restored session uses its restored kills and time. Until the session has at least 3 kills and 1 minute of active time the panel shows the historical estimate instead when you have completed the task before (see [Historical task estimates](#historical-task-estimates)), else `Time left: estimating...`; it shows `Time left: -` once no kills are left (the task is done or there is no task). Hover over it for your kills per hour and average time per kill, in short form: `18s` under a minute, `4m 05s` under an hour and `1h 02m` from an hour on.

## Historical task estimates

When a task is assigned and you have completed it before, the panel shows a **History** estimate below the task, even before you start the timer, for example:

```
History (12 tasks)   172 kills ~1:40:00
Loot (GE): ~2.1M     Supplies: 150K
```

It uses your completed sessions for that creature (older records without a creature id are matched by monster name; boss tasks are matched per boss; for a task with a [Catacombs variant](#catacombs-variants), only the version your kills in the running or paused session show, both until then; only your [cannon tasks](#cannon-tasks) once that session fires a cannonball, when you have completed any of them, both until then or without any) and estimates the kills left on the task: the whole task while it is new, and fewer once some kills are done, for example after a partial save. Loot is labelled with the selected **Loot price** mode; supplies are at GE prices. Like the task history, the estimate uses today's GE prices. The estimate stays visible while a session is running or paused. Until the live time-left estimate has 3 kills and 1 minute of active time, the time-left line uses the historical average time per kill instead (from the same tasks as the estimate, so the same location first); hover over it to see which one it shows.

Hover over the estimate for the average task duration, time per kill (in the short form `18s`, `4m 05s` or `1h 02m`), loot and supplies per kill, profit per kill (the loot per kill minus the supplies per kill shown above it, so loot at the **Loot price** setting and supplies at GE; marked `~` when either part is approximate, and `-` when they are unknown), supplies used per kill (in doses, bites or charges where they are counted that way), XP per skill per kill and XP per hour. Partial and other unfinished sessions, and skipped, blocked or untracked assignments, are left out. Older records count only towards what they recorded: tasks saved before values were tracked don't count towards the GP averages, and tasks saved before XP was tracked don't count towards the XP averages. Outliers are not trimmed, so a very short or long task moves the average. One completed task is enough for an estimate.

**Same location first.** When the task's current location (see [Task locations](#task-locations)) has completed history there, the estimate uses only those tasks and says so, with the title on a line of its own:

```
History at Smoke Dungeon (3 tasks)
172 kills ~1:20:00
Loot (GE): ~1.8M     Supplies: 120K
```

The current location is Konar's or Krystilia's assigned area, else the named region where the running or paused session has the most kills, else the named region you are standing in. Location names match ignoring case and extra spaces, and a task saved before its region was named counts once it is. With no completed task at that location, or while the location has no name, the estimate uses all your history of the task, as above. A long location name is cut short so the task count stays visible; hover for the full name.

## What to bring

For a task with completed history, **What to bring** below the History estimate suggests supplies for the kills left on the task (the assigned count while the task is new), like the History estimate. It takes the supplies used per kill over your completed tasks of that monster, multiplies by the kills left and rounds each amount up to a whole tracked unit: an item, dose, bite or charge. Bank trips work the same way, per kill over the tasks that recorded them, and show as `~2 bank trips` (or `No bank trips`). It counts the same tasks as the History estimate, so the version of a task with a Catacombs variant and the cannon tasks the session shows. Like the History estimate, it uses only your tasks at the current location when there are some there (the header's tooltip then says `From your tasks at <location> only.`), otherwise all of them.

The section starts closed and shows `What to bring (N tasks)`, the bank trips and the number of supplies; click it to show one row per supply, for example `Prayer potion` | `13 doses` or `Cannonballs` | `x21`. Rows are sorted by their GE value when a price is known, otherwise by amount. Hover over the header for what the numbers are based on. Older records with no supply or bank-trip data do not contribute, and the section is hidden until there is history.

## Task history

Below the current-task controls, the **Task history** section lists the logged-in account's saved tasks, newest first. Each row shows the monster, when the task ended (local time), the kills (as `kills / assigned amount` when the assigned amount is known), the active time (H:MM:SS), whether the task was **Completed** or **Partial**, and the task's loot (at the **Loot price** setting, labelled `(GE)` or `(HA)` like the live values), supplies and profit, and its XP gained with XP per hour of active time, for example `XP: 127K` and `XP/h: 120K` (`XP: -` for tasks saved before XP was tracked). Hover over a row for the exact GE and high-alchemy values, which prices are shown, the task's superiors, bank trips and deaths (`-` for tasks saved before they were counted), its master, type, Slayer points earned and task streak (when known), its location (`Unknown` when not recorded or named), its boss kill counts, keys, clue scrolls and notable drops (when there are any), and its note, if it has one. Tasks saved before values were tracked show `-` instead of a value (never `0`); a valued task in which no supplies were used shows `0` supplies. Row values leave out "gp" so the loot and supplies fit on one line; the tooltip shows the exact amounts in gp. Three filters above the list narrow it down, and they combine: a task is shown only when it matches all three.

**Monster icons**. Each row's monster shows the task's icon before its name, as in [Overall totals](#overall-totals): a Catacombs variant or cannon task shows its task's icon (`Deviant spectres` the Aberrant spectres icon, `Dust devils (cannon)` the Dust devils icon), and a name that is no known Slayer task an empty space, so the names stay lined up. The icons may appear a moment after the rows.

- **Monster** shows one monster's tasks. A task done on its [Catacombs variant](#catacombs-variants) is listed under the variant, for example `Deviant spectres`, and a [cannon task](#cannon-tasks) under its cannon name, for example `Dust devils (cannon)`.
- **Location** shows the tasks at one location, by the name the task's tooltip shows: its own location name, else its Konar or Wilderness area, else the name you gave its map region (also when you named the region after the task). Names that differ only in case or spacing are one place, so a location spelled anew keeps its tasks selected and the rows you opened up. Tasks without a location are listed as `No location`, last; a place you named "No location" yourself is a separate entry, listed with the other names.
- **Master** shows the tasks one Slayer master assigned, for example `Nieve/Steve`. Tasks whose master isn't known (older tasks, or a master Slayer Ledger doesn't recognise) are listed as `Unknown master`.

Each filter starts at **All** and lists only the values in this account's saved tasks, alphabetically, with `No location` and `Unknown master` last. A long name is cut short in the box, but the open list widens to show it (up to a limit); hover over the box to read the chosen name in full. When a value you picked is no longer in the history (you deleted its last task, edited its location, or logged in to another account), that filter goes back to **All**. When the filters together match none of your tasks, the list says `No saved tasks match these filters.`

The list shows the newest 25 matching tasks at first; click **Show 25 more** below them to add the next 25 (hover over it to see how many tasks match). Choosing another value in a filter, or logging in to another account, starts from the first 25 again; saving, editing or deleting a task keeps the tasks you have opened up.

When a completed task is successfully saved, the plugin posts a chat summary, starting "Slayer task complete:", with its kills, monster (its [Catacombs variant](#catacombs-variants), if it was done on one, and `(cannon)` for a [cannon task](#cannon-tasks)), active time, profit at the selected **Loot price** mode, and the number of superiors when at least one appeared. A profit that was not recorded is omitted, and so is a superior count that was not recorded or is 0. The **Task-complete notification** setting optionally adds a RuneLite notification; it is off by default. Partial, discarded, or unsuccessfully saved sessions do not produce a completion summary.

When a master gives you a new task, the plugin posts one chat line previewing it from your completed tasks of that monster, for example `Abyssal demons (172): ~1:40:00 · +1.2M (GE) · ~2 bank trips (from 3 tasks)`: the task and kills left, then the estimated active time, profit (loot at the **Loot price** setting, labelled `(GE)` or `(HA)`, minus supplies) and bank trips, worked out exactly as the [History estimate](#historical-task-estimates) and [What to bring](#what-to-bring) do, including the same-location-first rule (the line then ends `(from 1 task at Smoke Dungeon)`). Parts your history doesn't have are left out, and nothing is posted for a task you have no completed history of. Only a task assigned while you are logged in counts as new: a task you already had when you logged in, hopped worlds, restarted the plugin or had a session restored is not previewed again. If your saved tasks are still loading when the task is assigned, the line is posted once they have loaded (still only once). Turn it off with the **New-task chat preview** setting (on by default).

**Every saved task is valued at today's prices** (owner decision, 2026-10-28), so tasks done months apart compare fairly: the history rows and their tooltips and item details, **Overall totals** and its item lists, **Trends** (profit/h, profit/kill and the chart), the **History estimate** and **What to bring**, the task advisor (its variant and cannon advice too) and the new-task chat preview. In **GE** mode loot and supplies are at today's GE prices; in **High alch** mode loot is at its high-alchemy value, which never changes, and supplies are at today's GE prices. The task-complete chat line values the task just saved, so its prices are today's too. The prices are looked up once per item and kept until the plugin restarts; a task's tooltip says `Prices: today's GE prices (high alch values don't change)`. An item whose price RuneLite hasn't loaded is left out and the total marked `~`. It is looked up again every 3 seconds, up to 10 times, while you are logged in, so prices missing right after the client starts fill in once RuneLite has loaded them; after that, it is looked up again when your saved tasks change.

Whenever saved tasks are shown and some of their items' prices are on their way (after logging in, or when a task is saved), the history and the sections valued at today's prices keep what they show (`Loading saved tasks...` right after logging in) until the prices arrive, usually within a moment, and then redraw once, so the totals don't flash `~`. Until RuneLite has returned any price since the plugin started, prices that came back unavailable and are being looked up again (RuneLite may not have loaded its prices yet right after the client started) are waited for too; once it has, an item RuneLite has no price for is drawn at once, marked `~`. They never wait more than 3 seconds: after that they are drawn with the prices there are, the missing ones marked `~`, and redrawn when the rest arrive. **Show 25 more** clicks are ignored during the wait.

Saved tasks are never changed: each still keeps the prices it was saved with, but they are not shown. **Older tasks saved without per-item prices** are still valued from their items at today's GE prices, like any other task, so the row, its item details and Overall totals agree; only their high-alchemy values are their saved totals, since no high-alchemy value per item was kept, so in **High alch** mode their loot is marked `~`. **Tasks saved without items** (only totals) can't be revalued: they keep their saved totals, and a GE value that isn't 0 is marked `~` because it isn't at today's prices; the tooltip says `Prices: saved totals (no items kept), so GE values are marked ~`. Their high-alchemy total is exact. A task with no items and saved GE totals of 0 has nothing to revalue and shows 0 at today's prices.

Click a task to show its **Supplies used** and **Loot received** below it as item icons, in the same way as the live lists, with the same titles, for example `Supplies used (4)` and `Loot received (12, 2 hidden)`; click it again to hide them. They use the prices the task shows, today's GE prices with the saved high-alchemy values, so they match the task's totals; the first line says so. In **High alch** mode, older tasks saved without per-item high-alchemy values show their saved total marked `~`, and their loot items unpriced. Tasks saved before loot and supplies were tracked show `No item details`. Below the items, **XP gained** (with the number of skills in its title, for example `XP gained (5)`) lists the task's XP skill by skill as text lines, each with the skill's small icon before its name; hover over a line for the exact XP and XP per hour. It shows the 10 highest skills; click `+N more` to show the rest, and `Show less` to go back. Tasks with no XP gained, or saved before XP was tracked, have no XP list.

The hint under the list reads: "Right-click a task to edit its note or location, or delete it."

To add, change or remove a saved task's note, right-click its row and choose **Edit note**, then edit the text and press OK (leave it empty to remove the note; Cancel changes nothing). Only that saved record changes.

To change a saved task's location name, right-click its row and choose **Edit location**, edit the name and press OK (Cancel changes nothing). Only that saved record's name changes; the region's name for later tasks stays as it is. Leave it empty to remove the record's own name: it then shows its region's current name, or `Unknown`.

To delete a saved task, right-click its row and choose **Delete task**, then confirm. Deleting cannot be undone. It removes only that saved record; the session you are currently tracking, if any, is not affected. The history is hidden while you are logged out and always shows only the account that is logged in.

## Trends

The collapsed **Trends** section, below the task history, compares completed tasks by **Profit (GE)/h** or **Profit (HA)/h**, **Profit (GE)/kill** or **Profit (HA)/kill** (both labelled with your **Loot price** setting), **Slayer XP/h** or **Points/h**. Click its header to show or hide it, choose the **Metric**, and pick **List** or **Chart**. The section remembers your choices until RuneLite closes.

**List** ranks monsters by the metric. Each row shows the monster's icon, then the rank and monster on the left, and the rate with the number of completed tasks used, for example `~1.23M gp/h (n=12)`, on the right; Profit/kill shows, for example, `~12.3K gp/kill (n=12)`. Hover over a row for the exact rate and how it was worked out.

**Chart** plots one point per completed task for the monster chosen under **Monster**, oldest left, so you can see how a monster's rates changed over time. Each hourly rate uses that task's own active time, and Profit/kill that task's profit divided by its kills. A task appears only when it recorded the selected metric and has a completion date, and for the hourly metrics at least 1 minute of active time. Dates use your local time. Hover over a point for its completion time, rate and task note, which can help identify a setup change. When two monsters share a name, the choice says which is which, for example `(boss)`.

**Monster icons**. The List rows and the **Monster** choices show the task's icon before the name, as in [Overall totals](#overall-totals): a Catacombs variant or cannon task shows its task's icon, and a name that is no known Slayer task an empty space. The **Monster** box is sized for a short name, so a long one is cut short in the closed box; the open list widens to show it.

- Only completed tasks you tracked count. Partial tasks, skipped and blocked assignments, and untracked completions are left out.
- A task done on its [Catacombs variant](#catacombs-variants) is its own monster, for example `Deviant spectres` apart from `Aberrant spectres`, and so is a [cannon task](#cannon-tasks), for example `Dust devils (cannon)` apart from `Dust devils`.
- List rates pool each metric's totals and divide by the active time of the same tasks. A rate appears from one task, outliers included, but only once the monster has at least 1 minute of active time for that metric; until then the row shows `-` and is listed last.
- Profit/kill pools the same way over kills instead: the total profit of the monster's tasks that recorded profit, divided by the kills of those same tasks (not an average of each task's own figure). It needs no minimum active time, only tasks with at least 1 kill.
- Profit is loot at the **Loot price** setting minus supplies at GE, as in the history, at today's available GE prices (in High alch, for the GE-priced items, such as supplies). `~` means some prices were unavailable, or an older task kept its saved totals. Losses rank below 0.
- Slayer XP/h counts Slayer XP only, not combat or other skills.
- Points/h counts only tasks tracked from the full assigned amount. A task you started tracking part-way through is left out of Points/h, because its points also cover kills the ledger didn't see; it still counts for profit and XP. Points/h also leaves out milestone (bonus) tasks, every 10th task in a streak (10th, 50th, 100th and so on), because which monster lands on one is luck; their points still count everywhere else.
- A task that didn't record a metric (for example one saved before XP was tracked) is left out of that metric rather than counted as zero.
- Ties are listed by name.

## Task advisor

While you have a task, the task box can show advice on it, for example `Advice: Consider skip (n=3)`, from how the monster's rate compares with your other monsters' in your completed tasks. *n* is how many completed tasks of this monster the rate comes from. It is advice only: the plugin never skips or blocks anything. Hover over it for the reason, for example `Profit (GE)/h 10K: 1st lowest of your 6 monsters (bottom 25%)`, and a short reminder of the rules below. **Consider skip** and **Consider block** are shown in orange, as the task box's last line. **Do** adds no line, to keep the box short: hover over the task line to see `Advice: Do` and its reason.

- The rates are the ones the [Trends](#trends) list shows, worked out the same way, except that a task's [Catacombs variant](#catacombs-variants) tasks are counted with its standard ones, and its [cannon tasks](#cannon-tasks) with those without a cannon: completed tasks you tracked only, a rate from 1 minute of active time, profit at the **Loot price** setting and today's prices, Slayer XP only, and Points/h only from tasks tracked from the full assigned amount, leaving out milestone (bonus) tasks. All of a monster's tasks count, wherever you did them. How often a master assigns the task is not used.
- **Enough data per monster:** a monster counts only once it has at least 3 completed tasks with the goal's rate and at least 30 minutes of active time on them. One with less gets no advice and is left out of the ranking.
- **Boss tasks are ranked separately:** a boss task is only compared with your other boss tasks (the reason says `of your 6 bosses`), and other tasks only with other tasks, so a boss never moves a regular monster's position. Jad and Zuk tasks count as regular tasks.
- **Position:** among the counted monsters of the same kind (boss or not), the task's monster is the *n*th lowest, where *n* counts the monsters whose rate is the same as or lower than its own, itself included. Tied monsters therefore share the higher position, and when all rates are equal every monster is **Do**. In the upper half the reason counts from the top instead, for example `5th highest of your 24 monsters` for the 20th lowest.
- **Consider block** when *n* is at most 10% of the monsters, **Consider skip** when it is at most 25%, otherwise **Do**. With 24 monsters, the 2 lowest are **Consider block** and the 3rd to 6th **Consider skip**. Below 10 monsters nothing is **Consider block**, and with 6 or 7 only the lowest is **Consider skip**. **Consider block** also needs at least 5 tasks of the monster; with 3 or 4 it is **Consider skip** instead, and the reason says `(bottom 10%, block from 5 tasks)`.
- No advice is shown until this monster counts and at least 6 monsters of the same kind count, this one included. Bosses and other tasks each need their own 6.
- **Standard or Catacombs variant.** For a task with a [Catacombs variant](#catacombs-variants), the advisor also says which version has the better rate on its goal, for example `Better: Deviant spectres`, when both versions have enough data on their own: at least 3 completed tasks with the goal's rate and 30 minutes of active time each, as above (the other monsters don't matter for this). Hover over it for the whole line and both rates, for example `Profit (GE)/h: Deviant spectres 450K (n=4), Aberrant spectres 300K (n=5)`. It is the task box's last line when the advice is **Do** or there is none, and in the tooltip of the **Consider skip** or **Consider block** line otherwise. With equal rates, or too little data for either version, there is no variant advice.
- **With or without a cannon.** Likewise, when both your [cannon tasks](#cannon-tasks) and your tasks without a cannon of the monster have enough data on their own (at least 3 completed tasks with the goal's rate and 30 minutes of active time each), the advisor says which has had the better rate on its goal: `Better: with cannon` or `Better: without cannon`. Hover over it for the whole line and both rates, for example `Profit (GE)/h: Dust devils (cannon) 450K (n=4), Dust devils 300K (n=5)`; for a task with a Catacombs variant both groups count both versions, so they are named `Aberrant spectres, any version, with cannon` and `Aberrant spectres, any version, without cannon`. It is shown in the same place as the variant advice. A task with both shows them in one line, for example `Better: Deviant spectres, without cannon`, so the box keeps to three lines. The two are compared apart: the variant advice counts tasks with and without a cannon together, and the cannon advice counts both versions together. With equal rates, or too little data for either, there is no cannon advice.

The **Task advisor** section of the plugin's configuration has **Task advisor** to turn it on or off (on by default) and **Task advisor goal** to choose the rate it compares: **Profit/h** (the default), **Slayer XP/h** or **Points/h**. Changes apply at once.

## Task overlay

The **Slayer Ledger overlay** shows the tracked task without opening the sidebar: the task name, its kills left (on a **Left** row under the task, so the narrow box doesn't wrap), the session's kills, the estimated time left and the profit. It is shown while a session with a task is running or paused. While the session is paused, whether you paused it or a logout or world hop did, an orange **Paused** row (`Resume in sidebar`) comes first, even if every row below is turned off. It is hidden when no session is active or the session has no task yet, and it hides as soon as the final kill completes the task (while the last loot is still being counted, and after the task is saved). Right after you log in, until the plugin has read the task again, and while a task whose count reached 0 waits for its completion message, the kills left show as `?` and the time left as `-`. Time left and profit read exactly as in the sidebar: whole minutes such as `~42m` or `~1h 05m`, the historical estimate until the session has at least 3 kills and 1 minute of active time (`estimating...` when you haven't completed the task before), and profit labelled with the **Loot price** mode, such as `Profit (GE)` with `~` when some item prices are unavailable. The profit row appears once the session's values have been worked out. The overlay starts at the top left and can be moved with RuneLite's usual overlay drag (Alt-drag). Its settings are in the **Task overlay** section of the plugin's configuration: **Show task overlay** turns it on or off, and **Show task row** (the task and **Left** rows), **Show kills row**, **Show time left row** and **Show profit row** choose its rows. All are on by default.

**Not tracking reminder.** Sessions never start or resume by themselves, so the plugin tells you when a Slayer task kill isn't being tracked. When your task count goes down while no session is running, the overlay shows an orange **Not tracking** row (`Play in sidebar`), and chat gets one line: `Kill not counted: Slayer Ledger isn't tracking. Press Play in the sidebar.` The row goes away as soon as you press Play, when the task ends, or when you get a different task or log out; another untracked kill on the same task shows it again. When the session is paused instead, the **Paused** row is already showing, so only the chat line is posted: `Kill not counted: Slayer Ledger is paused. Press Resume in the sidebar.` Either line is posted at most once per task, on its first untracked kill, even across world hops and relogins; a new task can post it again. The kill that completes a task, a new or reset task, and the first reading after a login or hop never count as untracked kills. The row follows **Show task overlay**; the chat line is always on.

## Panel sections

The **Panel sections** section of the plugin's configuration hides sidebar sections you don't use: **What to bring**, **Trends**, **Overall totals**, **Luck meter** and **Skipped / blocked**. All are shown by default, and a change applies at once. A hidden section leaves no gap and is not worked out at all; turned back on, it shows the current data straight away, open or closed as it was. Hiding **What to bring** keeps the **History** estimate above it.

## Overall totals

The collapsed **Overall totals** section summarizes completed, non-partial played tasks. Click its header to show or hide it, and choose **Overall**, **By master**, **By month** or **By monster**. In **Overall** and **By month**, each group shows its task count, total active time, profit and XP, each on its own short line; profit and XP show the number of tasks that recorded them as `(n=...)`. Profit is labelled **Profit (GE)** or **Profit (HA)** with the selected **Loot price** mode and uses today's prices, as the history does; amounts are shortened as in the history (`~` means some item prices were unavailable, or an older task kept its saved totals). **XP (all skills)** is the XP of every skill gained on those tasks, not only Slayer XP. Hover over a group for the exact profit and XP, the price source and what each sample count means. Months use the local completion month, newest first; records without a known completion date or Slayer master appear in unknown groups listed last. A task without profit or XP data is left out of that total rather than counted as zero. Skipped, blocked, partial and untracked assignments are excluded.

**By monster** has one group per monster, named as the history names it: a [Catacombs variant](#catacombs-variants) and a [cannon task](#cannon-tasks) are groups of their own, for example `Deviant spectres` and `Dust devils (cannon)`. Groups with the most tasks come first, then by name. Each group shows its task count, then:

```
Tasks done:          12
Killed:              1,234
Avg task:            0:42:10
Avg profit (GE):     ~450K (n=12)
Profit (GE)/kill:    ~4,380
Time/kill:           24s
Profit (GE)/h:       ~640K
Slayer XP:           123K (n=12)
Slayer XP/h:         14.6K
```

**By master** shows, for each master, the task count, then:

```
Total tasks:         80
Avg task:            0:35:00
Avg profit (GE):     ~310K
Profit (GE):         ~24.8M (n=80)
Profit (GE)/h:       ~531K
Points:              1,566 (n=78)
Avg points:          18
Slayer XP:           1.04M (n=80)
Avg Slayer XP:       13K
```

and below those lines a **Monsters assigned** list: each monster the master gave you, with its count out of all the master's assignments, for example `Dust devils   12 / 80 (15%)`, most assigned first (ties by name). The list shows the top 10; click `+N more` to show the rest and `Show less` to go back. Hover over a line for the exact share and how those assignments ended, for example `Completed: 8`, `Skipped: 3`, `Blocked: 1`.

In both, the task count next to the group's name, and the **Tasks done** or **Total tasks** line, count completed tasks; hover over the count to read `80 completed tasks`. By master's assignments are counted separately, in **Monsters assigned**.

**Monster icons**. Each **By monster** group's name and each **Monsters assigned** line shows the task's icon before the monster's name: the same picture RuneLite's Slayer plugin shows for the task (mostly the Slayer guide's pictures; boss tasks show their pet), drawn small so the sidebar doesn't widen. A Catacombs variant or cannon task shows its task's icon, for example `Deviant spectres` and `Dust devils (cannon)` show the Aberrant spectres and Dust devils icons. A name that is no known Slayer task (for example `Unknown monster`, or a task newer than the plugin's list) has an empty space instead, so the names stay lined up.

**Master faces**. Each **By master** group's name shows the master's face before it: the master's chathead picture from the Old School RuneScape Wiki, bundled with the plugin and drawn at the same small size. A name two masters share shows the first one's face: `Turael/Aya` shows Turael, `Duradel/Kuradal` Duradel and `Nieve/Steve` Nieve; Konar shows Konar quo Maten. A master Slayer Ledger doesn't recognise (`Unknown master`) has an empty space, and so does every master if a build leaves the pictures out.

How these lines are worked out:

- **Which tasks.** The same as the other groupings: completed, non-partial played tasks, valued in the same way (the **Loot price** setting, today's prices, and loot [Ground Items hides](#ground-items-hidden-items) left out of the value, except coins). `~` means some item prices were unavailable, or an older task kept its saved totals.
- **Averages** are per task: the group's active time over its tasks; profit, Slayer XP and points over the tasks that recorded them. A task without a figure (for example one saved before XP or points were tracked) is left out of that figure's total and average rather than counted as 0; `(n=...)` on the total says how many tasks it comes from. The averages use those n tasks; the rates use those of them with active time (see **Rates**).
- **Rates** follow the [Trends](#trends) rules. **Profit/kill** is pooled: the total profit of the tasks that recorded profit, divided by the kills of those same tasks. **Profit/h** and **Slayer XP/h** divide the total by the active time of the same tasks, and show `-` until they have at least 1 minute of it. **Time/kill** is the group's active time over its kills, in short form: `18s` under a minute, `4m 05s` under an hour and `1h 02m` from an hour on. Tasks without any active time are left out of all four, as in Trends.
- **Slayer XP** is Slayer XP only, as in Trends, not the XP of all skills.
- **Slayer points.** **Points** counts every completed task that recorded its points, bonus (milestone) tasks included. **Avg points** leaves out the bonus tasks (a streak of 10, 20, 30 and so on), as Trends' Points/h does, so a few large bonus payouts don't swamp the usual points per task; the tooltip says how many tasks it averages over.
- **Monsters assigned** counts the master's completed tasks (the same tasks the group counts, so a completed task with no kills is not counted in either; each under the task's assigned name, so a Catacombs variant or cannon task counts as its task) plus every [Skipped / blocked](#skipped-and-blocked-tasks) entry of that master, whatever its outcome (skipped, blocked, reset, completed untracked or ended). The percentages are rounded to whole numbers (half up), so they may not add up to 100%; a share under 0.5% shows as `<1%`. Limits: an assignment you played only part of, saved as a partial task, and then skipped or reset counts nowhere, because it has no completed task and no entry; only the newest 500 entries are kept; a master with entries but no completed task has no group; and, rarely, one assignment counts twice. When the plugin didn't see an assignment start, it guesses from the newest saved task alone whether you played it; if that guess misses, the assignment can get both a completed task and a Skipped / blocked entry.

Below its lines, each group has two item lists, **Supplies used** and **Loot received**, with the items of all the group's tasks added up. They look and work like a history task's [item lists](#item-lists): a grid of item icons with the quantity drawn on them, the same tooltips and the orange alch border. Both start closed, with the number of items in the title; click a list's title to show or hide it. An open list stays open while the totals update, and closes again when you close the section or choose another grouping.

- The items come from the same tasks as the totals: completed, non-partial played tasks. Tasks saved before loot and supplies were tracked add nothing; when none of a group's tasks has item data, both lists say `None`. **Loot received** leaves out the items [Ground Items hides](#ground-items-hidden-items), as the profit does.
- Each item is valued as the group's profit counts it: loot at the **Loot price** setting and supplies at GE prices, at today's GE prices, so an icon's value is the sum of its tasks' values. The GE price and high-alchemy value in the tooltip are those of the newest of its tasks that had them. If only some of those tasks had a price for an item, its value counts the priced ones and shows `~`.
- Potions add up in doses, pizzas, pies and cakes in bites, and teleport jewellery and the fungicide spray in charges. A noted drop stays separate from the unnoted item.

## Luck meter

The collapsed **Luck meter**, below **Overall totals**, compares observed loot with the expected count for selected drops in the bundled snapshot. Click its header to show or hide it and choose a task; each **Task** choice shows the task's icon before its name, as in [Overall totals](#overall-totals). The box is sized for a short name; the open list widens to show a long one. It pools completed, non-partial tasks **with loot data**: tasks saved before loot tracking are left out, so their kills never inflate the expected count. The summary shows how many, for example `12 tasks / 3456 kills with loot data`. Expected drops are those kills multiplied by the listed drop rate, and saved loot item names (noted items included) give the observed count. Each drop shows its name and the observed / expected ratio, then `Observed / expected:` with both numbers. Below 1 expected drop the ratio is hidden and the row reads **too few kills**. Hover over a drop for the exact numbers and the chance of getting at least the observed count under a Poisson model. Rare drops vary a lot, so small samples say little about luck.

The comparison is approximate. Every kill of the task counts toward expected at the standard monster's rate, including variants (tasks done on a [Catacombs variant](#catacombs-variants), and [cannon tasks](#cannon-tasks), are pooled with their task here), superiors, task bosses (Abyssal Sire, Grotesque Guardians), Greater nechryael in the Catacombs and kills in any location. The initial snapshot covers selected fixed-quantity drops for Abyssal demons, Dust devils, Gargoyles, Kurask and Nechryael; it is not a complete drop table or a full loot simulation. Conditional, area-specific, clue, tertiary and shared rare-drop tables are excluded. Because the loot tracker records NPC loot received during a running task, unrelated kills can affect observed counts; pause the session during unrelated fights. If the bundled data can't be read, the section shows `Luck meter data unavailable`.

The versioned snapshot is bundled in [`slayer-drop-snapshot.json`](../src/main/resources/com/shmayvan/slayerledger/slayer-drop-snapshot.json). Its selected drop rates are adapted from the Old School RuneScape Wiki articles "Abyssal demon", "Dust devil", "Gargoyle", "Kurask" and "Nechryael" (revisions listed in the file) by Old School RuneScape Wiki contributors, and are distributed under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/), not the plugin's BSD licence. The plugin makes no runtime Wiki calls.

The bundled list of each Slayer task's locations, [`slayer-task-locations.json`](../src/main/resources/com/shmayvan/slayerledger/slayer-task-locations.json), is adapted from the Old School RuneScape Wiki's "Slayer task" articles, or the monster article where there is none (articles and revisions listed in the file), by Old School RuneScape Wiki contributors. Only the location names were kept, shortened and standardised to one short place name each; the **Name this location** box suggests them. It is also distributed under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/), not the plugin's BSD licence, and is read from the plugin jar, never from the Wiki at runtime.

## Skipped and blocked tasks

Below the task history, the collapsed **Skipped / blocked** section lists the Slayer assignments that ended without a saved session, so the history covers every assignment, not only the ones you played. Click its header to show or hide it. Each row is compact, for example `Abyssal demons · Skipped · 10/26/26`, with the task's icon before the monster (as in [Overall totals](#overall-totals); an empty space for a name that is no known Slayer task); hover over it for the master, task type, assigned amount and the change in Slayer points. Right-click a row and choose **Delete entry** to remove it.

The plugin watches every assignment, whether or not a session is running. It only reads game values and never acts. When an assignment ends without a session, it records one of these:

- **Skipped:** your Slayer points went down (cancel costs vary by master, so any drop counts and the real change is shown).
- **Blocked:** points went down and the game's blocked-task list changed at the same moment.
- **Reset:** your task streak went down with no points spent, for example a Turael or Spria reset.
- **Completed (untracked):** the task was completed while no session was tracking it.
- **Ended:** the signals fit none of the above, so the plugin doesn't guess. For example, the task was stored.

These entries never count as played tasks. They are left out of the saved-task count, the Task history filters, the time-left estimate, per-monster averages and **Trends**. They only count as assignments, in [Overall totals](#overall-totals)' By master **Monsters assigned** list. A task that had any session, whether saved as completed or partial, gets no entry. The newest 500 entries are kept. They are stored in the same ledger file, and older ledger files load unchanged.

Known limitations:

- The blocked-task values are not decoded: the plugin can't say which slot holds which task, only that one changed when the task ended. A block made from the rewards list while you keep your current task is not recorded.
- Entries are only made while you are logged in and the plugin is running. An assignment that changed while you were logged out (or on another client) gets no entry, and neither does one skipped in the last few seconds before you log out or hop.
- After a client restart, the plugin can't tell whether the task you already had was played in an earlier run. It skips the entry when your newest saved task is a partial session of the same task. A session you discarded isn't remembered across a restart, so skipping that task afterwards is logged.
- If your streak was already 0, a Turael reset changes nothing the plugin can see, so it is recorded as **Ended**. These rules have not been checked in game yet.
