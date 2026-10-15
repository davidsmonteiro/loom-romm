package ro.davidmontei.loom.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.di.DataModule
import java.io.File

/**
 * Upgrading Loom keeps the catalog and installs, and adds what save sync needs. The old database is
 * built from an exported schema; Room then migrates it and checks every table against what the
 * current version expects, as it does on the Thor.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun version1BecomesTheCurrentVersionKeepingEverything() = runBlocking<Unit> {
        val file = context.getDatabasePath(DB).apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, version = 1)
            db.execSQL("INSERT INTO platforms VALUES (1, '3ds', '3ds', '3DS', 'Nintendo 3DS', 1, 't')")
            db.execSQL(
                "INSERT INTO roms (id, platform_id, platform_slug, platform_fs_slug, title, search_key, sort_key, fs_name, " +
                    "fs_name_no_ext, fs_extension, size_bytes, has_simple_single_file, has_nested_single_file, has_multiple_files, " +
                    "cover_small, cover_large, regions, updated_at, missing_from_fs, removed_on_server) VALUES " +
                    "(7, 1, '3ds', '3ds', 'Pokemon X', 'pokemon x', 'pokemon x', 'Pokemon X.3ds', 'Pokemon X', '3ds', 100, 1, 0, 0, " +
                    "NULL, NULL, 'Europe', 't', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO installs VALUES (7, '6365-3230', '/storage/6365-3230/ROMs', 'n3ds/Pokemon X.3ds', " +
                    "'n3ds/pokemon x.3ds', 1, 'FILE', 100, 'SIZE', 'ADOPTED', NULL, 1)",
            )
        }

        val room = DataModule.database(context) // as the app opens it
        try {
            val rom = room.catalogDao().rom(7)!!
            assertThat(rom.title).isEqualTo("Pokemon X")
            assertThat(rom.titleId).isNull() // filled by the next (full) catalog sync
            assertThat(rom.saveTarget).isNull()
            assertThat(room.catalogDao().install(7)!!.relPath).isEqualTo("n3ds/Pokemon X.3ds")

            val saves = room.saveDao()
            saves.upsertBaseline(SaveBaselineEntity(7, "autosave", "abc", 12, "abc", "2026-10-13T17:49:45Z", 1))
            assertThat(saves.baseline(7, "autosave")!!.serverSaveId).isEqualTo(12)
            saves.upsertConflict(SaveConflictEntity(7, "autosave", "BOTH_CHANGED", "l", 1, 2, 12, "s", "t", "azahar", 3))
            assertThat(saves.conflict(7)!!.skipped).isFalse()
            saves.upsertUnit(SaveUnitEntity(7, "azahar", "/x/data", "f", 2, 3, 4, "l", SaveSyncState.CONFLICT, null, 5))
            assertThat(saves.unit(7)!!.state).isEqualTo(SaveSyncState.CONFLICT)
        } finally {
            room.close()
        }
    }

    /** A baseline from before Loom recorded its folder stays, with no folder: the next sync compares as a first one. */
    @Test fun version2BaselinesStayWithoutAFolder() = runBlocking<Unit> {
        val file = context.getDatabasePath(DB).apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, version = 2)
            db.execSQL("INSERT INTO save_baselines VALUES (7, 'autosave', 'abc', 12, 'abc', '2026-10-13T17:49:45Z', 1)")
        }

        val room = DataModule.database(context)
        try {
            val baseline = room.saveDao().baseline(7, "autosave")!!
            assertThat(baseline.serverSaveId).isEqualTo(12)
            assertThat(baseline.unitPath).isNull()
            room.saveDao().upsertBaseline(baseline.copy(unitPath = "/x/data"))
            assertThat(room.saveDao().baseline(7, "autosave")!!.unitPath).isEqualTo("/x/data")
        } finally {
            room.close()
        }
    }

    /** Version 3 (Loom 0.2.0) keeps its baselines and gains the table of Switch games' title IDs. */
    @Test fun version3GainsSwitchTitleIdsKeepingBaselines() = runBlocking<Unit> {
        val file = context.getDatabasePath(DB).apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, version = 3)
            db.execSQL("INSERT INTO save_baselines VALUES (7, 'autosave', 'abc', 12, 'abc', '2026-10-13T17:49:45Z', 1, '/x/data')")
        }

        val room = DataModule.database(context)
        try {
            val saves = room.saveDao()
            assertThat(saves.baseline(7, "autosave")!!.unitPath).isEqualTo("/x/data")
            saves.upsertSaveKey(SaveKeyEntity("eden", 30, "010015100B514000", SaveKeySource.OWNER, null, 1))
            assertThat(saves.saveKey("eden", 30)!!.source).isEqualTo(SaveKeySource.OWNER)
        } finally {
            room.close()
        }
    }

    /** Version 4 (Loom 0.3.0) keeps Switch games' title IDs and gains the folders owners chose to sync from. */
    @Test fun version4GainsSyncedFoldersKeepingSwitchTitleIds() = runBlocking<Unit> {
        val file = context.getDatabasePath(DB).apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, version = 4)
            db.execSQL("INSERT INTO switch_titles VALUES (30, '010015100B514000', 'IMAGE', 'print', 1)")
        }

        val room = DataModule.database(context)
        try {
            val saves = room.saveDao()
            assertThat(saves.saveKey("eden", 30)!!.saveKey).isEqualTo("010015100B514000")
            val folder = "/storage/6365-3230/ROMs/saves/Eden/user/save/0000000000000000/${"0".repeat(32)}/010015100B514000"
            saves.upsertSyncedFolder(SyncedFolderEntity(30, folder, 2))
            assertThat(saves.syncedFolder(30)!!.folder).isEqualTo(folder)
            saves.upsertUnit(SaveUnitEntity(30, "eden", null, null, 0, 0, null, null, SaveSyncState.MOVED, "why", 3))
            assertThat(saves.unit(30)!!.state).isEqualTo(SaveSyncState.MOVED)
        } finally {
            room.close()
        }
    }

    /**
     * Version 5 (Loom 0.3.1) keeps its queue, mid-download parts included; those games came from the
     * library, not from a RomM install request. A job queued afterwards can remember its request.
     */
    @Test fun version5DownloadsCarryOnAndCameFromNoRommRequest() = runBlocking<Unit> {
        val file = context.getDatabasePath(DB).apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, version = 5)
            db.execSQL(
                "INSERT INTO download_jobs (id, rom_id, platform_id, title, phase, wait_reason, failure_code, failure_detail, " +
                    "failure_retryable, lease_id, attempt, generation, checksum_retries, plan_json, fingerprint, volume_uuid, " +
                    "staging_root, roms_root, final_rel_path, final_key, final_kind, bytes_done, bytes_total, queue_order, updated_at) " +
                    "VALUES (3, 7, 1, 'Pokemon X', 'PAUSED', NULL, NULL, NULL, 1, NULL, 1, 1, 0, '{}', 'fp', '6365-3230', " +
                    "'/storage/6365-3230/.loom-staging', '/storage/6365-3230/ROMs', NULL, NULL, NULL, 50, 100, 1, 1)",
            )
            db.execSQL("INSERT INTO download_parts VALUES (3, 70, '{}', 'api/roms/7/content/x', 50, 'etag', NULL, 'PARTIAL')")
        }

        val room = DataModule.database(context)
        try {
            val downloads = room.downloadDao()
            val job = downloads.jobForRom(7)!!
            assertThat(job.phase).isEqualTo("PAUSED")
            assertThat(job.bytesDone).isEqualTo(50)
            assertThat(job.installRequestId).isNull()
            assertThat(downloads.parts(3).single().durableBytes).isEqualTo(50)

            val sent = downloads.insertJob(job.copy(id = 0, romId = 8, title = "Pokemon Y", queueOrder = 2, installRequestId = "req-1"))
            assertThat(downloads.jobForInstallRequest("req-1")!!.id).isEqualTo(sent)
            downloads.setInstallRequest(3, "req-2")
            assertThat(downloads.jobForInstallRequest("req-2")!!.romId).isEqualTo(7)
        } finally {
            room.close()
        }
    }

    /**
     * Version 6's Switch title IDs, read or chosen, become Eden's save keys for those games, so
     * PPSSPP's disc IDs can sit beside them; the folders owners chose to sync from stay as they were.
     */
    @Test fun version6MovesSwitchTitleIdsIntoTheSaveKeyTable() = runBlocking<Unit> {
        val folder = "/storage/6365-3230/ROMs/saves/Eden/user/save/0000000000000000/${"0".repeat(32)}/010015100B514000"
        val file = context.getDatabasePath(DB).apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, version = 6)
            db.execSQL("INSERT INTO switch_titles VALUES (30, '010015100B514000', 'IMAGE', 'print', 1)")
            db.execSQL("INSERT INTO switch_titles VALUES (31, '01001F5010DFA000', 'OWNER', NULL, 2)")
            db.execSQL("INSERT INTO synced_folders VALUES (30, '$folder', 3)")
        }

        val room = DataModule.database(context)
        try {
            val saves = room.saveDao()
            assertThat(saves.saveKey("eden", 30)).isEqualTo(SaveKeyEntity("eden", 30, "010015100B514000", SaveKeySource.IMAGE, "print", 1))
            assertThat(saves.saveKey("eden", 31)).isEqualTo(SaveKeyEntity("eden", 31, "01001F5010DFA000", SaveKeySource.OWNER, null, 2))
            assertThat(saves.observeOwnerChosenSaveKeys().first()).containsExactly(31)
            assertThat(saves.syncedFolder(30)!!.folder).isEqualTo(folder)
            assertThat(room.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'switch_titles'").use { it.count })
                .isEqualTo(0)

            // A PSP game's disc ID sits beside them, under PPSSPP's id.
            saves.upsertSaveKey(SaveKeyEntity("ppsspp", 30, "ULUS10064", SaveKeySource.ROMM, null, 4))
            assertThat(saves.saveKey("ppsspp", 30)!!.saveKey).isEqualTo("ULUS10064")
            assertThat(saves.saveKey("eden", 30)!!.saveKey).isEqualTo("010015100B514000")
            saves.forgetLearnedSaveKey("ppsspp", 30)
            saves.forgetLearnedSaveKey("eden", 31) // the owner's pick stays
            assertThat(saves.saveKey("ppsspp", 30)).isNull()
            assertThat(saves.saveKey("eden", 31)).isNotNull()
        } finally {
            room.close()
        }
    }

    /** Version 6's save conflicts stay, skipped or not, saying no more than their reason. A conflict can say more from now on. */
    @Test fun version6ConflictsStayAndCanSayWhyRommsSaveCantBeRestored() = runBlocking<Unit> {
        val file = context.getDatabasePath(DB).apply { parentFile!!.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createFromSchema(db, version = 6)
            db.execSQL("INSERT INTO save_conflicts VALUES (7, 'autosave', 'BOTH_CHANGED', 'l', 1, 2, 12, 's', 't', 'azahar', 3, 1)")
        }

        val room = DataModule.database(context)
        try {
            val saves = room.saveDao()
            val conflict = saves.conflict(7)!!
            assertThat(conflict.reason).isEqualTo("BOTH_CHANGED")
            assertThat(conflict.skipped).isTrue()
            assertThat(conflict.detail).isNull()
            assertThat(conflict.unitPath).isNull()
            saves.upsertConflict(conflict.copy(reason = "SERVER_UNREADABLE", detail = "RomM's save isn't an archive", unitPath = "/x/data"))
            assertThat(saves.conflict(7)!!.detail).isEqualTo("RomM's save isn't an archive")
            assertThat(saves.conflict(7)!!.unitPath).isEqualTo("/x/data")
        } finally {
            room.close()
        }
    }

    /** Creates the tables, indices and Room's identity row of an exported schema version. */
    private fun createFromSchema(db: SQLiteDatabase, version: Int) {
        val name = "ro.davidmontei.loom.data.db.LoomDatabase/$version.json"
        val json = listOf(File("schemas/$name"), File("app/schemas/$name")).first { it.isFile }.readText()
        val database = Json.parseToJsonElement(json).jsonObject.getValue("database").jsonObject
        for (entity in database.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { index ->
                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
    }

    private companion object {
        const val DB = "loom.db"
    }
}
