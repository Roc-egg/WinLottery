package roc.win.lottery.persistence

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

/** 模式 2 新增的本机历史开奖集合表，语句与导出的模式快照逐字一致。 */
internal const val CREATE_HISTORICAL_DRAW_SETS_SQL =
    "CREATE TABLE IF NOT EXISTS `historical_draw_sets` (`lottery_type` TEXT NOT NULL, " +
        "`source_name` TEXT NOT NULL, `fetched_at_epoch_millis` INTEGER NOT NULL, " +
        "`draw_count` INTEGER NOT NULL, `newest_issue` TEXT NOT NULL, `data_version` INTEGER NOT NULL, " +
        "PRIMARY KEY(`lottery_type`))"

/** 模式 2 新增的本机历史开奖号码表，语句与导出的模式快照逐字一致。 */
internal const val CREATE_HISTORICAL_DRAWS_SQL =
    "CREATE TABLE IF NOT EXISTS `historical_draws` (`lottery_type` TEXT NOT NULL, `issue` TEXT NOT NULL, " +
        "`draw_date` TEXT NOT NULL, `primary_numbers` TEXT NOT NULL, `secondary_numbers` TEXT NOT NULL, " +
        "PRIMARY KEY(`lottery_type`, `issue`))"

/** 模式 1 升级到 2：只新增两张独立表，不改动任何票据表。 */
internal val MIGRATION_1_2: Migration =
    Migration(1, 2) { connection ->
        connection.execSQL(CREATE_HISTORICAL_DRAW_SETS_SQL)
        connection.execSQL(CREATE_HISTORICAL_DRAWS_SQL)
    }

/** 模式 3 新增的已开奖查询结果表，语句与导出的模式快照逐字一致。 */
internal const val CREATE_DRAW_RESULTS_SQL =
    "CREATE TABLE IF NOT EXISTS `draw_results` (`lottery_type` TEXT NOT NULL, `issue` TEXT NOT NULL, " +
        "`draw_date` TEXT NOT NULL, `primary_numbers` TEXT NOT NULL, `secondary_numbers` TEXT NOT NULL, " +
        "`status` TEXT NOT NULL, `revision` INTEGER NOT NULL, `rule_version` TEXT NOT NULL, " +
        "`policy` TEXT NOT NULL, `prize_tiers` TEXT NOT NULL, `evidence` TEXT NOT NULL, " +
        "`supporting_evidence` TEXT NOT NULL, `data_version` INTEGER NOT NULL, " +
        "PRIMARY KEY(`lottery_type`, `issue`))"

/** 模式 2 升级到 3：只新增已开奖查询结果表，不改动票据表和历史开奖表。 */
internal val MIGRATION_2_3: Migration =
    Migration(2, 3) { connection ->
        connection.execSQL(CREATE_DRAW_RESULTS_SQL)
    }

/** 按版本顺序登记的全部显式迁移。 */
internal val WIN_LOTTERY_DATABASE_MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
