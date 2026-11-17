import { useEffect } from 'react';

export function useDocumentTitle(pageName: string): void {
  useEffect(() => {
    document.title = `${pageName} | Shop`;
  }, [pageName]);
}
