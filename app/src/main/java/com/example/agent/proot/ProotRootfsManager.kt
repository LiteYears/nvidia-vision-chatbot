package com.example.agent.proot

import java.io.File

/**
 * Manages the virtual Ubuntu 22.04 LTS (Jammy Jellyfish) PRoot rootfs structure
 * inside the agent workspace sandbox.
 *
 * Implements user-space filesystem virtualization:
 * - /etc (os-release, lsb-release, issue, passwd, group, sudoers, hostname, hosts, resolv.conf)
 * - /proc (version, cpuinfo, meminfo, uptime)
 * - /home/ubuntu and /root (home directories)
 * - /tmp, /var, /usr/bin, /usr/lib/python3/dist-packages
 * - /workspace (mount point for user project files)
 * - Virtual path translation (e.g. /etc/os-release -> <workspace>/.rootfs/etc/os-release)
 */
class ProotRootfsManager {

    companion object {
        const val ROOTFS_DIR = ".rootfs"
        const val DISTRO_NAME = "Ubuntu"
        const val DISTRO_VERSION = "22.04.4 LTS"
        const val DISTRO_CODENAME = "jammy"

        private var instance: ProotRootfsManager? = null
        fun getInstance(): ProotRootfsManager {
            return instance ?: synchronized(this) {
                instance ?: ProotRootfsManager().also { instance = it }
            }
        }
    }

    /**
     * Initializes the complete Ubuntu 22.04 LTS rootfs files inside the workspace.
     */
    fun ensureRootfs(workspaceDir: File): File {
        val rootfsDir = File(workspaceDir, ROOTFS_DIR).apply { mkdirs() }

        // Standard Ubuntu hierarchy
        listOf(
            "bin", "sbin", "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin", "usr/local/lib",
            "etc", "etc/apt", "etc/apt/sources.list.d",
            "var/lib/dpkg", "var/log", "var/tmp", "var/cache/apt/archives",
            "tmp", "proc", "sys", "dev",
            "root", "home/ubuntu",
            "usr/lib/python3/dist-packages",
            "usr/local/lib/python3.11/dist-packages"
        ).forEach { File(rootfsDir, it).mkdirs() }

        // Symlink / link workspace lib to python dist-packages
        val libDir = File(workspaceDir, "lib").apply { mkdirs() }

        // 1. /etc/os-release
        val osRelease = File(rootfsDir, "etc/os-release")
        if (!osRelease.exists()) {
            osRelease.writeText(
                """
                PRETTY_NAME="Ubuntu 22.04.4 LTS"
                NAME="Ubuntu"
                VERSION_ID="22.04"
                VERSION="22.04.4 LTS (Jammy Jellyfish)"
                VERSION_CODENAME=jammy
                ID=ubuntu
                ID_LIKE=debian
                HOME_URL="https://www.ubuntu.com/"
                SUPPORT_URL="https://help.ubuntu.com/"
                BUG_REPORT_URL="https://bugs.launchpad.net/ubuntu/"
                PRIVACY_POLICY_URL="https://www.ubuntu.com/legal/terms-and-policies/privacy-policy"
                UBUNTU_CODENAME=jammy
                """.trimIndent() + "\n"
            )
        }

        // 2. /etc/lsb-release
        val lsbRelease = File(rootfsDir, "etc/lsb-release")
        if (!lsbRelease.exists()) {
            lsbRelease.writeText(
                """
                DISTRIB_ID=Ubuntu
                DISTRIB_RELEASE=22.04
                DISTRIB_CODENAME=jammy
                DISTRIB_DESCRIPTION="Ubuntu 22.04.4 LTS"
                """.trimIndent() + "\n"
            )
        }

        // 3. /etc/issue
        val issue = File(rootfsDir, "etc/issue")
        if (!issue.exists()) {
            issue.writeText("Ubuntu 22.04.4 LTS \\n \\l\n\n")
        }

        // 4. /etc/debian_version
        val debVer = File(rootfsDir, "etc/debian_version")
        if (!debVer.exists()) {
            debVer.writeText("bookworm/sid\n")
        }

        // 5. /etc/hostname
        val hostname = File(rootfsDir, "etc/hostname")
        if (!hostname.exists()) {
            hostname.writeText("ubuntu-proot\n")
        }

        // 6. /etc/hosts
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText(
                """
                127.0.0.1   localhost
                127.0.1.1   ubuntu-proot
                ::1         localhost ip6-localhost ip6-loopback
                """.trimIndent() + "\n"
            )
        }

        // 7. /etc/resolv.conf
        val resolv = File(rootfsDir, "etc/resolv.conf")
        if (!resolv.exists()) {
            resolv.writeText(
                """
                nameserver 8.8.8.8
                nameserver 1.1.1.1
                """.trimIndent() + "\n"
            )
        }

        // 8. /etc/passwd
        val passwd = File(rootfsDir, "etc/passwd")
        if (!passwd.exists()) {
            passwd.writeText(
                """
                root:x:0:0:root:/root:/bin/bash
                daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin
                bin:x:2:2:bin:/bin:/usr/sbin/nologin
                sys:x:3:3:sys:/dev:/usr/sbin/nologin
                sync:x:4:65534:sync:/bin:/bin/sync
                games:x:5:60:games:/usr/games:/usr/sbin/nologin
                man:x:6:12:man:/var/cache/man:/usr/sbin/nologin
                lp:x:7:7:lp:/var/spool/lpd:/usr/sbin/nologin
                mail:x:8:8:mail:/var/mail:/usr/sbin/nologin
                news:x:9:9:news:/var/spool/news:/usr/sbin/nologin
                uucp:x:10:10:uucp:/var/spool/uucp:/usr/sbin/nologin
                proxy:x:13:13:proxy:/bin:/usr/sbin/nologin
                www-data:x:33:33:www-data:/var/www:/usr/sbin/nologin
                backup:x:34:34:backup:/var/backups:/usr/sbin/nologin
                list:x:38:38:Mailing List Manager:/var/list:/usr/sbin/nologin
                irc:x:39:39:ircd:/run/ircd:/usr/sbin/nologin
                gnats:x:41:41:Gnats Bug-Reporting System (admin):/var/lib/gnats:/usr/sbin/nologin
                nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin
                ubuntu:x:1000:1000:Ubuntu User,,,:/home/ubuntu:/bin/bash
                """.trimIndent() + "\n"
            )
        }

        // 9. /etc/group
        val group = File(rootfsDir, "etc/group")
        if (!group.exists()) {
            group.writeText(
                """
                root:x:0:
                daemon:x:1:
                bin:x:2:
                sys:x:3:
                adm:x:4:ubuntu
                tty:x:5:
                disk:x:6:
                lp:x:7:
                mail:x:8:
                news:x:9:
                uucp:x:10:
                man:x:12:
                proxy:x:13:
                kmem:x:15:
                dialout:x:20:ubuntu
                fax:x:21:
                voice:x:22:
                cdrom:x:24:ubuntu
                floppy:x:25:
                tape:x:26:
                sudo:x:27:ubuntu
                audio:x:29:ubuntu
                dip:x:30:ubuntu
                www-data:x:33:
                backup:x:34:
                operator:x:37:
                src:x:40:
                gnats:x:41:
                shadow:x:42:
                utmp:x:43:
                video:x:44:ubuntu
                sasl:x:45:
                plugdev:x:46:ubuntu
                staff:x:50:
                games:x:60:
                users:x:100:
                nogroup:x:65534:
                ubuntu:x:1000:
                """.trimIndent() + "\n"
            )
        }

        // 10. /etc/sudoers
        val sudoers = File(rootfsDir, "etc/sudoers")
        if (!sudoers.exists()) {
            sudoers.writeText(
                """
                Defaults	env_reset
                Defaults	mail_badpass
                Defaults	secure_path="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                root	ALL=(ALL:ALL) ALL
                %admin ALL=(ALL) ALL
                %sudo	ALL=(ALL:ALL) NOPASSWD:ALL
                ubuntu ALL=(ALL:ALL) NOPASSWD:ALL
                """.trimIndent() + "\n"
            )
        }

        // 11. /etc/environment
        val envFile = File(rootfsDir, "etc/environment")
        if (!envFile.exists()) {
            envFile.writeText("PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n")
        }

        // 12. /proc/version
        val procVersion = File(rootfsDir, "proc/version")
        if (!procVersion.exists()) {
            procVersion.writeText("Linux version 5.15.0-101-generic (buildd@lcy02-amd64-071) (gcc (Ubuntu 11.4.0-1ubuntu1~22.04) 11.4.0, GNU ld 2.38) #111-Ubuntu SMP PREEMPT_DYNAMIC\n")
        }

        // 13. /proc/cpuinfo
        val procCpu = File(rootfsDir, "proc/cpuinfo")
        if (!procCpu.exists()) {
            val sb = StringBuilder()
            for (cpu in 0..7) {
                sb.append(
                    """
                    processor	: $cpu
                    model name	: ARMv8 Processor rev 4 (v8l)
                    BogoMIPS	: 38.40
                    Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp
                    CPU implementer	: 0x51
                    CPU architecture: 8
                    CPU variant	: 0x7
                    CPU part	: 0x803
                    CPU revision	: 1

                    """.trimIndent() + "\n"
                )
            }
            procCpu.writeText(sb.toString())
        }

        // 14. /proc/meminfo
        val procMem = File(rootfsDir, "proc/meminfo")
        if (!procMem.exists()) {
            procMem.writeText(
                """
                MemTotal:        8192000 kB
                MemFree:         4096000 kB
                MemAvailable:    5896000 kB
                Buffers:          128000 kB
                Cached:          1920000 kB
                SwapCached:            0 kB
                Active:          2048000 kB
                Inactive:        1536000 kB
                SwapTotal:       2097152 kB
                SwapFree:        2097152 kB
                """.trimIndent() + "\n"
            )
        }

        // 15. /proc/uptime
        val procUptime = File(rootfsDir, "proc/uptime")
        if (!procUptime.exists()) {
            procUptime.writeText("3634560.42 29076483.36\n")
        }

        // 16. /etc/apt/sources.list
        val sourcesList = File(rootfsDir, "etc/apt/sources.list")
        if (!sourcesList.exists()) {
            sourcesList.writeText(
                """
                deb http://archive.ubuntu.com/ubuntu jammy main restricted universe multiverse
                deb http://archive.ubuntu.com/ubuntu jammy-updates main restricted universe multiverse
                deb http://security.ubuntu.com/ubuntu jammy-security main restricted universe multiverse
                """.trimIndent() + "\n"
            )
        }

        // 17. /var/lib/dpkg/status
        val dpkgStatus = File(rootfsDir, "var/lib/dpkg/status")
        if (!dpkgStatus.exists()) {
            dpkgStatus.writeText(
                """
                Package: base-files
                Status: install ok installed
                Priority: required
                Section: admin
                Installed-Size: 350
                Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
                Architecture: amd64
                Version: 12ubuntu4.4
                Description: Debian base system miscellaneous files

                Package: bash
                Status: install ok installed
                Priority: required
                Section: shells
                Installed-Size: 1500
                Architecture: amd64
                Version: 5.1-6ubuntu1
                Description: GNU Bourne Again SHell

                Package: python3
                Status: install ok installed
                Priority: important
                Section: python
                Installed-Size: 100
                Architecture: amd64
                Version: 3.10.6-1~22.04
                Description: interactive high-level object-oriented language (default python3 version)

                Package: python3-pip
                Status: install ok installed
                Priority: optional
                Section: python
                Installed-Size: 1200
                Architecture: all
                Version: 22.0.2+dfsg-1ubuntu0.4
                Description: Python package installer
                """.trimIndent() + "\n"
            )
        }

        // 18. /proc/mounts
        val procMounts = File(rootfsDir, "proc/mounts")
        if (!procMounts.exists()) {
            procMounts.writeText(
                """
                /dev/root / ext4 rw,relatime 0 0
                proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0
                sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0
                tmpfs /dev tmpfs rw,nosuid,size=4096k,nr_inodes=1024,mode=755 0 0
                tmpfs /tmp tmpfs rw,nosuid,nodev,relatime 0 0
                workspace /workspace 9p rw,relatime 0 0
                """.trimIndent() + "\n"
            )
        }

        // 19. /proc/loadavg
        val procLoadavg = File(rootfsDir, "proc/loadavg")
        if (!procLoadavg.exists()) {
            procLoadavg.writeText("0.08 0.03 0.01 1/142 12345\n")
        }

        // 20. /dev/null
        val devNull = File(rootfsDir, "dev/null")
        if (!devNull.exists()) {
            devNull.createNewFile()
        }

        // 21. /etc/bash.bashrc, /root/.bashrc, /home/ubuntu/.bashrc
        val bashrcContent = """
        # ~/.bashrc: executed by bash(1) for non-login shells.
        export PS1='\[\e[01;32m\]\u@ubuntu-proot\[\e[00m\]:\[\e[01;34m\]\w\[\e[00m\]\$ '
        alias ll='ls -la'
        alias la='ls -A'
        alias l='ls -CF'
        alias python='python3'
        alias pip='pip3'
        export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        export PYTHONPATH="/workspace/lib:/workspace/src:/workspace"
        """.trimIndent() + "\n"

        val etcBashrc = File(rootfsDir, "etc/bash.bashrc")
        if (!etcBashrc.exists()) etcBashrc.writeText(bashrcContent)

        val rootBashrc = File(rootfsDir, "root/.bashrc")
        if (!rootBashrc.exists()) rootBashrc.writeText(bashrcContent)

        val userBashrc = File(rootfsDir, "home/ubuntu/.bashrc")
        if (!userBashrc.exists()) userBashrc.writeText(bashrcContent)

        return rootfsDir
    }

    /**
     * Translates a virtual Linux path (e.g. '/etc/os-release', '/proc/version', '/tmp', '/workspace')
     * into the actual workspace file location.
     */
    fun resolveVirtualPath(pathStr: String, workingDir: File, workspaceRoot: File): File {
        val trimmed = pathStr.trim()

        // 1. Direct workspace path or relative path
        if (trimmed == "." || trimmed == "./" || trimmed.isBlank()) {
            return workingDir
        }
        if (!trimmed.startsWith("/")) {
            return File(workingDir, trimmed).canonicalFile
        }

        val rootfsDir = File(workspaceRoot, ROOTFS_DIR)

        // 2. Absolute /workspace or /home/ubuntu/workspace
        if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
            val sub = trimmed.removePrefix("/workspace").trimStart('/')
            return if (sub.isBlank()) workspaceRoot else File(workspaceRoot, sub).canonicalFile
        }
        if (trimmed == "/home/ubuntu/workspace" || trimmed.startsWith("/home/ubuntu/workspace/")) {
            val sub = trimmed.removePrefix("/home/ubuntu/workspace").trimStart('/')
            return if (sub.isBlank()) workspaceRoot else File(workspaceRoot, sub).canonicalFile
        }

        // 3. /usr/lib/python3/dist-packages or /usr/local/lib/... -> workspace lib/
        if (trimmed.contains("python3/dist-packages") || trimmed.contains("python3.11/dist-packages") || trimmed.contains("site-packages")) {
            val sub = trimmed.substringAfter("packages").trimStart('/')
            val libDir = File(workspaceRoot, "lib")
            return if (sub.isBlank()) libDir else File(libDir, sub).canonicalFile
        }

        // 4. Virtual PRoot system directories mapped inside .rootfs/
        val relFromRoot = trimmed.trimStart('/')
        val mappedFile = File(rootfsDir, relFromRoot)
        if (mappedFile.exists() || relFromRoot.startsWith("etc/") || relFromRoot.startsWith("proc/") ||
            relFromRoot.startsWith("var/") || relFromRoot.startsWith("tmp/") || relFromRoot.startsWith("dev/") ||
            relFromRoot.startsWith("root") || relFromRoot.startsWith("home/")
        ) {
            return mappedFile.canonicalFile
        }

        // Default to workspace root if no match
        val candidate = File(workspaceRoot, relFromRoot)
        return candidate.canonicalFile
    }

    /**
     * Formats an actual workspace path back to its virtual PRoot presentation path.
     */
    fun toVirtualPath(file: File, workspaceRoot: File): String {
        val canonical = file.canonicalPath
        val wsCanonical = workspaceRoot.canonicalPath
        val rootfsCanonical = File(workspaceRoot, ROOTFS_DIR).canonicalPath

        return when {
            canonical == wsCanonical -> "/workspace"
            canonical.startsWith("$rootfsCanonical/") -> {
                "/" + canonical.removePrefix("$rootfsCanonical/").trimStart('/')
            }
            canonical.startsWith("$wsCanonical/") -> {
                "/workspace/" + canonical.removePrefix("$wsCanonical/").trimStart('/')
            }
            else -> canonical
        }
    }
}
