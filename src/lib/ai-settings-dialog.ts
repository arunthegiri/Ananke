/**
 * Open/close state for the AI provider settings dialog, shared by every entry
 * point (theme popover, Copilot model picker, Copilot "unavailable" state)
 * without prop-drilling. The dialog itself is mounted once, in TopBar.
 */
type State = { open: boolean; provider: string | null };

let state: State = { open: false, provider: null };
const listeners = new Set<() => void>();

function set(next: State) {
  state = next;
  listeners.forEach((l) => l());
}

export function openAiSettings(provider?: string) {
  set({ open: true, provider: provider ?? null });
}

export function closeAiSettings() {
  set({ ...state, open: false });
}

export function subscribeAiSettings(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function getAiSettings(): State {
  return state;
}

const CLOSED: State = { open: false, provider: null };
export function getAiSettingsServer(): State {
  return CLOSED;
}
