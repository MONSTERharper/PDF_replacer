const form = document.querySelector('#replaceForm');
const statusEl = document.querySelector('#status');

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  statusEl.textContent = 'Processing PDF...';
  statusEl.className = 'status';
  const button = form.querySelector('button');
  button.disabled = true;

  try {
    const data = new FormData(form);
    const font = data.get('font');
    if (!font || font.size === 0) {
      data.delete('font');
    }
    data.set('strict', form.strict.checked ? 'true' : 'false');

    const response = await fetch('/api/replace', { method: 'POST', body: data });
    if (!response.ok) {
      const message = await response.text();
      throw new Error(message || `Request failed with ${response.status}`);
    }

    const blob = await response.blob();
    const disposition = response.headers.get('content-disposition') || '';
    const match = disposition.match(/filename="?([^";]+)"?/i);
    const filename = match ? match[1] : 'replaced.pdf';
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);

    const matches = response.headers.get('x-pdf-replacer-matches');
    statusEl.textContent = matches ? `Done. ${matches} replacement(s) applied.` : 'Done. Download started.';
    statusEl.className = 'status ok';
  } catch (error) {
    statusEl.textContent = error.message;
    statusEl.className = 'status error';
  } finally {
    button.disabled = false;
  }
});
