package com.dharaneesh.expensetracker.data;

import androidx.annotation.NonNull;
import androidx.room.*;

/** Financial fields are encrypted; only synchronization metadata is stored in plaintext. */
@Entity(tableName = "records")
public class Record {
  @PrimaryKey @NonNull public String id = "";
  @NonNull public String kind = "";
  @NonNull public String encrypted = "";
  public long updatedAt;
  public boolean dirty = true;
  public boolean deleted;
}
