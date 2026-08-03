'use client';

import React from 'react';

/**
 * Renders the small markdown subset the bot answers use: **bold**, bullet and
 * numbered lists, and pipe tables. Deliberately not a full markdown library —
 * the input is our own authored content, so this stays dependency-free and
 * predictable, and it never renders raw HTML.
 */

type Block =
  | { type: 'p'; lines: string[] }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }
  | { type: 'table'; head: string[]; rows: string[][] };

const BULLET = /^\s*[•\-*]\s+/;
const NUMBERED = /^\s*\d+[.)]\s+/;
const TABLE_ROW = /^\s*\|(.+)\|\s*$/;
const TABLE_DIVIDER = /^\s*\|[\s:|-]+\|\s*$/;

function splitRow(line: string): string[] {
  const inner = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  return inner.split('|').map((c) => c.trim());
}

function parse(text: string): Block[] {
  const lines = text.split('\n');
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i++;
      continue;
    }

    // Table: a header row, a divider, then body rows.
    if (TABLE_ROW.test(line) && i + 1 < lines.length && TABLE_DIVIDER.test(lines[i + 1])) {
      const head = splitRow(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && TABLE_ROW.test(lines[i])) {
        rows.push(splitRow(lines[i]));
        i++;
      }
      blocks.push({ type: 'table', head, rows });
      continue;
    }

    if (BULLET.test(line)) {
      const items: string[] = [];
      while (i < lines.length && BULLET.test(lines[i])) {
        items.push(lines[i].replace(BULLET, ''));
        i++;
      }
      blocks.push({ type: 'ul', items });
      continue;
    }

    if (NUMBERED.test(line)) {
      const items: string[] = [];
      while (i < lines.length && NUMBERED.test(lines[i])) {
        items.push(lines[i].replace(NUMBERED, ''));
        i++;
      }
      blocks.push({ type: 'ol', items });
      continue;
    }

    // Plain paragraph: consume until a blank line or a structural line.
    const para: string[] = [];
    while (
      i < lines.length &&
      lines[i].trim() &&
      !BULLET.test(lines[i]) &&
      !NUMBERED.test(lines[i]) &&
      !TABLE_ROW.test(lines[i])
    ) {
      para.push(lines[i]);
      i++;
    }
    if (para.length) blocks.push({ type: 'p', lines: para });
  }

  return blocks;
}

/** Renders **bold** and `code` spans; everything else stays literal text. */
function Inline({ text }: { text: string }) {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return (
    <>
      {parts.map((part, i) => {
        if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
          return <strong key={i}>{part.slice(2, -2)}</strong>;
        }
        // Setup hints include shell commands to copy, so they need to read as
        // commands rather than as prose with stray backticks.
        if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
          return (
            <code
              key={i}
              style={{
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                fontSize: '0.9em',
                background: 'var(--surface-3)',
                borderRadius: 4,
                padding: '0.1em 0.35em',
                wordBreak: 'break-word',
              }}
            >
              {part.slice(1, -1)}
            </code>
          );
        }
        return <React.Fragment key={i}>{part}</React.Fragment>;
      })}
    </>
  );
}

export function RichText({ text }: { text: string }) {
  const blocks = React.useMemo(() => parse(text), [text]);

  return (
    <div className="rich">
      {blocks.map((block, bi) => {
        switch (block.type) {
          case 'ul':
            return (
              <ul key={bi}>
                {block.items.map((item, ii) => (
                  <li key={ii}>
                    <Inline text={item} />
                  </li>
                ))}
              </ul>
            );
          case 'ol':
            return (
              <ol key={bi}>
                {block.items.map((item, ii) => (
                  <li key={ii}>
                    <Inline text={item} />
                  </li>
                ))}
              </ol>
            );
          case 'table':
            return (
              // Wide tables scroll inside their own container so the message
              // bubble never forces the page to scroll sideways.
              <div className="rich-table-wrap" key={bi}>
                <table>
                  <thead>
                    <tr>
                      {block.head.map((h, hi) => (
                        <th key={hi}>
                          <Inline text={h} />
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {block.rows.map((row, ri) => (
                      <tr key={ri}>
                        {row.map((cell, ci) => (
                          <td key={ci}>
                            <Inline text={cell} />
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          default:
            return (
              <p key={bi}>
                {block.lines.map((line, li) => (
                  <React.Fragment key={li}>
                    {li > 0 && <br />}
                    <Inline text={line} />
                  </React.Fragment>
                ))}
              </p>
            );
        }
      })}
    </div>
  );
}

export default RichText;
