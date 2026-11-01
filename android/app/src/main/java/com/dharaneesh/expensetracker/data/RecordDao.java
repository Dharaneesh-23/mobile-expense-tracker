package com.dharaneesh.expensetracker.data;

import androidx.room.*;
import java.util.List;

@Dao
public interface RecordDao {
  @Query("SELECT * FROM records WHERE deleted = 0 ORDER BY updatedAt DESC")
  List<Record> all();

  @Query("SELECT * FROM records WHERE dirty = 1 AND kind != 'review' LIMIT 100")
  List<Record> dirty();

  @Query("SELECT * FROM records WHERE id = :id LIMIT 1")
  Record get(String id);

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  void put(Record record);

  @Query("UPDATE records SET dirty = 0 WHERE id = :id AND updatedAt = :version")
  void markSynced(String id, long version);
}
