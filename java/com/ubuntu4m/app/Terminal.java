package com.ubuntu4m.app;

import android.content.Context;
import android.net.TrafficStats;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class Terminal {

    private static final String TAG = "Ubuntu4M";
    private static final String BUNDLED_BINARY_NAME = "libhello_arm64.so";
    private static final String BUNDLED_PROOT_NAME = "libproot.so";
    private static final String BUNDLED_PROOT_LOADER_NAME = "libproot_loader.so";
    private static final String BUNDLED_PROOT_LOADER32_NAME = "libproot_loader32.so";
    private static final String BUNDLED_TAR_NAME = "libtar_bin.so";
    private static final String PROOT_TMP_DIR_NAME = "proot-tmp";
    private static final String ROOTFS_DIR_NAME = "rootfs";
    private static final String ROOTFS_READY_MARKER_NAME = ".ubuntu4m-rootfs-ready";
    private static final String ROOTFS_ASSET_PATH = "ubuntu-rootfs/ubuntu-base-24.04.4-base-arm64.tar";
    private static final String CA_CERTS_ASSET_PATH = "ubuntu-rootfs/ca-certificates.crt";
    private static final String ROOTFS_STAGING_NAME = "ubuntu-base-24.04.4-base-arm64.tar";
    private static final String APT_SOURCES_FILE = "etc/apt/sources.list.d/ubuntu.sources";
    private static final String RESOLV_CONF_FILE = "etc/resolv.conf";
    private static final String PROFILE_SCRIPT_FILE = "etc/profile.d/ubuntu4m.sh";
    private static final String APT_CONF_FILE = "etc/apt/apt.conf.d/99ubuntu4m";
    private static final String MIRROR_HELPER_FILE = "usr/local/bin/mubuntu-mirror";
    private static final String NLOAD_WRAPPER_FILE = "usr/local/bin/nload";
    private static final String CA_CERTS_FILE = "etc/ssl/certs/ca-certificates.crt";
    private static final String GROUP_FILE = "etc/group";
    private static final String NETWORK_STATS_FILE_NAME = ".mubuntu-netstats";
    private static final String DEFAULT_APT_MIRROR = "http://mirrors.aliyun.com/ubuntu-ports/";
    private static final long NETWORK_STATS_UPDATE_INTERVAL_MS = 1000L;
    public interface Listener {
        void onOutput(String text);
        void onExit(int exitCode);
    }

    private final Context context;
    private final Listener listener;

    private Process process;
    private OutputStreamWriter processInput;
    private Thread readThread;
    private Thread networkStatsThread;
    private volatile boolean running;
    private volatile boolean networkStatsRunning;
    private File networkStatsFile;

    public Terminal(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void start() {
        Thread startThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    launchShell();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start terminal", e);
                    sendOutput("Error: " + e.getMessage() + "\r\n");
                }
            }
        }, "Ubuntu4M-shell-start");
        startThread.start();
    }

    public void writeInput(String text) {
        if (processInput != null && running) {
            try {
                processInput.write(normalizeInput(text));
                processInput.flush();
            } catch (IOException e) {
                Log.e(TAG, "Error writing to process", e);
            }
        }
    }

    public void destroy() {
        running = false;
        stopNetworkStatsWriter();
        if (process != null) {
            process.destroy();
        }
        if (readThread != null) {
            readThread.interrupt();
        }
    }

    private void launchShell() throws IOException {
        File homeDir = context.getFilesDir();
        File prootTmpDir = new File(context.getCacheDir(), PROOT_TMP_DIR_NAME);
        File rootfsDir = new File(homeDir, ROOTFS_DIR_NAME);
        ensureDirectory(prootTmpDir);

        String nativeBinDir = context.getApplicationInfo().nativeLibraryDir;
        sendOutput(buildWelcomeMessage());
        ensureUbuntuRootfs(nativeBinDir, rootfsDir);
        configureUbuntuRootfs(rootfsDir);
        networkStatsFile = new File(homeDir, NETWORK_STATS_FILE_NAME);
        writeNetworkStatsSnapshot();

        ProcessBuilder processBuilder = buildUbuntuProcess(nativeBinDir, homeDir, rootfsDir, prootTmpDir);
        processBuilder.directory(homeDir);
        processBuilder.redirectErrorStream(true);

        java.util.Map<String, String> env = processBuilder.environment();
        env.put("HOME", homeDir.getAbsolutePath());
        env.put("PWD", homeDir.getAbsolutePath());
        env.put("TMPDIR", prootTmpDir.getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("PATH", nativeBinDir + ":/system/bin:/system/xbin:/product/bin:/vendor/bin");
        env.put("LD_LIBRARY_PATH", nativeBinDir);
        env.put("PROOT_LOADER", nativeBinDir + "/" + BUNDLED_PROOT_LOADER_NAME);
        env.put("PROOT_LOADER_32", nativeBinDir + "/" + BUNDLED_PROOT_LOADER32_NAME);
        env.put("PROOT_TMP_DIR", prootTmpDir.getAbsolutePath());

        process = processBuilder.start();
        processInput = new OutputStreamWriter(process.getOutputStream());
        running = true;
        startNetworkStatsWriter();

        readThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readOutputLoop();
            }
        }, "Ubuntu4M-shell-output");
        readThread.start();
    }

    private void readOutputLoop() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()), 4096);
            char[] buffer = new char[4096];
            int len;
            while (running && (len = reader.read(buffer)) > 0) {
                sendOutput(new String(buffer, 0, len));
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Error reading process output", e);
            }
        } finally {
            running = false;
            stopNetworkStatsWriter();
            try {
                int exitCode = process.waitFor();
                if (listener != null) {
                    listener.onExit(exitCode);
                }
            } catch (InterruptedException ignored) {
                // Ignore teardown races during shutdown.
            }
        }
    }

    private String normalizeInput(String text) {
        if ("\r".equals(text)) {
            return "\n";
        }
        return text;
    }

    private ProcessBuilder buildUbuntuProcess(String nativeBinDir, File homeDir,
                                              File rootfsDir, File prootTmpDir) {
        String prootPath = nativeBinDir + "/" + BUNDLED_PROOT_NAME;
        return new ProcessBuilder(
                prootPath,
                "--link2symlink",
                "-r", rootfsDir.getAbsolutePath(),
                "-0",
                "-w", "/root",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", homeDir.getAbsolutePath() + ":/host-home",
                "-b", nativeBinDir + ":/host-native",
                "/usr/bin/script",
                "-qefc",
                "stty rows 24 cols 80 >/dev/null 2>&1; cd /root || cd /; exec /usr/bin/env " +
                        "HOME=/root " +
                        "USER=root " +
                        "LOGNAME=root " +
                        "SHELL=/bin/bash " +
                        "TERM=xterm-256color " +
                        "LANG=C.UTF-8 " +
                        "COLUMNS=80 " +
                        "LINES=24 " +
                        "MUBUNTU_NETSTATS_FILE=/host-home/" + NETWORK_STATS_FILE_NAME + " " +
                        "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt " +
                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                        "/bin/bash --noprofile --rcfile /etc/profile.d/ubuntu4m.sh -i",
                "/dev/null"
        );
    }

    private void ensureUbuntuRootfs(String nativeBinDir, File rootfsDir) throws IOException {
        if (hasUsableUbuntuRootfs(rootfsDir)) {
            return;
        }

        sendOutput("Preparing Ubuntu rootfs (first run)...\r\n");
        if (rootfsDir.exists()) {
            deleteRecursively(rootfsDir);
        }
        ensureDirectory(rootfsDir);

        File stagedTar = new File(context.getCacheDir(), ROOTFS_STAGING_NAME);
        sendOutput("Staging rootfs asset...\r\n");
        copyAssetToFile(ROOTFS_ASSET_PATH, stagedTar);

        sendOutput("Extracting Ubuntu rootfs...\r\n");
        String tarOutput = runBundledTar(nativeBinDir, stagedTar, rootfsDir);
        applyUbuntuHardLinkFallbacks(rootfsDir);

        if (!hasCoreUbuntuFiles(rootfsDir)) {
            throw new IOException("Ubuntu rootfs extraction incomplete.\n" + tarOutput);
        }

        writeMarker(new File(rootfsDir, ROOTFS_READY_MARKER_NAME), ROOTFS_ASSET_PATH);
        if (!stagedTar.delete()) {
            Log.w(TAG, "Failed to delete staged rootfs tar: " + stagedTar.getAbsolutePath());
        }
        sendOutput("Ubuntu rootfs ready.\r\n\r\n");
    }

    private void configureUbuntuRootfs(File rootfsDir) throws IOException {
        copyAssetToFile(CA_CERTS_ASSET_PATH, new File(rootfsDir, CA_CERTS_FILE));
        ensureAndroidGroupMappings(rootfsDir);
        writeTextFile(new File(rootfsDir, RESOLV_CONF_FILE),
                "nameserver 223.5.5.5\n" +
                        "nameserver 223.6.6.6\n" +
                        "nameserver 119.29.29.29\n");
        writeTextFile(new File(rootfsDir, APT_SOURCES_FILE),
                buildUbuntuSources(DEFAULT_APT_MIRROR));
        writeTextFile(new File(rootfsDir, APT_CONF_FILE),
                "Acquire::Retries \"3\";\n" +
                        "Acquire::ForceIPv4 \"true\";\n" +
                        "Acquire::http::Timeout \"12\";\n" +
                        "Acquire::https::Timeout \"12\";\n" +
                        "Acquire::https::CaInfo \"/etc/ssl/certs/ca-certificates.crt\";\n");
        writeExecutableTextFile(new File(rootfsDir, MIRROR_HELPER_FILE),
                "#!/bin/sh\n" +
                        "set -eu\n" +
                        "\n" +
                        "case \"${1:-aliyun}\" in\n" +
                        "  official)\n" +
                        "    uri=\"http://ports.ubuntu.com/ubuntu-ports/\"\n" +
                        "    ;;\n" +
                        "  aliyun)\n" +
                        "    uri=\"http://mirrors.aliyun.com/ubuntu-ports/\"\n" +
                        "    ;;\n" +
                        "  tuna)\n" +
                        "    uri=\"http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/\"\n" +
                        "    ;;\n" +
                        "  ustc)\n" +
                        "    uri=\"http://mirrors.ustc.edu.cn/ubuntu-ports/\"\n" +
                        "    ;;\n" +
                        "  *)\n" +
                        "    echo \"usage: mubuntu-mirror [official|aliyun|tuna|ustc]\" >&2\n" +
                        "    exit 1\n" +
                        "    ;;\n" +
                        "esac\n" +
                        "\n" +
                        "cat > /etc/apt/sources.list.d/ubuntu.sources <<EOF\n" +
                        "Types: deb\n" +
                        "URIs: ${uri}\n" +
                        "Suites: noble noble-updates noble-backports noble-security\n" +
                        "Components: main restricted universe multiverse\n" +
                        "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n" +
                        "EOF\n" +
                        "\n" +
                        "echo \"APT mirror set to: ${uri}\"\n");
        writeExecutableTextFile(new File(rootfsDir, NLOAD_WRAPPER_FILE), buildNloadWrapperScript());
        writeTextFile(new File(rootfsDir, PROFILE_SCRIPT_FILE),
                "export LANG=C.UTF-8\n" +
                        "export TERM=${TERM:-xterm-256color}\n" +
                        "export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt\n" +
                        "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                        "alias ll='ls -alF'\n" +
                        "alias ls='ls --color=auto'\n" +
                        "alias grep='grep --color=auto'\n" +
                        "alias hosthello='/host-native/" + BUNDLED_BINARY_NAME + "'\n" +
                        "alias mirror='mubuntu-mirror'\n" +
                        "if [ -n \"$PS1\" ]; then\n" +
                        "  if [ -x /usr/bin/dircolors ]; then\n" +
                        "    eval \"$(dircolors -b)\"\n" +
                        "  fi\n" +
                        "  export PS1='\\[\\e[1;32m\\]mubuntu\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]# '\n" +
                        "fi\n");
    }

    private void startNetworkStatsWriter() {
        stopNetworkStatsWriter();
        networkStatsRunning = true;
        networkStatsThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (networkStatsRunning) {
                    try {
                        writeNetworkStatsSnapshot();
                        Thread.sleep(NETWORK_STATS_UPDATE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException e) {
                        if (networkStatsRunning) {
                            Log.w(TAG, "Failed to update synthetic network stats", e);
                        }
                    }
                }
            }
        }, "Ubuntu4M-net-stats");
        networkStatsThread.start();
    }

    private void stopNetworkStatsWriter() {
        networkStatsRunning = false;
        if (networkStatsThread != null) {
            networkStatsThread.interrupt();
            networkStatsThread = null;
        }
    }

    private void writeNetworkStatsSnapshot() throws IOException {
        if (networkStatsFile == null) {
            return;
        }

        long totalRx = sanitizeTrafficStat(TrafficStats.getTotalRxBytes());
        long totalTx = sanitizeTrafficStat(TrafficStats.getTotalTxBytes());
        long mobileRx = sanitizeTrafficStat(TrafficStats.getMobileRxBytes());
        long mobileTx = sanitizeTrafficStat(TrafficStats.getMobileTxBytes());
        boolean supported = totalRx >= 0L && totalTx >= 0L;

        if (!supported) {
            totalRx = 0L;
            totalTx = 0L;
            mobileRx = 0L;
            mobileTx = 0L;
        }

        if (mobileRx > totalRx) {
            mobileRx = totalRx;
        }
        if (mobileTx > totalTx) {
            mobileTx = totalTx;
        }

        long otherRx = Math.max(0L, totalRx - mobileRx);
        long otherTx = Math.max(0L, totalTx - mobileTx);
        long timestampMs = SystemClock.elapsedRealtime();

        writeTextFile(networkStatsFile,
                "# synthetic stats for nload fallback\n" +
                        "stats_supported=" + (supported ? "1" : "0") + "\n" +
                        "timestamp_ms=" + timestampMs + "\n" +
                        "total_rx_bytes=" + totalRx + "\n" +
                        "total_tx_bytes=" + totalTx + "\n" +
                        "mobile_rx_bytes=" + mobileRx + "\n" +
                        "mobile_tx_bytes=" + mobileTx + "\n" +
                        "other_rx_bytes=" + otherRx + "\n" +
                        "other_tx_bytes=" + otherTx + "\n");
    }

    private long sanitizeTrafficStat(long value) {
        return value == TrafficStats.UNSUPPORTED ? -1L : Math.max(0L, value);
    }

    private String buildNloadWrapperScript() {
        return "#!/bin/sh\n" +
                "set -eu\n" +
                "\n" +
                "if [ -x /usr/bin/nload ] && cat /proc/net/dev >/dev/null 2>&1; then\n" +
                "  exec /usr/bin/nload \"$@\"\n" +
                "fi\n" +
                "\n" +
                "stats_file=\"${MUBUNTU_NETSTATS_FILE:-/host-home/" + NETWORK_STATS_FILE_NAME + "}\"\n" +
                "iface=\"${1:-total0}\"\n" +
                "case \"$iface\" in\n" +
                "  ''|total0|all0)\n" +
                "    title='total traffic'\n" +
                "    rx_key='total_rx_bytes'\n" +
                "    tx_key='total_tx_bytes'\n" +
                "    ;;\n" +
                "  mobile0|rmnet0|cell0)\n" +
                "    title='mobile traffic'\n" +
                "    rx_key='mobile_rx_bytes'\n" +
                "    tx_key='mobile_tx_bytes'\n" +
                "    ;;\n" +
                "  other0|wifi0)\n" +
                "    title='non-mobile traffic'\n" +
                "    rx_key='other_rx_bytes'\n" +
                "    tx_key='other_tx_bytes'\n" +
                "    ;;\n" +
                "  -*)\n" +
                "    title='total traffic'\n" +
                "    rx_key='total_rx_bytes'\n" +
                "    tx_key='total_tx_bytes'\n" +
                "    ;;\n" +
                "  *)\n" +
                "    printf 'nload fallback interfaces: total0 mobile0 other0\\n' >&2\n" +
                "    exit 1\n" +
                "    ;;\n" +
                "esac\n" +
                "\n" +
                "format_rate() {\n" +
                "  awk -v value=\"$1\" 'BEGIN {\n" +
                "    split(\"B/s KiB/s MiB/s GiB/s\", units, \" \");\n" +
                "    unit = 1;\n" +
                "    while (value >= 1024 && unit < 4) {\n" +
                "      value /= 1024;\n" +
                "      unit++;\n" +
                "    }\n" +
                "    printf \"%.2f %s\", value, units[unit];\n" +
                "  }'\n" +
                "}\n" +
                "\n" +
                "format_total() {\n" +
                "  awk -v value=\"$1\" 'BEGIN {\n" +
                "    split(\"B KiB MiB GiB\", units, \" \");\n" +
                "    unit = 1;\n" +
                "    while (value >= 1024 && unit < 4) {\n" +
                "      value /= 1024;\n" +
                "      unit++;\n" +
                "    }\n" +
                "    printf \"%.2f %s\", value, units[unit];\n" +
                "  }'\n" +
                "}\n" +
                "\n" +
                "lookup_value() {\n" +
                "  case \"$1\" in\n" +
                "    total_rx_bytes) printf '%s' \"${total_rx_bytes:-0}\" ;;\n" +
                "    total_tx_bytes) printf '%s' \"${total_tx_bytes:-0}\" ;;\n" +
                "    mobile_rx_bytes) printf '%s' \"${mobile_rx_bytes:-0}\" ;;\n" +
                "    mobile_tx_bytes) printf '%s' \"${mobile_tx_bytes:-0}\" ;;\n" +
                "    other_rx_bytes) printf '%s' \"${other_rx_bytes:-0}\" ;;\n" +
                "    other_tx_bytes) printf '%s' \"${other_tx_bytes:-0}\" ;;\n" +
                "    *) printf '0' ;;\n" +
                "  esac\n" +
                "}\n" +
                "\n" +
                "prev_ts=''\n" +
                "prev_rx=''\n" +
                "prev_tx=''\n" +
                "trap 'printf \"\\\\033[0m\\\\n\"' INT TERM EXIT\n" +
                "\n" +
                "while :; do\n" +
                "  if [ ! -r \"$stats_file\" ]; then\n" +
                "    printf '\\033[H\\033[2Jwaiting for Android network stats...\\n'\n" +
                "    sleep 1\n" +
                "    continue\n" +
                "  fi\n" +
                "\n" +
                "  unset stats_supported timestamp_ms total_rx_bytes total_tx_bytes mobile_rx_bytes mobile_tx_bytes other_rx_bytes other_tx_bytes\n" +
                "  # shellcheck disable=SC1090\n" +
                "  . \"$stats_file\"\n" +
                "\n" +
                "  if [ \"${stats_supported:-0}\" != '1' ]; then\n" +
                "    printf '\\033[H\\033[2Jnload fallback unavailable\\n\\nAndroid TrafficStats is unsupported on this device.\\n'\n" +
                "    sleep 2\n" +
                "    continue\n" +
                "  fi\n" +
                "\n" +
                "  now_ts=\"${timestamp_ms:-0}\"\n" +
                "  now_rx=\"$(lookup_value \"$rx_key\")\"\n" +
                "  now_tx=\"$(lookup_value \"$tx_key\")\"\n" +
                "  rx_rate=0\n" +
                "  tx_rate=0\n" +
                "\n" +
                "  if [ -n \"$prev_ts\" ] && [ \"$now_ts\" -gt \"$prev_ts\" ]; then\n" +
                "    delta_ts=$((now_ts - prev_ts))\n" +
                "    delta_rx=$((now_rx - prev_rx))\n" +
                "    delta_tx=$((now_tx - prev_tx))\n" +
                "    if [ \"$delta_rx\" -lt 0 ]; then delta_rx=0; fi\n" +
                "    if [ \"$delta_tx\" -lt 0 ]; then delta_tx=0; fi\n" +
                "    rx_rate=$((delta_rx * 1000 / delta_ts))\n" +
                "    tx_rate=$((delta_tx * 1000 / delta_ts))\n" +
                "  fi\n" +
                "\n" +
                "  prev_ts=\"$now_ts\"\n" +
                "  prev_rx=\"$now_rx\"\n" +
                "  prev_tx=\"$now_tx\"\n" +
                "\n" +
                "  printf '\\033[H\\033[2J'\n" +
                "  printf 'nload fallback: %s\\n\\n' \"$title\"\n" +
                "  printf 'Android blocks /proc/net/dev and /sys/class/net in this sandbox.\\n'\n" +
                "  printf 'Using host TrafficStats instead of the original nload backend.\\n\\n'\n" +
                "  printf 'RX rate : %s\\n' \"$(format_rate \"$rx_rate\")\"\n" +
                "  printf 'TX rate : %s\\n' \"$(format_rate \"$tx_rate\")\"\n" +
                "  printf 'RX total: %s\\n' \"$(format_total \"$now_rx\")\"\n" +
                "  printf 'TX total: %s\\n\\n' \"$(format_total \"$now_tx\")\"\n" +
                "  printf 'Interfaces: total0 mobile0 other0\\n'\n" +
                "  sleep 1\n" +
                "done\n";
    }

    private String buildUbuntuSources(String uri) {
        return "Types: deb\n" +
                "URIs: " + uri + "\n" +
                "Suites: noble noble-updates noble-backports noble-security\n" +
                "Components: main restricted universe multiverse\n" +
                "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n";
    }

    private String buildWelcomeMessage() {
        StringBuilder banner = new StringBuilder();
        banner.append("\033[1;32mwelcome to Mubuntu\033[0m\r\n");
        banner.append("\033[1;36mkernel:\033[0m ")
                .append(readHostKernelVersion())
                .append("\r\n");
        banner.append("\033[1;33muse apt update and install new programe\033[0m\r\n");
        return banner.toString();
    }

    private String readHostKernelVersion() {
        ProcessBuilder unameBuilder = new ProcessBuilder("/system/bin/uname", "-r");
        unameBuilder.redirectErrorStream(true);

        try {
            Process unameProcess = unameBuilder.start();
            String output = readFully(unameProcess).trim();
            int exitCode = unameProcess.waitFor();
            if (exitCode == 0 && output.length() > 0) {
                return output;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read kernel version", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.getProperty("os.version", "unknown");
    }

    private void ensureAndroidGroupMappings(File rootfsDir) throws IOException {
        File groupFile = new File(rootfsDir, GROUP_FILE);
        String existing = groupFile.exists() ? readFile(groupFile) : "";
        StringBuilder content = new StringBuilder(existing);
        if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
            content.append('\n');
        }

        int[] gids = readHostGroupIds();
        boolean changed = false;
        for (int gid : gids) {
            if (gid < 0 || groupFileContainsGid(content, gid)) {
                continue;
            }
            content.append("android_gid_")
                    .append(gid)
                    .append(":x:")
                    .append(gid)
                    .append(":\n");
            changed = true;
        }

        if (changed) {
            writeTextFile(groupFile, content.toString());
        }
    }

    private boolean groupFileContainsGid(CharSequence groupFileContent, int gid) {
        String needle = ":" + gid + ":";
        return groupFileContent.toString().contains(needle);
    }

    private int[] readHostGroupIds() throws IOException {
        ProcessBuilder idBuilder = new ProcessBuilder("/system/bin/id", "-G");
        idBuilder.redirectErrorStream(true);
        Process idProcess = idBuilder.start();
        String output = readFully(idProcess).trim();

        int exitCode;
        try {
            exitCode = idProcess.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading Android group ids", e);
        }
        if (exitCode != 0) {
            throw new IOException("Failed to read Android group ids: " + output);
        }
        if (output.length() == 0) {
            return new int[0];
        }

        String[] parts = output.split("\\s+");
        int[] gids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                gids[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                gids[i] = -1;
            }
        }
        return gids;
    }

    private String runBundledTar(String nativeBinDir, File stagedTar, File rootfsDir)
            throws IOException {
        ProcessBuilder tarBuilder = new ProcessBuilder(
                nativeBinDir + "/" + BUNDLED_TAR_NAME,
                "-xf",
                stagedTar.getAbsolutePath(),
                "-C",
                rootfsDir.getAbsolutePath()
        );
        tarBuilder.redirectErrorStream(true);
        java.util.Map<String, String> env = tarBuilder.environment();
        env.put("LD_LIBRARY_PATH", nativeBinDir);
        env.put("PATH", nativeBinDir + ":/system/bin:/system/xbin:/product/bin:/vendor/bin");

        Process tarProcess = tarBuilder.start();
        String tarOutput = readFully(tarProcess);
        int exitCode;
        try {
            exitCode = tarProcess.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while extracting Ubuntu rootfs", e);
        }

        if (exitCode != 0 && !containsOnlyExpectedHardLinkFailures(tarOutput)) {
            throw new IOException("Ubuntu rootfs extraction failed.\n" + tarOutput);
        }
        return tarOutput;
    }

    private boolean hasUsableUbuntuRootfs(File rootfsDir) {
        return new File(rootfsDir, ROOTFS_READY_MARKER_NAME).isFile()
                && hasCoreUbuntuFiles(rootfsDir);
    }

    private boolean hasCoreUbuntuFiles(File rootfsDir) {
        return new File(rootfsDir, "usr/bin/bash").isFile()
                && new File(rootfsDir, "usr/lib/os-release").isFile()
                && new File(rootfsDir, "etc/os-release").exists();
    }

    private boolean containsOnlyExpectedHardLinkFailures(String tarOutput) {
        boolean sawPerl = false;
        boolean sawUncompress = false;
        boolean sawSummary = false;

        String[] lines = tarOutput.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() == 0) {
                continue;
            }
            if (line.contains("usr/bin/perl5.38.2: Cannot hard link to")) {
                sawPerl = true;
                continue;
            }
            if (line.contains("usr/bin/uncompress: Cannot hard link to")) {
                sawUncompress = true;
                continue;
            }
            if (line.contains("Exiting with failure status due to previous errors")) {
                sawSummary = true;
                continue;
            }
            return false;
        }
        return sawPerl && sawUncompress && sawSummary;
    }

    private void applyUbuntuHardLinkFallbacks(File rootfsDir) throws IOException {
        copyIfMissing(
                new File(rootfsDir, "usr/bin/perl"),
                new File(rootfsDir, "usr/bin/perl5.38.2")
        );
        copyIfMissing(
                new File(rootfsDir, "usr/bin/gunzip"),
                new File(rootfsDir, "usr/bin/uncompress")
        );
    }

    private void copyIfMissing(File source, File target) throws IOException {
        if (target.exists()) {
            return;
        }
        if (!source.isFile()) {
            throw new IOException("Missing fallback source: " + source.getAbsolutePath());
        }
        copyFile(source, target);
        target.setExecutable(source.canExecute(), true);
        target.setReadable(source.canRead(), true);
        target.setWritable(source.canWrite(), true);
        target.setLastModified(source.lastModified());
    }

    private void ensureDirectory(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Failed to create directory: " + dir.getAbsolutePath());
        }
    }

    private void copyAssetToFile(String assetPath, File target) throws IOException {
        InputStream input = null;
        try {
            input = context.getAssets().open(assetPath);
            copyStreamToFile(input, target);
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private void copyFile(File source, File target) throws IOException {
        InputStream input = null;
        try {
            input = new FileInputStream(source);
            copyStreamToFile(input, target);
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }

    private void copyStreamToFile(InputStream input, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }

        OutputStream output = null;
        try {
            output = new FileOutputStream(target, false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    private String readFully(Process process) throws IOException {
        BufferedReader reader = null;
        StringBuilder builder = new StringBuilder();
        try {
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()), 4096);
            char[] buffer = new char[4096];
            int len;
            while ((len = reader.read(buffer)) > 0) {
                builder.append(buffer, 0, len);
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
        return builder.toString();
    }

    private void writeMarker(File markerFile, String content) throws IOException {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(markerFile, false),
                    StandardCharsets.UTF_8);
            writer.write(content);
            writer.write('\n');
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory() && !isSymbolicLink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("Failed to delete: " + file.getAbsolutePath());
        }
    }

    private boolean isSymbolicLink(File file) throws IOException {
        File absoluteFile = file.getAbsoluteFile();
        File canonicalFile = file.getCanonicalFile();
        return !absoluteFile.getAbsolutePath().equals(canonicalFile.getAbsolutePath());
    }

    private void sendOutput(String text) {
        if (listener != null) {
            listener.onOutput(text);
        }
    }

    private void writeTextFile(File target, String content) throws IOException {
        if (target.exists() && content.equals(readFile(target))) {
            return;
        }
        OutputStreamWriter writer = null;
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                ensureDirectory(parent);
            }
            writer = new OutputStreamWriter(new FileOutputStream(target, false),
                    StandardCharsets.UTF_8);
            writer.write(content);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void writeExecutableTextFile(File target, String content) throws IOException {
        writeTextFile(target, content);
        target.setReadable(true, false);
        target.setWritable(true, true);
        target.setExecutable(true, false);
    }

    private String readFile(File file) throws IOException {
        InputStream input = null;
        try {
            input = new FileInputStream(file);
            StringBuilder builder = new StringBuilder();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                builder.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            }
            return builder.toString();
        } finally {
            if (input != null) {
                input.close();
            }
        }
    }
}
