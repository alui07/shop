import { useId, useState, type SyntheticEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

export function SearchForm({
  initialQuery,
  onSearch,
}: {
  initialQuery: string;
  onSearch: (query: string) => void;
}) {
  const inputId = useId();
  const hintId = useId();
  const [draft, setDraft] = useState(initialQuery);

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSearch(draft.trim());
  };

  return (
    <form onSubmit={submit} role="search" className="flex flex-col gap-1">
      <label htmlFor={inputId} className="text-sm font-medium">
        Search products
      </label>
      <div className="flex gap-2">
        <Input
          id={inputId}
          type="search"
          value={draft}
          maxLength={100}
          aria-describedby={hintId}
          onChange={(event) => setDraft(event.target.value)}
        />
        <Button type="submit">Search</Button>
      </div>
      <p id={hintId} className="text-xs text-muted-foreground">
        Search matches whole words, not parts of words.
      </p>
    </form>
  );
}
