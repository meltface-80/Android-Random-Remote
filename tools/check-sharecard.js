#!/usr/bin/env node
/*
 * check-sharecard.js — the two promises the share card's blurb makes.
 *
 * A source-scanning guard, in the same spirit as check-api-contract.py and
 * JsonSafeTest: it reads sharecard.js rather than running it, because the card
 * is drawn on a canvas and there is no browser in CI. It cannot tell you the
 * card looks right. It can tell you the two things that would go wrong quietly.
 *
 *  1. CONTRAST. The card is a picture that leaves the app, and the worst ground
 *     it can present is a white sleeve: under the scrim and the pane that
 *     flattens to rgb(83,85,88). The RELEASED line was already solved against
 *     that (an earlier colour measured 2.31:1 and was unreadable); the blurb
 *     and its credit have to clear the same bar. Nudging a hex by a shade is
 *     exactly the kind of change nobody re-measures.
 *
 *  2. NO TRUNCATION. "Grow the card, do not cut the words" is the whole design
 *     of the band. A line limit that creeps down turns it back into an
 *     ellipsis, and the card would still look fine in every screenshot taken
 *     of a short blurb.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const FILE = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'web', 'sharecard.js');
const src = fs.readFileSync(FILE, 'utf8');

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

/** The value of `const NAME = '#rrggbb';` in the card. */
function colourOf(name) {
  const m = new RegExp("const\\s+" + name + "\\s*=\\s*'(#[0-9a-fA-F]{6})'").exec(src);
  if (!m) throw new Error(`${name} is gone from sharecard.js — did it get renamed?`);
  return m[1];
}

const failures = [];

for (const name of ['BIO_FG', 'CREDIT_FG']) {
  const hex = colourOf(name);
  const ratio = contrast(hex, WORST);
  const line = `${name} ${hex} on rgb(${WORST}) = ${ratio.toFixed(2)}:1`;
  if (ratio < FLOOR) {
    failures.push(`${line} — under the ${FLOOR}:1 floor. A white sleeve makes this unreadable.`);
  } else {
    console.log('  ok    ' + line);
  }
}

// The blurb is wrapped with a line limit no real extract can reach. Anything
// small enough to bite would put an ellipsis back into the card.
const wrap = /wrapText\(measure,\s*bioText,[^)]*?,\s*(\d+)\s*\)/.exec(src);
if (!wrap) {
  failures.push('the blurb no longer goes through wrapText(measure, bioText, …) — check by hand');
} else if (Number(wrap[1]) < 500) {
  failures.push(
    `the blurb is wrapped to ${wrap[1]} lines. That is a truncation limit, not a guard — ` +
    'the card is meant to grow and keep every word.'
  );
} else {
  console.log(`  ok    blurb wraps to ${wrap[1]} lines, so nothing is cut`);
}

if (failures.length) {
  console.error('\nshare card checks FAILED:');
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log('\nShare card blurb checks passed.');
