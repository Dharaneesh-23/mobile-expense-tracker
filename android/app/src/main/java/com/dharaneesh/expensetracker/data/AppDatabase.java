package com.dharaneesh.expensetracker.data;

import androidx.room.*;

@Database(
    entities = {Record.class},
    version = 1,
    exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
  public abstract RecordDao records();
}
