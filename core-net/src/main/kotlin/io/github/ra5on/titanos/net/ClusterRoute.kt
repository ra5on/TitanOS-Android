package io.github.ra5on.titanos.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Anfragen an einen anderen Knoten des Clusters über das gekoppelte NAS, wie
 * das Web (lib/cluster-node.ts `apiPath`): `/api/v1/X` wird
 * `/api/v1/cluster/nodes/<node>/api/X`. Anmeldung, eigenes Konto, Darstellung,
 * der Cluster selbst, Agent, Mail, Kalender und Kontakte bleiben bei diesem
 * NAS, ebenso alle Routen der App (`/api/v1/app/…`: Kopplung, Sitzung,
 * Mitteilungen, Foto-Sicherung, Abgleich). Das NAS prüft das Recht selbst
 * (Konto des Knotens, `handleClusterProxy`).
 */
class ClusterRoute(private val node: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val routed = route(path, node) ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().url(request.url.newBuilder().encodedPath(routed).build()).build())
    }

    companion object {
        private const val PREFIX = "/api/v1"

        /** Pfade, die immer dieses NAS meinen (die Liste LOCAL des Webs und die App-Routen). */
        private val LOCAL = listOf(
            Regex("^/session(/|$)"),
            Regex("^/login"),
            Regex("^/logout"),
            Regex("^/setup"),
            Regex("^/preferences"),
            Regex("^/account(/|$)"),
            Regex("^/cluster(/|$)"),
            Regex("^/background"),
            Regex("^/login-appearance"),
            Regex("^/app-icon"),
            Regex("^/restore"),
            Regex("^/welcome"),
            Regex("^/agent(/|$)"),
            Regex("^/mail(/|$)"),
            Regex("^/calendar(/|$)"),
            Regex("^/contacts(/|$)"),
            Regex("^/app(/|$)"),
        )

        /** Der Pfad am Knoten, oder null, wenn [path] bei diesem NAS bleibt. */
        fun route(path: String, node: String): String? {
            if (node.isEmpty() || !path.startsWith("$PREFIX/")) return null
            val rest = path.removePrefix(PREFIX)
            if (LOCAL.any { it.containsMatchIn(rest) }) return null
            return "$PREFIX/cluster/nodes/${java.net.URLEncoder.encode(node, "UTF-8").replace("+", "%20")}/api$rest"
        }
    }
}
