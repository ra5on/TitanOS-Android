package io.github.ra5on.titanos.settings

/** Antworten des nachgebauten NAS für die Gruppe „Netzwerk“ (Formen wie titand -dev, Geheimnisse nur als Platzhalter). */
object NetworkFixtures {
    private const val TOPOLOGY = """{"networks":[
        {"id":"admin","name":"LAN","purpose":"management","parent":"bond:uplink","vlan":0,"bridge":true,"management":true,"ipv4":"dhcp","address":"","gateway":"","ipv6":"auto","address6":"","gateway6":"","dns":[],"future":"bleibt"},
        {"id":"net2","name":"Speicher","purpose":"storage","parent":"enp3s0","vlan":20,"bridge":false,"management":false,"ipv4":"static","address":"10.20.0.5/24","gateway":"","ipv6":"off","address6":"","gateway6":"","dns":["10.20.0.1"],"routes":[{"destination":"10.30.0.0/16","gateway":"10.20.0.1","metric":100}]}
      ],
      "bonds":[{"id":"uplink","ports":["enp1s0","enp2s0"],"mode":"active-backup"}],
      "hostname":"titan","ports":[],"bondMode":"","vlan":0,"bridge":false,"dhcp":true,"address":"","gateway":"","dns":[]}"""

    private const val PORTS = """[{"name":"enp1s0","mac":"52:54:00:e5:b9:49","carrier":true,"speedMbps":1000},{"name":"enp2s0","mac":"52:54:00:e5:b9:4a","carrier":true,"speedMbps":2500},{"name":"enp3s0","mac":"52:54:00:e5:b9:4b","carrier":false,"speedMbps":0}]"""

    private const val LIVE = """{"admin":{"device":"br0","up":true,"addresses":["192.168.178.129/24","fdd7:88a7:bf64::5/64"]},"net2":{"device":"tv20","up":false,"addresses":[]}}"""

    val network = """{"bondModes":["active-backup","802.3ad","balance-alb"],"timezone":"Europe/Berlin","network":{
        "config":{"hostname":"titan","ports":[],"bondMode":"","vlan":0,"bridge":false,"dhcp":true,"address":"","gateway":"","dns":[]},
        "configured":true,"bridgeReady":true,"pending":null,"pendingSeconds":0,"interfaces":$PORTS,
        "device":"br0","addresses":["192.168.178.129/24"],"gateway":"192.168.178.1","dns":["192.168.178.1"],
        "topology":$TOPOLOGY,"pendingTopology":null,"live":$LIVE,"vlans":true,"vlansInUse":true}}"""

    /** Eine Änderung auf Probe, die die Adresse des Verwaltungsnetzes ändert. */
    val networkPending = """{"bondModes":["active-backup","802.3ad","balance-alb"],"timezone":"Europe/Berlin","network":{
        "config":{"hostname":"titan","ports":[],"bondMode":"","vlan":0,"bridge":false,"dhcp":true,"address":"","gateway":"","dns":[]},
        "configured":true,"bridgeReady":true,"pending":{"hostname":"titan","ports":[],"bondMode":"","vlan":0,"bridge":false,"dhcp":true,"address":"","gateway":"","dns":[]},"pendingSeconds":97,"interfaces":$PORTS,
        "device":"br0","addresses":["192.168.178.129/24"],"gateway":"192.168.178.1","dns":["192.168.178.1"],
        "topology":$TOPOLOGY,
        "pendingTopology":{"networks":[{"id":"admin","name":"LAN","purpose":"management","parent":"bond:uplink","vlan":0,"bridge":true,"management":true,"ipv4":"static","address":"192.168.178.20/24","gateway":"192.168.178.1","ipv6":"auto","address6":"","gateway6":"","dns":["192.168.178.1"]}],"bonds":[{"id":"uplink","ports":["enp1s0","enp2s0"],"mode":"active-backup"}],"hostname":"titan"},
        "live":$LIVE,"vlans":true,"vlansInUse":true}}"""

    const val plugins = """{"plugins":[{"name":"tailscale","installed":true},{"name":"netbird","installed":false}]}"""

    const val tailscale = """{"settings":{"routes":["192.168.178.0/24"],"advertiseExit":false,"exitNode":"","acceptRoutes":true,"acceptDNS":false},
        "active":true,"readOnly":false,"state":"Running","version":"1.84.0","name":"titan","ips":["100.101.102.103","fd7a:115c:a1e0::1"],
        "exitNodes":[{"id":"n1","name":"router","ip":"100.64.0.2","online":true}],"health":[],
        "https":{"name":"titan.beispiel.ts.net","state":"ok","enabled":true,"notAfter":"2027-01-20T10:00:00Z"}}"""

    const val diagnostics = """{"checks":[{"name":"Link","ok":true,"detail":"br0 ist verbunden"},{"name":"Gateway","ok":false,"detail":"192.168.178.1 antwortet nicht auf Ping"}]}"""
}
