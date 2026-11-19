<p align="center">
  <img src="BridgeDroidHQ.png" width="200" />
</p>

<h1 align="center">BridgeDroid</h1>
<p align="center">Screw it, connect a PS2 to your phone. Why not?</p>

<br>

**Turn your rooted Android device into a proper WiFi-to-Ethernet bridge!**

### But why? Isn't there already an Ethernet tethering option in modern Android?<br>

Yeah, but it doesn't allow you to access LAN. Also, this works all the way back to Android 7.

## Usage<br>

Be sure to give the app root permissions. In most use cases. it's as simple as plugging in the device to your phone over a USB Ethernet dongle, connecting to a WiFi network, then turning it on.<br>

## Troubleshooting<br>

### Error: Interface 'x' not found!<br>

Press the "Interfaces" button and look for your dongle, most likely starting with eth or usb, and put it in "Eth Iface"<br>

### My interface does not appear!<br>

Make sure your device is properly connected. Also, ensure Ethernet is enabled. (Might be in Connections -> More connection settings -> Ethernet or similar.)

### [Guard] is blocking an IP in logs.<br>

You probably set the IP for the Ethernet device in settings, or the IP is already taken. Manually set a different IP, or go to your Ethernet settings, clear the IP and switch to DHCP.

