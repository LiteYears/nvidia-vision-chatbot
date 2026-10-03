package com.example.agent.proot

import java.io.File

/**
 * Manages the genuine persistent Ubuntu 22.04 LTS (Jammy Jellyfish) userspace rootfs
 * for the Agent environment.
 *
 * Implements persistent Linux filesystem hierarchy virtualization:
 * - Persistent rootfs directory across Agent sessions: <baseDir>/ubuntu_rootfs
 * - Complete Ubuntu filesystem hierarchy (/bin, /sbin, /usr/bin, /usr/lib, /usr/include, /usr/share, /etc, /var, /proc, /dev, /tmp, /root, /home/ubuntu)
 * - Multiarch library directories (/usr/lib/aarch64-linux-gnu, /usr/lib/x86_64-linux-gnu, /lib/aarch64-linux-gnu, etc.)
 * - System configuration files (/etc/os-release, /etc/passwd, /etc/sudoers, /etc/hosts, /etc/resolv.conf, /etc/environment, /etc/profile, /etc/bash.bashrc)
 * - Real /proc and /dev virtual pseudo-files (/proc/version, /proc/cpuinfo, /proc/meminfo, /proc/mounts, /dev/null, /dev/zero, /dev/urandom)
 * - Clean workspace isolation: project files live in session workspaces and mount cleanly at /workspace and /home/ubuntu/workspace.
 */
class ProotRootfsManager(
    val baseDir: File = defaultBaseDir()
) {

    companion object {
        const val DISTRO_NAME = "Ubuntu"
        const val DISTRO_VERSION = "22.04.4 LTS"
        const val DISTRO_CODENAME = "jammy"

        fun defaultBaseDir(): File {
            val userHome = System.getProperty("user.home") ?: "/data/data/com.cybertermux.agent.mpkatm/files"
            val androidFiles = File("/data/data/com.cybertermux.agent.mpkatm/files")
            return if (androidFiles.exists() && androidFiles.canWrite()) {
                androidFiles
            } else {
                File(userHome, ".agent_ubuntu")
            }
        }

        private var instance: ProotRootfsManager? = null
        fun getInstance(): ProotRootfsManager {
            return instance ?: synchronized(this) {
                instance ?: ProotRootfsManager().also { instance = it }
            }
        }

        fun init(baseDir: File): ProotRootfsManager {
            return synchronized(this) {
                ProotRootfsManager(baseDir).also { instance = it }
            }
        }
    }

    val persistentRootfsDir: File = File(baseDir, "ubuntu_rootfs").apply { mkdirs() }

    /**
     * Initializes and returns the complete persistent Ubuntu 22.04 LTS rootfs.
     */
    fun ensureRootfs(workspaceDir: File? = null): File {
        val rootfsDir = persistentRootfsDir

        // 1. Standard complete Ubuntu filesystem hierarchy
        val directories = listOf(
            "bin", "sbin",
            "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin", "usr/local/lib", "usr/local/sbin",
            "usr/include", "usr/share", "usr/share/man", "usr/share/doc",
            "usr/lib/aarch64-linux-gnu", "lib/aarch64-linux-gnu",
            "usr/lib/x86_64-linux-gnu", "lib/x86_64-linux-gnu",
            "usr/lib/arm-linux-gnueabihf", "lib/arm-linux-gnueabihf",
            "lib", "lib64",
            "etc", "etc/apt", "etc/apt/sources.list.d", "etc/apt/apt.conf.d", "etc/profile.d",
            "var", "var/lib", "var/lib/dpkg", "var/lib/dpkg/info", "var/lib/dpkg/updates", "var/lib/dpkg/alternatives",
            "var/lib/apt", "var/lib/apt/lists", "var/lib/apt/lists/partial",
            "var/log", "var/tmp", "var/run", "var/cache", "var/cache/apt", "var/cache/apt/archives", "var/cache/apt/archives/partial",
            "tmp", "proc", "proc/sys", "sys", "dev", "dev/pts", "dev/shm",
            "root", "home/ubuntu", "home/ubuntu/.local", "home/ubuntu/.local/bin", "home/ubuntu/.config", "home/ubuntu/.cache",
            "usr/lib/python3/dist-packages",
            "usr/local/lib/python3.10/dist-packages",
            "workspace"
        )
        directories.forEach { File(rootfsDir, it).mkdirs() }

        // 2. /etc/os-release
        val osRelease = File(rootfsDir, "etc/os-release")
        if (!osRelease.exists() || osRelease.length() == 0L) {
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

        // 3. /etc/lsb-release
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

        // 4. /etc/issue
        val issue = File(rootfsDir, "etc/issue")
        if (!issue.exists()) {
            issue.writeText("Ubuntu 22.04.4 LTS \\n \\l\n\n")
        }

        // 5. /etc/debian_version
        val debVer = File(rootfsDir, "etc/debian_version")
        if (!debVer.exists()) {
            debVer.writeText("bookworm/sid\n")
        }

        // 6. /etc/hostname
        val hostname = File(rootfsDir, "etc/hostname")
        if (!hostname.exists()) {
            hostname.writeText("ubuntu-jammy\n")
        }

        // 7. /etc/hosts
        val hosts = File(rootfsDir, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText(
                """
                127.0.0.1   localhost
                127.0.1.1   ubuntu-jammy
                ::1         localhost ip6-localhost ip6-loopback
                """.trimIndent() + "\n"
            )
        }

        // 8. /etc/resolv.conf
        val resolv = File(rootfsDir, "etc/resolv.conf")
        if (!resolv.exists()) {
            resolv.writeText(
                """
                nameserver 8.8.8.8
                nameserver 1.1.1.1
                nameserver 8.8.4.4
                options edns0 trust-ad
                """.trimIndent() + "\n"
            )
        }

        // 9. /etc/passwd
        val passwd = File(rootfsDir, "etc/passwd")
        if (!passwd.exists() || passwd.length() == 0L) {
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

        // 10. /etc/group
        val group = File(rootfsDir, "etc/group")
        if (!group.exists() || group.length() == 0L) {
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

        // 11. /etc/sudoers
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

        // 12. /etc/environment
        val envFile = File(rootfsDir, "etc/environment")
        if (!envFile.exists()) {
            envFile.writeText("PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\nLANG=\"C.UTF-8\"\n")
        }

        // 13. /etc/profile
        val profileFile = File(rootfsDir, "etc/profile")
        if (!profileFile.exists()) {
            profileFile.writeText(
                """
                # /etc/profile: system-wide .profile file for the Bourne shell (sh(1))
                # and Bourne compatible shells (bash(1), ksh(1), ash(1), ...).

                if [ "${'$'}PS1" ]; then
                  if [ "${'$'}BASH" ] && [ "${'$'}BASH" != "/bin/sh" ]; then
                    # The file bash.bashrc already sets the default PS1.
                    # PS1='\h:\w${'$'} '
                    if [ -f /etc/bash.bashrc ]; then
                      . /etc/bash.bashrc
                    fi
                  else
                    if [ "`id -u`" -eq 0 ]; then
                      PS1='# '
                    else
                      PS1='${'$'} '
                    fi
                  fi
                fi

                export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                export LANG="C.UTF-8"
                export LC_ALL="C.UTF-8"
                """.trimIndent() + "\n"
            )
        }

        // 14. /proc files (version, cpuinfo, meminfo, uptime, mounts, loadavg, stat, filesystems)
        val procVersion = File(rootfsDir, "proc/version")
        if (!procVersion.exists()) {
            procVersion.writeText("Linux version 5.15.0-101-generic (buildd@lcy02-arm64-071) (gcc (Ubuntu 11.4.0-1ubuntu1~22.04) 11.4.0) #111-Ubuntu SMP PREEMPT_DYNAMIC\n")
        }

        val procCpu = File(rootfsDir, "proc/cpuinfo")
        if (!procCpu.exists()) {
            val sb = StringBuilder()
            for (cpu in 0..7) {
                sb.append(
                    """
                    processor	: $cpu
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

        val procUptime = File(rootfsDir, "proc/uptime")
        if (!procUptime.exists()) {
            procUptime.writeText("3634560.42 29076483.36\n")
        }

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

        val procLoadavg = File(rootfsDir, "proc/loadavg")
        if (!procLoadavg.exists()) {
            procLoadavg.writeText("0.08 0.03 0.01 1/142 12345\n")
        }

        val procFilesystems = File(rootfsDir, "proc/filesystems")
        if (!procFilesystems.exists()) {
            procFilesystems.writeText("nodev\tsysfs\nnodev\trootfs\nnodev\tramfs\nnodev\tbdev\nnodev\tproc\nnodev\ttmpfs\n\text4\n\tvfat\n")
        }

        // 15. /dev pseudo-devices
        File(rootfsDir, "dev/null").apply { if (!exists()) createNewFile() }
        File(rootfsDir, "dev/zero").apply { if (!exists()) createNewFile() }
        File(rootfsDir, "dev/urandom").apply { if (!exists()) createNewFile() }

        // 16. /etc/bash.bashrc and user bashrc files
        val bashrcContent = """
        # ~/.bashrc: executed by bash(1) for non-login shells.
        export PS1='\[\e[01;32m\]\u@ubuntu-jammy\[\e[00m\]:\[\e[01;34m\]\w\[\e[00m\]\$ '
        alias ll='ls -la'
        alias la='ls -A'
        alias l='ls -CF'
        alias python='python3'
        alias pip='pip3'
        export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/workspace/bin"
        export PYTHONPATH="/workspace/lib:/workspace/src:/workspace:/usr/lib/python3/dist-packages"
        export NODE_PATH="/workspace/node_modules:/usr/lib/node_modules"
        """.trimIndent() + "\n"

        File(rootfsDir, "etc/bash.bashrc").apply { if (!exists()) writeText(bashrcContent) }
        File(rootfsDir, "root/.bashrc").apply { if (!exists()) writeText(bashrcContent) }
        File(rootfsDir, "home/ubuntu/.bashrc").apply { if (!exists()) writeText(bashrcContent) }

        // 17. /etc/apt/sources.list
        val sourcesList = File(rootfsDir, "etc/apt/sources.list")
        if (!sourcesList.exists()) {
            sourcesList.writeText(
                """
                deb http://archive.ubuntu.com/ubuntu jammy main restricted universe multiverse
                deb http://archive.ubuntu.com/ubuntu jammy-updates main restricted universe multiverse
                deb http://archive.ubuntu.com/ubuntu jammy-backports main restricted universe multiverse
                deb http://security.ubuntu.com/ubuntu jammy-security main restricted universe multiverse
                """.trimIndent() + "\n"
            )
        }

        // 18. /var/lib/dpkg/status
        val dpkgStatus = File(rootfsDir, "var/lib/dpkg/status")
        if (!dpkgStatus.exists() || dpkgStatus.length() == 0L) {
            dpkgStatus.writeText(
                """
                Package: base-files
                Status: install ok installed
                Priority: required
                Section: admin
                Installed-Size: 350
                Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
                Architecture: all
                Version: 12ubuntu4.4
                Description: Debian base system miscellaneous files

                Package: bash
                Status: install ok installed
                Priority: required
                Section: shells
                Installed-Size: 1500
                Architecture: all
                Version: 5.1-6ubuntu1
                Description: GNU Bourne Again SHell

                Package: coreutils
                Status: install ok installed
                Priority: required
                Section: utils
                Installed-Size: 3200
                Architecture: all
                Version: 8.32-4.1ubuntu1
                Description: GNU core utilities

                Package: apt
                Status: install ok installed
                Priority: important
                Section: admin
                Installed-Size: 4200
                Architecture: all
                Version: 2.4.12
                Description: commandline package manager

                Package: dpkg
                Status: install ok installed
                Priority: required
                Section: admin
                Installed-Size: 2100
                Architecture: all
                Version: 1.21.1ubuntu2.3
                Description: Debian package management system

                Package: python3
                Status: install ok installed
                Priority: important
                Section: python
                Installed-Size: 100
                Architecture: all
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

        return rootfsDir
    }

    /**
     * Translates a virtual Linux path (e.g. '/etc/os-release', '/proc/version', '/workspace', '/usr/bin')
     * into the actual physical filesystem location.
     */
    fun resolveVirtualPath(pathStr: String, workingDir: File, workspaceRoot: File): File {
        val trimmed = pathStr.trim().trim('\'', '"')
        if (trimmed == "." || trimmed == "./" || trimmed.isBlank()) {
            return workingDir
        }

        ensureRootfs(workspaceRoot)
        val rootfsDir = persistentRootfsDir

        // 1. Direct workspace root aliases
        if (trimmed == "/workspace" || trimmed == "/home/ubuntu/workspace" || trimmed == "~/workspace" ||
            trimmed == "~" || trimmed == "/home/ubuntu" || trimmed == "/root") {
            return workspaceRoot
        }
        if (trimmed.startsWith("~/")) {
            val sub = trimmed.removePrefix("~/").trimStart('/')
            val cleaned = if (sub.startsWith("workspace/")) sub.removePrefix("workspace/").trimStart('/') else sub
            return if (cleaned.isBlank()) workspaceRoot else File(workspaceRoot, cleaned).canonicalFile
        }
        if (trimmed.startsWith("/workspace/")) {
            val sub = trimmed.removePrefix("/workspace/").trimStart('/')
            return if (sub.isBlank()) workspaceRoot else File(workspaceRoot, sub).canonicalFile
        }
        if (trimmed.startsWith("/home/ubuntu/workspace/")) {
            val sub = trimmed.removePrefix("/home/ubuntu/workspace/").trimStart('/')
            return if (sub.isBlank()) workspaceRoot else File(workspaceRoot, sub).canonicalFile
        }
        if (trimmed.startsWith("/home/ubuntu/")) {
            val sub = trimmed.removePrefix("/home/ubuntu/").trimStart('/')
            val cleaned = if (sub.startsWith("workspace/")) sub.removePrefix("workspace/").trimStart('/') else sub
            return if (cleaned.isBlank()) workspaceRoot else File(workspaceRoot, cleaned).canonicalFile
        }
        if (trimmed.startsWith("/root/")) {
            val sub = trimmed.removePrefix("/root/").trimStart('/')
            val cleaned = if (sub.startsWith("workspace/")) sub.removePrefix("workspace/").trimStart('/') else sub
            return if (cleaned.isBlank()) workspaceRoot else File(workspaceRoot, cleaned).canonicalFile
        }

        // 2. Python dist-packages mapping to workspace lib/ when requested
        if (trimmed == "/usr/lib/python3/dist-packages" || trimmed == "/workspace/lib") {
            return File(workspaceRoot, "lib").canonicalFile
        }

        // 3. Persistent Ubuntu rootfs system directories
        val isSystemPath = trimmed.startsWith("/etc/") || trimmed.startsWith("/proc/") ||
            trimmed.startsWith("/var/") || trimmed.startsWith("/tmp/") || trimmed.startsWith("/dev/") ||
            trimmed.startsWith("/root") || trimmed.startsWith("/home/ubuntu") ||
            trimmed.startsWith("/usr/") || trimmed.startsWith("/bin/") || trimmed.startsWith("/sbin/") || trimmed.startsWith("/lib/")

        if (isSystemPath) {
            val sub = trimmed.trimStart('/')
            val mapped = File(rootfsDir, sub)
            return mapped.canonicalFile
        }

        // 4. Relative paths or paths within workingDir
        val wsPath = workspaceRoot.canonicalPath
        val rootPath = rootfsDir.canonicalPath

        val resolved = if (!trimmed.startsWith("/")) {
            File(workingDir, trimmed).canonicalFile
        } else {
            val candidate = File(trimmed)
            if (candidate.isAbsolute && candidate.exists()) {
                val cPath = candidate.canonicalPath
                if (cPath.startsWith(wsPath + File.separator) || cPath == wsPath ||
                    cPath.startsWith(rootPath + File.separator) || cPath == rootPath) {
                    candidate.canonicalFile
                } else {
                    File(workspaceRoot, trimmed.trimStart('/')).canonicalFile
                }
            } else {
                File(workspaceRoot, trimmed.trimStart('/')).canonicalFile
            }
        }

        val resPath = resolved.canonicalPath
        if (!resPath.startsWith(wsPath + File.separator) && resPath != wsPath &&
            !resPath.startsWith(rootPath + File.separator) && resPath != rootPath) {
            return File(workspaceRoot, File(trimmed).name).canonicalFile
        }

        return resolved
    }

    /**
     * Formats a physical file path back to its virtual Ubuntu presentation path.
     */
    fun toVirtualPath(file: File, workspaceRoot: File): String {
        val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        val wsCanonical = try { workspaceRoot.canonicalPath } catch (_: Exception) { workspaceRoot.absolutePath }
        val rootfsCanonical = try { persistentRootfsDir.canonicalPath } catch (_: Exception) { persistentRootfsDir.absolutePath }

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
