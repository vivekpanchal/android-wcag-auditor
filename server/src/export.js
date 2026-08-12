function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function csvField(s) {
  const v = String(s ?? '');
  return /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
}

function toCsv(issues) {
  const header = ['id', 'packageName', 'screen', 'timestamp', 'severity', 'wcagSC', 'wcagLevel', 'elementDescription', 'description', 'suggestedFix'];
  const rows = issues.map((i) => header.map((h) => csvField(i[h])).join(','));
  return [header.join(','), ...rows].join('\n');
}

function toHtml(issues) {
  const groups = new Map();
  for (const issue of issues) {
    const key = `${issue.packageName || 'unknown'} — ${issue.screen || 'unknown screen'}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(issue);
  }

  const sections = [...groups.entries()].map(([screen, screenIssues]) => `
    <section>
      <h2>${escapeHtml(screen)}</h2>
      <p class="count">${screenIssues.length} issue(s)</p>
      ${screenIssues.map((i) => `
        <div class="issue severity-${escapeHtml(i.severity || 'unknown')}">
          <div class="issue-header">
            <span class="badge severity">${escapeHtml(i.severity)}</span>
            <span class="badge wcag">WCAG ${escapeHtml(i.wcagSC)} (${escapeHtml(i.wcagLevel)})</span>
            <span class="timestamp">${new Date(i.timestamp).toLocaleString()}</span>
          </div>
          <p class="element"><strong>Element:</strong> ${escapeHtml(i.elementDescription)}</p>
          <p class="description">${escapeHtml(i.description)}</p>
          ${i.suggestedFix ? `<p class="fix"><strong>Suggested fix:</strong> ${escapeHtml(i.suggestedFix)}</p>` : ''}
          ${i.screenshot ? `
          <div class="screenshot-wrap">
            <img class="screenshot" src="data:image/png;base64,${i.screenshot}" alt="Screenshot of ${escapeHtml(i.elementDescription)}" />
            ${i.bounds ? `<div class="screenshot-highlight" data-bounds="${i.bounds.x},${i.bounds.y},${i.bounds.width},${i.bounds.height}"></div>` : ''}
          </div>` : ''}
        </div>
      `).join('')}
    </section>
  `).join('');

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<title>Accessibility Audit Report</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 900px; margin: 2rem auto; padding: 0 1rem; color: #1a1a1a; }
  h1 { margin-bottom: 0.25rem; }
  .meta { color: #666; margin-bottom: 2rem; }
  section { margin-bottom: 2.5rem; }
  h2 { border-bottom: 2px solid #ddd; padding-bottom: 0.25rem; }
  .count { color: #666; font-size: 0.9rem; }
  .issue { border: 1px solid #ddd; border-radius: 8px; padding: 1rem; margin: 0.75rem 0; }
  .issue.severity-critical { border-left: 5px solid #d32f2f; }
  .issue.severity-serious { border-left: 5px solid #f57c00; }
  .issue.severity-moderate { border-left: 5px solid #fbc02d; }
  .issue.severity-minor { border-left: 5px solid #689f38; }
  .badge { display: inline-block; padding: 0.15rem 0.5rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; margin-right: 0.5rem; }
  .badge.severity { background: #eee; }
  .badge.wcag { background: #e3f2fd; color: #0d47a1; }
  .timestamp { color: #999; font-size: 0.8rem; }
  .screenshot-wrap { position: relative; display: inline-block; overflow: hidden; margin-top: 0.5rem; }
  .screenshot { max-width: 300px; display: block; border: 1px solid #ccc; }
  .screenshot-highlight { position: absolute; border: 2px solid #d32f2f; box-shadow: 0 0 0 9999px rgba(211, 47, 47, 0.18); pointer-events: none; }
</style>
</head>
<body>
  <h1>Accessibility Audit Report</h1>
  <p class="meta">Generated ${new Date().toLocaleString()} — ${issues.length} total issue(s)</p>
  ${sections || '<p>No issues recorded.</p>'}
  <script>
    // Bounds are in the screenshot's own pixel space, so the highlight box
    // is positioned as a percentage of the loaded image's natural size.
    document.querySelectorAll('.screenshot-wrap').forEach((wrap) => {
      const img = wrap.querySelector('img');
      const box = wrap.querySelector('.screenshot-highlight');
      if (!img || !box) return;
      const position = () => {
        const [x, y, width, height] = box.dataset.bounds.split(',').map(Number);
        box.style.left = (x / img.naturalWidth) * 100 + '%';
        box.style.top = (y / img.naturalHeight) * 100 + '%';
        box.style.width = (width / img.naturalWidth) * 100 + '%';
        box.style.height = (height / img.naturalHeight) * 100 + '%';
      };
      if (img.complete) position();
      else img.onload = position;
    });
  </script>
</body>
</html>`;
}

module.exports = { toCsv, toHtml };
