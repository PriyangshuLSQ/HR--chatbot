'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { DEFAULT_FAQS, type FAQ } from '@/lib/chatbot-data';
import { respond, type BotTurn, type ConversationContext } from '@/lib/conversation';
import { useChatbotAuth } from '@/lib/chatbot-auth';
import { useTheme, type Theme } from '@/lib/theme';
import { fetchThreads, putThreads } from '@/lib/hr-api';
import { isSoundOn, playNotification, setSoundOn } from '@/lib/sound';
import { PoweredByLeadSquared, ROBIN_NAME, RobinAvatar } from '@/components/Robin';
import type { Citation, KnowledgeAnswer } from '@/lib/knowledge/types';
import RichText from '@/components/RichText';
import {
  RotatingText,
  TypedRichText,
  hideDanglingMarkup,
  useSmoothedStream,
} from '@/components/Typewriter';
import FeedbackBar from '@/components/FeedbackBar';
import {
  LeaveBalanceCard,
  PayslipCard,
  RequestsCard,
  TicketCard,
} from '@/components/DataCards';
import {
  CalendarIcon,
  ChatIcon,
  CollapseIcon,
  LaptopIcon,
  LogoutIcon,
  MenuIcon,
  MoonIcon,
  PlusIcon,
  SendIcon,
  ShieldIcon,
  SparkIcon,
  SpeakerIcon,
  SpeakerOffIcon,
  SunIcon,
  TicketIcon,
  WalletIcon,
  XIcon,
} from '@/components/Icons';

// ---------------------------------------------------------------------------
// Types & constants
// ---------------------------------------------------------------------------

interface ChatMessage {
  id: string;
  role: 'user' | 'bot';
  text: string;
  at: number;
  turn?: BotTurn;
  /** The user query this bot turn answered — attached to feedback. */
  sourceQuery?: string;
}

interface Thread {
  id: string;
  title: string;
  messages: ChatMessage[];
  updatedAt: number;
}

const QUICK_ACTIONS = [
  { label: 'My leave balance', query: 'what is my leave balance', Icon: CalendarIcon },
  { label: 'Latest payslip', query: 'show me my latest payslip', Icon: WalletIcon },
  { label: 'Expense claim status', query: 'what is the status of my expense claim', Icon: SparkIcon },
  { label: 'Asset request', query: 'status of my laptop request', Icon: LaptopIcon },
];

const SUGGESTIONS = [
  'How many earned leaves do I have left?',
  'When will my salary be credited?',
  'What does my health insurance cover?',
  'How do I claim internet reimbursement?',
  'What is the work from home policy?',
  'How much notice do I need to serve?',
];

/**
 * The rotating second line under the greeting.
 *
 * <p>Every one of these is a thing this assistant can actually do, which is the
 * point: on an empty conversation the hardest question is "what can I even ask", and
 * a cycling line answers it without a wall of text. Nothing here promises a
 * capability the intents and the knowledge base cannot cover.
 */
const GREETING_LINES = [
  'How can I help you today?',
  'Ask me about leave, payroll or benefits.',
  'Need your leave balance or a payslip?',
  'Chasing an expense claim or an asset request?',
  'Ask about insurance, notice periods or WFH.',
];

/** Within this many px of the bottom counts as "still following the conversation". */
const FOLLOW_THRESHOLD_PX = 120;

const WELCOME = `Hello! I'm **Robin**, the LeadSquared HR assistant, available 24/7.

I can answer policy questions on **leave, payroll, benefits, expenses and compliance**, and I can pull your **live Darwinbox records** — leave balances, payslip summaries, and the status of leave, expense and asset requests.

Ask me anything in your own words. Typos are fine.`;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

let idCounter = 0;
function newId(prefix: string) {
  idCounter += 1;
  return `${prefix}_${Date.now().toString(36)}${idCounter}`;
}

function titleFrom(text: string): string {
  const clean = text.trim().replace(/\s+/g, ' ');
  return clean.length > 38 ? `${clean.slice(0, 38)}…` : clean;
}

function timeOf(at: number): string {
  return new Date(at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** Up to two initials for the avatar, from a display name or an email. */
function initialsOf(nameOrEmail: string): string {
  const cleaned = nameOrEmail.split('@')[0].replace(/[._-]+/g, ' ').trim();
  const parts = cleaned.split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

function relativeDay(at: number): string {
  const days = Math.floor((Date.now() - at) / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  return `${days} days ago`;
}

/**
 * Asks the RAG layer over HR's uploaded documents.
 *
 * Server-side because retrieval reads the knowledge store from disk and talks
 * to the local model. Returns null on any failure so the conversation engine
 * falls back to its existing clarify/escalate behaviour — a slow or absent
 * model must never block an answer the curated intents could have given.
 */
/**
 * @param threadId which conversation this belongs to. Sent so the server can read the transcript
 *     from its own copy instead of trusting the `history` below — see `AiController.historyFor`.
 *     The history is still sent as a fallback for the first message of a thread, before the
 *     server has one, and for a signed-out session.
 */
async function askKnowledge(
  question: string,
  history: { role: 'user' | 'bot'; text: string }[],
  threadId: string | null,
  onToken?: (text: string) => void
): Promise<KnowledgeAnswer | null> {
  // Was the last 6 messages. The server caps this itself, so sending the recent conversation
  // rather than two exchanges is what lets the assistant follow a thread it has been part of.
  const body = JSON.stringify({ question, history: history.slice(-40), threadId });

  // Without a token sink there is nothing streaming buys, and the plain endpoint
  // is one round trip instead of a parsed event stream.
  if (!onToken) {
    try {
      const res = await fetch('/api/ai/ask', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body,
      });
      if (!res.ok) return null;
      return (await res.json()) as KnowledgeAnswer;
    } catch {
      return null;
    }
  }

  try {
    const res = await fetch('/api/ai/ask/stream', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body,
    });
    if (!res.ok || !res.body) return null;

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let answer: KnowledgeAnswer | null = null;

    // SSE frames are separated by a blank line and can be split across reads, so
    // the tail of the buffer is kept until its terminator arrives.
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split('\n\n');
      buffer = frames.pop() ?? '';

      for (const frame of frames) {
        const event = /^event:\s*(.+)$/m.exec(frame)?.[1]?.trim();
        /*
         * A fragment containing newlines arrives as several `data:` lines; SSE
         * rejoins them with \n, and dropping that would run words together.
         *
         * Everything after `data:` is content, including a leading space. The SSE
         * spec has clients strip one optional padding space, and doing that here
         * ate the spaces off the front of tokens — the model emits " sick", the
         * server writes `data: sick`, and stripping turned the answer into
         * "advance;sickleavecanbeapplied...". Verified against spring-webmvc 6.1.6:
         * the field prefix is the literal `data:` with no padding, so the first
         * character after it always belongs to the token.
         */
        const data = frame
          .split('\n')
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5))
          .join('\n');

        if (event === 'token') onToken(data);
        else if (event === 'answer') answer = JSON.parse(data) as KnowledgeAnswer;
        else if (event === 'error') return null;
      }
    }

    // The final `answer` event is authoritative — the tokens were a preview of it.
    return answer;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function ChatPage() {
  const [threads, setThreads] = useState<Thread[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [input, setInput] = useState('');
  const [thinking, setThinking] = useState(false);
  /**
   * Text arriving from the model right now, before the turn is finalised.
   *
   * <p>Paced rather than rendered as it arrives. The model delivers a long pause and
   * then a burst — measured here at 447 characters in under two seconds, the first
   * 90 of them in one lump because the server withholds that much to rule out a
   * refusal. Shown directly that reads as a paragraph appearing at once, which is
   * the opposite of the effect streaming is for.
   */
  const {
    text: streamText,
    push: pushToken,
    drained: drainStream,
    reset: resetStream,
  } = useSmoothedStream();
  /**
   * The one message allowed to type itself out.
   *
   * <p>An id rather than a boolean so it cannot leak onto a restored conversation:
   * switching threads or reloading renders every answer complete, and only a reply
   * that has just been produced in this session matches.
   */
  const [animateId, setAnimateId] = useState<string | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  /**
   * Desktop only: the sidebar collapses to an icon rail. Mobile has no room for a
   * rail, so there it stays a drawer that is either open or gone.
   */
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [booted, setBooted] = useState(false);
  const { theme, toggleTheme } = useTheme();
  /** Read from localStorage on mount, not at init — the server has no window. */
  const [soundOn, setSoundOnState] = useState(true);
  const [faqs] = useState<FAQ[]>(DEFAULT_FAQS);

  const router = useRouter();
  const { user, isLoading: authLoading } = useChatbotAuth();

  // No session, no chat — whatever the server's auth posture is.
  //
  // This was `ssoEnabled && !user`, which meant the guard only ran where Entra was
  // configured. Everywhere else — the EC2 pilot included — an unsigned visitor got the
  // full chat window, and the identity below filled the gap with a fabricated employee.
  // So the deployment with the weakest authentication was also the one that showed a
  // name and inbox belonging to nobody, and asked HR questions on their behalf.
  useEffect(() => {
    if (authLoading) return;
    if (!user) router.replace('/login');
  }, [authLoading, user, router]);

  const ctxRef = useRef<ConversationContext>({});
  /**
   * Whether the model streamed this turn. A ref, not state: it is written from
   * inside the token callback and read immediately after the await, and a state
   * update would not have landed by then.
   */
  const streamedRef = useRef(false);

  /**
   * Message ids whose reveal has already been shown.
   *
   * <p>A ref rather than state: recording one must not re-render, or adding an id
   * during the child's mount effect would restart the very animation being recorded.
   * It exists because switching conversations unmounts the transcript — without it, a
   * remounted typewriter began again and every reopened thread replayed its answers.
   */
  const animatedRef = useRef<Set<string>>(new Set());
  const endRef = useRef<HTMLDivElement>(null);
  /** The scrolling transcript, so follow-along can tell whether to yield. */
  const scrollerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Threads are stored per employee, keyed on the address the session was issued to.
  //
  // No fallback. These used to read `?? 'employee@company.com'` and `?? 'Ananya Sharma'`,
  // so a page opened without a session greeted a person who does not work here and filed
  // the conversation under an address nobody owns. An empty string is the honest value for
  // "not signed in", and the guard above means it is never the value we render with: the
  // early return below holds the page until the redirect lands.
  const email = user?.email ?? '';
  const userName = user?.name ?? '';
  /** Just the given name for the greeting — a full legal name reads like a letter. */
  const firstName = userName.trim().split(/\s+/)[0] ?? '';

  const active = threads.find((t) => t.id === activeId) ?? null;
  const messages = active?.messages ?? [];

  // --- boot: restore threads and preferences ---------------------------
  useEffect(() => {
    setSoundOnState(isSoundOn());
    setSidebarCollapsed(localStorage.getItem('hr_sidebar') === 'collapsed');
    // The theme is restored before paint by the bootstrap script in the root layout, and its
    // state is owned by useTheme — nothing to do here.

    // Conversations come from MongoDB via the backend, so they survive a reload
    // and follow the employee to another device — localStorage did neither.
    // A failure starts a fresh thread rather than blocking the chat: someone with
    // an urgent payroll question should not be stuck behind their own history.
    let cancelled = false;

    void (async () => {
      let restored: Thread[] = [];
      try {
        restored = (await fetchThreads()) as unknown as Thread[];
      } catch {
        restored = [];
      }

      if (cancelled) return;

      if (restored.length) {
        setThreads(restored);
        setActiveId(restored[0].id);
      } else {
        const first = freshThread();
        setThreads([first]);
        setActiveId(first.id);
      }
      setBooted(true);
    })();

    return () => {
      cancelled = true;
    };
  }, [email]);

  // Persist so context genuinely survives a reload, not just a re-render.
  //
  // Debounced: a single reply updates `threads` several times (user message, then
  // the bot turn), and each keystroke of a streamed answer would otherwise be its
  // own round trip. 600ms is below the gap between turns, so a conversation is
  // never more than one exchange behind what is stored.
  useEffect(() => {
    if (!booted || !threads.length) return;

    const timer = setTimeout(() => {
      void putThreads(threads.slice(0, 20) as unknown as never[]).catch(() => {
        // The conversation is still on screen; it just is not saved yet. The next
        // turn retries, and the banner-free failure is deliberate — an employee
        // mid-question does not need to be told about our storage layer.
      });
    }, 600);

    return () => clearTimeout(timer);
  }, [threads, booted, email]);

  /**
   * Keeps the newest turn in view.
   *
   * <p>Also handed to the typing answer, which grows after it has already been
   * appended — without that the text would type itself off the bottom of a
   * transcript that had stopped following it.
   */
  const scrollToEnd = useCallback((smooth = true) => {
    endRef.current?.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'end' });
  }, []);

  /**
   * The follow-along scroll, used while text is being revealed.
   *
   * <p>Instant, not smooth, and that is the whole point. A smooth scroll is an
   * animation with a duration; asking for one on every frame of a reveal restarts it
   * before it can finish, so the transcript judders instead of tracking the text. An
   * instant scroll each frame is what reads as smooth.
   *
   * <p>It also yields to the reader: someone who has scrolled up to re-read an
   * earlier answer is not dragged back down mid-sentence.
   */
  const followText = useCallback(() => {
    const el = scrollerRef.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight > FOLLOW_THRESHOLD_PX) return;
    scrollToEnd(false);
  }, [scrollToEnd]);

  // A new turn, or the waiting state changing: animate, because this is a discrete
  // jump rather than a continuous one.
  useEffect(() => {
    scrollToEnd(true);
  }, [messages.length, thinking, scrollToEnd]);

  // Text arriving: track it per frame.
  useEffect(() => {
    if (streamText) followText();
  }, [streamText, followText]);

  function freshThread(): Thread {
    return {
      id: newId('thread'),
      title: 'New conversation',
      updatedAt: Date.now(),
      messages: [
        { id: newId('msg'), role: 'bot', text: WELCOME, at: Date.now() },
      ],
    };
  }

  const toggleSidebar = () => {
    const next = !sidebarCollapsed;
    setSidebarCollapsed(next);
    localStorage.setItem('hr_sidebar', next ? 'collapsed' : 'expanded');
  };

  const toggleSound = () => {
    const next = !soundOn;
    setSoundOnState(next);
    setSoundOn(next);
    // Play the chime when switching it on, so the choice is confirmed by the thing
    // being chosen rather than by an icon changing shape.
    if (next) playNotification();
  };

  const startThread = () => {
    const t = freshThread();
    ctxRef.current = {};
    setThreads((prev) => [t, ...prev]);
    setActiveId(t.id);
    setSidebarOpen(false);
    inputRef.current?.focus();
  };

  const selectThread = (id: string) => {
    // Switching threads resets dialogue state; the new thread has its own.
    ctxRef.current = {};
    setActiveId(id);
    setSidebarOpen(false);
  };

  // --- send ---------------------------------------------------------------
  const send = useCallback(
    async (text: string) => {
      const query = text.trim();
      if (!query || thinking || !activeId) return;

      streamedRef.current = false;

      const userMsg: ChatMessage = { id: newId('msg'), role: 'user', text: query, at: Date.now() };

      setThreads((prev) =>
        prev.map((t) =>
          t.id === activeId
            ? {
                ...t,
                title: t.messages.some((m) => m.role === 'user') ? t.title : titleFrom(query),
                messages: [...t.messages, userMsg],
                updatedAt: Date.now(),
              }
            : t
        )
      );
      setInput('');
      setThinking(true);

      // Transcript gives an escalated ticket the surrounding thread.
      const transcript = [...messages, userMsg].map((m) => ({
        role: m.role,
        text: m.text,
      }));

      try {
        const { turn, ctx } = await respond(query, ctxRef.current, {
          faqs,
          email,
          userName,
          transcript,
          // Closed over the active thread so the server can prefer its own transcript.
          askKnowledge: (q, hist, onToken) => askKnowledge(q, hist, activeId, onToken),
          // Streamed fragments land in their own state, not in the thread: the
          // turn is not a message yet — retrieval may still refuse, in which case
          // this text is discarded and the employee is offered a human instead.
          onToken: (text) => {
            streamedRef.current = true;
            pushToken(text);
          },
        });
        ctxRef.current = ctx;

        // Let the tail finish revealing before the finished message replaces this
        // one. Swapping in the complete text mid-reveal is the dump the pacing
        // exists to avoid — and it is the last place it could still happen.
        await drainStream();
        resetStream();

        const botMsg: ChatMessage = {
          id: newId('msg'),
          role: 'bot',
          text: turn.content,
          at: Date.now(),
          turn,
          sourceQuery: query,
        };

        // Type it out only if the model did not already reveal it token by token.
        // A curated intent, a Darwinbox record or an escalation offer is a string
        // the client already held, and those used to appear instantly — next to a
        // streamed answer that typed itself, the instant ones read as canned.
        setAnimateId(streamedRef.current ? null : botMsg.id);

        setThreads((prev) =>
          prev.map((t) =>
            t.id === activeId
              ? { ...t, messages: [...t.messages, botMsg], updatedAt: Date.now() }
              : t
          )
        );

        // Here rather than in an effect on message count: an effect would also
        // fire when a restored conversation loads, chiming on every page open for
        // an answer given yesterday.
        playNotification();
      } catch {
        setThreads((prev) =>
          prev.map((t) =>
            t.id === activeId
              ? {
                  ...t,
                  messages: [
                    ...t.messages,
                    {
                      id: newId('msg'),
                      role: 'bot',
                      text: "Something went wrong fetching that. Please try again in a moment.",
                      at: Date.now(),
                    },
                  ],
                }
              : t
          )
        );

        // Chimes for this too. Someone who looked away needs to know the turn
        // finished, and "it failed" is as much worth looking back for as an
        // answer — a notification that only fires on success trains people to
        // assume silence means still-working.
        playNotification();
      } finally {
        setThinking(false);
        resetStream();
      }
    },
    [
      activeId,
      faqs,
      messages,
      thinking,
      email,
      userName,
      pushToken,
      drainStream,
      resetStream,
    ]
  );

  const showIntro = messages.length <= 1;

  // -----------------------------------------------------------------------

  // Held until the session is known, and held again if there is none: the effect above is
  // redirecting to /login, and rendering the chat in the meantime is what used to flash a
  // fabricated employee's name on screen. The skeleton is the same one the boot path shows,
  // so this reads as loading rather than as an error.
  if (authLoading || !user) return <BootSkeleton />;

  if (!booted) return <BootSkeleton />;

  return (
    <div className="app-shell">
      {sidebarOpen && (
        <div className="sidebar-scrim only-mobile" onClick={() => setSidebarOpen(false)} />
      )}

      <Sidebar
        open={sidebarOpen}
        collapsed={sidebarCollapsed}
        onToggleCollapse={toggleSidebar}
        threads={threads}
        activeId={activeId}
        onSelect={selectThread}
        onNew={startThread}
        onClose={() => setSidebarOpen(false)}
      />

      <main style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <Header
          onMenu={() => setSidebarOpen((v) => !v)}
          onToggleTheme={toggleTheme}
          theme={theme}
          soundOn={soundOn}
          onToggleSound={toggleSound}
        />

        <div
          ref={scrollerRef}
          className="scroll-slim"
          style={{
            flex: 1,
            overflowY: 'auto',
            padding: '1.5rem 1rem 1rem',
          }}
        >
          <div style={{ maxWidth: 780, margin: '0 auto', display: 'grid', gap: '1.25rem' }}>
            {showIntro && <QuickStart onPick={send} firstName={firstName} />}

            {messages.map((m) =>
              m.role === 'user' ? (
                <UserBubble key={m.id} message={m} />
              ) : (
                <BotBubble
                  key={m.id}
                  message={m}
                  onPick={send}
                  animate={m.id === animateId && !animatedRef.current.has(m.id)}
                  onTick={followText}
                  onAnimated={() => animatedRef.current.add(m.id)}
                />
              )
            )}

            {/*
              Once the first fragment lands, the answer replaces the "Thinking" line and grows in
              place. That line stays for retrieval and embedding, which is genuine waiting with
              nothing to show yet.
            */}
            {thinking &&
              (streamText ? <StreamingBubble text={streamText} /> : <Thinking />)}
            <div ref={endRef} />
          </div>
        </div>

        <Composer
          value={input}
          onChange={setInput}
          onSend={() => send(input)}
          disabled={thinking}
          inputRef={inputRef}
          suggestions={showIntro ? [] : SUGGESTIONS.slice(0, 3)}
          onPick={send}
        />
      </main>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------

function Sidebar({
  open,
  collapsed,
  onToggleCollapse,
  threads,
  activeId,
  onSelect,
  onNew,
  onClose,
}: {
  open: boolean;
  collapsed: boolean;
  onToggleCollapse: () => void;
  threads: Thread[];
  activeId: string | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onClose: () => void;
}) {
  const { user: signedIn, logout: signOut } = useChatbotAuth();

  // Collapsed applies to the docked desktop sidebar only. When the mobile drawer
  // is open the full thing is shown — a 60px rail inside an overlay would be a
  // worse version of the button that opened it.
  const rail = collapsed && !open;

  if (rail) {
    return (
      <aside
        className="hide-mobile"
        style={{
          width: 60,
          flexShrink: 0,
          background: 'var(--surface)',
          borderRight: '1px solid var(--border)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '0.5rem',
          padding: '0.75rem 0',
          height: '100%',
        }}
      >
        {/* Expands the sidebar. The avatar doubles as the affordance so the rail
            does not need a separate logo and button competing for 60px. */}
        <button
          onClick={onToggleCollapse}
          aria-label="Expand conversations"
          aria-expanded={false}
          title="Expand conversations"
          style={{ display: 'flex', borderRadius: 10 }}
        >
          <RobinAvatar size={34} rounded="badge" ring />
        </button>

        <button
          className="btn btn-ghost"
          style={{ padding: '0.5rem', position: 'relative' }}
          onClick={onToggleCollapse}
          aria-label={`Show ${threads.length} conversation${threads.length === 1 ? '' : 's'}`}
          title="Conversations"
        >
          <ChatIcon size={18} />
          {threads.length > 1 && (
            <span
              style={{
                position: 'absolute',
                top: 2,
                right: 2,
                minWidth: 15,
                height: 15,
                padding: '0 3px',
                borderRadius: 999,
                background: 'var(--primary)',
                color: '#fff',
                fontSize: '0.5625rem',
                fontWeight: 700,
                lineHeight: '15px',
              }}
            >
              {threads.length}
            </span>
          )}
        </button>

        <button
          className="btn btn-ghost"
          style={{ padding: '0.5rem' }}
          onClick={onNew}
          aria-label="New conversation"
          title="New conversation"
        >
          <PlusIcon size={18} />
        </button>

        <div style={{ marginTop: 'auto' }}>
          <button
            className="btn btn-ghost"
            // Red, because this is the one destructive control in the rail — the icons around it
            // create things. `--error-ink` rather than `--error`: it is the foreground-tuned red,
            // legible on both themes' surfaces where the raw token goes muddy in dark mode. The
            // icon inherits it through currentColor.
            style={{ padding: '0.5rem', color: 'var(--error-ink)' }}
            aria-label="Sign out"
            title="Sign out"
            onClick={() => {
              void signOut().then(() => {
                window.location.href = '/login';
              });
            }}
          >
            <LogoutIcon size={16} />
          </button>
        </div>
      </aside>
    );
  }

  return (
    <aside
      className={open ? 'sidebar-drawer' : 'hide-mobile'}
      style={{
        width: 272,
        flexShrink: 0,
        background: 'var(--surface)',
        borderRight: '1px solid var(--border)',
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
      }}
    >
      <div style={{ padding: '0.875rem', borderBottom: '1px solid var(--border)' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '0.5rem',
            marginBottom: '0.875rem',
          }}
        >
          <RobinAvatar size={32} rounded="badge" ring />
          <div style={{ minWidth: 0 }}>
            <p style={{ fontSize: '0.8125rem', fontWeight: 700, lineHeight: 1.2 }}>{ROBIN_NAME}</p>
            <p style={{ fontSize: '0.6875rem', color: 'var(--muted-foreground)' }}>LeadSquared</p>
          </div>
          {/* Collapse on desktop, close on mobile — the same corner, because it
              is the same intention: get this panel out of the way. */}
          <button
            className="btn btn-ghost btn-sm hide-mobile"
            style={{ marginLeft: 'auto', padding: '0.25rem' }}
            onClick={onToggleCollapse}
            aria-label="Collapse conversations"
            aria-expanded
            title="Collapse"
          >
            <CollapseIcon size={16} />
          </button>
          <button
            className="btn btn-ghost btn-sm only-mobile"
            style={{ marginLeft: 'auto', padding: '0.25rem' }}
            onClick={onClose}
            aria-label="Close menu"
          >
            <XIcon size={16} />
          </button>
        </div>

        <button className="btn btn-primary" style={{ width: '100%' }} onClick={onNew}>
          <PlusIcon size={16} />
          New conversation
        </button>
      </div>

      <div style={{ padding: '0.75rem 0.875rem 0.375rem' }}>
        <p className="label-caps">Recent</p>
      </div>

      <div className="scroll-slim" style={{ flex: 1, overflowY: 'auto', padding: '0 0.625rem 0.75rem' }}>
        {threads.map((t) => {
          const isActive = t.id === activeId;
          const userTurns = t.messages.filter((m) => m.role === 'user').length;
          return (
            <button
              key={t.id}
              onClick={() => onSelect(t.id)}
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                padding: '0.625rem 0.75rem',
                marginBottom: '0.25rem',
                borderRadius: 'var(--radius)',
                border: '1px solid',
                borderColor: isActive ? 'var(--primary)' : 'transparent',
                background: isActive ? 'var(--primary-soft)' : 'transparent',
                transition: 'all 0.16s ease',
              }}
              aria-current={isActive ? 'true' : undefined}
            >
              <span
                style={{
                  display: 'block',
                  fontSize: '0.8125rem',
                  fontWeight: 600,
                  color: isActive ? 'var(--info-ink)' : 'var(--foreground)',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {t.title}
              </span>
              <span style={{ fontSize: '0.6875rem', color: 'var(--muted-foreground)' }}>
                {relativeDay(t.updatedAt)} · {userTurns} question{userTurns === 1 ? '' : 's'}
              </span>
            </button>
          );
        })}
      </div>

      <div style={{ padding: '0.75rem 0.875rem', borderTop: '1px solid var(--border)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <span
            style={{
              width: 28,
              height: 28,
              borderRadius: '50%',
              background: 'var(--surface-3)',
              display: 'grid',
              placeItems: 'center',
              fontSize: '0.6875rem',
              fontWeight: 700,
              color: 'var(--foreground-secondary)',
            }}
          >
            {initialsOf(signedIn?.name ?? signedIn?.email ?? 'Employee')}
          </span>
          <div style={{ minWidth: 0, flex: 1 }}>
            <p style={{ fontSize: '0.75rem', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {signedIn?.name ?? 'Employee'}
            </p>
            <p
              style={{
                fontSize: '0.6875rem',
                color: 'var(--muted-foreground)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {signedIn?.email ?? 'not signed in'}
              {signedIn?.role === 'hr_admin' ? ' · HR admin' : ''}
            </p>
          </div>
          {/*
            A button, not a link to /login: under SSO the session outlives the
            navigation, so a bare link would land on the login page and be bounced
            straight back into the chat. The session has to be cleared first.
          */}
          <button
            className="btn btn-ghost btn-sm"
            // Same red as the collapsed rail's sign-out, so the control reads the same either way.
            style={{ padding: '0.3125rem', color: 'var(--error-ink)' }}
            aria-label="Sign out"
            onClick={() => {
              void signOut().then(() => {
                window.location.href = '/login';
              });
            }}
          >
            <LogoutIcon size={15} />
          </button>
        </div>

        <div style={{ marginTop: '0.625rem' }}>
          <PoweredByLeadSquared align="left" />
        </div>
      </div>
    </aside>
  );
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

function Header({
  onMenu,
  onToggleTheme,
  theme,
  soundOn,
  onToggleSound,
}: {
  onMenu: () => void;
  onToggleTheme: () => void;
  theme: Theme;
  soundOn: boolean;
  onToggleSound: () => void;
}) {
  return (
    <header
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '0.75rem',
        padding: '0.75rem 1rem',
        borderBottom: '1px solid var(--border)',
        background: 'var(--surface)',
        flexShrink: 0,
      }}
    >
      <button
        className="btn btn-ghost only-mobile"
        style={{ padding: '0.4375rem' }}
        onClick={onMenu}
        aria-label="Open menu"
      >
        <MenuIcon size={18} />
      </button>

      {/*
        The title and status line ("HR Support", "Available 24/7 · Darwinbox connected") are
        gone from view — the sidebar already names the product and the status was decoration.
        The heading stays for assistive tech, because the conversation view has no other one
        once the greeting is replaced by messages, and the div stays as the flex spacer that
        holds the buttons to the right.
      */}
      <div style={{ minWidth: 0, flex: 1 }}>
        <h1 className="sr-only">HR Support</h1>
      </div>

      <button
        className="btn btn-ghost"
        style={{ padding: '0.4375rem', color: soundOn ? undefined : 'var(--faint)' }}
        onClick={onToggleSound}
        aria-pressed={soundOn}
        aria-label={soundOn ? 'Turn off answer sound' : 'Turn on answer sound'}
        title={soundOn ? 'Sound on — answers chime' : 'Sound off'}
      >
        {soundOn ? <SpeakerIcon size={16} /> : <SpeakerOffIcon size={16} />}
      </button>

      <button
        className="btn btn-ghost"
        style={{ padding: '0.4375rem' }}
        onClick={onToggleTheme}
        aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
      >
        {theme === 'dark' ? <SunIcon size={16} /> : <MoonIcon size={16} />}
      </button>

      {/*
        Shown to everyone, unlike the admin link that used to sit here: every
        employee has tickets of their own to read, and the page shows only theirs.
      */}
      <a href="/tickets" className="btn btn-secondary btn-sm" title="My HR tickets">
        <TicketIcon size={15} />
        <span className="hide-mobile">My tickets</span>
        <span className="sr-only only-mobile">My HR tickets</span>
      </a>

    </header>
  );
}

// ---------------------------------------------------------------------------
// Message bubbles
// ---------------------------------------------------------------------------

function UserBubble({ message }: { message: ChatMessage }) {
  return (
    <div className="anim-in" style={{ display: 'flex', justifyContent: 'flex-end' }}>
      <div style={{ maxWidth: '86%', textAlign: 'right' }}>
        <div
          style={{
            display: 'inline-block',
            textAlign: 'left',
            padding: '0.6875rem 0.9375rem',
            borderRadius: '14px 14px 4px 14px',
            background: 'var(--primary)',
            color: 'var(--primary-foreground)',
            fontSize: '0.9375rem',
            boxShadow: 'var(--shadow-xs)',
            wordBreak: 'break-word',
          }}
        >
          {message.text}
        </div>
        <p style={{ fontSize: '0.6875rem', color: 'var(--faint)', marginTop: '0.25rem' }}>
          {timeOf(message.at)}
        </p>
      </div>
    </div>
  );
}

function BotBubble({
  message,
  onPick,
  animate = false,
  onTick,
  onAnimated,
}: {
  message: ChatMessage;
  onPick: (text: string) => void;
  /**
   * Type this answer out. Set for exactly one message — the one that just arrived
   * and did not already stream. Restored history renders complete: replaying a
   * week of answers on page load would be absurd, and a streamed answer has
   * already been revealed once at the model's own pace.
   */
  animate?: boolean;
  onTick?: () => void;
  /** Reported when the reveal starts, so the page can avoid replaying it later. */
  onAnimated?: () => void;
}) {
  const turn = message.turn;
  const sensitive = turn?.sensitive;

  return (
    <div className="anim-in" style={{ display: 'flex', gap: '0.625rem', alignItems: 'flex-start' }}>
      {/*
        A confidential turn keeps the shield: it tells the employee this one was
        routed to a person, which Robin's face would not convey.
      */}
      {sensitive ? (
        <span
          style={{
            width: 30,
            height: 30,
            borderRadius: 9,
            flexShrink: 0,
            display: 'grid',
            placeItems: 'center',
            background: 'var(--error-soft)',
            color: 'var(--error-ink)',
            marginTop: 2,
          }}
          aria-hidden
        >
          <ShieldIcon size={16} />
        </span>
      ) : (
        <span style={{ marginTop: 2, display: 'flex' }}>
          <RobinAvatar size={30} rounded="badge" ring />
        </span>
      )}

      <div style={{ minWidth: 0, flex: 1 }}>
        {/*
          The name, once per turn. The avatar alone identified the speaker by picture; in a
          transcript people read rather than look at, an unlabelled bubble is just "the other
          one". Sits outside the bubble so it reads as a byline rather than as part of the
          answer, and is not repeated inside the text where it would compete with the content.
        */}
        <p
          style={{
            // Not `label-caps`: that class forces text-transform: uppercase, which rendered a
            // name as ROBIN. A name is a name — shouting it makes it read as a system label
            // rather than as whoever is talking. Same size and colour, sentence case, and no
            // wide tracking, which only earns its place on actual all-caps labels.
            fontSize: '0.75rem',
            fontWeight: 600,
            color: 'var(--muted-foreground)',
            marginBottom: '0.3125rem',
          }}
        >
          Robin
        </p>

        <div
          style={{
            padding: '0.8125rem 1rem',
            borderRadius: '4px 14px 14px 14px',
            background: 'var(--surface)',
            border: '1px solid',
            borderColor: sensitive ? 'var(--error)' : 'var(--border)',
            boxShadow: 'var(--shadow-xs)',
            fontSize: '0.9375rem',
          }}
        >
          {sensitive && (
            <p
              className="badge badge-error"
              style={{ marginBottom: '0.625rem' }}
            >
              <ShieldIcon size={11} />
              Priority · Confidential
            </p>
          )}

          <TypedRichText
            text={message.text}
            animate={animate}
            onTick={onTick}
            onAnimated={onAnimated}
          />

          {/* Spell-corrections are surfaced so the employee can see the bot
              understood them, rather than silently answering a different
              question. */}
          {turn?.corrections && turn.corrections.length > 0 && (
            <p style={{ fontSize: '0.6875rem', color: 'var(--faint)', marginTop: '0.5rem' }}>
              Read as:{' '}
              {/* Keyed by position, not by `from`: the same word can be corrected twice in one
                  message, and a long paste corrects plenty of them. React warns about the
                  duplicate and may drop or duplicate a child. */}
              {turn.corrections.map((c, i) => (
                <span key={`${i}-${c.from}`}>
                  {i > 0 && ', '}
                  <s>{c.from}</s> → <strong>{c.to}</strong>
                </span>
              ))}
            </p>
          )}

          {turn?.citations && turn.citations.length > 0 && (
            <Citations citations={turn.citations} />
          )}

          {turn?.card?.kind === 'leave_balance' && (
            <LeaveBalanceCard balances={turn.card.balances} />
          )}
          {turn?.card?.kind === 'payslip' && <PayslipCard payslip={turn.card.payslip} />}
          {turn?.card?.kind === 'requests' && (
            <RequestsCard requests={turn.card.requests} heading={turn.card.heading} />
          )}
          {turn?.card?.kind === 'ticket' && <TicketCard ticket={turn.card.ticket} />}

          {/* The single clarifying question, rendered as one-tap chips. */}
          {turn?.clarify && (
            <div
              style={{
                display: 'flex',
                flexWrap: 'wrap',
                gap: '0.5rem',
                marginTop: '0.75rem',
              }}
            >
              {turn.clarify.options.map((opt) => (
                <button
                  key={opt.intentId}
                  className="chip"
                  onClick={() => onPick(opt.label)}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          )}

          {turn?.decision === 'escalate' && turn.offerEscalation && !turn.card && (
            <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.75rem' }}>
              <button className="btn btn-primary btn-sm" onClick={() => onPick('yes')}>
                Yes, raise a ticket
              </button>
              <button className="btn btn-secondary btn-sm" onClick={() => onPick('no')}>
                No thanks
              </button>
            </div>
          )}
        </div>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '0.75rem',
            flexWrap: 'wrap',
            marginTop: '0.3125rem',
          }}
        >
          <span style={{ fontSize: '0.6875rem', color: 'var(--faint)' }}>{timeOf(message.at)}</span>

          {turn && turn.confidence > 0 && turn.decision !== 'ack' && (
            <span style={{ fontSize: '0.6875rem', color: 'var(--faint)' }}>
              {turn.intentLabel ? `${turn.intentLabel} · ` : ''}
              {Math.round(turn.confidence * 100)}% match
            </span>
          )}

          {turn?.collectFeedback && (
            <FeedbackBar
              messageId={message.id}
              query={message.sourceQuery ?? ''}
              intentId={turn.intentId}
              intentLabel={turn.intentLabel}
              confidence={turn.confidence}
            />
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * The passages behind an answer, with no attribution line above them.
 *
 * There used to be one — "Written from your HR documents by claude-haiku-4-5" — naming both
 * the source and the model. Both are gone deliberately: how the answer was produced is an
 * implementation detail an employee has no use for, and naming the model invites questions
 * about the machine instead of about their leave.
 *
 * The passages themselves stay. HR Ops' own Phase 1 requirement is that the assistant cites
 * the policy section it draws from — for insurance questions especially, where an employee
 * acting on a coverage answer needs to be able to check it. Removing the sentence hides how
 * the answer was made; removing these would hide what it was based on, which is a different
 * and much more consequential thing.
 */
function Citations({ citations }: { citations: Citation[] }) {
  return (
    <div
      style={{
        marginTop: '0.875rem',
        paddingTop: '0.75rem',
        borderTop: '1px solid var(--border)',
      }}
    >
      <div style={{ display: 'grid', gap: '0.375rem' }}>
        {citations.slice(0, 3).map((c, i) => (
          <details
            key={`${c.docId}-${i}`}
            style={{
              background: 'var(--surface-2)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-sm)',
              padding: '0.4375rem 0.625rem',
            }}
          >
            <summary
              style={{
                fontSize: '0.75rem',
                cursor: 'pointer',
                color: 'var(--foreground-secondary)',
                fontWeight: 600,
              }}
            >
              {c.title}
              {c.headings.length > 0 && (
                <span style={{ fontWeight: 400, color: 'var(--muted-foreground)' }}>
                  {' › '}
                  {c.headings.join(' › ')}
                </span>
              )}
            </summary>
            <p
              style={{
                fontSize: '0.75rem',
                color: 'var(--muted-foreground)',
                marginTop: '0.4375rem',
                lineHeight: 1.55,
              }}
            >
              “{c.snippet}
              {c.snippet.length >= 300 ? '…' : ''}”
            </p>
          </details>
        ))}
      </div>
    </div>
  );
}

/**
 * The answer as it is being written.
 *
 * Deliberately plain text, not the Markdown renderer the finished bubble uses:
 * half-arrived syntax renders as garbage — a lone `**` or an unclosed table row
 * flickering as tokens land — which reads worse than watching plain prose appear.
 * The formatted version replaces this the moment the turn completes.
 */
/**
 * What Robin is doing right now, above the bubble.
 *
 * <p>One word per phase, both swept by the same shimmer: <b>Thinking</b> while retrieval,
 * embedding and prompt evaluation run — measured at eight of the eleven seconds a real answer
 * took here — then <b>Writing</b> once words are arriving.
 *
 * <p>The waiting phase used to be a moving equaliser with no wording, on the argument that
 * naming internals tells the employee nothing. Named now because the two phases read as one
 * continuous state when they share a treatment, and "Thinking" is the honest word for a wait
 * that is mostly retrieval — it describes the shape of what is happening without claiming to
 * describe the mechanism.
 */
function ActivityLabel({ state }: { state: 'waiting' | 'writing' }) {
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        fontSize: '0.75rem',
        fontWeight: 600,
        letterSpacing: '0.01em',
      }}
      role="status"
    >
      <span className="activity-shimmer">{state === 'waiting' ? 'Thinking' : 'Writing'}</span>
    </span>
  );
}

/** The avatar with a halo leaving it, used while a turn is in flight. */
function ActiveAvatar() {
  return (
    <span className="activity-halo">
      <RobinAvatar size={30} rounded="badge" ring />
    </span>
  );
}

function StreamingBubble({ text }: { text: string }) {
  return (
    <div style={{ display: 'flex', gap: '0.625rem', alignItems: 'flex-start' }}>
      <ActiveAvatar />
      <div style={{ display: 'grid', gap: '0.375rem', minWidth: 0 }}>
        <ActivityLabel state="writing" />
        <div
          style={{
            padding: '0.875rem 1rem',
            borderRadius: '4px 14px 14px 14px',
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            maxWidth: '46rem',
            fontSize: '0.875rem',
            lineHeight: 1.65,
            color: 'var(--foreground)',
          }}
          // Polite, not assertive: a screen reader should not re-read the whole
          // answer on every fragment.
          aria-live="polite"
          aria-busy="true"
        >
          {/*
            Rendered as markdown while it streams, with any half-written marker
            trimmed off the tail. Previously this was raw text, so a figure arrived
            as a literal `**30 days` and snapped into bold when the turn finalised —
            the one moment the effect drew attention to itself.

            The caret rides on the wrapper (a ::after on the last block) so it
            trails the final character instead of dropping to its own line.
          */}
          <div className="typing-caret-host">
            <RichText text={hideDanglingMarkup(text)} />
          </div>
        </div>
      </div>
    </div>
  );
}

function Thinking() {
  return (
    <div style={{ display: 'flex', gap: '0.625rem', alignItems: 'center', padding: '0.25rem 0' }}>
      <ActiveAvatar />
      <ActivityLabel state="waiting" />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Intro / quick start
// ---------------------------------------------------------------------------

/**
 * The opening line on an empty conversation.
 *
 * <p>Two lines rather than one: the name is fixed so it does not flicker on a timer
 * — someone's own name moving on the page is distracting in a way a prompt is not —
 * and only the invitation underneath rotates.
 */
function Greeting({ firstName }: { firstName: string }) {
  return (
    <div style={{ padding: '0.5rem 0 0.25rem' }}>
      <h1
        style={{
          fontSize: 'clamp(1.375rem, 4vw, 1.75rem)',
          fontWeight: 600,
          letterSpacing: '-0.02em',
          lineHeight: 1.2,
          marginBottom: '0.375rem',
        }}
      >
        {firstName ? `Hello, ${firstName}` : 'Hello'}
      </h1>

      <p
        style={{
          fontSize: 'clamp(1rem, 3vw, 1.1875rem)',
          color: 'var(--muted-foreground)',
          letterSpacing: '-0.01em',
          // Reserves the taller line's height so the cards below do not shift up
          // and down as the phrases swap.
          minHeight: '1.6em',
        }}
      >
        <RotatingText phrases={GREETING_LINES} />
      </p>
    </div>
  );
}

function QuickStart({ onPick, firstName }: { onPick: (q: string) => void; firstName: string }) {
  return (
    <div className="anim-fade" style={{ display: 'grid', gap: '1rem' }}>
      <Greeting firstName={firstName} />

      <div>
        <p className="label-caps" style={{ marginBottom: '0.5rem' }}>
          Live from Darwinbox
        </p>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
            gap: '0.625rem',
          }}
        >
          {QUICK_ACTIONS.map(({ label, query, Icon }, i) => (
            <button
              key={query}
              onClick={() => onPick(query)}
              className="card anim-in"
              style={{
                padding: '0.875rem',
                textAlign: 'left',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.5rem',
                animationDelay: `${i * 0.05}s`,
                transition: 'transform 0.16s ease, box-shadow 0.16s ease, border-color 0.16s',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.transform = 'translateY(-2px)';
                e.currentTarget.style.boxShadow = 'var(--shadow)';
                e.currentTarget.style.borderColor = 'var(--primary)';
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.transform = 'translateY(0)';
                e.currentTarget.style.boxShadow = 'var(--shadow-sm)';
                e.currentTarget.style.borderColor = 'var(--border)';
              }}
            >
              <span style={{ color: 'var(--primary)' }}>
                <Icon size={18} />
              </span>
              <span style={{ fontSize: '0.8125rem', fontWeight: 600 }}>{label}</span>
            </button>
          ))}
        </div>
      </div>

      <div>
        <p className="label-caps" style={{ marginBottom: '0.5rem' }}>
          Or try asking
        </p>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
          {SUGGESTIONS.map((s) => (
            <button key={s} className="chip" onClick={() => onPick(s)}>
              {s}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

function Composer({
  value,
  onChange,
  onSend,
  disabled,
  inputRef,
  suggestions,
  onPick,
}: {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  disabled: boolean;
  inputRef: React.RefObject<HTMLInputElement | null>;
  suggestions: string[];
  onPick: (q: string) => void;
}) {
  return (
    <div
      style={{
        borderTop: '1px solid var(--border)',
        background: 'var(--surface)',
        padding: '0.875rem 1rem',
        flexShrink: 0,
      }}
    >
      <div style={{ maxWidth: 780, margin: '0 auto' }}>
        {suggestions.length > 0 && (
          <div
            className="scroll-slim"
            style={{
              display: 'flex',
              gap: '0.375rem',
              marginBottom: '0.625rem',
              overflowX: 'auto',
              paddingBottom: 2,
            }}
          >
            {suggestions.map((s) => (
              <button
                key={s}
                className="chip"
                style={{ flexShrink: 0, fontSize: '0.75rem', padding: '0.3125rem 0.625rem' }}
                onClick={() => onPick(s)}
              >
                {s}
              </button>
            ))}
          </div>
        )}

        <form
          onSubmit={(e) => {
            e.preventDefault();
            onSend();
          }}
          style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-end' }}
        >
          <label style={{ flex: 1 }}>
            <span className="sr-only">Ask an HR question</span>
            <input
              ref={inputRef}
              className="input"
              value={value}
              onChange={(e) => onChange(e.target.value)}
              placeholder="Ask about leave, payroll, benefits…"
              disabled={disabled}
              enterKeyHint="send"
              autoComplete="off"
            />
          </label>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={disabled || !value.trim()}
            aria-label="Send message"
          >
            <SendIcon size={16} />
            <span className="hide-mobile">Send</span>
          </button>
        </form>

        <p
          style={{
            fontSize: '0.6875rem',
            color: 'var(--faint)',
            marginTop: '0.5rem',
            textAlign: 'center',
          }}
        >
          Sensitive matters are routed privately to your HRBP. Feedback is anonymous.
        </p>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Boot skeleton
// ---------------------------------------------------------------------------

function BootSkeleton() {
  return (
    <div className="app-shell">
      <div
        className="hide-mobile"
        style={{ width: 272, borderRight: '1px solid var(--border)', padding: '0.875rem' }}
      >
        <div className="skeleton" style={{ height: 38, marginBottom: '1rem' }} />
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="skeleton" style={{ height: 46, marginBottom: '0.5rem' }} />
        ))}
      </div>
      <div style={{ flex: 1, padding: '1.5rem' }}>
        <div className="skeleton" style={{ height: 52, marginBottom: '1.5rem', maxWidth: 780 }} />
        <div style={{ maxWidth: 780, display: 'grid', gap: '1rem' }}>
          <div className="skeleton" style={{ height: 88 }} />
          <div className="skeleton" style={{ height: 64, width: '70%', marginLeft: 'auto' }} />
          <div className="skeleton" style={{ height: 120 }} />
        </div>
      </div>
    </div>
  );
}
