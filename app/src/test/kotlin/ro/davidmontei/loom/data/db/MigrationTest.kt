package ro.davidmontei.loom.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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
            saves.upsertSwitchTitle(SwitchTitleEntity(30, "010015100B514000", SwitchTitleSource.OWNER, null, 1))
            assertThat(saves.switchTitle(30)!!.source).isEqualTo(SwitchTitleSource.OWNER)
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
