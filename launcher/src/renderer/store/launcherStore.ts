import { create } from 'zustand';

export enum LaunchState {
  IDLE       = 'IDLE',
  LAUNCHING  = 'LAUNCHING',
  RUNNING    = 'RUNNING',
  CRASHED    = 'CRASHED',
}

interface LauncherState {
  launchState:    LaunchState;
  progress:       number;         // 0-100
  progressLabel:  string;
  error:          string | null;
  logs:           string[];
  maxLogs:        number;

  // User settings
  allocatedMemoryMb: number;
  selectedProfile:   string;
  username:          string;
  javaPath:          string;

  // Actions
  setLaunchState:    (state: LaunchState) => void;
  setLaunchProgress: (progress: number, label: string) => void;
  setError:          (error: string | null) => void;
  appendLog:         (line: string) => void;
  clearLogs:         () => void;
  setAllocatedMemory:(mb: number) => void;
  setSelectedProfile:(profile: string) => void;
  setUsername:       (name: string) => void;
  setJavaPath:       (path: string) => void;
}

export const useLauncherStore = create<LauncherState>((set) => ({
  launchState:       LaunchState.IDLE,
  progress:          0,
  progressLabel:     '',
  error:             null,
  logs:              [],
  maxLogs:           500,
  allocatedMemoryMb: 4096,
  selectedProfile:   'default',
  username:          'Player',
  javaPath:          '',

  setLaunchState: (state) => set({ launchState: state, error: null }),

  setLaunchProgress: (progress, progressLabel) =>
    set({ progress, progressLabel }),

  setError: (error) => set({ error }),

  appendLog: (line) =>
    set((s) => ({
      logs: s.logs.length >= s.maxLogs
        ? [...s.logs.slice(1), line]
        : [...s.logs, line]
    })),

  clearLogs: () => set({ logs: [] }),

  setAllocatedMemory: (allocatedMemoryMb) => set({ allocatedMemoryMb }),
  setSelectedProfile: (selectedProfile)   => set({ selectedProfile }),
  setUsername:        (username)          => set({ username }),
  setJavaPath:        (javaPath)          => set({ javaPath }),
}));
