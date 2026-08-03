/**
 * The chime that plays when an answer arrives.
 *
 * Synthesised with Web Audio rather than shipped as an .mp3: it is a two-note
 * tone, so a file would be a binary asset in the repo, a network fetch on first
 * play, and a licence to keep track of, for something twelve lines of code
 * produce exactly.
 *
 * Everything here fails silently. A blocked AudioContext, a browser without Web
 * Audio, or a machine with no output device must never break the chat — the
 * answer is on screen either way, and the sound is a courtesy.
 */

const STORAGE_KEY = 'hr_sound';

/**
 * One context for the page. Creating one per notification leaks them — browsers
 * cap the number a document may hold, and the cap is low enough to hit in a long
 * conversation.
 */
let context: AudioContext | null = null;

type WindowWithAudio = Window &
  typeof globalThis & { webkitAudioContext?: typeof AudioContext };

function audioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  if (context) return context;

  const Ctor = window.AudioContext ?? (window as WindowWithAudio).webkitAudioContext;
  if (!Ctor) return null;

  try {
    context = new Ctor();
    return context;
  } catch {
    return null;
  }
}

/** On by default — the point of asking for it is to hear it. */
export function isSoundOn(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    return window.localStorage.getItem(STORAGE_KEY) !== 'off';
  } catch {
    // Private mode with storage blocked. Default to on rather than silently
    // disabling a feature the employee cannot then re-enable.
    return true;
  }
}

export function setSoundOn(on: boolean): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off');
  } catch {
    // Preference will not survive a reload. Not worth surfacing.
  }
}

/**
 * A soft two-note rise, about a fifth apart.
 *
 * Quiet (peak gain 0.07) and short (~0.22s) on purpose. This fires every time an
 * answer lands, in an office, possibly on speakers — a notification someone has
 * to mute is worse than no notification.
 */
export function playNotification(): void {
  if (!isSoundOn()) return;

  const ctx = audioContext();
  if (!ctx) return;

  // Autoplay policy suspends contexts created before a user gesture. By the time
  // an answer arrives the employee has pressed send, so this resolves — but it is
  // a promise, so it cannot be awaited here without delaying the tone.
  if (ctx.state === 'suspended') void ctx.resume().catch(() => {});

  try {
    const now = ctx.currentTime;
    const notes = [
      { hz: 660, at: 0 },
      { hz: 990, at: 0.09 },
    ];

    for (const note of notes) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      // Sine, not square or saw: no harmonics, so it reads as a chime rather than
      // an alert. This is not an error, it is an answer arriving.
      osc.type = 'sine';
      osc.frequency.value = note.hz;

      const start = now + note.at;
      const end = start + 0.13;

      // A ramped envelope. Starting or stopping a gain abruptly puts a click at
      // the edge, which is audible and sounds like a fault.
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(0.07, start + 0.012);
      gain.gain.exponentialRampToValueAtTime(0.0001, end);

      osc.connect(gain).connect(ctx.destination);
      osc.start(start);
      osc.stop(end + 0.02);
    }
  } catch {
    // Nothing to recover: no sound this time.
  }
}
