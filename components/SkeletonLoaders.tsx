'use client';

export function SkeletonChatMessage() {
  return (
    <div style={{ display: 'flex', gap: '0.75rem', marginBottom: '1rem' }}>
      <div className="skeleton-avatar" />
      <div style={{ flex: 1 }}>
        <div className="skeleton-text" style={{ width: '60%' }} />
        <div className="skeleton-message" style={{ width: '100%', height: '2rem' }} />
        <div className="skeleton-message" style={{ width: '80%', height: '2rem' }} />
      </div>
    </div>
  );
}

export function SkeletonChatContainer() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
      <SkeletonChatMessage />
      <SkeletonChatMessage />
      <SkeletonChatMessage />
    </div>
  );
}

export function SkeletonSidebar() {
  return (
    <div style={{ padding: '1rem', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
      <div className="skeleton-card" style={{ height: '3rem' }} />
      {[1, 2, 3, 4, 5].map((i) => (
        <div key={i} style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
          <div className="skeleton-avatar" />
          <div style={{ flex: 1 }}>
            <div className="skeleton-text" style={{ width: '70%' }} />
            <div className="skeleton-text" style={{ width: '50%' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export function SkeletonStatCard() {
  return (
    <div className="skeleton-card" style={{ padding: '1.5rem', height: '140px' }}>
      <div className="skeleton-text" style={{ width: '40%', marginBottom: '0.75rem' }} />
      <div className="skeleton-text" style={{ width: '60%', height: '1.5rem' }} />
    </div>
  );
}

export function SkeletonStatCards() {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', gap: '1.5rem' }}>
      {[1, 2, 3, 4].map((i) => (
        <SkeletonStatCard key={i} />
      ))}
    </div>
  );
}

export function SkeletonTableRow() {
  return (
    <div style={{ display: 'flex', gap: '1rem', padding: '1rem', borderBottom: '1px solid var(--border)', alignItems: 'center' }}>
      <div className="skeleton-text" style={{ width: '100px' }} />
      <div className="skeleton-text" style={{ width: '150px' }} />
      <div className="skeleton-text" style={{ width: '120px' }} />
      <div className="skeleton-text" style={{ width: '80px' }} />
    </div>
  );
}

export function SkeletonTable() {
  return (
    <div>
      {[1, 2, 3, 4, 5].map((i) => (
        <SkeletonTableRow key={i} />
      ))}
    </div>
  );
}

export function SkeletonFAQItem() {
  return (
    <div className="skeleton-card" style={{ padding: '1rem', marginBottom: '0.75rem' }}>
      <div className="skeleton-text" style={{ width: '80%', marginBottom: '0.5rem' }} />
      <div className="skeleton-message" style={{ height: '2rem', width: '100%' }} />
    </div>
  );
}

export function SkeletonFAQList() {
  return (
    <div>
      {[1, 2, 3, 4].map((i) => (
        <SkeletonFAQItem key={i} />
      ))}
    </div>
  );
}

export function LoadingSpinner() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '2rem' }}>
      <div className="loading-spinner" />
    </div>
  );
}

export function TypingIndicator() {
  return (
    <div className="typing-indicator">
      <div className="typing-dot" />
      <div className="typing-dot" />
      <div className="typing-dot" />
    </div>
  );
}

export function PageLoader() {
  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      gap: '2rem',
      padding: '2rem',
    }}>
      <SkeletonStatCards />
      <div style={{ display: 'flex', gap: '2rem' }}>
        <div style={{ flex: 1 }}>
          <SkeletonTable />
        </div>
        <div style={{ width: '300px' }}>
          <SkeletonSidebar />
        </div>
      </div>
    </div>
  );
}
