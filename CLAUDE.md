# DiamondEcon

A small Spigot economy plugin for a private Minecraft server. Diamonds are the currency.
Features: a bank, `/pay`, transaction stats/history, and decorated chest shops.

A Next.js dashboard (stock lookup, balance graphs) comes later and is **not** in this repo yet.
It will read the plugin's database directly, so treat the schema as a public interface.

## Stack

- **Spigot 26.3** (`org.spigotmc:spigot-api:26.3-R0.1-SNAPSHOT`). Spigot, not Paper: use only the Spigot API.
- **Kotlin 2.4.21** on **Java 25**, built with Gradle 9.8.2 (Kotlin DSL)
- Java and Gradle are pinned in `mise.toml`. Use `mise exec -- ./gradlew build`.
  Output: `build/libs/diamond-econ-<version>.jar`.
- Runtime dependencies (the Kotlin stdlib, the Postgres JDBC driver) are **not shaded**. They're listed under
  `libraries:` in `src/main/resources/plugin.yml`, and Spigot downloads them from Maven Central at startup.
  Their versions live once in `build.gradle.kts` and are expanded into `plugin.yml` by `processResources`.
  Add new runtime dependencies the same way: `compileOnly` in Gradle + an entry in `libraries:`.
- `docker-compose.yml` runs a local `postgres:16` (database/user/password `diamondecon`, bound to localhost:5432).
  The Claude session may not have Docker socket permissions. If so, ask the user to run compose commands.

## Layout (`src/main/kotlin/dev/diamondecon/`)

| File | Role |
|---|---|
| `DiamondEcon.kt` | Plugin entry point. Wires everything together and registers commands and listeners |
| `Database.kt` | SQLite connection and schema creation, plus JDBC helpers (`update`, `query`, `queryOne`, `insert`) |
| `bank/Bank.kt` | Accounts, atomic debit/credit/transfer, ledger writes, history/stats queries |
| `shop/ShopManager.kt` | In-memory shop cache, shop persistence, floating display entities (spinning item + label) |
| `shop/ShopListener.kt` | Buying (right-click), info (left-click), protection (break/explode/hopper/double-chest) |
| `shop/Shop.kt`, `shop/BlockKey.kt` | Shop model and world+xyz key |
| `command/EconomyCommands.kt` | `/balance /deposit /withdraw /pay /history /stats /baltop`, account creation on join |
| `command/ShopCommand.kt` | `/shop create|remove|price|list|help` |
| `util/Items.kt`, `util/Fmt.kt` | Inventory helpers (`countItems`, `roomFor`, `takeItems`, `giveItems`) and chat formatting |

## Design rules (keep these)

- **The bank is backed by real diamonds.** Balances only come from `/deposit` (diamond = 1, diamond block = 9)
  and leave through `/withdraw`. Nothing creates money from nothing. Amounts are whole diamonds (`Long`).
- **Every balance change goes through `Bank`.** It runs as one SQL transaction (conditional debit
  `WHERE balance >= amount`, credit, ledger insert) and writes a row to `transactions`.
  Never change `accounts.balance` anywhere else.
- **Keep items and money in sync.** Validate items first (stock, inventory space), then commit the money,
  then move items in the same server tick. Item moves can't fail halfway on the main thread, so nothing
  can be duplicated or lost. Any change to threading must keep this guarantee.
- **Shops** are single chests, trapped chests or barrels (copper chests are excluded, since copper golems
  move items). They are sell-only: a shop sells `quantity` items for `price` diamonds. Items are matched
  with `ItemStack.isSimilar`. The shop's item is stored as YAML-serialized `ItemStack`, plus `item_type`
  (Material name) for the dashboard.
- **Decorations** (`ItemDisplay` + `TextDisplay`) are **non-persistent**. They spawn on chunk load,
  are removed on chunk unload and plugin disable, and are never saved to the world.
- `shops.stock` is kept up to date (on purchase, owner closing the chest, chunk load) so the dashboard can
  look up stock without the server running.
- There is no Vault integration, on purpose: the economy is self-contained.
- Messages use `ChatColor` legacy strings. `◆` is the diamond symbol, and the tone is cute (✿, ♥).

## Current status

- Committed: initial Java version → Kotlin rewrite → Postgres compose + driver.
- **Never run on a real server yet.** Unverified assumptions to check on the first boot:
  - The SQLite JDBC driver is assumed to be bundled with the Spigot server jar.
  - `api-version: '26.3'` is assumed to be accepted by the server.
  - Spigot is assumed to download the `libraries:` entries.
- Storage is still **SQLite** (`plugins/DiamondEcon/economy.db`, WAL mode). The Postgres driver is on the
  classpath but unused.
- All database calls currently run synchronously on the main server thread. That's fine for SQLite, but
  not for Postgres.

## Next step (agreed direction, scope not yet confirmed)

Move storage to **Postgres via JetBrains Exposed 1.6.0, DSL style** (not DAO), with **HikariCP 7.1.0**:

1. Exposed `Table` objects (`Accounts`, `Transactions`, `Shops`) using native Postgres types
   (`uuid`, `bigint`, `timestamptz` via `exposed-java-time`), created with `SchemaUtils.create`.
   Add a `config.yml` for connection settings.
2. Port `Bank` and `ShopManager` to the Exposed DSL, keeping the conditional-debit transfer.
3. Move database work off the main thread (single executor), then hop back with the Bukkit scheduler
   to touch the world or inventories. Shop purchases need care so items can't be duplicated:
   reserve/validate, then pay, then move items with a re-check.
4. Remove SQLite and `Database.kt`'s raw JDBC helpers.

Open question for the user: all four steps at once, or Exposed first and the threading separately.
Risks: Exposed discovers dialects through `ServiceLoader`, and drivers may need `Class.forName(...)`
when loaded through Spigot's library classloader. Verify both on a real server.

The SQLite → Postgres switch happens before launch, so no data migration is planned.
