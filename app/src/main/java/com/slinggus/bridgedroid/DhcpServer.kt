package com.slinggus.bridgedroid

import android.content.Context
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class DhcpServer(
    private val context: Context,
    private val ethMac: String,
    private val serverIp: String = "192.168.44.1",
    private val offerIp: String = "192.168.44.100",
    private val subnetMask: String = "255.255.255.0",
    private val routerIp: String = "192.168.44.1",
    private val dnsServer: String = "1.1.1.1",
    private val onLog: (String) -> Unit
) : Thread() {

    @Volatile
    private var running = false
    private var rootProcess: Process? = null

    override fun run() {
        running = true
        try {
            val apkPath = context.packageCodePath
            val safeEthMac = if (ethMac.isNotEmpty()) ethMac else "none"
            val cmd = "CLASSPATH=\"$apkPath\" /system/bin/app_process /system/bin " +
                "com.slinggus.bridgedroid.RootDhcpMain $safeEthMac $serverIp $offerIp $subnetMask $routerIp $dnsServer"

            val pb = ProcessBuilder("su", "-c", cmd)
            pb.redirectErrorStream(true)
            rootProcess = pb.start()

            val reader = BufferedReader(InputStreamReader(rootProcess!!.inputStream))
            while (running) {
                val line = reader.readLine() ?: break
                onLog(line)
            }
        } catch (e: Exception) {
            if (running) onLog("DHCP Server error: ${e.message}")
        }
    }

    fun stopServer() {
        running = false
        rootProcess?.destroy()
        rootProcess = null
        RootShell.execute("pkill -f com.slinggus.bridgedroid.RootDhcpMain")
    }
}

object RootDhcpMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true)
        if (args.size < 6) {
            out.println("DHCP Server error: missing arguments")
            return
        }

        val ignoreMac = args[0]
        val serverIp = args[1]
        val offerIp = args[2]
        val subnetMask = args[3]
        val routerIp = args[4]
        val dnsServer = args[5]

        try {
            val recvSocket = DatagramSocket(67, InetAddress.getByName("0.0.0.0"))
            recvSocket.broadcast = true

            val sendSocket = DatagramSocket(6767, InetAddress.getByName(serverIp))
            sendSocket.broadcast = true

            out.println("DHCP Server ready on :67 (Reply via $serverIp -> Offer=$offerIp, Mask=$subnetMask, GW=$routerIp, DNS=$dnsServer)")

            val buffer = ByteArray(1024)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                recvSocket.receive(packet)

                if (packet.length < 240) continue

                val data = packet.data
                val op = data[0].toInt()
                if (op != 1) continue

                val xid = ByteBuffer.wrap(data, 4, 4).int
                val clientMac = data.copyOfRange(28, 34)
                val macStr = clientMac.joinToString(":") { "%02x".format(it) }

                if (ignoreMac != "none" && macStr.equals(ignoreMac, ignoreCase = true)) {
                    continue
                }

                var msgType = 0
                var idx = 240
                while (idx < packet.length - 1) {
                    val opt = data[idx].toInt() and 0xFF
                    if (opt == 255) break
                    val len = data[idx + 1].toInt() and 0xFF
                    if (opt == 53 && len >= 1) {
                        msgType = data[idx + 2].toInt() and 0xFF
                        break
                    }
                    idx += 2 + len
                }

                when (msgType) {
                    1 -> {
                        out.println("[PKT RX] DHCP DISCOVER from $macStr (${packet.length}B)")
                        sendReply(sendSocket, serverIp, offerIp, subnetMask, routerIp, dnsServer, xid, clientMac, isAck = false)
                        out.println("[PKT TX] DHCP OFFER -> $offerIp (Mask=$subnetMask, GW=$routerIp, DNS=$dnsServer)")
                    }
                    3 -> {
                        out.println("[PKT RX] DHCP REQUEST from $macStr (${packet.length}B)")
                        sendReply(sendSocket, serverIp, offerIp, subnetMask, routerIp, dnsServer, xid, clientMac, isAck = true)
                        out.println("[PKT TX] DHCP ACK -> $offerIp")
                    }
                }
            }
        } catch (e: Exception) {
            out.println("DHCP Server stopped: ${e.message}")
        }
    }

    private fun sendReply(
        socket: DatagramSocket,
        serverIp: String,
        offerIp: String,
        subnetMask: String,
        routerIp: String,
        dnsServer: String,
        xid: Int,
        mac: ByteArray,
        isAck: Boolean
    ) {
        val resp = ByteArray(300)
        resp[0] = 2
        resp[1] = 1
        resp[2] = 6
        resp[3] = 0

        ByteBuffer.wrap(resp, 4, 4).putInt(xid)

        val ipParts = offerIp.split(".").map { it.toInt().toByte() }.toByteArray()
        System.arraycopy(ipParts, 0, resp, 16, 4)

        val srvParts = serverIp.split(".").map { it.toInt().toByte() }.toByteArray()
        System.arraycopy(srvParts, 0, resp, 20, 4)

        System.arraycopy(mac, 0, resp, 28, 6)

        resp[236] = 99.toByte()
        resp[237] = 130.toByte()
        resp[238] = 83.toByte()
        resp[239] = 99.toByte()

        var o = 240

        resp[o++] = 53; resp[o++] = 1; resp[o++] = if (isAck) 5.toByte() else 2.toByte()

        resp[o++] = 54; resp[o++] = 4
        System.arraycopy(srvParts, 0, resp, o, 4); o += 4

        val maskParts = subnetMask.split(".").map { it.toInt().toByte() }.toByteArray()
        resp[o++] = 1; resp[o++] = 4
        System.arraycopy(maskParts, 0, resp, o, 4); o += 4

        val routerParts = routerIp.split(".").map { it.toInt().toByte() }.toByteArray()
        resp[o++] = 3; resp[o++] = 4
        System.arraycopy(routerParts, 0, resp, o, 4); o += 4

        val dnsParts = dnsServer.split(".").map { it.toInt().toByte() }.toByteArray()
        resp[o++] = 6; resp[o++] = 4
        System.arraycopy(dnsParts, 0, resp, o, 4); o += 4

        resp[o++] = 51; resp[o++] = 4
        ByteBuffer.wrap(resp, o, 4).putInt(86400); o += 4

        resp[o++] = 255.toByte()

        val replyPacket = DatagramPacket(
            resp,
            o,
            InetAddress.getByName("255.255.255.255"),
            68
        )
        socket.send(replyPacket)
    }
}