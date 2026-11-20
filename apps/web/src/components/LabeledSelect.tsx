import { useId } from 'react';

export interface SelectOption {
  value: string;
  label: string;
}

export function LabeledSelect({
  label,
  value,
  options,
  onChange,
  busy = false,
}: {
  label: string;
  value: string;
  options: readonly SelectOption[];
  onChange: (value: string) => void;
  busy?: boolean;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      <select
        id={id}
        value={value}
        aria-disabled={busy || undefined}
        onChange={(event) => onChange(event.target.value)}
        className="h-9 rounded-md border border-input bg-background px-3 text-sm shadow-xs focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}
