'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
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
 * trimDanglingMarkup} is what fixes that, and it is the reason this file exists at
 * all rather than a one-line CSS animation.
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
 * Drops a markup marker that has been revealed but not yet closed.
 *
 * <p>Without this, revealing `You get **30 days**` one character at a time spends
 * several frames showing a literal `**`, then `**30`, before the bold ever forms.
 * Trimming the unmatched tail means the reader sees plain text turn bold, which is
 * what the eye expects and what a streamed answer in Claude or ChatGPT does.
 *
 * <p>Only handles the subset {@link RichText} understands. A trailing table pipe is
 * left alone deliberately: half a table row is meaningless either way, so tables are
 * excluded from the reveal by {@link useTypewriter} instead.
 */
export function trimDanglingMarkup(text: string): string {
  let out = text;

  // An odd count means the last one opened something that has not closed yet.
  const bold = (out.match(/\*\*/g) ?? []).length;
  if (bold % 2 === 1) out = out.slice(0, out.lastIndexOf('**'));

  const code = (out.match(/`/g) ?? []).length;
  if (code % 2 === 1) out = out.slice(0, out.lastIndexOf('`'));

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
}: {
  text: string;
  animate: boolean;
  /** Called as the reveal grows, so a transcript can keep itself scrolled. */
  onTick?: () => void;
}) {
  const hasTable = useMemo(() => /^\s*\|.+\|\s*$/m.test(text), [text]);
  const { visible, done } = useTypewriter(text, animate && !hasTable);

  useEffect(() => {
    onTick?.();
  }, [visible, onTick]);

  // The caret is a ::after on the last block rather than an element after the
  // markup. RichText emits block-level <p>/<ul>, so a sibling span landed on its
  // own line below the text instead of trailing the last character.
  return (
    <div className={done ? undefined : 'typing-caret-host'}>
      <RichText text={done ? text : trimDanglingMarkup(visible)} />
    </div>
  );
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
