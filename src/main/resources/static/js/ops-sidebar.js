/**
 * Market Radar Ops Console — sidebar behavior. The sidebar markup itself is
 * server-rendered (fragments/ops-sidebar.html) with real data (queue count,
 * nav hrefs); this script layers the client-only session/role concerns on
 * top: who's "logged in" (localStorage), hiding Pipeline Config for
 * maker/checker, and the account switcher.
 */
(function () {
  document.addEventListener('DOMContentLoaded', function () {
    var session = window.MR_ROLES.requireSession();
    if (!session) return;
    var role = window.MR_ROLES.roleDef(session);

    var nameEl = document.getElementById('ops-account-name');
    var roleEl = document.getElementById('ops-account-role');
    var avatarEl = document.getElementById('ops-avatar');
    if (nameEl) nameEl.textContent = session.name;
    if (avatarEl) avatarEl.textContent = window.MR_ROLES.initials(session.name);
    if (roleEl) {
      roleEl.textContent = role.label + (session.dept ? ' · ' + session.dept : ' · ' + (window.MR_I18N ? window.MR_I18N.t('common.allDepts') : 'All departments'));
    }

    if (!role.canViewPipelineConfig) {
      document.querySelectorAll('[data-nav-group="pipeline"]').forEach(function (el) { el.style.display = 'none'; });
    }

    var toggle = document.getElementById('ops-account-toggle');
    var switcher = document.getElementById('ops-switcher');
    if (toggle && switcher) {
      toggle.addEventListener('click', function () { switcher.classList.toggle('is-open'); });
    }
    document.querySelectorAll('.ops-switcher-item').forEach(function (item) {
      item.addEventListener('click', function () {
        var acc = window.MR_ROLES.ACCOUNTS.find(function (a) { return a.email === item.dataset.email; });
        if (acc) { window.MR_ROLES.setSession(acc); window.location.reload(); }
      });
    });
    var signOut = document.getElementById('ops-signout');
    if (signOut) signOut.addEventListener('click', function () {
      window.MR_ROLES.clearSession();
      window.location.href = '/ops/login';
    });

    // Elements marked data-role-hide="maker,checker" are hidden for those roles.
    document.querySelectorAll('[data-role-hide]').forEach(function (el) {
      var hideFor = el.dataset.roleHide.split(',');
      if (hideFor.indexOf(role.id) !== -1) el.style.display = 'none';
    });
    // Elements marked data-role-show="admin,auditor" are shown ONLY for those roles.
    document.querySelectorAll('[data-role-show]').forEach(function (el) {
      var showFor = el.dataset.roleShow.split(',');
      if (showFor.indexOf(role.id) === -1) el.style.display = 'none';
    });
  });
})();
