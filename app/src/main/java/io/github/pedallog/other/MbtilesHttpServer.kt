package io.github.pedallog.other

import fi.iki.elonen.NanoHTTPD
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * MBTiles HTTP server with detailed logging and broader normalized schema support.
 * Supports:
 * - Classic schema: tiles(zoom_level|zoom, tile_column|x, tile_row|y, tile_data|data)
 * - Normalized schema variants:
 *   - tiles_shallow(zoom_level,tile_column,tile_row,<link>) + tiles_data(<link>,tile_data)
 *     where <link> can be: tile_id | tile_hash | tile_data_id
 *   - OpenMapTiles-like: map(zoom_level,tile_column,tile_row,<link>) + (tile_data|images)(<link>,tile_data)
 *     where <link> can be: tile_id | tile_hash | tile_data_id
 *
 * Handles:
 * - XYZ->TMS Y flip
 * - gzip-encoded vector tiles (adds Content-Encoding: gzip when needed)
 * - Proper Content-Type for vector PBF tiles
 */
class MbtilesHttpServer(private val portNum: Int, private val mbtilesFile: File) : NanoHTTPD(portNum) {

    private lateinit var db: SQLiteDatabase

    // Schema flags
    private var hasClassicTiles = false
    private var hasNormalized = false

    // Column name variants for classic schema
    private var classicZoomCol: String = "zoom_level"
    private var classicXCol: String = "tile_column"
    private var classicYCol: String = "tile_row"
    private var classicDataCol: String = "tile_data"

    // Normalized schema config
    private var normalizedLinkColumn: String? = null // tile_id | tile_hash | tile_data_id
    private var normalizedDataTable: String? = null  // tile_data | images

    // gzip detection
    private var likelyGzipped: Boolean = false

    fun startServer() {
        Log.i("MbtilesHttpServer", "Opening MBTiles: ${mbtilesFile.absolutePath}")
        db = SQLiteDatabase.openDatabase(mbtilesFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

        val tables = getTables()
        Log.i("MbtilesHttpServer", "Tables: $tables")

        hasClassicTiles = "tiles" in tables
        hasNormalized = ("tiles_shallow" in tables && ("tiles_data" in tables || "images" in tables)) ||
                ("map" in tables && ("images" in tables || "tile_data" in tables))

        Log.i("MbtilesHttpServer", "hasClassicTiles=$hasClassicTiles, hasNormalized=$hasNormalized")

        if (!hasClassicTiles && !hasNormalized) {
            throw IllegalStateException("MBTiles schema not supported: $tables")
        }

        if (hasClassicTiles) {
            val cols = getColumnNames("tiles")
            Log.i("MbtilesHttpServer", "tiles columns: $cols")
            classicZoomCol = when {
                "zoom_level" in cols -> "zoom_level"
                "zoom" in cols -> "zoom"
                else -> "zoom_level"
            }
            classicXCol = when {
                "tile_column" in cols -> "tile_column"
                "x" in cols -> "x"
                else -> "tile_column"
            }
            classicYCol = when {
                "tile_row" in cols -> "tile_row"
                "y" in cols -> "y"
                else -> "tile_row"
            }
            classicDataCol = when {
                "tile_data" in cols -> "tile_data"
                "data" in cols -> "data"
                else -> "tile_data"
            }
            Log.i("MbtilesHttpServer", "Classic col mapping: zoom=$classicZoomCol, x=$classicXCol, y=$classicYCol, data=$classicDataCol")
        }

        if (hasNormalized) {
            // Detect link column from tiles_shallow or map; include tile_data_id support
            val shallowCols = if ("tiles_shallow" in tables) getColumnNames("tiles_shallow") else emptySet()
            val mapCols = if ("map" in tables) getColumnNames("map") else emptySet()
            Log.i("MbtilesHttpServer", "tiles_shallow columns: $shallowCols")
            Log.i("MbtilesHttpServer", "map columns: $mapCols")

            normalizedLinkColumn = when {
                "tile_id" in shallowCols -> "tile_id"
                "tile_hash" in shallowCols -> "tile_hash"
                "tile_data_id" in shallowCols -> "tile_data_id"
                "tile_id" in mapCols -> "tile_id"
                "tile_hash" in mapCols -> "tile_hash"
                "tile_data_id" in mapCols -> "tile_data_id"
                else -> null
            }

            // Determine data table name
            normalizedDataTable = when {
                "tile_data" in tables -> "tile_data"
                "images" in tables -> "images"
                else -> null
            }
            Log.i("MbtilesHttpServer", "Normalized config: link=$normalizedLinkColumn, dataTable=$normalizedDataTable")
            if (normalizedLinkColumn == null) {
                Log.w("MbtilesHttpServer", "Could not determine normalized link column; shallowCols=$shallowCols mapCols=$mapCols")
            }
        }

        likelyGzipped = detectGzip()
        Log.i("MbtilesHttpServer", "likelyGzipped=$likelyGzipped")

        start(SOCKET_READ_TIMEOUT, false)
        Log.i("MbtilesHttpServer", "Started on http://127.0.0.1:$portNum serving ${mbtilesFile.name}")
    }

    fun stopServer() {
        try {
            stop()
        } catch (e: Exception) {
            Log.w("MbtilesHttpServer", "Error stopping server: ${e.message}")
        } finally {
            if (this::db.isInitialized && db.isOpen) db.close()
        }
        Log.i("MbtilesHttpServer", "Stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val parts = uri.trimStart('/').split('/')
        Log.d("MbtilesHttpServer", "Request uri=$uri parts=$parts")

        return try {
            if (parts.size != 3 || !parts[2].endsWith(".pbf")) {
                Log.w("MbtilesHttpServer", "Unsupported path or extension: $uri")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
            val z = parts[0].toIntOrNull()
            val x = parts[1].toIntOrNull()
            val yStr = parts[2].removeSuffix(".pbf")
            val y = yStr.toIntOrNull()
            if (z == null || x == null || y == null) {
                Log.w("MbtilesHttpServer", "Bad z/x/y: z=$z x=$x y=$y from $uri")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad z/x/y")
            }

            val tmsY = (1 shl z) - 1 - y
            Log.d("MbtilesHttpServer", "XYZ z=$z x=$x y=$y -> TMS y=$tmsY (flip applied)")

            val blobClassic = fetchClassicTile(z, x, tmsY)
            val blobNormalized = if (blobClassic == null) fetchNormalizedTile(z, x, tmsY) else null
            val blob = blobClassic ?: blobNormalized

            Log.d(
                "MbtilesHttpServer",
                "Branch=${if (blobClassic != null) "classic" else if (blobNormalized != null) "normalized" else "none"} " +
                        "blobSize=${blob?.size ?: 0}"
            )

            return if (blob != null && blob.isNotEmpty()) {
                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/x-protobuf",
                    blob.inputStream(),
                    blob.size.toLong()
                )
                val gzip = isGzip(blob) || likelyGzipped
                if (gzip) {
                    resp.addHeader("Content-Encoding", "gzip")
                    Log.d("MbtilesHttpServer", "Added Content-Encoding: gzip")
                }
                resp.addHeader("Cache-Control", "max-age=3600")
                Log.d("MbtilesHttpServer", "200 OK for z=$z x=$x y=$y (tmsY=$tmsY)")
                resp
            } else {
                Log.v("MbtilesHttpServer", "204 No Content for z=$z x=$x y=$y (tmsY=$tmsY)")
                newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            }
        } catch (e: Exception) {
            Log.e("MbtilesHttpServer", "serve error for uri=$uri: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    // ----- Classic schema -----
    private fun fetchClassicTile(z: Int, x: Int, yTms: Int): ByteArray? {
        if (!hasClassicTiles) return null
        val sql = """
            SELECT $classicDataCol FROM tiles
            WHERE $classicZoomCol=? AND $classicXCol=? AND $classicYCol=?
            LIMIT 1
        """.trimIndent()
        val args = arrayOf(z.toString(), x.toString(), yTms.toString())
        Log.d("MbtilesHttpServer", "Classic SQL: $sql args=${args.toList()}")
        return try {
            db.rawQuery(sql, args).use { c ->
                if (c.moveToFirst()) {
                    val blob = c.getBlob(0)
                    Log.d("MbtilesHttpServer", "Classic hit length=${blob?.size ?: 0}")
                    blob
                } else {
                    Log.d("MbtilesHttpServer", "Classic miss")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w("MbtilesHttpServer", "Classic query failed: ${e.message}")
            null
        }
    }

    // ----- Normalized schema -----
    private fun fetchNormalizedTile(z: Int, x: Int, yTms: Int): ByteArray? {
        val tables = getTables()
        val link = normalizedLinkColumn

        // tiles_shallow + tiles_data/images
        if ("tiles_shallow" in tables && link != null && ( "tiles_data" in tables || "images" in tables )) {
            val dataTable = if ("tiles_data" in tables) "tiles_data" else "images"
            val sql = """
                SELECT d.tile_data
                FROM tiles_shallow s
                JOIN $dataTable d ON s.$link = d.$link
                WHERE s.zoom_level=? AND s.tile_column=? AND s.tile_row=?
                LIMIT 1
            """.trimIndent()
            val args = arrayOf(z.toString(), x.toString(), yTms.toString())
            Log.d("MbtilesHttpServer", "Normalized shallow+data SQL: $sql args=${args.toList()}")
            return try {
                db.rawQuery(sql, args).use { c ->
                    if (c.moveToFirst()) {
                        val blob = c.getBlob(0)
                        Log.d("MbtilesHttpServer", "Normalized shallow+data hit length=${blob?.size ?: 0}")
                        blob
                    } else {
                        Log.d("MbtilesHttpServer", "Normalized shallow+data miss")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w("MbtilesHttpServer", "Normalized shallow+data query failed: ${e.message}")
                null
            }
        }

        // OpenMapTiles-like: map + tile_data/images
        if ("map" in tables && ( "tile_data" in tables || "images" in tables )) {
            val dataTable = if ("tile_data" in tables) "tile_data" else "images"
            val mapCols = getColumnNames("map")
            val chosenLink = when {
                link != null -> link
                "tile_id" in mapCols -> "tile_id"
                "tile_hash" in mapCols -> "tile_hash"
                "tile_data_id" in mapCols -> "tile_data_id"
                else -> "tile_id"
            }
            val sql = """
                SELECT d.tile_data
                FROM map m
                JOIN $dataTable d ON m.$chosenLink = d.$chosenLink
                WHERE m.zoom_level=? AND m.tile_column=? AND m.tile_row=?
                LIMIT 1
            """.trimIndent()
            val args = arrayOf(z.toString(), x.toString(), yTms.toString())
            Log.d("MbtilesHttpServer", "Normalized map+data SQL: $sql args=${args.toList()}")
            return try {
                db.rawQuery(sql, args).use { c ->
                    if (c.moveToFirst()) {
                        val blob = c.getBlob(0)
                        Log.d("MbtilesHttpServer", "Normalized map+data hit length=${blob?.size ?: 0}")
                        blob
                    } else {
                        Log.d("MbtilesHttpServer", "Normalized map+data miss")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w("MbtilesHttpServer", "Normalized map+data query failed: ${e.message}")
                null
            }
        }

        Log.w("MbtilesHttpServer", "Normalized schema not recognized for z=$z x=$x y=$yTms (link=$link, dataTable=$normalizedDataTable)")
        return null
    }

    // ----- Helpers -----
    private fun getTables(): Set<String> {
        return try {
            val set = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) set.add(c.getString(0))
            }
            set
        } catch (e: Exception) {
            Log.w("MbtilesHttpServer", "getTables error: ${e.message}")
            emptySet()
        }
    }

    private fun getColumnNames(table: String): Set<String> {
        return try {
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val cols = mutableSetOf<String>()
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (nameIdx >= 0) cols.add(c.getString(nameIdx))
                }
                cols
            }
        } catch (e: Exception) {
            Log.w("MbtilesHttpServer", "getColumnNames($table) error: ${e.message}")
            emptySet()
        }
    }

    private fun isGzip(bytes: ByteArray): Boolean {
        // GZIP magic number 0x1f 0x8b
        return bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    }

    private fun detectGzip(): Boolean {
        // Try to read one sample tile from any supported schema to detect gzip
        val sample: ByteArray? = try {
            db.rawQuery("SELECT $classicDataCol FROM tiles ORDER BY $classicZoomCol ASC LIMIT 1", null)
                .use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        } catch (_: Exception) { null } ?: try {
            db.rawQuery(
                "SELECT d.tile_data FROM tiles_shallow s JOIN tiles_data d ON (s.tile_id=d.tile_id OR s.tile_hash=d.tile_hash OR s.tile_data_id=d.tile_data_id) LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        } catch (_: Exception) { null } ?: try {
            db.rawQuery(
                "SELECT d.tile_data FROM map m JOIN tile_data d ON (m.tile_id=d.tile_id OR m.tile_hash=d.tile_hash OR m.tile_data_id=d.tile_data_id) LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        } catch (_: Exception) { null } ?: try {
            db.rawQuery(
                "SELECT d.tile_data FROM map m JOIN images d ON (m.tile_id=d.tile_id OR m.tile_hash=d.tile_hash OR m.tile_data_id=d.tile_data_id) LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        } catch (_: Exception) { null }

        return sample?.let { isGzip(it) } ?: false
    }
}
