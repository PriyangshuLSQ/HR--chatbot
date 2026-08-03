'use client';

import { useState } from 'react';
import { recordFeedback, type Rating } from '@/lib/hr-store';
import { CheckIcon, ThumbDownIcon, ThumbUpIcon } from './Icons';

/**
 * Anonymous thumbs up/down shown under every substantive bot answer.
 *
 * A thumbs-down opens an optional comment box — the digest is far more useful
 * with the employee's own words than with a bare count. Nothing identifying is
 * stored: the payload keeps the query and the matched intent, never the user.
 */
export function FeedbackBar({
  messageId,
  query,
  intentId,
  intentLabel,
  confidence,
  channel,
}: {
  messageId: string;
  query: string;
  intentId?: string;
  intentLabel?: string;
  confidence: number;
  channel: string;
}) {
  const [rating, setRating] = useState<Rating | null>(null);
  const [showComment, setShowComment] = useState(false);
  const [comment, setComment] = useState('');
  const [sent, setSent] = useState(false);

  /**
   * The thumb fills immediately and stays filled even if the write fails.
   *
   * Deliberate: a rating is a courtesy the employee did us, not a task of theirs.
   * Interrupting their actual HR question with "your feedback failed to save"
   * spends their attention on our problem. The store reverts its cache, so the
   * digest never counts a rating that was not stored.
   */
  const submit = (value: Rating) => {
    setRating(value);
    void recordFeedback({
      messageId,
      rating: value,
      query,
      intentId,
      intentLabel,
      confidence,
      channel,
    }).catch(() => {});
    // Only a negative rating is worth interrupting for a reason.
    if (value === 'down') setShowComment(true);
  };

  const sendComment = () => {
    void recordFeedback({
      messageId,
      rating: 'down',
      comment: comment.trim() || undefined,
      query,
      intentId,
      intentLabel,
      confidence,
      channel,
    }).catch(() => {});
    setShowComment(false);
    setSent(true);
  };

  const btn = (active: boolean, tone: 'up' | 'down'): React.CSSProperties => ({
    display: 'inline-flex',
    alignItems: 'center',
    gap: '0.25rem',
    padding: '0.25rem 0.5rem',
    borderRadius: 'var(--radius-sm)',
    border: '1px solid',
    borderColor: active ? (tone === 'up' ? 'var(--success)' : 'var(--error)') : 'transparent',
    background: active
      ? tone === 'up'
        ? 'var(--success-soft)'
        : 'var(--error-soft)'
      : 'transparent',
    color: active
      ? tone === 'up'
        ? 'var(--success-ink)'
        : 'var(--error-ink)'
      : 'var(--faint)',
    fontSize: '0.75rem',
    fontWeight: 600,
    transition: 'all 0.16s ease',
  });

  return (
    <div style={{ marginTop: '0.5rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '0.25rem', flexWrap: 'wrap' }}>
        {rating === null ? (
          <span style={{ fontSize: '0.75rem', color: 'var(--faint)', marginRight: '0.25rem' }}>
            Was this helpful?
          </span>
        ) : (
          <span
            style={{
              fontSize: '0.75rem',
              color: 'var(--muted-foreground)',
              marginRight: '0.25rem',
              display: 'inline-flex',
              alignItems: 'center',
              gap: '0.25rem',
            }}
          >
            <CheckIcon size={12} />
            Thanks for the feedback
          </span>
        )}

        {/* Icon + text label, so the rating never relies on colour alone. */}
        <button
          type="button"
          onClick={() => submit('up')}
          style={btn(rating === 'up', 'up')}
          aria-pressed={rating === 'up'}
          aria-label="This answer was helpful"
        >
          <ThumbUpIcon size={13} />
          Yes
        </button>
        <button
          type="button"
          onClick={() => submit('down')}
          style={btn(rating === 'down', 'down')}
          aria-pressed={rating === 'down'}
          aria-label="This answer was not helpful"
        >
          <ThumbDownIcon size={13} />
          No
        </button>
      </div>

      {showComment && (
        <div className="anim-in" style={{ marginTop: '0.5rem', display: 'flex', gap: '0.375rem' }}>
          <input
            className="input"
            style={{ fontSize: '0.8125rem', padding: '0.4375rem 0.625rem' }}
            placeholder="What were you hoping for? (optional)"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') sendComment();
            }}
            autoFocus
          />
          <button type="button" className="btn btn-secondary btn-sm" onClick={sendComment}>
            Send
          </button>
        </div>
      )}

      {sent && (
        <p style={{ fontSize: '0.75rem', color: 'var(--success-ink)', marginTop: '0.375rem' }}>
          Noted — this goes into the weekly digest for the HR team.
        </p>
      )}
    </div>
  );
}

export default FeedbackBar;
