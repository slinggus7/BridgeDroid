package com.slinggus.bridgedroid

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class BridgeManager(private val context: Context, private val logCallback: (String) -> Unit) {

    private var dhcpServer: DhcpServer? = null
    private var monitorThread: Thread? = null
    private var packetLogThread: Thread? = null
    private var tcpdumpProcess: Process? = null

    @Volatile
    private var monitoring = false

    private var activeWifiGateway: String = ""
    private var activeDhcpServerIp: String = ""
    private var activeDnsDnatTarget: String = ""
    private var activeEthIpOnly: String = ""
    private val activeProxyArpEth = mutableListOf<String>()
    private val activeProxyArpWifi = mutableListOf<String>()

    enum class Mode { AUTO, PROXY_ARP, NAT }

    data class NetworkInfo(
        val wifiIface: String,
        val wifiIp: String,
        val wifiHostCidr: String,
        val wifiNetworkCidr: String,
        val wifiGateway: String,
        val ethIface: String,
        val ethUp: Boolean,
        val ethRxPackets: String,
        val ethTxPackets: String,
        val allInterfaces: List<String>
    )

    fun detectNetwork(customWifi: String = "wlan0", customEth: String = "eth0"): NetworkInfo {
        var wifiIp = ""
        var wifiHostCidr = ""
        var wifiNetworkCidr = ""
        var wifiGw = ""

        val linkRes = RootShell.execute("ip -o link show")
        val allIfaces = linkRes.stdout.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val name = parts[1].substringBefore(":").substringBefore("@")
                val state = when {
                    line.contains("state UP") -> "UP"
                    line.contains("state DOWN") -> "DOWN"
                    else -> "UNKNOWN"
                }
                val ipLine = RootShell.execute("ip -4 -o addr show dev $name").stdout.firstOrNull()
                val ip = ipLine?.let {
                    Regex("""inet\s+(\S+)""").find(it)?.groupValues?.get(1)
                } ?: "no IPv4"
                "$name [$state] ($ip)"
            } else null
        }

        val ipRes = RootShell.execute("ip -4 addr show dev $customWifi")
        for (line in ipRes.stdout) {
            val trimmed = line.trim()
            if (trimmed.startsWith("inet ")) {
                val tokens = trimmed.split(Regex("\\s+"))
                if (tokens.size >= 2) {
                    wifiHostCidr = tokens[1]
                    wifiIp = wifiHostCidr.substringBefore("/")
                    wifiNetworkCidr = computeNetworkCidr(wifiHostCidr)
                }
            }
        }

        val routeRes = RootShell.execute("ip -4 route show table all dev $customWifi")
        for (line in routeRes.stdout) {
            if (line.contains("default via")) {
                val parts = line.trim().split(Regex("\\s+"))
                val idx = parts.indexOf("via")
                if (idx != -1 && idx + 1 < parts.size) {
                    wifiGw = parts[idx + 1]
                    break
                }
            }
        }

        val ethRes = RootShell.execute("ip link show dev $customEth")
        val ethUp = ethRes.stdout.any { it.contains("state UP") || it.contains("state UNKNOWN") }
        val rxPkts = RootShell.execute("cat /sys/class/net/$customEth/statistics/rx_packets").stdout.firstOrNull()?.trim() ?: "?"
        val txPkts = RootShell.execute("cat /sys/class/net/$customEth/statistics/tx_packets").stdout.firstOrNull()?.trim() ?: "?"

        return NetworkInfo(
            customWifi, wifiIp, wifiHostCidr, wifiNetworkCidr,
            wifiGw, customEth, ethUp, rxPkts, txPkts, allIfaces
        )
    }

    fun startBridge(
        mode: Mode,
        interceptDhcp: Boolean,
        wifiIface: String,
        ethIface: String,
        guestIpInput: String,
        subnetMaskInput: String,
        routerInput: String,
        dnsInput: String
    ): Boolean {
        logCallback("=== Activating Bridge ($mode | InterceptDHCP=$interceptDhcp) ===")

        val netInfo = detectNetwork(wifiIface, ethIface)
        if (netInfo.wifiIp.isEmpty()) {
            logCallback("Error: $wifiIface is not connected or has no IPv4 address.")
            return false
        }

        val ethCheck = RootShell.execute("ip link show dev $ethIface")
        if (ethCheck.exitCode != 0) {
            logCallback("Error: Interface '$ethIface' not found! Tap 'Interfaces' to check the adapter name.")
            return false
        }

        if (isValidIpv4(guestIpInput) && guestIpInput == netInfo.wifiIp) {
            logCallback("WARNING: Guest IP ($guestIpInput) matches the phone's WiFi IP! Pick a different IP for the guest.")
        }

        val hasGuestIp = isValidIpv4(guestIpInput)
        val hasMask = isValidIpv4(subnetMaskInput)
        val hasRouter = isValidIpv4(routerInput)
        val hasDns = isValidIpv4(dnsInput)

        val effectiveMask = if (hasMask) subnetMaskInput else "255.255.255.0"
        val prefixLen = maskToPrefix(effectiveMask)

        val effectiveDns = when {
            hasDns -> dnsInput
            isValidIpv4(netInfo.wifiGateway) -> netInfo.wifiGateway
            else -> "1.1.1.1"
        }

        val guestInWifiSubnet = hasGuestIp && isIpInCidr(guestIpInput, netInfo.wifiNetworkCidr)
        val routerIsWifiGw = hasRouter && routerInput == netInfo.wifiGateway
        val effectiveMode = mode

        val effectiveRouter: String
        val ethLocalIp: String

        if (effectiveMode == Mode.PROXY_ARP || guestInWifiSubnet || routerIsWifiGw) {
            effectiveRouter = if (hasRouter) routerInput else if (isValidIpv4(netInfo.wifiGateway)) netInfo.wifiGateway else "192.168.1.1"
            ethLocalIp = if (hasRouter && !isIpInCidr(routerInput, netInfo.wifiNetworkCidr)) routerInput else "169.254.254.1"
        } else {
            effectiveRouter = when {
                hasRouter && !isIpInCidr(routerInput, netInfo.wifiNetworkCidr) -> routerInput
                hasGuestIp && !guestInWifiSubnet -> suggestGatewayInSubnet(guestIpInput, prefixLen)
                else -> "192.168.44.1"
            }
            ethLocalIp = if (isIpInCidr(effectiveRouter, netInfo.wifiNetworkCidr)) "169.254.254.1" else effectiveRouter
        }

        val ethCidr = "$ethLocalIp/${if (ethLocalIp == "169.254.254.1") 30 else prefixLen}"
        val dhcpOfferIp = if (hasGuestIp) guestIpInput else suggestHostInSubnet(effectiveRouter, prefixLen)
        val ethMac = RootShell.execute("cat /sys/class/net/$ethIface/address").stdout.firstOrNull()?.trim().orEmpty()

        logCallback("WiFi: ${netInfo.wifiHostCidr} | Net: ${netInfo.wifiNetworkCidr} | GW: ${netInfo.wifiGateway}")
        logCallback("Guest Config -> IP: ${if (hasGuestIp) guestIpInput else "Auto/Listen"}, Mask: $effectiveMask (/$prefixLen), Router: $effectiveRouter, DNS: $effectiveDns")

        stopBridge(wifiIface, ethIface)

        activeWifiGateway = netInfo.wifiGateway
        activeEthIpOnly = ethLocalIp

        logErrorsIfAny("Sysctl", RootShell.execute(
            "ip link set dev $ethIface up",
            "sysctl -w net.ipv4.ip_forward=1",
            "sysctl -w net.ipv4.conf.$wifiIface.forwarding=1",
            "sysctl -w net.ipv4.conf.$ethIface.forwarding=1",
            "sysctl -w net.ipv4.conf.all.rp_filter=0",
            "sysctl -w net.ipv4.conf.default.rp_filter=0",
            "sysctl -w net.ipv4.conf.$wifiIface.rp_filter=0",
            "sysctl -w net.ipv4.conf.$ethIface.rp_filter=0",
            "sysctl -w net.ipv4.conf.$ethIface.arp_filter=0",
            "sysctl -w net.ipv4.conf.$ethIface.arp_ignore=0",
            "sysctl -w net.ipv4.conf.$ethIface.arp_accept=1",
            "sysctl -w net.ipv4.conf.$wifiIface.proxy_arp=1",
            "sysctl -w net.ipv4.conf.$ethIface.proxy_arp=1",
            "sysctl -w net.ipv4.conf.$ethIface.proxy_arp_pvlan=1"
        ))

        RootShell.execute(
            "ip addr flush dev $ethIface",
            "ip addr add $ethCidr dev $ethIface"
        )

        val routeCmds = mutableListOf(
            "ip rule add iif $ethIface lookup main pref 100",
            "ip rule add iif $wifiIface lookup main pref 101"
        )
        if (netInfo.wifiNetworkCidr.isNotEmpty()) {
            routeCmds.add("ip rule add from ${netInfo.wifiNetworkCidr} lookup main pref 102")
            routeCmds.add("ip rule add to ${netInfo.wifiNetworkCidr} lookup main pref 103")
            routeCmds.add("ip route replace ${netInfo.wifiNetworkCidr} dev $wifiIface table main")
        }
        if (netInfo.wifiGateway.isNotEmpty()) {
            routeCmds.add("ip route replace default via ${netInfo.wifiGateway} dev $wifiIface table main")
        }
        if (hasGuestIp) {
            logCallback("Binding host route & Proxy-ARP: $guestIpInput/32 <-> $ethIface")
            routeCmds.add("ip rule add from $guestIpInput/32 lookup main pref 104")
            routeCmds.add("ip rule add to $guestIpInput/32 lookup main pref 105")
            routeCmds.add("ip route replace $guestIpInput/32 dev $ethIface table main")
            if (effectiveMode == Mode.PROXY_ARP && guestInWifiSubnet) {
                routeCmds.add("ip neigh replace proxy $guestIpInput dev $wifiIface")
                activeProxyArpWifi.add(guestIpInput)
            }
        }

        // Answer Proxy-ARP on eth0 for Router & DNS so guest resolves them to eth0
        if (isIpInCidr(effectiveRouter, netInfo.wifiNetworkCidr)) {
            routeCmds.add("ip neigh replace proxy $effectiveRouter dev $ethIface")
            activeProxyArpEth.add(effectiveRouter)
        }
        if (isIpInCidr(effectiveDns, netInfo.wifiNetworkCidr) && effectiveDns != netInfo.wifiIp) {
            routeCmds.add("ip neigh replace proxy $effectiveDns dev $ethIface")
            activeProxyArpEth.add(effectiveDns)
        }

        logErrorsIfAny("Routing", RootShell.execute(*routeCmds.toTypedArray()))

        val iptCmds = mutableListOf(
            "iptables -I FORWARD 1 -i $ethIface -o $wifiIface -j ACCEPT",
            "iptables -I FORWARD 1 -i $wifiIface -o $ethIface -j ACCEPT",
            "iptables -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu"
        )

        // In AUTO and NAT modes, always MASQUERADE out wlan0
        if (effectiveMode != Mode.PROXY_ARP) {
            iptCmds.add("iptables -t nat -I POSTROUTING 1 -o $wifiIface -j MASQUERADE")
            logCallback("Outbound NAT (Masquerade) ON -> $wifiIface (${netInfo.wifiIp})")
        } else {
            logCallback("Outbound NAT OFF (Pure Transparent Proxy-ARP on $wifiIface)")
        }

        if (hasDns && ethLocalIp != effectiveDns) {
            activeDnsDnatTarget = effectiveDns
            iptCmds.add("iptables -t nat -I PREROUTING 1 -i $ethIface -d $ethLocalIp -p udp --dport 53 -j DNAT --to-destination $effectiveDns:53")
            iptCmds.add("iptables -t nat -I PREROUTING 1 -i $ethIface -d $ethLocalIp -p tcp --dport 53 -j DNAT --to-destination $effectiveDns:53")
        }

        if (interceptDhcp) {
            logCallback("DHCP Intercept: ON (Offer=$dhcpOfferIp, Mask=$effectiveMask, GW=$effectiveRouter, DNS=$effectiveDns)")
            val subnetCidr = computeNetworkCidr(ethCidr)
            activeDhcpServerIp = ethLocalIp

			RootShell.execute(
                "ip route replace broadcast 255.255.255.255 dev $ethIface table 200",
                "ip route replace $subnetCidr dev $ethIface table 200",
                "ip route replace default dev $ethIface table 200",
                "ip rule add from $ethLocalIp/32 lookup 200 pref 89"
            )
            if (ethLocalIp != "169.254.254.1") {
                RootShell.execute(
                    "ip rule add from $subnetCidr lookup main pref 98",
                    "ip rule add to $subnetCidr lookup main pref 99"
                )
            }
			
            iptCmds.add("iptables -I INPUT 1 -i $ethIface -p udp --dport 67 -j ACCEPT")
            iptCmds.add("iptables -t nat -I POSTROUTING 1 -o $ethIface -p udp --sport 6767 --dport 68 -j SNAT --to-source $ethLocalIp:67")

            dhcpServer = DhcpServer(
                context = context,
                ethMac = ethMac,
                serverIp = ethLocalIp,
                offerIp = dhcpOfferIp,
                subnetMask = effectiveMask,
                routerIp = effectiveRouter,
                dnsServer = effectiveDns,
                onLog = logCallback
            )
            dhcpServer?.start()
        } else {
            logCallback("DHCP Intercept: OFF")
        }

        logErrorsIfAny("IPTables", RootShell.execute(*iptCmds.toTypedArray()))

        startMonitorLoop(ethIface, wifiIface, ethCidr, netInfo.wifiNetworkCidr, guestIpInput, effectiveMode)
        startPacketLogger(ethIface)

        logCallback("Bridge active on $ethIface. Watching packets...")
        return true
    }

    private fun startPacketLogger(ethIface: String) {
        val tcpdumpSearch = RootShell.execute(
            "for p in \$(which tcpdump 2>/dev/null) /system/bin/tcpdump /system/xbin/tcpdump /vendor/bin/tcpdump /data/local/tmp/tcpdump /data/data/com.termux/files/usr/bin/tcpdump; do [ -x \"\$p\" ] && echo \"\$p\" && break; done"
        )
        val tcpdumpBin = tcpdumpSearch.stdout.firstOrNull()?.trim().orEmpty()

        if (tcpdumpBin.isNotEmpty()) {
            logCallback("[PacketLog] Using $tcpdumpBin on $ethIface")
            packetLogThread = Thread {
                try {
                    val filter = "arp or (ip and not dst net 224.0.0.0/4)"
                    tcpdumpProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", "$tcpdumpBin -i $ethIface -n -l -q -e \"$filter\""))
                    val reader = BufferedReader(InputStreamReader(tcpdumpProcess!!.inputStream))
                    while (monitoring) {
                        val line = reader.readLine() ?: break
                        logCallback("[PKT] $line")
                    }
                } catch (_: Exception) {
                }
            }.apply { start() }
            return
        }

        val logRuleCheck = RootShell.execute(
            "iptables -t raw -I PREROUTING 1 -i $ethIface -j LOG --log-prefix \"NB_RX \" --log-level 4",
            "iptables -t mangle -I POSTROUTING 1 -o $ethIface -j LOG --log-prefix \"NB_TX \" --log-level 4"
        )

        if (logRuleCheck.exitCode == 0) {
            logCallback("[PacketLog] Using kernel IPTables LOG + link stats on $ethIface")
            packetLogThread = Thread {
                var lastTs = ""
                var lastRx = -1L
                var lastTx = -1L
                val seenInLastTs = mutableSetOf<String>()

                while (monitoring) {
                    try {
                        val rx = RootShell.execute("cat /sys/class/net/$ethIface/statistics/rx_packets").stdout.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
                        val tx = RootShell.execute("cat /sys/class/net/$ethIface/statistics/tx_packets").stdout.firstOrNull()?.trim()?.toLongOrNull() ?: -1L

                        val dmesgRes = RootShell.execute("dmesg | grep -E 'NB_RX|NB_TX' | tail -n 30")
                        var loggedIpPkts = 0

                        for (rawLine in dmesgRes.stdout) {
                            val tsMatch = Regex("""\[\s*([\d.]+)\]""").find(rawLine)
                            val ts = tsMatch?.groupValues?.get(1) ?: ""
                            if (lastTs.isNotEmpty()) {
                                val cmp = compareKernelTs(ts, lastTs)
                                if (cmp < 0) continue
                                if (cmp == 0 && !seenInLastTs.add(rawLine)) continue
                            }
                            if (ts != lastTs) {
                                lastTs = ts
                                seenInLastTs.clear()
                                seenInLastTs.add(rawLine)
                            }
                            formatIptablesLog(rawLine)?.let {
                                logCallback(it)
                                loggedIpPkts++
                            }
                        }

                        if (lastRx >= 0 && lastTx >= 0 && loggedIpPkts == 0) {
                            val dRx = rx - lastRx
                            val dTx = tx - lastTx
                            if (dRx > 0 || dTx > 0) {
                                logCallback("[L2 FRAME] $ethIface: +$dRx RX, +$dTx TX (ARP/non-IP or link traffic)")
                            }
                        }
                        lastRx = rx
                        lastTx = tx

                        Thread.sleep(600)
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                    }
                }
            }.apply { start() }
            return
        }

        logCallback("[PacketLog] Using nf_conntrack + link counter monitor on $ethIface")
        packetLogThread = Thread {
            var lastRx = -1L
            var lastTx = -1L
            val knownFlows = mutableMapOf<String, String>()
            while (monitoring) {
                try {
                    val rx = RootShell.execute("cat /sys/class/net/$ethIface/statistics/rx_packets").stdout.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
                    val tx = RootShell.execute("cat /sys/class/net/$ethIface/statistics/tx_packets").stdout.firstOrNull()?.trim()?.toLongOrNull() ?: -1L
                    if (lastRx >= 0 && (rx != lastRx || tx != lastTx)) {
                        logCallback("[PKT] $ethIface hardware counters: +${rx - lastRx} RX, +${tx - lastTx} TX")
                    }
                    lastRx = rx
                    lastTx = tx

                    val ct = RootShell.execute("cat /proc/net/nf_conntrack | grep -E 'src=192\\.168\\.|src=10\\.|src=172\\.' | head -n 25")
                    for (line in ct.stdout) {
                        val proto = Regex("""^\S+\s+\d+\s+(\S+)""").find(line)?.groupValues?.get(1) ?: "ip"
                        val src = Regex("""src=(\S+)""").find(line)?.groupValues?.get(1) ?: continue
                        val dst = Regex("""dst=(\S+)""").find(line)?.groupValues?.get(1) ?: continue
                        val spt = Regex("""sport=(\d+)""").find(line)?.groupValues?.get(1) ?: ""
                        val dpt = Regex("""dport=(\d+)""").find(line)?.groupValues?.get(1) ?: ""
                        val pkts = Regex("""packets=(\d+)""").findAll(line).map { it.groupValues[1] }.joinToString("/")
                        val key = "$proto:$src:$spt->$dst:$dpt"
                        if (knownFlows[key] != pkts) {
                            knownFlows[key] = pkts
                            logCallback("[FLOW] ${proto.uppercase()} $src:$spt -> $dst:$dpt (pkts rx/tx: $pkts)")
                        }
                    }
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }.apply { start() }
    }

    private fun formatIptablesLog(line: String): String? {
        val dir = when {
            line.contains("NB_RX") -> "PKT RX"
            line.contains("NB_TX") -> "PKT TX"
            else -> return null
        }
        val src = Regex("""SRC=(\S+)""").find(line)?.groupValues?.get(1) ?: "?"
        val dst = Regex("""DST=(\S+)""").find(line)?.groupValues?.get(1) ?: "?"
        val proto = Regex("""PROTO=(\S+)""").find(line)?.groupValues?.get(1) ?: "?"
        val spt = Regex("""SPT=(\d+)""").find(line)?.groupValues?.get(1)
        val dpt = Regex("""DPT=(\d+)""").find(line)?.groupValues?.get(1)
        val len = Regex("""LEN=(\d+)""").find(line)?.groupValues?.get(1) ?: "?"

        val srcFormatted = if (spt != null) "$src:$spt" else src
        val dstFormatted = if (dpt != null) "$dst:$dpt" else dst
        return "[$dir] $proto $srcFormatted -> $dstFormatted (${len}B)"
    }

    private fun compareKernelTs(ts1: String, ts2: String): Int {
        val d1 = ts1.toDoubleOrNull() ?: return 0
        val d2 = ts2.toDoubleOrNull() ?: return 0
        return d1.compareTo(d2)
    }

    private fun startMonitorLoop(
        ethIface: String,
        wifiIface: String,
        expectedEthCidr: String,
        wifiNetworkCidr: String,
        manualGuestIp: String,
        effectiveMode: Mode
    ) {
        monitoring = true
        val knownGuests = mutableSetOf<String>()
        val expectedIpOnly = expectedEthCidr.substringBefore("/")

        monitorThread = Thread {
            while (monitoring) {
                try {
                    val addrLines = RootShell.execute("ip -4 -o addr show dev $ethIface").stdout
                    var hasExpected = false
                    var removedConflict = false
                    for (line in addrLines) {
                        val cidr = Regex("""inet\s+(\S+)""").find(line)?.groupValues?.get(1) ?: continue
                        if (cidr == expectedEthCidr) {
                            hasExpected = true
                        } else {
                            logCallback("[Guard] Removing conflicting IP $cidr from $ethIface")
                            RootShell.execute("ip addr del $cidr dev $ethIface")
                            removedConflict = true
                        }
                    }
                    if (!hasExpected || removedConflict) {
                        val restoreCmds = mutableListOf(
                            "ip link set dev $ethIface up",
                            "ip addr replace $expectedEthCidr dev $ethIface"
                        )
                        if (wifiNetworkCidr.isNotEmpty()) {
                            restoreCmds.add("ip route replace $wifiNetworkCidr dev $wifiIface table main")
                        }
                        if (activeWifiGateway.isNotEmpty()) {
                            restoreCmds.add("ip route replace default via $activeWifiGateway dev $wifiIface table main")
                        }
                        if (isValidIpv4(manualGuestIp)) {
                            restoreCmds.add("ip route replace $manualGuestIp/32 dev $ethIface table main")
                        }
                        RootShell.execute(*restoreCmds.toTypedArray())
                    }

                    if (!isValidIpv4(manualGuestIp)) {
                        val arpRes = RootShell.execute("cat /proc/net/arp")
                        for (line in arpRes.stdout.drop(1)) {
                            val cols = line.trim().split(Regex("\\s+"))
                            if (cols.size >= 6) {
                                val ip = cols[0]
                                val flags = cols[2]
                                val mac = cols[3]
                                val dev = cols[5]

                                if (dev == ethIface &&
                                    flags != "0x0" &&
                                    mac != "00:00:00:00:00:00" &&
                                    isValidIpv4(ip) &&
                                    !ip.startsWith("169.254.") &&
                                    ip != expectedIpOnly
                                ) {
                                    if (knownGuests.add(ip)) {
                                        val sameSubnet = isIpInCidr(ip, wifiNetworkCidr)
                                        logCallback("[ARP] Guest bound on $ethIface: $ip ($mac)")
                                        val cmds = mutableListOf(
                                            "ip rule add from $ip/32 lookup main pref 104",
                                            "ip rule add to $ip/32 lookup main pref 105",
                                            "ip route replace $ip/32 dev $ethIface table main"
                                        )
                                        if (effectiveMode == Mode.PROXY_ARP && sameSubnet) {
                                            cmds.add("ip neigh replace proxy $ip dev $wifiIface")
                                            activeProxyArpWifi.add(ip)
                                        }
                                        RootShell.execute(*cmds.toTypedArray())
                                    }
                                }
                            }
                        }
                    }
                    Thread.sleep(1200)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }.apply { start() }
    }

    fun stopBridge(wifiIface: String, ethIface: String) {
        monitoring = false
        monitorThread?.interrupt()
        monitorThread = null

        packetLogThread?.interrupt()
        packetLogThread = null
        tcpdumpProcess?.destroy()
        tcpdumpProcess = null

        dhcpServer?.stopServer()
        dhcpServer = null

        val cmds = mutableListOf(
            "ip rule del pref 89",
            "ip rule del pref 90",
			"ip rule del pref 98",
            "ip rule del pref 99",
            "ip rule del pref 100",
            "ip rule del pref 101",
            "ip rule del pref 102",
            "ip rule del pref 103",
            "ip rule del pref 104",
            "ip rule del pref 105",
            "ip route flush table 200",
            "iptables -t raw -D PREROUTING -i $ethIface -j LOG --log-prefix \"NB_RX \" --log-level 4",
            "iptables -t mangle -D POSTROUTING -o $ethIface -j LOG --log-prefix \"NB_TX \" --log-level 4",
            "iptables -D FORWARD -i $ethIface -o $wifiIface -j ACCEPT",
            "iptables -D FORWARD -i $wifiIface -o $ethIface -j ACCEPT",
            "iptables -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu",
            "iptables -D INPUT -i $ethIface -p udp --dport 67 -j ACCEPT",
            "iptables -t nat -D POSTROUTING -o $wifiIface -j MASQUERADE",
            "ip addr flush dev $ethIface",
            "ip route flush table main dev $ethIface",
            "echo 0 > /proc/sys/net/ipv4/conf/$wifiIface/proxy_arp",
            "echo 0 > /proc/sys/net/ipv4/conf/$ethIface/proxy_arp",
            "echo 0 > /proc/sys/net/ipv4/conf/$ethIface/proxy_arp_pvlan"
        )

        for (ip in activeProxyArpEth) {
            cmds.add("ip neigh del proxy $ip dev $ethIface")
        }
        activeProxyArpEth.clear()

        for (ip in activeProxyArpWifi) {
            cmds.add("ip neigh del proxy $ip dev $wifiIface")
        }
        activeProxyArpWifi.clear()

        if (activeDhcpServerIp.isNotEmpty()) {
            cmds.add("iptables -t nat -D POSTROUTING -o $ethIface -p udp --sport 6767 --dport 68 -j SNAT --to-source $activeDhcpServerIp:67")
            activeDhcpServerIp = ""
        }
        if (activeDnsDnatTarget.isNotEmpty() && activeEthIpOnly.isNotEmpty()) {
            cmds.add("iptables -t nat -D PREROUTING -i $ethIface -d $activeEthIpOnly -p udp --dport 53 -j DNAT --to-destination $activeDnsDnatTarget:53")
            cmds.add("iptables -t nat -D PREROUTING -i $ethIface -d $activeEthIpOnly -p tcp --dport 53 -j DNAT --to-destination $activeDnsDnatTarget:53")
            activeDnsDnatTarget = ""
        }

        activeWifiGateway = ""
        activeEthIpOnly = ""

        RootShell.execute(*cmds.toTypedArray())
    }

    private fun logErrorsIfAny(tag: String, res: RootShell.Result) {
        if (res.exitCode != 0 && res.stderr.isNotEmpty()) {
            logCallback("[$tag Warning] ${res.stderr.joinToString(" | ")}")
        }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.split(".").map { it.toInt() }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun intToIp(ip: Int): String {
        return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
    }

    private fun maskToPrefix(maskStr: String): Int {
        if (!isValidIpv4(maskStr)) return 24
        val mask = ipToInt(maskStr)
        var prefix = 0
        for (i in 31 downTo 0) {
            if (((mask ushr i) and 1) == 1) prefix++ else break
        }
        return prefix
    }

    private fun suggestHostInSubnet(routerIp: String, prefix: Int): String {
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val net = ipToInt(routerIp) and mask
        val candidate = net or 100
        return if (intToIp(candidate) == routerIp) intToIp(net or 101) else intToIp(candidate)
    }

    private fun suggestGatewayInSubnet(guestIp: String, prefix: Int): String {
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val net = ipToInt(guestIp) and mask
        val candidate = net or 1
        return if (intToIp(candidate) == guestIp) intToIp(net or 254) else intToIp(candidate)
    }

    private fun computeNetworkCidr(hostCidr: String): String {
        val ip = hostCidr.substringBefore("/")
        val prefix = hostCidr.substringAfter("/", "24").toIntOrNull() ?: 24
        if (!isValidIpv4(ip)) return ""
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val net = ipToInt(ip) and mask
        return "${intToIp(net)}/$prefix"
    }

    private fun isIpInCidr(ip: String, cidr: String): Boolean {
        if (!isValidIpv4(ip) || !cidr.contains("/")) return false
        val netIp = cidr.substringBefore("/")
        val prefix = cidr.substringAfter("/").toIntOrNull() ?: return false
        if (!isValidIpv4(netIp)) return false
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (ipToInt(ip) and mask) == (ipToInt(netIp) and mask)
    }
}