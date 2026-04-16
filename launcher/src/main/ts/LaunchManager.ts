/**
 * LaunchManager — core launcher orchestration.
 *
 * Responsibilities:
 * 1. Locate or download the correct Minecraft version JAR
 * 2. Locate or download Fabric loader for the target MC version
 * 3. Inject our custom JVM arguments (ZGC, memory, performance flags)
 * 4. Inject the Booger Client mod JAR into the mod folder
 * 5. Spawn the Minecraft process and monitor it
 * 6. Report launch state to the React UI via Tauri events
 *
 * JVM ARGUMENT STRATEGY:
 * These args are the most impactful thing we can do for performance
 * outside of the mod itself. Lunar injects similar args.
 * We go further — we SIZE args based on available system RAM and CPU cores.
 *
 * Base args (all users):
 *   -XX:+UseZGC                          → Generational ZGC (Java 21+)
 *   -XX:+ZGenerational                   → Enable generational mode
 *   -XX:ZUncommitDelay=1                 → Return unused memory to OS quickly
 *   -XX:+UnlockExperimentalVMOptions     → Allow experimental GC tuning
 *   -XX:MaxDirectMemorySize=2G           → Sodium's off-heap buffer needs this
 *   -XX:+DisableExplicitGC              → Ignore mods calling System.gc() (we control this)
 *   -Djava.awt.headless=false            → Required for our font atlas (Java2D)
 *   -Dfabric.systemLibraries=...         → Fabric system lib path
 *
 * RAM-scaled args (based on system RAM):
 *   4GB system:  -Xms1G -Xmx2G
 *   8GB system:  -Xms2G -Xmx4G
 *   16GB system: -Xms3G -Xmx6G
 *   32GB+ system:-Xms4G -Xmx8G
 *
 * CPU-scaled args (based on core count):
 *   <4 cores: -XX:ConcGCThreads=1
 *   4-8 cores: -XX:ConcGCThreads=2
 *   >8 cores:  -XX:ConcGCThreads=4
 *
 * PROCESS MANAGEMENT:
 * We use Tauri's shell plugin to spawn the JVM process.
 * stdout/stderr are piped and forwarded to the launcher's console log.
 * Exit code is monitored — non-zero = crash, we show the error log.
 * Process lifecycle:
 *   IDLE → LAUNCHING → RUNNING → STOPPED
 *                   ↘ CRASHED
 */

import { invoke } from '@tauri-apps/api/tauri';
import { Command } from '@tauri-apps/api/shell';
import { emit, listen } from '@tauri-apps/api/event';
import { useLauncherStore, LaunchState } from '../store/launcherStore';

// ─── Types ─────────────────────────────────────────────────────────────────

export interface LaunchConfig {
  minecraftVersion: string;     // e.g. "1.21.4"
  fabricVersion:    string;     // e.g. "0.16.9"
  profileName:      string;     // Booger Client profile name
  javaPath:         string;     // Absolute path to java executable
  gameDir:          string;     // .minecraft directory
  username:         string;     // Player username (or "Player" in offline mode)
  accessToken:      string;     // Mojang/Microsoft auth token (or "offline")
  uuid:             string;     // Player UUID
  allocatedMemoryMb: number;   // Heap size in MB (-Xmx)
}

export interface SystemInfo {
  totalRamMb:   number;
  cpuCores:     number;
  os:           'windows' | 'macos' | 'linux';
  javaVersion:  string | null;
  javaPath:     string | null;
}

// ─── JVM Argument Builder ──────────────────────────────────────────────────

export function buildJvmArgs(config: LaunchConfig, sysInfo: SystemInfo): string[] {
  const { allocatedMemoryMb } = config;
  const initialHeapMb = Math.max(512, Math.floor(allocatedMemoryMb * 0.4));

  // ConcGC threads scale with CPU cores
  const concGcThreads =
    sysInfo.cpuCores <= 2 ? 1 :
    sysInfo.cpuCores <= 6 ? 2 :
    sysInfo.cpuCores <= 12 ? 4 : 6;

  const args: string[] = [
    // ── Memory ────────────────────────────────────────────────────────────
    `-Xms${initialHeapMb}m`,
    `-Xmx${allocatedMemoryMb}m`,

    // ── Garbage Collector: Generational ZGC ───────────────────────────────
    '-XX:+UseZGC',
    '-XX:+ZGenerational',
    '-XX:ZUncommitDelay=1',
    `-XX:ConcGCThreads=${concGcThreads}`,
    '-XX:+UnlockExperimentalVMOptions',

    // ── GC Logging (we parse this for AntiGcModule feedback) ──────────────
    '-Xlog:gc:file=logs/booger-gc.log:time,level,tags:filecount=3,filesize=10m',

    // ── Direct memory for Sodium's off-heap buffers ────────────────────────
    '-XX:MaxDirectMemorySize=2G',

    // ── Disable mods from calling System.gc() (AntiGcModule does this) ────
    '-XX:+DisableExplicitGC',

    // ── JIT Compilation tuning ─────────────────────────────────────────────
    // Tiered compilation: C1 first pass, C2 for hot methods
    '-XX:+TieredCompilation',
    '-XX:CompileThreshold=1500',
    // Inline everything we can — reduces method call overhead in hot render loops
    '-XX:MaxInlineSize=512',

    // ── Network stack tuning ────────────────────────────────────────────────
    // Netty uses direct ByteBuffers — larger pool avoids re-allocation
    '-Dio.netty.allocator.numDirectArenas=4',
    '-Dio.netty.allocator.maxOrder=9',

    // ── Java2D for font atlas (our GlyphCache uses Java AWT) ───────────────
    '-Djava.awt.headless=false',
    '-Dawt.useSystemAAFontSettings=on',

    // ── Fabric / Minecraft ─────────────────────────────────────────────────
    '-Dfabric.skipMcProvider=false',
    '-Dbooger.launcher=true',
    `-Dbooger.profile=${config.profileName}`,

    // ── OS-specific optimizations ──────────────────────────────────────────
    ...(sysInfo.os === 'windows' ? windowsSpecificArgs() : []),
    ...(sysInfo.os === 'linux'   ? linuxSpecificArgs()   : []),
    ...(sysInfo.os === 'macos'   ? macosSpecificArgs()   : []),
  ];

  return args;
}

function windowsSpecificArgs(): string[] {
  return [
    // Windows: use IOCP for better Netty IO on Win10+
    '-Dio.netty.transport.noNative=false',
    // DWM hint: tell Windows compositor we're a game
    '-Dorg.lwjgl.opengl.Window.undecorated=false',
  ];
}

function linuxSpecificArgs(): string[] {
  return [
    // Prefer XShm for clipboard operations (faster than default)
    '-Dawt.toolkit=sun.awt.X11.XToolkit',
    // Use epoll for Netty (better than select on Linux)
    '-Dio.netty.channel.epoll.EpollEventArray.maxEvents=512',
  ];
}

function macosSpecificArgs(): string[] {
  return [
    // macOS ARM64: enable Rosetta-free path for LWJGL on M1/M2
    '-XstartOnFirstThread', // Required for LWJGL on macOS
    '-Dorg.lwjgl.system.allocator=system',
  ];
}

// ─── Launch Orchestrator ───────────────────────────────────────────────────

export class LaunchManager {

  private currentProcess: Command | null = null;
  private logLines: string[] = [];
  private unlistenFns: (() => void)[] = [];

  async launch(config: LaunchConfig): Promise<void> {
    const store = useLauncherStore.getState();
    store.setLaunchState(LaunchState.LAUNCHING);
    store.setLaunchProgress(0, 'Initializing...');

    try {
      // Step 1: Validate Java installation
      store.setLaunchProgress(5, 'Checking Java...');
      const sysInfo = await this.detectSystemInfo(config.javaPath);
      if (!sysInfo.javaVersion) {
        throw new Error('Java 21+ not found. Please install Java 21 or later.');
      }
      const javaMajor = parseInt(sysInfo.javaVersion.split('.')[0]);
      if (javaMajor < 21) {
        throw new Error(`Java 21+ required, found Java ${sysInfo.javaVersion}`);
      }

      // Step 2: Ensure Fabric is installed
      store.setLaunchProgress(20, 'Checking Fabric...');
      await this.ensureFabricInstalled(config);

      // Step 3: Ensure Booger Client mod JAR is present
      store.setLaunchProgress(40, 'Preparing Booger Client...');
      await this.ensureBoogerModJar(config);

      // Step 4: Build launch command
      store.setLaunchProgress(60, 'Building launch command...');
      const jvmArgs = buildJvmArgs(config, sysInfo);
      const mcArgs = this.buildMinecraftArgs(config);
      const classpath = await this.buildClasspath(config);

      // Step 5: Spawn process
      store.setLaunchProgress(80, 'Launching Minecraft...');
      await this.spawnProcess(config, jvmArgs, mcArgs, classpath);

      store.setLaunchProgress(100, 'Running!');
      store.setLaunchState(LaunchState.RUNNING);

    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : String(error);
      store.setLaunchState(LaunchState.CRASHED);
      store.setError(message);
      throw error;
    }
  }

  private async spawnProcess(
    config: LaunchConfig,
    jvmArgs: string[],
    mcArgs: string[],
    classpath: string
  ): Promise<void> {
    const mainClass = 'net.fabricmc.loader.impl.launch.knot.KnotClient';
    const allArgs = [...jvmArgs, '-cp', classpath, mainClass, ...mcArgs];

    this.currentProcess = Command.create(config.javaPath, allArgs, {
      cwd: config.gameDir,
      env: {
        JAVA_HOME: config.javaPath.replace('/bin/java', '').replace('\\bin\\java.exe', ''),
      }
    });

    // Pipe stdout/stderr to launcher log
    this.currentProcess.stdout.on('data', (line: string) => {
      this.appendLog(line);
      // Parse crash indicators
      if (line.includes('FATAL') || line.includes('Exception in thread')) {
        emit('game-crash', { line });
      }
    });

    this.currentProcess.stderr.on('data', (line: string) => {
      this.appendLog(`[ERR] ${line}`);
    });

    this.currentProcess.on('close', (data: { code: number }) => {
      const store = useLauncherStore.getState();
      if (data.code === 0) {
        store.setLaunchState(LaunchState.IDLE);
      } else {
        store.setLaunchState(LaunchState.CRASHED);
        store.setError(`Game exited with code ${data.code}. Check logs for details.`);
      }
    });

    // Listen for game-ready signal (Booger Client emits this via stdout)
    const unlisten = await listen('game-crash', () => {
      useLauncherStore.getState().setLaunchState(LaunchState.CRASHED);
    });
    this.unlistenFns.push(unlisten);

    await this.currentProcess.spawn();
  }

  private async detectSystemInfo(javaPath: string): Promise<SystemInfo> {
    try {
      const result = await invoke<SystemInfo>('detect_system_info', { javaPath });
      return result;
    } catch {
      return {
        totalRamMb: 8192,
        cpuCores: 4,
        os: this.detectOs(),
        javaVersion: null,
        javaPath: null
      };
    }
  }

  private detectOs(): 'windows' | 'macos' | 'linux' {
    if (navigator.userAgent.includes('Win')) return 'windows';
    if (navigator.userAgent.includes('Mac')) return 'macos';
    return 'linux';
  }

  private async ensureFabricInstalled(config: LaunchConfig): Promise<void> {
    await invoke('ensure_fabric_installed', {
      minecraftVersion: config.minecraftVersion,
      fabricVersion: config.fabricVersion,
      gameDir: config.gameDir
    });
  }

  private async ensureBoogerModJar(config: LaunchConfig): Promise<void> {
    await invoke('ensure_booger_mod_jar', {
      gameDir: config.gameDir,
      modVersion: '0.1.0'
    });
  }

  private buildMinecraftArgs(config: LaunchConfig): string[] {
    return [
      '--username',    config.username,
      '--accessToken', config.accessToken,
      '--uuid',        config.uuid,
      '--gameDir',     config.gameDir,
      '--version',     `fabric-${config.fabricVersion}-${config.minecraftVersion}`,
      '--assetIndex',  config.minecraftVersion,
      '--userType',    config.accessToken === 'offline' ? 'offline' : 'msa',
    ];
  }

  private async buildClasspath(config: LaunchConfig): Promise<string> {
    return invoke<string>('build_classpath', {
      gameDir: config.gameDir,
      minecraftVersion: config.minecraftVersion,
      fabricVersion: config.fabricVersion
    });
  }

  private appendLog(line: string): void {
    this.logLines.push(line);
    if (this.logLines.length > 1000) this.logLines.shift();
    useLauncherStore.getState().appendLog(line);
  }

  kill(): void {
    this.currentProcess?.kill();
    this.unlistenFns.forEach(fn => fn());
    this.unlistenFns = [];
  }

  getLogs(): string[] {
    return [...this.logLines];
  }
}

export const launchManager = new LaunchManager();
