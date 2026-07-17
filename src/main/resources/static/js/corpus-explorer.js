(() => {
  const rows = [...document.querySelectorAll('#corpus-table tbody tr')];
  const search = document.querySelector('#search');
  const intake = document.querySelector('#intake');
  const language = document.querySelector('#language');
  const state = document.querySelector('#state');
  const summary = document.querySelector('#page-summary');
  const previous = document.querySelector('#page-previous');
  const next = document.querySelector('#page-next');
  const vi = document.documentElement.lang === 'vi';
  const pageSize = 50;
  let page = 0;

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

  [...new Set(rows.map(row => row.dataset.state))].sort().forEach(value => {
    state.add(new Option(labels[value] || value, value));
  });
  rows.forEach(row => {
    const badge = row.querySelector('.state');
    if (badge) badge.textContent = labels[row.dataset.state] || row.dataset.state;
  });

  function matches(row) {
    const query = search.value.trim().toLowerCase();
    return (!query || row.dataset.search.includes(query))
      && (!intake.value || row.dataset.intake === intake.value)
      && (!language.value || row.dataset.language === language.value)
      && (!state.value || row.dataset.state === state.value);
  }

  function render() {
    const filtered = rows.filter(matches);
    const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
    page = Math.min(page, pageCount - 1);
    const start = page * pageSize;
    const end = Math.min(start + pageSize, filtered.length);
    rows.forEach(row => { row.hidden = true; });
    filtered.slice(start, end).forEach(row => { row.hidden = false; });
    summary.textContent = filtered.length === 0
      ? (vi ? 'Không có tài liệu phù hợp' : 'No matching documents')
      : (vi ? `Hiển thị ${start + 1}–${end} / ${filtered.length} tài liệu`
        : `Showing ${start + 1}–${end} of ${filtered.length} documents`);
    previous.disabled = page === 0;
    next.disabled = page >= pageCount - 1;
  }

  [search, intake, language, state].forEach(control => {
    control.addEventListener(control === search ? 'input' : 'change', () => { page = 0; render(); });
  });
  previous.addEventListener('click', () => { if (page > 0) { page -= 1; render(); } });
  next.addEventListener('click', () => { page += 1; render(); });
  document.querySelector('#clear').addEventListener('click', () => {
    search.value = ''; intake.value = ''; language.value = ''; state.value = ''; page = 0; render();
  });
  render();
})();
