import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default defineConfig(
  globalIgnores(['node_modules', 'reports', 'pnpm-lock.yaml']),
  js.configs.recommended,
  tseslint.configs.strict,
  {
    languageOptions: { globals: globals.node },
  },
  {
    files: ['specs/**/*.ts'],
    ignores: ['specs/support/test.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@playwright/test',
              importNames: ['test', 'expect'],
              message:
                'Import test and expect from the support test module (specs/support/test.ts).',
            },
          ],
        },
      ],
    },
  },
);
