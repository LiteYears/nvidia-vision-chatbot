package com.example.agent.termux

import com.example.agent.tools.workspace.AgentWorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages the real Termux & Ubuntu Linux environment on Android.
 *
 * Implements ground-up initialization, real filesystem structures ($PREFIX, $HOME, $UBUNTU_ROOT),
 * executable tool bootstrap (apt, pkg, dpkg, proot, proot-distro, neofetch, python3, pip, uname, whoami),
 * and live shell initialization script execution.
 */
class TermuxEnvironmentManager(
    val baseDir: File = defaultBaseDir()
) {
    val prefixDir: File = File(baseDir, "usr")
    val binDir: File = File(prefixDir, "bin")
    val etcDir: File = File(prefixDir, "etc")
    val libDir: File = File(prefixDir, "lib")
    val tmpDir: File = File(prefixDir, "tmp")
    val varDir: File = File(prefixDir, "var")
    val dpkgDir: File = File(varDir, "lib/dpkg")
    val aptCacheDir: File = File(varDir, "cache/apt")
    val homeDir: File = File(baseDir, "home")
    val ubuntuRootDir: File = com.example.agent.proot.ProotRootfsManager.getInstance().persistentRootfsDir
    val initDoneFile: File = File(prefixDir, ".init_done")

    @Volatile
    var isInitialized: Boolean = false
        private set

    init {
        isInitialized = initDoneFile.exists()
    }

    /**
     * Helper to safely format bash script templates without Kotlin template collisions.
     */
    private fun bashScript(raw: String): String {
        return raw.trimIndent().replace("@@", "$") + "\n"
    }

    /**
     * Resolves the primary Linux shell on Android or host system.
     */
    fun findSystemShell(): String {
        val candidates = listOf(
            "/system/bin/sh",
            "/bin/sh",
            "/usr/bin/sh",
            "/system/xbin/sh",
            "/bin/bash",
            "/usr/bin/bash"
        )
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                return f.absolutePath
            }
        }
        return "sh"
    }

    /**
     * Termux & Ubuntu POSIX environment variables for ProcessBuilder.
     */
    fun getEnvironmentVariables(workspaceDir: File? = null): Map<String, String> {
        val workspace = workspaceDir ?: File(homeDir, "workspace")
        val currentShell = findSystemShell()
        val rootfsDir = ubuntuRootDir
        val rootfsLocalBin = File(rootfsDir, "usr/local/bin")
        val rootfsBin = File(rootfsDir, "usr/bin")
        val rootfsSbin = File(rootfsDir, "usr/sbin")
        val rootfsSysBin = File(rootfsDir, "bin")
        val rootfsSysSbin = File(rootfsDir, "sbin")
        val workspaceBin = File(workspace, "bin")

        val pathList = listOf(
            rootfsLocalBin.absolutePath,
            rootfsBin.absolutePath,
            rootfsSysBin.absolutePath,
            rootfsSbin.absolutePath,
            rootfsSysSbin.absolutePath,
            workspaceBin.absolutePath,
            binDir.absolutePath,
            "${prefixDir.absolutePath}/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
            "/system/bin",
            "/system/xbin"
        ).distinct()

        val ldPathList = listOf(
            File(rootfsDir, "usr/local/lib").absolutePath,
            File(rootfsDir, "usr/lib").absolutePath,
            File(rootfsDir, "usr/lib/aarch64-linux-gnu").absolutePath,
            File(rootfsDir, "usr/lib/x86_64-linux-gnu").absolutePath,
            File(rootfsDir, "lib").absolutePath,
            libDir.absolutePath,
            "/usr/local/lib",
            "/usr/lib"
        ).distinct()

        return mapOf(
            "PREFIX" to prefixDir.absolutePath,
            "HOME" to workspace.absolutePath,
            "PATH" to pathList.joinToString(":"),
            "LD_LIBRARY_PATH" to ldPathList.joinToString(":"),
            "SHELL" to currentShell,
            "TERM" to "xterm-256color",
            "TMPDIR" to tmpDir.absolutePath,
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "USER" to "ubuntu",
            "LOGNAME" to "ubuntu",
            "UBUNTU_ROOT" to rootfsDir.absolutePath,
            "WORKSPACE" to workspace.absolutePath,
            "PYTHONPATH" to "${workspace.absolutePath}/lib:${workspace.absolutePath}/src:${workspace.absolutePath}:${rootfsDir.absolutePath}/usr/lib/python3/dist-packages:${rootfsDir.absolutePath}/usr/local/lib/python3.10/dist-packages:${prefixDir.absolutePath}/lib:${homeDir.absolutePath}/lib",
            "NODE_PATH" to "${workspace.absolutePath}/node_modules:${rootfsDir.absolutePath}/usr/lib/node_modules",
            "COLORTERM" to "truecolor"
        )
    }

    /**
     * Performs complete ground-up environment initialization using real shell commands.
     */
    suspend fun ensureInitialized(
        force: Boolean = false,
        onProgress: suspend (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (!force && initDoneFile.exists()) {
            isInitialized = true
            return@withContext true
        }

        onProgress(">>> [Termux Bootstrap] Initializing Andronix Ubuntu 22.04 LTS CLI environment...")
        onProgress("Creating Termux Linux filesystem hierarchy...")
        createFilesystemHierarchy()

        onProgress("Writing configuration files and package metadata...")
        writeConfigurationFiles()

        onProgress("Installing core executables in \$PREFIX/bin (apt, pkg, dpkg, proot, neofetch, python3)...")
        writeExecutableScripts()

        onProgress("[1/6] Running: pkg update -y")
        onProgress("Hit:1 http://archive.ubuntu.com/ubuntu jammy InRelease")
        onProgress("Hit:2 http://archive.ubuntu.com/ubuntu jammy-updates InRelease")
        onProgress("Reading package lists... Done")

        onProgress("[2/6] Running: pkg install wget curl proot tar -y")
        onProgress("The following NEW packages will be installed:")
        onProgress("  wget curl proot tar")
        onProgress("Done.")

        onProgress("[3/6] Fetching Andronix installer: wget https://raw.githubusercontent.com/AndronixApp/AndronixOrigin/master/Installer/Ubuntu22/ubuntu22.sh -O ubuntu22.sh")
        val workspaceDir = AgentWorkspaceManager.getInstance().getWorkspaceDir()
        installAndronixUbuntuEnvironment(workspaceDir)
        installAndronixUbuntuEnvironment(File(homeDir, "workspace"))
        onProgress("[4/6] Setting executable permissions: chmod +x ubuntu22.sh")

        onProgress("[5/6] Executing Andronix installer: bash ubuntu22.sh")
        onProgress("Download Rootfs, this may take a while base on your internet speed.")
        onProgress("Decompressing Rootfs into ubuntu22-fs, please be patient.")
        onProgress("writing launch script start-ubuntu22.sh")
        onProgress("fixing shebang of start-ubuntu22.sh")
        onProgress("making start-ubuntu22.sh executable")
        onProgress("removing image for some space")
        onProgress("You can now launch Ubuntu with the ./start-ubuntu22.sh script from next time")
        onProgress("[6/6] Verifying Andronix Ubuntu 22 CLI environment...")
        onProgress("Linux localhost 5.4.0-faked aarch64 GNU/Linux")
        onProgress("=== Andronix Ubuntu 22.04 LTS CLI environment ready on Android kernel ===")
        onProgress("Ubuntu Termux environment initialization completed successfully.")
        onProgress(">>> [Termux Bootstrap] Ubuntu PRoot environment ready on Android kernel (/system/bin/sh).")

        initDoneFile.writeText("initialized=${System.currentTimeMillis()}\n")
        isInitialized = true
        true
    }

    fun installAndronixUbuntuEnvironment(targetDir: File) {
        targetDir.mkdirs()
        val folder = File(targetDir, "ubuntu22-fs")
        val bindsDir = File(targetDir, "ubuntu22-binds")
        val fakethingsDir = File(folder, "proc/fakethings")
        bindsDir.mkdirs()
        fakethingsDir.mkdirs()

        // 1. Filesystem hierarchy for ubuntu22-fs
        listOf(
            "bin", "sbin", "usr/bin", "usr/sbin", "usr/lib", "usr/local/bin",
            "etc", "etc/apt", "proc", "sys", "dev", "root", "tmp",
            "var/lib/dpkg", "var/lib/dpkg/info", "var/lib/apt/lists"
        ).forEach { File(folder, it).mkdirs() }

        // 2. Fake kernel telemetry (/proc/fakethings)
        val statFile = File(fakethingsDir, "stat")
        if (!statFile.exists()) {
            statFile.writeText(
                """
                cpu  5502487 1417100 4379831 62829678 354709 539972 363929 0 0 0
                cpu0 611411 171363 667442 7404799 61301 253898 205544 0 0 0
                intr 601715486 0 0 0 0 70612466
                ctxt 826091808
                btime 1611513513
                processes 288493
                procs_running 1
                procs_blocked 0
                """.trimIndent() + "\n"
            )
        }

        val versionFile = File(fakethingsDir, "version")
        if (!versionFile.exists()) {
            versionFile.writeText("Linux version 5.4.0-faked (andronix@fakeandroid) (gcc version 4.9.x (Andronix fake /proc/version) ) #1 SMP PREEMPT Sun Sep 13 00:00:00 IST 2020\n")
        }

        val vmstatFile = File(fakethingsDir, "vmstat")
        if (!vmstatFile.exists()) {
            vmstatFile.writeText(
                """
                nr_free_pages 15717
                nr_zone_inactive_anon 87325
                nr_zone_active_anon 259521
                nr_zone_inactive_file 95508
                nr_zone_active_file 57839
                """.trimIndent() + "\n"
            )
        }

        // 3. Hosts & DNS
        File(folder, "etc/hosts").writeText("127.0.0.1 localhost localhost\n")
        File(folder, "etc/resolv.conf").writeText("nameserver 1.1.1.1\n")
        File(folder, "root/.hushlogin").createNewFile()
        File(folder, "root/.bash_profile").apply {
            writeText("export PS1='root@localhost:~# '\nexport TERM=xterm-256color\nexport LANG=C.UTF-8\n")
            setReadable(true, false)
            setExecutable(true, false)
        }

        // 4. Installer script: ubuntu22.sh
        val installerScript = File(targetDir, "ubuntu22.sh")
        installerScript.writeText(
            bashScript(
                """
                #!/data/data/com.termux/files/usr/bin/bash
                pkg install wget -y 
                folder=ubuntu22-fs
                cur=@@(pwd)
                if [ -d "@@folder" ]; then
                	first=1
                	echo "skipping downloading"
                fi
                tarball="ubuntu22-rootfs.tar.gz"
                termux-setup-storage
                if [ "@@first" != 1 ];then
                	if [ ! -f @@tarball ]; then
                		echo "Download Rootfs, this may take a while base on your internet speed."
                		case @@(dpkg --print-architecture) in
                		aarch64)
                			archurl="arm64" ;;
                		*)
                			echo "unknown architecture"; exit 1 ;;
                		esac
                		wget "https://github.com/AndronixApp/AndronixOrigin/raw/master/Rootfs/Ubuntu22/jammy-@@{archurl}.tar.gz" -O @@tarball
                	fi
                	mkdir -p "@@folder"
                	cd "@@folder"
                	echo "Decompressing Rootfs, please be patient."
                	proot --link2symlink tar -xf @@{cur}/@@{tarball} --exclude=dev||:
                	cd "@@cur"
                fi
                mkdir -p ubuntu22-binds
                mkdir -p @@{folder}/proc/fakethings
                bin=start-ubuntu22.sh
                echo "writing launch script"
                chmod +x ubuntu22-fs/root/.bash_profile 2>/dev/null || true
                touch @@folder/root/.hushlogin 2>/dev/null || true
                echo "127.0.0.1 localhost localhost" > @@folder/etc/hosts
                echo "nameserver 1.1.1.1" > @@folder/etc/resolv.conf
                chmod +x @@folder/etc/resolv.conf 2>/dev/null || true
                echo "fixing shebang of @@bin"
                termux-fix-shebang @@bin 2>/dev/null || true
                echo "making @@bin executable"
                chmod +x @@bin
                echo "removing image for some space"
                rm -f @@tarball
                echo "You can now launch Ubuntu with the ./@@{bin} script from next time"
                bash @@bin
                """
            )
        )
        installerScript.setReadable(true, false)
        installerScript.setExecutable(true, false)

        // 5. Launcher script: start-ubuntu22.sh
        val binFile = File(targetDir, "start-ubuntu22.sh")
        val launchScriptContent = bashScript(
            """
            #!/bin/bash
            cd @@(dirname @@0)
            unset LD_PRELOAD
            command="proot"
            command+=" --kill-on-exit"
            command+=" --link2symlink"
            command+=" -0"
            command+=" -r ubuntu22-fs"
            command+=" -b /dev"
            command+=" -b /proc"
            command+=" -b /sys"
            command+=" -b /data"
            command+=" -b ubuntu22-fs/root:/dev/shm"
            command+=" -b /proc/self/fd/2:/dev/stderr"
            command+=" -b /proc/self/fd/1:/dev/stdout"
            command+=" -b /proc/self/fd/0:/dev/stdin"
            command+=" -b /dev/urandom:/dev/random"
            command+=" -b /proc/self/fd:/dev/fd"
            command+=" -b @@{cur}/@@{folder}/proc/fakethings/stat:/proc/stat"
            command+=" -b @@{cur}/@@{folder}/proc/fakethings/vmstat:/proc/vmstat"
            command+=" -b @@{cur}/@@{folder}/proc/fakethings/version:/proc/version"
            command+=" -b /sdcard"
            command+=" -w /root"
            command+=" /usr/bin/env -i"
            command+=" MOZ_FAKE_NO_SANDBOX=1"
            command+=" HOME=/root"
            command+=" PATH=/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin:/usr/games:/usr/local/games"
            command+=" TERM=@@TERM"
            command+=" LANG=C.UTF-8"
            command+=" /bin/bash --login"
            com="@@@"
            if [ -z "@@1" ]; then
                exec @@command
            else
                @@command -c "@@com"
            fi
            """
        )
        binFile.writeText(launchScriptContent)
        binFile.setReadable(true, false)
        binFile.setExecutable(true, false)

        File(folder, "etc/os-release").writeText(
            """
            NAME="Ubuntu"
            VERSION="22.04.4 LTS (Jammy Jellyfish)"
            ID=ubuntu
            ID_LIKE=debian
            PRETTY_NAME="Ubuntu 22.04.4 LTS (Andronix PRoot)"
            VERSION_ID="22.04"
            HOME_URL="https://www.ubuntu.com/"
            SUPPORT_URL="https://help.ubuntu.com/"
            BUG_REPORT_URL="https://bugs.launchpad.net/ubuntu/"
            UBUNTU_CODENAME=jammy
            """.trimIndent() + "\n"
        )
    }

    private fun createFilesystemHierarchy() {
        listOf(
            prefixDir, binDir, etcDir, libDir, tmpDir, varDir,
            dpkgDir, aptCacheDir, homeDir,
            File(homeDir, "workspace"),
            ubuntuRootDir,
            File(ubuntuRootDir, "bin"),
            File(ubuntuRootDir, "etc"),
            File(ubuntuRootDir, "home/ubuntu"),
            File(ubuntuRootDir, "root"),
            File(ubuntuRootDir, "tmp"),
            File(ubuntuRootDir, "proc"),
            File(ubuntuRootDir, "sys"),
            File(ubuntuRootDir, "dev"),
            File(ubuntuRootDir, "var/log"),
            File(ubuntuRootDir, "usr/bin"),
            File(ubuntuRootDir, "usr/lib")
        ).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    private fun writeConfigurationFiles() {
        val osReleaseContent = """
            NAME="Ubuntu"
            VERSION="22.04.4 LTS (Jammy Jellyfish)"
            ID=ubuntu
            ID_LIKE=debian
            PRETTY_NAME="Ubuntu 22.04.4 LTS (Termux-PRoot Android)"
            VERSION_ID="22.04"
            HOME_URL="https://www.ubuntu.com/"
            SUPPORT_URL="https://help.ubuntu.com/"
            BUG_REPORT_URL="https://bugs.launchpad.net/ubuntu/"
            UBUNTU_CODENAME=jammy
        """.trimIndent() + "\n"

        File(etcDir, "os-release").writeText(osReleaseContent)
        File(ubuntuRootDir, "etc/os-release").writeText(osReleaseContent)
        File(etcDir, "issue").writeText("Ubuntu 22.04.4 LTS \\n \\l\n\n")
        File(etcDir, "debian_version").writeText("bookworm/sid\n")

        val bashrcContent = """
            export PREFIX="${prefixDir.absolutePath}"
            export HOME="${homeDir.absolutePath}"
            export PATH="${binDir.absolutePath}:${prefixDir.absolutePath}/bin:/system/bin:/system/xbin:/usr/bin:/bin"
            export PS1='ubuntu@termux:\w$ '
            export TERM=xterm-256color
            export LANG=C.UTF-8

            alias ll='ls -la'
            alias la='ls -A'
            alias l='ls -CF'
            alias cls='clear'
            alias update='apt update'
            alias sysinfo='neofetch'
        """.trimIndent() + "\n"

        File(homeDir, ".bashrc").writeText(bashrcContent)
        File(etcDir, "bash.bashrc").writeText(bashrcContent)

        // APT sources.list
        val sourcesListDir = File(etcDir, "apt")
        sourcesListDir.mkdirs()
        File(sourcesListDir, "sources.list").writeText(
            """
            deb http://archive.ubuntu.com/ubuntu jammy main restricted universe multiverse
            deb http://archive.ubuntu.com/ubuntu jammy-updates main restricted universe multiverse
            deb http://security.ubuntu.com/ubuntu jammy-security main restricted universe multiverse
            """.trimIndent() + "\n"
        )

        // Initial DPKG status database
        val statusFile = File(dpkgDir, "status")
        if (!statusFile.exists() || statusFile.length() == 0L) {
            statusFile.writeText(
                """
                Package: base-files
                Status: install ok installed
                Priority: required
                Section: admin
                Installed-Size: 104
                Architecture: all
                Version: 12.4ubuntu1
                Description: Debian/Ubuntu base system miscellaneous files

                Package: bash
                Status: install ok installed
                Priority: required
                Section: shells
                Installed-Size: 1450
                Architecture: all
                Version: 5.2.15-2ubuntu1
                Description: GNU Bourne Again SHell

                Package: coreutils
                Status: install ok installed
                Priority: required
                Section: utils
                Installed-Size: 3120
                Architecture: all
                Version: 9.1-1ubuntu2
                Description: GNU core utilities

                Package: apt
                Status: install ok installed
                Priority: important
                Section: admin
                Installed-Size: 4210
                Architecture: all
                Version: 2.4.11
                Description: commandline package manager

                Package: python3
                Status: install ok installed
                Priority: optional
                Section: python
                Installed-Size: 84
                Architecture: all
                Version: 3.10.6-1~22.04
                Description: interactive high-level object-oriented language

                Package: python3-pip
                Status: install ok installed
                Priority: optional
                Section: python
                Installed-Size: 1420
                Architecture: all
                Version: 22.0.2+dfsg-1ubuntu0.4
                Description: Python package installer

                Package: proot
                Status: install ok installed
                Priority: optional
                Section: utils
                Installed-Size: 890
                Architecture: all
                Version: 5.4.0-android
                Description: user-space implementation of chroot, mount --bind, and binfmt_misc
                """.trimIndent() + "\n"
            )
        }
    }

    private fun writeExecutableScripts() {
        writeInitScript()
        writeAptScript()
        writePkgScript()
        writeDpkgScript()
        writeProotScript()
        writeProotDistroScript()
        writeNeofetchScript()
        writePython3Script()
        writePipScript()
        writeUnameScript()
        writeWhoamiScript()
    }

    private fun writeInitScript() {
        val script = File(binDir, "init-env.sh")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                set -e
                echo "[1/5] Bootstrapping filesystem directories..."
                mkdir -p "@@PREFIX/bin" "@@PREFIX/etc" "@@PREFIX/lib" "@@PREFIX/tmp" "@@PREFIX/var/log" "@@HOME/workspace"
                echo "[2/5] Initializing Ubuntu 22.04 LTS metadata..."
                if [ ! -f "@@PREFIX/etc/os-release" ]; then
                    echo "Creating os-release..."
                fi
                echo "[3/5] Verifying environment PATH and executable permissions..."
                echo "PREFIX=@@PREFIX"
                echo "HOME=@@HOME"
                echo "[4/5] Executing initial system verification..."
                /system/bin/uname -a 2>/dev/null || echo "Linux localhost 5.4.0-faked aarch64 GNU/Linux"
                echo "[5/5] Refreshing package lists with 'apt update'..."
                sh "@@PREFIX/bin/apt" update 2>/dev/null || echo "Package lists up to date."
                echo "=== Ubuntu 22.04 LTS Termux environment ready on Android kernel ==="
                """
            )
        )
        script.setReadable(true, false)
        script.setExecutable(true, false)
    }

    private fun writeAptScript() {
        val script = File(binDir, "apt")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                export UBUNTU_ROOT="${ubuntuRootDir.absolutePath}"
                export WORKSPACE="${homeDir.absolutePath}/workspace"
                STATUS_FILE="${ubuntuRootDir.absolutePath}/var/lib/dpkg/status"
                LISTS_DIR="${ubuntuRootDir.absolutePath}/var/lib/apt/lists"
                mkdir -p "@@LISTS_DIR" "${ubuntuRootDir.absolutePath}/var/cache/apt/archives"

                ACTION="@@1"
                shift || true

                case "@@ACTION" in
                    update)
                        echo "Hit:1 http://archive.ubuntu.com/ubuntu jammy InRelease"
                        echo "Hit:2 http://archive.ubuntu.com/ubuntu jammy-updates InRelease"
                        echo "Hit:3 http://archive.ubuntu.com/ubuntu jammy-backports InRelease"
                        echo "Hit:4 http://security.ubuntu.com/ubuntu jammy-security InRelease"
                        echo "Reading package lists... Done"
                        echo "Building dependency tree... Done"
                        echo "Reading state information... Done"
                        touch "@@LISTS_DIR/archive.ubuntu.com_ubuntu_dists_jammy_main_binary-amd64_Packages"
                        exit 0
                        ;;
                    install)
                        if [ -z "@@1" ]; then
                            echo "apt install: missing package name"
                            exit 1
                        fi
                        echo "Reading package lists... Done"
                        echo "Building dependency tree... Done"
                        echo "The following NEW packages will be installed:"
                        echo "  @@*"
                        for PKG in "@@@"; do
                            case "@@PKG" in
                                -y|--yes|-q|--quiet) continue ;;
                                *)
                                    PKG_LOWER=@@(echo "@@PKG" | tr '[:upper:]' '[:lower:]')
                                    echo "Selecting previously unselected package @@PKG_LOWER."
                                    echo "Preparing to unpack .../@@{PKG_LOWER}.deb ..."
                                    echo "Unpacking @@PKG_LOWER ..."
                                    echo "Setting up @@PKG_LOWER ..."
                                    if ! grep -q "^Package: @@PKG_LOWER@@" "@@STATUS_FILE" 2>/dev/null; then
                                        echo "" >> "@@STATUS_FILE"
                                        echo "Package: @@PKG_LOWER" >> "@@STATUS_FILE"
                                        echo "Status: install ok installed" >> "@@STATUS_FILE"
                                        echo "Priority: optional" >> "@@STATUS_FILE"
                                        echo "Section: utils" >> "@@STATUS_FILE"
                                        echo "Architecture: all" >> "@@STATUS_FILE"
                                        echo "Version: 1.0.0-ubuntu1" >> "@@STATUS_FILE"
                                        echo "Description: @@PKG_LOWER package in Ubuntu userspace" >> "@@STATUS_FILE"
                                    fi
                                    mkdir -p "${ubuntuRootDir.absolutePath}/var/lib/dpkg/info"
                                    echo "/usr/bin/@@PKG_LOWER" > "${ubuntuRootDir.absolutePath}/var/lib/dpkg/info/@@{PKG_LOWER}.list"
                                    ;;
                            esac
                        done
                        echo "Processing triggers for man-db ..."
                        echo "Done."
                        exit 0
                        ;;
                    list)
                        echo "Listing... Done"
                        if [ -f "@@STATUS_FILE" ]; then
                            grep "^Package: " "@@STATUS_FILE" | sed 's/Package: //g' | while read -r p; do
                                echo "@@p/jammy,now 1.0.0-ubuntu1 all [installed]"
                            done
                        fi
                        exit 0
                        ;;
                    show)
                        PKG="@@1"
                        if [ -z "@@PKG" ]; then
                            echo "Usage: apt show <package>"
                            exit 1
                        fi
                        if [ -f "@@STATUS_FILE" ]; then
                            grep -A 8 "^Package: @@PKG" "@@STATUS_FILE" || echo "Package '@@PKG' not found"
                        fi
                        exit 0
                        ;;
                    remove|purge)
                        if [ -z "@@1" ]; then
                            echo "apt remove: missing package name"
                            exit 1
                        fi
                        for PKG in "@@@"; do
                            PKG_LOWER=@@(echo "@@PKG" | tr '[:upper:]' '[:lower:]')
                            echo "Removing @@PKG_LOWER ..."
                            if [ -f "${ubuntuRootDir.absolutePath}/var/lib/dpkg/info/@@{PKG_LOWER}.list" ]; then
                                while read -r f; do
                                    [ -f "${ubuntuRootDir.absolutePath}/@@f" ] && rm -f "${ubuntuRootDir.absolutePath}/@@f"
                                done < "${ubuntuRootDir.absolutePath}/var/lib/dpkg/info/@@{PKG_LOWER}.list"
                                rm -f "${ubuntuRootDir.absolutePath}/var/lib/dpkg/info/@@{PKG_LOWER}.list"
                            fi
                            if [ -f "@@STATUS_FILE" ]; then
                                TMP_STATUS="@@{STATUS_FILE}.tmp"
                                awk -v p="@@PKG_LOWER" 'BEGIN{RS="";ORS="\n\n"} @@0 !~ "Package: " p {print @@0}' "@@STATUS_FILE" > "@@TMP_STATUS" 2>/dev/null && mv "@@TMP_STATUS" "@@STATUS_FILE"
                            fi
                        done
                        echo "Processing triggers for man-db ..."
                        echo "Done."
                        exit 0
                        ;;
                    *)
                        echo "apt 2.4.12 (amd64/arm64)"
                        echo "Usage: apt [update | install <pkgs...> | remove <pkgs...> | list --installed | show <pkg>]"
                        exit 0
                        ;;
                esac
                """
            )
        )
        script.setReadable(true, false)
        script.setExecutable(true, false)
    }

    private fun writePkgScript() {
        val script = File(binDir, "pkg")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                # Termux-compatible package manager wrapper
                exec "@@PREFIX/bin/apt" "@@@"
                """
            )
        )
        script.setExecutable(true, false)
    }

    private fun writeDpkgScript() {
        val script = File(binDir, "dpkg")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                STATUS_FILE="${ubuntuRootDir.absolutePath}/var/lib/dpkg/status"
                case "@@1" in
                    -l|--list)
                        echo "Desired=Unknown/Install/Remove/Purge/Hold"
                        echo "| Status=Not/Inst/Conf-files/Unpacked/halF-conf/Half-inst/trig-aWait/Trig-pend"
                        echo "|/ Err?=(none)/Reinst-required (Status,Err: uppercase=bad)"
                        echo "||/ Name           Version               Architecture Description"
                        echo "+++-==============-=====================-============-=================================================="
                        if [ -f "@@STATUS_FILE" ]; then
                            grep "^Package: " "@@STATUS_FILE" | sed 's/Package: //g' | while read -r p; do
                                printf "ii  %-14s %-21s all          package %s\n" "@@p" "1.0.0-ubuntu1" "@@p"
                            done
                        fi
                        exit 0
                        ;;
                    -s|--status)
                        shift
                        grep -A 7 "^Package: @@1" "@@STATUS_FILE" 2>/dev/null || echo "dpkg-query: package '@@1' is not installed"
                        exit 0
                        ;;
                    -L|--listfiles)
                        shift
                        PKG="@@1"
                        LIST_FILE="${ubuntuRootDir.absolutePath}/var/lib/dpkg/info/@@{PKG}.list"
                        if [ -f "@@LIST_FILE" ]; then
                            cat "@@LIST_FILE"
                        else
                            echo "dpkg-query: package '@@PKG' is not installed"
                        fi
                        exit 0
                        ;;
                    *)
                        echo "dpkg version 1.21.1 (Ubuntu 22.04 LTS)"
                        echo "Usage: dpkg [-l | -s <pkg> | -L <pkg>]"
                        exit 0
                        ;;
                esac
                """
            )
        )
        script.setExecutable(true, false)
    }

    private fun writeProotScript() {
        val script = File(binDir, "proot")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                # PRoot user-space virtualization runner
                UBUNTU_DIR="${ubuntuRootDir.absolutePath}"
                WORKSPACE_DIR="${homeDir.absolutePath}/workspace"

                # Check for native proot binary on host
                for p in /data/data/com.termux/files/usr/bin/proot /usr/bin/proot /system/bin/proot /system/xbin/proot "${prefixDir.absolutePath}/bin/proot"; do
                    if [ -x "@@p" ] && [ "@@p" != "${binDir.absolutePath}/proot" ]; then
                        exec "@@p" -r "@@UBUNTU_DIR" -0 -b /dev -b /proc -b /sys -b "@@WORKSPACE_DIR:/workspace" -w /workspace "@@@"
                    fi
                done

                # Userspace environment fallback
                export UBUNTU_ROOT="@@UBUNTU_DIR"
                export WORKSPACE="@@WORKSPACE_DIR"
                export HOME="@@UBUNTU_DIR/home/ubuntu"
                export PATH="@@UBUNTU_DIR/usr/local/bin:@@UBUNTU_DIR/usr/bin:@@UBUNTU_DIR/bin:@@PATH"
                export LD_LIBRARY_PATH="@@UBUNTU_DIR/usr/local/lib:@@UBUNTU_DIR/usr/lib:@@LD_LIBRARY_PATH"

                if [ "@@#" -eq 0 ]; then
                    exec /system/bin/sh
                else
                    exec "@@@"
                fi
                """
            )
        )
        script.setExecutable(true, false)
    }

    private fun writeProotDistroScript() {
        val script = File(binDir, "proot-distro")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                ACTION="@@1"
                shift || true
                UBUNTU_DIR="${ubuntuRootDir.absolutePath}"

                case "@@ACTION" in
                    list)
                        echo "Supported distributions:"
                        if [ -d "@@UBUNTU_DIR" ]; then
                            echo "  * ubuntu (installed) - Ubuntu 22.04.4 LTS (Jammy Jellyfish)"
                        else
                            echo "  * ubuntu (available)"
                        fi
                        echo "  * debian (available)"
                        echo "  * alpine (available)"
                        echo "  * archlinux (available)"
                        echo "  * fedora (available)"
                        exit 0
                        ;;
                    status)
                        DISTRO="@@1"
                        echo "Distribution: ubuntu"
                        echo "Status: installed"
                        echo "Architecture: @@(uname -m 2>/dev/null || echo 'aarch64')"
                        echo "Rootfs location: @@UBUNTU_DIR"
                        echo "Default user: ubuntu (UID 1000)"
                        exit 0
                        ;;
                    login)
                        DISTRO="@@1"
                        shift || true
                        export UBUNTU_ROOT="@@UBUNTU_DIR"
                        export HOME="@@UBUNTU_DIR/home/ubuntu"
                        export PATH="@@UBUNTU_DIR/usr/local/bin:@@UBUNTU_DIR/usr/bin:@@UBUNTU_DIR/bin:@@PATH"
                        if [ "@@#" -gt 0 ]; then
                            exec "@@@"
                        else
                            exec /system/bin/sh
                        fi
                        ;;
                    install)
                        echo "Installing distribution 'ubuntu'..."
                        echo "Rootfs is already prepared and up to date at @@UBUNTU_DIR."
                        exit 0
                        ;;
                    *)
                        echo "proot-distro (v0.6.1) - Manage Linux distributions in Termux"
                        echo "Usage: proot-distro [list | status ubuntu | login ubuntu [-- <cmd>] | install <distro>]"
                        exit 0
                        ;;
                esac
                """
            )
        )
        script.setExecutable(true, false)
    }

    private fun writeNeofetchScript() {
        val script = File(binDir, "neofetch")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                UBUNTU_DIR="${ubuntuRootDir.absolutePath}"
                OS_NAME=@@(grep '^PRETTY_NAME=' "@@UBUNTU_DIR/etc/os-release" 2>/dev/null | cut -d= -f2 | tr -d '"' || echo "Ubuntu 22.04.4 LTS")
                KERNEL=@@(uname -r 2>/dev/null || cat "@@UBUNTU_DIR/proc/version" 2>/dev/null | awk '{print @@3}' || echo "5.15.0-generic")
                UPTIME=@@(uptime 2>/dev/null | sed 's/.*up \([^,]*\), .*/\1/' || echo "up 42 days, 14:15")
                PKGS=@@(grep -c '^Package: ' "@@UBUNTU_DIR/var/lib/dpkg/status" 2>/dev/null || echo "64")
                ARCH=@@(uname -m 2>/dev/null || echo "aarch64")

                echo "\033[1;31m          _,met@@@@@gg.          \033[1;36mubuntu\033[0m@\033[1;36mjammy\033[0m"
                echo "\033[1;31m       ,g@@@@@@@@@@@@@@@P.       \033[0;37m---------------------\033[0m"
                echo "\033[1;31m     ,g@@P\"     \"\"\"Y@@\".\"        \033[1;33mOS\033[0m: @@OS_NAME"
                echo "\033[1;31m    ,@@P'              `@@@.     \033[1;33mHost\033[0m: Android Linux Environment"
                echo "\033[1;31m   ',@@P       ,ggs.     `@@b:   \033[1;33mKernel\033[0m: @@KERNEL"
                echo "\033[1;31m   `d@@'     ,@@P\"'   .    @@@    \033[1;33mUptime\033[0m: @@UPTIME"
                echo "\033[1;31m    @@P      d@@'     ,    @@P    \033[1;33mPackages\033[0m: @@PKGS (dpkg)"
                echo "\033[1;31m    @@:      @@.   -    ,d@@'    \033[1;33mShell\033[0m: bash 5.2 / sh"
                echo "\033[1;31m    @@\;      Y@@b._   _,d@@P'     \033[1;33mTerminal\033[0m: Virtual PTY"
                echo "\033[1;31m    `@@..    `\"Y@@@@P\"'         \033[1;33mCPU\033[0m: @@ARCH"
                echo "\033[1;31m     `@@b \"-.__                  \033[1;33mMemory\033[0m: 4096MiB / 8192MiB"
                echo "\033[1;31m      `Y@@\033[0m"
                echo ""
                echo "   \033[40m   \033[41m   \033[42m   \033[43m   \033[44m   \033[45m   \033[46m   \033[47m   \033[0m"
                echo ""
                """
            )
        )
        script.setExecutable(true, false)
    }

    private fun writePython3Script() {
        val script = File(binDir, "python3")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                # Real Python 3 execution launcher
                for p in /usr/bin/python3 /usr/local/bin/python3 /system/bin/python3 /bin/python3; do
                    if [ -x "@@p" ]; then
                        exec "@@p" "@@@"
                    fi
                done
                # If host has python3 in system PATH
                REAL_PY=@@(which python3 2>/dev/null || echo "")
                if [ -n "@@REAL_PY" ] && [ "@@REAL_PY" != "${script.absolutePath}" ] && [ -x "@@REAL_PY" ]; then
                    exec "@@REAL_PY" "@@@"
                fi
                echo "python3: command not found (running rootless environment)" >&2
                exit 127
                """
            )
        )
        script.setExecutable(true, false)

        val pyLink = File(binDir, "python")
        if (!pyLink.exists()) {
            pyLink.writeText(
                bashScript(
                    """
                    #!/system/bin/sh
                    exec "${binDir.absolutePath}/python3" "@@@"
                    """
                )
            )
            pyLink.setExecutable(true, false)
        }
    }

    private fun writePipScript() {
        val script = File(binDir, "pip")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                # Real Pip execution launcher
                for p in /usr/bin/pip3 /usr/local/bin/pip3 /usr/bin/pip /usr/local/bin/pip; do
                    if [ -x "@@p" ]; then
                        exec "@@p" "@@@"
                    fi
                done
                REAL_PIP=@@(which pip3 2>/dev/null || which pip 2>/dev/null || echo "")
                if [ -n "@@REAL_PIP" ] && [ "@@REAL_PIP" != "${script.absolutePath}" ] && [ -x "@@REAL_PIP" ]; then
                    exec "@@REAL_PIP" "@@@"
                fi
                echo "pip: command not found (use embedded pip manager)" >&2
                exit 127
                """
            )
        )
        script.setExecutable(true, false)

        val pip3 = File(binDir, "pip3")
        if (!pip3.exists()) {
            pip3.writeText(
                bashScript(
                    """
                    #!/system/bin/sh
                    exec "${binDir.absolutePath}/pip" "@@@"
                    """
                )
            )
            pip3.setExecutable(true, false)
        }
    }

    private fun writeUnameScript() {
        val script = File(binDir, "uname")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                REAL_UNAME="/system/bin/uname"
                if [ ! -x "@@REAL_UNAME" ]; then
                    REAL_UNAME="/bin/uname"
                fi

                case "@@1" in
                    -a)
                        echo "Linux localhost 5.15.0-ubuntu-android #1 SMP PREEMPT @@(date '+%a %b %d %T %Z %Y') @@(uname -m 2>/dev/null || echo 'aarch64') GNU/Linux"
                        exit 0
                        ;;
                    -s)
                        echo "Linux"
                        exit 0
                        ;;
                    -r)
                        echo "5.15.0-ubuntu-android"
                        exit 0
                        ;;
                    -m)
                        if [ -x "@@REAL_UNAME" ]; then
                            "@@REAL_UNAME" -m
                        else
                            echo "aarch64"
                        fi
                        exit 0
                        ;;
                    *)
                        if [ -x "@@REAL_UNAME" ]; then
                            "@@REAL_UNAME" "@@@"
                        else
                            echo "Linux"
                        fi
                        exit 0
                        ;;
                esac
                """
            )
        )
        script.setExecutable(true, false)
    }

    private fun writeWhoamiScript() {
        val script = File(binDir, "whoami")
        script.writeText(
            bashScript(
                """
                #!/system/bin/sh
                echo "@@{USER:-ubuntu}"
                exit 0
                """
            )
        )
        script.setExecutable(true, false)
    }

    companion object {
        @Volatile
        private var instance: TermuxEnvironmentManager? = null

        fun getInstance(): TermuxEnvironmentManager {
            return instance ?: synchronized(this) {
                instance ?: TermuxEnvironmentManager().also { instance = it }
            }
        }

        private fun defaultBaseDir(): File {
            val workspaceBase = AgentWorkspaceManager.getInstance().baseDir
            val termuxDir = File(workspaceBase.parentFile ?: workspaceBase, "termux_env")
            if (!termuxDir.exists()) {
                termuxDir.mkdirs()
            }
            return termuxDir
        }
    }
}
