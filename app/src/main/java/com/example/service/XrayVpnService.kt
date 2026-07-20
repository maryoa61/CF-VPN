override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val manager = VpnConnectionManager.getInstance(this)

        if (action == "STOP") {
            manager.log("Disconnect command received from app interface")
            stopVpn()
            return START_NOT_STICKY
        }

        // 1. نمایش فوری نوتیفیکیشن برای جلوگیری از کرش سرویس در اندروید ۱۲ به بالا
        startForegroundNotification()

        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (configJson.isNullOrEmpty()) {
            manager.log("Fatal: no VpnConfig payload supplied")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopVpn()
            return START_NOT_STICKY
        }

        manager.setStatus(VpnStatus.CONNECTING)
        manager.log("Initializing core-level network tunnel configuration...")

        // 2. انتقال عملیات سنگین (پارس Moshi و اسکن IP) به ترد بک‌گراند
        serviceScope.launch {
            val config = try {
                moshi.adapter(VpnConfig::class.java).fromJson(configJson)
            } catch (e: Exception) {
                manager.log("Fatal: failed to parse VpnConfig payload: ${e.message}")
                null
            }

            if (config == null || config.type != "trojan" || config.password.isNullOrEmpty()) {
                manager.log("Fatal: Invalid config (null, non-trojan, or missing password)")
                manager.setStatus(VpnStatus.DISCONNECTED)
                stopVpn()
                return@launch
            }

            startVpn(config)
        }

        return START_STICKY
    }

    private fun startVpn(config: VpnConfig) {
        val manager = VpnConnectionManager.getInstance(this)
        try {
            if (vpnInterface != null) {
                manager.log("startVpn() called while already connected, ignoring")
                return
            }

            val builder = Builder()
                .setSession("CFVPN_Secure_Tunnel")
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS_V4, 32)
                .addAddress(TUN_ADDRESS_V6, 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addDnsServer("2001:4860:4860::8888")

            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                manager.log("Warning: could not exclude own app from tunnel")
            }

            val pfd = builder.establish()
            if (pfd == null) {
                manager.log("Fatal: VpnService.Builder.establish() returned null")
                manager.setStatus(VpnStatus.DISCONNECTED)
                stopVpn()
                return
            }
            vpnInterface = pfd

            manager.log("Virtual TUN device created successfully: MTU=$TUN_MTU")

            if (!coreEnvInitialized) {
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")
                coreEnvInitialized = true
            }

            val poolCandidates = config.ipPool?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: IpPoolManager.DEFAULT_POOL

            val resolvedPool: List<String> = if (poolCandidates.isNotEmpty()) {
                val targetPort = if (config.port > 0) config.port else IP_POOL_SCAN_PORT_FALLBACK
                
                // اسکن IP الان در بستر Dispatchers.IO امن است (runBlocking حذف شد)
                val ranked = IpPoolManager.scanAndRank(
                    candidates = poolCandidates,
                    port = targetPort,
                    keepTop = IP_POOL_KEEP_TOP,
                    timeoutMs = IP_POOL_SCAN_TIMEOUT_MS
                )
                
                if (ranked.isEmpty()) {
                    manager.log("IP-Pool: no candidate answered, falling back to ${config.address}")
                    emptyList()
                } else {
                    manager.log("IP-Pool: ${ranked.size} clean edge IP(s) ready")
                    IpPoolManager.start(
                        scope = serviceScope,
                        candidates = poolCandidates,
                        port = targetPort,
                        intervalMs = IP_POOL_RESCAN_INTERVAL_MS,
                        timeoutMs = IP_POOL_SCAN_TIMEOUT_MS,
                        keepTop = IP_POOL_KEEP_TOP
                    ) { updated -> manager.log("IP-Pool refreshed: ${updated.joinToString()}") }
                    ranked
                }
            } else {
                emptyList()
            }

            val xrayConfigJson = buildXrayConfig(config, resolvedPool)
            val controller = Libv2ray.newCoreController(this)
            coreController = controller
            controller.startLoop(xrayConfigJson, 0)
            
            val hevConfig = buildHevConfig()
            val hevResult = Hev2Socks.start(hevConfig, pfd.fd)
            if (hevResult != 0) {
                manager.log("Fatal: Hev2Socks.start returned $hevResult")
                manager.setStatus(VpnStatus.DISCONNECTED)
                stopVpn()
                return
            }

            startHevWatchdog()
            manager.setStatus(VpnStatus.CONNECTED)

        } catch (e: Exception) {
            manager.log("Fatal: Failed to establish VPN tunnel: ${e.message}")
            manager.setStatus(VpnStatus.DISCONNECTED)
            stopVpn()
        }
    }
