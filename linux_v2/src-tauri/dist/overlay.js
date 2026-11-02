/* GUPet Linux desktop overlay: pixel pet, physics, gestures, skins and reactions. */
(function () {
  'use strict';

  const tauri = window.__TAURI__;
  const fatalEl = document.getElementById('fatal');
  if (!tauri || !tauri.core || !tauri.event || !tauri.window) {
    showFatal('Tauri API is not available');
    return;
  }

  const invoke = tauri.core.invoke;
  const listen = tauri.event.listen;
  const appWindow = tauri.window.getCurrentWindow();
  const root = document.getElementById('root');

  const DESKTOP_SCALE = 2.4;
  const GRID_W = 18;
  const GRID_H = 13;
  const GRID_TOP = 2;
  const MAX_PARTICLES = 14;
  const LONG_PRESS_MS = 420;
  const DOUBLE_TAP_MS = 320;
  const PEEK_AFTER_MS = 45_000;

  const S = {
    IDLE: 'idle',
    WALK: 'walk',
    PRESS: 'press',
    DRAG: 'drag',
    PET: 'pet',
    FLY: 'fly',
    SLEEP: 'sleep',
    HANG: 'hang',
    PEEK: 'peek'
  };
  const P = {
    IDLE: 'idle', WALK: 'walk', PRESS: 'press', DRAG: 'drag', FLY: 'fly', PET: 'pet',
    SLEEP: 'sleep', HANG: 'hang', PEEK: 'peek', STRETCH: 'stretch', YAWN: 'yawn',
    GROOM: 'groom', SPIN: 'spin', DANCE: 'dance'
  };
  const E = {
    OPEN: 'open', BLINK: 'blink', HAPPY: 'happy', SURPRISED: 'surprised',
    DIZZY: 'dizzy', ANGRY: 'angry', SLEEP: 'sleep', LOVE: 'love'
  };
  const KIND = { HEART: 'heart', SPARK: 'spark', ZZZ: 'zzz', NOTE: 'note', STAR: 'star' };

  const DEFAULTS = {
    show: true,
    species: 'gupet',
    palette: 0,
    colorHex: null,
    sizeDp: 4,
    wander: true,
    phrases: true,
    gravity: false,
    shake: true,
    haptics: true,
    edgeVibration: true,
    poop: true,
    maxPoops: 3,
    poopSize: 1,
    poopInterval: 1,
    poopColor: '#8b5a2b',
    poopFadeMs: 500,
    weight: 1,
    reactToPoop: true,
    reactToOtherPets: true,
    wakeOnPetTouch: true,
    hideOnAppOpen: false,
    canHang: true,
    sleepMinutes: 3,
    reactNotifications: false,
    dance: true,
    customSkinId: null,
    customSkinRevision: 0,
    activeSkinIds: ['builtin'],
    petsCollide: true,
    persistPosition: true,
    gravityStrength: 1,
    slideFriction: 1,
    phraseColor: '#ffffff',
    phraseFontSize: 14,
    phraseBgColor: '#0b0b0b',
    phraseBgAlpha: 0.85,
    phraseOutlineColor: '#ff4d30',
    phraseOutlineWidth: 2,
    phraseDurationMs: 3000,
    phraseFadeMs: 400,
    phraseFadeStyle: 'fade',
    lastPosX: null,
    lastPosY: null
  };

  const DEFAULT_PC = {
    extendedFrames: false,
    cursorTouch: false,
    cursorNear: false,
    cursorFar: false,
    appOpen: false,
    hideOnAppOpen: false,
    hideMode: 'all',
    hideFilter: 'only',
    hideApps: [],
    cursorReaction: 'none',
    cursorDetection: 'everywhere',
    cursorDistance: 5,
    cursorSpeed: 50,
    extraFrames: {}
  };

  function normalizePcSettings(value) {
    const src = value && typeof value === 'object' ? value : {};
    const reaction = src.cursorReaction === 'chase' || src.cursorReaction === 'flee'
      ? src.cursorReaction : 'none';
    const detection = src.cursorDetection === 'distance' ? 'distance' : 'everywhere';
    const distance = Math.round(clamp(Number(src.cursorDistance) || 5, 1, 10));
    const speed = Math.round(clamp(Number(src.cursorSpeed) || 50, 1, 100));
    const apps = Array.isArray(src.hideApps)
      ? src.hideApps.map(function (item) { return String(item || '').trim().toLowerCase(); })
        .filter(function (item) { return item.length > 0 && item.length <= 120; })
        .slice(0, 64)
      : [];
    const uniqueApps = Array.from(new Set(apps));
    const hideMode = src.hideMode === 'selected' || src.hideMode === 'all'
      ? src.hideMode
      : (uniqueApps.length ? 'selected' : 'all');
    const hideFilter = src.hideFilter === 'except' ? 'except' : 'only';
    return {
      extendedFrames: !!src.extendedFrames,
      cursorTouch: !!src.cursorTouch,
      cursorNear: !!src.cursorNear,
      cursorFar: !!src.cursorFar,
      appOpen: !!src.appOpen,
      hideOnAppOpen: !!src.hideOnAppOpen,
      hideMode: hideMode,
      hideFilter: hideFilter,
      hideApps: uniqueApps,
      cursorReaction: reaction,
      cursorDetection: detection,
      cursorDistance: distance,
      cursorSpeed: speed,
      extraFrames: src.extraFrames && typeof src.extraFrames === 'object' ? src.extraFrames : {}
    };
  }

  const app = {
    settings: Object.assign({}, DEFAULTS),
    phrases: [],
    activeIds: ['builtin'],
    skins: new Map(),
    skinCache: new Map(),
    pets: [],
    poops: [],
    running: false,
    settingsVisible: false,
    openApps: [],
    openAppEntries: [],
    poopsCleaned: 0,
    lastCursorPollAt: 0,
    lastAppsPollAt: 0,
    mediaPlaying: false,
    mediaTrack: '',
    loaded: false,
    ignoreApplied: false,
    ignoreWanted: true,
    interacting: false,
    activeTouch: null,
    lastPositionSaveAt: 0,
    reloadInFlight: null,
    reloadQueued: false,
    reloadTimer: null,
    fatalShown: false
  };

  let screenW = Math.max(1, window.innerWidth);
  let screenH = Math.max(1, window.innerHeight);
  let dpr = window.devicePixelRatio || 1;
  let windowOrigin = { x: 0, y: 0 };
  let windowScale = dpr;
  let lastTick = performance.now();
  let nextFrameAt = 0;
  let lastStatusAt = 0;
  let lastCursor = null;
  let lastHit = null;
  let inputRegionLast = '';
  let inputRegionBusy = false;
  let inputRegionPending = null;
  let lastInputRects = [];

  function showFatal(message) {
    if (app.fatalShown) return;
    app.fatalShown = true;
    if (fatalEl) {
      fatalEl.textContent = 'GUPet overlay: ' + message;
      fatalEl.style.display = 'block';
    }
    console.error('[gupet-overlay]', message);
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function randFloat(min, max) {
    if (max <= min) return min;
    return min + Math.random() * (max - min);
  }

  function randInt(min, max) {
    if (max <= min) return min;
    return Math.floor(min + Math.random() * (max - min));
  }

  function chance(probability) {
    return Math.random() < probability;
  }

  function dpInt(value) {
    return Math.max(1, Math.round(value * DESKTOP_SCALE));
  }

  function dpFloat(value) {
    return value * DESKTOP_SCALE;
  }

  function maxPetUnit() {
    // Полностью помещаем питомца на экран: окно 18x17 клеток, спрайт 18x13.
    const byWidth = Math.max(1, screenW / GRID_W);
    const byHeight = Math.max(1, screenH / (GRID_H + 4));
    return Math.max(1, Math.min(byWidth, byHeight));
  }

  function unitForSizeDp(value) {
    const size = clamp(Number(value) || 4, 1, 10);
    const minUnit = 1;
    // Значение 4 сохраняем таким же, как в Android (4 * 2.4).
    const defaultUnit = DESKTOP_SCALE * 4;
    if (size <= 4) {
      const t = (size - 1) / 3;
      return minUnit * Math.pow(defaultUnit / minUnit, t);
    }
    const maxUnit = Math.max(defaultUnit, maxPetUnit());
    const t = (size - 4) / 6;
    return defaultUnit * Math.pow(maxUnit / defaultUnit, t);
  }

  function nowMs() {
    return performance.now();
  }

  function parseColor(input, fallback) {
    if (typeof input !== 'string') return fallback;
    let text = input.trim();
    if (!text) return fallback;
    if (text[0] === '#') text = text.slice(1);
    if (text.length === 3 || text.length === 4) {
      text = text.split('').map(function (part) { return part + part; }).join('');
    }
    if (text.length === 6) text = 'ff' + text;
    if (text.length !== 8 || !/^[0-9a-fA-F]{8}$/.test(text)) return fallback;
    return parseInt(text, 16) >>> 0;
  }

  function colorString(argb) {
    const a = ((argb >>> 24) & 255) / 255;
    const r = (argb >>> 16) & 255;
    const g = (argb >>> 8) & 255;
    const b = argb & 255;
    return 'rgba(' + r + ',' + g + ',' + b + ',' + a + ')';
  }

  function mixColors(a, b, amount) {
    const t = clamp(amount, 0, 1);
    const ar = (a >>> 16) & 255;
    const ag = (a >>> 8) & 255;
    const ab = a & 255;
    const br = (b >>> 16) & 255;
    const bg = (b >>> 8) & 255;
    const bb = b & 255;
    const r = Math.round(ar + (br - ar) * t);
    const g = Math.round(ag + (bg - ag) * t);
    const blue = Math.round(ab + (bb - ab) * t);
    return (((0xff000000 | (r << 16) | (g << 8) | blue) >>> 0));
  }

  const PALETTE_NAMES = ['Классический', 'Мятный', 'Лавандовый', 'Небесный', 'Розовый', 'Графит'];
  const PALETTE_BODY = [0xffd97757, 0xff4fbf98, 0xff9b8aee, 0xff4fa8e8, 0xffee85ae, 0xff697580];
  const PALETTE_EYE = [0xff37221e, 0xff163328, 0xff29234a, 0xff163248, 0xff4b1f35, 0xfff4f1e8];
  const PALETTE_SHADE = [0xffb7553f, 0xff2d9875, 0xff7866cb, 0xff317dac, 0xffc95783, 0xff45505d];
  const PALETTE_OUTLINE = [0xff743927, 0xff205e4a, 0xff4b3d8b, 0xff20577f, 0xff873958, 0xff303842];
  const PALETTE_HIGHLIGHT = [0xffffac85, 0xff9ce4c6, 0xffd3caff, 0xffb4e2ff, 0xffffc4d6, 0xffaeb8c1];

  function paletteFromIndex(index) {
    const i = clamp(Number(index) || 0, 0, PALETTE_BODY.length - 1);
    return {
      body: PALETTE_BODY[i],
      eye: PALETTE_EYE[i],
      shade: PALETTE_SHADE[i],
      outline: PALETTE_OUTLINE[i],
      highlight: PALETTE_HIGHLIGHT[i],
      blush: mixColors(PALETTE_BODY[i], 0xffff6f91, 0.58)
    };
  }

  function luminance01(color) {
    return (0.299 * ((color >>> 16) & 255) + 0.587 * ((color >>> 8) & 255) + 0.114 * (color & 255)) / 255;
  }

  function paletteFromColor(hex) {
    const parsed = parseColor(hex, 0);
    if (!parsed) return null;
    const body = (0xff000000 | (parsed & 0x00ffffff)) >>> 0;
    const shade = (0xff000000 | (Math.round(((body >>> 16) & 255) * 0.8) << 16) | (Math.round(((body >>> 8) & 255) * 0.8) << 8) | Math.round((body & 255) * 0.8)) >>> 0;
    const outline = (0xff000000 | (Math.round(((body >>> 16) & 255) * 0.55) << 16) | (Math.round(((body >>> 8) & 255) * 0.55) << 8) | Math.round((body & 255) * 0.55)) >>> 0;
    return {
      body: body,
      eye: luminance01(body) > 0.55 ? 0xff37221e : 0xfff4f1e8,
      shade: shade,
      outline: outline,
      highlight: mixColors(body, 0xffffffff, 0.45),
      blush: mixColors(body, 0xffff6f91, 0.58)
    };
  }

  function paletteFor(p) {
    return (p.settings.colorHex && paletteFromColor(p.settings.colorHex)) || paletteFromIndex(p.settings.palette);
  }

  const BODY_LEFT = [5, 4, 3, 2, 2, 2, 2, 2, 3, 4];
  const BODY_RIGHT = [13, 14, 15, 16, 16, 16, 16, 16, 15, 14];
  const GUPET_BODY_LEFT = [5, 4, 3, 3, 3, 4, 5];
  const GUPET_BODY_RIGHT = [13, 14, 15, 15, 15, 14, 13];
  const LEG_X = [4, 7, 11, 14];
  const GUPET_LEG_X = [4, 6, 11, 13];
  const SPRITE_HEART = ['.XX.XX.', 'XXXXXXX', 'XXXXXXX', '.XXXXX.', '..XXX..', '...X...'];
  const SPRITE_SPARK = ['..X..', '..X..', 'XX.XX', '..X..', '..X..'];
  const SPRITE_STAR = ['..X..', '.XXX.', 'XXXXX', '.XXX.', '..X..'];
  const SPRITE_ZZZ = ['XXXXX', '...X.', '..X..', '.X...', 'XXXXX'];
  const SPRITE_NOTE = ['..XXXXX', '..X...X', '..X...X', '..X...X', 'XXX.XXX', 'XXX.XXX'];
  const POOP_PATTERN = [
    '....X....', '...XXo...', '..XXXXX..', '.XXXXXXo.',
    '.XwkXwkX.', 'XXXXXXXXo', 'XXXXXXXXX'
  ];

  function drawPixel(ctx, x, y, w, h, color, unit, top) {
    ctx.fillStyle = colorString(color);
    ctx.fillRect(x * unit, top + y * unit, w * unit, h * unit);
  }

  function drawArm(ctx, side, raised, body, outline, highlight, unit, top) {
    const baseX = side < 0 ? 1 : 16;
    if (raised) {
      drawPixel(ctx, baseX, 5, 2, 3, outline, unit, top);
      drawPixel(ctx, baseX + (side < 0 ? 1 : 0), 5, 1, 2, body, unit, top);
      drawPixel(ctx, side < 0 ? 0 : 17, 4, 1, 2, body, unit, top);
    } else {
      drawPixel(ctx, baseX, 7, 2, 2, outline, unit, top);
      drawPixel(ctx, baseX + (side < 0 ? 1 : 0), 7, 1, 2, body, unit, top);
      drawPixel(ctx, side < 0 ? 0 : 17, 8, 1, 1, highlight, unit, top);
    }
  }

  function drawEye(ctx, x, y, color, outline, expression, unit, top, species) {
    if (species === 'gupet' || species === 'skin') {
      if (expression === E.BLINK || expression === E.SLEEP) {
        drawPixel(ctx, x - 1, y + 1, 3, 1, color, unit, top);
      } else if (expression === E.HAPPY) {
        drawPixel(ctx, x - 1, y + 1, 1, 1, color, unit, top);
        drawPixel(ctx, x, y, 1, 1, color, unit, top);
        drawPixel(ctx, x + 1, y + 1, 1, 1, color, unit, top);
      } else if (expression === E.SURPRISED) {
        drawPixel(ctx, x, y, 1, 2, color, unit, top);
        drawPixel(ctx, x, y, 1, 1, 0xffffffff, unit, top);
      } else if (expression === E.DIZZY) {
        drawPixel(ctx, x - 1, y, 1, 1, color, unit, top);
        drawPixel(ctx, x + 1, y, 1, 1, color, unit, top);
        drawPixel(ctx, x, y + 1, 1, 1, color, unit, top);
        drawPixel(ctx, x - 1, y + 2, 1, 1, color, unit, top);
        drawPixel(ctx, x + 1, y + 2, 1, 1, color, unit, top);
      } else if (expression === E.ANGRY) {
        drawPixel(ctx, x, y, 1, 2, color, unit, top);
        drawPixel(ctx, x - 1, y - 1, 2, 1, color, unit, top);
      } else if (expression === E.LOVE) {
        drawPixel(ctx, x - 1, y, 1, 1, 0xffff6485, unit, top);
        drawPixel(ctx, x + 1, y, 1, 1, 0xffff6485, unit, top);
        drawPixel(ctx, x, y + 1, 1, 1, 0xffff6485, unit, top);
      } else {
        drawPixel(ctx, x, y, 1, 2, color, unit, top);
        drawPixel(ctx, x, y, 1, 1, 0xffffffff, unit, top);
      }
      return;
    }
    if (expression === E.BLINK || expression === E.SLEEP) {
      drawPixel(ctx, x - 1, y + 1, 3, 1, color, unit, top);
      drawPixel(ctx, x, y, 1, 1, outline, unit, top);
    } else if (expression === E.HAPPY) {
      drawPixel(ctx, x - 1, y + 1, 1, 1, color, unit, top);
      drawPixel(ctx, x, y, 1, 1, color, unit, top);
      drawPixel(ctx, x + 1, y + 1, 1, 1, color, unit, top);
    } else if (expression === E.SURPRISED) {
      drawPixel(ctx, x, y, 2, 3, 0xfffff5e9, unit, top);
      drawPixel(ctx, x, y + 1, 1, 1, color, unit, top);
      drawPixel(ctx, x + 1, y, 1, 1, 0xffffffff, unit, top);
    } else if (expression === E.DIZZY) {
      drawPixel(ctx, x - 1, y, 1, 1, color, unit, top);
      drawPixel(ctx, x + 1, y, 1, 1, color, unit, top);
      drawPixel(ctx, x, y + 1, 1, 1, color, unit, top);
      drawPixel(ctx, x - 1, y + 2, 1, 1, color, unit, top);
      drawPixel(ctx, x + 1, y + 2, 1, 1, color, unit, top);
    } else if (expression === E.ANGRY) {
      drawPixel(ctx, x - 1, y, 3, 1, color, unit, top);
      drawPixel(ctx, x, y + 1, 1, 2, color, unit, top);
    } else if (expression === E.LOVE) {
      drawPixel(ctx, x - 1, y, 1, 1, 0xffff6485, unit, top);
      drawPixel(ctx, x + 1, y, 1, 1, 0xffff6485, unit, top);
      drawPixel(ctx, x, y + 1, 1, 1, 0xffff6485, unit, top);
      drawPixel(ctx, x, y + 2, 1, 1, 0xffff6485, unit, top);
    } else {
      drawPixel(ctx, x - 1, y, 3, 3, 0xfffff7ec, unit, top);
      drawPixel(ctx, x, y + 1, 1, 2, color, unit, top);
      drawPixel(ctx, x - 1, y, 1, 1, 0xffffffff, unit, top);
    }
  }

  function drawParticle(ctx, kind, x, y, cell, alpha) {
    const rows = kind === KIND.HEART ? SPRITE_HEART : kind === KIND.SPARK ? SPRITE_SPARK :
      kind === KIND.STAR ? SPRITE_STAR : kind === KIND.ZZZ ? SPRITE_ZZZ : SPRITE_NOTE;
    const color = kind === KIND.HEART ? 0xffff5c7a : kind === KIND.SPARK ? 0xffffc94d :
      kind === KIND.STAR ? 0xffffe07a : kind === KIND.ZZZ ? 0xff9eaee5 : 0xff64b5f6;
    ctx.globalAlpha = clamp(alpha, 0, 255) / 255;
    ctx.fillStyle = colorString(color);
    for (let row = 0; row < rows.length; row++) {
      for (let col = 0; col < rows[row].length; col++) {
        if (rows[row][col] === 'X') {
          ctx.fillRect(x + col * cell, y + row * cell, cell, cell);
        }
      }
    }
    ctx.globalAlpha = 1;
  }

  function drawParticles(ctx, p, unit, now, particles) {
    for (let i = 0; i < particles.length; i++) {
      const particle = particles[i];
      const elapsed = Math.max(0, now - particle.bornAt);
      const progress = clamp(elapsed / Math.max(1, particle.duration), 0, 1);
      const alpha = Math.floor(255 * (1 - progress));
      const cell = unit * 0.72;
      const x = particle.x + particle.driftX * progress;
      const y = particle.y - unit * (2.5 * progress);
      drawParticle(ctx, particle.kind, x, y, cell, alpha);
    }
  }

  function frameNameFor(frame) {
    if (frame.pcFrame) return frame.pcFrame;
    if (frame.pose === P.SLEEP || frame.pose === P.YAWN) return 'sleep';
    if (frame.pose === P.DANCE || frame.pose === P.SPIN) return 'dance';
    if (frame.expression === E.BLINK) return 'blink';
    if (frame.expression === E.HAPPY) return 'happy';
    if (frame.expression === E.SURPRISED) return 'surprised';
    if (frame.expression === E.DIZZY) return 'dizzy';
    if (frame.expression === E.ANGRY) return 'angry';
    if (frame.expression === E.LOVE) return 'love';
    return 'idle';
  }

  function poseFor(p, now) {
    if (p.state === S.WALK) return P.WALK;
    if (p.state === S.PRESS) return P.PRESS;
    if (p.state === S.DRAG) return P.DRAG;
    if (p.state === S.PET) return P.PET;
    if (p.state === S.FLY) return P.FLY;
    if (p.state === S.SLEEP) return P.SLEEP;
    if (p.state === S.HANG) return P.HANG;
    if (p.state === S.PEEK) return P.PEEK;
    if (app.mediaPlaying && p.settings.dance) return musicPose(now);
    if (now < p.idlePoseUntil) return p.idlePose;
    return P.IDLE;
  }

  function buildFrame(p, now) {
    const pose = poseFor(p, now);
    let expression = E.OPEN;
    if (p.state === S.SLEEP) {
      expression = E.SLEEP;
    } else if (now < p.expressionUntil) {
      expression = p.expression;
    } else if (now < p.blinkUntil && (p.state === S.IDLE || p.state === S.WALK || p.state === S.PEEK || p.state === S.HANG)) {
      expression = E.BLINK;
    }

    let waveSide = 0;
    if (pose === P.PEEK && p.peekOut && !p.peekMoving && Math.floor(now / 220) % 2 === 0) {
      waveSide = p.peekSide < 0 ? -1 : 1;
    }
    if (now < p.waveUntil && Math.floor(now / 240) % 2 === 0) {
      waveSide = Math.floor(now / 240) % 4 === 0 ? -1 : 1;
    }
    if (app.mediaPlaying && p.settings.dance && p.state === S.IDLE) {
      expression = E.HAPPY;
      waveSide = Math.floor(now / 240) % 2 === 0 ? -1 : 1;
    }

    let rotation = 0;
    if (p.state === S.FLY) {
      rotation = clamp(p.velocityX / dpFloat(70), -25, 25);
    } else if (p.state === S.HANG) {
      rotation = Math.sin(((now - p.hangStartedAt) / 1000) * 3) * 6;
    } else if (pose === P.SPIN && app.mediaPlaying && p.state === S.IDLE) {
      rotation = ((now % 2400) / 2400) * 360;
    } else if (pose === P.SPIN && now < p.idlePoseUntil) {
      rotation = ((Math.max(0, now - p.idlePoseStartedAt) % 900) / 900) * 360;
    } else if (p.slideVelocity !== 0) {
      rotation = clamp(-p.slideVelocity / dpFloat(30), -12, 12);
    } else if (p.expression === E.DIZZY && now < p.expressionUntil) {
      rotation = Math.sin(now / 100) * 7;
    } else if (app.mediaPlaying && p.state === S.IDLE) {
      rotation = Math.sin(now / 250) * 5;
    } else if (p.state === S.DRAG && p.touch) {
      rotation = clamp((p.touch.velocityX || 0) / dpFloat(90), -18, 18);
    }

    const squash = p.hopActive && p.hopVelocity < 0 ? -1 : (now < p.squashUntil ? 1 : 0);
    const pcFrame = p.pcFrame && (p.state === S.IDLE || p.state === S.WALK || p.state === S.SLEEP)
      ? p.pcFrame : '';
    return {
      pose: pose,
      expression: expression,
      pcFrame: pcFrame,
      look: clamp(p.look, -1, 1),
      waveSide: waveSide,
      blush: p.state === S.PET || expression === E.HAPPY || pose === P.DANCE,
      mouthOpen: now < p.mouthOpenUntil || (p.state === S.PET && Math.floor(now / 180) % 2 === 0),
      squash: squash,
      rotation: rotation,
      hopOffset: p.hopOffset,
      phase: now / 1000
    };
  }

  function drawPet(p, now) {
    const ctx = p.ctx;
    if (!ctx) return;
    const winW = p.winW;
    const winH = p.winH;
    if (winW <= 0 || winH <= 0) return;
    const unit = Math.min(winW / GRID_W, winH / (GRID_H + 2));
    if (unit <= 0) return;
    const topPad = unit * 2;
    const centerX = winW / 2;
    const centerY = topPad + unit * 6.5;
    const frame = buildFrame(p, now);
    const idleBreath = (frame.pose === P.IDLE || frame.pose === P.PET || frame.pose === P.SLEEP)
      ? Math.sin(frame.phase * (frame.pose === P.SLEEP ? 2.2 : 3.1)) * 0.34 * unit : 0;
    const danceBob = frame.pose === P.DANCE ? Math.abs(Math.sin(frame.phase * 10)) * unit * 0.8 : 0;
    const squashX = frame.pose === P.STRETCH ? 0.97 : (frame.squash > 0 ? 1.08 : (frame.squash < 0 ? 0.93 : 1));
    const squashY = frame.pose === P.STRETCH ? 1.06 : (frame.squash > 0 ? 0.91 : (frame.squash < 0 ? 1.08 : 1));

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, p.canvas.width, p.canvas.height);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.imageSmoothingEnabled = false;
    ctx.save();

    const rotateX = centerX;
    const rotateY = centerY + frame.hopOffset;
    if (frame.rotation) {
      ctx.translate(rotateX, rotateY);
      ctx.rotate(frame.rotation * Math.PI / 180);
      ctx.translate(-rotateX, -rotateY);
    }
    ctx.translate(0, frame.hopOffset - idleBreath - danceBob);
    ctx.translate(centerX, centerY);
    ctx.scale(squashX, squashY);
    ctx.translate(-centerX, -centerY);

    const entry = p.skinEntry;
    if (entry && entry.loaded && entry.images && entry.images.idle) {
      const image = entry.images[frameNameFor(frame)] || entry.images.idle;
      if (image) {
        ctx.drawImage(image, 0, topPad, GRID_W * unit, GRID_H * unit);
        drawParticles(ctx, p, unit, now, p.particles);
        ctx.restore();
        return;
      }
    }

    const pal = paletteFor(p);
    let species = p.settings.species || 'gupet';
    if (species === 'skin') species = 'gupet';
    const body = pal.body;
    const eye = pal.eye;
    const shade = pal.shade;
    const outline = pal.outline;
    const highlight = pal.highlight;
    const blush = pal.blush;
    const alternate = Math.floor(frame.phase * (species === 'dog' ? 13 : 5)) & 1;
    const tailLift = species === 'cat' ? (alternate === 0 ? 0 : 1) : species === 'dog' ? (alternate === 0 ? 1 : -1) : 0;

    if (species === 'cat') {
      drawPixel(ctx, 14, 8, 2, 1, outline, unit, topPad);
      drawPixel(ctx, 15, 7 - tailLift, 2, 1, shade, unit, topPad);
      drawPixel(ctx, 16, 5 - tailLift, 1, 3, body, unit, topPad);
      drawPixel(ctx, 15, 4 - tailLift, 1, 1, highlight, unit, topPad);
      drawPixel(ctx, 4, 2, 4, 4, outline, unit, topPad);
      drawPixel(ctx, 5, 1, 3, 4, body, unit, topPad);
      drawPixel(ctx, 6, 1, 1, 2, 0xffffb2bc, unit, topPad);
      drawPixel(ctx, 11, 1, 3, 4, body, unit, topPad);
      drawPixel(ctx, 11, 2, 4, 4, outline, unit, topPad);
      drawPixel(ctx, 12, 1, 1, 2, 0xffffb2bc, unit, topPad);
    } else if (species === 'dog') {
      drawPixel(ctx, 14, 6 + tailLift, 2, 2, outline, unit, topPad);
      drawPixel(ctx, 15, 5 + tailLift, 2, 2, body, unit, topPad);
      drawPixel(ctx, 16, 5 + tailLift, 1, 1, highlight, unit, topPad);
      drawPixel(ctx, 1, 3, 4, 4, outline, unit, topPad);
      drawPixel(ctx, 2, 3, 3, 4, shade, unit, topPad);
      drawPixel(ctx, 13, 3, 4, 4, outline, unit, topPad);
      drawPixel(ctx, 13, 3, 3, 4, shade, unit, topPad);
      drawPixel(ctx, 3, 4, 1, 2, highlight, unit, topPad);
    }

    drawPixel(ctx, 4, 12.5, 10, 0.5, 0x55352520, unit, topPad);

    const outlineExpansion = (frame.pose === P.PET || frame.pose === P.STRETCH) ? 1 : 0;
    const activeLeft = species === 'gupet' ? GUPET_BODY_LEFT : BODY_LEFT;
    const activeRight = species === 'gupet' ? GUPET_BODY_RIGHT : BODY_RIGHT;
    const bodyStartY = species === 'gupet' ? 3 : 2;
    for (let row = 0; row < activeLeft.length; row++) {
      const y = row + bodyStartY;
      const left = activeLeft[row] - outlineExpansion;
      const right = activeRight[row] + outlineExpansion;
      drawPixel(ctx, left - 1, y, right - left + 2, 1, outline, unit, topPad);
      drawPixel(ctx, left, y, right - left, 1, body, unit, topPad);
      if (row >= 2 && row <= 7) drawPixel(ctx, right - 2, y, 1, 1, shade, unit, topPad);
      if (row >= 1 && row <= 4) drawPixel(ctx, left + 1, y, 1, 1, highlight, unit, topPad);
    }

    const walk = frame.pose === P.WALK || frame.pose === P.PEEK || frame.pose === P.DRAG;
    const step = Math.floor(frame.phase * 8) & 1;
    const activeLegs = species === 'gupet' ? GUPET_LEG_X : LEG_X;
    const legY = species === 'gupet' ? 10 : 11;
    for (let i = 0; i < activeLegs.length; i++) {
      const footHeight = frame.pose === P.SLEEP ? 1 : (walk && ((i + step) & 1) === 0 ? 2 : 1);
      drawPixel(ctx, activeLegs[i], legY, 2, footHeight, outline, unit, topPad);
      drawPixel(ctx, activeLegs[i], legY, 1, footHeight, body, unit, topPad);
    }

    const armsUp = frame.pose === P.DRAG || frame.pose === P.FLY || frame.pose === P.HANG ||
      frame.pose === P.STRETCH || frame.pose === P.DANCE;
    const groomLeft = frame.pose === P.GROOM && alternate === 0;
    const groomRight = frame.pose === P.GROOM && alternate !== 0;
    drawArm(ctx, -1, armsUp || frame.waveSide < 0 || groomLeft, body, outline, highlight, unit, topPad);
    drawArm(ctx, 1, armsUp || frame.waveSide > 0 || groomRight, body, outline, highlight, unit, topPad);

    const look = clamp(frame.look, -1, 1);
    const leftEye = (species === 'gupet' ? 6 : 5) + look;
    const rightEye = 11 + look;
    const eyeY = 5;
    const faceExpression = (frame.pose === P.SLEEP || frame.pose === P.YAWN) ? E.SLEEP : frame.expression;
    drawEye(ctx, leftEye, eyeY, eye, outline, faceExpression, unit, topPad, species);
    drawEye(ctx, rightEye, eyeY, eye, outline, faceExpression, unit, topPad, species);

    if (frame.blush || frame.pose === P.PET || frame.expression === E.LOVE) {
      drawPixel(ctx, 4, 7, 2, 1, blush, unit, topPad);
      drawPixel(ctx, 13, 7, 2, 1, blush, unit, topPad);
    }

    if (species === 'dog') {
      drawPixel(ctx, 7, 7, 4, 2, 0xffffe2be, unit, topPad);
      drawPixel(ctx, 8, 7, 2, 1, outline, unit, topPad);
      if (frame.mouthOpen || frame.pose === P.YAWN) drawPixel(ctx, 8, 8, 2, 1, 0xffea6688, unit, topPad);
    } else if (species === 'cat') {
      drawPixel(ctx, 8, 7, 1, 1, 0xffffa9b7, unit, topPad);
      drawPixel(ctx, 7, 8, 2, 1, outline, unit, topPad);
      if (frame.mouthOpen || frame.pose === P.YAWN) drawPixel(ctx, 8, 8, 1, 1, 0xffea6688, unit, topPad);
    } else if (frame.mouthOpen || frame.pose === P.YAWN) {
      drawPixel(ctx, 8, 7, 2, 2, outline, unit, topPad);
      drawPixel(ctx, 8, 8, 2, 1, 0xffea6688, unit, topPad);
    } else {
      drawPixel(ctx, 8, 7, 1, 1, outline, unit, topPad);
      drawPixel(ctx, 9, 8, 1, 1, outline, unit, topPad);
    }

    drawParticles(ctx, p, unit, now, p.particles);
    ctx.restore();
  }

  function musicPose(now) {
    const phase = Math.floor(now / 700) % 10;
    if (phase === 4) return P.STRETCH;
    if (phase === 7) return P.SPIN;
    if (phase === 9) return P.GROOM;
    return P.DANCE;
  }

  function nextPoopDelay(p) {
    return Math.floor(randFloat(150_000, 330_000) * clamp(p.settings.poopInterval, 0.25, 4));
  }

  function makePet(skinId, index) {
    const el = document.createElement('div');
    el.className = 'pet';
    el.style.opacity = '0';
    el.style.transition = 'opacity 200ms ease';
    const canvas = document.createElement('canvas');
    el.appendChild(canvas);
    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.style.display = 'none';
    root.appendChild(el);
    root.appendChild(bubble);
    const p = {
      id: skinId,
      skinId: skinId,
      index: index,
      el: el,
      canvas: canvas,
      ctx: canvas.getContext('2d', { alpha: true }),
      bubble: bubble,
      bubbleVisible: false,
      bubbleUntil: 0,
      bubbleFadeIn: false,
      bubbleToken: 0,
      settings: Object.assign({}, DEFAULTS),
      pcSettings: Object.assign({}, DEFAULT_PC),
      pcFrame: '',
      pcHidden: false,
      pcMoveAt: 0,
      pcWalkSpeed: 0,
      pcWalkTarget: 0,
      pcWalkUntil: 0,
      skin: null,
      skinEntry: null,
      unit: 0,
      winW: 0,
      winH: 0,
      x: 0,
      y: 0,
      state: S.IDLE,
      touch: null,
      pressTimer: null,
      lastInteractionAt: nowMs(),
      nextActionAt: nowMs() + 3500,
      idlePose: P.IDLE,
      idlePoseStartedAt: 0,
      idlePoseUntil: 0,
      nextBlinkAt: nowMs() + 2000,
      blinkUntil: 0,
      nextHeartAt: nowMs(),
      nextZAt: nowMs() + 700,
      nextNoteAt: nowMs() + 400,
      nextPoopAt: nowMs() + 150_000,
      nextPeekAt: nowMs() + PEEK_AFTER_MS,
      waveUntil: 0,
      expression: E.OPEN,
      expressionUntil: 0,
      squashUntil: 0,
      mouthOpenUntil: 0,
      hopActive: false,
      hopOffset: 0,
      hopVelocity: 0,
      hopCooldownUntil: 0,
      hopBlockedUntil: 0,
      hopTimes: [],
      velocityX: 0,
      velocityY: 0,
      slideVelocity: 0,
      look: 0,
      peekSide: 0,
      peekPending: 0,
      peekOut: false,
      peekMoving: false,
      peekPhaseUntil: 0,
      peekEndAt: 0,
      hangStartedAt: 0,
      hangUntil: 0,
      hiddenUntilTap: false,
      positionReady: false,
      particles: [],
      lastTapAt: 0,
      recentTaps: [],
      lastPoopReactAt: 0,
      lastPetReactAt: 0,
      wallBounceAt: 0,
      nextExternalReactionAt: 0,
      lastPhrase: '',
      needsHello: false,
      frameTime: 0
    };
    el.addEventListener('pointerdown', function (event) { onPointerDown(p, event); });
    el.addEventListener('contextmenu', function (event) {
      event.preventDefault();
      event.stopPropagation();
      if (!app.running) return;
      invoke('show_settings').catch(function (err) { console.error('show_settings failed', err); });
    });
    return p;
  }

  function maxXFor(p) {
    return Math.max(0, screenW - p.winW);
  }

  function maxYFor(p) {
    return Math.max(0, screenH - p.winH);
  }

  function minXFor() { return 0; }
  function minYFor() { return 0; }

  function updatePetWindowSize(p) {
    const oldW = p.winW;
    const oldH = p.winH;
    p.unit = unitForSizeDp(p.settings.sizeDp);
    p.winW = Math.max(1, Math.floor(p.unit * GRID_W));
    p.winH = Math.max(1, Math.floor(p.unit * (GRID_H + 4)));
    if (oldW > 0 && oldW !== p.winW) {
      p.x += (oldW - p.winW) / 2;
    }
    if (oldH > 0 && oldH !== p.winH) {
      p.y += (oldH - p.winH) / 2;
    }
    p.x = clamp(p.x, minXFor(p), maxXFor(p));
    p.y = clamp(p.y, minYFor(p), maxYFor(p));
  }

  function sizePetCanvas(p) {
    const width = Math.max(1, Math.round(p.winW * dpr));
    const height = Math.max(1, Math.round(p.winH * dpr));
    if (p.canvas.width !== width || p.canvas.height !== height) {
      p.canvas.width = width;
      p.canvas.height = height;
    }
    p.el.style.width = p.winW + 'px';
    p.el.style.height = p.winH + 'px';
    p.canvas.style.width = '100%';
    p.canvas.style.height = '100%';
  }

  function updatePetLayout(p) {
    p.el.style.transform = 'translate3d(' + Math.round(p.x) + 'px,' + Math.round(p.y) + 'px,0)';
  }

  function setPosition(p, x, y, allowOffscreenX) {
    p.x = allowOffscreenX ? x : clamp(x, minXFor(p), maxXFor(p));
    p.y = clamp(y, minYFor(p) - dpInt(3), maxYFor(p));
    updatePetLayout(p);
  }

  function restorePosition(p, index) {
    const s = p.settings;
    if (index === 0 && s.persistPosition && typeof s.lastPosX === 'number' && typeof s.lastPosY === 'number' &&
      s.lastPosX >= 0 && s.lastPosY >= 0) {
      p.x = minXFor(p) + (maxXFor(p) - minXFor(p)) * clamp(s.lastPosX, 0, 1);
      p.y = minYFor(p) + (maxYFor(p) - minYFor(p)) * clamp(s.lastPosY, 0, 1);
      p.positionReady = true;
      return;
    }
    const fx = 0.78;
    const fy = 0.72;
    const baseX = minXFor(p) + (maxXFor(p) - minXFor(p)) * fx;
    p.y = clamp(minYFor(p) + (maxYFor(p) - minYFor(p)) * fy, minYFor(p), maxYFor(p));
    const step = p.winW * 0.55;
    let shift = index % 4;
    const near = function (x) {
      return app.pets.some(function (other) {
        return other !== p && Math.abs(other.x - x) < p.winW * 0.35 && Math.abs(other.y - p.y) < p.winH * 0.35;
      });
    };
    let x = baseX + shift * step;
    while (near(x) && shift < 8) {
      shift += 1;
      x = baseX + shift * step;
    }
    if (x > maxXFor(p)) {
      shift = Math.max(1, shift);
      while (shift < 12) {
        x = baseX - shift * step;
        if (!near(x)) break;
        shift += 1;
      }
    }
    p.x = clamp(x, minXFor(p), maxXFor(p));
    p.positionReady = true;
  }

  function savePositionNow() {
    if (!app.pets.length) return;
    const p = app.pets[0];
    if (!p.settings.persistPosition) return;
    const maxX = maxXFor(p);
    const maxY = maxYFor(p);
    const fx = maxX > 0 ? clamp((p.x - minXFor(p)) / maxX, 0, 1) : 0.5;
    const fy = maxY > 0 ? clamp((p.y - minYFor(p)) / maxY, 0, 1) : 0.6;
    app.settings.lastPosX = fx;
    app.settings.lastPosY = fy;
    invoke('set_last_position', { x: fx, y: fy }).catch(function (err) {
      console.error('set_last_position failed', err);
    });
  }

  function savePosition(p) {
    if (app.pets[0] !== p) return;
    savePositionNow();
  }

  function resetPetRuntime(p) {
    const now = nowMs();
    p.state = S.IDLE;
    p.touch = null;
    clearPressTimer(p);
    p.lastInteractionAt = now;
    p.nextActionAt = now + 3500;
    p.idlePose = P.IDLE;
    p.idlePoseStartedAt = 0;
    p.idlePoseUntil = 0;
    p.nextBlinkAt = now + 2000;
    p.blinkUntil = 0;
    p.nextHeartAt = now;
    p.nextZAt = now + 700;
    p.nextNoteAt = now + 400;
    p.nextPoopAt = now + nextPoopDelay(p);
    p.nextPeekAt = now + PEEK_AFTER_MS;
    p.waveUntil = 0;
    p.expression = E.OPEN;
    p.expressionUntil = 0;
    p.squashUntil = 0;
    p.mouthOpenUntil = 0;
    p.hopActive = false;
    p.hopOffset = 0;
    p.hopVelocity = 0;
    p.velocityX = 0;
    p.velocityY = 0;
    p.slideVelocity = 0;
    p.look = 0;
    p.peekSide = 0;
    p.peekPending = 0;
    p.peekOut = false;
    p.peekMoving = false;
    p.hiddenUntilTap = false;
    p.lastTapAt = 0;
    p.recentTaps = [];
    p.particles = [];
    p.nextExternalReactionAt = 0;
    p.lastPetReactAt = 0;
    p.lastPoopReactAt = 0;
    p.wallBounceAt = 0;
  }

  function applySettingsToPet(p, skin, index, isNew) {
    p.skin = skin || { id: p.skinId, name: p.id, revision: 0, settings: null, pcSettings: null };
    p.pcSettings = normalizePcSettings(p.skin.pcSettings);
    p.pcFrame = '';
    p.pcHidden = false;
    p.pcMoveAt = 0;
    p.pcWalkSpeed = 0;
    p.pcWalkTarget = 0;
    p.pcWalkUntil = 0;
    const skinSettings = (p.skin && p.skin.settings && typeof p.skin.settings === 'object') ? p.skin.settings : {};
    p.skinPhrases = Array.isArray(skinSettings.phrases) && skinSettings.phrases.length ? skinSettings.phrases : null;
    const mergedSkinSettings = Object.assign({}, skinSettings);
    if (Array.isArray(mergedSkinSettings.phrases)) delete mergedSkinSettings.phrases;
    p.settings = Object.assign({}, DEFAULTS, app.settings, mergedSkinSettings);
    p.settings.phrases = typeof mergedSkinSettings.phrases === 'boolean'
      ? mergedSkinSettings.phrases
      : app.settings.phrases !== false;
    p.settings.activeSkinIds = app.activeIds.slice();
    updatePetWindowSize(p);
    sizePetCanvas(p);
    if (isNew) {
      resetPetRuntime(p);
      restorePosition(p, index);
    } else {
      p.x = clamp(p.x, minXFor(p), maxXFor(p));
      p.y = clamp(p.y, minYFor(p), maxYFor(p));
    }
    if (!p.settings.phrases) hideBubble(p, false);
    if (isNew && p.settings.hideOnAppOpen) {
      const side = (p.x - minXFor(p) < maxXFor(p) - p.x) ? -1 : 1;
      startPeek(p, nowMs(), side);
      p.peekEndAt = Number.POSITIVE_INFINITY;
      p.hiddenUntilTap = true;
    }
    updatePetLayout(p);
    ensureSkinFrames(p.skin, p);
  }

  async function reloadNow(initial) {
    let raw;
    try {
      raw = await invoke('get_runtime_state');
    } catch (err) {
      showFatal('Не удалось загрузить настройки: ' + errorText(err));
      return;
    }
    const wasRunning = app.running;
    app.running = !!raw.running;
    app.settingsVisible = !!raw.settingsVisible;
    app.poopsCleaned = Number(raw.poopsCleaned) || app.poopsCleaned || 0;
    app.settings = Object.assign({}, DEFAULTS, raw.settings || {});
    app.phrases = Array.isArray(raw.phrases) ? raw.phrases : [];
    app.activeIds = Array.isArray(raw.activeIds) && raw.activeIds.length ? raw.activeIds.slice() : ['builtin'];
    app.skins = new Map();
    for (const skin of (raw.skins || [])) app.skins.set(skin.id, skin);

    if (wasRunning && !app.running) {
      cancelActiveTouch(true);
      for (const p of app.pets) hideBubble(p, false);
      clearPoops(false);
    }
    if (!wasRunning && app.running) {
      const now = nowMs();
      for (const p of app.pets) p.lastInteractionAt = now;
    }

    const keep = new Set(app.activeIds);
    for (const p of app.pets.slice()) {
      if (!keep.has(p.skinId)) removePet(p);
    }

    app.activeIds.forEach(function (id, index) {
      let p = app.pets.find(function (candidate) { return candidate.skinId === id; });
      const skin = app.skins.get(id) || { id: id, name: id, revision: 0, settings: null };
      const isNew = !p;
      if (!p) {
        p = makePet(id, index);
        app.pets.push(p);
      }
      p.index = index;
      applySettingsToPet(p, skin, index, isNew);
      if (isNew) {
        p.nextPoopAt = nowMs() + nextPoopDelay(p) + index * 20_000;
        p.lastInteractionAt = nowMs();
        if (index === 0) p.needsHello = true;
        requestAnimationFrame(function () {
          p.el.style.opacity = '1';
        });
      }
    });

    pruneSkinCache();
    for (const p of app.pets) {
      if (p.needsHello && app.running && p.settings.phrases) {
        p.needsHello = false;
        say(p, 'hello');
      }
    }
    applyMusicState(raw.music);

    if (!app.loaded) {
      app.loaded = true;
      writeStatus();
    }
    pollOpenApps();
    if (initial) console.log('[gupet-overlay] loaded', app.pets.length, 'pet(s)');
  }

  function scheduleReload(force) {
    if (app.reloadInFlight) {
      app.reloadQueued = true;
      return;
    }
    if (app.reloadTimer) clearTimeout(app.reloadTimer);
    app.reloadTimer = setTimeout(function () {
      app.reloadTimer = null;
      app.reloadInFlight = reloadNow(false).catch(function (err) {
        console.error('reload failed', err);
      }).finally(function () {
        app.reloadInFlight = null;
        if (app.reloadQueued) {
          app.reloadQueued = false;
          scheduleReload(false);
        }
      });
    }, force ? 0 : 90);
  }

  function removePet(p) {
    cancelActiveTouch(false, p);
    clearPressTimer(p);
    hideBubble(p, false);
    if (p.bubble && p.bubble.parentNode) p.bubble.parentNode.removeChild(p.bubble);
    if (p.el && p.el.parentNode) p.el.parentNode.removeChild(p.el);
    const index = app.pets.indexOf(p);
    if (index >= 0) app.pets.splice(index, 1);
  }

  function applyRuntimeSkinEntry(id, entry) {
    for (const p of app.pets) {
      if (p.skinId === id) p.skinEntry = entry;
    }
  }

  function releaseSkinEntry(entry) {
    if (!entry) return;
    entry.loaded = false;
    entry.poopImage = null;
    entry.images = {};
    entry.promise = null;
  }

  function pruneSkinCache() {
    const keep = new Set(app.activeIds);
    for (const [id, entry] of app.skinCache) {
      if (!keep.has(id)) {
        releaseSkinEntry(entry);
        app.skinCache.delete(id);
      }
    }
  }

  function ensureSkinFrames(skin, pet) {
    if (!skin || skin.id === 'builtin') {
      if (pet) pet.skinEntry = null;
      return Promise.resolve(null);
    }
    const revision = Number(skin.revision) || 0;
    let entry = app.skinCache.get(skin.id);
    if (entry && entry.revision === revision && entry.promise) {
      if (pet) pet.skinEntry = entry;
      return entry.promise;
    }
    entry = {
      id: skin.id,
      revision: revision,
      images: {},
      poopImage: null,
      loaded: false,
      promise: null
    };
    app.skinCache.set(skin.id, entry);
    if (pet) pet.skinEntry = entry;
    entry.promise = invoke('load_skin_frames', { id: skin.id }).then(async function (data) {
      const entries = Object.entries((data && data.frames) || {});
      const loaded = await Promise.all(entries.map(function (pair) {
        return loadImage(pair[1]).then(function (image) { return [pair[0], image]; });
      }));
      for (const pair of loaded) {
        if (pair[1]) entry.images[pair[0]] = pair[1];
      }
      if (data && data.poop) entry.poopImage = await loadImage(data.poop);
      entry.loaded = !!entry.images.idle;
      applyRuntimeSkinEntry(skin.id, entry);
      return entry;
    }).catch(function (err) {
      console.error('load_skin_frames failed', skin.id, err);
      entry.loaded = false;
      applyRuntimeSkinEntry(skin.id, entry);
      return entry;
    });
    return entry.promise;
  }

  function loadImage(src) {
    return new Promise(function (resolve) {
      if (!src) { resolve(null); return; }
      const image = new Image();
      image.onload = function () {
        image.onload = null;
        image.onerror = null;
        resolve(image);
      };
      image.onerror = function () {
        image.onload = null;
        image.onerror = null;
        image.src = '';
        resolve(null);
      };
      image.src = src;
    });
  }

  function errorText(err) {
    return typeof err === 'string' ? err : (err && err.message ? err.message : String(err));
  }

  function setExpression(p, expression, durationMs) {
    p.expression = expression;
    p.expressionUntil = nowMs() + durationMs;
  }

  function playIdlePose(p, pose, durationMs, phrase, now) {
    p.idlePose = pose;
    p.idlePoseStartedAt = now;
    p.idlePoseUntil = now + durationMs;
    p.nextActionAt = Math.max(p.nextActionAt, now + durationMs + randInt(1500, 3000));
    say(p, phrase);
  }

  function hop(p, strengthPx) {
    if (p.state !== S.IDLE && p.state !== S.WALK) return;
    const now = nowMs();
    if (now < p.hopCooldownUntil || now < p.hopBlockedUntil) return;
    p.hopTimes.push(now);
    while (p.hopTimes.length && now - p.hopTimes[0] > 3000) p.hopTimes.shift();
    if (p.hopTimes.length >= 3) {
      p.hopBlockedUntil = now + 5000;
      p.hopTimes = [];
      return;
    }
    const unit = Math.max(1, p.unit);
    const headroom = 2.5 * unit;
    const room = Math.max(0, p.y + 2 * unit);
    const maxStrength = Math.min(
      Math.sqrt(2 * dpFloat(2500) * headroom),
      Math.sqrt(2 * dpFloat(2500) * room)
    );
    p.hopActive = true;
    p.hopVelocity = -Math.min(strengthPx, maxStrength);
    p.hopOffset = 0;
  }

  function spawnParticle(p, kind, originX, originY) {
    if (!p.el || p.pcHidden) return;
    while (p.particles.length >= MAX_PARTICLES) p.particles.shift();
    const now = nowMs();
    const width = p.winW;
    const cell = p.unit * 0.72;
    const x = typeof originX === 'number' ? originX : (width / 2 - cell * 3 + Math.random() * cell * 5);
    const y = typeof originY === 'number' ? originY : (kind === KIND.ZZZ ? p.unit * 4.2 : p.unit * 3.1);
    p.particles.push({
      kind: kind,
      x: x,
      y: y,
      driftX: (Math.random() - 0.5) * dpFloat(32),
      bornAt: now,
      duration: kind === KIND.ZZZ ? 1600 : 1100
    });
  }

  function setMediaPlaying(playing) {
    playing = !!playing;
    if (app.mediaPlaying === playing) return;
    if (playing && app.pets.length && app.pets.every(function (p) { return !p.settings.dance; })) return;
    app.mediaPlaying = playing;
    const now = nowMs();
    if (playing) {
      for (const p of app.pets) {
        p.lastInteractionAt = now;
        p.nextNoteAt = now + 250;
        p.idlePoseUntil = 0;
        p.idlePose = P.IDLE;
        if (p.state === S.SLEEP) p.state = S.IDLE;
        if (p.state === S.PEEK) endPeek(p, now);
        say(p, 'music');
      }
    } else {
      for (const p of app.pets) {
        p.idlePoseUntil = 0;
        p.idlePose = P.IDLE;
        p.nextActionAt = now + randInt(2000, 4000);
        if (chance(0.7)) say(p, 'music_end');
      }
    }
  }

  function phrasePool(p, category) {
    const source = Array.isArray(p.skinPhrases) && p.skinPhrases.length ? p.skinPhrases : app.phrases;
    let pool = source.filter(function (phrase) {
      return phrase && phrase.enabled !== false && phrase.context === category && phrase.text;
    });
    if (!pool.length) {
      pool = source.filter(function (phrase) {
        return phrase && phrase.enabled !== false && phrase.context === 'idle' && phrase.text;
      });
    }
    return pool;
  }

  function say(p, category, force) {
    if (p.pcHidden) {
      hideBubble(p, false);
      return;
    }
    if (!force && !p.settings.phrases) return;
    let pool = phrasePool(p, category);
    if (!pool.length) return;
    let pick = pool[pool.length - 1];
    if (pool.length > 1) {
      let total = 0;
      for (const item of pool) total += clamp(Number(item.weight) || 1, 1, 10);
      let roll = Math.random() * Math.max(1, total);
      for (const item of pool) {
        roll -= clamp(Number(item.weight) || 1, 1, 10);
        if (roll < 0) { pick = item; break; }
      }
      if (pick.text === p.lastPhrase) pick = pool[(pool.indexOf(pick) + 1) % pool.length];
    }
    p.lastPhrase = pick.text;
    showBubbleText(p, pick.text);
  }

  function styleBubble(p) {
    const s = p.settings;
    const b = p.bubble;
    const bg = parseColor(s.phraseBgColor, 0xff29262e);
    const alpha = clamp(Number(s.phraseBgAlpha) || 0, 0, 1);
    b.style.fontSize = clamp(Number(s.phraseFontSize) || 14, 8, 48) + 'px';
    b.style.color = colorString(parseColor(s.phraseColor, 0xfff4f1f8));
    b.style.background = colorString((Math.round(alpha * 255) << 24) | (bg & 0x00ffffff));
    const outlineWidth = clamp(Number(s.phraseOutlineWidth) || 0, 0, 12);
    b.style.border = outlineWidth > 0
      ? outlineWidth + 'px solid ' + colorString(parseColor(s.phraseOutlineColor, 0xffff4d30))
      : 'none';
  }

  function positionBubble(p) {
    const b = p.bubble;
    if (!b || !p.bubbleVisible) return;
    styleBubble(p);
    const width = b.offsetWidth;
    const height = b.offsetHeight;
    if (width <= 0 || height <= 0) return;
    const petX = Math.round(p.x);
    const petY = Math.round(p.y) + (p.hopActive ? Math.round(p.hopOffset) : 0);
    const anchorX = petX + p.winW / 2;
    const anchorY = petY + p.unit * 2;
    let x = anchorX - width / 2;
    x = clamp(x, 0, Math.max(0, screenW - width));
    let y = anchorY - height - Math.max(4, p.unit * 0.5);
    if (y < 0) y = petY + p.winH + Math.max(4, p.unit * 0.5);
    y = clamp(y, 0, Math.max(0, screenH - height));
    b.style.left = Math.round(x) + 'px';
    b.style.top = Math.round(y) + 'px';
  }

  function showBubbleText(p, text) {
    if (!p.bubble || !text) return;
    if (p.pcHidden) {
      hideBubble(p, false);
      return;
    }
    const b = p.bubble;
    const now = nowMs();
    if (b._hideTimer) {
      clearTimeout(b._hideTimer);
      b._hideTimer = null;
    }
    p.bubbleToken++;
    const token = p.bubbleToken;
    styleBubble(p);
    b.textContent = String(text);
    b.style.transition = 'none';
    b.style.transform = 'translate(0,0) scale(1)';
    b.style.opacity = '0';
    b.style.visibility = 'visible';
    b.style.display = 'block';
    p.bubbleVisible = true;
    p.bubbleUntil = now + clamp(Number(p.settings.phraseDurationMs) || 3000, 300, 60_000);
    p.bubbleFadeIn = true;
    b.style.left = '0px';
    b.style.top = '0px';
    positionBubble(p);
    requestAnimationFrame(function () {
      if (!p.bubbleVisible || p.bubbleToken !== token) return;
      b.style.transition = 'opacity 150ms ease';
      b.style.opacity = '1';
      p.bubbleFadeIn = false;
    });
  }

  function hideBubble(p, animated) {
    const b = p.bubble;
    if (!b || !p.bubbleVisible) return;
    p.bubbleVisible = false;
    p.bubbleFadeIn = false;
    p.bubbleToken++;
    const token = p.bubbleToken;
    if (b._hideTimer) {
      clearTimeout(b._hideTimer);
      b._hideTimer = null;
    }
    const fadeMs = clamp(Number(p.settings.phraseFadeMs) || 0, 0, 3000);
    if (!animated || fadeMs <= 0 || p.settings.phraseFadeStyle === 'none') {
      b.style.transition = 'none';
      b.style.display = 'none';
      b.style.visibility = 'hidden';
      return;
    }
    b.style.transition = 'opacity ' + fadeMs + 'ms ease, transform ' + fadeMs + 'ms ease';
    b.style.opacity = '0';
    if (p.settings.phraseFadeStyle === 'slideUp') {
      b.style.transform = 'translate(0,' + (-dpInt(16)) + 'px)';
    } else if (p.settings.phraseFadeStyle === 'scale') {
      b.style.transform = 'scale(1.15)';
    }
    b._hideTimer = setTimeout(function () {
      if (p.bubbleToken !== token) return;
      b.style.display = 'none';
      b.style.visibility = 'hidden';
      b._hideTimer = null;
    }, fadeMs + 30);
  }

  function updateBubble(p, now) {
    if (p.pcHidden) {
      hideBubble(p, false);
      return;
    }
    if (!p.bubbleVisible) return;
    if (now >= p.bubbleUntil) {
      hideBubble(p, true);
      return;
    }
    positionBubble(p);
  }

  function nearestPetTo(x, y) {
    let best = null;
    let bestDistance = Infinity;
    for (const p of app.pets) {
      const dx = p.x + p.winW / 2 - x;
      const dy = p.y + p.winH / 2 - y;
      const distance = dx * dx + dy * dy;
      if (distance < bestDistance) {
        bestDistance = distance;
        best = p;
      }
    }
    return best || app.pets[0] || null;
  }

  function drawPoopCanvas(canvas, width, height, p) {
    const ctx = canvas.getContext('2d');
    const entry = p.skinEntry;
    if (entry && entry.poopImage) {
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.imageSmoothingEnabled = false;
      ctx.clearRect(0, 0, width, height);
      ctx.drawImage(entry.poopImage, 0, 0, width, height);
      return;
    }
    const base = (parseColor(p.settings.poopColor, 0xff8a5a33) | 0xff000000) >>> 0;
    const light = mixColors(base, 0xffffffff, 0.35);
    const cell = Math.min(width / 9, height / 7);
    const ox = (width - cell * 9) / 2;
    const oy = (height - cell * 7) / 2;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);
    for (let row = 0; row < POOP_PATTERN.length; row++) {
      for (let col = 0; col < POOP_PATTERN[row].length; col++) {
        const ch = POOP_PATTERN[row][col];
        let color = null;
        if (ch === 'X') color = base;
        else if (ch === 'o') color = light;
        else if (ch === 'w') color = 0xffffffff;
        else if (ch === 'k') color = 0xff1a1a1a;
        if (color !== null) {
          ctx.fillStyle = colorString(color);
          ctx.fillRect(ox + col * cell, oy + row * cell, cell, cell);
        }
      }
    }
  }

  function poopNow(p, manual) {
    if (p.pcHidden) return;
    if ((!manual && !p.settings.poop) || !p.el) return;
    const now = nowMs();
    p.nextPoopAt = now + nextPoopDelay(p);
    const maxPoops = Math.max(1, Number(app.settings.maxPoops) || 3);
    if (app.poops.length >= maxPoops) {
      if (!manual) return;
      cleanPoop(app.poops[0], true, false);
    }
    const poopUnit = Math.max(dpInt(3), p.unit * 0.9) * clamp(Number(p.settings.poopSize) || 1, 0.5, 3);
    const width = Math.max(1, Math.floor(poopUnit * 9));
    const height = Math.max(1, Math.floor(poopUnit * 7));
    const side = p.look === 0 ? (chance(0.5) ? -1 : 1) : -p.look;
    const centerX = p.x + p.winW / 2 + side * p.winW * 0.42;
    const x = clamp(centerX - width / 2, 0, Math.max(0, screenW - width));
    const y = clamp(p.y + p.winH - height, 0, Math.max(0, screenH - height));

    const el = document.createElement('div');
    el.className = 'poop';
    el.dataset.ownerId = p.id;
    el.style.width = width + 'px';
    el.style.height = height + 'px';
    el.style.left = Math.round(x) + 'px';
    el.style.top = Math.round(y) + 'px';
    const canvas = document.createElement('canvas');
    canvas.style.width = '100%';
    canvas.style.height = '100%';
    canvas.width = Math.max(1, Math.round(width * dpr));
    canvas.height = Math.max(1, Math.round(height * dpr));
    drawPoopCanvas(canvas, width, height, p);
    el.appendChild(canvas);
    root.appendChild(el);
    const entry = {
      el: el,
      canvas: canvas,
      ownerId: p.id,
      x: x,
      y: y,
      width: width,
      height: height,
      bornAt: now,
      removing: false
    };
    el.addEventListener('pointerdown', function (event) {
      event.preventDefault();
      event.stopPropagation();
    });
    el.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      cleanPoop(entry, true, true);
    });
    app.poops.push(entry);
    updateInputRegion();
    requestAnimationFrame(function () {
      if (!entry.removing) el.style.transform = 'scale(1)';
    });
    spawnParticle(p, KIND.SPARK);
    spawnParticle(p, KIND.SPARK);
    p.squashUntil = now + 180;
    setExpression(p, E.HAPPY, 1700);
    say(p, 'poop');
    p.look = -side;
    if (p.settings.wander && p.state === S.IDLE) {
      const target = clamp(p.x - side * dpFloat(randInt(40, 80)), minXFor(p), maxXFor(p));
      if (Math.abs(target - p.x) > dpFloat(12)) {
        p.walkTarget = target;
        p.state = S.WALK;
      }
    }
    p.nextActionAt = now + randInt(3000, 6000);
  }

  function cleanPoop(entry, animated, count) {
    if (!entry || entry.removing) return;
    const index = app.poops.indexOf(entry);
    if (index < 0) return;
    entry.removing = true;
    app.poops.splice(index, 1);
    if (count) {
      app.poopsCleaned += 1;
      invoke('record_poop_cleaned').catch(function (err) {
        console.error('record_poop_cleaned failed', err);
      });
      const near = nearestPetTo(entry.x + entry.width / 2, entry.y + entry.height / 2);
      if (near) {
        for (let i = 0; i < 3; i++) spawnParticle(near, KIND.SPARK);
        if (near.settings.phrases && chance(0.7)) say(near, 'clean');
      }
    }
    updateInputRegion();
    const near = nearestPetTo(entry.x + entry.width / 2, entry.y + entry.height / 2);
    const fadeMs = clamp(Number(near ? near.settings.poopFadeMs : app.settings.poopFadeMs) || 0, 0, 3000);
    if (animated && fadeMs > 0) {
      entry.el.classList.add('removing');
      entry.el.style.transition = 'transform ' + fadeMs + 'ms ease, opacity ' + fadeMs + 'ms ease';
      entry.el.style.opacity = '0';
      setTimeout(function () {
        if (entry.el.parentNode) entry.el.parentNode.removeChild(entry.el);
      }, fadeMs + 40);
    } else if (entry.el.parentNode) {
      entry.el.parentNode.removeChild(entry.el);
    }
  }

  function clearPoops(animated) {
    for (const entry of app.poops.slice()) cleanPoop(entry, animated, false);
  }

  function clearPetPoops(p) {
    if (!p) return;
    for (const entry of app.poops.slice()) {
      if (entry.ownerId === p.id) cleanPoop(entry, false, false);
    }
    // Куски, которые уже находятся в анимации исчезновения, больше не
    // лежат в app.poops, но их DOM ещё виден. Убираем и их.
    const stale = root.querySelectorAll('.poop');
    for (let i = 0; i < stale.length; i++) {
      const el = stale[i];
      if (el.dataset.ownerId === p.id && el.parentNode) {
        el.parentNode.removeChild(el);
      }
    }
    updateInputRegion();
  }

  function poopOwnerHidden(entry) {
    if (!entry || !entry.ownerId) return false;
    return app.pets.some(function (p) {
      return p.id === entry.ownerId && p.pcHidden;
    });
  }

  function petTouchesPoop(p) {
    if (!p.el || !app.poops.length) return false;
    const ax1 = p.x + p.winW;
    const ay1 = p.y + p.winH;
    for (const entry of app.poops) {
      if (poopOwnerHidden(entry)) continue;
      if (p.x < entry.x + entry.width && ax1 > entry.x && p.y < entry.y + entry.height && ay1 > entry.y) return true;
    }
    return false;
  }

  function reactToPoopTouch(p, now) {
    if (!p.settings.reactToPoop || now < p.lastPoopReactAt) return;
    if (!petTouchesPoop(p)) return;
    p.lastPoopReactAt = now + 2500;
    setExpression(p, E.DIZZY, 1500);
    if (p.state === S.IDLE || p.state === S.WALK) {
      hop(p, dpFloat(150));
    } else if (p.state === S.FLY) {
      p.velocityY -= dpFloat(220);
    }
  }

  function stepGround(p, dt, now) {
    if (p.settings.gravity && p.state !== S.SLEEP && p.y < maxYFor(p) - dpInt(1) && !p.hopActive) {
      p.state = S.FLY;
      p.velocityX = 0;
      p.velocityY = 0;
      return;
    }
    if (!p.settings.gravity) {
      p.y = clamp(p.y, minYFor(p), maxYFor(p));
      p.slideVelocity = 0;
    } else if (p.y < maxYFor(p) - dpInt(1) && !p.hopActive) {
      p.state = S.FLY;
      p.velocityY = 0;
      return;
    } else {
      p.y = Math.min(p.y, maxYFor(p));
      p.slideVelocity *= Math.exp(-2.6 * clamp(p.settings.slideFriction, 0.1, 3) * dt);
      if (Math.abs(p.slideVelocity) < dpFloat(7)) p.slideVelocity = 0;
      if (p.slideVelocity !== 0) {
        p.x += p.slideVelocity * dt;
        if (p.x < minXFor(p) || p.x > maxXFor(p)) {
          p.x = clamp(p.x, minXFor(p), maxXFor(p));
          p.slideVelocity = 0;
        } else if (Math.abs(p.slideVelocity) > dpFloat(55) && p.state === S.SLEEP) {
          p.state = S.IDLE;
          setExpression(p, E.SURPRISED, 800);
        }
        if (Math.abs(p.slideVelocity) > dpFloat(60)) {
          p.state = S.IDLE;
          p.look = p.slideVelocity > 0 ? 1 : -1;
        }
      }
    }

    if (p.state === S.WALK) {
      const direction = p.walkTarget >= p.x ? 1 : -1;
      const pcRunning = now < (p.pcWalkUntil || 0) && p.pcWalkSpeed > 0 && Math.abs(p.walkTarget - (p.pcWalkTarget || 0)) < 0.5;
      p.look = direction;
      p.x += direction * (pcRunning ? p.pcWalkSpeed : dpFloat(42)) * dt;
      const reached = direction > 0 ? p.x >= p.walkTarget : p.x <= p.walkTarget;
      const edge = (direction < 0 && p.x <= minXFor(p)) || (direction > 0 && p.x >= maxXFor(p));
      if (reached || edge) {
        p.x = clamp(p.x, minXFor(p), maxXFor(p));
        p.pcWalkSpeed = 0;
        p.pcWalkTarget = 0;
        p.pcWalkUntil = 0;
        p.state = S.IDLE;
        p.nextActionAt = now + randInt(2500, 6000);
        const side = p.peekPending;
        p.peekPending = 0;
        if (side !== 0 && ((side < 0 && p.x <= minXFor(p) + dpInt(4)) || (side > 0 && p.x >= maxXFor(p) - dpInt(4)))) {
          startPeek(p, now, side);
        } else {
          savePosition(p);
        }
      }
      return;
    }

    if (p.state === S.SLEEP) {
      if (now >= p.nextZAt) {
        p.nextZAt = now + 1650;
        spawnParticle(p, KIND.ZZZ);
      }
      return;
    }

    if (now >= p.nextBlinkAt) {
      p.blinkUntil = now + 130;
      p.nextBlinkAt = now + (chance(0.15) ? 450 : randInt(2000, 5500));
    }

    if (Number(p.settings.sleepMinutes) > 0 && now - p.lastInteractionAt > Number(p.settings.sleepMinutes) * 60_000 &&
      !p.hopActive && !app.mediaPlaying && p.state === S.IDLE) {
      p.state = S.SLEEP;
      p.look = 0;
      p.nextZAt = now + 500;
      hideBubble(p);
      return;
    }

    if (p.settings.poop && now >= p.nextPoopAt && p.state === S.IDLE && !p.hopActive) {
      poopNow(p, false);
      return;
    }

    if (app.mediaPlaying && p.settings.dance) {
      if (now >= p.nextNoteAt) {
        p.nextNoteAt = now + 1050;
        spawnParticle(p, KIND.NOTE);
      }
      return;
    }

    if (now < p.nextActionAt || p.hopActive) return;
    p.nextActionAt = now + randInt(3500, 9000);

    if (!p.settings.wander) {
      const roll = randInt(0, 100);
      if (roll <= 18) p.look = randInt(-1, 2);
      else if (roll <= 40) say(p, 'idle');
      else if (roll <= 56) p.look = randInt(-1, 2);
      else if (roll <= 66) p.waveUntil = now + 1600;
      else if (roll <= 75) playIdlePose(p, P.STRETCH, 1400, 'stretch', now);
      else if (roll <= 84) playIdlePose(p, P.YAWN, 1200, 'yawn', now);
      else if (roll <= 93) playIdlePose(p, P.GROOM, 1800, 'groom', now);
      else playIdlePose(p, P.SPIN, 900, 'spin', now);
      return;
    }

    const idleLongEnough = now - p.lastInteractionAt > PEEK_AFTER_MS;
    if (idleLongEnough && now >= p.nextPeekAt && chance(0.22)) {
      const side = (p.x - minXFor(p) < maxXFor(p) - p.x) ? -1 : 1;
      p.peekPending = side;
      p.walkTarget = side < 0 ? minXFor(p) : maxXFor(p);
      p.state = S.WALK;
      return;
    }

    const roll = randInt(0, 100);
    if (roll <= 28) {
      const distance = dpFloat(randInt(45, 155)) * (chance(0.5) ? 1 : -1);
      p.walkTarget = clamp(p.x + distance, minXFor(p), maxXFor(p));
      if (Math.abs(p.walkTarget - p.x) > dpFloat(12)) p.state = S.WALK;
    } else if (roll <= 39) {
      p.look = randInt(-1, 2);
    } else if (roll <= 53) {
      say(p, 'idle');
    } else if (roll <= 67) {
      p.look = randInt(-1, 2);
    } else if (roll <= 75) {
      p.waveUntil = now + 1600;
    } else if (roll <= 81) {
      playIdlePose(p, P.STRETCH, 1400, 'stretch', now);
    } else if (roll <= 87) {
      playIdlePose(p, P.YAWN, 1200, 'yawn', now);
    } else if (roll <= 93) {
      playIdlePose(p, P.GROOM, 1800, 'groom', now);
    } else {
      playIdlePose(p, P.SPIN, 900, 'spin', now);
    }
  }

  function bounceOffWall(p, now, direction) {
    const speed = Math.abs(p.velocityX);
    if (speed <= dpFloat(10)) {
      p.velocityX = 0;
      return;
    }
    // Не даём питомцу «прилипнуть» к стене: после удара он всегда
    // продолжает движение в обратную сторону.
    const bounceSpeed = Math.max(speed * 0.62, dpFloat(120));
    p.velocityX = direction > 0 ? bounceSpeed : -bounceSpeed;
    p.look = direction > 0 ? 1 : -1;
    p.squashUntil = now + 120;
    p.wallBounceAt = now;
  }

  function stepFlight(p, dt, now) {
    p.x += p.velocityX * dt;
    p.y += p.velocityY * dt;
    if (p.settings.gravity) {
      const weightK = clamp(1 + (clamp(p.settings.weight, 0.1, 5) - 1) * 0.5, 0.5, 3);
      const g = dpFloat(2450) * clamp(p.settings.gravityStrength, 0, 2) * weightK;
      p.velocityY += g * dt;
      p.velocityX *= Math.exp(-0.32 * clamp(p.settings.slideFriction, 0.1, 3) * dt);
    } else {
      const friction = Math.exp(-1.9 * clamp(p.settings.slideFriction, 0.1, 3) * dt);
      p.velocityX *= friction;
      p.velocityY *= friction;
    }
    if (p.x < minXFor(p)) {
      p.x = minXFor(p);
      bounceOffWall(p, now, 1);
    } else if (p.x > maxXFor(p)) {
      p.x = maxXFor(p);
      bounceOffWall(p, now, -1);
    }
    if (p.y < minYFor(p)) {
      if (p.settings.canHang && p.velocityY < -dpFloat(260) && !p.shakeDizzy) {
        startHang(p, now);
        return;
      }
      p.y = minYFor(p);
      p.velocityY = Math.abs(p.velocityY) < dpFloat(60) ? 0 : Math.abs(p.velocityY) * 0.55;
    } else if (p.y > maxYFor(p)) {
      p.y = maxYFor(p);
      if (p.settings.gravity && p.velocityY < dpFloat(390)) {
        land(p, now);
      } else {
        p.velocityY = Math.abs(p.velocityY) < dpFloat(60) ? 0 : -Math.abs(p.velocityY) * (p.settings.gravity ? 0.42 : 0.55);
        p.velocityX *= 0.78;
        p.squashUntil = now + 120;
      }
    }
    if (p.state === S.FLY && !p.settings.gravity && Math.hypot(p.velocityX, p.velocityY) < dpFloat(42)) land(p, now);
    if (p.shakeDizzy && now - p.lastInteractionAt > 1200 && p.state === S.FLY && Math.hypot(p.velocityX, p.velocityY) < dpFloat(160)) {
      p.shakeDizzy = false;
      setExpression(p, E.DIZZY, 2000);
      say(p, 'dizzy');
    }
  }

  function land(p, now) {
    p.state = S.IDLE;
    p.velocityX = 0;
    p.velocityY = 0;
    p.hopActive = false;
    p.hopOffset = 0;
    p.hopVelocity = 0;
    p.hopCooldownUntil = now + 2000;
    p.squashUntil = now + 140;
    p.nextActionAt = now + randInt(2000, 5000);
    if (p.shakeDizzy) {
      p.shakeDizzy = false;
      setExpression(p, E.DIZZY, 2500);
      say(p, 'dizzy');
    }
    savePosition(p);
  }

  function startHang(p, now) {
    p.state = S.HANG;
    p.velocityX = 0;
    p.velocityY = 0;
    p.y = minYFor(p) - p.unit * 1.5;
    p.hangStartedAt = now;
    p.hangUntil = now + randInt(2000, 3500);
    if (chance(0.7)) say(p, 'hang');
  }

  function stepHang(p, now) {
    p.x = clamp(p.x, minXFor(p), maxXFor(p));
    p.y = minYFor(p) - p.unit * 1.5;
    if (now < p.hangUntil) return;
    p.state = S.FLY;
    p.velocityX = 0;
    p.velocityY = dpFloat(p.settings.gravity ? 160 : 650);
    setExpression(p, E.SURPRISED, 700);
    if (chance(0.6)) say(p, 'hang_fall');
  }

  function stepPeek(p, dt, now) {
    const hiddenUnits = 3.2;
    const visibleUnits = 7;
    const targetX = p.peekSide > 0
      ? screenW - p.unit * (p.peekOut ? visibleUnits : hiddenUnits)
      : -p.winW + p.unit * (p.peekOut ? visibleUnits : hiddenUnits);
    const delta = targetX - p.x;
    const step = dpFloat(52) * dt;
    p.peekMoving = Math.abs(delta) > 1;
    if (p.peekMoving) setPosition(p, p.x + clamp(delta, -step, step), p.y, true);
    p.look = (!p.peekOut && p.peekMoving) ? p.peekSide : -p.peekSide;
    if (now < p.peekPhaseUntil || p.peekMoving) return;
    if (now >= p.peekEndAt) {
      endPeek(p, now);
      return;
    }
    if (!p.peekOut) {
      p.peekOut = true;
      p.peekPhaseUntil = now + randInt(1400, 2800);
      if (chance(0.55)) say(p, 'peek');
    } else {
      p.peekOut = false;
      p.peekPhaseUntil = now + randInt(3000, 7000);
    }
  }

  function startPeek(p, now, side) {
    p.state = S.PEEK;
    p.peekSide = side;
    p.peekOut = false;
    p.peekMoving = true;
    p.peekPhaseUntil = now + randInt(2500, 5000);
    p.peekEndAt = now + randInt(25_000, 45_000);
    p.look = side;
  }

  function endPeek(p, now) {
    const side = p.peekSide;
    if (!side) {
      p.state = S.IDLE;
      p.nextPeekAt = now + randInt(60_000, 120_000);
      return;
    }
    p.peekSide = 0;
    p.peekMoving = false;
    p.nextPeekAt = now + randInt(60_000, 120_000);
    p.state = S.WALK;
    p.walkTarget = side < 0 ? minXFor(p) + dpFloat(30) : maxXFor(p) - dpFloat(30);
    p.nextActionAt = now + randInt(3000, 6000);
  }

  function reactToPetTouch(a, b) {
    const now = nowMs();
    for (const self of [a, b]) {
      if (self.state === S.SLEEP) {
        if (!self.settings.wakeOnPetTouch || now < self.lastPetReactAt) continue;
        self.lastPetReactAt = now + 2000;
        self.state = S.IDLE;
        setExpression(self, E.SURPRISED, 1000);
        continue;
      }
      if (!self.settings.reactToOtherPets || now < self.lastPetReactAt) continue;
      self.lastPetReactAt = now + 2000;
      setExpression(self, E.HAPPY, 1000);
    }
  }

  function resolveCollisions() {
    if (!app.settings.petsCollide || app.pets.length < 2) return;
    for (let i = 0; i < app.pets.length; i++) {
      for (let j = i + 1; j < app.pets.length; j++) {
        const a = app.pets[i];
        const b = app.pets[j];
        if (a.pcHidden || b.pcHidden) continue;
        if (a.state === S.PEEK || b.state === S.PEEK) continue;
        if (a.state === S.SLEEP && b.state === S.SLEEP) continue;
        const minD = (a.winW + b.winW) / 2 * 0.75;
        const dx = (b.x + b.winW / 2) - (a.x + a.winW / 2);
        if (dx >= minD || dx <= -minD) continue;
        const dy = (b.y + b.winH / 2) - (a.y + a.winH / 2);
        if (dy >= minD || dy <= -minD) continue;
        const dist = Math.hypot(dx, dy);
        if (dist >= minD || dist < 0.01) continue;
        const nx = dx / dist;
        const ny = dy / dist;
        const wa = clamp(a.settings.weight, 0.1, 5);
        const wb = clamp(b.settings.weight, 0.1, 5);
        const rvx = b.velocityX - a.velocityX;
        const rvy = b.velocityY - a.velocityY;
        const vn = rvx * nx + rvy * ny;
        if (vn <= 0) {
          const k = 0.55;
          const impulse = -(1 + k) * vn / (1 / wa + 1 / wb);
          if (a.state !== S.DRAG) {
            a.velocityX -= impulse * nx / wa;
            a.velocityY -= impulse * ny / wa;
          }
          if (b.state !== S.DRAG) {
            b.velocityX += impulse * nx / wb;
            b.velocityY += impulse * ny / wb;
          }
          if (a.state === S.IDLE || a.state === S.WALK || a.state === S.SLEEP) a.state = S.FLY;
          if (b.state === S.IDLE || b.state === S.WALK || b.state === S.SLEEP) b.state = S.FLY;
        }
        const overlap = (minD - dist) * 0.5;
        if (a.state !== S.DRAG) {
          a.x = clamp(a.x - nx * overlap, minXFor(a), maxXFor(a));
          a.y = clamp(a.y - ny * overlap, minYFor(a), maxYFor(a));
        }
        if (b.state !== S.DRAG) {
          b.x = clamp(b.x + nx * overlap, minXFor(b), maxXFor(b));
          b.y = clamp(b.y + ny * overlap, minYFor(b), maxYFor(b));
        }
        reactToPetTouch(a, b);
      }
    }
  }

  function stepPet(p, dt, now) {
    if (p.state === S.FLY) stepFlight(p, dt, now);
    else if (p.state === S.HANG) stepHang(p, now);
    else if (p.state === S.PEEK) stepPeek(p, dt, now);
    else if (p.state === S.PET) {
      if (now >= p.nextHeartAt) {
        p.nextHeartAt = now + 450;
        spawnParticle(p, KIND.HEART);
      }
    } else if (p.state === S.IDLE || p.state === S.WALK || p.state === S.SLEEP) {
      stepGround(p, dt, now);
    }

    if (p.hopActive) {
      p.hopOffset += p.hopVelocity * dt;
      p.hopVelocity += dpFloat(2500) * dt;
      if (p.hopOffset >= 0) {
        p.hopOffset = 0;
        p.hopVelocity = 0;
        p.hopActive = false;
        p.squashUntil = now + 110;
      }
    }
    p.particles = p.particles.filter(function (particle) { return now - particle.bornAt < particle.duration; });
    reactToPoopTouch(p, now);
  }

  function clearPressTimer(p) {
    if (p.pressTimer) {
      clearTimeout(p.pressTimer);
      p.pressTimer = null;
    }
  }

  function onPointerDown(p, event) {
    if (event.button !== 0 || !app.running) return;
    event.preventDefault();
    event.stopPropagation();
    if (app.activeTouch) return;
    app.activeTouch = { p: p, pointerId: event.pointerId, noTouch: false };
    app.interacting = true;
    updateInputRegion();
    try { p.el.setPointerCapture(event.pointerId); } catch (_) {}

    if (p.hiddenUntilTap) {
      p.hiddenUntilTap = false;
      p.lastInteractionAt = nowMs();
      endPeek(p, nowMs());
      app.activeTouch.noTouch = true;
      return;
    }

    const now = nowMs();
    const wasSleeping = p.state === S.SLEEP;
    const wasPeeking = p.state === S.PEEK;
    p.lastInteractionAt = now;
    p.touch = {
      downX: event.clientX,
      downY: event.clientY,
      downAt: now,
      offsetX: event.clientX - p.x,
      offsetY: event.clientY - p.y,
      anchorX: event.clientX,
      direction: 0,
      velocityX: 0,
      samples: [{ time: now, x: event.clientX, y: event.clientY }]
    };
    p.hopActive = false;
    p.hopOffset = 0;
    p.hopVelocity = 0;
    p.velocityX = 0;
    p.velocityY = 0;
    p.slideVelocity = 0;
    p.peekSide = 0;
    p.peekPending = 0;
    p.state = S.PRESS;
    if (wasSleeping) {
      setExpression(p, E.SURPRISED, 800);
      say(p, 'wake');
    } else if (wasPeeking) {
      p.nextPeekAt = now + randInt(60_000, 120_000);
      setExpression(p, E.SURPRISED, 650);
      say(p, 'found');
    }
    clearPressTimer(p);
    p.pressTimer = setTimeout(function () {
      p.pressTimer = null;
      if (app.activeTouch && app.activeTouch.p === p && p.state === S.PRESS) beginPet(p, nowMs());
    }, LONG_PRESS_MS);
  }

  function addTouchSample(track, now, x, y) {
    track.samples.push({ time: now, x: x, y: y });
    while (track.samples.length > 2 && now - track.samples[0].time > 110) track.samples.shift();
    while (track.samples.length > 6) track.samples.shift();
  }

  function onPointerMove(event) {
    const active = app.activeTouch;
    if (!active || active.pointerId !== event.pointerId) return;
    if (active.noTouch) return;
    const p = active.p;
    const now = nowMs();
    const track = p.touch;
    if (!track) return;
    event.preventDefault();
    addTouchSample(track, now, event.clientX, event.clientY);
    if (p.state === S.PRESS && Math.hypot(event.clientX - track.downX, event.clientY - track.downY) > dpFloat(8)) {
      clearPressTimer(p);
      p.state = S.DRAG;
      p.peekPending = 0;
      p.el.classList.add('dragging');
      if (chance(0.25)) say(p, 'drag');
    }
    if (p.state === S.DRAG) {
      setPosition(p, event.clientX - track.offsetX, event.clientY - track.offsetY, false);
      const first = track.samples[0];
      if (first && now - first.time > 5) {
        track.velocityX = (event.clientX - first.x) / ((now - first.time) / 1000);
      }
    } else if (p.state === S.PET) {
      const dx = event.clientX - track.anchorX;
      if (Math.abs(dx) > dpFloat(10)) {
        const direction = dx > 0 ? 1 : -1;
        if (track.direction !== 0 && direction !== track.direction) spawnParticle(p, KIND.HEART);
        track.direction = direction;
        track.anchorX = event.clientX;
      }
    }
  }

  function onPointerUp(event, cancelled) {
    const active = app.activeTouch;
    if (!active || active.pointerId !== event.pointerId) return;
    if (cancelled) event.preventDefault();
    const p = active.p;
    clearPressTimer(p);
    if (active.noTouch) {
      app.activeTouch = null;
      app.interacting = false;
      updateInputRegion();
      return;
    }
    const track = p.touch;
    p.touch = null;
    p.lastInteractionAt = nowMs();
    const now = nowMs();
    if (p.state === S.DRAG) {
      releaseDrag(p, now, track, cancelled);
    } else if (p.state === S.PET) {
      endPet(p, now);
    } else if (p.state === S.PRESS) {
      if (!cancelled && track && now - track.downAt >= LONG_PRESS_MS) {
        beginPet(p, now);
        endPet(p, now);
      } else {
        p.state = S.IDLE;
        if (!cancelled) onTap(p, now);
      }
    } else {
      p.state = S.IDLE;
    }
    p.el.classList.remove('dragging');
    app.activeTouch = null;
    app.interacting = false;
    updateInputRegion();
    try { p.el.releasePointerCapture(event.pointerId); } catch (_) {}
  }

  function cancelActiveTouch(force, onlyPet) {
    const active = app.activeTouch;
    if (!active || (onlyPet && active.p !== onlyPet)) return;
    const p = active.p;
    clearPressTimer(p);
    p.el.classList.remove('dragging');
    p.touch = null;
    if (p.state === S.DRAG || p.state === S.PRESS) p.state = S.IDLE;
    app.activeTouch = null;
    app.interacting = false;
    updateInputRegion();
  }

  function releaseDrag(p, now, track, cancelled) {
    let vx = 0;
    let vy = 0;
    const first = track && track.samples[0];
    const last = track && track.samples[track.samples.length - 1];
    if (!cancelled && first && last && last.time - first.time > 5 && now - last.time < 100) {
      const elapsed = (last.time - first.time) / 1000;
      vx = (last.x - first.x) / elapsed;
      vy = (last.y - first.y) / elapsed;
    }
    const limit = dpFloat(6000);
    vx = clamp(vx, -limit, limit);
    vy = clamp(vy, -limit, limit);
    if (!cancelled && p.settings.canHang && Math.abs(vy) > dpFloat(700) && p.y <= minYFor(p) + p.unit * 2) {
      startHang(p, now);
    } else if (!cancelled && (Math.hypot(vx, vy) > dpFloat(700) || p.settings.gravity)) {
      p.state = S.FLY;
      p.velocityX = vx;
      p.velocityY = vy;
      if (Math.hypot(vx, vy) > dpFloat(2500)) say(p, 'throw');
    } else {
      p.state = S.IDLE;
      p.squashUntil = now + 130;
      p.nextActionAt = now + randInt(2000, 5000);
      savePosition(p);
    }
  }

  function onTap(p, now) {
    if (now - p.lastTapAt < DOUBLE_TAP_MS) {
      p.lastTapAt = 0;
      hop(p, dpFloat(360));
      setExpression(p, E.HAPPY, 1300);
      for (let i = 0; i < 3; i++) spawnParticle(p, KIND.SPARK);
      if (chance(0.4)) say(p, 'jump');
      return;
    }
    p.lastTapAt = now;
    while (p.recentTaps.length && now - p.recentTaps[0] > 2500) p.recentTaps.shift();
    p.recentTaps.push(now);
    if (p.recentTaps.length >= 6) {
      p.recentTaps = [];
      setExpression(p, E.ANGRY, 2000);
      say(p, 'annoyed', true);
      return;
    }
    p.squashUntil = now + 130;
    setExpression(p, E.SURPRISED, 450);
    hop(p, dpFloat(200));
    if (chance(0.35)) say(p, 'tap');
  }

  function beginPet(p, now) {
    if (p.state !== S.PRESS && p.state !== S.PET) return;
    p.state = S.PET;
    p.nextHeartAt = now + 100;
  }

  function endPet(p, now) {
    p.state = S.IDLE;
    setExpression(p, E.HAPPY, 2200);
    p.nextActionAt = now + randInt(3000, 6000);
    if (chance(0.65)) say(p, 'pet');
  }

  function recenter() {
    if (!app.pets.length) return;
    app.pets.forEach(function (p, index) {
      p.x = clamp((minXFor(p) + maxXFor(p)) / 2 + index * p.winW * 0.3, minXFor(p), maxXFor(p));
      p.y = (minYFor(p) + maxYFor(p)) / 2;
      p.state = S.IDLE;
      p.velocityX = 0;
      p.velocityY = 0;
      p.slideVelocity = 0;
      p.peekSide = 0;
      p.peekPending = 0;
      p.lastInteractionAt = nowMs();
      updatePetLayout(p);
      hop(p, dpFloat(500));
      for (let i = 0; i < 3; i++) spawnParticle(p, KIND.SPARK);
      say(p, 'hello');
    });
    savePositionNow();
  }

  function reactToNotification(p, mood) {
    if (typeof mood === 'string' && mood.indexOf('say:') === 0) {
      if (!app.running || !p.el) return;
      p.lastInteractionAt = nowMs();
      showBubbleText(p, mood.slice(4));
      return;
    }
    if (!p.settings.reactNotifications || !app.running || !p.el) return;
    const now = nowMs();
    if (now < p.nextExternalReactionAt || (p.state !== S.IDLE && p.state !== S.WALK && p.state !== S.SLEEP && p.state !== S.PEEK)) return;
    p.nextExternalReactionAt = now + (mood === 'mail' ? 5000 : 3000);
    p.lastInteractionAt = now;
    if (p.state === S.SLEEP) p.state = S.IDLE;
    if (p.state === S.PEEK) endPeek(p, now);
    if (mood === 'love') {
      setExpression(p, E.HAPPY, 2000);
      for (let i = 0; i < 3; i++) spawnParticle(p, KIND.HEART);
      say(p, 'love');
    } else if (mood === 'laugh') {
      setExpression(p, E.HAPPY, 1500);
      hop(p, dpFloat(400));
      spawnParticle(p, KIND.SPARK);
      say(p, 'laugh');
    } else if (mood === 'hi') {
      p.waveUntil = now + 1800;
      say(p, 'hi');
    } else {
      p.look = chance(0.5) ? 1 : -1;
      setExpression(p, E.SURPRISED, 700);
      hop(p, dpFloat(290));
      say(p, 'mail');
    }
    p.nextActionAt = Math.max(p.nextActionAt, now + 2500);
  }

  function frameInterval(now) {
    if (!app.running) return 220;
    if (app.interacting || app.activeTouch) return 16;
    let active = false;
    let allSleeping = app.pets.length > 0;
    for (const p of app.pets) {
      if (p.pcHidden) continue;
      if (p.state !== S.SLEEP) allSleeping = false;
      if (p.touch || p.hopActive || p.particles.length || p.bubbleVisible || p.slideVelocity !== 0 ||
          p.state === S.DRAG || p.state === S.PRESS || p.state === S.PET || p.state === S.FLY ||
          p.state === S.HANG || p.state === S.PEEK || p.state === S.WALK ||
          now < p.expressionUntil || now < p.idlePoseUntil || now < p.waveUntil ||
          now < p.squashUntil || now < p.blinkUntil) {
        active = true;
      }
    }
    if (app.mediaPlaying) active = true;
    if (active) return 16;
    return allSleeping ? 125 : 48;
  }

  function tick(now) {
    if (now < nextFrameAt) {
      requestAnimationFrame(tick);
      return;
    }
    const dt = clamp((now - lastTick) / 1000, 0, 0.05);
    lastTick = now;
    nextFrameAt = now + frameInterval(now);
    if (app.running) {
      resolveCollisions();
      for (const p of app.pets.slice()) {
        updatePcPresence(p, now);
        if (!p.pcHidden) stepPet(p, dt, now);
      }
      if (app.pets.length && now >= app.lastPositionSaveAt) {
        app.lastPositionSaveAt = now + 8000;
        savePositionNow();
      }
    }
    for (const p of app.pets) {
      updatePetLayout(p);
      if (p.pcHidden) continue;
      updateBubble(p, now);
      drawPet(p, now);
    }
    if (now - lastStatusAt > 2000) {
      lastStatusAt = now;
      writeStatus();
    }
    requestAnimationFrame(tick);
  }

  function petHitRect(p) {
    if (!p.el) return null;
    const unit = Math.min(p.winW / GRID_W, p.winH / (GRID_H + 2));
    const left = p.x + Math.max(0, unit * 0.4);
    const right = p.x + p.winW - Math.max(0, unit * 0.4);
    const top = p.y + unit * 1.65;
    const bottom = p.y + Math.min(p.winH, unit * (GRID_H + 2.45));
    return { left: left, top: top, width: right - left, height: bottom - top };
  }

  function hitTestPet(p, x, y) {
    const rect = petHitRect(p);
    return !!rect && x >= rect.left && x <= rect.left + rect.width && y >= rect.top && y <= rect.top + rect.height;
  }

  function hitTest(x, y) {
    for (const p of app.pets) {
      if (p.pcHidden) continue;
      if (hitTestPet(p, x, y)) return true;
    }
    return false;
  }

  function pcDistancePx(pc) {
    return dpFloat(90 + clamp(Number(pc.cursorDistance) || 5, 1, 10) * 36);
  }

  function pcAppEntryMatches(entry, wanted) {
    const needle = String(wanted || '').trim().toLowerCase();
    if (!needle) return false;
    if (String(entry.id || '').toLowerCase() === needle) return true;
    return (Array.isArray(entry.terms) ? entry.terms : []).some(function (term) {
      const value = String(term || '').trim().toLowerCase();
      return value === needle || value.indexOf(needle) >= 0;
    });
  }

  function pcOpenAppEntries() {
    if (app.openAppEntries.length) return app.openAppEntries;
    return app.openApps.map(function (name) {
      return { id: name, label: name, terms: [name] };
    });
  }

  function pcSpeedT(pc) {
    return (clamp(Number(pc.cursorSpeed) || 50, 1, 100) - 1) / 99;
  }

  function pcRunSpeed(pc) {
    // 1 — очень медленно, 100 — почти мгновенно. Экспонента даёт
    // равномерно ощутимые шаги по всему диапазону 1..100.
    return 24 * Math.pow(300, pcSpeedT(pc));
  }

  function pcReactionDelay(pc) {
    return Math.round(1000 - 970 * pcSpeedT(pc));
  }

  function pcStartRun(p, target, now, speed) {
    const duration = Math.max(180, Math.abs(target - p.x) / speed * 1000 + 180);
    p.pcWalkSpeed = speed;
    p.pcWalkTarget = target;
    p.pcWalkUntil = now + duration;
    p.walkTarget = target;
    p.state = S.WALK;
    p.nextActionAt = now + duration + 500;
  }

  function updatePcPresence(p, now) {
    const pc = p.pcSettings || DEFAULT_PC;
    const entries = pcOpenAppEntries();
    let hidden = false;
    // Перетаскивание одного питомца не должно временно показывать питомцев,
    // скрытых правилом «скрывать при открытом приложении»: иначе после
    // отпускания мыши они резко исчезают.
    if (pc.hideOnAppOpen) {
      if (pc.hideMode === 'all') {
        hidden = entries.length > 0;
      } else if (pc.hideFilter === 'except') {
        hidden = entries.some(function (entry) {
          return !pc.hideApps.some(function (wanted) { return pcAppEntryMatches(entry, wanted); });
        });
      } else {
        hidden = entries.some(function (entry) {
          return pc.hideApps.some(function (wanted) { return pcAppEntryMatches(entry, wanted); });
        });
      }
    }
    if (hidden !== p.pcHidden) {
      p.pcHidden = hidden;
      p.el.style.visibility = hidden ? 'hidden' : 'visible';
      if (hidden) {
        hideBubble(p, false);
        clearPetPoops(p);
        p.particles.length = 0;
      }
    }
    if (hidden) {
      p.pcFrame = '';
      return;
    }

    let frame = '';
    if (pc.extendedFrames) {
      if (pc.appOpen && app.settingsVisible) {
        frame = 'app_open';
      } else if (lastCursor && lastCursor.known) {
        const cx = lastCursor.x;
        const cy = lastCursor.y;
        const centerX = p.x + p.winW / 2;
        const centerY = p.y + p.winH * 0.55;
        const distance = Math.hypot(cx - centerX, cy - centerY);
        const threshold = pcDistancePx(pc);
        const touch = pc.cursorTouch && hitTestPet(p, cx, cy);
        if (touch) frame = 'cursor_hover';
        else if (pc.cursorNear && distance <= threshold) frame = 'cursor_near';
        else if (pc.cursorFar && distance > threshold) frame = 'cursor_far';
      }
    }
    p.pcFrame = frame;

    if (pc.cursorReaction === 'none' || !lastCursor || !lastCursor.known) return;
    if (app.interacting || p.state === S.DRAG || p.state === S.PRESS || p.state === S.PET ||
        p.state === S.FLY || p.state === S.HANG || p.state === S.PEEK) return;
    const cx = lastCursor.x;
    const cy = lastCursor.y;
    const centerX = p.x + p.winW / 2;
    const centerY = p.y + p.winH * 0.55;
    const distance = Math.hypot(cx - centerX, cy - centerY);
    const threshold = pcDistancePx(pc);
    if (pc.cursorDetection === 'distance' && distance > threshold) return;
    if (now < p.pcMoveAt) return;

    const speed = pcRunSpeed(pc);
    if (pc.cursorReaction === 'chase') {
      if (distance <= dpFloat(58)) return;
      const target = clamp(cx - p.winW / 2, minXFor(p), maxXFor(p));
      if (Math.abs(target - p.x) < dpFloat(6)) return;
      p.pcMoveAt = now + pcReactionDelay(pc);
      p.lastInteractionAt = now;
      if (p.state === S.SLEEP) {
        p.state = S.IDLE;
        p.nextZAt = now + 700;
      }
      pcStartRun(p, target, now, speed);
    } else if (pc.cursorReaction === 'flee') {
      const safeDistance = Math.max(threshold * 0.72, dpFloat(100));
      if (distance > safeDistance) return;
      const away = centerX >= cx ? 1 : -1;
      const target = clamp(p.x + away * dpFloat(150), minXFor(p), maxXFor(p));
      if (Math.abs(target - p.x) < dpFloat(8)) return;
      p.pcMoveAt = now + pcReactionDelay(pc);
      p.lastInteractionAt = now;
      if (p.state === S.SLEEP) {
        p.state = S.IDLE;
        p.nextZAt = now + 700;
      }
      pcStartRun(p, target, now, speed);
    }
  }

  function desiredInputRects() {
    if (!app.running) return [];
    const scale = windowScale || dpr || 1;
    const rects = [];
    for (const p of app.pets) {
      if (p.pcHidden) continue;
      const rect = petHitRect(p);
      if (!rect) continue;
      const left = Math.max(0, rect.left - 1);
      const top = Math.max(0, rect.top - 1);
      const right = Math.min(screenW, rect.left + rect.width + 1);
      const bottom = Math.min(screenH, rect.top + rect.height + 1);
      if (right <= left || bottom <= top) continue;
      rects.push([
        Math.round(left * scale),
        Math.round(top * scale),
        Math.max(1, Math.round((right - left) * scale)),
        Math.max(1, Math.round((bottom - top) * scale))
      ]);
    }
    for (const entry of app.poops) {
      if (entry.removing || !entry.el || poopOwnerHidden(entry)) continue;
      const left = Math.max(0, entry.x - 2);
      const top = Math.max(0, entry.y - 2);
      const right = Math.min(screenW, entry.x + entry.width + 2);
      const bottom = Math.min(screenH, entry.y + entry.height + 2);
      if (right <= left || bottom <= top) continue;
      rects.push([
        Math.round(left * scale),
        Math.round(top * scale),
        Math.max(1, Math.round((right - left) * scale)),
        Math.max(1, Math.round((bottom - top) * scale))
      ]);
    }
    return rects;
  }

  function updateInputRegion() {
    const rects = desiredInputRects();
    const signature = JSON.stringify(rects);
    if (signature === inputRegionLast) return;
    if (inputRegionBusy) {
      inputRegionPending = rects;
      return;
    }
    inputRegionBusy = true;
    invoke('set_click_regions', { rects: rects }).then(function (ok) {
      inputRegionBusy = false;
      if (ok) {
        inputRegionLast = signature;
        lastInputRects = rects;
        app.ignoreApplied = rects.length === 0;
        app.ignoreWanted = rects.length === 0;
      }
      const pending = inputRegionPending;
      inputRegionPending = null;
      if (pending) {
        inputRegionPending = null;
        updateInputRegion();
      } else if (!ok) {
        setTimeout(updateInputRegion, 250);
      }
    }).catch(function (err) {
      inputRegionBusy = false;
      console.error('set_click_regions failed', err);
      setTimeout(updateInputRegion, 500);
    });
  }

  async function refreshGeometry() {
    try {
      const outer = await appWindow.outerPosition();
      if (outer && typeof outer.x === 'number' && typeof outer.y === 'number') {
        windowOrigin = { x: outer.x, y: outer.y };
      }
      const scale = await appWindow.scaleFactor();
      if (scale) windowScale = scale;
    } catch (err) {
      console.error('window geometry failed', err);
    }
  }

  async function updateClickThrough() {
    if (!app.running) return;
    try {
      const cursor = await tauri.window.cursorPosition();
      const x = (cursor.x - windowOrigin.x) / windowScale;
      const y = (cursor.y - windowOrigin.y) / windowScale;
      lastCursor = {
        x: x,
        y: y,
        raw: [cursor.x, cursor.y],
        css: [Math.round(x), Math.round(y)],
        known: true,
        at: nowMs()
      };
      lastHit = hitTest(x, y);
    } catch (err) {
      lastCursor = { x: 0, y: 0, known: false, error: String(err), at: nowMs() };
      lastHit = null;
    }
  }

  function handleResize() {
    screenW = Math.max(1, window.innerWidth);
    screenH = Math.max(1, window.innerHeight);
    dpr = window.devicePixelRatio || 1;
    for (const p of app.pets) {
      updatePetWindowSize(p);
      sizePetCanvas(p);
      if (p.state !== S.PEEK) {
        p.x = clamp(p.x, minXFor(p), maxXFor(p));
        p.y = clamp(p.y, minYFor(p), maxYFor(p));
      }
      updatePetLayout(p);
    }
    refreshGeometry();
    updateInputRegion();
  }

  function writeStatus() {
    const report = {
      running: app.running,
      mediaPlaying: app.mediaPlaying,
      mediaTrack: app.mediaTrack,
      interacting: app.interacting,
      ignore: app.ignoreApplied,
      ignoreWanted: app.ignoreWanted,
      cursor: lastCursor,
      hit: lastHit,
      clickRegions: lastInputRects,
      viewport: [screenW, screenH],
      dpr: dpr,
      scale: windowScale,
      origin: windowOrigin,
      activeIds: app.activeIds.slice(),
      settingsVisible: app.settingsVisible,
      openApps: app.openApps.slice(),
      poopsCleaned: app.poopsCleaned,
      loadedSkins: Array.from(app.skinCache.values()).map(function (entry) {
        return { id: entry.id, revision: entry.revision, loaded: entry.loaded, frames: Object.keys(entry.images) };
      }),
      pets: app.pets.map(function (p) {
        return {
          id: p.skinId,
          state: p.state,
          x: Math.round(p.x),
          y: Math.round(p.y),
          unit: Math.round(p.unit * 10) / 10,
          win: [p.winW, p.winH],
          velocity: [Math.round(p.velocityX), Math.round(p.velocityY)],
          wallBounceAt: Math.round(p.wallBounceAt || 0),
          phrase: p.bubbleVisible ? p.bubble.textContent : '',
          bubble: p.bubbleVisible ? {
            x: Math.round(parseFloat(p.bubble.style.left) || 0),
            y: Math.round(parseFloat(p.bubble.style.top) || 0),
            width: p.bubble.offsetWidth,
            height: p.bubble.offsetHeight
          } : null,
          expression: p.expression,
          media: app.mediaPlaying,
          pcFrame: p.pcFrame || '',
          pcHidden: !!p.pcHidden,
          pcReaction: p.pcSettings.cursorReaction || 'none',
          pcSpeed: p.pcSettings.cursorSpeed || 50,
          frames: !!(p.skinEntry && p.skinEntry.loaded)
        };
      }),
      poops: app.poops.map(function (entry) {
        return { x: Math.round(entry.x), y: Math.round(entry.y), width: entry.width, height: entry.height };
      })
    };
    invoke('overlay_status', { report: report }).catch(function (err) {
      console.error('overlay_status failed', err);
    });
  }

  async function pollOpenApps() {
    const needed = app.pets.some(function (p) {
      return p.pcSettings && p.pcSettings.hideOnAppOpen;
    });
    if (!needed) {
      app.openApps = [];
      app.openAppEntries = [];
      return;
    }
    try {
      // Для скрытия нужен только сфокусированный клиент, а не весь список
      // открытых окон: приложение в фоне не должно прятать питомца.
      const list = await invoke('list_active_apps');
      if (Array.isArray(list)) {
        const entries = [];
        const terms = new Set();
        list.forEach(function (item) {
          if (typeof item === 'string') {
            const name = item.trim().toLowerCase();
            if (!name) return;
            entries.push({ id: name, label: item.trim(), terms: [name] });
            terms.add(name);
            return;
          }
          if (!item || typeof item !== 'object') return;
          const id = String(item.id || item.label || '').trim().toLowerCase();
          const label = String(item.label || item.id || '').trim();
          if (!id || !label) return;
          const entryTerms = (Array.isArray(item.terms) ? item.terms : [id, label])
            .map(function (term) { return String(term || '').trim().toLowerCase(); })
            .filter(Boolean);
          entries.push({ id: id, label: label, terms: entryTerms });
          entryTerms.forEach(function (term) { terms.add(term); });
        });
        app.openAppEntries = entries;
        app.openApps = Array.from(terms);
      }
    } catch (_) {}
  }

  function applyMusicState(state) {
    const payload = state || {};
    app.mediaTrack = [payload.artist, payload.track].filter(Boolean).join(' — ');
    setMediaPlaying(!!payload.playing);
  }

  async function wireEvents() {
    const handlers = [
      ['gupet://state', function () { scheduleReload(false); }],
      ['gupet://settings-visible', function (event) {
        const payload = (event && event.payload) || {};
        app.settingsVisible = !!payload.visible;
      }],
      ['gupet://poop-now', function () {
        for (const p of app.pets) poopNow(p, true);
      }],
      ['gupet://clean-poops', function () { clearPoops(true); }],
      ['gupet://test-phrase', function (event) {
        const text = event && event.payload && (event.payload.text || event.payload);
        if (typeof text === 'string' && app.pets.length) showBubbleText(app.pets[0], text);
      }],
      ['gupet://recenter', function () { recenter(); }],
      ['gupet://music', function (event) {
        const payload = (event && event.payload) || {};
        applyMusicState(payload);
      }],
      ['gupet://notification', function (event) {
        const payload = (event && event.payload) || {};
        const mood = payload.mood || (payload.source === 'desktop' ? 'mail' : 'mail');
        for (const p of app.pets) reactToNotification(p, mood);
      }]
    ];
    const results = await Promise.allSettled(handlers.map(function (pair) {
      return listen(pair[0], pair[1]);
    }));
    for (let i = 0; i < results.length; i++) {
      if (results[i].status === 'rejected') {
        console.error('event listen failed', handlers[i][0], results[i].reason);
      }
    }
  }

  window.__GUPET_OVERLAY__ = {
    state: app,
    invoke: invoke,
    reload: function () { return reloadNow(false); },
    poop: function () { for (const p of app.pets) poopNow(p, true); },
    clean: function () { clearPoops(true); },
    recenter: recenter,
    status: writeStatus
  };

  window.addEventListener('resize', handleResize);
  window.addEventListener('pointermove', onPointerMove, { passive: false });
  window.addEventListener('pointerup', function (event) { onPointerUp(event, false); });
  window.addEventListener('pointercancel', function (event) { onPointerUp(event, true); });
  window.addEventListener('beforeunload', savePositionNow);

  requestAnimationFrame(function (time) {
    lastTick = time;
    requestAnimationFrame(tick);
  });
  setInterval(updateClickThrough, 150);
  setInterval(updateInputRegion, 120);
  setInterval(pollOpenApps, 2500);
  setInterval(refreshGeometry, 5000);
  refreshGeometry();
  updateInputRegion();

  wireEvents().then(function () {
    return reloadNow(true);
  }).then(function () {
    updateInputRegion();
    writeStatus();
  }).catch(function (err) {
    showFatal(errorText(err));
  });
})();
