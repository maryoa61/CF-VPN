package com.example.data

import kotlinx.coroutines.flow.Flow

class VpnRepository(private val vpnConfigDao: VpnConfigDao) {
    val allConfigs: Flow<List<VpnConfig>> = vpnConfigDao.getAllConfigs()
    val selectedConfigFlow: Flow<VpnConfig?> = vpnConfigDao.getSelectedConfigFlow()

    suspend fun getSelectedConfig(): VpnConfig? {
        return vpnConfigDao.getSelectedConfig()
    }

    suspend fun getConfigById(id: Int): VpnConfig? {
        return vpnConfigDao.getConfigById(id)
    }

    suspend fun insertConfig(config: VpnConfig): Long {
        return vpnConfigDao.insertConfig(config)
    }

    suspend fun updateConfig(config: VpnConfig) {
        vpnConfigDao.updateConfig(config)
    }

    suspend fun deleteConfig(config: VpnConfig) {
        vpnConfigDao.deleteConfig(config)
    }

    suspend fun deleteAllConfigs() {
        vpnConfigDao.deleteAllConfigs()
    }

    suspend fun selectConfig(id: Int) {
        vpnConfigDao.deselectAllConfigs()
        vpnConfigDao.selectConfigById(id)
    }

    suspend fun deselectAll() {
        vpnConfigDao.deselectAllConfigs()
    }
}
