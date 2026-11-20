import type { FormEvent, ReactNode } from 'react';
import { cn } from '@/lib/utils';

export const SELECT_CLASS = cn(
  'h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs outline-none md:text-sm',
  'focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50',
  'aria-invalid:border-destructive',
);
export const TEXTAREA_CLASS = cn(SELECT_CLASS, 'h-auto min-h-28 py-2');

export interface ControlProps {
  id: string;
  'aria-invalid': boolean;
  'aria-describedby': string | undefined;
}

/** Schema issues arrive as objects and server messages as strings. */
export function errorMessage(error: unknown): string {
  return typeof error === 'string' ? error : (error as { message: string }).message;
}

export function FieldFrame({
  id,
  label,
  errors,
  children,
}: {
  id: string;
  label: string;
  errors: readonly unknown[];
  children: (control: ControlProps) => ReactNode;
}) {
  const invalid = errors.length > 0;
  const errorId = `${id}-error`;
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      {children({
        id,
        'aria-invalid': invalid,
        'aria-describedby': invalid ? errorId : undefined,
      })}
      {invalid && (
        <p id={errorId} className="text-sm text-destructive">
          {errorMessage(errors[0])}
        </p>
      )}
    </div>
  );
}

/** Moves focus to the first invalid control, or the form alert, once a failed submit has rendered. */
export function focusFirstProblem(form: HTMLFormElement): void {
  [...form.querySelectorAll<HTMLElement>('[role="alert"], [aria-invalid="true"]')]
    .slice(0, 1)
    .forEach((element) => element.focus());
}

export function FormAlert({
  messages,
  children,
}: {
  messages: readonly string[];
  children?: ReactNode;
}) {
  return (
    messages.length > 0 && (
      <div
        role="alert"
        tabIndex={-1}
        className="space-y-2 rounded-md border border-destructive p-3 text-sm"
      >
        <p>{messages.join(' ')}</p>
        {children}
      </div>
    )
  );
}

export async function submitAndFocus(
  event: FormEvent<HTMLFormElement>,
  form: { handleSubmit: () => Promise<unknown>; state: { isSubmitSuccessful: boolean } },
): Promise<void> {
  event.preventDefault();
  const element = event.currentTarget;
  await form.handleSubmit();
  if (!form.state.isSubmitSuccessful) setTimeout(() => focusFirstProblem(element), 0);
}
