(() => {
  const vi = document.documentElement.lang === 'vi';
  const labels = {
    IMPORT_FAILED: vi ? 'Nhập thất bại' : 'Import failed',
    DUPLICATE: vi ? 'Bản trùng' : 'Duplicate',
    WAITING_FOR_CLASSIFICATION: vi ? 'Chờ phân loại' : 'Awaiting classification',
    OUT_OF_SCOPE: vi ? 'Ngoài phạm vi' : 'Out of scope',
    HELD_FOR_REVIEW: vi ? 'Chờ xem xét' : 'Held for review',
    WAITING_FOR_EXTRACTION: vi ? 'Chờ trích xuất' : 'Awaiting extraction',
    EVIDENCE_EXTRACTED: vi ? 'Đã trích xuất bằng chứng' : 'Evidence extracted',
    WAITING_FOR_VERIFICATION: vi ? 'Chờ kiểm chứng' : 'Awaiting verification',
    PARTIALLY_VERIFIED: vi ? 'Đã kiểm chứng một phần' : 'Partially verified',
    REPORT_ELIGIBLE: vi ? 'Đủ điều kiện báo cáo' : 'Report eligible'
  };
  document.querySelectorAll('.state[data-state]').forEach(badge => {
    badge.textContent = labels[badge.dataset.state] || badge.dataset.state;
  });
})();
