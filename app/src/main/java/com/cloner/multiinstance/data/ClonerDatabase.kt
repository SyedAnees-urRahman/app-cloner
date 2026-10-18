package com.cloner.multiinstance.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cloner.multiinstance.model.CloneEntity

@Database(entities = [CloneEntity::class], version = 1, exportSchema = false)
abstract class ClonerDatabase : RoomDatabase() {
    abstract fun cloneDao(): CloneDao

    companion object {
        @Volatile
        private var instance: ClonerDatabase? = null

        fun get(context: Context): ClonerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClonerDatabase::class.java,
                    "cloner.db"
                ).build().also { instance = it }
            }
    }
}
