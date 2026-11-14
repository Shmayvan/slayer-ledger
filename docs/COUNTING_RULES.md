# Slayer Ledger: counting rules and details

This page explains exactly what Slayer Ledger counts, how it values it, and the known limits of each feature. The [README](../README.md) gives the overview; read this when a number in the panel surprises you.

## Contents

- [Sessions and saving](#sessions-and-saving)
- [Loot tracking](#loot-tracking), [Bonecrusher and ash sanctifier](#bonecrusher-and-ash-sanctifier), [Ignored loot](#ignored-loot)
- [Supplies tracking](#supplies-tracking), including [Supplies not counted](#supplies-not-counted)
- [XP gained](#xp-gained)
- [Superiors, bank trips and deaths](#superiors-bank-trips-and-deaths)
- [Master, task type, Slayer points and streak](#master-task-type-slayer-points-and-streak)
- [Boss kill count](#boss-kill-count)
- [Keys, clue scrolls and notable drops](#keys-clue-scrolls-and-notable-drops)
- [Task notes](#task-notes), [Task locations](#task-locations), [Catacombs variants](#catacombs-variants), [Slayer bosses](#slayer-bosses), [Cannon tasks](#cannon-tasks) and [Setup tags](#setup-tags)
- [Loot and supply values](#loot-and-supply-values), [Loot price](#loot-price), [Ground Items' hidden items](#ground-items-hidden-items), [Item lists](#item-lists)
- [Time left](#time-left), [Historical task estimates](#historical-task-estimates), [What to bring](#what-to-bring)
- [Task history](#task-history) and the [Chat lines](#chat-lines)
- [Rates](#rates), [Task advisor](#task-advisor) and the 2.0 [Advisor](#advisor)
- [Task overlay](#task-overlay), including its state line and the [Supply runway](#supply-runway)
- [Panel sections](#panel-sections), [Overall totals](#overall-totals)
- [Skipped and blocked tasks](#skipped-and-blocked-tasks)

## Sessions and saving

Tracking is automatic: a session records the kills and active time of your current Slayer task from your first kill on it, with nothing to press. Saved tasks are stored locally per account under `~/.runelite/plugin-data/slayer-ledger/<account-hash>.json`. Data from earlier versions in `~/.runelite/slayer-ledger` is moved there automatically the first time the new version runs.

The sidebar starts with a status strip (see **States** below), then the **live task card**. Its header shows the task's icon and name, for example `Abyssal demons` (or its Catacombs variant or Slayer boss once your kills show it, for example `Deviant spectres` or `Grotesque Guardians`, with `+ boss` for a few boss kills; see [Catacombs variants](#catacombs-variants) and [Slayer bosses](#slayer-bosses); with `(cannon)` once it is a cannon task, for example `Dust devils (cannon)`; see [Cannon tasks](#cannon-tasks)), or `No Slayer task.`; then, each left out while unknown, a line with the location, and a line with the master, the task type (only when it isn't a regular task), the kills left while no session is active (before your first kill and after **End task**) and the next bonus task, for example `Konar · 201 left · bonus in 3`. (The task advice is on the [advisor card](#advisor), below the live task.) Hover over the name, location or master line to see each detail in full; a line too long for the card is cut short with `…`, and so is a value too long for the grid below, with its full value in its tooltip. While a session is active the card also shows the task's setup tags as small icons you can click (see [Setup tags](#setup-tags)), a progress bar with the kills done of the task, for example `73 / 201` (not when the task's size is unknown), and a grid of **Kills left** and **Time left**, **Pace** (active time per kill, for example `0:20/kill`) and **Kills/h**, **Elapsed** and **Superiors**, **Profit** and **XP**; once you have died or banked, **Deaths** and **Bank trips** follow. Pace and Kills/h show `?` until the session has 3 kills and 1 minute of active time, and Profit shows `?` until the session's items are valued. Profit is always signed, for example `+212K` or `-38K`. Below the card come the session's supplies, loot and XP (see [Item lists](#item-lists)), then its boss kill counts, keys, clue scrolls and notable drops, and its note, and then the [advisor card](#advisor).

**How a session starts.** Your first kill on an assigned task starts its session. A kill is a drop in the task's remaining count; an expeditious bracelet's double drop counts as 2 kills, because the kills always follow the task count. A bracelet of slaughter's kill leaves the count unchanged, so it counts no kill, but its message (`Your bracelet of slaughter prevents your slayer count from decreasing...`) starts or restarts the session like any task kill. The session's time is backdated to your first hit on the monsters you damaged that died around the kill (from 2 game ticks before the count dropped to 1 tick after), so the whole first fight counts, a boss's too. A ranged attack's or spell's hit lands a few ticks after the attack, which gives its XP at once, so such a hit counts from the game tick its attack started. Your hits on one monster with gaps longer than the Away limit (below) are separate fights, so a monster you hit long ago doesn't stretch the start. A kill with none of your monsters dying around it, for example a cannon-only kill, starts the clock at the kill. The time is never backdated before the task was assigned (or first seen by the plugin, for example at a login), before your previous session ended, or before the session last stopped. The monsters that died around your kills become the session's *task monsters*, for Away.

**Time.** A session's active time is the sum of the stretches it ran. A stretch starts at a start or restart and ends at a stop; time between stretches is never task time.

**Stops and restarts.**

- **Hopping worlds** is not a stop: the clock keeps running.
- **Logging out**, or losing the connection, stops the clock at once. Logging in again doesn't restart it: the sidebar and overlay show **Waiting for next kill**, one chat line says `Slayer Ledger: waiting for your next kill`, and your next task kill restarts the session, backdated to its fight as above. Your hits from before a logout are forgotten, so logged-out time never counts.
- **Away**: after 10 minutes (the **Away after** setting, 1 to 60 minutes) without a task kill or a hit on one of the session's task monsters, the session goes **Away**. Its time stops at your last kill or hit, so the time shown steps back once, by up to the limit, and the supplies, XP and loot and the bank trips since then are taken back out of the session (see below). A hit counts as activity at the end of its game tick, after that tick's supplies and XP are read, so what your last hit's tick used and gained stays. A bank trip shorter than the limit counts as task time, and so does a long fight with a task monster, since every hit on it counts as activity. Fighting other monsters (a raid, say) doesn't. Your next task kill restarts the session, never backdated before the Away stop.
- **Jad and Zuk tasks**: their only task kill comes at the end of a long run, so on a TzTok-Jad task the time inside the Fight Caves is task time, and on a TzKal-Zuk task the time inside the Inferno: entering starts or restarts the clock with no kill needed, and Away never starts inside. Leaving, also when you die, stops it and keeps what came before; the sidebar then shows **Away** until you enter again. Logging out inside stops it like any logout, and logging in inside starts it again at once. Pausing inside works as usual: the clock stays stopped until you press **Resume** or enter again.
- **Pause** in the sidebar stops the clock at once. **Resume**, shown only while paused, starts it again at once; so does your next task kill, backdated to its fight but never before the pause.
- **End task** in the sidebar stops the session and saves it as a partial record (with **Confirm before saving incomplete tasks** on, it asks whether to keep or discard it). That assignment is then **Ended**: no kill on it starts a session until you press **Track again**, so one assignment never turns into several partial records by accident. A new assignment is tracked normally. End task while waiting for the first kill just marks the assignment Ended. Either way, nothing used, gained or fought before End task counts after Track again. An Ended assignment stays Ended after a logout or a client restart, until you press Track again (also at the login screen).
- **Completing the task** saves the session as completed, after the short wait for the final loot (see [Loot tracking](#loot-tracking)). When the final kill also restarts the session, for example your last kill after logging in, the restart comes first, so its fight counts, then the completion. A new task assigned while a session is active saves the old one as a partial record, and the sidebar shows a notice with a **Discard** button. The notice stays while your next session runs, until you press Discard, the next session is saved or another account logs in.

**States.** The status strip at the top of the sidebar says in words what tracking is doing: **Running** (with the elapsed time beside it, for example `21:54`, or `1:03:31` from an hour on), Waiting for first kill, Away, Paused, Logged out (Waiting for next kill once you are logged in again), Ended or No task. Beside it are only the buttons that apply: **Pause** while running, **Resume** while paused, **End task** while there is a task or session to end, and **Track again** once it is Ended. A button does what it showed when you clicked it: if a kill restarted the session, or a new task cleared Ended, just before your click, the click does nothing. Whenever time isn't running, the overlay's first row says so too, except while logged out: after a login the overlay shows nothing until the plugin has read your task (see [Task overlay](#task-overlay)).

An in-progress session is not lost when you log out, close the client, turn the plugin off, or switch accounts. It is saved as that account's *active session* (`~/.runelite/plugin-data/slayer-ledger/<account-hash>.active.json`) instead of becoming a ledger record; an Ended assignment is kept in the same file, so it stays Ended. On the next login to the same account, if your task is still the same (same monster, and the remaining count has not gone up), the session comes back with its kills and time as after a logout, waiting for your next kill, and the panel shows "Restored previous session" until it restarts. Kills made while the plugin was not running are not counted. If the task changed, was completed, or there is no task, the old session is saved as a partial record and the panel offers a Discard button. A kill in the moments while the plugin is still checking for a session to restore after a login doesn't start a new session. Files written by this version can't be read by 1.x versions of the plugin, which leave them alone.

**Supplies, XP and loot around the clock.** While the clock runs, everything you use and gain counts at once. While a task is assigned but the clock is stopped (waiting for your first kill, Away, paused, or after logging in), what you use and gain is held back, for up to an hour, and nothing is held while no task is assigned or after **End task**. When the session starts or restarts, part of it counts:

- the **XP and loot** from the start of the stretch, which for a kill is your first hit on its monsters (so a boss fight counts in full) and always includes the XP of the kill itself;
- the **supplies** from 3 minutes before that: a pre-pot, a teleport to the task, potions drunk at the bank just before you come back from Away. Never from before the task was assigned (so nothing from a task you skipped reaches the next one), before your previous session on it ended, or before the clock last stopped.

The rest is dropped: XP, loot and supplies from a pause or a raid never count, apart from the raid's last 3 minutes of supplies before a restart. When the session goes Away, the supplies, XP and loot since your last kill or hit are taken back and held the same way, so a later restart counts only their last 3 minutes of supplies. The XP of your last kill, and the loot of the monsters that died around your task kills, are never taken back; such loot that arrives after the clock stopped (you paused or logged out just after the kill) still counts. That loot is recognised by the monster's name and by arriving within 10 game ticks (6 seconds) of your last task kill: a kill's loot arrives within a tick or two. Araxxor's gets 5 minutes, because it drops its loot only when you choose to harvest its corpse, which can be a while after the kill. That covers the kill that completes the task too: the task is still saved 2 game ticks after the count reaches 0, and a harvest within 5 minutes of that kill is added to the saved task afterwards (see [Loot tracking](#loot-tracking)). So on a session where none of your monsters ever died around a task kill (only cannon kills, say), the last kill's loot is taken back with the rest. Loot of those monsters at any other time is like any other loot: taken back by Away, and held back while the clock is stopped. That covers a Konar or Wilderness task monster killed outside the assigned area (no task kill), a monster that only happened to die beside a task kill, and a monster killed in the moment after logging in, before the plugin reads the task again. Superiors, bank trips and deaths still count only while the clock runs, and the bank trips since your last kill or hit are taken back when the session goes Away: they belong to whatever you went off to do. Deaths and superiors are never taken back: a death usually comes a few ticks after your last hit, and a long recovery after it doesn't make it any less a death on the task; only task kills spawn superiors.

If a task can't be saved (for example another program holds the ledger file open, or the disk is full), the panel says "Couldn't save the last task. See the client log." The task is kept beside the ledger (`<account-hash>.unsaved-<id>.json`) and added to it once, by the next save or login that can write the ledger. This also covers a ledger written by a newer version of the plugin: the task is kept until this version can write the ledger, or a newer one with the same format does.

## Loot tracking

While a session is **running**, Slayer Ledger tallies the loot you receive from NPC kills, using the same in-game loot data as RuneLite's Loot Tracker (pickpocket loot is ignored). Loot received while the clock is stopped, with a task assigned, is held back and counts only if a start or restart covers it (see [Sessions and saving](#sessions-and-saving)); loot of the monsters that died around your task kills counts when it arrives within 10 game ticks of your last task kill (5 minutes for Araxxor, whose loot comes when you harvest it), even if the clock stopped meanwhile. With no task assigned, or after **End task**, loot is not counted. When a running task completes, the save waits 2 game ticks so loot from the final kill that arrives just after the task count updates is still included; the task's kills and time are final at the moment it completed, and logging out, hopping, stopping or closing the client during those ticks saves it right away. Loot from any NPC you kill while running counts, not only your task monster; if you fight something unrelated for longer than the Away limit, it is taken back when the session goes Away, and you can pause the session for shorter fights. The tally is kept with the active session, so it survives a pause, Away, logging out and restarting the client.

**Araxxor's harvest after the task is saved.** When the kill that completes the task is Araxxor's (Araxxor died around it, as for any task kill) and you harvest its corpse after those 2 ticks, the task is saved without that loot, and the harvest is added to the saved task when it comes within 5 minutes of the kill, as if it had come before the save:

- its items join the task's loot, after the bonecrusher and **Ignored loot** settings as for any loot;
- they are priced when they arrive, as a task's loot is priced when it is saved, and the task's loot value is worked out again (an item the task already had keeps its saved price). As for every saved task, the history then shows it at today's prices, with [Ground Items' hidden items](#ground-items-hidden-items) left out of its value;
- a stack worth at least the **Notable drop value** is added to its notable drops.

Only the first Araxxor loot after that kill is added, and only on the same account: if it came before the save (also before the task's completion was confirmed, or with every item crushed or ignored), it was the harvest, and nothing is added later. Logging out, losing the connection, hopping worlds, switching accounts or a kill on a new task ends the wait, and so does turning the plugin off. If the harvest can't be written, the panel says "Couldn't add Araxxor's loot to the last task. See the client log."

Known limitations of the harvest:

- The task-complete chat line and its notification are posted when the task is saved, so they leave the harvest out, and the harvest gets no [notable drop line](#chat-lines).
- A pet from the harvest is not added: its message doesn't say which monster it came from.
- If you destroy the corpse instead and kill and harvest another Araxxor within 5 minutes of the final kill, with no new task, that loot is added to the task.
- If the task itself couldn't be saved (it is kept beside the ledger, see [Sessions and saving](#sessions-and-saving)), its harvest is not added.
- The harvest is recognised by the loot's monster name, Araxxor, and the final kill by Araxxor's death; neither has been checked in game yet.

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

While a session is **running**, Slayer Ledger also counts the supplies you use, and while a task is assigned outside it, it holds them back for the next start or restart (see [Sessions and saving](#sessions-and-saving)). Once per game tick, while a task is assigned and not ended, it compares your inventory, your worn ammo, weapon, amulet, ring and gloves slots, Dizana's quiver, your rune pouch (while it is in your inventory; the divine rune pouch uses the same slots) and your cannon's loaded cannonballs with the previous tick. These are added together, so moving items between them is never counted as use: filling or emptying the rune pouch, equipping ammo or jewellery, loading the quiver, and loading or picking up the cannon all net to zero. Only these kinds of item are counted:

- **Food**: any item with an *Eat* or *Drink* option that is not a potion (for example sharks, karambwans or jugs of wine). Other items that leave your inventory, such as bones you bury, items you alch or equipment you put on, are not counted.
- **Food eaten in parts, in bites**: pizzas (2 bites: the pizza, then the 1/2 pizza), pies (2 bites: the pie, then the half pie; the pie dish left over is not counted) and cakes and chocolate cakes (3 bites: the cake, the 2/3 cake, then the slice). Like potion doses, bites are counted per food and saved under the whole item, each valued at the whole item's GE price divided by its bites, so eating a whole pizza costs exactly one pizza and finishing a half pizza you already had costs half of one.
- **Potions, in doses**: an item named like `Prayer potion(4)` with a *Drink* option. Doses are counted per potion, so drinking a (4) into a (3) is one dose, and decanting or combining potions uses nothing. Doses are saved under the highest-dose version of that potion seen since the plugin started and valued per dose (the 4-dose price divided by 4, rounded to the nearest coin for the total).
- **Runes**: runes cast from your inventory or from any slot of your rune pouch or divine rune pouch, including the runes a thrall's resurrection spell uses. Elemental staves and tomes simply use no runes.
- **Ammo**: arrows, bolts, darts, knives, chinchompas and other ammo or thrown weapons that leave your ammo slot, weapon slot, quiver or inventory. Ammo you pick up again is taken off what you used for that item (never below zero). This includes blowpipe darts: picking up dropped darts of the type loaded in your blowpipe reduces its counted darts.
- **Cannonballs fired**: cannonballs that leave your cannon without coming back to your inventory. Granite cannonballs are told apart from regular ones by what you load. If tracking starts with your cannon already loaded (for example after restarting the plugin), regular cannonballs are assumed until a load or the pickup shows the type; picking up a granite-loaded cannon then counts only the granite cannonballs it fired, and one that never fired counts none.
- **Slayer consumables**: bags of salt and ice coolers, one per item used. Tools such as the rock hammer are not used up, so they are not counted.
- **Teleport tablets**: one per tablet broken. A tablet is an item with a *Break* option and "teleport" in its name, for example Varrock teleport, Teleport to house or Salve graveyard teleport. Other tablets (enchanting, Bones to bananas) are not counted.
- **Teleport scrolls**: one per scroll read with its *Teleport* option: the Nardah, Digsite, Feldip hills, Lunar isle, Mort'ton, Pest control, Piscatoris, Tai bwo wannai, Iorwerth camp, Mos le'harmless, Lumberyard, Zul-andra, Key master, Revenant cave, Watson, Ardeaglais, Chasm, Colossal wyrm, Guthixian temple and Spider cave teleports (the scrolls RuneLite's Item Identification plugin knows). Storing scrolls in the master scroll book (using them on the book) is not a use. Teleports from the book itself are not counted (see [what is not counted](#supplies-not-counted)).
- **Teleport jewellery and the fungicide spray, in charges**: rings of dueling, games necklaces, amulets of glory and glory (t), skills necklaces, combat bracelets, rings of wealth and wealth (i), necklaces of passage, digsite pendants, burning amulets, slayer rings, rings of returning, and the fungicide spray (10 charges). Like potion doses, charges are counted per item type, whether the item is in your inventory or worn, and saved under its highest-charge version (for example the amulet of glory(6)), each valued at that version's GE price divided by its charges. The last charge counts too, whether the item then crumbles (a ring of dueling or games necklace) or becomes an uncharged item (an amulet of glory, combat bracelet, skills necklace or ring of wealth, or Fungicide spray 0). Recharging, for example at the Fountain of Heroes, is not a use, and later charges count as normal. Refilling the spray with Fungicide is not a use either, and the Fungicide refill itself is not counted, so the spray's cost is counted once, per charge you spray. The amulet of eternal glory and the slayer ring (eternal) never run out, so they are not counted.
- **Bracelet of slaughter and expeditious bracelet, in charges**: one charge each time the game says the worn bracelet's effect activated (for example "Your bracelet of slaughter prevents your slayer count from decreasing. It has 29 charges left."), the same message RuneLite's Item Charges plugin counts by. The last charge counts too, whether the bracelet then crumbles to dust or regenerates. Charges are saved under the bracelet, 30 to a bracelet, and each is valued at the bracelet's GE price divided by 30. Checking a bracelet is not a use, and breaking one early to reset its charges is not counted.
- **Toxic blowpipe, tridents and powered staves, as estimates**: the game doesn't tell plugins how many darts, scales or charges are left, so each attack is counted from your character's attack animation while the weapon is wielded, at most one per game tick, and multiplied by its average cost. A blowpipe shot uses 2/3 of a Zulrah's scale and part of a dart, depending on what you wear in your cape slot: a whole dart with nothing, 0.4 with Ava's attractor, 0.28 with Ava's accumulator, 0.2 with Ava's assembler (including the Masori and max cape versions) or Dizana's quiver, and 0.28 with the ranging cape or max cape (0.2 with the **Ranging/max cape has Vorkath's head upgrade** setting on). Parts are carried over, so three shots use two scales, and are saved as whole items. A cast of a charged trident of the seas uses 1 death rune, 1 chaos rune, 5 fire runes and 10 coins; a trident of the swamp uses 1 death rune, 1 chaos rune, 5 fire runes and 1 Zulrah's scale. A cast of a charged Sanguinesti staff (regular or holy) uses 2 blood runes, and a cast of a charged Tumeken's shadow 2 soul runes and 5 chaos runes: what recharging the staff for one cast costs (OSRS Wiki, November 2026; the Sanguinesti staff took 3 blood runes a charge before 22 July 2026; aether runes charged into the shadow in place of soul runes are counted as soul runes). A staff cast is told apart from other spells by the staff's own cast graphic. These are counted as the runes, coins and scales themselves, so they are valued and saved like other supplies. Loading or unloading the blowpipe, or charging or uncharging a trident or staff, is not counted: those items are skipped for a short while after you *Use* them on the weapon or choose *Charge*, *Unload* or *Uncharge*, until the game confirms it (or after 30 seconds). Unloading the blowpipe prints no confirmation, so after an *Unload* (or a *Use* the game rejects, or a cancelled charge) those items are skipped for the full 30 seconds: darts, Zulrah's scales, coins and death, chaos, fire, blood, soul and aether runes used in that time, including for other spells, are not counted.

Supplies used while the clock is stopped (waiting for your first kill, Away, paused, after logging in) count only in the 3 minutes before the next start or restart (a task kill, Resume, or entering the Fight Caves or the Inferno on a Jad or Zuk task); supplies used since your last kill or hit are taken back when the session goes Away. Nothing is read or counted while no task is assigned or after **End task**. They are also not counted:

- while the bank, a deposit box, group storage, the seed vault, the Grand Exchange, a shop, a trade, or Death's office or coffer is open, and for 2 game ticks after it was last open (so a deposit made just as you close the bank is not counted);
- for an item you chose *Drop* on in the last 2 game ticks (dropping a rune pouch skips those ticks entirely);
- for 10 game ticks after you die, while your items move to your gravestone;
- for items you gain, such as loot you pick up (apart from the ammo pickup above).

The blowpipe's dart type is learned from the message you get when you *Check* it (or add darts), and remembered for your account. Until it is known, for example the first time you use the plugin or after you unload the darts or run out, shots count their scales only and the panel shows **Check your blowpipe to count darts** once a shot has been counted without a dart type, while the session is running and the blowpipe is wielded; *Check* the blowpipe once and darts are counted from then on.

Supplies used after a task completes are not added to that task, even during the short wait for its final loot. Like loot, supplies are kept with the active session (so they survive a pause, Away, logging out and restarting the client), saved with the task, and valued when the task is saved. Older saved tasks have no supplies.

Known limitations:

- Only the kinds of item listed above are counted; see [Supplies not counted](#supplies-not-counted) for what is left out and why.
- A recharged item's charges are valued at the GE price like any other charge, even though recharging was free. Untradeable charged items (such as the ring of wealth (i)) have a GE price of 0, so their charges are counted but add nothing to the supplies value. Imbuing a ring of wealth while a task is running counts its charges as used, because the imbued ring is a separate item.
- Only the pizzas, pies and cakes listed above are counted in bites. Any other food eaten in parts counts each part as one item when it is eaten, so its value is overstated.
- Blowpipe, trident and staff use is an estimate from the average rates. The Ava's device rates are assumed, not read from the game; Dizana's quiver is always assumed to save 80%, whatever its setting; and max cape variants other than the plain, accumulator, assembler and Dizana's max capes (such as the fire, infernal or imbued god max capes) count as no device. Whether a trident cast that splashes, and the blowpipe's special attack, are counted correctly has not been checked in game yet, and nor have the Sanguinesti staff's and Tumeken's shadow's casts (the holy Sanguinesti staff's cast graphic is assumed from the game's names for it).
- Pouring a potion out with *Empty*, or using food, ammo or another counted supply on something (other than loading the blowpipe, charging a trident or staff, or storing teleport scrolls in the master scroll book), counts as using it. Moving supplies into a looting bag or another storage item not listed above also counts as using them.
- If the cannon holds one cannonball type and you load another, or the cannon decays while loaded, the cannonballs may be counted under the wrong type or as fired. If tracking started with the cannon already loaded, the cannonballs it fires count as regular ones until you pick it up or load it; a task saved before that, or paused between the firing and the pickup, keeps them as regular cannonballs. They also stay regular when the cannon ran empty before you picked it up or loaded it, when the load was not a top-up of the cannonballs already in it, or when half or fewer of the cannonballs it last held came back on the pickup. Cannonballs of the other type looted as the cannon empties can only be taken for the cannon's type when they are more than half of what it last held.
- An ammo pickup is only taken off ammo of the same item already counted as used; it is not checked against where that ammo came from.

### Supplies not counted

These are left out on purpose, because the game gives a plugin no reliable way to see them used, or because a guess could count the wrong amount:

- **Toxic staff of the dead and serpentine helm**: their Zulrah's scales are used per minute in combat, not per attack (10 scales on entering combat and 10 more each minute you stay in it), and the game shows a plugin neither that timer nor how many scales are left.
- **Ash sanctifier and bonecrusher (and bonecrusher necklace) charges**: the game prints nothing when an ash or bone uses a charge, and a plugin can't tell whether the item is charged or switched on (its *Activity* option). A charge is also cheap: a tenth of a death rune per ash, and ecto-tokens, which charge the bonecrusher, have no GE price.
- **Teleports from the master scroll book**: the scrolls stored in the book are kept in game variables whose meaning hasn't been checked in game. Scrolls you carry are counted, and storing them in the book is not a use.
- **Other charged weapons**, for example the scythe of vitur, Venator bow, Craw's and Webweaver bows, Thammaron's and Accursed sceptres, Viggora's and Ursine chainmaces and crystal weapons: each needs its own attack signal and recharge cost checked first.
- **Other charged items whose charges aren't part of the item**, for example the Xeric's talisman, skull sceptre and the tome of fire's or water's pages: their charges only show in check messages. The teleport crystal and abyssal bracelet do have an item per charge, but aren't counted yet: the teleport crystal is untradeable, so its charges would add nothing to the supplies value, and the abyssal bracelet is a Runecraft teleport.
- **Runes from an infinite source** (an elemental staff or tome) are never used up, so nothing is counted. Thralls are not left out: their runes come from your inventory or rune pouch like any other spell's, so they are counted as runes.

## XP gained

While a session is **running**, Slayer Ledger adds up the XP you gain in every skill (and, from the clock's stopped stretches, what a start or restart covers; see [Sessions and saving](#sessions-and-saving)), for example Slayer, your combat style, Hitpoints, Magic from barrage and Prayer. While a session is active the panel shows the total below the time left, with XP per hour of active time once the session has a minute of it, for example `XP: 152K (48.2K/h)`. Hover over it for the exact total and every skill. The **XP gained** list below the item lists shows the XP skill by skill, highest first, with the skill's small icon before its name, as RuneLite's XP tracker shows; it starts closed, so click its title to show or hide it. A skill this version of RuneLite doesn't know (for example from a task saved by a newer client) has no icon, and its name stays lined up with the others.

- XP is read once per game tick and compared with the reading before. XP gained while the clock is stopped, with a task assigned, counts only from the start of the stretch a start or restart opens: from your first hit on the monsters that died around the kill (for a ranged attack or spell, from the tick it started, so its own XP counts), and always the XP of the kill itself. XP gained before that, while the session is paused or Away until then, since your last kill or hit when it goes Away, with no task assigned or after **End task** never counts.
- When a running task completes, its XP is final: XP from the final kill (on the tick the task completes) counts, and XP during the short wait for the final loot does not.
- After logging in, a world hop or a lost connection, the first reading is only a starting point, so the stats the game sends when you log in are never counted as XP gained. XP gained in the moments before that first reading is not counted.

The XP is kept with the active session (so it survives a pause, Away, logging out and restarting the client) and saved with the task. A session restored from before XP was tracked counts from the moment it was restored, so that one task's XP per hour comes out low: its XP covers only the time after the restore, but the rate uses the whole session's time.

## Superiors, bank trips and deaths

While a session is **running**, Slayer Ledger also counts:

- **Superiors**: each `A superior foe has appeared...` game message (the one RuneLite's Slayer plugin notifies on). Its colouring is ignored, both `<col>` tags and the game's own `@mes_hl_red@` markup. A superior that appears while the session is stopped (Away, paused or logged out), or after the task completed (during the short wait for its final loot), is not counted, unless the kill that spawned it starts or restarts the session (its count drops on the same game tick as the message or the next).
- **Bank trips**: each time you open the bank, a deposit box or group storage. Opening one again within 30 seconds (50 game ticks) of last having one open counts as the same trip, so going back for a forgotten item, or from a deposit box to the bank, counts once. Banking while the session is stopped (Away, paused or logged out) or not started is never counted, and a bank opened while it is stopped starts that 30-second window too. A bank trip shorter than the Away limit keeps the session running (see [Sessions and saving](#sessions-and-saving)); one after your last kill or hit, when the session then goes Away, is taken back.
- **Deaths**: each time you die. A death while the session is stopped (Away, paused or logged out), or after the task completed, is not counted. A counted death is never taken back when the session goes Away: it usually comes a few ticks after your last hit. Only the number of deaths is kept; what a death cost you (Death's fee or items lost) is not tracked.

While a session is active the panel shows `Superiors: N`, `Deaths: N` and `Bank trips: N` on one line below the time left. All three are kept with the active session (so they survive a pause, Away, logging out and restarting the client) and saved with the task. Tasks saved before they were counted show `-`; a session restored from before they were counted counts from the moment it was restored.

## Master, task type, Slayer points and streak

The live task card's master line shows who assigned your current task and, unless it is a regular task, what kind of task it is, for example `Krystilia · Wilderness` or `Duradel/Kuradal · Boss`. For Konar's tasks the location line above it shows the location she gave, for example `Kraken Cove`, until your kills place the task. Hover over the line to see `Master: Konar` and `Type: Konar (Kraken Cove)` in full. Each session saves them with the task, and a completed task also saves the Slayer points it gave and your task streak afterwards. Hover over a saved task in the history to see them.

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
- A task's final kill restarts a stopped session before it completes it, so every completion waits the 2 ticks for its final loot and late points. Whether the points and the streak always arrive on the same tick has not been checked in game yet.

## Boss kill count

On a boss task, the panel and the saved task use the boss's name, as RuneLite's own Slayer plugin shows it, for example `Vorkath`. While a session is active, the panel also shows the boss's kill count from the game's kill count message (`Your Vorkath kill count is: 152.`), above the keys and notable drops, for example:

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

- Kill count messages while the session is stopped are not in the `+N` (the kill that restarts a session may come just before its message), but the kill count shown includes them once the next kill is counted. Your kill count from before the task is not saved: it can't be told reliably (kills before the session started, or while it was stopped).
- A task lists at most 8 bosses; on a boss task, kills of an unrelated boss while the session runs (for example Tempoross) are listed too.
- The message formats are the ones RuneLite's own Chat Commands plugin reads. The boss names for Jad and Zuk tasks, and the messages themselves, have not been checked in game yet.

## Keys, clue scrolls and notable drops

While a session is active, the panel lists the Slayer keys and clue scrolls you have received during the task and its notable drops, below the loot and supply values, for example:

```
Keys: 2 brimstone
Clues: 1 hard, 2 elite
Notable drops:
  Abyssal whip: 1.5M
  Pet
```

Each line appears only when there is something to show. The three newest notable drops are listed; `+N more` means there are others, and hovering over the box lists them all with their exact values. Saved tasks show the same lines in their history tooltip, when there is data for them.

- **Keys and clue scrolls** are counted from the task's loot: brimstone keys (Konar's tasks), Larran's keys (Krystilia's Wilderness tasks) and clue scrolls by tier (beginner, easy, medium, hard, elite and master). Nothing extra is saved, so they follow the loot rules above (including what a start or restart counts, after the bonecrusher and **Ignored loot** settings), and tasks saved with loot before this version show their keys and clues too.
- **Notable drops**: a loot item stack from one kill worth at least the **Notable drop value** setting (default 100,000 gp) at the **Loot price** setting, for example one Abyssal whip, or 25 rune bars from one kill. Stacks of the same item in one kill count as one stack; an untradeable item counts at its high alch value, as in the loot value. Each drop keeps its item, quantity and value (GE and high alch) from when it dropped, so later price changes don't change it, and the panel shows the value at the current **Loot price** setting. Changing the setting only affects drops from then on. A notable item also gets a [chat line](#chat-lines), for example `Dragon chainbody (148K) from a dust devil.`
- **Pets** are always notable: the game's pet message (`You have a funny feeling like you're being followed`, or `You feel something weird sneaking into your backpack`) adds `Pet`, and the message for a pet you already have adds `Pet (already owned)`. The message doesn't say which pet it is.

Like loot, these count while the session is running, in the short wait for the final kill's loot after a task completes (and, for a notable drop, with Araxxor's harvest after its task is saved, see [Loot tracking](#loot-tracking)), and, held back while the clock is stopped, when a start or restart covers them. A notable drop is taken back with its loot when the session goes Away; a pet never is. They are kept with the active session and saved with the task.

Known limitations:

- Keys and clue scrolls only count when they come from NPC loot. A clue from anything else, for example a clue bottle, nest or geode while skilling, or an impling jar, is not counted, and nor are keys or clues you leave out with **Ignored loot**.
- A task keeps at most 20 notable drops so its saved file stays small. Beyond that, the least valuable item is left out (pets are always kept).
- An item whose price is unavailable when it drops (RuneLite hasn't loaded its prices yet) is never notable.
- Rare drops worth less than the threshold (so-called uniques) are not listed: RuneLite has no reliable list of which drops are uniques, and the plugin doesn't guess one.
- Whether the pet messages appear as expected, and whether loot reports a clue scroll by its own item id, has not been checked in game yet. The pet messages are the ones RuneLite's own Screenshot plugin uses.

## Task notes

While a session is active, the panel shows a **Note** box below the session's progress, for a short note on how you are doing the task, for example `Barrage`, `Prayer gear` or `Cannon`. Type a note and press Enter (or click elsewhere), or open the box's list to pick a recent one: it offers your most recent distinct notes from saved tasks, newest first, followed by `Prayer gear`, `Strength gear`, `Barrage`, `Range pray` and `Cannon`, up to 8 in all, so the same labels are reused. Notes are tidied when saved: extra spaces and line breaks are removed, and they are cut to 60 characters. Notes that differ only in capitals count once in the list, which uses the newest spelling. An empty note means no note.

The note is kept with the active session (so it survives a pause, Away, logging out and restarting the client) and saved with the task. A note entered just as the task completes, while the plugin waits a moment for the final kill's loot, is still saved with that task; one sent after the task was saved is written to its saved record instead. Tasks saved before notes existed have none.

## Task locations

The plugin names where you killed your task by itself, so you can tell, for example, dust devils in the Smoke Dungeon from dust devils in the Catacombs. There is nothing to name or set.

**How a task's location is decided.**

- While a session is running, each kill the task count records is counted at the tile you are standing on (inside an instance, the tile the instance copies). The bundled list of Slayer spots (its source is at the end of this section) says which spot that tile is in: each spot lists the game's map regions (64 by 64 tile squares) it covers. Where two spots share a region, the floor or a box drawn around each spot's monsters decides: for example the Alchemical Hydra's lair is the floor below the Karuulm Slayer Dungeon's drakes, and the Kraken's boss room is a box inside Kraken Cove's region.
- The task's location is the spot with the most kills. On a tie, the spot whose list of tasks includes your task wins, then the spot you killed in most recently.
- A map region the list doesn't have is a place of its own, named `Region 12345` until you rename it (below).
- Kills before the session starts are not counted, as for the kill count itself; a kill that restarts a stopped session counts where it happened. The kills per spot are kept with the active session, so they survive pausing, logging out and restarting the client.
- Each saved task keeps its spot and the map region with the most of its kills there.
- Konar's and Krystilia's tasks are placed by their kills too. Until the first kill, the live task card shows their assigned area.

**The name shown** for a task's location is, in this order:

1. the name you gave that task with **Edit location** (see [Task history](#task-history));
2. your own name for its map region: one you gave in Slayer Ledger 1.x's **Name this location** box, or a region you renamed (below). This is skipped in a region two spots share, such as Kraken Cove and the Kraken's boss room, or the Karuulm Slayer Dungeon and the Alchemical Hydra's lair: a name you gave that region can't say which of them you meant, so each shows the list's name;
3. the list's name for its spot, for example `Catacombs of Kourend`, or `Region 12345` for a region the list doesn't have.

A task with no spot shows your name for its region if you gave one, else its Konar or Krystilia area if it has one, otherwise `Unknown`. Names never decide where a task is grouped: tasks are grouped by spot, so a name of your own (for example a region you named `Myth's Guild`, which the list calls the Corsair Cove Dungeon) stays what you see, and the task still counts with that spot's other tasks.

**Renaming.**

- **Rename place (all its tasks)**, in the right-click menu of a saved task in a region the list doesn't have, names that region for every task there: the running task, every later task there and the saved tasks there without a name of their own. The name is kept for your account (in RuneLite's per-account settings, like the 1.x names); saved files are not rewritten. Names are tidied like notes and cut to 40 characters; an empty name changes nothing.
- **Edit location** renames only that task. It keeps its spot, so it still groups with the spot's other tasks.

**Tasks saved before 2.0** keep the region and the name they were saved with. Their spot is worked out from their region each time they are read: the spot that lists the region; in a region shared by several spots, the one shared spot whose list of tasks includes the task; otherwise, and for tasks saved without a region, the spot is Unknown. The files are not changed for this.

**Where it shows.** While a session is active, the live task card's location line shows the location once a kill was counted, with `Location: <name>` in its tooltip. In the history, each row shows the location's short name after its date (the list's short name, such as `Catacombs`, in place of its full one; a name you gave shows as you typed it, cut short when long), and hovering over a task shows its `Location:` line in full; it reads `Unknown` for a task with no spot and no name, whose row shows only the date. The history's **Location** filter lists the spots of your tasks (see [Task history](#task-history)).

The **Name this location** box and the **Ask again about skipped places** setting of 1.x are gone; the names you gave in it are still used.

Known limitations:

- The location is where you stood when the task count went down, which for ranged or magic kills may be a few tiles from the monster. The boxes that split shared regions were drawn halfway across the gaps between the spots' monster spawns, so a kill made from just outside a line can count for the neighbouring spot.
- The Kraken's boss room ends at a line between y 10017 and 10021 that is inferred from the spawns and the crevice; standing on Kraken Cove's north shore near it may count as the boss room.
- The Thermonuclear smoke devil's lair also has four ordinary smoke devils, so a smoke devil task partly done there counts for the boss's lair.
- Some names are towns, kingdoms or provinces, for example `Wilderness` or `Lumbridge`. Such a spot covers only the regions where its tasks' monsters spawn, and gives way to more specific spots.
- Where two places' monsters are mixed in one region, one of them keeps the region (for example Lumbridge keeps region 12850 from the River Lum), and kills there count for it.
- Parts of a place with no Slayer monsters, such as corridors, banks and entrances, are not in the list: kills there count as their region's own place.
- The regions of newer content (Varlamore, the Sailing islands, Mortimer's areas and a few others) come from the Wiki's maps only; they could not be checked against an independent list of spawns.
- 13 low-level places from the Wiki's task pages have no spot, for example the Grand Tree for bats; kills there count as their region's own place.
- Two places can show the same name in the **Location** filter (for example a region you named after a place the list has elsewhere); they stay separate entries.
- A 1.x task keeps the name it was saved with, so renaming its region later doesn't rename it; clear its own name with **Edit location** (leave it empty) to let it follow.
- Kills made while you are on a Sailing boat aren't given a location: they still count, but not toward any spot.

The bundled list of Slayer spots, [`slayer-spots.json`](../src/main/resources/com/shmayvan/slayerledger/slayer-spots.json), is adapted from the Old School RuneScape Wiki's "Slayer task" articles and the monster and location articles they link to (each spot lists the article revisions it was read from, retrieved 9 November 2026), by Old School RuneScape Wiki contributors: the spot names and their tasks, with map regions computed from the monsters' spawn coordinates and short names added. It is distributed under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/), not the plugin's BSD licence, and is read from the plugin jar, never from the Wiki at runtime. The coordinates were checked against, and the Wiki's moved dungeon maps translated with, the NPC spawn and transport data of [mejrs/data_osrs](https://github.com/mejrs/data_osrs) (commit `7777c539`), and a few region ids were derived from it (the Dagannoth Kings' lairs and three Stronghold of Security floors); none of its records are copied.

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

- **Which kills count.** While a session is **running**, every NPC you hit (a hitsplat of yours) that then dies counts as one kill of its name. NPCs only others hit, NPCs that leave the screen before they die, NPCs that die while the session is stopped (except those that died around the kill that starts or restarts it, from 2 game ticks before its count dropped, or before it dropped to zero when the task's completion came a few ticks later, which that kill counts, each once; and those that died on the tick after the task's final kill, which the completed task counts during the short wait for its loot), and NPCs you hit before a world hop or login are not counted; your hits while no session runs are remembered (so the kill that starts or restarts the session can be timed from them), so an NPC you hit just before the session started counts when it dies after. The kills per name are kept with the active session, so they survive pausing, logging out and restarting the client, and are saved with the task.
- **The most kills decide.** The task counts as its variant if your counted kills of the variant are more than your counted kills of the standard monster. Only task kills count: the standard monster's kills are your other counted kills, but never more than the session's task kills (the kills the game's task counter counted) that the variant's kills and the superiors leave over. So other monsters you kill, such as an Ankou on the way, the death spawns of Greater Nechryael or anything else caught in a barrage, never count as the variant; but while some task kills weren't counted by name, they can stand in for those missing kills on the standard side. A superior (for example a Repugnant spectre) is a task kill, but it counts for neither version either. A tie, or no task kills yet, is the standard monster. Task kills whose monster wasn't counted (for example a task that was in progress when you updated to a version that counts kills by name) count for neither, so the kills that were counted decide; before 2.0 they counted as the standard monster. The same rule and numbers decide what the live task card shows while you play and what is saved with the task, also after a restored session, and the same rule decides a task done on its [Slayer boss](#slayer-bosses). The task count itself is unchanged: kills still come from the game's task counter.
- **Live task card and overlay.** While a session is active, the live task card shows the variant as soon as the kills so far say so, for example `Deviant spectres`; hover over it to see the task and `Doing: Deviant spectres (from your kills)`. The [overlay](#task-overlay)'s Task row shows the same name.
- **History and totals.** A variant task is its own monster: the history row and its **Monster** filter show it as `Deviant spectres`, apart from `Aberrant spectres`, so their profit, time and XP aren't mixed. Its tooltip says `Catacombs variant of Aberrant spectres`. [Overall totals](#overall-totals) **By monster** shows it as its own group too. By master's **Monsters assigned** counts it as its task, `Aberrant spectres`, because that is what the master assigned.
- **Historical time left.** For your current task, the [historical time left](#historical-task-estimates) uses both versions' tasks until the active session has its first task kill, then only the version your kills say you are doing, by the rule above.
- **Unchanged.** The [Advisor](#advisor) and its chat line and the [Task advisor](#task-advisor)'s main advice (its tooltip says so) still pool both versions by task, and so do the Skipped / blocked entries. Tasks saved before this version have no kill data, so they stay standard.

## Slayer bosses

Some Slayer tasks have a Slayer boss that can only be fought on that task (or on a boss task for it), for example the Grotesque Guardians on a gargoyle task. The plugin tells a task done on its boss apart by itself, from the monsters you kill; there is nothing to set.

| Task | Slayer boss | Counted from the deaths of |
|---|---|---|
| Gargoyles | Grotesque Guardians | Dusk and Dawn |
| Cave kraken | Kraken | Kraken |
| Hellhounds | Cerberus | Cerberus |
| Smoke devils | Thermonuclear smoke devil | Thermonuclear smoke devil |
| Abyssal demons | Abyssal Sire | Abyssal Sire |
| Hydras | Alchemical Hydra | Alchemical Hydra |
| Araxytes and Spiders | Araxxor | Araxxor |
| Gryphons | Shellbane gryphon | Shellbane gryphon |

The list is the Old School RuneScape Wiki's list of Slayer bosses (the bosses that need a task for the boss itself or for its monster), each row checked against the boss's own article (revisions of 9 October to 8 November 2026, read 9 November 2026). The NPC names are the game's (RuneLite's NPC names, checked against RuneLite 1.13.4); each boss keeps its name in every phase or form. Bosses that are only ever boss tasks (such as Vorkath or Zulrah) and bosses that count for a task without needing one (such as the King Black Dragon for black dragons) are not on the list.

- **Which kills count.** The same as for [Catacombs variants](#catacombs-variants): the monsters you hit that die while a session runs, by name. A Grotesque Guardians kill is one kill, however many of Dusk and Dawn are counted, also with the Double Trouble reward, which takes two kills off the task for it. So on a task that mixed the Guardians and gargoyles with Double Trouble, the Guardians weigh less than their share of the task (a task done only on the Guardians is still the Guardians). The Kraken counts when the Kraken dies, not the whirlpool it rises from. What the boss summons, such as the Sire's respiratory systems and spawns, Araxxor's araxytes or the Kraken's tentacles, takes no kills off the task and counts for nothing.
- **Done on the boss.** The task counts as done on its boss when your boss kills are more than your kills of the task's own monsters, by the [Catacombs variants](#catacombs-variants)' rule (superiors left out, a tie is the task's own monster). It is then named after the boss, as a variant is: the live task card and the overlay's Task row show `Grotesque Guardians` while you play, and the history row, its **Monster** filter, [Overall totals](#overall-totals) **By monster** and the task-complete chat line show `Grotesque Guardians`. The history tooltip says `Slayer boss of Gargoyles`.
- **Boss kills.** Any kill of the task's boss, even one, gives the task its boss mark, which the saved task keeps. When the task's name doesn't already say so, the live task card and the overlay's Task row add `+ boss`, for example `Cave kraken + boss`, and the card's and the history row's tooltips say `Boss kills: Kraken`.
- **Boss tasks** (type Boss) are their own pool, named after their boss already: they are never named after another boss and have no boss mark.
- **Unchanged.** The [historical time left](#historical-task-estimates) and the [Task advisor](#task-advisor) still pool a task done on its boss with the task's other tasks. The [Advisor](#advisor) counts it in the task's pool, as its own option. By master's **Monsters assigned** counts it as its task, `Gargoyles`, because that is what the master assigned.
- **Tasks saved by earlier versions** get the boss mark the first time this version reads them, from their kills by name when they have any, else from their [boss kill counts](#boss-kill-count). Earlier versions kept neither for a boss fought on a regular task, so in practice their tasks get no boss mark. Their name is kept as saved.

Known limitations:

- Earlier versions counted a boss's kill count only on boss tasks and on tasks named after the boss, so a gargoyle task they saved after fighting the Grotesque Guardians has no boss mark, and isn't named after the boss.
- On a gargoyle task, the Grotesque Guardians' kill count messages are still not counted (see [Boss kill count](#boss-kill-count)).
- With Double Trouble, the task-complete chat line's kill count is the task's (two per Guardians kill), next to the boss's name.
- The Cerberus and Araxxor boss tasks have the same names as a hellhound or araxyte task done on the boss, so the history's **Monster** filter and Overall totals **By monster** show them together; the other bosses' boss tasks have their own names (for example `The Grotesque Guardians`). The historical time left and the advisor keep them apart.
- Whether every boss's death (Araxxor's, whose corpse stays to be harvested, and Dawn's in particular) is seen as your kill has not been checked in game yet.

## Cannon tasks

A task counts as a **cannon task** when its **Cannon** [setup tag](#setup-tags) is Yes: the value you set, or else the detected one, which is Yes when at least half of the task's kills were made while your cannon was firing. So a cannon set up for a few kills fires cannonballs but doesn't make a cannon task, and a task you set to Yes is one whatever it fired. (Before 2.0 a task was a cannon task when it fired any cannonballs.)

- **Tasks saved by earlier versions.** Their Cannon tag is estimated from the cannonballs they fired, as earlier versions grouped them: Yes when they fired any, otherwise No (also for a task saved before supplies were tracked). Cannonballs that come back to your inventory (picking the cannon up, or unloading it) are not fired, so setting up and loading a cannon you never fire never counts. You can change the tag by hand with **Setup…**, and the task's name and groups follow it.
- **A task started before the update.** A task in progress when you updated from 1.x comes back with its kills, but they recorded nothing about the cannon, so they are left out: its Cannon tag is Yes when at least half of the kills made since the update were cannon kills. Until the first of those is recorded, the cannonballs it fired decide, as for a task saved by an earlier version. So a cannon task stays one after its next cannon kill, live and when it is saved, and its Cannon toggle's tooltip counts the same kills, for example `Cannon kills: 1 of 1` after the first.
- **Names.** A cannon task is its own monster, named with `(cannon)`: `Dust devils (cannon)`, apart from `Dust devils`. Done on a [Catacombs variant](#catacombs-variants), both apply: `Deviant spectres (cannon)`.
- **Live task card and overlay.** While a session is active, the live task card shows `(cannon)` while the session's Cannon tag is Yes (its Cannon toggle), for example `Dust devils (cannon)`; hover over it to see `Cannon: Yes, a cannon task`. Before its first kill is counted, a session's tag is Yes as soon as it has fired a cannonball; from then on it follows its kills, so a cannonball fired just before a kill made without the cannon doesn't make it Yes. The [overlay](#task-overlay)'s Task row shows the same name.
- **History and totals.** The history row, its **Monster** filter and the task-complete chat line show a cannon task under its cannon name, so the cannonball costs and the faster kills don't mix with your tasks without a cannon. So does [Overall totals](#overall-totals) **By monster**. By master's **Monsters assigned** counts it as its task, `Dust devils`. The **Monster** filter's `(cannon)` names and the **Cannon** filter (see [Advisor](#advisor)) go by the same tag, so they always agree.
- **Historical time left.** For your current task, the [historical time left](#historical-task-estimates) uses tasks with and without a cannon until the active session's Cannon tag is Yes, then only your cannon tasks of the monster (of the version your kills show, for a task with a [Catacombs variant](#catacombs-variants)), when you have at least one completed cannon task of it. With none yet, it keeps using both. While the tag is No it keeps using both, however many kills you make, because you can still set up a cannon later in the task. The overlay's time left uses the same tasks as the sidebar's.
- **Unchanged.** The [Task advisor](#task-advisor)'s main advice still pools tasks with and without a cannon, and so do the Skipped / blocked entries. The [Advisor](#advisor) splits its options by the Cannon tag, and its chat line names the best setup with the other cannon choice.

## Setup tags

Each saved task has three setup tags, shown in its history tooltip:

- **Cannon**: Yes or No.
- **Style**: Melee, Ranged, Barrage (Ancient Magicks bursts and barrages), Magic (any other spell) or Mixed (no style did most of the work), or Unknown.
- **Gear**: Prayer, Strength or Mixed, or Unknown.

Slayer Ledger keeps the value it detected and, separately, any value you set yourself. The value you set always wins. The tags are shown in the tooltip, and they decide how your history is grouped and filtered and what the advisor suggests: the history's **Cannon**, **Style** and **Gear** filters and the [Advisor](#advisor)'s options go by them, and so does a task's `(cannon)` name in the history, its **Monster** filter and Overall totals (see [Cannon tasks](#cannon-tasks)).

**Tasks you play now.** Each task kill records what it was done with, and the task's tags are worked out from all its kills, live and when it is saved:

- **Cannon**: a kill is a cannon kill when your cannon was set up and fired (its loaded cannonballs went down) in the 10 game ticks up to the kill. A decayed cannon counts as not set up, so its cannonballs emptying when it decays is no shot. The task is Yes when at least half of its kills were cannon kills, so a brief cannon setup doesn't make it Yes.
- **Style**: each of your attacks gets a style when its animation starts, from the spell you cast or the weapon you hold, and each of your hitsplats on a monster is put down to your attack on that monster that landed it. Every hit on a monster lands one game tick later than the weapon's own hit delay, because the game handles monsters before players (the OSRS Wiki's Hit delay page). So a melee attack's hits land on the tick after it starts (the dragon claws' special attack's last two on the tick after that), and a ranged attack's or spell's 2 to 6 ticks after it, depending on the distance. A melee attack on the monster is looked for first, then the latest ranged attack or spell on it: when you switch weapons, your next attack waits out the old weapon's attack speed, so by then the earlier attack's hits have landed. Burst, Barrage and chinchompas also hit the monsters around their target: those hits count on the tick their hit on the target lands, since all of one cast's hits land together. So a dart's hit that lands after you switched to a melee weapon is still Ranged, and the cannon's hits on other monsters while you attack have no style. A monster's style is the style of most of your hits on it (a tie is Mixed). The task's style is the one that covers at least half of its kills that have a style; a tie, or no style reaching half, is Mixed. A kill with no attack of yours around its hits, such as the cannon's, has no style, and a task with no styled kill at all (cannon only) is Unknown.
  - **Barrage**: Ancient Magicks' Burst and Barrage, which share one cast animation, with its walking and priority variants.
  - **Magic**: any other spell: Rush and Blitz, the standard spellbook's strikes, bolts, blasts, waves and surges, Crumble Undead, Iban Blast, Magic Dart, the god spells, and the Arceuus grasp and demonbane spells. Also an attack with a weapon whose magic attack bonus is its highest: tridents, powered staves and sceptres, the kodai wand, nightmare staves, the master wand.
  - **Ranged**: an attack with a weapon that has a ranged strength bonus, or whose ranged attack bonus is its highest: bows, crossbows, the blowpipe, darts (which have only ranged strength), knives, chinchompas.
  - **Melee**: any other weapon, or none. A staff with melee bonuses above its magic one (the staff of the dead, Ahrim's or the ancient staff) is Melee when it bashes; its spells are Magic or Barrage by their cast.

  An attack is an animation that starts while you are attacking a monster and gains you Attack, Strength, Defence, Magic or Hitpoints XP on that game tick: every attack that hits gains XP at once, before its hitsplat shows, and a spell gains its base Magic XP even when it splashes. Eating, drinking, loading a cannon, picking something up or blocking a monster's hit is not one. Ranged XP alone doesn't make an attack, since the cannon gains it too (with no Hitpoints XP); your own ranged attacks that hit gain Hitpoints XP as well. An attack that misses gains no XP, so its 0 hitsplat has no style; a kill's style comes from the hits that did damage.
- **Gear**: at each kill, the prayer bonuses of everything you wear are added up from the items' equipment stats (RuneLite's, which are the OSRS Wiki's): **+25 or more** is Prayer, **+14 or less** is Strength (the gear for your style's damage), anything between is Mixed. The task's gear is the label of most kills; a tie is Mixed. An item RuneLite has no stats for adds nothing.

The gear cut-offs were checked against real setups, using each item's prayer bonus on the OSRS Wiki (9 November 2026):

| Setup | Prayer bonus | Gear |
|---|---|---|
| Torva full helm, platebody and platelegs, amulet of torture, infernal cape, primordial boots, ferocious gloves, ultor ring, avernic defender, Osmumten's fang | +7, +9 with Rada's blessing 4 | Strength |
| Neitiznot faceguard, Bandos chestplate and tassets, with the same amulet, cape, boots, gloves, ring and defender, abyssal whip | +9, +11 with Rada's blessing 4 | Strength |
| Helm of neitiznot, fighter torso, obsidian platelegs, amulet of fury, fire cape, dragon boots, barrows gloves, berserker ring (i), dragon defender, abyssal whip | +10, +12 with Rada's blessing 4 | Strength |
| Masori mask, body and chaps (f), necklace of anguish, Ava's assembler, zaryte vambraces, pegasian boots, venator ring, toxic blowpipe | +6 | Strength |
| Armadyl helmet, chestplate and chainskirt, with the same accessories, Armadyl crossbow | +7 | Strength |
| Crystal helm, body and legs, with the same accessories, bow of faerdhinen | +10, +12 with Rada's blessing 4 | Strength |
| Ancestral hat, robe top and robe bottom, occult necklace, imbued god cape, tormented bracelet, eternal boots, magus ring, kodai wand, Elidinis' ward (f) | +8 | Strength |
| Ahrim's hood, robetop and robeskirt, with the same accessories but a seers ring (i), Ahrim's staff, arcane spirit shield | +7 | Strength |
| Virtus mask, robe top and robe bottom, with the Ancestral set's accessories and Rada's blessing 4 | +14 | Strength |
| Bandos (above) with a Proselyte hauberk and cuisse for the chestplate and tassets | +21 | Mixed |
| Justiciar faceguard, chestguard and legguards, holy wraps, Saradomin cape, holy book, holy blessing, ring of the gods, holy sandals, salve amulet (ei), abyssal whip | +29 | Prayer |
| Proselyte sallet, hauberk and cuisse, with the same | +37, +32 with a defender for the book | Prayer |

The 2.0 plan's first guess was Strength at +10 or less. Damage sets reach +11 to +14 with Rada's blessing 4 (the Kourend & Kebos elite diary reward, +2), crystal armour or Virtus, so the Strength cut-off is +14. Prayer sets start at +29, so +25 stays. God capes have no prayer bonus.

The weapon rule was checked against these weapons' OSRS Wiki bonuses (9 November 2026): Melee: abyssal whip and tentacle, scythe of vitur, Ghrazi rapier, Osmumten's fang, blade of saeldor, dragon hunter lance, abyssal bludgeon, Armadyl godsword, dragon scimitar, arclight, emberlight, keris partisan, voidwaker, inquisitor's mace, soulreaper axe, noxious halberd, elder maul, dragon claws, dragon warhammer, Zamorakian hasta, dual macuahuitl. Ranged: toxic blowpipe (charged or not), twisted bow, bow of faerdhinen, magic shortbow (i), crystal bow, dark bow, venator bow, webweaver bow, rune, Armadyl, dragon hunter and zaryte crossbows, heavy ballista, dragon darts and knives, red and black chinchompas, eclipse atlatl. Magic: tridents of the seas and swamp, Sanguinesti staff, Tumeken's shadow, accursed, Thammaron's and warped sceptres, eye of ayak, kodai wand, nightmare staff, master wand. Melee by their bonuses, Magic by their casts: staff of the dead (and toxic), staff of light, Ahrim's staff, ancient staff, Iban's staff, slayer's staff. The cast animations are RuneLite's names for them: `ZAROS_VERTICAL_CASTING` (1979), with its walking and priority variants `_WALKMERGE` (10092) and `_PRIORITY` (7753), for Burst and Barrage; `ZAROS_CASTING` (1978) and `ZAROS_CASTING_WALKMERGE` (10091) for Rush and Blitz.

**The live task.** While a session is active, the live task card shows its tags in one row: **Cannon**, **Style** and **Gear**, each followed by the game's icon for its value, the same icons as the overlay's: the cannon barrels for Yes; Ice Barrage's spell icon for Barrage; the Ranged, Magic and Attack skill icons for Ranged, Magic and Melee; the Prayer and Strength skill icons for Prayer and Strength gear. A value with no icon is a short word instead: `No` for no cannon, `Mix` for Mixed and `?` for Unknown. A tag you set has a small orange mark in its icon's top right corner. Click a tag (or reach it with Tab and press Space) to choose its value from a list, and click it again to close the list: the first choice, for example `Detected: Melee`, shows the detected value and uses it; the others set the tag yourself, as **Setup…** does for a saved task. Hover over a tag for its value, whether it was detected or set by you (and whether it is an estimate), and what it came from, for example `Cannon kills: 30 of 52`, `Kills by style: Barrage 40, Magic 5` and `Prayer bonus: +6 to +8`. A choice made just as the task completes or is saved still reaches its saved task.

**Tasks saved by earlier versions** are given **estimated** tags, worked out from what they kept, the first time this version reads your saved tasks. Reading them changes nothing in your file: the estimates are written to it the next time it changes anyway (a task saved, edited or deleted, or a skip or block recorded). An estimate is shown with `~` in front, for example `Style: ~Barrage`, and the tooltip then ends with `~ estimated: saved before setup was recorded`.

- **Cannon**: Yes when the task fired any cannonballs, otherwise No (also for a task saved before supplies were tracked). It is an estimate, because the tag is meant to say whether most of the task's kills were cannon kills, which earlier versions didn't record.
- **Style**: from the XP the task gained in Attack, Strength, Defence, Ranged and Magic.
  - Attack and Strength XP count as melee, and Defence XP too when the task has some Attack or Strength XP. Without Attack or Strength XP, Defence XP counts as melee (a Defence stance) only when it is more than the Ranged and Magic XP together; otherwise it came from longrange or defensive casting and is left out.
  - Ranged XP is left out on a cannon task, because the cannon gives Ranged XP.
  - Magic XP counts as **Barrage** when the task's supplies include Ancient Magicks runes in their own proportions (between half and twice as many blood runes as death runes, for Barrage and Blitz, or about twice as many chaos runes as death runes, for Burst) or when no supplies were recorded; otherwise it counts as **Magic**. A trident's charges count as one death and one chaos rune per cast, so they don't look like Ancient Magicks, and a Sanguinesti staff's or Tumeken's shadow's runes with a few thrall death runes don't either.
  - The style with at least half of this XP is the task's style. A tie between two styles, or no style reaching half, is **Mixed**.
  - With none of this XP left, the style is **Unknown**: for example a cannon task done with ranged, or a task saved before XP was tracked.
- **Gear**: Unknown, because earlier versions kept no equipment.

| Task saved by an earlier version | Cannon | Style | Gear |
|---|---|---|---|
| Cannonballs fired; Ranged XP from the cannon, Attack and Strength XP | ~Yes | ~Melee | Unknown |
| Melee XP, no cannonballs | ~No | ~Melee | Unknown |
| Death and blood runes; almost all Magic XP | ~No | ~Barrage | Unknown |
| No supplies recorded; about 60% of the combat XP is Magic | ~No | ~Barrage | Unknown |
| Ranged XP, with Defence XP from longrange | ~No | ~Ranged | Unknown |
| Cannonballs fired; Ranged XP only | ~Yes | Unknown | Unknown |
| No XP recorded | ~No | Unknown | Unknown |

Unknown is not an estimate: it means there was nothing to tell the tag from, so it is shown without `~`.

**Changing a tag.** Right-click a saved task and choose **Setup…**. The dialog has one box per tag: its first choice, `Detected: ...`, shows the detected (or estimated) value and uses it; the others set the tag yourself. Press OK to save (Cancel changes nothing). Only that saved task changes. A value you set is never an estimate, so choosing the same value as an estimate confirms it, and the tooltip then shows both, for example `Style: Magic (set by you; detected ~Barrage)`.

Known limitations:

- The estimates are rough. Each style earns XP at its own rate (a spell gives a fixed amount per cast plus less XP per damage than melee or ranged), so a style's share of the XP is only roughly its share of the kills. Rune use can't tell Blitz from Barrage (both count as Barrage), and a task that mixed a trident, a powered staff or thralls with bursting may count either way.
- Detection hasn't been checked in game yet: the walking and priority variants of the Ancient Magicks cast animations, whether an attack's animation and XP read on the game tick it starts, whether all of a Barrage's or chinchompa's hits land on one tick, whether the cannon's hitsplats count as yours (either way they give no style), whether picking the cannon up or its decay can read as a shot for a moment, whether Araxxor's death is seen as your kill (its corpse stays to be harvested), and whether a block during a cast is left out.
- While you attack, the cannon's hits on the monster you attack count for your style (on the ticks your attack's hits could land), and so does one on another monster on the very tick a Burst's, Barrage's or chinchompa's hits land; a thrall's or a ring of recoil's hits may too, if they read as yours. A cannon task done in melee is Melee, not Unknown.
- A Barrage cast on a large monster from far to its north or east can land 7 ticks after the cast, after the window; such a hit has no style. The splash of a Barrage whose target splashed (no hitsplat on it) has no style either.
- Right after a weapon swap, a missed attack (which gains no XP, so it isn't recorded) can leave its 0 hitsplat to an earlier attack on the same monster within that attack's window, and a ranged attack can claim a hit a newer Burst or Barrage splashed onto its target. Each adds at most one hit of the other style. Weapons other than Bursts, Barrages and chinchompas that hit several monsters (a Venator bow's bounces, a scythe's sweep, multi-target specs) give their extra hits no style, so those monsters count as unstyled. A salamander reads as Ranged whichever of its styles you use. A spell not in the list above (a cast you start without a staff, of a spell this version doesn't know) counts as your weapon's style.
- A task that a 1.x version started and this version finished leaves its earlier kills, which recorded nothing, out of its cannon, style and gear: its tags come from the kills since the update, so a cannon task stays one (see [Cannon tasks](#cannon-tasks)). With no kill recorded since, its cannon falls back to the cannonballs. A kill's record is lost if you log out or hop on the very next game tick.
- Once this version has saved your tasks, earlier (1.x) versions of the plugin still show them but never change the file: a task they save is kept beside it until this version adds it (see [Sessions and saving](#sessions-and-saving)), but a skip or block they record is not saved at all. Don't switch back to 1.x on an account whose tasks this version has saved.

## Loot and supply values

While a session is active, the panel shows its value below the kills and time:

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

- left out of the **Loot received** icons: live, in a saved task's details and in **Overall totals**. The list's title counts what is left out, for example `Loot (3 hidden)` live and `Loot received (3 hidden)` in a saved task's details and in Overall totals, and its tooltip says why; a list whose items are all hidden says `All hidden` instead of `None`;
- left out of the **loot value**, and so of **profit**, everywhere loot is valued, **except coins**: the live values and the overlay's profit, the history rows and their tooltips, **Overall totals** (its profit/h and profit/kill too, also for [Catacombs variants](#catacombs-variants) and [cannon tasks](#cannon-tasks) By monster), the task advisor's profit goal, the [Advisor](#advisor)'s profit per hour, and the task-complete chat line. A hidden item other than coins gets no [notable drop line](#chat-lines) either. A hidden item whose price is unavailable no longer marks a value `~`.

**Coins always count in the loot value** (owner decision, 2026-10-28). If the list hides Coins, they are left out of the **Loot received** icons and counted in the title's `hidden` number like any other hidden item, but their value still counts in loot and profit everywhere. The tooltip says so: `3 items are hidden by your Ground Items hidden list; hidden items are left out of loot value, except coins.`

Nothing else changes: the items are still in the task's loot, the **Supplies used** lists are never filtered, and kills, keys and clue scrolls and notable drops still see them. Slayer Ledger only reads Ground Items' settings; it never changes them.

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

Two lists show what the task used and dropped item by item. Like RuneLite's Loot Tracker, each list is a grid of item icons, five to a row, with the quantity drawn on the icon. A list with nothing in it says `None`.

- **Live**: under the live task card, **Supplies used** and **Loot** are always open, each under its heading with its value on the right, for example `Loot` and `344K` (the loot at your **Loot price** setting, the supplies at GE; hover over the value for both prices and the profit). **Loot** leaves out the items [Ground Items hides](#ground-items-hidden-items) and counts them in its heading, for example `Loot (5 hidden)`. **XP gained** follows, with the XP per hour on the right.
- **A history task's details**: **Supplies used** and **Loot received** as the live lists show them, but under small white headings like Overall totals', quieter than the task's name above them, each with the task's value on the right, for example `Loot received` and `150K`; **Loot received** counts the hidden items in its heading, for example `Loot received (5 hidden)`.
- **Overall totals**: **Supplies used** and **Loot received** under small white headings, quieter than the group's orange name so you can see where each group ends, with `Show` or `Hide` on the right; they start closed (click a heading to show or hide the list). As live, only the hidden items are counted, for example `Loot received (5 hidden)`. By master's **Monsters assigned** has a small heading like them, with no count, because its lines count assignments, not monsters.

- Hover over an icon for the item's name, the exact quantity and its value, the item's GE price and high-alchemy value, and whether alching it pays (see below).
- Potions are counted in doses, pizzas, pies and cakes in bites, and teleport jewellery and the fungicide spray in charges, with the value per dose, bite or charge, as in the supplies total. Their icon shows the full item (for example a 4-dose potion) with the number of doses, bites or charges drawn on it; the tooltip says which it is, for example `Quantity: 22 doses`.
- **One icon per potion**. A potion is counted under the variant with the most doses seen so far, so a task that started with only 3-dose prayer potions and later had a 4-dose one counts its doses under both. The live and history **Supplies used** icons show such a potion once: its doses added up, its values added up (marked `~` when one of the variants has no price), and the icon of the variant with the most doses. Potions are matched by name without the dose, as the [Advisor](#advisor)'s Bring list matches them, so a potion whose name hasn't arrived yet shows on its own for a moment. Saved tasks are not changed, and Overall totals still lists each variant.
- Supplies are valued at GE prices and loot at the **Loot price** setting (an untradeable item at its high-alchemy value), using the same prices as the totals above, so the items add up to them. An item whose price is unavailable says `Value (GE): unavailable` in its tooltip.
- Icons are sorted by value, highest first, with unpriced items last. Every item is shown; there is no `+N more`.
- **Alch or sell?** A loot icon with a thin orange border is an item whose high-alchemy value, less the nature rune the cast uses (at today's GE price), is higher than its GE price; its tooltip explains it under `(alch)`. The GE tax is not taken into account. Untradeable items, which are already valued at high alch, are never marked, and nothing is marked while the nature rune's price is unavailable.

The icons come from RuneLite's item data and may appear a moment after the list opens. Item names are looked up once per item, so a tooltip may show `Item <id>` for a moment until its name arrives. A noted drop is shown separately from the unnoted item, as a bank note icon.

## Time left

While a session is active, the live task card's **Time left** shows an estimate of how long the rest of the task will take, for example `~42m`. It is shown in whole minutes, rounded to the nearest minute: `~42m` under an hour, `~1h 05m` from an hour on, and `~<1m` when less than half a minute is left. It is the session's average active time per kill so far multiplied by the kills left on the task, so it assumes you keep the same pace; it goes up if the task is extended and stands still while the session is stopped (Away, paused or logged out). A restored session uses its restored kills and time. Until the session has at least 3 kills and 1 minute of active time it shows the historical estimate instead when you have completed the task before (see [Historical task estimates](#historical-task-estimates)), else `?`; it shows `-` once no kills are left (the task is done or there is no task). The overlay shows the same estimate written as `~42 min` or `~1 h 5 min` (see [Task overlay](#task-overlay)). Hover over it for your kills per hour and average time per kill, in short form: `18s` under a minute, `4m 05s` under an hour and `1h 02m` from an hour on.

## Historical task estimates

Until a session has 3 kills and 1 minute of active time, its [Time left](#time-left), in the sidebar and the overlay, is your historical average time per kill on the task times the kills left, when you have completed the task before. Hover over it in the sidebar to see which one it shows, for example `Historical estimate from 3 completed tasks at Smoke Dungeon; assumes the same average time per kill.`

The average uses your completed sessions for that creature (older records without a creature id are matched by monster name; boss tasks are matched per boss; for a task with a [Catacombs variant](#catacombs-variants), only the version your kills in the active session show, both until then; only your [cannon tasks](#cannon-tasks) once that session's Cannon tag is Yes, when you have completed any of them, both until then or without any): their active time over their kills, pooled. Partial and other unfinished sessions, and skipped, blocked or untracked assignments, are left out. Outliers are not trimmed, so a very short or long task moves the average. One completed task is enough.

**Same location first.** When the task's current location (see [Task locations](#task-locations)) has completed history there, the average uses only those tasks, and the tooltip names the location. The current location is the spot where the active session has the most kills, else the spot the list gives the name of Konar's or Krystilia's assigned area, else the spot you are standing in, named as the live task card would: your name for its region, the list's name, or the assigned area's name. Tasks match by spot, as the history groups them, not by the name they show: a task you renamed with **Edit location** still counts at its spot, a task saved before 2.0 counts at its region's spot, and a task with no spot (an older one named by hand, saved without a region) counts only when all locations do. A session whose kills were all in a region two spots share that the list can't split has no spot, so the next place in that order is used. With no completed task at that spot, or while it is unknown (an assigned area the list has no spot of, or no place known yet), the average uses all your history of the task.

Before 2.0 a **History** box below the task showed the time, loot and supplies for the kills left from these tasks, with averages per kill in its tooltip. The [Advisor](#advisor) card replaced it: it shows, for each setup you have used on the task, the time for the kills left with its range, Slayer XP/h, profit/h and the supply cost.

## What to bring

What to bring is part of the [Advisor](#advisor) card since 2.0: each option's **Bring** lists the supplies for the kills left, from that setup's tasks, and the advisor's chat line names the two most valuable. See **Each option** under [Advisor](#advisor) for how the amounts are worked out (a safe amount per supply, the 80th percentile of its use per kill, rather than 1.x's average). The earlier **What to bring** section below the History estimate, with its bank-trip estimate and its **Panel sections** setting, was removed; the advisor suggests no bank trips.

## Task history

Below the current-task controls, the **Task history** section lists the logged-in account's saved tasks, newest first, two lines each. The first line shows the task's icon and name (its [Catacombs variant](#catacombs-variants), and `(cannon)` for a [cannon task](#cannon-tasks), as the **Monster** filter lists it) and, on the right, its active time in minutes, for example `52 min` or `1 h 1 min`. The second shows the date the task ended (local time), its location's short name (for example `Catacombs` for the Catacombs of Kourend; a name you gave it shows as you typed it) and an icon for each of its [setup tags](#setup-tags), and, on the right, its profit: the loot at the **Loot price** setting less the supplies at GE, always signed (`+212K`, `-38K`, `0`), green for a gain and yellow for a loss, with `~` when some prices weren't available. Only an exception has a word: `Partial` before the date of a task saved before it was finished; a completed task has none. A long name or location is cut short with `…`. Hover over a row for the full name, the date and time, the exact active time (for example `Active: 1:01:05`), the kills (as `kills / assigned amount` when the assigned amount is known), the XP gained with XP per hour of active time, for example `XP: 127K (120K/h)` (`XP: -` for tasks saved before XP was tracked), the exact GE and high-alchemy loot, supplies and profit, which prices are shown, the task's superiors, bank trips and deaths (`-` for tasks saved before they were counted), its master, type, Slayer points earned and task streak (when known), its location in full (`Unknown` when not recorded or named), its [setup tags](#setup-tags), its boss kill counts, keys, clue scrolls and notable drops (when there are any), and its note, if it has one. Tasks saved before values were tracked show `-` for the profit (never `0`); the tooltip shows the exact amounts in gp. Seven filters above the list narrow it down, and they combine: a task is shown only when it matches all of them.

**Setup tag icons**. Each of a task's setup tags as it stands (yours when you set it, else the detected or estimated value) has an icon after its location, in the overlay's order: the cannon barrels for a cannon task; Ice Barrage's spell icon for Barrage; the Attack, Ranged and Magic skill icons for Melee, Ranged and Magic; the Prayer and Strength skill icons for Prayer and Strength gear. These are the same icons as the overlay's and the live task card's. Mixed and Unknown have no icon. Hover over an icon for its tag, with `(estimated)` for a tag estimated on a task saved before 2.0 and `(set by you)` for one you set with **Setup…**, for example `Barrage (estimated)`.

**Monster icons**. Each row's monster shows the task's icon before its name, as in [Overall totals](#overall-totals): a Catacombs variant or cannon task shows its task's icon (`Deviant spectres` the Aberrant spectres icon, `Dust devils (cannon)` the Dust devils icon), and a name that is no known Slayer task an empty space, so the names stay lined up. The icons may appear a moment after the rows.

- **Monster** shows one monster's tasks. A task done on its [Catacombs variant](#catacombs-variants) is listed under the variant, for example `Deviant spectres`, and a [cannon task](#cannon-tasks) under its cannon name, for example `Dust devils (cannon)`.
- **Location** shows the tasks at one place. Tasks are grouped by their [spot](#task-locations), so a task you renamed on its own stays with the other tasks there, and renaming keeps the place selected and the rows you opened up; each entry shows the name of its newest task without a name of its own (your name for the region, or the list's name), so a name you give one task with **Edit location** doesn't rename the entry. Only when every task there has its own name (older tasks you named by hand) does the entry show the newest of those. A task with no spot but a name of its own (an older task you named by hand, or an older Konar task saved without a region) is grouped by that name, with names that differ only in case or spacing as one place. Tasks with neither are listed as `No location`, last; a place you named "No location" yourself is a separate entry, listed with the other names.
- **Master** shows the tasks one Slayer master assigned, for example `Nieve/Steve`. Tasks whose master isn't known (older tasks, or a master Slayer Ledger doesn't recognise) are listed as `Unknown master`.
- **Task type** shows one kind of task: `Regular`, `Konar`, `Wilderness` or `Boss` (see [Master, task type, Slayer points and streak](#master-task-type-slayer-points-and-streak)). A task whose type isn't known shows only under **Any**.
- **Cannon**, **Style** and **Gear** show the tasks with one value of a [setup tag](#setup-tags), as the tag stands (yours when you set it, else the detected value; an estimated value counts as that value): Cannon `Yes` or `No`; Style `Melee`, `Ranged`, `Barrage`, `Magic` or `Mixed`; Gear `Prayer`, `Strength` or `Mixed`. A task whose style or gear is Unknown shows only under **Any**. These three sit behind **More filters**: click it to show them (**Hide more filters** hides them again; while hidden with a value set it reads, for example, `More filters (1 set)`). It opens by itself when one of them is set, and stays as you left it until the plugin restarts.

Monster, Location and Master start at **All**, the others at **Any**. Monster, Location and Master list only the values in this account's saved tasks, alphabetically, with `No location` and `Unknown master` last; the others always list every value. A long name is cut short in the box, but the open list widens to show it (up to a limit); hover over the box to read the chosen name in full. When a value you picked is no longer in the history (you deleted its last task or renamed a task that has no spot), that filter goes back to **All**; logging in to another account sets every filter back, and so does a history with no tasks to show (logged out, loading, unreadable, or none saved). While any filter is set, the section's title counts them, for example `Task history · 2 filters`, and **Clear filters**, beside **More filters**, sets them all back. When the filters together match none of your tasks, the list says `No tasks match these filters.` with a **Clear filters** link under it; with no saved tasks at all it says `No saved tasks yet. Finished tasks show up here.`

The list shows the newest 25 matching tasks at first; click **Show 25 more** below them to add the next 25 (hover over it to see how many tasks match). Choosing another value in a filter, clearing the filters, or logging in to another account starts from the first 25 again; saving, editing or deleting a task keeps the tasks you have opened up.

**Every saved task is valued at today's prices** (owner decision, 2026-10-28), so tasks done months apart compare fairly: the history rows and their tooltips and item details, **Overall totals** and its item lists, the task advisor and the [Advisor](#advisor) with its chat line. In **GE** mode loot and supplies are at today's GE prices; in **High alch** mode loot is at its high-alchemy value, which never changes, and supplies are at today's GE prices. The task-complete chat line values the task just saved, so its prices are today's too. The prices are looked up once per item and kept until the plugin restarts; a task's tooltip says `Prices: today's GE prices (high alch values don't change)`. An item whose price RuneLite hasn't loaded is left out and the total marked `~`. It is looked up again every 3 seconds, up to 10 times, while you are logged in, so prices missing right after the client starts fill in once RuneLite has loaded them; after that, it is looked up again when your saved tasks change.

Whenever saved tasks are shown and some of their items' prices are on their way (after logging in, or when a task is saved), the history and the sections valued at today's prices keep what they show (`Loading saved tasks...` right after logging in) until the prices arrive, usually within a moment, and then redraw once, so the totals don't flash `~`. Until RuneLite has returned any price since the plugin started, prices that came back unavailable and are being looked up again (RuneLite may not have loaded its prices yet right after the client started) are waited for too; once it has, an item RuneLite has no price for is drawn at once, marked `~`. They never wait more than 3 seconds: after that they are drawn with the prices there are, the missing ones marked `~`, and redrawn when the rest arrive. **Show 25 more** clicks are ignored during the wait.

Saved tasks are never changed: each still keeps the prices it was saved with, but they are not shown. **Older tasks saved without per-item prices** are still valued from their items at today's GE prices, like any other task, so the row, its item details and Overall totals agree; only their high-alchemy values are their saved totals, since no high-alchemy value per item was kept, so in **High alch** mode their loot is marked `~`. **Tasks saved without items** (only totals) can't be revalued: they keep their saved totals, and a GE value that isn't 0 is marked `~` because it isn't at today's prices; the tooltip says `Prices: saved totals (no items kept), so GE values are marked ~`. Their high-alchemy total is exact. A task with no items and saved GE totals of 0 has nothing to revalue and shows 0 at today's prices.

Click a task (anywhere on its row, its tag icons too) to show its **Supplies used** and **Loot received** below it as item icons like the live lists, each under a small white heading with the task's value on the right (the supplies at GE, the loot at the **Loot price** setting; hover over the value for both prices and the profit), for example `Supplies used` with `9,000` and `Loot received (2 hidden)` with `150K`; click it again to hide them. They use the prices the task shows, today's GE prices with the saved high-alchemy values, so they match the task's totals; the first line says so. In **High alch** mode, older tasks saved without per-item high-alchemy values show their saved total marked `~`, and their loot items unpriced. Tasks saved before loot and supplies were tracked show `No item details`. Below the items, **XP gained** (with the task's XP per hour on the right) lists the task's XP skill by skill as text lines, each with the skill's small icon before its name; hover over a line for the exact XP and XP per hour. It shows the 10 highest skills; click `+N more` to show the rest, and `Show less` to go back. Tasks with no XP gained, or saved before XP was tracked, have no XP list.

The hint under the list reads: "Right-click a task to edit its note, location or setup, or delete it."

To add, change or remove a saved task's note, right-click its row and choose **Edit note**, then edit the text and press OK (leave it empty to remove the note; Cancel changes nothing). Only that saved record changes.

To change a saved task's location name, right-click its row and choose **Edit location**, edit the name and press OK (Cancel changes nothing, and so does OK on the name it already shows). Only that saved record's name changes; it keeps its spot, and the region's name for other tasks stays as it is. Leave it empty to remove the record's own name: it then shows its spot's name, or `Unknown`. A task in a map region the spot list doesn't have also has **Rename place (all its tasks)**, which names the region for every task there (see [Task locations](#task-locations)).

To set a saved task's cannon, style or gear tag, right-click its row and choose **Setup…** (see [Setup tags](#setup-tags)).

To delete a saved task, right-click its row and choose **Delete task**, then confirm. Deleting cannot be undone. It removes only that saved record; the session you are currently tracking, if any, is not affected. The history is hidden while you are logged out and always shows only the account that is logged in.

### Chat lines

Slayer Ledger posts a few lines in your chat, each as a console message named `Slayer Ledger`. Apart from the waiting line, each has its first words in your chat's highlight colour and the rest in its normal colour, so they follow your RuneLite chat colours, and its own setting in the **Chat** section of the plugin's settings, on by default. Names (a monster, an item, a place you named) are shown as typed and can't add formatting. The tone is light (2.0, owner decision): the facts first, and at most one short line of character at a rare moment.

**Task complete.** When a completed task is successfully saved: `Dust devils done: 201 kills in 1 h 1 min, +412K. Gz.` It names the task as the history does (its [Catacombs variant](#catacombs-variants) or Slayer boss, if it was done on one, and `(cannon)` for a [cannon task](#cannon-tasks)), then the kills, the active time in minutes (`52 min`, `1 h 1 min`; `<1 min` under half a minute), the profit at the **Loot price** setting less the supplies at GE, always signed (`+412K`, `-38K`, `0`, with `~` before the sign when some prices weren't available), and the superiors when at least one appeared (`3 superiors`). A profit that wasn't recorded is left out, and so is a superior count that wasn't recorded or is 0. It ends with `Gz.`, unless the record line below follows it, so only one of the two lines carries any character. Partial, discarded or unsuccessfully saved tasks get no line. Turn it off with **Task-complete line**. The **Task-complete notification** setting (off by default) also shows a RuneLite notification with the same text; it works whether or not the line is on. The earlier line, `Slayer task complete: 201 Dust devils · 1:01:05 · +412K`, had the same facts in another order.

**Record task**. When the task just saved is the fastest of its kind, a second line follows: `Fastest Dust devils task yet: 52 min, 4 min under your old best.`

- **Of its kind**: your other completed tasks with the same name as the history gives them, so the same monster, the same Catacombs variant or Slayer boss, and the same cannon use (`Dust devils` and `Dust devils (cannon)` are separate). Partial tasks never count, on either side, nor do tasks without kills or active time.
- **Fastest**: its active time per kill is lower than the best of those tasks. A tie is not a record. There must be at least 3 of those tasks to beat, so the first few tasks of a monster never post one.
- **The margin**: task sizes differ, so the line compares at this task's kills. `4 min under your old best` is how much less time this task took than your old best's time per kill would have taken for the same number of kills; a margin under half a minute reads `<1 min`. For example, with an old best of 100 kills in 30 minutes, a 200-kill task done in 52 minutes is 8 minutes under (100 kills at that pace would take 30 minutes, so 200 take 60).
- It is worked out from the saved tasks the sidebar holds for the logged-in account, so a task saved while another account's history is shown gets no record line. Turn it off with **Record task line**; the task-complete line then keeps its `Gz.`

**Notable drop**. When a kill's loot holds a [notable drop](#keys-clue-scrolls-and-notable-drops) that the session takes (while it runs, from a paired task kill while it is stopped, or in the short wait for the final kill's loot): `Dragon chainbody (148K) from a dust devil.` or `Abyssal whip (1.4M) at kill 140 of 201.`

- The item, `x25` when the stack is more than one, and its value at the **Loot price** setting, as the notable drops list values it.
- Which of the two endings is fixed by the task's kill number, so a given kill always gets the same line: on an even kill, `at kill 140 of 201` (the kills done of the assigned task, as the overlay's bar counts them); on an odd kill, or when the kill number isn't known (for example the final kill's loot), `from a dust devil`, the monster that dropped it in lower case with `a` or `an`. A Slayer boss goes by its own name: `from Cerberus`. If the monster's name isn't known either, the line is just the item and its value.
- `That's the task paid for.` is added only when it is true: the drop's value is at least the session's supply cost so far (at GE, as the live card values it, at most 2 game ticks old), and that cost is above 0 with every supply's price known. It is said once per task: only the first line it is true for gets it, and later drops of that task don't repeat it. (A plugin or client restart forgets that it was said, so the restored task may say it once more.)
- An item [Ground Items hides](#ground-items-hidden-items) gets no line, because it is left out of your loot value; coins are the exception, as they still count. The item is still listed with the task's notable drops. If the same kill dropped another notable stack that isn't hidden, the most valuable of those gets the line instead, and only its value can make the task paid for.
- A kill with more than one notable stack gets one line, for the most valuable. A pet gets no line (the game announces it), and nor does loot only buffered while no session runs, or [Araxxor's harvest after the task is saved](#loot-tracking). Turn it off with **Notable drop line**; the drops are still listed with the task.

**Advisor.** When a master gives you a new task, the plugin posts the [Advisor](#advisor)'s chat line for it once, for example `Dust devils x142: barrage, Catacombs, ~24 min; ~440 death, ~220 blood (3 tasks). Cannon, Smoke Dungeon: ~41 min (2).` (2.0 replaced the earlier preview of time, profit and bank trips; see the Advisor's **Chat line** for what it says). Only a task assigned while you are logged in counts as new: a task you already had when you logged in, hopped worlds, restarted the plugin or had a session restored gets no line. Turn it off with the **Advisor chat line** setting (on by default; it keeps the choice you made for the earlier **New-task chat preview**).

**Waiting for your next kill.** `Slayer Ledger: waiting for your next kill` is posted on the first tick after a login (or a client restart) while a session waits for your next kill (see [Task overlay](#task-overlay)); it is always on.

## Rates

The [Task advisor](#task-advisor) and [Overall totals](#overall-totals) compare monsters by the same rates: **Profit/h** and **Profit/kill** (labelled with your **Loot price** setting, for example `Profit (GE)/h`), **Slayer XP/h** and **Points/h** (the Task advisor uses only Slayer XP/h or Profit/h, as the **Advisor metric** says). They are worked out as the Trends section, removed in 2.0, worked them out:

- Only completed tasks you tracked count. Partial tasks, skipped and blocked assignments, and untracked completions are left out.
- A task done on its [Catacombs variant](#catacombs-variants) is its own monster, for example `Deviant spectres` apart from `Aberrant spectres`, and so is a [cannon task](#cannon-tasks), for example `Dust devils (cannon)` apart from `Dust devils`, unless a feature says it pools them (the advisor's main advice does).
- Hourly rates pool each metric's totals and divide by the active time of the same tasks. A rate appears from one task, outliers included, but only once the monster has at least 1 minute of active time for that metric.
- Profit/kill pools the same way over kills instead: the total profit of the monster's tasks that recorded profit, divided by the kills of those same tasks (not an average of each task's own figure). It needs no minimum active time, only tasks with at least 1 kill.
- Profit is loot at the **Loot price** setting minus supplies at GE, as in the history, at today's available GE prices (in High alch, for the GE-priced items, such as supplies). `~` means some prices were unavailable, or an older task kept its saved totals. Losses rank below 0.
- Slayer XP/h counts Slayer XP only, not combat or other skills.
- Points/h counts only tasks tracked from the full assigned amount. A task you started tracking part-way through is left out of Points/h, because its points also cover kills the ledger didn't see; it still counts for profit and XP. Points/h also leaves out milestone (bonus) tasks, every 10th task in a streak (10th, 50th, 100th and so on), because which monster lands on one is luck; their points still count everywhere else.
- A task that didn't record a metric (for example one saved before XP was tracked) is left out of that metric rather than counted as zero.

## Task advisor

While you have a task, the [advisor card](#advisor) can show advice on it, for example `Consider skip (n=3)`, from how the monster's rate compares with your other monsters' in your completed tasks. The rate (the advice's goal) follows the **Advisor metric** setting ([Advisor](#advisor)): **Slayer XP/h** under **Fastest finish** (the default), and **profit/h** under **Profit per hour** and **Cheapest**. *n* is how many completed tasks of this monster the rate comes from. It is advice only: the plugin never skips or blocks anything. Hover over it for the reason, for example `Profit (GE)/h 10K: 1st lowest of your 6 monsters (bottom 25%)`, and a short reminder of the rules below. **Consider skip** and **Consider block** are shown in yellow, under the advisor card's title. **Do** adds no line, to keep the card short. (Before 2.0's advisor card this advice was the live task card's last line.)

- The rates are the [Rates](#rates), worked out the same way, except that a task's [Catacombs variant](#catacombs-variants) tasks are counted with its standard ones, and its [cannon tasks](#cannon-tasks) with those without a cannon: completed tasks you tracked only, a rate from 1 minute of active time, profit at the **Loot price** setting and today's prices, and Slayer XP only. All of a monster's tasks count, wherever you did them. How often a master assigns the task is not used.
- **Enough data per monster:** a monster counts only once it has at least 3 completed tasks with the goal's rate and at least 30 minutes of active time on them. One with less gets no advice and is left out of the ranking.
- **Boss tasks are ranked separately:** a boss task is only compared with your other boss tasks (the reason says `of your 6 bosses`), and other tasks only with other tasks, so a boss never moves a regular monster's position. Jad and Zuk tasks count as regular tasks.
- **Position:** among the counted monsters of the same kind (boss or not), the task's monster is the *n*th lowest, where *n* counts the monsters whose rate is the same as or lower than its own, itself included. Tied monsters therefore share the higher position, and when all rates are equal every monster is **Do**. In the upper half the reason counts from the top instead, for example `5th highest of your 24 monsters` for the 20th lowest.
- **Consider block** when *n* is at most 10% of the monsters, **Consider skip** when it is at most 25%, otherwise **Do**. With 24 monsters, the 2 lowest are **Consider block** and the 3rd to 6th **Consider skip**. Below 10 monsters nothing is **Consider block**, and with 6 or 7 only the lowest is **Consider skip**. **Consider block** also needs at least 5 tasks of the monster; with 3 or 4 it is **Consider skip** instead, and the reason says `(bottom 10%, block from 5 tasks)`.
- No advice is shown until this monster counts and at least 6 monsters of the same kind count, this one included. Bosses and other tasks each need their own 6.
- **Standard or Catacombs variant, with or without a cannon.** 1.x also showed which version of a task had the better rate (`Better: Deviant spectres`) and whether it had been better with a cannon or without (`Better: without cannon`). 2.0's advisor card no longer shows these lines: its options (each spot, cannon choice and style) and its counterpart (the best option with the other cannon choice) answer the same questions for the kills left.

The **Task advisor** section of the plugin's configuration has **Task advisor** to turn this advice on or off (on by default) and **Advisor metric** (see [Advisor](#advisor)), which picks its rate as above. Changes apply at once. 2.0 removed the earlier **Task advisor goal** setting (Profit/h, Slayer XP/h or Points/h): Points/h is no longer an advice goal, and a goal you chose before is not carried over.

## Advisor

Slayer Ledger 2.0's advisor answers, from your own completed tasks, how to do a new task, how long its kills left take and what to bring. It posts its chat line when a task is assigned and shows its card in the sidebar (both below); the history's setup filters are being added in 2.0. This section is what their numbers mean.

**When it is worked out.** For the task you have, again whenever the task, its master or assigned area, its kills left (at most once a game tick), your saved tasks, today's prices, the **Loot price** setting, Ground Items' hidden items or the **Advisor metric** change. A new Skipped / blocked entry doesn't change it, so it isn't worked out again for one. It is worked out in the background, never on the sidebar's refresh, and while saved tasks wait for today's prices (see [Task history](#task-history)) it waits for them too. Until the new numbers are ready, the last ones stay if they are for the same task, task type (Regular, Konar, Wilderness or Boss), assigned area, **Advisor metric** and **Loot price** setting, so a kill doesn't blank them. The chat line never uses them: it waits for the numbers for the kills left now.

**Which tasks count.** Your completed tasks of the same creature, as for [Rates](#rates): completed and not partial, with kills and active time, any [variant](#catacombs-variants). Boss tasks are their own pool: a boss task's advice uses only your tasks of that boss, and an ordinary task's never uses boss tasks. A Wilderness task (Krystilia's) uses only your Wilderness tasks, because its kills count only in the Wilderness; a task whose master isn't known is left out of it.

**Options.** The tasks are grouped by their [spot](#task-locations), whether they were done on the task's [Slayer boss](#slayer-bosses) (most of their kills were the boss, for example the Grotesque Guardians on a gargoyle task), their **Cannon** tag and their **Style** tag ([Setup tags](#setup-tags); a value you set wins). A few boss kills on an ordinary task don't split it off; the option says how many of its tasks had any. An estimated tag counts as its value, so a `~Barrage` task is in the Barrage option, and the option is then marked estimated. A task whose spot or style is Unknown is in no option, but it counts in **All setups**, the line for all your tasks of the creature (its number of tasks and median time). Gear doesn't split options: each shows the gear of more than half of its tasks, for example `mostly Prayer gear`, or nothing when no gear has that many (Unknown gear is no note).

**Konar.** A Konar task's kills count only in its assigned area, so when the area has the name of a [spot](#task-locations) (its full or short name), only the options at that spot are shown, the recommendation and counterpart included. When none of your tasks were done there, every option is shown and the advisor notes it. An area that is no spot's name shows every option.

**Each option**, for the kills left:

- **n**: its number of tasks.
- **Time**: each task's time per kill (its active time divided by its kills); the median of those times the kills left, and a range from their 20th to their 80th percentile times the kills left. Percentiles are taken between neighbouring tasks: with one task all three are its time; with two, the median is halfway between them and the range runs from 20% to 80% of the way from the faster to the slower; with five, the median is the middle task's.
- **Slayer XP/h** and **profit/h**: as [Rates](#rates) describes, the totals over the active time of the tasks that recorded them, from 1 minute of it; profit is loot at the **Loot price** setting minus supplies, at today's prices.
- **Supplies**: the cost of the kills left: the tasks' supplies at today's GE prices per kill (over the tasks that have values), times the kills left.
- **Bring**: for each supply, each task's use per kill (0 for a task that didn't use it), the 80th percentile of those times the kills left, rounded up to whole items. That is a safe amount, more than a typical task needed, and an item only a few tasks used gets little or none. Potions are counted from doses in whole potions, pies and pizzas from bites in whole items, and teleport jewellery and Slayer bracelets from charges in whole items. A potion a task recorded under two items (its doses moved to the 4-dose potion when you first held one during the task) is one item: all its doses, in potions of the one with the most doses. The items are listed most valuable first at today's GE prices (items without a price last, then by amount); 8 show, then `+N more`.

**Tasks without supply data.** A task saved by an earlier version with no supplies recorded may have had its supply counting stop, as on the barrage tasks that version saved with none, so its supplies are unknown: it is left out of Bring, Supplies and profit/h, and still counts for n, the time and Slayer XP/h. A task this version saved with no supplies used none.

**Kills left unknown.** When the kills left are 0 or not known yet, the options and All setups are still worked out, but nothing is scaled to the kills left: each time is a pace (per kill), the supply cost is per kill, and there is nothing to bring.

**Recommendation.** The best option by the metric among those with at least 3 tasks. When no option has 3, the one with the most tasks (ties go to the better on the metric), labelled `based on 2 tasks`, so one lucky task doesn't win. With no completed task the advisor says `No completed Dust devils tasks yet, so no estimate.`

**Metrics.** The **Advisor metric** setting, in the plugin's **Task advisor** settings: **Fastest finish** (the default): the least time for the kills left. **Profit per hour**: the most profit/h. **Cheapest**: the least supply cost for the kills left. Ties go to the option with more tasks, then to the faster one. An option without the metric's number (no task with values) comes last. The metric also picks the [Task advisor](#task-advisor)'s rate: Slayer XP/h under Fastest finish, profit/h under the other two.

**Counterpart and more setups.** Beside the recommendation, the best option with the other cannon choice (with a cannon when the recommendation has none, and the other way round), chosen by the same 3-task rule, with its spot and number of tasks. **More setups** are the other options, in the metric's order.

**Chat line.** When a master gives you a new task, one line of at most 120 characters, posted once, with the task and kills left in your chat's highlight colour, for example `Dust devils x142: barrage, Catacombs, ~24 min; ~440 death, ~220 blood (3 tasks). Cannon, Smoke Dungeon: ~41 min (2).`: the task and its kills left; the recommendation's setup (`cannon` when it uses one, then its style, `~` before an estimated tag), its spot's short name and its time; its two most valuable items to bring (a rune's name without "rune"); its number of tasks; then the counterpart's cannon choice, its spot when it isn't the recommendation's, and its time with its number of tasks, for example `Aberrant spectres x150: ~cannon, ~melee, Stronghold Cave, ~32 min; ~433 cannonballs (1 task). No cannon: ~46 min (1).` Times are `~24 min`, or `~1 h 5 min` from an hour. The counterpart matters more than the items, so when the line doesn't fit it is the first of these that does: everything; the first item and the counterpart; the counterpart alone; the first item alone; neither. With no completed task it reads `Dust devils x142: no completed tasks yet.` A Wilderness task with completed tasks of the creature, but none of them from Krystilia, reads `Abyssal demons x40: no completed Wilderness tasks yet.` With tasks but none in an option (every spot or style Unknown) it reads `Dust devils x142: ~40 min (3 tasks, setup unknown).` With the kills left unknown there is no `x142` and each time is a pace: `Dust devils: barrage, Catacombs, ~0:12/kill (3 tasks).`

When it is posted: only a task assigned while you are logged in counts as new, so a task you already had when you logged in, hopped worlds, restarted the plugin or had a session restored gets no line. The line waits until what it shows is ready: your saved tasks (if they are still loading, it is posted once they have loaded), today's prices for them (at most the 3 seconds the history waits for them; after that it is posted at the prices there are), the advisor's numbers for the kills left now, and names: the names of Ground Items' hidden items, the name of a potion this task used that may join its two dose variants, the names of the two items it shows and your name for a spot's map region. It waits for names at most 5 seconds from the assignment. After that it is posted with the names there are: an item without its name shows as `item 560` and a region as `Region 12850`. It is still posted only once, and dropped if the task changes first. It is also dropped if you log out or switch accounts before it is posted, since the task is then one you had at a login. Turn it off with the **Advisor chat line** setting in the **Chat** settings (on by default; it keeps the choice you made for the earlier **New-task chat preview**, which it replaced).

**Advisor card.** In the sidebar, between the live task and the history, while you have a task and your saved tasks are loaded. From the top:

- **Title.** What it is based on: `Based on 6 Dust devils tasks` (all your completed tasks of the creature; `1 Dust devils task` for one), on up to two lines. Under it, the [Task advisor](#task-advisor)'s `Consider skip (n=6)` or `Consider block (n=6)` when there is such advice.
- **Metric tabs.** `Fastest`, `Profit/h` and `Cheapest`: the **Advisor metric** setting, the chosen one underlined. Clicking a tab changes the setting, exactly as in the plugin's configuration, and the card shows the numbers for it once they are worked out; until then it keeps the numbers it shows, with their tab underlined.
- **The note.** The recommendation, then under a line `With cannon` or `Without cannon` and the counterpart, in the same layout so they compare at a glance: the setup and spot (`Barrage, Catacombs`, on up to two lines) with the number of tasks beside it (`based on 1 task` when no setup has 3 tasks and the recommendation is the one with the most), and `estimated` when a task's cannon or style tag is an estimate (a task from an earlier version); the time for the kills left (`~1 h`) with its range (`56 min to 1 h 4 min`, left out when both ends are the same); **Slayer XP/h**; **Profit/h** (always signed; a loss also in red); **Supplies**, the cost of the kills left; **Gear** (`mostly Prayer`, or `-`); and **Bring**, up to 8 item icons with their amounts, then `+N more`. Hover an icon for its name, amount and GE value. With the kills left unknown, times are paces, Supplies is per kill and there is nothing to bring. Then `All setups (n tasks)` with their time, and `More setups (k)`, which opens a list of the other options, each with its time and number of tasks.
- **Notes.** A Konar task whose assigned area has none of your tasks says `No completed tasks in Slayer Tower yet, so all areas are used.` With no completed task the card is one line, `No completed Dust devils tasks yet, so no estimate.` (a Wilderness task with tasks from other masters only: `No completed Wilderness Dust devils tasks yet, so no estimate.`). With tasks but no known setup, it says `Setup unknown for these tasks.` and shows All setups.
- **Open and collapsed.** The card is open from the assignment to its first task kill, then collapses to one line, for example `Plan: barrage, ~1 h (3 tasks)`; hover over it for the spot and the counterpart. A task already started when you log in, or a session of it restored after a restart, shows it collapsed. Click the line to open the card, or the title to close it; your choice holds until the task changes (another creature, Konar area or size, or more kills left than before), and a new task opens the card again, even of the same creature. The task count reading 0 for a moment only hides the card. A title or setup name too long for two lines, or a longer name elsewhere, is cut short with `…`; hover over it for the whole name.

**Setup filters.** The history gets four more filters, each **Any** or one value: **Task type** (Regular, Konar, Wilderness or Boss, from the master and the task), **Cannon** (Yes or No), **Style** (Melee, Ranged, Barrage, Magic or Mixed) and **Gear** (Prayer, Strength or Mixed), on each task's tags with your own values first. A task whose value is unknown (no known master, an Unknown style or gear) shows only under **Any**, and an estimated value counts as its value.

## Task overlay

The **Slayer Ledger overlay** shows the assigned task in the game. It sits on RuneLite's standard overlay background, so your RuneLite **Overlay color** setting applies, and uses your RuneLite **Interface font**; while that is the default RuneScape font, the kills left and a critical supply warning are in RuneScape Bold. It starts at the top left, 160 pixels wide: move it with RuneLite's usual overlay drag (Alt-drag) and resize it as any overlay, and your size is kept. Text that doesn't fit is cut short with `…`; nothing wraps.

Its rows, top to bottom, each in one of four groups you can turn off (the state line can't be):

| Row | Example | Group | Shown |
|---|---|---|---|
| State | `Away` / `Back on your next kill` | none | whenever the clock isn't running |
| Identity | the task's icon beside `Dust devils`, then `Catacombs` and the setup sprites | Progress and identity | a task is assigned |
| Bar | `73 / 201` | Progress and identity | the task's size and kills left are known |
| Kills left, time left | `128 left` / `~38 min` | Progress and identity | a session is active |
| Pace, kills per hour | `0:18/kill` / `200/h` | Pace | from 3 kills and 1 minute of active time |
| Elapsed, superiors | `21:54 elapsed` / `1 superior` | Pace / Haul | a session is active |
| Profit, XP | `+212K` / `58.6K XP` | Haul | the session's values are worked out |
| Supply runway | the supply's icon and `Prayer: ~40 kills` | Supply runway | a supply you carry runs out before the task ends |
| Superior | `Superior spawned.` | Haul | for about 10 seconds after a superior spawns |

- **State line.** In yellow: `Away` / `Back on your next kill`; `Paused` / `Resume in sidebar`; `Waiting for next kill` after a login (before the plugin has read your task the overlay shows nothing, see **Hidden**). With a task assigned and no session: `Waiting for first kill`, or `Ended` / `See sidebar` after End task; the identity and the bar show under it. On a Jad or Zuk task, where going into the Fight Caves or the Inferno starts or restarts the session, the line says so: `Away` / `Back when you go in` after you leave, `Waiting` / `Go in to start` before the task's first kill, and `Waiting` / `Go in to resume` while it waits for the next kill (after a login, say). Every state line fits the overlay's default width whole.
- **Identity.** The task's name in the game's orange, as the sidebar's task card names it (the Catacombs variant or Slayer boss your kills show, `(cannon)`, `+ boss`). The second line has the spot's short name from the spot table (`Catacombs`, `Stronghold Cave`; until a kill names the spot, a Konar task's assigned area; for a place the table doesn't list, your name for it or `Region N`), then the setup tags as small sprites: the cannon barrels for a cannon task; Ice Barrage's spell icon for Barrage; the Ranged, Magic or Attack skill icon for the Ranged, Magic or Melee style; the Prayer or Strength skill icon for the gear focus. Mixed and Unknown have no sprite.
- **Bar.** The task's kills done of its assigned size. It keeps its fill while the session is stopped. With the kills left unknown (for example while a task whose count reached 0 waits for its completion message) there is no bar, and the line under it reads `73 killed` / `? left`, with the session's kills. With only the assigned size unknown there is no bar either.
- **Time left.** The same estimate as the sidebar's [Time left](#time-left) (the session's pace from 3 kills and 1 minute, before that your completed tasks' time per kill), written `~38 min` or `~1 h 5 min` (the live card uses the shorter `~9h 59m` for width); `?` until there is one.
- **Pace and elapsed.** As on the live card: the average active time per kill, kills per active hour, and the active time (`21:54`, from an hour `1:03:31`).
- **A narrow overlay.** No value is ever cut short, so no number loses a digit. When a line of two values doesn't fit, the right value's unit word goes first (`1,128 left` / `~5 h 12` for `~5 h 12 min`), then the left one's (`1,128` / `~5 h 12`, `0:18` / `200/h`, `1:03:31` / `2 superiors`), then the right value as a whole, and the left value keeps its word if it fits alone. The unit words are ` left` and ` killed` (kills), ` min` (time left), `/kill` (pace) and ` elapsed`; kills per hour, superiors, profit and XP keep theirs. Names, the spot, the state line's hint, the superior line and the runway are cut short with `…` instead.
- **Profit and XP.** Profit is the session's loot at the **Loot price** setting less its supplies, always signed (`+212K`, `-38K`), with `~` before the sign when some prices are unavailable; it is light green for a gain and yellow for a loss. XP is every skill's.
- **Runway or superior, never both.** The runway ([Supply runway](#supply-runway)) wins: a superior line never hides a warning. The superior line appears on the tick after the plugin counts a superior for the session (only while it runs, as the sidebar's count) and stays 17 ticks, about 10 seconds: `Superior spawned.` for the session's first, then by turns `Another superior (2nd).` and `Superior spawned (3rd).`.
- **Hidden** when there is no task, and as soon as the final kill completes the task (while the last loot is still being counted, and after the task is saved). After you log in it shows nothing until the plugin has read your task, 3 game ticks (about 2 seconds), so another account's task or state never shows; a world hop doesn't hide it.
- **Settings.** In the **Task overlay** section of the plugin's configuration: **Show task overlay** turns it on or off; **Progress and identity**, **Pace**, **Haul** and **Supply runway** turn the groups on or off. All are on by default. A line that pairs two groups (elapsed and superiors) shows only the one that is on, in its place. 1.x's **Show task row** carries over as **Progress and identity**, and **Show profit row** as **Haul**; **Show kills row** and **Show time left row** are gone (the kills are in the bar, and the time left is part of Progress and identity), and what you chose for them is no longer read.

**State line.** Tracking is automatic, so the old Not tracking reminder is gone: a task kill always starts or restarts the session, unless you pressed End task. The state line follows **Show task overlay** and can't be turned off on its own, so a stopped clock is never silent. The one chat line about it, `Slayer Ledger: waiting for your next kill`, is posted on the first tick after a login (or a client restart) while a session waits for your next kill; it is always on (see [Chat lines](#chat-lines)).

### Supply runway

The overlay's **Supply runway** row warns when a supply you carry will run out before the task ends, at the rate you use it. It is worked out once per game tick, after the supplies are read; the overlay only draws the result.

- **Which supplies.** Only these, each named by a short name beside the item's own icon:
  - **Prayer:** prayer potions;
  - **Restores:** super restores, blighted super restores and sanfew serums;
  - **Food:** anything counted as food in [Supplies tracking](#supplies-tracking), food eaten in parts in bites;
  - **Cannonballs:** regular and granite;
  - **Runes;**
  - **Ammo:** arrows, bolts and thrown weapons;
  - **Darts:** the darts a toxic blowpipe takes, in it or thrown;
  - **Scales:** a toxic blowpipe's Zulrah's scales.

  Other potions, teleports, jewellery charges and Slayer consumables don't count. Each item is a supply of its own (sharks and karambwans separately), except that every size of a potion counts together, in doses.
- **How much is carried.** What the supplies tracker saw on that tick: the inventory, the worn ammo, weapon and jewellery slots, Dizana's quiver, the rune pouch's runes and the cannonballs loaded in your cannon. Potions count in doses: two Prayer potion(4)s and a Prayer potion(3) are 11 doses. A worn toxic blowpipe adds its darts and scales as its last check message gave them (its Check option), less the darts and scales counted as used since, to any spare darts and loose scales you carry. The game's messages that it has run out of darts, of scales or of both (for example `Your blowpipe has run out of darts.`, `Your blowpipe needs to be charged with Zulrah's scales.` and `Your blowpipe has run out of scales and darts.`) set those to none. What it holds is kept from one task to the next and across End task. It is forgotten, and no darts or scales count until you check it again, since it could hold anything, when you load darts or scales into it (*Use* them on it), choose *Unload* or *Uncharge*, log out, or another account logs in; also before your first check. Its shots aren't counted while no task is assigned or after End task, so darts and scales used then aren't taken off, and it can count more than it holds until your next check. Nothing counts while no task is assigned or after End task, and only a worn blowpipe's known contents while the inventory hasn't loaded.
- **Use per kill.** From the session's 10th kill, this task's: what the session has used of the supply (as the sidebar counts it: potions in doses, ammo less what came back) divided by its kills. A supply it hasn't used then doesn't run out. Before the 10th kill, your history's: the same completed tasks the [Time left](#time-left) estimate uses (same task, version and cannon use, at the same spot first), their total use divided by their kills, leaving out tasks saved without supply data. With neither, nothing is shown. The history's figures are worked out when your history or the task changes, not on every tick.
- **Kills it lasts.** The carried amount divided by the use per kill, rounded down, but at least 1 while any of it is carried, even when that is less than one kill's use: `0 left` means you carry none.
- **Run out.** A supply you carried earlier in the session and carry none of now counts at none, so its warning stays, as the critical `Prayer: 0 left` with its icon, instead of going away. It clears when you carry the supply again or another of its kind (karambwans after your sharks run out, another dart type; not another rune, since a spell needs each of its runes), when the session ends, or when the use per kill no longer uses it: before the 10th kill, when your history's figures don't; from the 10th kill a supply the session has used stays used, so the warning stays until you carry it again or the task ends. Nothing counts as run out while the inventory hasn't loaded; nor do darts and scales while what your blowpipe holds isn't known, worn or not (before your first check, and after loading it, *Unload*, *Uncharge* or a logout, as above), since they may be in it, nor thrown darts then, which can't be told from a blowpipe's; nor while it isn't worn but is known to hold some (they count only while it is worn). The overlay keeps what you carried only while it is on.
- **Only what you use runs out.** A supply runs out only when it falls to none on a game tick whose supplies are counted (see the ticks and items left out in [Supplies tracking](#supplies-tracking)); one that leaves on a tick that isn't is forgotten instead, not run out. After a tick left out as a whole (the bank, a deposit box or another of those interfaces open, a rune pouch dropped, a death), the supplies you carried earlier are just those you carry after it: prayer potions you deposit to switch setups never show as run out, and neither does a supply that had run out before. An item you drop (or store in the master scroll book, or load into or empty from a blowpipe, trident or powered staff) is forgotten on its own, if you carried it on the tick before: dropping your last sharks is no run-out, while dropping the vial of your last prayer dose leaves prayer run out. While you charge or empty such a weapon every material it takes is left out for a moment, but one you no longer carry stays run out: death runes used up by barraging still show `Runes: 0 left` after you charge a trident.
- **What is shown.** Only the supply that lasts the fewest kills, and only when that is fewer than the kills left on the task; nothing while the kills left are unknown. A tie goes to the first in the list above, then the lower item id. Two levels, told by the words and the weight:
  - **Warning:** `Prayer: ~40 kills`, in yellow;
  - **Critical,** when it lasts 9 kills or fewer: `Prayer: 6 left`, in yellow Bold.

  If the line doesn't fit, ` kills` is dropped first, then it is cut short with `…`. The row goes away as soon as you carry enough, or your use per kill drops; a supply that ran out keeps its row (see **Run out**).
- **Limits.** The runes a trident, Sanguinesti staff or Tumeken's shadow cast is valued at count in the use per kill, as they do in the supplies, but runes held as the weapon's charges are not carried; carrying the same runes while using such a weapon can warn early.

## Panel sections

The **Panel sections** section of the plugin's configuration hides sidebar sections you don't use: **Overall totals** and **Skipped / blocked**. (Trends and the Luck meter, and their settings, were removed in 2.0; **What to bring** became part of the [advisor card](#advisor), and its setting went with it.) Both are shown by default, and a change applies at once. A hidden section leaves no gap and is not worked out at all; turned back on, it shows the current data straight away, open or closed as it was.

## Overall totals

The collapsed **Overall totals** section summarizes completed, non-partial played tasks. Click its header to show or hide it, and choose **Overall**, **By master**, **By month** or **By monster**. With no completed task it says `No completed tasks yet.`

Each group is a block: its name in orange with the number of tasks it covers on the right, for example `Konar   80 tasks` (hover over it to read `80 completed tasks`), then one line per figure, the figure's name on the left and its value on the right. Every block of a grouping has the same lines in the same order, so you can compare groups at a glance. The unit is in the line's name, for example `Profit (GE)/h`, not on the value. Amounts follow the panel's number rules: under 10,000 in full (`1,566`), then shortened to three figures (`24.8M`); a profit always has a sign (`+412K`, `-38K`) and is green for a gain and yellow for a loss. `~` means some item prices were unavailable, or an older task kept its saved totals. A total that only some of the group's tasks recorded (for example tasks saved before XP or points were tracked) says how many beside its name, for example `Points  78 tasks`; without that, it covers all the group's tasks.

In **Overall** and **By month**, each group shows its total active time, profit and XP:

```
Overall                  80 tasks
Active time              46:40:00
Profit (GE)               ~+24.8M
XP (all skills)  78 tasks   3.61M
```

Profit is labelled **Profit (GE)** or **Profit (HA)** with the selected **Loot price** mode and uses today's prices, as the history does. **XP (all skills)** is the XP of every skill gained on those tasks, not only Slayer XP. Hover over a group for the exact profit and XP, the price source and how many tasks each total comes from. Months use the local completion month, newest first; records without a known completion date or Slayer master appear in unknown groups listed last. A task without profit or XP data is left out of that total rather than counted as zero. Skipped, blocked, partial and untracked assignments are excluded.

**By monster** has one group per monster, named as the history names it: a [Catacombs variant](#catacombs-variants) and a [cannon task](#cannon-tasks) are groups of their own, for example `Deviant spectres` and `Dust devils (cannon)`. Groups with the most tasks come first, then by name. Each group shows:

```
Dust devils              12 tasks
Kills                       1,234
Avg task                  0:42:10
Avg profit (GE)             ~+450K
Profit (GE)/kill           ~+4,380
Pace                     0:24/kill
Profit (GE)/h               ~+640K
Slayer XP                    123K
Slayer XP/h                 14.6K
```

**By master** shows, for each master:

```
Duradel/Kuradal          80 tasks
Avg task                  0:35:00
Avg profit (GE)             ~+310K
Profit (GE)                ~+24.8M
Profit (GE)/h               ~+531K
Points  78 tasks            1,566
Avg points                     18
Slayer XP                   1.04M
Avg Slayer XP                 13K
```

and below those lines a **Monsters assigned** list: each monster the master gave you, with its count out of all the master's assignments, for example `Dust devils   12 / 80 (15%)`, most assigned first (ties by name). The list shows the top 10; click `+N more` to show the rest and `Show less` to go back. Hover over a line for the exact share and how those assignments ended, for example `Completed: 8`, `Skipped: 3`, `Blocked: 1`.

In both, the number of tasks next to the group's name counts completed tasks; By master's assignments are counted separately, in **Monsters assigned**.

**Monster icons**. Each **By monster** group's name and each **Monsters assigned** line shows the task's icon before the monster's name: the same picture RuneLite's Slayer plugin shows for the task (mostly the Slayer guide's pictures; boss tasks show their pet), drawn small so the sidebar doesn't widen. A Catacombs variant or cannon task shows its task's icon, for example `Deviant spectres` and `Dust devils (cannon)` show the Aberrant spectres and Dust devils icons. A name that is no known Slayer task (for example `Unknown monster`, or a task newer than the plugin's list) has an empty space instead, so the names stay lined up.

**Master faces**. Each **By master** group's name shows the master's face before it: the master's chathead picture from the Old School RuneScape Wiki, bundled with the plugin and drawn at the same small size. A name two masters share shows the first one's face: `Turael/Aya` shows Turael, `Duradel/Kuradal` Duradel and `Nieve/Steve` Nieve; Konar shows Konar quo Maten. A master Slayer Ledger doesn't recognise (`Unknown master`) has an empty space, and so does every master if a build leaves the pictures out.

How these lines are worked out:

- **Which tasks.** The same as the other groupings: completed, non-partial played tasks, valued in the same way (the **Loot price** setting, today's prices, and loot [Ground Items hides](#ground-items-hidden-items) left out of the value, except coins). `~` means some item prices were unavailable, or an older task kept its saved totals.
- **Averages** are per task: the group's active time over its tasks; profit, Slayer XP and points over the tasks that recorded them. A task without a figure (for example one saved before XP or points were tracked) is left out of that figure's total and average rather than counted as 0; when that leaves out any of the group's tasks, the total's line says how many tasks it comes from, for example `Points  78 tasks`. The averages use those tasks; the rates use those of them with active time (see **Rates**).
- **Rates** follow the [Rates](#rates) rules. **Profit/kill** is pooled: the total profit of the tasks that recorded profit, divided by the kills of those same tasks. **Profit/h** and **Slayer XP/h** divide the total by the active time of the same tasks, and show `-` until they have at least 1 minute of it. **Pace** is the group's active time over its kills, as the live task card shows its pace: minutes and seconds per kill, `0:18/kill`. Tasks without any active time are left out of all four, as in the [Rates](#rates).
- **Slayer XP** is Slayer XP only, as in the [Rates](#rates), not the XP of all skills.
- **Slayer points.** **Points** counts every completed task that recorded its points, bonus (milestone) tasks included. **Avg points** leaves out the bonus tasks (a streak of 10, 20, 30 and so on), as Points/h does in the [Rates](#rates), so a few large bonus payouts don't swamp the usual points per task; the tooltip says how many tasks it averages over.
- **Monsters assigned** counts the master's completed tasks (the same tasks the group counts, so a completed task with no kills is not counted in either; each under the task's assigned name, so a Catacombs variant or cannon task counts as its task) plus every [Skipped / blocked](#skipped-and-blocked-tasks) entry of that master, whatever its outcome (skipped, blocked, reset, completed untracked or ended). The percentages are rounded to whole numbers (half up), so they may not add up to 100%; a share under 0.5% shows as `<1%`. Limits: an assignment you played only part of, saved as a partial task, and then skipped or reset counts nowhere, because it has no completed task and no entry; only the newest 500 entries are kept; a master with entries but no completed task has no group; and, rarely, one assignment counts twice. When the plugin didn't see an assignment start, it guesses from the newest saved task alone whether you played it; if that guess misses, the assignment can get both a completed task and a Skipped / blocked entry.

Below its lines, each group has two item lists, **Supplies used** and **Loot received**, with the items of all the group's tasks added up. They look and work like the live task's [item lists](#item-lists): a grid of item icons with the quantity drawn on them, the same tooltips and the orange alch border, under a small white heading, quieter than the group's name. Both start closed, with `Show` on the right of the heading; click a list's heading to show or hide it. An open list stays open while the totals update, and closes again when you close the section or choose another grouping.

- The items come from the same tasks as the totals: completed, non-partial played tasks. Tasks saved before loot and supplies were tracked add nothing; when none of a group's tasks has item data, both lists say `None`. **Loot received** leaves out the items [Ground Items hides](#ground-items-hidden-items), as the profit does.
- Each item is valued as the group's profit counts it: loot at the **Loot price** setting and supplies at GE prices, at today's GE prices, so an icon's value is the sum of its tasks' values. The GE price and high-alchemy value in the tooltip are those of the newest of its tasks that had them. If only some of those tasks had a price for an item, its value counts the priced ones and shows `~`.
- Potions add up in doses, pizzas, pies and cakes in bites, and teleport jewellery and the fungicide spray in charges. A noted drop stays separate from the unnoted item.

## Skipped and blocked tasks

Below the task history, the collapsed **Skipped / blocked** section lists the Slayer assignments that ended without a saved session, so the history covers every assignment, not only the ones you played. Click its header to show or hide it. Each entry is two short lines: the task's icon and the monster, with how it ended on the right, then the date and the master, each when known:

```
Abyssal demons                 Skipped
10/26/26 · Duradel/Kuradal
```

The icon is the task's (as in [Overall totals](#overall-totals)); a name that is no known Slayer task has an empty space. A name too long for the sidebar is cut short with `…`. Hover over an entry for the whole name, the master, task type, assigned amount and the change in Slayer points. Right-click an entry and choose **Delete entry** to remove it. With no entries, the section says `No skipped or blocked tasks yet.`

The plugin watches every assignment, whether or not a session is running. It only reads game values and never acts. When an assignment ends without a session, it records one of these:

- **Skipped:** your Slayer points went down (cancel costs vary by master, so any drop counts and the real change is shown).
- **Blocked:** points went down and the game's blocked-task list changed at the same moment.
- **Reset:** your task streak went down with no points spent, for example a Turael or Spria reset.
- **Completed (untracked):** the task was completed while no session was tracking it.
- **Ended:** the signals fit none of the above, so the plugin doesn't guess. For example, the task was stored.

These entries never count as played tasks. They are left out of the saved-task count, the Task history filters, the time-left estimate, per-monster averages and the [Rates](#rates). They only count as assignments, in [Overall totals](#overall-totals)' By master **Monsters assigned** list. A task that had any session, whether saved as completed or partial, gets no entry. The newest 500 entries are kept. They are stored in the same ledger file, and older ledger files load unchanged.

Known limitations:

- The blocked-task values are not decoded: the plugin can't say which slot holds which task, only that one changed when the task ended. A block made from the rewards list while you keep your current task is not recorded.
- Entries are only made while you are logged in and the plugin is running. An assignment that changed while you were logged out (or on another client) gets no entry, and neither does one skipped in the last few seconds before you log out or hop.
- After a client restart, the plugin can't tell whether the task you already had was played in an earlier run. It skips the entry when your newest saved task is a partial session of the same task. A session you discarded isn't remembered across a restart, so skipping that task afterwards is logged.
- If your streak was already 0, a Turael reset changes nothing the plugin can see, so it is recorded as **Ended**. These rules have not been checked in game yet.
