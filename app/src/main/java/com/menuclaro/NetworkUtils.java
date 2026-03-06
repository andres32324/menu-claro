package com.menuclaro;

import android.content.Context;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class NetworkUtils {

    public static String getLocalIP(Context context) {
        String wifiIp = null;
        String tailscaleIp = null;

        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface net = nets.nextElement();
                if (!net.isUp() || net.isLoopback()) continue;

                Enumeration<InetAddress> addrs = net.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof java.net.Inet4Address)) continue;
                    if (addr.isLoopbackAddress()) continue;

                    String ip = addr.getHostAddress();
                    String name = net.getName().toLowerCase();

                    // Tailscale usa interfaz "tun0" o "utun" o IPs 100.x.x.x
                    if (name.contains("tun") || ip.startsWith("100.")) {
                        tailscaleIp = ip;
                    } else {
                        wifiIp = ip;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Prioridad: Tailscale > WiFi
        if (tailscaleIp != null) return tailscaleIp;
        if (wifiIp != null) return wifiIp;
        return "Sin conexión";
    }
}
