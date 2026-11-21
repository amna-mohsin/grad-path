/* GradPath AI - shared page behaviour (no inline scripts, CSP friendly). */
(function () {
  'use strict';

  const GP = (window.GP = window.GP || {});

  /** CSRF token for fetch() calls (read from the meta tags rendered by Thymeleaf). */
  GP.csrfHeaders = function () {
    const token = document.querySelector('meta[name="_csrf"]');
    const header = document.querySelector('meta[name="_csrf_header"]');
    const h = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (token && header) { h[header.content] = token.content; }
    return h;
  };

  GP.postJson = async function (url, body) {
    const res = await fetch(url, { method: 'POST', headers: GP.csrfHeaders(), body: JSON.stringify(body), credentials: 'same-origin' });
    let data = {};
    try { data = await res.json(); } catch (e) { /* non-JSON error page */ }
    if (!res.ok) {
      const msg = data.error || (res.status === 401 ? 'Your session has ended. Please sign in again.'
        : res.status === 403 ? 'This form has expired. Please reload the page.' : 'Something went wrong. Please try again.');
      throw new Error(msg);
    }
    return data;
  };

  GP.escape = function (s) {
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  };

  /** Convert <time datetime> UTC timestamps to the viewer's local time. */
  function localTimes(root) {
    const fmt = new Intl.DateTimeFormat(undefined, { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    (root || document).querySelectorAll('time[datetime]').forEach(t => {
      const d = new Date(t.getAttribute('datetime'));
      if (!isNaN(d)) { t.textContent = fmt.format(d); t.title = d.toISOString(); }
    });
  }
  GP.localTimes = localTimes;

  document.addEventListener('DOMContentLoaded', () => {
    localTimes();

    // Reveal or mask password fields on the public sign-in and registration forms.
    document.querySelectorAll('[data-password-toggle]').forEach(button => {
      const wrapper = button.closest('[data-password-field]');
      const input = wrapper && wrapper.querySelector('input[type=password], input[type=text]');
      if (!input) { return; }
      button.addEventListener('click', () => {
        const visible = input.type === 'password';
        input.type = visible ? 'text' : 'password';
        button.setAttribute('aria-label', (visible ? 'Hide ' : 'Show ') + button.dataset.passwordToggle);
        button.setAttribute('aria-pressed', String(visible));
      });
    });

    // Mobile navigation drawer.
    document.querySelectorAll('[data-nav-toggle]').forEach(btn => btn.addEventListener('click', () => {
      const open = document.body.classList.toggle('nav-open');
      btn.setAttribute('aria-expanded', String(open));
    }));
    document.addEventListener('click', e => {
      if (document.body.classList.contains('nav-open') && !e.target.closest('.sidebar') && !e.target.closest('[data-nav-toggle]')) {
        document.body.classList.remove('nav-open');
      }
    });

    // Confirmation for destructive actions: <form data-confirm="...">.
    document.querySelectorAll('form[data-confirm]').forEach(f => f.addEventListener('submit', e => {
      if (!window.confirm(f.dataset.confirm)) { e.preventDefault(); }
    }));

    // Auto-submit selects/checkboxes: <select data-autosubmit>.
    document.querySelectorAll('[data-autosubmit]').forEach(el => el.addEventListener('change', () => el.form && el.form.submit()));

    // Client-side tabs: <div data-tabs> with buttons [data-tab] and panels [data-panel].
    document.querySelectorAll('[data-tabs]').forEach(group => {
      const buttons = group.querySelectorAll('[data-tab]');
      const show = name => {
        buttons.forEach(b => { const on = b.dataset.tab === name; b.classList.toggle('active', on); b.setAttribute('aria-selected', String(on)); });
        group.querySelectorAll('[data-panel]').forEach(p => { p.hidden = p.dataset.panel !== name; });
      };
      buttons.forEach(b => b.addEventListener('click', () => show(b.dataset.tab)));
      show(group.dataset.tabs || (buttons[0] && buttons[0].dataset.tab));
    });

    // Disable submit buttons after submit to prevent double posts; show a busy label.
    document.querySelectorAll('form').forEach(f => f.addEventListener('submit', e => {
      if (e.defaultPrevented) { return; }
      const btn = f.querySelector('button[type=submit][data-busy]');
      if (btn) { btn.disabled = true; btn.textContent = btn.dataset.busy; }
    }));

    // Live character counters: <textarea data-count="id-of-counter">.
    document.querySelectorAll('[data-count]').forEach(t => {
      const out = document.getElementById(t.dataset.count);
      const update = () => { if (out) { out.textContent = t.value.length + ' / ' + (t.maxLength > 0 ? t.maxLength : ''); } };
      t.addEventListener('input', update); update();
    });

    // Range inputs mirror their value into an output element: <input type=range data-out="id">.
    document.querySelectorAll('input[type=range][data-out]').forEach(r => {
      const out = document.getElementById(r.dataset.out);
      const update = () => { if (out) { out.textContent = r.value; } };
      r.addEventListener('input', update); update();
    });

    // A skill cannot be both "confident" and "learning": uncheck the other list.
    document.querySelectorAll('[data-exclusive]').forEach(box => box.addEventListener('change', () => {
      if (!box.checked) { return; }
      const other = document.querySelector('[data-exclusive="' + box.dataset.exclusive + '"][data-list="' + (box.dataset.list === 'skills' ? 'learning' : 'skills') + '"]');
      if (other) { other.checked = false; }
    }));
  });
})();
