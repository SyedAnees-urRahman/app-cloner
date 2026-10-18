# Keep our data + engine classes unobfuscated so crash reports and
# hook diagnostics stay readable.
-keep class com.cloner.multiinstance.model.** { *; }
-keep class com.cloner.multiinstance.engine.** { *; }

# Room (defense in depth; the artifact also ships consumer rules).
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
