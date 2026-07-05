/**
 * Client-only gate for the 3 pipeline-config pages (Sources, Classifications,
 * Dedup) — Maker/Checker never see these (hidden entirely, not just
 * disabled), matching the Role Matrix. No server enforcement (see roles.js).
 */
(function () {
  document.addEventListener('DOMContentLoaded', function () {
    var session = window.MR_ROLES.requireSession();
    if (!session) return;
    var role = window.MR_ROLES.roleDef(session);
    var content = document.getElementById('pipeline-content');
    var denied = document.getElementById('pipeline-denied');
    if (!role.canViewPipelineConfig) {
      if (content) content.style.display = 'none';
      if (denied) denied.style.display = 'block';
    }
  });
})();
