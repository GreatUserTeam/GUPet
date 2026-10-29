/* GUPet Linux bridge: synchronous AndroidPrefs/AndroidPet facades over Tauri commands. */
(function () {
  'use strict';

  var tauri = window.__TAURI__;
  if (!tauri || !tauri.core || !tauri.event) {
    window.GUPET_BRIDGE_READY = Promise.reject(new Error('Tauri API is not available'));
    return;
  }

  var invoke = tauri.core.invoke;
  var listen = tauri.event.listen;
  var cache = {
    prefs: {},
    pet: {},
    phrases: [],
    skins: [],
    full: { active: 'builtin', activeIds: ['builtin'], skins: [] },
    running: false,
    autostart: false,
    configDir: '',
    skinsDir: '',
    settingsPath: '',
    poopsCleaned: 0,
    settingsVisible: false
  };
  var skinPreview = {};
  var skinById = {};
  var refreshPromise = null;
  var stateRefreshTimer = null;
  var jsonCache = { pet: null, phrases: null, skins: null, full: null, bySkin: {} };

  function clearJsonCache() {
    jsonCache.pet = null;
    jsonCache.phrases = null;
    jsonCache.skins = null;
    jsonCache.full = null;
    jsonCache.bySkin = {};
  }

  function defaultPcSettings() {
    return {
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
  }

  function stringify(value) {
    try { return JSON.stringify(value); } catch (_) { return 'null'; }
  }

  function parse(text, fallback) {
    try {
      var value = JSON.parse(text);
      return value === null || value === undefined ? fallback : value;
    } catch (_) {
      return fallback;
    }
  }

  function rebuildIndexes() {
    skinPreview = {};
    skinById = {};
    var skins = Array.isArray(cache.skins) ? cache.skins : [];
    for (var i = 0; i < skins.length; i++) {
      skinById[skins[i].id] = skins[i];
    }
    var full = cache.full && Array.isArray(cache.full.skins) ? cache.full.skins : [];
    for (var j = 0; j < full.length; j++) {
      var skin = full[j];
      skinById[skin.id] = Object.assign({}, skinById[skin.id] || {}, skin);
      if (skin.preview) skinPreview[skin.id] = skin.preview;
    }
  }

  function applyBoot(boot) {
    if (!boot || typeof boot !== 'object') return;
    cache.prefs = boot.prefs || {};
    cache.pet = boot.pet || {};
    cache.phrases = Array.isArray(boot.phrases) ? boot.phrases : [];
    cache.skins = Array.isArray(boot.skins) ? boot.skins : [];
    cache.full = boot.full || { active: 'builtin', activeIds: ['builtin'], skins: [] };
    if (!Array.isArray(cache.full.activeIds) || !cache.full.activeIds.length) {
      cache.full.activeIds = [cache.full.active || 'builtin'];
    }
    cache.running = !!boot.running;
    cache.autostart = !!boot.autostart;
    cache.configDir = boot.configDir || '';
    cache.skinsDir = boot.skinsDir || '';
    cache.settingsPath = boot.settingsPath || '';
    cache.poopsCleaned = Number(boot.poopsCleaned) || 0;
    cache.settingsVisible = !!boot.settingsVisible;
    clearJsonCache();
    rebuildIndexes();
  }

  function refreshCache() {
    if (refreshPromise) return refreshPromise;
    refreshPromise = invoke('get_boot_state').then(function (boot) {
      applyBoot(boot);
      refreshPromise = null;
      return cache;
    }).catch(function (err) {
      refreshPromise = null;
      throw err;
    });
    return refreshPromise;
  }

  function errorText(err) {
    if (typeof err === 'string') return err;
    if (err && typeof err.message === 'string') return err.message;
    return String(err);
  }

  function uiAlert(title, message) {
    if (window.GUPetUI && typeof window.GUPetUI.alert === 'function') {
      window.GUPetUI.alert(title, message);
    } else {
      window.alert((title ? title + ': ' : '') + message);
    }
  }

  function runCommand(command, args, title) {
    return invoke(command, args || {}).catch(function (err) {
      uiAlert(title || 'GUPet', errorText(err));
      throw err;
    });
  }

  function readFileBase64(file, callback) {
    var reader = new FileReader();
    reader.onerror = function () { callback(new Error('Не удалось прочитать файл')); };
    reader.onload = function () {
      var result = String(reader.result || '');
      var comma = result.indexOf(',');
      callback(null, comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.readAsDataURL(file);
  }

  function fileInput(accept) {
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.style.display = 'none';
    document.body.appendChild(input);
    return input;
  }

  var AB = {
    getString: function (key) {
      if (key === 'autostart') return cache.autostart ? 'on' : 'off';
      var value = cache.prefs[key];
      return value === undefined ? null : String(value);
    },
    setString: function (key, value) {
      if (key === 'autostart') {
        var enabled = value === 'on' || value === '1' || value === 'true';
        cache.autostart = enabled;
        invoke('set_autostart', { enabled: enabled }).then(function (actual) {
          cache.autostart = !!actual;
        }).catch(function (err) {
          uiAlert('Автозапуск', errorText(err));
        });
        return;
      }
      cache.prefs[key] = String(value);
      invoke('set_pref', { key: key, value: String(value) }).catch(function (err) {
        console.error('set_pref failed', key, err);
      });
    },
    setSystemBarsColor: function () {},
    getStatusBarHeightPx: function () { return 0; },
    getPetSettings: function () {
      if (jsonCache.pet === null) jsonCache.pet = stringify(cache.pet);
      return jsonCache.pet;
    },
    setPetSettings: function (json) {
      var value = typeof json === 'string' ? parse(json, null) : json;
      if (!value || typeof value !== 'object') return;
      cache.pet = Object.assign({}, cache.pet, value);
      jsonCache.pet = null;
      invoke('set_pet_settings', { value: value }).catch(function (err) {
        console.error('set_pet_settings failed', err);
      });
    },
    getPhrases: function () {
      if (jsonCache.phrases === null) jsonCache.phrases = stringify(cache.phrases);
      return jsonCache.phrases;
    },
    setPhrases: function (json) {
      var value = typeof json === 'string' ? parse(json, []) : json;
      if (!Array.isArray(value)) return;
      cache.phrases = value;
      jsonCache.phrases = null;
      invoke('set_phrases', { value: value }).catch(function (err) {
        console.error('set_phrases failed', err);
      });
    }
  };

  var PB = {
    start: function () {
      cache.running = true;
      invoke('set_running', { running: true }).catch(function (err) { uiAlert('GUPet', errorText(err)); });
    },
    stop: function () {
      cache.running = false;
      invoke('set_running', { running: false }).catch(function (err) { uiAlert('GUPet', errorText(err)); });
    },
    isRunning: function () { return !!cache.running; },
    hasOverlayPermission: function () { return true; },
    hasNotificationAccess: function () { return true; },
    hasPostNotifications: function () { return true; },
    openOverlaySettings: function () {},
    openNotificationSettings: function () {},
    requestPostNotifications: function () {},

    listSkins: function () {
      if (jsonCache.skins === null) jsonCache.skins = stringify(cache.skins);
      return jsonCache.skins;
    },
    listFullSkins: function () {
      if (jsonCache.full === null) jsonCache.full = stringify(cache.full);
      return jsonCache.full;
    },
    getSkinPreviewUrl: function (id) { return skinPreview[id] || ''; },
    getSkin: function (id) {
      if (id === 'builtin') {
        return stringify({
          id: 'builtin',
          name: 'Стандартный GUPet',
          author: 'GUTeam',
          builtin: true,
          hasSettings: true,
          hasFrames: false,
          preview: '',
          revision: 0,
          settings: null,
          pcSettings: defaultPcSettings(),
          frames: {}
        });
      }
      if (!jsonCache.bySkin[id]) {
        jsonCache.bySkin[id] = stringify(
          skinById[id] || {
            id: id,
            name: '',
            author: '',
            builtin: false,
            hasFrames: false,
            pcSettings: defaultPcSettings(),
            frames: {}
          }
        );
      }
      return jsonCache.bySkin[id];
    },
    getPcSettings: function (id) {
      var skin = skinById[id];
      return stringify(
        skin && skin.pcSettings && typeof skin.pcSettings === 'object'
          ? skin.pcSettings
          : defaultPcSettings()
      );
    },
    setPcSettings: function (id, json) {
      var value = typeof json === 'string' ? parse(json, null) : json;
      if (!value || typeof value !== 'object') return;
      var skin = skinById[id];
      if (skin) skin.pcSettings = value;
      var fullSkin = ((cache.full && cache.full.skins) || []).filter(function (entry) { return entry.id === id; })[0];
      if (fullSkin) fullSkin.pcSettings = value;
      jsonCache.full = null;
      jsonCache.bySkin[id] = null;
      invoke('set_pc_settings', { id: id, value: value }).catch(function (err) {
        console.error('set_pc_settings failed', err);
      });
    },
    listOpenApps: function () {
      return invoke('list_open_apps');
    },
    getSkinSettings: function (id) {
      if (id === 'builtin') {
        return stringify(Object.assign({}, cache.pet, { phrases: cache.phrases }));
      }
      var skin = skinById[id];
      return stringify(skin && skin.settings ? skin.settings : {});
    },
    selectSkin: function (id) {
      var ids = [id];
      cache.full.active = id;
      cache.full.activeIds = ids;
      cache.pet.customSkinId = id === 'builtin' ? null : id;
      invoke('select_skin', { id: id }).then(function () {
        return refreshCache();
      }).catch(function (err) {
        console.error('select_skin failed', err);
      });
    },
    setActiveSkinIds: function (json) {
      var ids = typeof json === 'string' ? parse(json, []) : json;
      if (!Array.isArray(ids) || !ids.length) ids = ['builtin'];
      cache.full.activeIds = ids.slice();
      cache.full.active = ids[0];
      invoke('set_active_skin_ids', { ids: ids }).then(function (valid) {
        if (Array.isArray(valid) && valid.length) {
          cache.full.activeIds = valid.slice();
          cache.full.active = valid[0];
        }
        return refreshCache();
      }).catch(function (err) {
        console.error('set_active_skin_ids failed', err);
      });
    },
    importSkin: function () {
      var input = fileInput('.gupet,application/zip');
      input.addEventListener('change', function () {
        var file = input.files && input.files[0];
        input.remove();
        if (!file) return;
        if (file.size > 8 * 1024 * 1024) {
          uiAlert('Скин', 'Архив слишком большой');
          return;
        }
        readFileBase64(file, function (readErr, base64) {
          if (readErr) {
            uiAlert('Скин', readErr.message || String(readErr));
            return;
          }
          invoke('import_skin_base64', { filename: file.name, data: base64 }).then(function (info) {
            return refreshCache().then(function () {
              if (typeof window.onSkinImported === 'function') window.onSkinImported(info && info.name, true);
            });
          }).catch(function (err) {
            uiAlert('Импорт скина', errorText(err));
            if (typeof window.onSkinImported === 'function') window.onSkinImported('', false);
          });
        });
      }, { once: true });
      input.click();
    },
    exportSkin: function (id) {
      invoke('export_skin', { id: id }).then(function () {
        if (typeof window.onSkinExported === 'function') window.onSkinExported(true);
      }).catch(function (err) {
        uiAlert('Экспорт скина', errorText(err));
        if (typeof window.onSkinExported === 'function') window.onSkinExported(false);
      });
    },
    deleteSkin: function (id) {
      cache.skins = (cache.skins || []).filter(function (skin) { return skin.id !== id; });
      cache.full.skins = (cache.full.skins || []).filter(function (skin) { return skin.id !== id; });
      cache.full.activeIds = (cache.full.activeIds || []).filter(function (active) { return active !== id; });
      if (!cache.full.activeIds.length) cache.full.activeIds = ['builtin'];
      cache.full.active = cache.full.activeIds[0];
      invoke('delete_skin', { id: id }).then(function () {
        return refreshCache();
      }).catch(function (err) {
        console.error('delete_skin failed', err);
        refreshCache().catch(function () {});
      });
    },
    deleteFullSkin: function (id) {
      PB.deleteSkin(id);
    },
    createSkin: null,
    duplicateSkin: function (id) {
      invoke('duplicate_skin', { sourceId: id }).then(function (newId) {
        return refreshCache().then(function () {
          if (typeof window.onSkinDuplicated === 'function') window.onSkinDuplicated(newId || '');
        });
      }).catch(function (err) {
        uiAlert('Скин', errorText(err));
      });
    },
    saveSkinMeta: function (id, name, author) {
      var skin = skinById[id];
      if (skin && !skin.builtin) {
        skin.name = name;
        skin.author = author;
        var info = (cache.skins || []).filter(function (entry) { return entry.id === id; })[0];
        if (info) { info.name = name; info.author = author; }
        var fullSkin = ((cache.full && cache.full.skins) || []).filter(function (entry) { return entry.id === id; })[0];
        if (fullSkin) { fullSkin.name = name; fullSkin.author = author; }
      }
      jsonCache.skins = null;
      jsonCache.full = null;
      jsonCache.bySkin[id] = null;
      invoke('save_skin_meta', { id: id, name: name, author: author }).catch(function (err) {
        console.error('save_skin_meta failed', err);
      });
    },
    setSkinSettings: function (id, json) {
      var value = parse(json, {});
      var skin = skinById[id];
      if (skin) skin.settings = value;
      var fullSkin = ((cache.full && cache.full.skins) || []).filter(function (entry) { return entry.id === id; })[0];
      if (fullSkin) fullSkin.settings = value;
      jsonCache.full = null;
      jsonCache.bySkin[id] = null;
      invoke('set_skin_settings', { id: id, value: value }).catch(function (err) {
        console.error('set_skin_settings failed', err);
      });
    },
    snapshotSkin: function (id) {
      invoke('snapshot_skin', { id: id }).catch(function (err) {
        console.error('snapshot_skin failed', err);
      });
    },
    getFrameInfo: function (id, frame) {
      var skin = skinById[id];
      var info = skin && skin.frames ? skin.frames[frame] : null;
      return stringify(info || { exists: false, fileName: '' });
    },
    pickFrame: function (id, frame) {
      var input = fileInput('image/png,.png');
      input.addEventListener('change', function () {
        var file = input.files && input.files[0];
        input.remove();
        if (!file) return;
        if (file.size > 512 * 1024) {
          uiAlert('Кадр', 'PNG должен быть не больше 512 КБ');
          return;
        }
        readFileBase64(file, function (readErr, base64) {
          if (readErr) {
            uiAlert('Кадр', readErr.message || String(readErr));
            return;
          }
          invoke('set_frame_base64', { id: id, frame: frame, data: base64 }).then(function () {
            return refreshCache().then(function () {
              if (typeof window.onFrameUploaded === 'function') window.onFrameUploaded(id, frame, true);
            });
          }).catch(function (err) {
            uiAlert('Кадр', errorText(err));
            if (typeof window.onFrameUploaded === 'function') window.onFrameUploaded(id, frame, false);
          });
        });
      }, { once: true });
      input.click();
    },
    deleteFrame: function (id, frame) {
      var skin = skinById[id];
      if (skin && skin.frames) skin.frames[frame] = { exists: false, fileName: '' };
      invoke('delete_frame', { id: id, frame: frame }).then(function () {
        return refreshCache().then(function () {
          if (typeof window.onFrameDeleted === 'function') window.onFrameDeleted(id, frame);
        });
      }).catch(function (err) {
        uiAlert('Кадр', errorText(err));
      });
    },
    callPoop: function () { invoke('emit_poop').catch(function () {}); },
    cleanPoops: function () { invoke('clean_poops').catch(function () {}); },
    testPhrase: function (text) { invoke('test_phrase', { text: String(text) }).catch(function () {}); }
  };

  window.AndroidPrefs = AB;
  window.AndroidPet = PB;
  window.GUPetBridge = {
    cache: cache,
    refresh: refreshCache,
    invoke: invoke,
    createSkin: function (name, author) {
      return invoke('create_skin', { name: String(name || ''), author: String(author || '') }).then(function (result) {
        return refreshCache().then(function () { return result; });
      });
    }
  };

  window.GUPET_BRIDGE_READY = refreshCache().then(function () {
    listen('gupet://state', function () {
      // Несколько команд подряд (имя + настройки + ПК) приходят одним пакетом.
      if (stateRefreshTimer) clearTimeout(stateRefreshTimer);
      stateRefreshTimer = setTimeout(function () {
        stateRefreshTimer = null;
        refreshCache().then(function () {
          window.dispatchEvent(new CustomEvent('gupet-state'));
        }).catch(function () {});
      }, 150);
    }).catch(function () {});
    listen('gupet://poop-now', function () { /* overlay owns the event */ }).catch(function () {});
    listen('gupet://clean-poops', function () { }).catch(function () {});
    listen('gupet://test-phrase', function () { }).catch(function () {});
    listen('gupet://recenter', function () { }).catch(function () {});
    listen('gupet://music', function () { }).catch(function () {});
    listen('gupet://notification', function () { }).catch(function () {});
    invoke('ui_ready', { report: { viewport: [window.innerWidth, window.innerHeight], dpr: window.devicePixelRatio || 1 } }).catch(function () {});
    return cache;
  }).catch(function (err) {
    console.error('GUPet bridge initialization failed', err);
    throw err;
  });

  document.addEventListener('click', function (event) {
    var anchor = event.target && event.target.closest ? event.target.closest('a[href]') : null;
    if (!anchor) return;
    var href = anchor.getAttribute('href') || '';
    if (/^https?:\/\//i.test(href)) {
      event.preventDefault();
      invoke('open_url', { url: href }).catch(function () {});
    }
  }, true);

  window.open = function (url) {
    if (typeof url === 'string' && /^https?:\/\//i.test(url)) {
      invoke('open_url', { url: url }).catch(function () {});
    }
    return null;
  };
})();
