package fr.oeildelynx

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.UUID

/**
 * Identité « caméra » du téléphone pour le direct : identifiant, secret partagé, nom et adresses publiables.
 * Tant qu’aucun appairage n’a été créé, aucun port n’est ouvert par l’application.
 */
internal object LiveCamera {
    private const val KEY_ID = "live_id"
    private const val KEY_SECRET = "live_key"
    private const val KEY_NAME = "live_name"
    private const val KEY_CUSTOM_HOST = "live_custom_host"
    private const val KEY_PUBLIC_IP = "live_public_ip"
    private const val KEY_BOX_STATUS = "live_box_status"
    private const val IFA_F_TEMPORARY = 0x01

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("lynx", Context.MODE_PRIVATE)

    fun isPaired(context: Context) = prefs(context).contains(KEY_SECRET)

    fun secret(context: Context): ByteArray? = prefs(context).getString(KEY_SECRET, null)?.let {
        try { Base64.decode(it, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { null }
    }

    /** Crée l’identité au premier appairage. */
    fun ensurePaired(context: Context) {
        if (isPaired(context)) return
        regenerate(context)
    }

    /** Nouvelle clé : tous les téléphones spectateurs existants perdent l’accès. */
    fun regenerate(context: Context) {
        prefs(context).edit()
            .putString(KEY_ID, UUID.randomUUID().toString())
            .putString(KEY_SECRET, Base64.encodeToString(LiveProtocol.newKey(), Base64.NO_WRAP))
            .apply()
    }

    fun id(context: Context): String = prefs(context).getString(KEY_ID, null) ?: ""
    fun name(context: Context): String = prefs(context).getString(KEY_NAME, null) ?: (Build.MODEL ?: "Caméra")
    fun setName(context: Context, name: String) = prefs(context).edit().putString(KEY_NAME, name.trim().ifBlank { Build.MODEL }).apply()

    /** Nom d’hôte DynDNS / adresse fixe optionnel, saisi par l’utilisateur. */
    fun customHost(context: Context): String = prefs(context).getString(KEY_CUSTOM_HOST, "") ?: ""
    fun setCustomHost(context: Context, host: String) = prefs(context).edit().putString(KEY_CUSTOM_HOST, host.trim()).apply()

    fun publicIp(context: Context): String? = prefs(context).getString(KEY_PUBLIC_IP, null)
    fun boxStatus(context: Context): String? = prefs(context).getString(KEY_BOX_STATUS, null)

    /** Résultat de l’interrogation de la box. */
    class BoxResult(val publicIp: String?, val mapped: Boolean, val upnpFound: Boolean, val localIp: String?, val message: String)

    /**
     * Interroge la box en UPnP (bloquant : à appeler hors du thread principal).
     * Avec [openPort], demande aussi la redirection du port du direct vers ce téléphone.
     */
    fun probeBox(context: Context, openPort: Boolean): BoxResult {
        val port = LiveProtocol.DEFAULT_PORT
        val result = try {
            val gateway = LiveUpnp.discover()
            if (gateway == null) {
                BoxResult(null, false, false, null,
                    "Box sans UPnP : ouvre manuellement le port TCP $port vers ce téléphone dans l’interface de la box.")
            } else {
                val publicIp = try { LiveUpnp.externalIp(gateway) } catch (_: Exception) { null }
                var localIp: String? = null
                var mapped = false
                var error: String? = null
                if (openPort) {
                    try {
                        localIp = mapWithFallbacks(gateway, port)
                        mapped = true
                    } catch (failure: Exception) { error = failure.message }
                }
                val message = when {
                    publicIp != null && LiveUpnp.isPrivateIpv4(publicIp) ->
                        "La box a une adresse privée ($publicIp) : l’accès depuis Internet est impossible en IPv4 (double NAT ou IP partagée par l’opérateur). Le direct reste possible sur le même Wi-Fi et parfois en IPv6."
                    !openPort -> "Box compatible UPnP : le port $port sera ouvert automatiquement pendant la surveillance."
                    mapped -> "Port $port ouvert sur la box vers ce téléphone ($localIp)."
                    else -> "La box a refusé l’ouverture du port $port (${error ?: "erreur inconnue"}). Active l’UPnP dans la box ou crée la redirection à la main."
                }
                BoxResult(publicIp, mapped, true, localIp, message)
            }
        } catch (error: Exception) {
            BoxResult(null, false, false, null, "Box injoignable (${error.message ?: "réseau indisponible"}).")
        }
        prefs(context).edit().apply {
            val ip = result.publicIp?.takeUnless { LiveUpnp.isPrivateIpv4(it) }
            if (ip != null) putString(KEY_PUBLIC_IP, ip)
            // Pendant la surveillance, garde le résultat de l’ouverture du port faite par le service.
            if (openPort || !prefs(context).getBoolean("monitoring_active", false)) putString(KEY_BOX_STATUS, result.message)
        }.apply()
        return result
    }

    private fun mapWithFallbacks(gateway: LiveUpnp.Gateway, port: Int): String = try {
        LiveUpnp.addPortMapping(gateway, port, port, 0)
    } catch (error: LiveUpnp.UpnpError) {
        when (error.code) {
            // Conflit : ancienne redirection vers l’ancienne IP locale de ce téléphone ; la remplacer.
            718 -> { runCatching { LiveUpnp.deletePortMapping(gateway, port) }; LiveUpnp.addPortMapping(gateway, port, port, 0) }
            // Certaines box refusent les redirections permanentes.
            else -> LiveUpnp.addPortMapping(gateway, port, port, 86_400)
        }
    }

    fun closeBoxPort(context: Context) {
        prefs(context).edit().remove(KEY_BOX_STATUS).apply()
        try { LiveUpnp.discover(1500)?.let { LiveUpnp.deletePortMapping(it, LiveProtocol.DEFAULT_PORT) } } catch (_: Exception) { }
    }

    /** Adresse(s) locale(s) : IPv4 du Wi-Fi et IPv6 globales (souvent joignables directement depuis Internet). */
    fun localAddresses(context: Context): Pair<List<String>, List<String>> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val ipv4 = mutableListOf<String>(); val ipv6Stable = mutableListOf<String>(); val ipv6Temporary = mutableListOf<String>()
        val networks = connectivity.allNetworks.filter { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@filter false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }.ifEmpty { listOfNotNull(connectivity.activeNetwork) }
        for (network in networks) {
            val properties = connectivity.getLinkProperties(network) ?: continue
            for (link in properties.linkAddresses) {
                val address = link.address
                val text = address.hostAddress?.substringBefore('%') ?: continue
                when {
                    address is Inet4Address && !address.isLoopbackAddress -> ipv4 += text
                    address is Inet6Address && !address.isLinkLocalAddress && !address.isLoopbackAddress &&
                        !address.isSiteLocalAddress && (address.address[0].toInt() and 0xfe) != 0xfc -> {
                        if (link.flags and IFA_F_TEMPORARY != 0) ipv6Temporary += text else ipv6Stable += text
                    }
                }
            }
        }
        return ipv4.distinct() to (ipv6Stable + ipv6Temporary).distinct().take(2)
    }

    /** Adresses publiées dans le QR code et rafraîchies à chaque connexion d’un spectateur. */
    fun candidates(context: Context): List<String> {
        val port = LiveProtocol.DEFAULT_PORT
        val (ipv4, ipv6) = localAddresses(context)
        val hosts = mutableListOf<String>()
        customHost(context).takeIf { it.isNotBlank() }?.let { hosts += if (PairingCode.parseHost(it) != null) it else PairingCode.formatHost(it, port) }
        ipv4.forEach { hosts += PairingCode.formatHost(it, port) }
        publicIp(context)?.let { hosts += PairingCode.formatHost(it, port) }
        ipv6.forEach { hosts += PairingCode.formatHost(it, port) }
        return hosts.distinct()
    }

    fun pairingCode(context: Context): PairingCode? {
        val key = secret(context) ?: return null
        return PairingCode(id(context), name(context), key, candidates(context))
    }
}
