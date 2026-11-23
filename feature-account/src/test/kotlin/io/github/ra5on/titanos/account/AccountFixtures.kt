package io.github.ra5on.titanos.account

/**
 * Antworten eines NAS für „Mein Konto“ in den Formen von titand (geholt vom
 * Test-NAS, Werte ausgedacht). Geheimnisse sind offensichtliche Platzhalter.
 */
fun FakeNas.account(role: String = "owner", twoFactor: Boolean = false) {
    on("GET session", """{"configAvailable":true,"setupRequired":false,"user":{"name":"alex","role":"$role","twoFactor":$twoFactor},"hostname":"titan","version":"1.18"}""")
    on(
        "GET account/sessions",
        """{"sessions":[
            {"id":"s1","created":"2026-11-21T08:10:00+01:00","lastSeen":"2026-11-21T18:13:00+01:00","remoteIP":"192.168.1.40","userAgent":"TitanOS-Android/titanos Pixel 9","current":true,"kept":true,"device":"app1"},
            {"id":"s2","created":"2026-11-20T18:08:00+01:00","lastSeen":"2026-11-21T07:10:00+01:00","remoteIP":"192.168.1.23","userAgent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36","current":false,"kept":false},
            {"id":"s3","created":"2026-11-19T09:00:00+01:00","lastSeen":"2026-11-19T09:30:00+01:00","remoteIP":"100.101.102.103","userAgent":"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Safari/604.1","current":false,"kept":true,"until":"2026-12-19T09:00:00+01:00"}
        ]}""",
    )
    on("GET account/picture", """{"builtin":"bergsee"}""")
    on("GET account/passkeys", """{"passkeys":[{"id":"pk1","name":"Pixel 9","rpId":"titan.example.org","created":"2026-10-02T10:00:00Z","synced":true}],"rpId":"titan.example.org","usable":true}""")
    on("GET account/ssh-keys", """{"keys":[{"id":"k1","type":"ssh-ed25519","fingerprint":"SHA256:beispielAbdruckBeispielAbdruckBeispiel0","comment":"alex@laptop"}],"mayLogin":true,"sshEnabled":true}""")
    on("GET system", """{"hostname":"titan","network":{"ipv4":"192.168.1.10/24"}}""")
    on("GET account/webdav", """{"enabled":true,"set":true,"created":"2026-11-01T12:00:00Z"}""")
    on("GET account/devices", """{"devices":[{"id":"d1","name":"iPhone von Alex","created":"2026-11-10T10:00:00Z","lastSync":null,"lastKind":""}],"max":20}""")
    on("GET account/apps", """{"apps":[{"id":"app1","name":"Pixel 9","app":"titanos","created":"2026-11-18T10:00:00Z"},{"id":"app2","name":"Tablet","app":"agent","created":"2026-11-19T10:00:00Z","expires":"2026-12-19T10:00:00Z"}]}""")
    on("GET preferences", """{"sound":{"tone":"chime","volume":40,"severity":"all","later":1},"bell":{"minSeverity":"warning","groups":{"usb":false},"later":2},"appearance":{"theme":"graphit"}}""")
    on(
        "GET account/notifications",
        """{"delivery":{"ntfy":{"time":"2026-11-21T07:00:00Z","ok":true,"attempts":1}},"mailServer":true,"secretSet":{"ntfy":true,"telegram":false,"discord":false},
           "settings":{"language":"de","email":{"enabled":true,"to":"alex@example.org"},"ntfy":{"enabled":true,"url":"https://ntfy.sh/titan-beispiel"},"telegram":{"enabled":false,"chatID":""},"discord":{"enabled":false},"homeAssistant":{"enabled":false,"service":""},
           "events":{"backups":true,"storage":true,"system":true,"updates":false},"content":"summary","quiet":{"enabled":true,"from":"22:00","to":"07:00"},"origin":"https://titan.example.org","persistQuiet":false,"fallback":"","future":{"x":1}}}""",
    )
    on("GET homeassistant/notify-services", """{"services":["mobile_app_pixel_9"],"connections":[{"id":"main","name":""}]}""")
    on("GET agent/protect/me", """{"alerts":0,"emergency":false,"guard":false,"handbook":false}""")
    on("GET notifications/push", """{"publicKey":"pk_beispiel","devices":[{"id":"p1","name":"Firefox · Linux","service":"mozilla","app":"titanos","created":"2026-11-01T10:00:00Z","off":["camera"]}]}""")
    on("GET services/state", """{"mail":true,"calendar":true,"contacts":true}""")
    on("GET nvr/cameras", """{"cameras":[{"id":"haustuer","name":"Haustür"}]}""")
    on("POST account/2fa/begin", """{"secret":"JBSWY3DPEHPK3PXP","uri":"otpauth://totp/TitanOS:alex?secret=JBSWY3DPEHPK3PXP&issuer=TitanOS"}""")
    on(
        "POST account/apps/pair",
        """{"id":"pair1","code":"code_beispiel","manualCode":"ABCD-EFGH","expires":"2026-11-21T19:00:00Z","link":"titanos://pair?v=1&c=code_beispiel&a=https%3A%2F%2F192.168.1.10%3A443!c3BraV9iZWlzcGllbF9hYmNkZWZnaGlqa2xtbm9w",
           "addresses":[{"server":"https://192.168.1.10:443","kind":"lan","certificate":"selfSigned","spki":"c3BraV9iZWlzcGllbF9hYmNkZWZnaGlqa2xtbm9w"},{"server":"https://titan.example.org","kind":"domain","certificate":"valid","spki":""}]}""",
    )
    on("GET account/apps/pair/pair1", """{"state":"waiting","expires":"2026-11-21T19:00:00Z"}""")
}
