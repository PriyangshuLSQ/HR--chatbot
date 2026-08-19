'use client';

import { useCallback, useEffect, useState } from 'react';

export type Theme = 'light' | 'dark';

/** Where the choice is kept. One key, read by the bootstrap script and this hook alike. */
export const THEME_KEY = 'hr_theme';

/**
 * Applies the stored theme before the first paint.
 *
 * <p>Injected into the document head as a blocking script, which is the point: every page used to
 * restore the theme in its own {@code useEffect}, so the first frame rendered in whatever
 * {@code prefers-color-scheme} said and then corrected itself. Worse, a page that simply forgot to
 * write that effect never corrected at all — which is how <b>/tickets</b> came to ignore the
 * toggle entirely while using the theme variables correctly.
 *
 * <p>Deliberately not a React effect and deliberately not a module import: it has to execute
 * before the browser paints, and anything hydration-driven runs after.
 *
 * <p>Wrapped in try/catch because {@code localStorage} throws in private-mode Safari and inside
 * some embedded webviews. Failing there must leave the page readable on the OS preference, not
 * blank it.
 */
export const THEME_BOOTSTRAP = `(function(){try{var t=localStorage.getItem('${THEME_KEY}');if(t==='light'||t==='dark'){document.documentElement.setAttribute('data-theme',t);}}catch(e){}})();`;

/**
 * The theme in force right now.
 *
 * <p>Read from the DOM rather than from React state, so it agrees with whatever the bootstrap
 * script did and cannot go stale. Falls back to the OS preference when nothing has been chosen —
 * without that, the first click on the toggle would go dark for someone already in dark mode.
 */
function currentTheme(): Theme {
  const attribute = document.documentElement.getAttribute('data-theme');
  if (attribute === 'light' || attribute === 'dark') return attribute;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/**
 * The theme, and a way to flip it.
 *
 * <p>Every page that renders a toggle uses this. The duplicated copies it replaces were not just
 * repetition: each was a place to forget, and one of them did.
 */
export function useTheme(): { theme: Theme; toggleTheme: () => void } {
  // Starts at the light default and is corrected on mount. Reading localStorage or matchMedia
  // during render would produce different markup on the server than in the browser, which is a
  // hydration mismatch — the bootstrap script is what makes the visible theme correct already,
  // so this only catches up the state that decides which icon to draw.
  const [theme, setTheme] = useState<Theme>('light');

  useEffect(() => {
    setTheme(currentTheme());
  }, []);

  const toggleTheme = useCallback(() => {
    const next: Theme = currentTheme() === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch {
      // Unavailable storage. The attribute is still set, so the choice holds for this page view
      // and is simply forgotten on the next one — better than refusing to switch.
    }
    setTheme(next);
  }, []);

  return { theme, toggleTheme };
}
