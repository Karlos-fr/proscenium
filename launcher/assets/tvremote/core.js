/* TV Remote core 0.2.1 - do not edit here. */
/*
 * TV Remote — page core (format v1). No style here: each app's skin (CSS) gives the look.
 *
 *   <div id="app"></div>
 *   <script src="static/core.js"></script>
 *   <script>TVRemote.start(document.getElementById('app'), { hooks… })</script>
 *
 * DOM produced (classes for the skin):
 *   .tr-root > .tr-tabs (.tr-tab[.tr-active]) + .tr-screen
 *   .tr-screen > .tr-head (.tr-back, .tr-title, .tr-subtitle) + .tr-search? + .tr-items + .tr-more?
 *   .tr-item.tr-<type>[.tr-disabled] > .tr-main (.tr-label, .tr-badge.tr-badge-<kind>, .tr-value / control) + .tr-help
 *   grid: .tr-tiles > .tr-tile[.tr-checked] (img, .tr-tile-label, .tr-badge)
 *   list: .tr-rows > .tr-row[.tr-checked] (img, .tr-row-text > .tr-row-label + .tr-row-sub, .tr-row-moves)
 *   .tr-toast (errors), .tr-offline (connection lost), .tr-dialog (confirm)
 *
 * Hooks (all optional): render[type](item, ctx) → element, state(json), screen(el, screen),
 * confirm(question) → Promise<bool>, words: {back, search, offline, more, ok, cancel, up, down, send,
 * paused, busy, mismatch}.
 */
(function () {
  'use strict';
  var FORMAT = 1;
  var WORDS = {
    en: { back: 'Back', search: 'Search', offline: 'Connection to the TV lost…', more: 'More', ok: 'OK', cancel: 'Cancel', up: 'Up', down: 'Down', send: 'OK', paused: 'Too many wrong passwords: wait a moment.', busy: 'The TV is busy: try again.', mismatch: 'This page does not match the app: reload it.' },
    fr: { back: 'Retour', search: 'Rechercher', offline: 'Connexion à la TV perdue…', more: 'Plus', ok: 'OK', cancel: 'Annuler', up: 'Monter', down: 'Descendre', send: 'OK', paused: 'Trop de mots de passe erronés : patientez un instant.', busy: 'La TV est occupée : réessayez.', mismatch: 'Cette page ne correspond pas à l’app : rechargez-la.' },
    de: { back: 'Zurück', search: 'Suchen', offline: 'Verbindung zum Fernseher verloren…', more: 'Mehr', ok: 'OK', cancel: 'Abbrechen', up: 'Nach oben', down: 'Nach unten', send: 'OK', paused: 'Zu viele falsche Passwörter: bitte kurz warten.', busy: 'Der Fernseher ist beschäftigt: bitte erneut versuchen.', mismatch: 'Diese Seite passt nicht zur App: bitte neu laden.' },
    it: { back: 'Indietro', search: 'Cerca', offline: 'Connessione alla TV persa…', more: 'Altro', ok: 'OK', cancel: 'Annulla', up: 'Su', down: 'Giù', send: 'OK', paused: 'Troppe password errate: attendi un momento.', busy: 'La TV è occupata: riprova.', mismatch: 'Questa pagina non corrisponde all’app: ricaricala.' },
    es: { back: 'Atrás', search: 'Buscar', offline: 'Conexión con la TV perdida…', more: 'Más', ok: 'OK', cancel: 'Cancelar', up: 'Subir', down: 'Bajar', send: 'OK', paused: 'Demasiadas contraseñas erróneas: espera un momento.', busy: 'La TV está ocupada: inténtalo de nuevo.', mismatch: 'Esta página no corresponde a la app: recárgala.' },
    sv: { back: 'Tillbaka', search: 'Sök', offline: 'Anslutningen till TV:n bröts…', more: 'Mer', ok: 'OK', cancel: 'Avbryt', up: 'Upp', down: 'Ner', send: 'OK', paused: 'För många felaktiga lösenord: vänta en stund.', busy: 'TV:n är upptagen: försök igen.', mismatch: 'Sidan matchar inte appen: ladda om den.' }
  };

  var root, hooks, app, words;
  var client = Math.random().toString(16).slice(2) + Date.now().toString(16);
  var stack = [];            // screen ids, current last
  var current = null;        // current screen json
  var busy = 0;              // user is dragging / typing: refresh later
  var pendingRefresh = false;
  var search = '';
  var events = null;

  // ------------------------------------------------------------------ helpers

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function api(path, body) {
    var opt = body === undefined ? {} : {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body), keepalive: true
    };
    return fetch(path, opt).then(function (r) {
      if (r.status === 401) { location.reload(); throw handled(); }      // the browser asks the password again
      if (r.status === 429) { toast(words.paused); throw handled(); }
      if (r.status === 503) { toast(words.busy); throw handled(); }
      if (!r.ok) throw new Error(r.status);
      return r.json();
    });
  }

  /** An error already shown (or the page reloading): not a lost connection. */
  function handled() {
    var e = new Error('handled');
    e.handled = true;
    return e;
  }

  function failed(e) {
    if (!e || !e.handled) toast(words.offline);
  }

  function toast(text) {
    var t = root.querySelector('.tr-toast') || root.appendChild(el('div', 'tr-toast'));
    t.textContent = text;
    t.classList.add('tr-show');
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.classList.remove('tr-show'); }, 4000);
  }

  function format(f, v) {
    if (!f) return String(v);
    return f.replace(/%%/g, '\u0000').replace(/%d/g, Math.round(v)).replace(/%s/g, v).replace(/\u0000/g, '%');
  }

  function confirmBox(question) {
    if (hooks.confirm) return hooks.confirm(question);
    return new Promise(function (done) {
      var d = el('div', 'tr-dialog');
      var box = d.appendChild(el('div', 'tr-dialog-box'));
      box.appendChild(el('p', 'tr-dialog-text', question));
      var row = box.appendChild(el('div', 'tr-dialog-buttons'));
      var no = row.appendChild(el('button', 'tr-button tr-cancel', words.cancel));
      var yes = row.appendChild(el('button', 'tr-button tr-ok', words.ok));
      function close(v) { d.remove(); done(v); }
      no.onclick = function () { close(false); };
      yes.onclick = function () { close(true); };
      root.appendChild(d);
    });
  }

  // ------------------------------------------------------------------ talking to the TV

  function answer(r) {
    if (r.error) toast(r.error);
    if (r.open) return go(r.open);
    if (r.screen && current && r.screen.id === current.id) show(r.screen, true);
  }

  function query() {
    var p = current && current.paged;
    return p ? { q: search, offset: 0, limit: p.offset + p.limit } : {};
  }

  function set(id, value) {
    var b = Object.assign({ client: client, screen: current.id, id: id, value: value }, query());
    return api('api/set', b).then(answer, failed);
  }

  function action(id) {
    var b = Object.assign({ client: client, screen: current.id, id: id }, query());
    return api('api/action', b).then(answer, failed);
  }

  function screenUrl(id, offset, limit) {
    var u = 'api/screen/' + id.split('/').map(encodeURIComponent).join('/');
    var q = [];
    if (search) q.push('q=' + encodeURIComponent(search));
    if (offset) q.push('offset=' + offset);
    if (limit) q.push('limit=' + limit);
    return q.length ? u + '?' + q.join('&') : u;
  }

  function opened(id) { api('api/open', { client: client, screen: id }).catch(function () {}); }
  function closed(id) { api('api/close', { client: client, screen: id }).catch(function () {}); }

  // ------------------------------------------------------------------ navigation

  /** Opens a screen above the current one (or switches tab when it is a tab). */
  function go(id, replace) {
    var isTab = app.tabs.some(function (t) { return t.id === id; });
    var leaving = stack.slice();
    if (isTab || replace) stack = [id]; else stack.push(id);
    search = '';
    return load(id).then(function () {
      leaving.forEach(function (s) { if (stack.indexOf(s) < 0) closed(s); });
      history.replaceState(null, '', '#' + encodeURIComponent(stack.join('|')));
    });
  }

  function back() {
    if (stack.length < 2) return;
    var left = stack.pop();
    search = '';
    load(stack[stack.length - 1]).then(function () {
      closed(left);
      history.replaceState(null, '', '#' + encodeURIComponent(stack.join('|')));
    });
  }

  function load(id, keepScroll) {
    var p = current && current.id === id && current.paged;
    return api(screenUrl(id, 0, p ? p.offset + p.limit : 0)).then(function (s) {
      var first = !current || current.id !== s.id;
      show(s, keepScroll);
      if (first) opened(s.id);
    }, failed);
  }

  function refresh() {
    if (!current) return;
    if (busy) { pendingRefresh = true; return; }
    pendingRefresh = false;
    load(current.id, true);
  }

  // ------------------------------------------------------------------ rendering

  function show(s, keepScroll) {
    var y = window.scrollY;
    current = s;
    renderTabs();
    var box = root.querySelector('.tr-screen');
    var screen = el('section', 'tr-screen');
    screen.dataset.id = s.id;

    var head = screen.appendChild(el('header', 'tr-head'));
    if (stack.length > 1) {
      var b = head.appendChild(el('button', 'tr-back', words.back));
      b.onclick = back;
    }
    head.appendChild(el('h1', 'tr-title', s.title));
    if (s.subtitle) head.appendChild(el('p', 'tr-subtitle', s.subtitle));

    if (s.paged && s.paged.search) {
      var f = screen.appendChild(el('input', 'tr-search'));
      f.type = 'search';
      f.placeholder = words.search;
      f.value = search;
      var timer;
      f.oninput = function () {
        clearTimeout(timer);
        timer = setTimeout(function () {
          search = f.value;
          api(screenUrl(s.id, 0, s.paged.limit)).then(function (n) { show(n); focusSearch(); });
        }, 300);
      };
    }

    var items = screen.appendChild(el('div', 'tr-items'));
    (s.items || []).forEach(function (it) { items.appendChild(item(it)); });

    addMore(screen, s);

    if (box) box.replaceWith(screen); else root.appendChild(screen);
    if (hooks.screen) hooks.screen(screen, s);
    window.scrollTo(0, keepScroll ? y : 0);
  }

  function focusSearch() {
    var f = root.querySelector('.tr-search');
    if (f) { f.focus(); f.setSelectionRange(f.value.length, f.value.length); }
  }

  /** Paged screen with entries left: a "More" button, pressed by itself when it scrolls into view. */
  function addMore(screen, s) {
    var p = s.paged;
    if (!p || p.offset + p.limit >= p.total) return;
    var more = screen.appendChild(el('button', 'tr-more', words.more));
    more.type = 'button';
    var done = false;
    function next() { if (!done) { done = true; more.remove(); loadMore(screen, s); } }
    more.onclick = next;
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (e, o) {
        if (e[0].isIntersecting) { o.disconnect(); next(); }
      }, { rootMargin: '400px' }).observe(more);
    }
  }

  /** Paged screen: the next entries, appended below the others. */
  function loadMore(screen, s) {
    var p = s.paged;
    api(screenUrl(s.id, p.offset + p.limit, p.limit)).then(function (n) {
      if (current !== s) return;
      var items = screen.querySelector('.tr-items');
      (n.items || []).forEach(function (it) { items.appendChild(item(it)); });
      s.items = (s.items || []).concat(n.items || []);
      p.total = n.paged.total;
      p.limit = n.paged.offset + n.paged.limit - p.offset;
      addMore(screen, s);
    });
  }

  function renderTabs() {
    var bar = root.querySelector('.tr-tabs');
    if (!app.tabs.length) return;
    if (!bar) {
      bar = el('nav', 'tr-tabs');
      root.insertBefore(bar, root.firstChild);
      app.tabs.forEach(function (t) {
        var b = bar.appendChild(el('button', 'tr-tab'));
        b.dataset.id = t.id;
        if (t.icon) { var i = b.appendChild(el('img', 'tr-tab-icon')); i.src = t.icon; i.alt = ''; }
        b.appendChild(el('span', 'tr-tab-label', t.title));
        b.onclick = function () { go(t.id); };
      });
    }
    var active = stack[0];
    Array.prototype.forEach.call(bar.children, function (b) {
      b.classList.toggle('tr-active', b.dataset.id === active);
    });
  }

  function badge(b) {
    if (!b || !b.text) return null;
    return el('span', 'tr-badge tr-badge-' + (b.kind || 'none'), b.text);
  }

  function item(it) {
    var r = hooks.render && hooks.render[it.type];
    var ctx = { set: set, action: action, go: go, confirm: confirmBox, el: el, format: format, badge: badge, hold: hold };
    var e = r ? r(it, ctx) : (RENDER[it.type] || RENDER.info)(it, ctx);
    if (!e) return document.createComment(it.type);
    e.classList.add('tr-item', 'tr-' + it.type);
    if (it.enabled === false) e.classList.add('tr-disabled');
    if (it.id) e.dataset.id = it.id;
    if (it.help && !e.querySelector('.tr-help')) e.appendChild(el('p', 'tr-help', it.help));
    return e;
  }

  /** The user is acting on a control: SSE refreshes wait until it is done. */
  function hold(on) {
    busy = Math.max(0, busy + (on ? 1 : -1));
    if (!busy && pendingRefresh) setTimeout(refresh, 300);
  }

  function line(it, ctx) {
    var e = el('div');
    var m = e.appendChild(el('div', 'tr-main'));
    m.appendChild(el('span', 'tr-label', it.label));
    var b = ctx.badge(it.badge);
    if (b) m.appendChild(b);
    return e;
  }

  var RENDER = {
    header: function (it) { return el('h2', null, it.label); },

    info: function (it, ctx) {
      var e = line(it, ctx);
      if (it.value != null) e.firstChild.appendChild(el('span', 'tr-value', String(it.value)));
      return e;
    },

    toggle: function (it, ctx) {
      var e = line(it, ctx);
      var c = e.firstChild.appendChild(el('input', 'tr-switch'));
      c.type = 'checkbox';
      c.setAttribute('role', 'switch');
      c.checked = !!it.value;
      c.disabled = it.enabled === false;
      c.onchange = function () { ctx.set(it.id, c.checked); };
      e.firstChild.onclick = function (ev) { if (ev.target !== c && !c.disabled) { c.checked = !c.checked; c.onchange(); } };
      return e;
    },

    choice: function (it, ctx) {
      var e = line(it, ctx);
      var s = e.firstChild.appendChild(el('select', 'tr-select'));
      s.disabled = it.enabled === false;
      (it.options || []).forEach(function (o, i) {
        var opt = s.appendChild(el('option', null, o.label));
        opt.value = i;
        if (String(o.value) === String(it.value)) s.selectedIndex = i;
      });
      s.onfocus = function () { ctx.hold(true); };
      s.onblur = function () { ctx.hold(false); };
      s.onchange = function () { ctx.set(it.id, it.options[s.selectedIndex].value); };
      return e;
    },

    slider: function (it, ctx) {
      var e = line(it, ctx);
      var v = e.firstChild.appendChild(el('span', 'tr-value', format(it.format, it.value)));
      var s = e.appendChild(el('input', 'tr-range'));
      s.type = 'range';
      s.min = it.min; s.max = it.max; s.step = it.step || 1; s.value = it.value;
      s.disabled = it.enabled === false;
      var last = 0, timer = null, held = false, sent = it.value;
      function fill() {
        var p = (s.value - it.min) / ((it.max - it.min) || 1) * 100;
        s.style.setProperty('--tr-fill', p + '%');
      }
      fill();
      function send() {
        last = Date.now(); timer = null;
        if (Number(s.value) !== sent) { sent = Number(s.value); ctx.set(it.id, sent); }
      }
      s.oninput = function () {
        if (!held) { held = true; ctx.hold(true); }
        v.textContent = format(it.format, Number(s.value));
        fill();
        var wait = 150 - (Date.now() - last);
        if (wait <= 0) send(); else if (!timer) timer = setTimeout(send, wait);
      };
      s.onchange = function () {
        clearTimeout(timer);
        send();
        if (held) { held = false; ctx.hold(false); }
      };
      return e;
    },

    text: function (it, ctx) {
      var e = line(it, ctx);
      if (it.secret && it.isSet) e.firstChild.appendChild(el('span', 'tr-value tr-isset', '••••••'));
      var row = e.appendChild(el('form', 'tr-field'));
      var f = row.appendChild(el('input', 'tr-input'));
      f.type = it.secret ? 'password' : 'text';
      f.autocomplete = 'off';
      f.spellcheck = false;
      f.value = it.secret ? '' : (it.value || '');
      if (it.placeholder) f.placeholder = it.placeholder;
      f.disabled = it.enabled === false;
      var ok = row.appendChild(el('button', 'tr-button tr-send', words.send));
      ok.disabled = f.disabled;
      f.onfocus = function () { ctx.hold(true); };
      f.onblur = function () { ctx.hold(false); };
      row.onsubmit = function (ev) { ev.preventDefault(); f.blur(); ctx.set(it.id, f.value); };
      return e;
    },

    button: function (it, ctx) {
      var e = el('button');
      e.type = 'button';
      e.disabled = it.enabled === false;
      var m = e.appendChild(el('span', 'tr-main'));
      m.appendChild(el('span', 'tr-label', it.label));
      var b = ctx.badge(it.badge);
      if (b) m.appendChild(b);
      if (it.value != null && it.value !== '') m.appendChild(el('span', 'tr-value', String(it.value)));
      if (it.opens) e.classList.add('tr-opens');
      e.onclick = function () {
        if (it.opens) return ctx.go(it.opens);
        (it.confirm ? ctx.confirm(it.confirm) : Promise.resolve(true)).then(function (yes) {
          if (yes) ctx.action(it.id);
        });
      };
      return e;
    },

    grid: function (it, ctx) {
      var e = el('div');
      if (it.label) e.appendChild(el('h3', 'tr-label', it.label));
      var box = e.appendChild(el('div', 'tr-tiles'));
      (it.tiles || []).forEach(function (t) {
        var b = box.appendChild(el('button', 'tr-tile'));
        b.type = 'button';
        b.dataset.id = t.id;
        if (t.checked) b.classList.add('tr-checked');
        b.disabled = t.enabled === false || it.enabled === false;
        if (t.img) { var i = b.appendChild(el('img')); i.loading = 'lazy'; i.src = t.img; i.alt = ''; }
        b.appendChild(el('span', 'tr-tile-label', t.label));
        if (t.sub) b.appendChild(el('span', 'tr-tile-sub', t.sub));
        var bd = ctx.badge(t.badge);
        if (bd) b.appendChild(bd);
        b.onclick = function () {
          if (it.check && it.check !== 'none') ctx.set(it.id + '/' + t.id, !t.checked);
          else if (it.opens || t.opens) ctx.action(it.id + '/' + t.id);
        };
      });
      return e;
    },

    list: function (it, ctx) {
      var e = el('div');
      if (it.label) e.appendChild(el('h3', 'tr-label', it.label));
      var box = e.appendChild(el('div', 'tr-rows'));
      var rows = it.rows || [];
      rows.forEach(function (row, idx) {
        var r = box.appendChild(el('div', 'tr-row'));
        r.dataset.id = row.id;
        if (row.checked) r.classList.add('tr-checked');
        if (row.img) { var i = r.appendChild(el('img')); i.loading = 'lazy'; i.src = row.img; i.alt = ''; }
        var t = r.appendChild(el('div', 'tr-row-text'));
        t.appendChild(el('span', 'tr-row-label', row.label));
        if (row.sub) t.appendChild(el('span', 'tr-row-sub', row.sub));
        var bd = ctx.badge(row.badge);
        if (bd) r.appendChild(bd);
        if (it.check && it.check !== 'none') {
          t.onclick = function () { ctx.set(it.id + '/' + row.id, !row.checked); };
        } else if (it.opens) {
          t.onclick = function () { ctx.action(it.id + '/' + row.id); };
        }
        if (it.order) {
          var mv = r.appendChild(el('span', 'tr-row-moves'));
          [[-1, '▲', words.up], [1, '▼', words.down]].forEach(function (d) {
            var b = mv.appendChild(el('button', 'tr-move', d[1]));
            b.type = 'button';
            b.title = d[2];
            b.setAttribute('aria-label', d[2] + ' ' + row.label);
            var to = idx + d[0];
            b.disabled = to < 0 || to >= rows.length;
            b.onclick = function () {
              var ids = rows.map(function (x) { return x.id; });
              ids.splice(to, 0, ids.splice(idx, 1)[0]);
              ctx.set(it.id, ids);
            };
          });
        }
      });
      return e;
    }
  };

  // ------------------------------------------------------------------ live events

  function listen() {
    if (!window.EventSource) return;
    events = new EventSource('api/events?client=' + client);
    var offline = null;
    events.onopen = function () {
      if (offline) { offline.remove(); offline = null; }
      stack.forEach(opened);           // the TV forgot this phone's screens if the line dropped
      refresh();
    };
    events.onerror = function () {
      if (!offline) offline = root.appendChild(el('div', 'tr-offline', words.offline));
      // The password changed on the TV: EventSource cannot tell, a plain request can
      fetch('api/app').then(function (r) { if (r.status === 401) location.reload(); }, function () {});
    };
    events.addEventListener('changed', function (ev) {
      var d = JSON.parse(ev.data);
      if (d.by === client) return;
      if (current && (d.screen === '*' || d.screen === current.id)) refresh();
    });
    events.addEventListener('state', function (ev) {
      var d = JSON.parse(ev.data);
      if (hooks.state) hooks.state(d);
      root.dispatchEvent(new CustomEvent('tvremote:state', { detail: d }));
    });
    events.addEventListener('bye', function () {
      events.close();
      if (!offline) offline = root.appendChild(el('div', 'tr-offline', words.offline));
    });
  }

  window.addEventListener('pagehide', function () { stack.forEach(closed); });

  // ------------------------------------------------------------------ start

  function start(element, h) {
    root = element;
    root.classList.add('tr-root');
    hooks = h || {};
    return api('api/app').then(function (a) {
      app = a;
      app.tabs = app.tabs || [];
      words = Object.assign({}, WORDS.en, WORDS[(a.lang || 'en').slice(0, 2)] || {}, hooks.words || {});
      document.documentElement.lang = a.lang || 'en';
      if (a.theme && a.theme.accent) document.documentElement.style.setProperty('--tr-accent', a.theme.accent);
      if (a.format !== FORMAT) { toast(words.mismatch); }
      var saved = decodeURIComponent(location.hash.slice(1)).split('|').filter(Boolean);
      stack = saved.length ? saved : [a.root];
      return load(stack[stack.length - 1]).then(function () {
        stack.slice(0, -1).forEach(opened);
        listen();
        return a;
      });
    });
  }

  window.TVRemote = {
    start: start, go: go, back: back, set: set, action: action, refresh: refresh,
    get app() { return app; }, get screen() { return current; }, client: client, render: RENDER
  };
})();
