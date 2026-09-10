#!/usr/bin/env node
/*
 * Structural check on the bundled stylesheet.
 *
 * WHY THIS EXISTS. A rule was deleted by hand and the delete ate the "*\/" of
 * the comment above it, so the comment ran on and swallowed the next rules
 * whole. The file still "looked fine", CSS has no syntax errors to report, and
 * the browser silently dropped .lib-ctl — so the Sort control reverted to the
 * user-agent button: a white pill on a dark screen. It shipped twice that way.
 *
 * Nothing else in this repo reads the stylesheet, so nothing else could have
 * caught it. Two faults are flagged, and they are the two that hide:
 *
 *   1. an unterminated comment, or a comment holding an unbalanced brace —
 *      prose may quote a whole rule ("main { padding: 14px }"), so balanced
 *      braces inside a comment are left alone and an odd one is the signal;
 *   2. a declaration outside any block — what a swallowed "selector {" leaves
 *      behind, and what the browser throws away.
 */
'use strict';
const fs = require('fs');

const files = process.argv.slice(2);
if (!files.length) files.push('app/src/main/assets/web/style.css');

let failed = 0;

for (const file of files) {
  const css = fs.readFileSync(file, 'utf8');
  const problems = [];

  // Pass one: comments. Replaced by spaces (newlines kept) so pass two sees
  // the same line numbers.
  let stripped = '';
  let i = 0, line = 1;
  while (i < css.length) {
    if (css[i] === '/' && css[i + 1] === '*') {
      const start = line;
      let body = '', j = i + 2;
      while (j < css.length && !(css[j] === '*' && css[j + 1] === '/')) {
        if (css[j] === '\n') { line++; stripped += '\n'; } else stripped += ' ';
        body += css[j];
        j++;
      }
      if (j >= css.length) {
        problems.push(`${start}: comment is never closed`);
        i = j;
      } else {
        stripped += '  ';
        i = j + 2;
      }
      const open = (body.match(/\{/g) || []).length;
      const close = (body.match(/\}/g) || []).length;
      if (open !== close) {
        problems.push(`${start}: comment holds ${open} "{" and ${close} "}" — `
          + `a rule was probably swallowed: ${JSON.stringify(body.slice(0, 80))}`);
      }
      stripped += '  ';
      continue;
    }
    if (css[i] === '\n') line++;
    stripped += css[i];
    i++;
  }

  // Pass two: a declaration at depth zero. Walk to each brace; the text before
  // an opening one is a selector (or an at-rule prelude) and may not contain a
  // ";", which only a declaration does.
  let depth = 0;
  line = 1;
  let chunk = '', chunkLine = 1;
  const flush = (brace) => {
    if (depth === 0 && brace === '{' && /;/.test(chunk)) {
      const decl = chunk.split(';').find((p) => p.includes(':')) || chunk;
      problems.push(`${chunkLine}: declaration outside any rule: `
        + JSON.stringify(decl.trim().slice(0, 60)));
    }
    chunk = '';
    chunkLine = line;
  };
  for (let k = 0; k < stripped.length; k++) {
    const c = stripped[k];
    if (c === '\n') line++;
    if (c === '{') { flush('{'); depth++; continue; }
    if (c === '}') {
      if (depth === 0) problems.push(`${line}: "}" with no rule open`);
      else depth--;
      chunk = ''; chunkLine = line;
      continue;
    }
    if (!chunk.trim() && /\s/.test(c)) { chunkLine = line; continue; }
    chunk += c;
  }
  if (depth !== 0) problems.push(`end of file: ${depth} rule(s) left open`);
  // A trailing declaration after the last "}" never reaches flush().
  if (depth === 0 && /;/.test(chunk)) {
    problems.push(`${chunkLine}: declaration outside any rule: `
      + JSON.stringify(chunk.trim().slice(0, 60)));
  }

  if (problems.length) {
    failed += problems.length;
    console.error(`${file}:`);
    for (const p of problems) console.error(`  ${file}:${p}`);
  } else {
    console.log(`${file}: ok`);
  }
}

if (failed) {
  console.error(`\n${failed} problem(s). Every one of these is a rule the `
    + `browser drops without saying so.`);
  process.exit(1);
}
