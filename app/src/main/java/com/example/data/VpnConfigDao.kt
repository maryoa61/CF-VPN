package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnConfigDao {
    @Query("SELECT * FROM vpn_configs ORDER BY id DESC")
    fun getAllConfigs(): Flow<List<VpnConfig>>

    @Query("SELECT * FROM vpn_configs WHERE isSelected = 1 LIMIT 1")
    fun getSelectedConfigFlow(): Flow<VpnConfig?>

    @Query("SELECT * FROM vpn_configs WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedConfig(): VpnConfig?

    @Query("SELECT * FROM vpn_configs WHERE id = :id LIMIT 1")
    suspend fun getConfigById(id: Int): VpnConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: VpnConfig): Long

    @Update
    suspend fun updateConfig(config: VpnConfig)

    @Delete
    suspend fun deleteConfig(config: VpnConfig)

    @Query("DELETE FROM vpn_configs")
    suspend fun deleteAllConfigs()

    @Query("UPDATE vpn_configs SET isSelected = 0")
    suspend fun deselectAllConfigs()

    @Query("UPDATE vpn_configs SET isSelected = 1 WHERE id = :id")
    suspend fun selectConfigById(id: Int)
}
