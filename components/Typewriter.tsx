'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import RichText from '@/components/RichText';

/**
 * Progressive text reveal for the bot's answers.
 *
 * <p>Two different problems live here, and only one of them is animation.
 *
 * <p>The first is that a generated answer already arrives a fragment at a time over
 * SSE, so it needs no fake typing — the model's own pace is the effect. What it
 * needed was to stop rendering as raw text: mid-stream, `**30 days` showed its
 * asterisks and then snapped into bold once the turn finalised. {@link
 * hideDanglingMarkup} is what fixes that, and {@link useSmoothedStream} paces the
 * bursts the model arrives in — together they are why this file exists at all
 * rather than a one-line CSS animation.
 *
 * <p>The second is that most answers in this app are <em>not</em> generated. A
 * curated intent, a Darwinbox record, an escalation offer — those are strings the
 * client already holds, and they appeared instantly while a model answer typed
 * itself out. The inconsistency read as the fast ones being canned. {@link
 * useTypewriter} paces those to match.
 */

/** Roughly 60 characters a second: fast enough to read along with, not a stutter. */
const CHARS_PER_TICK = 2;
const TICK_MS = 32;

/**
 * Whether the visitor asked for less motion, watched live.
 *
 * <p>Read through matchMedia rather than CSS because a typewriter cannot be turned
 * off with a media query — the text has to be complete in the DOM, not merely
 * un-animated, or a screen reader announces a half-written sentence.
 */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);

  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    setReduced(query.matches);

    const onChange = (event: MediaQueryListEvent) => setReduced(event.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  return reduced;
}

/**
 * Hides a markup marker that has opened but not yet closed, keeping its words.
 *
 * <p>The distinction matters more than it sounds, and getting it wrong is what made
 * the first version of this stutter. Dropping the whole unmatched tail — everything
 * from the open `**` onward — freezes the visible text for as long as the emphasised
 * phrase takes to arrive, then pops all of it in at once. On an HR answer, where
 * every figure and deadline is bold, that is a stall and a jump every few words.
 *
 * <p>So only the two marker characters are removed. `**30 day` renders as "30 day"
 * in plain text and keeps flowing; when the closing `**` lands, the same words turn
 * bold. The reader sees a weight change rather than a jump, which is what a streamed
 * answer in Claude or ChatGPT does.
 *
 * <p>Only handles the subset {@link RichText} understands. Tables are excluded from
 * the reveal by {@link useTypewriter} instead: half a pipe row is meaningless
 * however it is rendered.
 */
export function hideDanglingMarkup(text: string): string {
  let out = text;

  // An odd count means the last one opened something still unclosed. Excise those
  // two characters in place rather than truncating from them.
  if ((out.match(/\*\*/g) ?? []).length % 2 === 1) {
    const at = out.lastIndexOf('**');
    out = out.slice(0, at) + out.slice(at + 2);
  }

  // A lone trailing asterisk is the first half of a marker whose second half has
  // not arrived. Left in, it renders as a stray `*` for one frame.
  //
  // The `**` exclusion is load-bearing: a text ending in a *complete* closing
  // marker also ends in `*`, and shaving one character off it broke the pair and
  // showed `**12 days*` on the very frame the phrase should have turned bold.
  if (out.endsWith('*') && !out.endsWith('**')) out = out.slice(0, -1);

  if ((out.match(/`/g) ?? []).length % 2 === 1) {
    const at = out.lastIndexOf('`');
    out = out.slice(0, at) + out.slice(at + 1);
  }

  return out;
}

/**
 * Reveals {@code text} over time, returning the slice visible so far.
 *
 * @param enabled false renders the whole string immediately — used for restored
 *     history, where replaying every answer on page load would be absurd, and for
 *     anyone who has asked for reduced motion
 */
export function useTypewriter(text: string, enabled: boolean): { visible: string; done: boolean } {
  const reduced = usePrefersReducedMotion();
  const active = enabled && !reduced;

  const [count, setCount] = useState(active ? 0 : text.length);

  // Which string is being typed. Restarting on the text itself would replay the
  // whole animation every time a parent re-renders with an equal-but-new string.
  const typing = useRef(text);

  useEffect(() => {
    if (typing.current !== text) {
      typing.current = text;
      setCount(active ? 0 : text.length);
    }
  }, [text, active]);

  useEffect(() => {
    if (!active) {
      setCount(text.length);
      return;
    }
    if (count >= text.length) return;

    const timer = setTimeout(
      () => setCount((n) => Math.min(text.length, n + CHARS_PER_TICK)),
      TICK_MS
    );
    return () => clearTimeout(timer);
  }, [active, count, text]);

  return { visible: text.slice(0, count), done: count >= text.length };
}

/**
 * Markdown that types itself out.
 *
 * <p>Tables opt out: {@link RichText} needs a whole pipe block to parse one, so a
 * partially revealed table renders as stray pipes and then reflows into a grid. An
 * answer containing one is shown complete, which is also the honest reading of what
 * a table is for.
 */
export function TypedRichText({
  text,
  animate,
  onTick,
  onAnimated,
}: {
  text: string;
  animate: boolean;
  /** Called as the reveal grows, so a transcript can keep itself scrolled. */
  onTick?: () => void;
  /**
   * Fired once, on mount, when this render is going to animate.
   *
   * <p>Exists so the caller can remember that it happened. Switching conversations
   * unmounts the transcript, and a remounted typewriter starts its counter at zero —
   * so an answer given ten minutes ago typed itself out again every time the thread
   * was reopened. Whether a message has already been revealed outlives this
   * component, so the caller has to hold it.
   */
  onAnimated?: () => void;
}) {
  const hasTable = useMemo(() => /^\s*\|.+\|\s*$/m.test(text), [text]);

  /**
   * Decided once, on mount, and then immune to the prop changing.
   *
   * <p>Not merely tidy — without the latch this reveal cut itself short. The caller
   * marks the message as seen from the mount effect below, which flips {@code animate}
   * false on the parent's very next render, and the parent re-renders immediately
   * (the waiting state clears the moment the answer lands). The typewriter would
   * read that as "no longer animating" and jump straight to the full text.
   */
  const latched = useRef(animate && !hasTable);
  const { visible, done } = useTypewriter(text, latched.current);

  // Mount only, deliberately: reported when the reveal begins rather than when it
  // finishes, so a thread switched away from mid-animation still counts as seen.
  // Finishing is not the interesting event — starting is.
  useEffect(() => {
    if (latched.current) onAnimated?.();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    onTick?.();
  }, [visible, onTick]);

  // The caret is a ::after on the last block rather than an element after the
  // markup. RichText emits block-level <p>/<ul>, so a sibling span landed on its
  // own line below the text instead of trailing the last character.
  return (
    <div className={done ? undefined : 'typing-caret-host'}>
      <RichText text={done ? text : hideDanglingMarkup(visible)} />
    </div>
  );
}

/* --------------------------------------------------------------------------
 * Smoothing a live stream
 * ------------------------------------------------------------------------ */

/**
 * Baseline reveal speed, characters per second.
 *
 * <p>About 140 is where this reads as deliberate typing rather than a block
 * arriving. For reference the model's own bursts were measured at 225 chars/sec on
 * a real answer here, which is past reading speed — the cap is the effect.
 */
const TARGET_CHARS_PER_SECOND = 140;

/**
 * Longest the reveal may lag behind what has arrived.
 *
 * <p>Sets the catch-up speed implicitly: a large backlog is released faster so the
 * answer never trails far behind, but by raising the rate smoothly rather than by
 * flushing. Whichever of this and {@link TARGET_CHARS_PER_SECOND} is faster wins.
 */
const MAX_LAG_SECONDS = 2.5;

/**
 * Reveals a streamed answer at a readable pace instead of at the model's.
 *
 * <p>The model does not deliver tokens evenly. Measured on this app: eight seconds
 * of nothing while the weights load and the prompt is evaluated, then 447
 * characters in under two — and the first 90 of those in a single lump, because the
 * server withholds that much to be sure the answer is not a refusal. Rendering
 * arrivals directly means a long wait and then a paragraph appearing at once.
 *
 * <p>So arrivals go into a buffer and are released on a timer. Proportional
 * draining is what makes one knob work for both problems: the 90-character lump is
 * spread over about a second and a half, and a fast tail is still capped to
 * something the eye can follow.
 *
 * @returns push to feed arrivals, drained to await the tail before finalising the
 *     turn, reset to clear between turns
 */
export function useSmoothedStream() {
  const [text, setText] = useState('');
  const pending = useRef('');
  const frame = useRef<number | null>(null);
  const lastAt = useRef(0);
  /** Fractional characters carried between frames, so short frames still advance. */
  const carry = useRef(0);
  const reduced = usePrefersReducedMotion();

  const stop = () => {
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current);
      frame.current = null;
    }
    carry.current = 0;
  };

  // Cancelled on unmount so a pending frame cannot setState into a component that
  // has already gone.
  useEffect(() => stop, []);

  const push = useCallback(
    (chunk: string) => {
      // Reduced motion: no pacing at all. Someone who asked for less movement
      // wants the answer, not a performance of it arriving.
      if (reduced) {
        setText((t) => t + chunk);
        return;
      }

      pending.current += chunk;
      if (frame.current !== null) return;

      /*
       * Driven by requestAnimationFrame rather than a timer, and released by
       * elapsed time rather than a fixed count per tick. Both matter for
       * smoothness: frames are what the compositor actually paints, so a timer at
       * some other interval lands two characters on one frame and none on the next,
       * which is visible as unevenness. Rate-per-second also keeps the speed
       * honest on a 120Hz display and under a dropped frame.
       */
      lastAt.current = performance.now();

      const step = (now: number) => {
        const backlog = pending.current;
        if (!backlog) {
          stop();
          return;
        }

        const elapsed = Math.max(0, now - lastAt.current) / 1000;
        lastAt.current = now;

        const rate = Math.max(TARGET_CHARS_PER_SECOND, backlog.length / MAX_LAG_SECONDS);
        carry.current += rate * elapsed;

        const take = Math.floor(carry.current);
        if (take > 0) {
          carry.current -= take;
          pending.current = backlog.slice(take);
          setText((t) => t + backlog.slice(0, take));
        }

        frame.current = requestAnimationFrame(step);
      };

      frame.current = requestAnimationFrame(step);
    },
    [reduced]
  );

  /**
   * Resolves once everything pushed has been shown.
   *
   * <p>Awaited before the finished message replaces this one. Without it the answer
   * would finalise while the tail was still being revealed, and swapping in the
   * complete text is exactly the dump this exists to avoid.
   */
  const drained = useCallback(
    () =>
      new Promise<void>((resolve) => {
        const check = () => {
          if (!pending.current) resolve();
          else requestAnimationFrame(check);
        };
        check();
      }),
    []
  );

  const reset = useCallback(() => {
    stop();
    pending.current = '';
    setText('');
  }, []);

  return { text, push, drained, reset };
}

/**
 * One line of text at a time, cycling — the greeting on an empty conversation.
 *
 * <p>Crossfades rather than retypes. A greeting that types itself over and over
 * pulls the eye back to it every few seconds, which is the opposite of what a
 * waiting-for-input state should do; a fade is noticed once and then ignored.
 */
export function RotatingText({
  phrases,
  intervalMs = 3600,
  className,
  style,
}: {
  phrases: string[];
  intervalMs?: number;
  className?: string;
  style?: React.CSSProperties;
}) {
  const reduced = usePrefersReducedMotion();
  const [index, setIndex] = useState(0);

  useEffect(() => {
    // One phrase, or reduced motion: nothing should move.
    if (reduced || phrases.length < 2) return;

    const timer = setInterval(() => setIndex((i) => (i + 1) % phrases.length), intervalMs);
    return () => clearInterval(timer);
  }, [reduced, phrases.length, intervalMs]);

  return (
    <span className={className} style={{ display: 'inline-block', ...style }}>
      {/*
        Keyed on the phrase so React swaps the node and the CSS animation runs
        again. aria-live is deliberately absent: this is decoration, and announcing
        a new greeting every few seconds would talk over the page.
      */}
      <span key={phrases[index]} className={reduced ? undefined : 'anim-rotate-in'}>
        {phrases[index]}
      </span>
    </span>
  );
}
