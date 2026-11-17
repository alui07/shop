import type { Page } from '@playwright/test';

export interface CspViolation {
  directive: string;
  blockedUri: string;
  sourceFile: string;
  line: number;
  sample: string;
}

export interface ConsoleError {
  text: string;
  url: string;
}

export interface BrowserDiagnostics {
  cspViolations: CspViolation[];
  consoleErrors: ConsoleError[];
  pageErrors: string[];
}

// Registered before navigation: the init script runs in every new document and reports each
// securitypolicyviolation event through a binding, so violations survive full page loads.
export async function collectBrowserDiagnostics(page: Page): Promise<BrowserDiagnostics> {
  const diagnostics: BrowserDiagnostics = { cspViolations: [], consoleErrors: [], pageErrors: [] };

  await page.exposeFunction('reportCspViolation', (violation: CspViolation) => {
    diagnostics.cspViolations.push(violation);
  });
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      const report = (window as unknown as Record<string, (v: unknown) => void>)[
        'reportCspViolation'
      ];
      report?.({
        directive: event.effectiveDirective,
        blockedUri: event.blockedURI,
        sourceFile: event.sourceFile,
        line: event.lineNumber,
        sample: event.sample,
      });
    });
  });

  page.on('console', (message) => {
    if (message.type() === 'error') {
      diagnostics.consoleErrors.push({ text: message.text(), url: message.location().url });
    }
  });
  page.on('pageerror', (error) => diagnostics.pageErrors.push(error.message));

  return diagnostics;
}

// Violation reports travel through an asynchronous binding, so let the page go quiet before asserting.
export async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 250)));
}
