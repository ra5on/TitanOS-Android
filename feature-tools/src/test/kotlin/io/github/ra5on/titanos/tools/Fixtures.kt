package io.github.ra5on.titanos.tools

import io.github.ra5on.titanos.admin.testing.FakeNas

/**
 * Nachgebaute Antworten für die Werkzeuge (aus scripts/test-ui-taskmanager.mjs,
 * test-ui-operations.mjs, test-ui-logs-harmless.mjs, test-ui-hwcheck-*.mjs und
 * den Typen des Webs). Keine echten Platten, keine Passwörter.
 */
object Fixtures {
    const val NOW = "2026-11-21T10:00:00Z"

    val storage = """
    {"volumes":[
      {"name":"Daten","level":"raid1","filesystem":"ext4","mounted":true,"members":[{"disk":"sda","state":"active"},{"disk":"sdb1","state":"active"}]},
      {"name":"Schnell","level":"single","filesystem":"btrfs","mounted":true,"members":[{"disk":"nvme0n1p1","state":"active"}]},
      {"name":"Archiv","level":"single","filesystem":"xfs","mounted":true,"members":[{"disk":"sdc","state":"active"}]}],
     "disks":[
      {"name":"sda","model":"WD Red Plus 8TB","serial":"WD-AAA1","sizeBytes":8000000000000,"kind":"hdd","role":"member","volume":"Daten","mounted":true,"smart":{"temperatureC":34}},
      {"name":"sdb","model":"WD Red Plus 8TB","serial":"WD-AAA2","sizeBytes":8000000000000,"kind":"hdd","role":"member","volume":"Daten","mounted":true,"smart":{"temperatureC":35}},
      {"name":"sdc","model":"Seagate IronWolf 12TB","serial":"ZL-0001","sizeBytes":12000000000000,"kind":"hdd","role":"member","volume":"Archiv","mounted":true,"smart":{"temperatureC":29}},
      {"name":"nvme0n1","model":"Samsung 990 Pro","serial":"S7-0001","sizeBytes":2000000000000,"kind":"nvme","role":"member","volume":"Schnell","mounted":true,"smart":{"temperatureC":41}},
      {"name":"sdd","model":"Toshiba MG10 20TB","serial":"TO-0002","sizeBytes":20000000000000,"kind":"hdd","role":"free","mounted":false,"testing":false},
      {"name":"sde","model":"Crucial MX500","serial":"CT-0003","sizeBytes":1000000000000,"kind":"ssd","role":"used","mounted":false,"testing":false,"contents":["ext4 · alte-daten"]},
      {"name":"sdf","model":"SanDisk Ultra","serial":"","sizeBytes":32000000000,"kind":"usb","role":"stick","mounted":true}],
     "diskSleep":{"diskStatus":[{"name":"sda","state":"active"},{"name":"sdb","state":"active"},{"name":"sdc","state":"asleep"}]}}
    """.trimIndent()

    val maintenance = """
    {"settings":{"backupsFirst":true,"enabled":true,"volume":"Daten","timezone":"Europe/Berlin","windowFrom":"01:00","windowTo":"06:00","jobs":[
      {"id":"j1","dependsOn":"","name":"RAID-Prüfung Daten","kind":"raid-check","target":"Daten","enabled":true,"every":"monthly","weekdays":[0],"monthDay":1,"time":"02:00","missed":"window","readData":5,"maxBytes":64000000,"maxFiles":20,"keep":7,"allowPrune":false},
      {"id":"j2","dependsOn":"j1","name":"SMART lang WD-AAA1","kind":"smart-long","target":"WD-AAA1","enabled":true,"every":"weekly","weekdays":[0,3],"monthDay":1,"time":"03:30","missed":"skip","readData":5,"maxBytes":64000000,"maxFiles":20,"keep":7,"allowPrune":false},
      {"id":"j3","dependsOn":"","name":"Snapshots Schnell","kind":"snapshot","target":"Schnell","enabled":true,"every":"hours","everyHours":6,"weekdays":[0],"monthDay":1,"time":"00:15","missed":"window","readData":5,"maxBytes":64000000,"maxFiles":20,"keep":14,"allowPrune":true,"lockDays":3}]},
     "jobs":[
      {"job":{"id":"j1","name":"RAID-Prüfung Daten","kind":"raid-check","target":"Daten","enabled":true,"every":"monthly","time":"02:00"},"state":{"running":true,"blocked":false,"lastAttempt":"2026-11-21T01:00:00Z","lastOK":"2026-10-01T03:12:00Z","error":"","phase":"check 41 %"},"next":"2026-12-01T01:00:00Z"},
      {"job":{"id":"j2","name":"SMART lang WD-AAA1","kind":"smart-long","target":"WD-AAA1","enabled":true,"every":"weekly","time":"03:30"},"state":{"running":false,"blocked":true,"lastAttempt":null,"lastOK":null,"error":"previous execution outcome is unknown","phase":""},"wait":"review","next":null},
      {"job":{"id":"j3","name":"Snapshots Schnell","kind":"snapshot","target":"Schnell","enabled":true,"every":"hours","time":"00:15"},"state":{"running":false,"blocked":false,"lastAttempt":"2026-11-21T06:15:00Z","lastOK":"2026-11-21T06:15:00Z","error":"","phase":""},"wait":"window","next":"2026-11-21T12:15:00Z"}],
     "history":[
      {"id":"j3","name":"Snapshots Schnell","kind":"snapshot","target":"Schnell","started":"2026-11-21T06:15:00Z","finished":"2026-11-21T06:15:04Z","error":""},
      {"id":"j1","name":"RAID-Prüfung Daten","kind":"raid-check","target":"Daten","started":"2026-11-01T01:00:00Z","finished":"2026-11-01T02:00:00Z","error":"budget exhausted after 3600 s"}],
     "available":true,"error":""}
    """.trimIndent()

    val operations = """
    {"transfers":[
      {"id":"t1","state":"running","from":"Medien/Filme","to":"Archiv/Filme","bytes":200000000000,"copied":84000000000,"current":"Medien/Filme/2025/Ein sehr langer Dateiname eines Films (Director's Cut) 2160p.mkv","error":"","priority":0,"rate":0},
      {"id":"t2","state":"done","from":"Fotos","to":"Archiv/Fotos","bytes":1000,"copied":1000,"failed":3,"error":"3 Dateien konnten nicht gelesen werden","current":""}],
     "maintenance":$maintenance,
     "backups":[
      {"job":{"id":"Daten nach Cloud","destination":{"type":"s3"}},"state":{"running":false,"phase":"","lastOK":"2026-11-20T02:00:00Z","lastError":"connection refused by target","checkError":""},"next":"2026-11-22T02:00:00Z"}],
     "docker":{"immich":{"state":"failed","output":"pulling images\nError: timeout","code":"timeout"}},
     "vms":[],
     "diskTests":[{"disk":"sdd","phase":"read","result":"","percent":37.5}],
     "plugins":[{"id":"p1","plugin":"nvidia","title":"NVIDIA-Treiber","state":"running","steps":[{"id":"signature","state":"done"},{"id":"download","state":"running"}],"bytes":120000000,"total":400000000,"started":"2026-11-21T09:55:00Z"}],
     "uploads":[{"id":"u1","name":"urlaub.mov","size":4000000000,"offset":1000000000,"busy":false}],
     "unavailable":["vm-clones"],
     "growth":{"pending":false,"operation":{}}}
    """.trimIndent()

    val scriptDetail = """{"id":"a1a1a1a1a1a1a1a1","name":"Fotos sichern","description":"rsync der Handyfotos auf das zweite Volume","trigger":"schedule","schedule":{"every":"daily","time":"03:00"},"content":"#!/bin/bash\nrsync -a --delete /mnt/Daten/Fotos/Handy/ /mnt/Archiv/Fotos/Handy/ --exclude '*.tmp' --log-file=/mnt/Daten/.titanos/scripts/fotos.log\n"}"""
    val scriptOutput = """{"running":false,"text":"sending incremental file list\nIMG_2041.jpg\nIMG_2042.jpg\nsent 18,430,112 bytes  received 1,204 bytes  12,287,544.00 bytes/sec\ntotal size is 92,113,442,301  speedup is 4,997.84\n","next":180,"skipped":false,"run":{"trigger":"manual","started":"2026-11-21T09:40:00Z","durationMs":4200,"exit":0,"outcome":"ok"}}"""
    val scriptRuns = """{"runs":[{"trigger":"schedule","started":"2026-11-21T03:00:00Z","durationMs":4200,"exit":0,"outcome":"ok","output":"sending incremental file list\nsent 1,204 bytes\n"},{"trigger":"manual","started":"2026-11-20T18:00:00Z","durationMs":800,"exit":23,"outcome":"failed","error":"exit status 23","output":"rsync error: some files/attrs were not transferred (see previous errors) (code 23) at main.c(1338) [sender=3.2.7]\n","truncated":true}]}"""

    val monitoring = """{"settings":{"enabled":true,"volume":"Daten","days":30,"maxMiB":64},"last":"2026-11-21T09:59:00Z","error":""}"""
    val trends = """[{"volume":"Daten","known":true,"used":5600000000000,"total":8000000000000,"dailyBytes":12000000000,"daysTo90":133.2,"daysToFull":200.4,"warning":false,"hours":700},{"volume":"Schnell","known":true,"used":1850000000000,"total":2000000000000,"dailyBytes":9000000000,"daysTo90":0,"daysToFull":16.7,"warning":true,"hours":700},{"volume":"Archiv","known":false,"used":0,"total":0,"dailyBytes":0,"daysTo90":null,"daysToFull":null,"warning":false,"hours":3}]"""

    fun history(): String {
        val start = java.time.Instant.parse("2026-11-20T10:00:00Z")
        val items = (0 until 48).map { i ->
            val at = start.plusSeconds(i * 1800L + if (i >= 30) 3600 else 0)
            val cpu = 8 + (i * 7) % 40
            val avail = 9_000_000_000L - (i % 12) * 300_000_000L
            """{"at":"$at","gap":${i == 30},"cpu":$cpu,"ramTotal":16000000000,"ramAvailable":$avail,
              "disks":[{"name":"sda","readBps":${(i % 9) * 4_000_000},"writeBps":${(i % 5) * 2_500_000},"latencyMs":${if (i == 20) "null" else (2 + i % 6).toString()}},{"name":"nvme0n1","readBps":${(i % 7) * 30_000_000},"writeBps":${(i % 4) * 10_000_000},"latencyMs":0.3}],
              "volumes":[{"name":"Daten","used":${5_500_000_000_000L + i * 2_000_000_000L},"total":8000000000000},{"name":"Schnell","used":${1_800_000_000_000L + i * 1_000_000_000L},"total":2000000000000}],
              "networks":[{"name":"enp3s0","receive":${(i % 10) * 1_200_000},"send":${(i % 6) * 400_000}}]}"""
        }
        return "[" + items.joinToString(",") + "]"
    }

    val activity = """
    {"entries":[
      {"at":"2026-11-21T09:58:00Z","user":"alex","via":"web","ip":"192.168.1.20","action":"POST /api/v1/files/delete","target":"Medien/Filme/alt/Ein sehr langer Dateiname (2019) 1080p.mkv","status":200,"ok":true},
      {"at":"2026-11-21T09:51:00Z","user":"sam","via":"web","ip":"192.168.1.31","action":"login_failed","ok":false},
      {"at":"2026-11-21T09:40:00Z","user":"alex","via":"token","action":"PUT /api/v1/system/timezone","status":200,"ok":true},
      {"at":"2026-11-21T09:30:00Z","user":"alex","via":"agent","action":"POST /api/v1/docker/containers/{id}/{action}","target":"immich_server","status":500,"ok":false},
      {"at":"2026-11-21T09:12:00Z","user":"","via":"system","action":"session_autolock","ok":true},
      {"at":"2026-11-21T08:00:00Z","user":"alex","via":"ssh","ip":"100.64.0.7","action":"ssh_login","ok":true}],
     "more":true,"stored":true,"status":{"settings":{"enabled":true,"volume":"","days":90,"maxMiB":64},"volume":"Daten","pending":0,"dropped":2,"error":""}}
    """.trimIndent()

    val logs = """
    {"entries":[
      {"time":"2026-11-21T09:50:01Z","priority":6,"source":"titand","message":"maintenance: started RAID-Prüfung Daten (check)"},
      {"time":"2026-11-21T09:50:02Z","priority":6,"source":"kernel","message":"md: data-check of RAID array md127"},
      {"time":"2026-11-21T09:51:10Z","priority":4,"source":"storage","message":"smartd: Device /dev/sda [SAT], 8 Currently unreadable (pending) sectors"},
      {"time":"2026-11-21T09:52:00Z","priority":3,"source":"docker","message":"immich_server: Error response from daemon: failed to create task for container: failed to create shim task: OCI runtime create failed: runc create failed: unable to start container process: error during container init: error mounting \"/mnt/Daten/Fotos\" to rootfs at \"/photos\": no such file or directory"},
      {"time":"2026-11-21T09:53:00Z","priority":3,"source":"kernel","message":"ACPI BIOS Error (bug): Could not resolve symbol [\\_SB.PC00.LPCB.HEC.ECAV], AE_NOT_FOUND (20240827/psargs-332)"},
      {"time":"2026-11-21T09:53:30Z","priority":4,"source":"network","message":"tailscaled: portmapper: failed to get PCP mapping: PCP response not ok, code 8"},
      {"time":"2026-11-21T09:54:00Z","priority":6,"source":"shares","message":"smbd[2211]: connect to service Medien initially as user alex (uid=1000, gid=100) (pid 2211)"},
      {"time":"2026-11-21T09:55:00Z","priority":6,"source":"vms","message":""},
      {"time":"2026-11-21T09:56:00Z","priority":6,"source":"titand","message":"scripts: Fotos sichern finished (ok, 4.2 s)"}],
     "cursor":"s=abc;i=42"}
    """.trimIndent()

    val tests = """
    {"tests":[
      {"disk":"sdd","model":"Toshiba MG10 20TB","serial":"TO-0002","sizeBytes":20000000000000,"mode":"read","phase":"read","percent":37.5,"speedBytesPerSecond":243000000,"remainingSeconds":51200,"errors":0,"started":"2026-11-21T04:00:00Z","finished":null,"result":"","notes":[],"autoResume":true,"persistent":true},
      {"disk":"sdg","model":"WD Red Pro 16TB","serial":"WD-RP-77","sizeBytes":16000000000000,"mode":"write","phase":"verify","percent":62,"speedBytesPerSecond":0,"remainingSeconds":0,"errors":0,"started":"2026-11-19T20:00:00Z","finished":null,"result":"interrupted","notes":[],"autoResume":false,"persistent":true,"resumed":"2026-11-20T08:00:00Z","resumes":1},
      {"disk":"sdh","model":"Seagate Exos 18TB","serial":"ZR-555","sizeBytes":18000000000000,"mode":"read","phase":"done","percent":100,"speedBytesPerSecond":0,"remainingSeconds":0,"errors":2,"started":"2026-11-18T08:00:00Z","finished":"2026-11-19T10:00:00Z","result":"failed","notes":[{"kind":"smart_skipped","detail":"USB bridge"}],"autoResume":false,"persistent":false}],
     "notices":[{"kind":"lost_disk","serial":"WD-OLD-1","model":"WD Green 2TB","mode":"read","started":"2026-11-10T08:00:00Z","time":"2026-11-11T08:00:00Z"}]}
    """.trimIndent()

    private fun proc(pid: Int, name: String, cpu: Double, mem: Long, extra: String = "") =
        """{"pid":$pid,"ppid":1,"name":"$name","command":"/usr/bin/$name","user":"root","state":"S","cpuPercent":$cpu,"memoryBytes":$mem,"start":${pid * 100},"owner":{"kind":""}$extra}"""

    val processes = """{"processes":[
      ${proc(1, "systemd", 0.1, 12_000_000, ""","command":"/sbin/init splash","protected":true""")},
      ${proc(812, "titand", 1.2, 80_000_000, ""","protected":true""")},
      ${proc(1500, "smbd", 0.4, 30_000_000, ""","owner":{"kind":"service","name":"smbd"}""")},
      ${proc(2301, "immich", 23.5, 1_400_000_000, ""","command":"/usr/src/app/immich --port 2283 --workers 4 --media /data/photos/library/originals/2026","owner":{"kind":"docker","id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"immich_server"}""")},
      ${proc(3120, "qemu-system-x86", 41.0, 4_200_000_000, ""","user":"libvirt-qemu","owner":{"kind":"vm","id":"3","name":"win-11"}""")},
      ${proc(4100, "pihole-FTL", 0.8, 60_000_000, ""","owner":{"kind":"lxc","name":"pihole"}""")},
      ${proc(5200, "bash", 0.0, 4_000_000, ""","user":"alex"""")}],
      "cpus":8,"memoryTotalBytes":16000000000,"sampledSeconds":2}"""

    val system = """{"cpu":{"model":"Intel Core i5-12500","threads":12,"usagePercent":23,"threadUsage":[
      {"id":"cpu0","usagePercent":12},{"id":"cpu1","usagePercent":40},{"id":"cpu2","usagePercent":5},{"id":"cpu3","usagePercent":77},
      {"id":"cpu4","usagePercent":3},{"id":"cpu5","usagePercent":20},{"id":"cpu6","usagePercent":9},{"id":"cpu7","usagePercent":55},
      {"id":"cpu8","usagePercent":null},{"id":"cpu9","usagePercent":31},{"id":"cpu10","usagePercent":2},{"id":"cpu11","usagePercent":18}]},
      "memory":{"totalBytes":32000000000,"availableBytes":21000000000},
      "network":{"interface":"enp3s0","state":"up","speedMbps":2500,"rxBytesPerSecond":3200000,"txBytesPerSecond":400000},
      "networks":[{"interface":"enp3s0","state":"up","speedMbps":2500,"rxBytesPerSecond":3200000,"txBytesPerSecond":400000},{"interface":"br0","state":"up","speedMbps":0,"rxBytesPerSecond":1000,"txBytesPerSecond":2000},{"interface":"enp4s0","state":"down","speedMbps":0,"rxBytesPerSecond":0,"txBytesPerSecond":0}],
      "accelerators":[{"address":"0000:00:02.0","name":"Intel UHD 770","usagePercent":14}],
      "disks":[{"name":"sda","readBytesPerSecond":12000000,"writeBytesPerSecond":3000000,"busyPercent":18},{"name":"sdb","readBytesPerSecond":11000000,"writeBytesPerSecond":3000000,"busyPercent":17},{"name":"sdc","readBytesPerSecond":0,"writeBytesPerSecond":0,"busyPercent":0},{"name":"nvme0n1","readBytesPerSecond":80000000,"writeBytesPerSecond":20000000,"busyPercent":30}]}"""

    val docker = """{"running":true,"containers":[
      {"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"immich_server","image":"ghcr.io/immich-app/immich-server:release","state":"running","cpuPercent":23.5,"memoryBytes":1400000000},
      {"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","name":"paperless","image":"paperlessngx/paperless-ngx:latest","state":"exited","cpuPercent":0,"memoryBytes":0}]}"""
    val vms = """{"vms":[{"name":"win-11","displayName":"Windows 11","state":"running","available":true,"cpuPercent":41,"memoryBytes":4200000000,"job":null},{"name":"debian","displayName":"","state":"stopped","available":true,"cpuPercent":0,"memoryBytes":0,"job":null}]}"""
    val lxc = """{"dev":false,"containers":[{"name":"pihole","displayName":"Pi-hole","state":"running","available":true,"cpuPercent":0.8,"memoryBytes":60000000}]}"""

    val hardware = """
    {"generated":"2026-11-21T09:00:00Z","kernel":"6.12.48+deb13-amd64","board":"ASRock B760M Pro RS","boot":"uefi",
     "cpu":{"vendor":"intel","model":"Intel Core i5-12500","threads":12,"cores":6,"level":3,"features":["sse4_2","avx2","pni","x86-64-v3"]},
     "virtualization":{"vendor":"intel","cpuFlag":true,"kvm":true,"disabledByFirmware":false,"iommu":false,"iommuTable":true},
     "memory":{"totalBytes":32000000000,"ecc":false,"corrected":0,"uncorrected":0},
     "controllers":[{"address":"0000:00:17.0","id":"8086:7ae2","name":"Intel Alder Lake SATA AHCI","kind":"sata","driver":"ahci","hints":[]},
       {"address":"0000:01:00.0","id":"1b21:1166","name":"ASMedia ASM1166","kind":"sata","driver":"ahci","hints":[{"code":"asm1166_firmware"}]}],
     "disks":[{"name":"sda","model":"WD Red Plus 8TB","sizeBytes":8000000000000,"transport":"sata","rotational":true,"removable":false,"hints":[]},
       {"name":"sdi","model":"Seagate Barracuda 8TB","sizeBytes":8000000000000,"transport":"sata","rotational":true,"removable":false,"smr":"known_model","hints":[{"code":"smr"}]},
       {"name":"sdj","model":"JMicron USB enclosure","sizeBytes":4000000000000,"transport":"usb","rotational":true,"removable":false,"usb":"152d:0578","usbDriver":"uas","hints":[{"code":"uas_quirk"}]}],
     "network":[{"name":"enp3s0","driver":"r8169","address":"0000:03:00.0","bus":"pci","speedMbs":2500,"link":true},{"name":"enp4s0","driver":"igc","address":"0000:04:00.0","bus":"pci","speedMbs":0,"link":false}],
     "graphics":[{"address":"0000:00:02.0","id":"8086:4690","name":"Intel UHD Graphics 770","driver":"i915"}],
     "sensors":[{"chip":"coretemp-isa-0000","temps":7,"fans":0},{"chip":"nct6798-isa-0290","temps":4,"fans":3}],
     "unbound":[{"bus":"pci","address":"0000:05:00.0","id":"10de:2504","class":"VGA","name":"NVIDIA GeForce RTX 3060","hint":"plugin","plugins":[{"plugin":"nvidia","title":"NVIDIA-Treiber","installed":false}]},
       {"bus":"pci","address":"0000:06:00.0","id":"8086:7a70","class":"Network","name":"Intel Wi-Fi 6E AX211","hint":"driver_failed","driver":"iwlwifi","kernel":"6.13","newKernel":"6.14.2+deb13-amd64","newKernelFixes":true,"messages":["iwlwifi 0000:06:00.0: Direct firmware load for iwlwifi-gl-c0-fm-c0-92.ucode failed with error -2"]},
       {"bus":"usb","address":"1-4","id":"0b05:19af","class":"","name":"ASUS Aura LED Controller","hint":"not_needed"},
       {"bus":"usb","address":"1-7","id":"1234:5678","class":"Vendor","name":"Unbekanntes Gerät","hint":"unclassified"}],
     "firmwareMissing":[{"file":"iwlwifi-gl-c0-fm-c0-92.ucode","device":"0000:06:00.0"}],
     "summary":[{"level":"warn","code":"smr","subject":"sdi"},{"level":"info","code":"iommu_off"}]}
    """.trimIndent()
    val uas = """{"devices":[],"active":[],"pending":[],"writable":true}"""
    val recovery = """{"operation":{"id":"op-20261120-0001","volume":"Daten","job":"Apps nach Cloud","members":[{"name":"immich","kind":"docker","running":true,"phase":"stopped for snapshot"},{"name":"win-11","kind":"vm","running":false,"phase":"paused","error":"domain was not resumed"}]},"active":false,"journalMissing":false}"""
    val clones = """[{"id":"c-77","source":"debian","vm":{"name":"debian-kopie","volume":"Daten"},"phase":"verified","error":""}]"""
    val backups = """{"jobs":[{"job":{"id":"Daten nach Cloud","destination":{"type":"s3"}},"state":{},"next":null},{"job":{"id":"Stick-Kopie","destination":{"type":"usb-copy"}},"state":{},"next":null}]}"""

    /** Liefert alle Antworten der Werkzeuge aus. */
    fun FakeNas.rich(): FakeNas {
        on("GET", "storage", storage)
        on("GET", "maintenance", maintenance)
        on("GET", "operations", operations)
        on("GET", "scripts/a1a1a1a1a1a1a1a1", scriptDetail)
        on("GET", "scripts/a1a1a1a1a1a1a1a1/output*", scriptOutput)
        on("GET", "scripts/a1a1a1a1a1a1a1a1/runs", scriptRuns)
        on("GET", "monitoring", monitoring)
        on("GET", "monitoring/trends", trends)
        on("GET", "monitoring/history*") { history() }
        on("GET", "activity*", activity)
        on("GET", "logs?*", logs)
        // Mitlaufen: seit dem letzten Stand nichts Neues.
        on("GET", "logs?source=&priority=6&lines=500&after*", """{"entries":[],"cursor":"s=abc;i=42"}""")
        on("GET", "storage/tests", tests)
        on("GET", "system", system)
        on("GET", "system/processes", processes)
        on("GET", "docker", docker)
        on("GET", "vms", vms)
        on("GET", "lxc", lxc)
        on("GET", "hardware/report", hardware)
        on("GET", "hardware/uas-quirks", uas)
        on("GET", "backups/application-recovery*", recovery)
        on("GET", "vm-clones/Daten", clones)
        on("GET", "vm-clones/Schnell", "[]")
        on("GET", "vm-clones/Archiv", "[]")
        on("GET", "backups", backups)
        on("GET", "network", """{"timezone":"Europe/Berlin"}""")
        on("GET", "users", """[{"name":"alex"},{"name":"sam"}]""")
        return this
    }
}
