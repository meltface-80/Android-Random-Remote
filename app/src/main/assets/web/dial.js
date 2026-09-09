/*
 * dial.js — the dial, for a browser.
 *
 * WHAT THIS IS AND IS NOT. The dial proper is a native Android View
 * (app/src/main/java/com/musicd/lite/android/dial/DialView.kt), ported from
 * meltface-80/dial-for-Roon and synced by tools/sync-dial.py. Nothing here
 * touches it, and nothing here is synced: this is a second implementation of
 * the same control surface, in canvas, for the phones and tablets that reach
 * this app over the LAN and have no APK to install.
 *
 * That means the two can drift, and the way they are kept honest is that every
 * number below is taken from DialView and named the same. The geometry
 * (0.115 ring, 0.58 transport row), the palette, the 320 degrees of sweep, the
 * 60ms send interval and the 900ms optimistic window are its, not new choices.
 * If DialView changes, this is where to change it back.
 *
 * WHAT IS MISSING, AND WHY IT CANNOT BE ADDED. The native dial has a fourth
 * control: the microphone. A browser will not open a microphone on an insecure
 * origin, and this page is served over plain http on a LAN address, so
 * SpeechRecognition and getUserMedia are both unavailable here. The button is
 * absent rather than present and dead, and the three that remain are spaced to
 * fill the row.
 *
 * NO TIMER POLLS ANYTHING. State arrives on the same long poll the main page
 * uses — /api/zone-state?wait_for=<revision> — which is also why the progress
 * arc advances: Roon's own seek updates bump the revision.
 */
'use strict';

(function () {
  // ---------------------------------------------------------------- palette
  // DialView's companion object, verbatim.
  const BG              = '#07080A';
  const RING_TRACK      = '#1C222A';
  const RING_FILL       = '#7AC8FF';
  const RING_MUTED      = '#5A6675';
  const THUMB           = '#EAF4FF';
  const TEXT_PRIMARY    = '#F2F5F8';
  const TEXT_SECONDARY  = '#98A3AF';
  const ART_PLACEHOLDER = '#141A21';
  const PROGRESS        = '#3C77A8';

  const DEGREES_FOR_FULL_RANGE = 320.0;
  const DEGREES_PER_INCREMENT  = 14.0;
  const SEND_INTERVAL_MS       = 60;
  const OPTIMISTIC_WINDOW_MS   = 900;

  const canvas = document.getElementById('dial');
  const ctx    = canvas.getContext('2d');

  // ------------------------------------------------------------------ state
  let zone = null;            // the /api/zone-state "zone" object
  let revision = 0;
  let statusText = 'Connecting…';
  let art = null;             // an <img>, already decoded
  let artKey = null;

  let optimisticValue = null;
  let lastGestureAt = 0;
  let pendingSteps = 0;
  let lastSendAt = 0;
  let sendTimer = null;
  let residual = 0;
  let residualDegrees = 0;

  let cx = 0, cy = 0, radius = 0, ringWidth = 0, innerRadius = 0, transportY = 0;

  // ------------------------------------------------------------ zone helpers
  function volumeOutputs() {
    return ((zone && zone.outputs) || []).filter((o) => o && o.volume);
  }
  function primaryVolume() {
    const o = volumeOutputs()[0];
    return o ? o.volume : null;
  }
  function isMuted() {
    return volumeOutputs().some((o) => o.is_muted);
  }
  function isIncremental(v) { return !!v && v.type === 'incremental'; }

  /** Roon's soft limit is a ceiling its owner set; the dial must not sweep past it. */
  function effectiveMax(v) {
    return (v.soft_limit === null || v.soft_limit === undefined)
      ? v.max : Math.min(v.soft_limit, v.max);
  }
  function displayedValue() {
    const v = primaryVolume();
    if (!v || isIncremental(v)) return null;
    return optimisticValue !== null ? optimisticValue : v.value;
  }
  function displayedFraction() {
    const v = primaryVolume();
    if (!v || isIncremental(v)) return 0;
    const span = effectiveMax(v) - v.min;
    if (span <= 0) return 0;
    const value = optimisticValue !== null ? optimisticValue : v.value;
    return Math.min(1, Math.max(0, (value - v.min) / span));
  }
  function formatVolume(v) {
    if (isMuted()) return 'muted';
    if (!v) return '—';
    if (isIncremental(v)) return '+/-';
    const value = displayedValue();
    if (v.type === 'db') return value.toFixed(1) + ' dB';
    return Math.abs(value - Math.floor(value)) < 1e-9
      ? String(Math.round(value)) : value.toFixed(1);
  }

  // --------------------------------------------------------------- geometry
  function layout() {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    canvas.width  = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    cx = w / 2;
    cy = h / 2;
    radius = Math.min(w, h) / 2 - 8;
    ringWidth = radius * 0.115;
    innerRadius = radius - ringWidth - 10;
    transportY = cy + innerRadius * 0.58;
  }

  const transportRadius = () => innerRadius * 0.16;

  /** Previous, play/pause, next. The native dial's fourth seat is the mic. */
  function controlCentres() {
    const spacing = innerRadius * 0.38;
    return [cx - spacing, cx, cx + spacing];
  }

  // --------------------------------------------------------------- rendering
  let drawQueued = false;
  function invalidate() {
    if (drawQueued) return;
    drawQueued = true;
    requestAnimationFrame(() => { drawQueued = false; draw(); });
  }

  function draw() {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    ctx.fillStyle = BG;
    ctx.fillRect(0, 0, w, h);
    drawRing();
    drawProgress();
    drawArtwork();
    drawText();
    drawTransport();
  }

  function arc(r, fromDeg, sweepDeg, colour, width, cap) {
    ctx.beginPath();
    ctx.strokeStyle = colour;
    ctx.lineWidth = width;
    ctx.lineCap = cap || 'round';
    ctx.arc(cx, cy, r, fromDeg * Math.PI / 180, (fromDeg + sweepDeg) * Math.PI / 180);
    ctx.stroke();
  }
  function disc(x, y, r, colour) {
    ctx.beginPath();
    ctx.fillStyle = colour;
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawRing() {
    const r = radius - ringWidth / 2;
    arc(r, 0, 360, RING_TRACK, ringWidth, 'butt');

    const v = primaryVolume();
    if (!v) return;                       // fixed-volume output: nothing to show

    const colour = isMuted() ? RING_MUTED : RING_FILL;
    if (isIncremental(v)) {
      // No range is reported, so there is nothing to fill. Detents instead, to
      // say the ring still works as a +/- control.
      for (let deg = -90; deg < 270; deg += 12) {
        const rad = deg * Math.PI / 180;
        disc(cx + r * Math.cos(rad), cy + r * Math.sin(rad), ringWidth * 0.12, colour);
      }
      return;
    }

    const fraction = displayedFraction();
    if (fraction > 0) arc(r, -90, 360 * fraction, colour, ringWidth);
    const angle = (-90 + 360 * fraction) * Math.PI / 180;
    disc(cx + r * Math.cos(angle), cy + r * Math.sin(angle),
         ringWidth * 0.30, isMuted() ? RING_MUTED : THUMB);
  }

  function drawProgress() {
    const np = zone && zone.now_playing;
    if (!np || !np.length || np.seek_position === null || np.seek_position === undefined) return;
    if (np.length <= 0) return;
    arc(innerRadius + 5, -90, 360 * (np.seek_position / np.length), PROGRESS, 2.5);
  }

  function drawArtwork() {
    if (!art) { disc(cx, cy, innerRadius, ART_PLACEHOLDER); return; }
    ctx.save();
    ctx.beginPath();
    ctx.arc(cx, cy, innerRadius, 0, Math.PI * 2);
    ctx.clip();
    const size = 2 * innerRadius;
    const scale = Math.max(size / art.width, size / art.height);
    ctx.drawImage(art, cx - art.width * scale / 2, cy - art.height * scale / 2,
                  art.width * scale, art.height * scale);
    ctx.restore();
    // Scrim, so the overlaid text stays legible on a bright cover.
    disc(cx, cy, innerRadius, 'rgba(4,6,9,.588)');
  }

  function label(text, y, size, colour, bold) {
    ctx.fillStyle = colour;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    ctx.font = (bold ? '700 ' : '400 ') + size + 'px system-ui, -apple-system, sans-serif';
    ctx.fillText(text, cx, y);
  }

  function ellipsise(text, maxWidth) {
    if (!text) return '';
    if (ctx.measureText(text).width <= maxWidth) return text;
    let end = text.length;
    while (end > 1 && ctx.measureText(text.slice(0, end) + '…').width > maxWidth) end--;
    return text.slice(0, end) + '…';
  }

  function wrap(text, perLine) {
    const out = [];
    let cur = '';
    for (const word of String(text || '').split(' ')) {
      if (!cur) cur = word;
      else if (cur.length + 1 + word.length <= perLine) cur += ' ' + word;
      else { out.push(cur); cur = word; }
    }
    if (cur) out.push(cur);
    return out;
  }

  function drawText() {
    const adjusting = Date.now() - lastGestureAt < OPTIMISTIC_WINDOW_MS;
    const v = primaryVolume();

    // Zone name, top of the inner circle.
    ctx.font = '400 ' + (innerRadius * 0.11) + 'px system-ui, sans-serif';
    label(ellipsise(zone ? zone.display_name : 'No zone', innerRadius * 1.5),
          cy - innerRadius * 0.60, innerRadius * 0.11, TEXT_SECONDARY, false);

    if (adjusting) {
      // While the ring is moving, the number is the point.
      const big = !v ? '—' : isMuted() ? 'muted' : isIncremental(v) ? '+/-'
        : v.type === 'db' ? displayedValue().toFixed(1) : String(Math.round(displayedValue()));
      label(big, cy + innerRadius * 0.10, innerRadius * 0.42, TEXT_PRIMARY, true);
      const units = !v ? 'no volume control' : v.type === 'db' ? 'dB' : 'volume';
      label(units, cy + innerRadius * 0.28, innerRadius * 0.12, TEXT_SECONDARY, false);
      return;
    }

    const np = zone && zone.now_playing;
    if (!np) {
      let y = cy - innerRadius * 0.05;
      for (const line of wrap(statusText, 26)) {
        label(line, y, innerRadius * 0.11, TEXT_SECONDARY, false);
        y += innerRadius * 0.15;
      }
      return;
    }

    ctx.font = '700 ' + (innerRadius * 0.155) + 'px system-ui, sans-serif';
    label(ellipsise(np.line1, innerRadius * 1.6), cy - innerRadius * 0.10,
          innerRadius * 0.155, TEXT_PRIMARY, true);
    ctx.font = '400 ' + (innerRadius * 0.125) + 'px system-ui, sans-serif';
    label(ellipsise(np.line2, innerRadius * 1.6), cy + innerRadius * 0.09,
          innerRadius * 0.125, TEXT_SECONDARY, false);
    ctx.font = '400 ' + (innerRadius * 0.105) + 'px system-ui, sans-serif';
    label(ellipsise(np.line3, innerRadius * 1.6), cy + innerRadius * 0.25,
          innerRadius * 0.105, TEXT_SECONDARY, false);

    // Small persistent volume readout under the zone name.
    if (v) {
      label(formatVolume(v), cy - innerRadius * 0.44, innerRadius * 0.10,
            isMuted() ? RING_MUTED : TEXT_SECONDARY, false);
    }
  }

  function drawTransport() {
    const centres = controlCentres();
    const y = transportY;
    const r = transportRadius();

    disc(centres[1], y, r, 'rgba(255,255,255,.275)');
    ctx.fillStyle = zone ? TEXT_PRIMARY : TEXT_SECONDARY;

    drawSkip(centres[0], y, r * 0.62, true);
    if (zone && zone.state === 'playing') drawPause(centres[1], y, r * 0.52);
    else drawPlay(centres[1], y, r * 0.58);
    drawSkip(centres[2], y, r * 0.62, false);
  }

  function drawPlay(x, y, s) {
    ctx.beginPath();
    ctx.moveTo(x - s * 0.55, y - s);
    ctx.lineTo(x + s * 0.85, y);
    ctx.lineTo(x - s * 0.55, y + s);
    ctx.closePath();
    ctx.fill();
  }
  function drawPause(x, y, s) {
    const w = s * 0.42;
    ctx.fillRect(x - s * 0.75, y - s, w, s * 2);
    ctx.fillRect(x + s * 0.33, y - s, w, s * 2);
  }
  function drawSkip(x, y, s, back) {
    const dir = back ? -1 : 1;
    ctx.beginPath();
    ctx.moveTo(x - dir * s * 0.9, y - s);
    ctx.lineTo(x + dir * s * 0.1, y);
    ctx.lineTo(x - dir * s * 0.9, y + s);
    ctx.closePath();
    ctx.moveTo(x + dir * s * 0.05, y - s);
    ctx.lineTo(x + dir * s * 1.05, y);
    ctx.lineTo(x + dir * s * 0.05, y + s);
    ctx.closePath();
    ctx.fill();
  }

  // --------------------------------------------------------------- gestures
  let mode = 'none';
  let lastAngle = 0, downX = 0, downY = 0, moved = false;
  const TOUCH_SLOP = 10;

  function pointAt(e) {
    const rect = canvas.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  }
  const angleOf = (x, y) => Math.atan2(y - cy, x - cx) * 180 / Math.PI;

  canvas.addEventListener('pointerdown', (e) => {
    canvas.setPointerCapture(e.pointerId);
    const p = pointAt(e);
    downX = p.x; downY = p.y; moved = false;
    residual = 0; residualDegrees = 0;
    const dist = Math.hypot(p.x - cx, p.y - cy);
    if (dist >= radius - ringWidth * 1.7 && dist <= radius + ringWidth) {
      lastAngle = angleOf(p.x, p.y);
      lastGestureAt = Date.now();
      mode = 'ring';
    } else {
      mode = 'inner';
    }
    invalidate();
  });

  canvas.addEventListener('pointermove', (e) => {
    if (mode === 'none') return;
    const p = pointAt(e);
    if (!moved && Math.hypot(p.x - downX, p.y - downY) > TOUCH_SLOP) moved = true;
    if (mode !== 'ring') return;
    const angle = angleOf(p.x, p.y);
    let delta = angle - lastAngle;
    // Keep the sweep continuous across the 12 o'clock seam.
    if (delta > 180) delta -= 360;
    if (delta < -180) delta += 360;
    lastAngle = angle;
    if (Math.abs(delta) < 90) applyRotation(delta);
    lastGestureAt = Date.now();
    invalidate();
  });

  function endGesture(e) {
    if (mode === 'ring') {
      flushSteps();
      setTimeout(() => {
        if (Date.now() - lastGestureAt >= OPTIMISTIC_WINDOW_MS) {
          optimisticValue = null;
          invalidate();
        }
      }, OPTIMISTIC_WINDOW_MS + 50);
    } else if (mode === 'inner' && !moved) {
      const p = pointAt(e);
      handleTap(p.x, p.y);
    }
    mode = 'none';
    invalidate();
  }
  canvas.addEventListener('pointerup', endGesture);
  canvas.addEventListener('pointercancel', () => { flushSteps(); mode = 'none'; invalidate(); });

  /**
   * Rotation into whole volume steps. Everything below one step is kept in
   * `residual`, so a slow sweep accumulates instead of being rounded away.
   */
  function applyRotation(degrees) {
    const v = primaryVolume();
    if (!v) return;
    let steps;
    if (isIncremental(v)) {
      residualDegrees += degrees;
      steps = Math.trunc(residualDegrees / DEGREES_PER_INCREMENT);
      if (!steps) return;
      residualDegrees -= steps * DEGREES_PER_INCREMENT;
    } else {
      const span = effectiveMax(v) - v.min;
      if (span <= 0) return;
      residual += degrees * (span / DEGREES_FOR_FULL_RANGE);
      steps = Math.trunc(residual / v.step);
      if (!steps) return;
      residual -= steps * v.step;
      const base = optimisticValue !== null ? optimisticValue : v.value;
      optimisticValue = Math.min(effectiveMax(v), Math.max(v.min, base + steps * v.step));
    }
    buzz(8);

    pendingSteps += steps;
    const now = Date.now();
    if (now - lastSendAt >= SEND_INTERVAL_MS) flushSteps();
    else if (!sendTimer) {
      sendTimer = setTimeout(flushSteps, SEND_INTERVAL_MS - (now - lastSendAt));
    }
  }

  function flushSteps() {
    if (sendTimer) { clearTimeout(sendTimer); sendTimer = null; }
    if (!pendingSteps) return;
    const steps = pendingSteps;
    pendingSteps = 0;
    lastSendAt = Date.now();
    // "relative_step" is what the native dial sends. /api/volume turns it into
    // Roon's relative +/-1 by itself when the output is incremental.
    post('/api/volume', { zone_or_output_id: zone.zone_id, how: 'relative_step', value: steps });
  }

  function handleTap(x, y) {
    const centres = controlCentres();
    const r = transportRadius() * 1.30;
    const commands = ['previous', 'playpause', 'next'];
    for (let i = 0; i < centres.length; i++) {
      if (Math.hypot(x - centres[i], y - transportY) > r) continue;
      buzz(12);
      if (zone) post('/api/control', { zone_or_output_id: zone.zone_id, command: commands[i] });
      return;
    }
    // Upper third of the inner circle: the zone name, then the volume readout.
    if (y < cy - innerRadius * 0.50) { openZones(); return; }
    if (y < cy - innerRadius * 0.30) {
      buzz(12);
      if (zone) post('/api/volume', { zone_or_output_id: zone.zone_id, mute: !isMuted() });
    }
  }

  /** Best-effort. Android browsers honour this; iOS Safari ignores it. */
  function buzz(ms) {
    if (navigator.vibrate) { try { navigator.vibrate(ms); } catch (e) { /* ignore */ } }
  }

  // ------------------------------------------------------------------ server
  async function post(path, body) {
    try {
      await fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
    } catch (e) { /* best effort: the next zone update is the source of truth */ }
  }

  async function loadArt(key) {
    if (key === artKey) return;
    artKey = key;
    if (!key) { art = null; invalidate(); return; }
    const size = Math.min(1024, Math.max(256, Math.round(innerRadius * 2 * (window.devicePixelRatio || 1))));
    const img = new Image();
    img.src = '/api/image/' + encodeURIComponent(key) + '?size=' + size;
    try {
      await img.decode();
      if (artKey !== key) return;      // a newer track won while this decoded
      art = img;
    } catch (e) {
      if (artKey === key) art = null;
    }
    invalidate();
  }

  /**
   * The long poll. One request is outstanding at a time and it sleeps on the
   * server until the zone actually changes, so this is not a timer and does
   * not become one on failure: a failed request backs off before retrying.
   */
  async function watch() {
    let backoff = 1000;
    for (;;) {
      const startedAt = Date.now();
      try {
        const qs = new URLSearchParams();
        if (revision) qs.set('wait_for', String(revision));
        const r = await fetch('/api/zone-state?' + qs, { cache: 'no-store' });
        if (r.status === 401 || r.status === 403 || r.status === 404) {
          // The LAN gate has logged us out. Its own page knows what to do.
          location.reload();
          return;
        }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        backoff = 1000;
        revision = data.revision || 0;
        zone = data.zone || null;
        statusText = zone ? 'Nothing playing' : 'No zone';
        loadArt(zone && zone.now_playing ? zone.now_playing.image_key : null);
        invalidate();
        // A floor, not a timer. The request above is meant to SLEEP on the
        // server until the zone changes, so this normally adds nothing at all.
        // It is here for the case where it comes back instantly and keeps
        // doing so: without it that is not a poll, it is a request storm.
        const took = Date.now() - startedAt;
        if (took < 200) await new Promise((res) => setTimeout(res, 200 - took));
      } catch (e) {
        statusText = 'Reconnecting…';
        invalidate();
        await new Promise((res) => setTimeout(res, backoff));
        backoff = Math.min(backoff * 2, 15000);
      }
    }
  }

  // ------------------------------------------------------------- zone picker
  const zonesEl = document.getElementById('zones');
  const listEl  = document.getElementById('zone-list');

  zonesEl.addEventListener('click', (e) => {
    if (e.target === zonesEl) zonesEl.classList.remove('open');
  });

  async function openZones() {
    listEl.innerHTML = '';
    zonesEl.classList.add('open');
    let zones = [];
    try {
      const r = await fetch('/api/zones', { cache: 'no-store' });
      const data = await r.json();
      zones = data.zones || data || [];
    } catch (e) { /* an empty sheet says the same thing a message would */ }
    for (const z of zones) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'zone-row' + (zone && z.zone_id === zone.zone_id ? ' is-current' : '');
      btn.textContent = z.display_name || z.zone_id;
      if (z.state) {
        const s = document.createElement('small');
        s.textContent = z.state;
        btn.appendChild(s);
      }
      btn.addEventListener('click', () => {
        zonesEl.classList.remove('open');
        // Ask for that zone by name once; the watcher then follows it, because
        // the server remembers what was last asked for. Zone ids do not survive
        // a Core restart, so nothing is stored here.
        revision = 0;
        fetch('/api/zone-state?zone=' + encodeURIComponent(z.zone_id), { cache: 'no-store' })
          .then((r) => r.json())
          .then((data) => {
            zone = data.zone || null;
            revision = data.revision || 0;
            loadArt(zone && zone.now_playing ? zone.now_playing.image_key : null);
            invalidate();
          })
          .catch(() => { /* the watcher will catch up on its own */ });
      });
      listEl.appendChild(btn);
    }
  }

  // ------------------------------------------------------------------ start
  window.addEventListener('resize', () => { layout(); invalidate(); });
  layout();
  invalidate();
  watch();
})();
