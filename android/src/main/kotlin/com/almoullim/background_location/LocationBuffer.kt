package com.almoullim.background_location

// LocationDatabase.kt
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.location.Location

class LocationBuffer(context: Context) : SQLiteOpenHelper(context, "location_buffer.db", null, 1) {
    
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                altitude REAL NOT NULL,
                accuracy REAL NOT NULL,
                bearing REAL NOT NULL,
                speed REAL NOT NULL,
                time REAL NOT NULL,
                is_mock INTEGER NOT NULL
            )
        """)
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS locations")
        onCreate(db)
    }
    
    fun insert(location: Location) {
        val values = ContentValues().apply {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("altitude", location.altitude)
            put("accuracy", location.accuracy.toDouble())
            put("bearing", location.bearing.toDouble())
            put("speed", location.speed.toDouble())
            put("time", location.time.toDouble())
            put("is_mock", if (location.isFromMockProvider) 1 else 0)
        }
        writableDatabase.insert("locations", null, values)
    }
    
    fun getAll(): List<Map<String, Any>> {
        val locations = mutableListOf<Map<String, Any>>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM locations ORDER BY time ASC", null)
        
        cursor.use {
            while (it.moveToNext()) {
                locations.add(mapOf(
                    "latitude" to it.getDouble(it.getColumnIndexOrThrow("latitude")),
                    "longitude" to it.getDouble(it.getColumnIndexOrThrow("longitude")),
                    "altitude" to it.getDouble(it.getColumnIndexOrThrow("altitude")),
                    "accuracy" to it.getDouble(it.getColumnIndexOrThrow("accuracy")),
                    "bearing" to it.getDouble(it.getColumnIndexOrThrow("bearing")),
                    "speed" to it.getDouble(it.getColumnIndexOrThrow("speed")),
                    "time" to it.getDouble(it.getColumnIndexOrThrow("time")),
                    "is_mock" to (it.getInt(it.getColumnIndexOrThrow("is_mock")) == 1)
                ))
            }
        }
        return locations
    }
    
    fun clear() {
        writableDatabase.delete("locations", null, null)
    }
    
    fun count(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM locations", null)
        cursor.use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }
}