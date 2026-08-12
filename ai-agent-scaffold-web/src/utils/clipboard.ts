interface ClipboardPort {
  writeText(text: string): Promise<unknown>;
}

interface TextareaPort {
  value: string;
  style: { position?: string; opacity?: string; pointerEvents?: string };
  setAttribute(name: string, value: string): void;
  focus(): void;
  select(): void;
  remove(): void;
}

interface DocumentPort {
  body: { appendChild(node: TextareaPort): unknown };
  createElement(tagName: 'textarea'): TextareaPort;
  execCommand(command: 'copy'): boolean;
}

interface ClipboardEnvironment {
  clipboard?: ClipboardPort;
  document?: DocumentPort;
}

/**
 * Copy text in HTTPS and HTTP deployments. Clipboard API is preferred; the
 * temporary textarea path keeps user-triggered copy working in insecure contexts.
 */
export async function copyText(text: string, overrides: ClipboardEnvironment = {}): Promise<void> {
  if (!text) throw new Error('COPY_TEXT_EMPTY');
  const clipboard = overrides.clipboard
    ?? (typeof navigator !== 'undefined' ? navigator.clipboard : undefined);
  if (clipboard?.writeText) {
    try {
      await clipboard.writeText(text);
      return;
    } catch {
      // Permission denial and HTTP insecure contexts fall through to legacy copy.
    }
  }

  const targetDocument = overrides.document
    ?? (typeof document !== 'undefined' ? document as unknown as DocumentPort : undefined);
  if (!targetDocument?.body || typeof targetDocument.execCommand !== 'function') {
    throw new Error('COPY_NOT_SUPPORTED');
  }
  const textarea = targetDocument.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  textarea.style.pointerEvents = 'none';
  targetDocument.body.appendChild(textarea);
  try {
    textarea.focus();
    textarea.select();
    if (!targetDocument.execCommand('copy')) throw new Error('COPY_REJECTED');
  } finally {
    textarea.remove();
  }
}
