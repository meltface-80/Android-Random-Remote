#!/usr/bin/env node
/*
 * check-sharecard.js — the two promises the share card's description makes.
 *
 * The card is drawn on a canvas and there is no browser in CI, so this checks
 * what would go wrong QUIETLY rather than what the card looks like:
 *
 *  1. CONTRAST. The card is a picture that leaves the app, and the worst ground
 *     it can present is a white sleeve: under the scrim and the pane that
 *     flattens to rgb(83,85,88). The description and the line saying whose
 *     words they are have to clear 4.5:1 on it. Nudging a hex by a shade, or
 *     fading the caption with an alpha, is exactly the kind of change nobody
 *     re-measures.
 *
 *  2. NO TRUNCATION. app.js decides how much description it will send (about
 *     ten sentences, hard-capped at a number of characters) and sharecard.js
 *     decides how many lines it will draw (DESC_MAX, under a MAX_CARD_H
 *     ceiling). They are two numbers in two files. Raise the first without the
 *     second and the card ellipsizes most of a Wikipedia opening, and it still
 *     looks right in every screenshot of a short one. This runs the card's own
 *     measure() — the height arithmetic, which needs no canvas — on the longest
 *     text app.js can send.
 *
 * Since 0.5.0 the card is Rouen's (v1.8.77), and these are its rules: Rouen
 * guards the same pair in test/unit/sharecard-layout.test.js. The metric is
 * the one the cap was chosen against there — about 13.5px a character for
 * Manrope at 26px.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const WEB = process.env.MUSICD_WEB_DIR
  ? path.resolve(process.env.MUSICD_WEB_DIR)
  : path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'web');
const CARD_FILE = path.join(WEB, 'sharecard.js');
const APP_FILE = path.join(WEB, 'app.js');
const src = fs.readFileSync(CARD_FILE, 'utf8');
const appSrc = fs.readFileSync(APP_FILE, 'utf8');

/** The flattened worst-case ground, from the card's own reasoning. */
const WORST = [83, 85, 88];
const FLOOR = 4.5;

function luminance([r, g, b]) {
  const lin = [r, g, b].map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
}

function hexToRgb(hex) {
  const m = /^#([0-9a-f]{6})$/i.exec(hex);
  if (!m) throw new Error('not a 6-digit hex colour: ' + hex);
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function contrast(hex, ground) {
  const a = luminance(hexToRgb(hex));
  const b = luminance(ground);
  const [hi, lo] = a > b ? [a, b] : [b, a];
  return (hi + 0.05) / (lo + 0.05);
}

const failures = [];
const ok = (line) => console.log('  ok    ' + line);

/*
 * The colour a block is painted in: the first `ctx.fillStyle = '…'` after the
 * comment that names the block. Non-greedy, and wide enough to see past the
 * comment explaining the colour — Rouen's own regex once had a window too small
 * for that, which is how a fade passed unnoticed there.
 */
function paintAfter(marker) {
  const at = src.indexOf(marker);
  if (at < 0) return { err: `"${marker}" is gone from sharecard.js — check the colours by hand` };
  const m = /ctx\.fillStyle\s*=\s*'([^']+)'/.exec(src.slice(at, at + 1600));
  if (!m) return { err: `no fillStyle follows "${marker}"` };
  return { colour: m[1], tail: src.slice(at, at + 1600) };
}

for (const [label, marker] of [['description', '// --- Description ---'],
                               ['attribution', '// --- Source ---']]) {
  const p = paintAfter(marker);
  if (p.err) { failures.push(p.err); continue; }
  if (!/^#[0-9a-f]{6}$/i.test(p.colour)) {
    // rgba() or a named colour: anything with alpha is measured on a ground it
    // was never chosen against, and that is the change this exists to catch.
    failures.push(`the ${label} is painted ${p.colour} — use an opaque #rrggbb so its contrast is a fact`);
    continue;
  }
  const ratio = contrast(p.colour, WORST);
  const line = `${label} ${p.colour} on rgb(${WORST}) = ${ratio.toFixed(2)}:1`;
  if (ratio < FLOOR) failures.push(`${line} — under the ${FLOOR}:1 floor. A white sleeve makes this unreadable.`);
  else ok(line);
  // A globalAlpha before the text is drawn fades it just as surely.
  const upToText = p.tail.slice(0, p.tail.indexOf('fillText') + 1);
  const alpha = /globalAlpha\s*=\s*(0?\.\d+)/.exec(upToText);
  if (alpha) failures.push(`the ${label} is faded to globalAlpha ${alpha[1]} — that is the contrast this checks, removed`);
}

// ---- The coupling: app.js's trim against the card's line budget ------------

const cap = (() => {
  // Anchored through to the assignment that hands the text to the card, so it
  // is THIS cap and not some other length check in the file.
  const m = /if \(t\.length > (\d+)\) t = t\.slice\(0, \d+\)[\s\S]{0,160}?out\.review = t;/.exec(appSrc);
  return m ? Number(m[1]) : null;
})();

let ShareCard = null;
try {
  ShareCard = require(CARD_FILE);
} catch (e) {
  failures.push('sharecard.js could not be loaded in Node: ' + e.message);
}

if (cap === null) {
  failures.push('could not find the description cap in app.js (extraOf: `if (t.length > N) … out.review = t;`)');
} else if (ShareCard && typeof ShareCard.measure === 'function') {
  const stub = (perChar) => ({ font: '', measureText: (s) => ({ width: String(s).length * perChar }) });
  const base = { title: 'Zebra IV', artist: 'Zebra', releaseRaw: '2003-07-08', label: 'Mayhem Records' };
  // Real prose, repeated to the cap: short words wrap more generously than
  // long ones, so an ordinary word length rather than a best case.
  const word = 'album ';
  const text = word.repeat(Math.ceil(cap / word.length)).slice(0, cap).trim();

  const m = ShareCard.measure(stub(13.5), { ...base, review: text, reviewSource: 'Wikipedia' });
  if (!m.desc) {
    failures.push(`a ${cap}-character description — the longest app.js sends — is dropped from the card`);
  } else if (m.desc.lines[m.desc.lines.length - 1].endsWith('…')) {
    failures.push(`a ${cap}-character description is ellipsized at ${m.desc.lines.length} lines. ` +
                  'DESC_MAX and the app.js trim are a pair: raise one and the other has to follow.');
  } else {
    ok(`the longest description app.js sends (${cap} chars) fits whole, in ${m.desc.lines.length} lines`);
  }

  // The worst header with the longest text must clear the ceiling, without the
  // header eating the description.
  const tall = ShareCard.measure(stub(13.5), {
    ...base,
    title: 'An Extremely Long Album Title That Will Wrap Over Several Lines Indeed Yes Truly',
    artist: 'A Very Long Collaborative Artist Credit Naming Several Different People',
    review: text, reviewSource: 'Wikipedia'
  });
  if (tall.cardH > ShareCard.MAX_CARD_H) {
    failures.push(`a tall header and a full description make a ${tall.cardH}px card, over the ${ShareCard.MAX_CARD_H}px ceiling`);
  } else if (!tall.desc || tall.desc.lines.length < 15) {
    failures.push(`only ${tall.desc ? tall.desc.lines.length : 0} description lines survive a tall header — the header is eating it`);
  } else {
    ok(`worst-case header and description: ${tall.cardH}px card, ${tall.desc.lines.length} lines`);
  }
}

if (failures.length) {
  console.error('\nshare card checks FAILED:');
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log('\nShare card checks passed.');
