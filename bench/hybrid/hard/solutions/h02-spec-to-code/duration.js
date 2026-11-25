// See SPEC.md.
function parseDuration(text) {
  if (typeof text !== 'string') return null;
  const m = /^\s*(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+)s)?\s*$/i.exec(text);
  if (!m || (m[1] === undefined && m[2] === undefined && m[3] === undefined)) return null;
  return (+(m[1] || 0)) * 3600 + (+(m[2] || 0)) * 60 + (+(m[3] || 0));
}

function formatDuration(seconds) {
  if (!Number.isInteger(seconds) || seconds < 0) return null;
  if (seconds === 0) return '0s';
  const parts = [];
  const h = Math.floor(seconds / 3600), m = Math.floor(seconds % 3600 / 60), s = seconds % 60;
  if (h) parts.push(h + 'h');
  if (m) parts.push(m + 'm');
  if (s) parts.push(s + 's');
  return parts.join(' ');
}

module.exports = { parseDuration, formatDuration };
