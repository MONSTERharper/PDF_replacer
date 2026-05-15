export type MatchMode =
  | 'exact'
  | 'caseInsensitive'
  | 'wholeWord'
  | 'caseInsensitiveWholeWord';

export type ApiReplaceScope = 'all' | 'first' | 'nth';

export interface ReplacePair {
  find: string;
  replace: string;
}

export interface ReplaceApiResult {
  filename: string;
  blob: Blob;
  matches: string;
  matchesFound: string;
  stylePreserved: string;
  styleFallback: string;
}

function parseFilename(disposition: string): string {
  const match = disposition.match(/filename="?([^";]+)"?/i);
  return match ? match[1] : 'bolt_document_replaced.pdf';
}

export async function postReplaceBatch(params: {
  files: File[];
  pairs: ReplacePair[];
  matchMode: MatchMode;
  replaceScope: ApiReplaceScope;
  occurrenceIndex?: number;
  strict: boolean;
  preserveStyle: boolean;
  retainMetadata: boolean;
}): Promise<ReplaceApiResult> {
  const data = new FormData();
  for (const file of params.files) {
    data.append('files', file);
  }
  for (const pair of params.pairs) {
    data.append('search', pair.find.trim());
    data.append('replacement', pair.replace ?? '');
  }
  data.append('matchMode', params.matchMode);
  data.append('replaceScope', params.replaceScope);
  data.set('strict', params.strict ? 'true' : 'false');
  data.set('preserveStyle', params.preserveStyle ? 'true' : 'false');
  data.set('retainMetadata', params.retainMetadata ? 'true' : 'false');
  if (params.replaceScope !== 'nth') {
    data.delete('occurrenceIndex');
  } else if (params.occurrenceIndex != null) {
    data.append('occurrenceIndex', String(params.occurrenceIndex));
  }

  const response = await fetch('/api/replace', { method: 'POST', body: data });
  if (!response.ok) {
    let message = `Request failed with ${response.status}`;
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('application/json')) {
      const payload = (await response.json()) as { message?: string };
      message = payload.message || message;
    } else {
      const text = await response.text();
      if (text) {
        message = text;
      }
    }
    throw new Error(message);
  }

  const blob = await response.blob();
  const disposition = response.headers.get('content-disposition') || '';
  return {
    filename: parseFilename(disposition),
    blob,
    matches: response.headers.get('X-Bolt-Replacer-Matches') || '0',
    matchesFound: response.headers.get('X-Bolt-Replacer-Matches-Found') || '0',
    stylePreserved: response.headers.get('X-Bolt-Replacer-Style-Preserved') || '0',
    styleFallback: response.headers.get('X-Bolt-Replacer-Style-Fallback') || '0',
  };
}

export async function postContactInquiry(body: {
  name: string;
  email: string;
  subject: string;
  message: string;
}): Promise<void> {
  const response = await fetch('/api/contact', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const payload = (await response.json().catch(() => ({}))) as { message?: string };
  if (!response.ok) {
    throw new Error(payload.message || `Request failed with ${response.status}`);
  }
}
