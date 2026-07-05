/**
 * Market Radar Ops Console — role/session model. Plain global, no build step,
 * no real auth: this is a client-side-only prototype (localStorage session).
 * Real backend endpoints (POST /review/{id}/approve etc.) are UNCHANGED and
 * remain unauthenticated — this module only drives what the UI shows/enables.
 */
(function () {
  var DEPARTMENTS = ['Compliance', 'Product', 'Legal'];

  var ROLE_DEFS = {
    maker: {
      id: 'maker', label: 'Maker', deptScoped: true,
      canRunJobs: true, canViewPipelineConfig: false,
      canApprove: false, canEditApprove: false, canForceApprove: false, canReject: false, canEditOnly: true,
    },
    checker: {
      id: 'checker', label: 'Checker', deptScoped: true,
      canRunJobs: false, canViewPipelineConfig: false,
      canApprove: true, canEditApprove: true, canForceApprove: true, canReject: true, canEditOnly: false,
    },
    admin: {
      id: 'admin', label: 'Compliance Admin', deptScoped: false,
      canRunJobs: true, canViewPipelineConfig: true,
      canApprove: true, canEditApprove: true, canForceApprove: true, canReject: true, canEditOnly: false,
    },
    auditor: {
      id: 'auditor', label: 'Auditor', deptScoped: false,
      canRunJobs: false, canViewPipelineConfig: true,
      canApprove: false, canEditApprove: false, canForceApprove: false, canReject: false, canEditOnly: false,
      readOnly: true,
    },
  };

  var ACCOUNTS = [
    { name: 'Trần Minh', email: 'minh.tran@marketradar-demo.vn', role: 'checker', dept: 'Compliance' },
    { name: 'Phạm Lan', email: 'lan.pham@marketradar-demo.vn', role: 'maker', dept: 'Product' },
    { name: 'Nguyễn An', email: 'an.nguyen@marketradar-demo.vn', role: 'admin', dept: null },
    { name: 'Lê Hoa', email: 'hoa.le@marketradar-demo.vn', role: 'auditor', dept: null },
  ];

  var KEY = 'mr_ops_session';

  function getSession() {
    try { return JSON.parse(localStorage.getItem(KEY)); } catch (e) { return null; }
  }
  function setSession(acc) { localStorage.setItem(KEY, JSON.stringify(acc)); }
  function clearSession() { localStorage.removeItem(KEY); }
  function roleDef(session) { return ROLE_DEFS[(session && session.role) || 'auditor']; }
  function initials(name) {
    return (name || '').split(' ').map(function (p) { return p[0]; }).slice(0, 2).join('').toUpperCase();
  }

  window.MR_ROLES = {
    DEPARTMENTS: DEPARTMENTS, ROLE_DEFS: ROLE_DEFS, ACCOUNTS: ACCOUNTS, KEY: KEY,
    getSession: getSession, setSession: setSession, clearSession: clearSession,
    roleDef: roleDef, initials: initials,
  };

  /**
   * requireSession — call at the top of every ops page (except login).
   * Redirects to login if no session exists.
   */
  window.MR_ROLES.requireSession = function () {
    var s = getSession();
    if (!s) { window.location.href = '/ops/login'; return null; }
    return s;
  };
})();
