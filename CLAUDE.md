# DiamondEcon

A small Spigot economy plugin for a private Minecraft server. Diamonds are the currency.
Features: a bank, `/pay`, transaction stats/history, and decorated chest shops.

A Next.js dashboard (stock lookup, balance graphs) comes later and is **not** in this repo yet.
It will read the plugin's database directly, so treat the schema as a public interface.

## Stack

- **Spigot 26.3** (`org.spigotmc:spigot-api:26.3-R0.1-SNAPSHOT`). Spigot, not Paper: use only the Spigot API.
- **Kotlin 2.4.21** on **Java 25**, built with Gradle 9.8.2 (Kotlin DSL)
- Java and Gradle are pinned in `mise.toml`. Use `mise exec -- ./gradlew build` (this also runs the tests),
  or `assemble` to get just the jar. Output: `build/libs/diamond-econ-<version>.jar`.
- **Tests** (`src/test/kotlin`, JUnit 6 + Testcontainers 2): `BankTest` and `MigrationsTest` run against a real
  `postgres:16` container, with a fresh database per test (`TestPostgres`), so Docker must be running.
  The Claude Code sandbox can't reach the Docker socket, so ask the user to run `./gradlew test`.
- Runtime dependencies (Kotlin stdlib, Postgres JDBC driver, Exposed core/jdbc/java-time, HikariCP) are **not shaded**. They're listed under
  `libraries:` in `src/main/resources/plugin.yml`, and Spigot downloads them from Maven Central at startup.
  Their versions live once in `build.gradle.kts` and are expanded into `plugin.yml` by `processResources`.
  Add new runtime dependencies the same way: `compileOnly` in Gradle + an entry in `libraries:`.
- `docker-compose.yml` runs a local `postgres:16` (database/user/password `diamondecon`, bound to localhost:5432).
  The Claude session may not have Docker socket permissions. If so, ask the user to run compose commands.

## Layout (`src/main/kotlin/dev/diamondecon/`)

| File | Role |
|---|---|
| `DiamondEcon.kt` | Plugin entry point. Wires everything together and registers commands and listeners |
| `db/Storage.kt` | HikariCP pool to Postgres (settings in `config.yml`), Exposed `Database`, runs migrations on startup |
| `db/DbWorker.kt` | Single database thread: `submit(work) { result -> }` runs `work` off-thread, the callback on the main thread |
| `db/Migrations.kt` | Versioned migration runner (`schema_version` table) over `src/main/resources/db/migrations/*.sql` |
| `db/Tables.kt` | Exposed DSL tables `Accounts`, `Transactions`, `Shops` (the dashboard's schema) |
| `bank/Bank.kt` | Accounts, atomic debit/credit/transfer, ledger writes, history/stats queries |
| `shop/ShopManager.kt` | In-memory shop cache, shop persistence, floating display entities (spinning item + label) |
| `shop/ShopListener.kt` | Buying (right-click), info (left-click), protection (break/explode/hopper/double-chest) |
| `shop/Shop.kt`, `shop/BlockKey.kt` | Shop model and world+xyz key |
| `command/EconomyCommands.kt` | `/balance /deposit /withdraw /pay /history /stats /baltop`, account creation on join |
| `command/ShopCommand.kt` | `/shop create|remove|price|name|icon|list|help`, admin `/shop setowner|reload` |
| `util/Items.kt`, `util/Fmt.kt` | Inventory helpers (`countItems`, `roomFor`, `takeItems`, `giveItems`) and chat formatting |

## Design rules (keep these)

- **The bank is backed by real diamonds.** Balances only come from `/deposit` (diamond = 1, diamond block = 9)
  and leave through `/withdraw`. Nothing creates money from nothing. Amounts are whole diamonds (`Long`).
- **Every balance change goes through `Bank`.** It runs as one SQL transaction (conditional debit
  `WHERE balance >= amount`, credit, ledger insert) and writes a row to `transactions`.
  Never change `accounts.balance` anywhere else.
- **Database work never runs on the main thread** (except the migrations and `ShopManager.load` at startup).
  It goes through `DbWorker`: one thread, jobs in submission order, so writes never overtake each other and
  a balance read in the same job just before a write is exact. Callbacks run on the main thread, and only there
  may code touch the world, inventories or the shop cache.
- **Keep items and money in sync** while the money step runs on the database thread:
  - Items the player gives up are taken *before* the money moves and given back if it fails (`/deposit`).
    Shop stock is *reserved* (taken out of the chest) before payment and put back if it fails or is declined.
  - Items the player receives are handed out *after* the money committed (`/withdraw`, purchases). If the player
    logged out in between, their saved inventory can't be changed, so the payment is undone with a `REFUND`
    ledger entry (and shop items go back to the chest).
  - Nothing in a job may fail after its money write commits, or the callback's failure path would undo items
    for money that did move.
  - `DbWorker.stop()` (onDisable) finishes queued jobs and runs their callbacks before the pool closes.
- **Shops** are single chests, trapped chests or barrels (copper chests are excluded, since copper golems
  move items). They are sell-only: a shop sells `quantity` items for `price` diamonds. Items are matched
  with `ItemStack.isSimilar`. The shop's item is stored as YAML-serialized `ItemStack`, plus `item_type`
  (Material name) for the dashboard. An optional owner-chosen `display_name` (with § colors) replaces the item's
  name in labels and messages, e.g. for a shulker box full of dirt. Purchases record the name the buyer saw in
  `transactions.item_name` (null on older rows). An optional `icon` (YAML `ItemStack`) replaces the floating item.
  It's cosmetic only: matching and purchases always use `item`.
- **Decorations** (`ItemDisplay` + `TextDisplay`) are **non-persistent**. They spawn on chunk load,
  are removed on chunk unload and plugin disable, and are never saved to the world.
- `shops.stock` is kept up to date (on purchase, owner closing the chest, chunk load) so the dashboard can
  look up stock without the server running.
- **Schema changes go through migrations.** The plugin owns the schema, and the dashboard only reads it.
  If the dashboard uses Prisma or Drizzle, generate its models from the database (`prisma db pull`) and don't
  use their migration tools. To change the schema:
  1. Add `NNN_description.sql` to `src/main/resources/db/migrations/`, list it in `Migrations.SCRIPTS`, and update
     `Tables.kt` to match, keeping Exposed's index and constraint names.
  2. Never edit a migration that has shipped. Migrations only go forward, so take a backup with `pg_dump` before
     deploying one.
  3. On startup, the plugin logs a warning if the database and `Tables.kt` disagree
     (`SchemaUtils.statementsRequiredToActualizeScheme`). That warning usually means a migration is missing.
- There is no Vault integration, on purpose: the economy is self-contained.
- Messages use `ChatColor` legacy strings. `◆` is the diamond symbol, and the tone is cute (✿, ♥).

## Current status

- Committed: initial Java version → Kotlin rewrite → Postgres compose + driver.
- Storage is **Postgres via Exposed 1.6.0 (DSL) + HikariCP 7.1.0**. SQLite is gone. Native types: `uuid`, `bigint`,
  `timestamptz` (`created_at`, defaulted by the DB), `type` as the enum name. Foreign keys go from `transactions.from_uuid/to_uuid`
  and `shops.owner_uuid` to `accounts.uuid`. `transactions.shop_id` has no FK, so the ledger survives shop removal.
- Exposed loads its `ServiceLoader` services with its own classloader (not the context classloader), so Spigot's
  library loader should be fine. The Postgres driver class is set explicitly on Hikari.
- **First boot on a real Spigot 26.3 server (2026-10-30) worked:** `api-version: '26.3'` is accepted, and Spigot
  downloads the `libraries:` entries with their transitive deps. Hikari connects and the shops load.
  Purchases haven't been tested in-game yet.
- Local test server: `~/mc`. To install while it's running, put the jar in `~/mc/plugins/update/`
  (Bukkit's update folder). It replaces the plugin jar on the next restart.
- Hikari `connectionTimeout` is 5s and Exposed retries are off (`defaultMaxAttempts = 1`). During an outage,
  each queued job fails after ~5s and the player gets an error; the server itself doesn't stall.
- Known gap: `shops.owner_name` isn't updated when a shop owner renames their Minecraft account.

## Next step

Test the threaded purchase, deposit and withdraw flows in-game (including logging out mid-purchase).
