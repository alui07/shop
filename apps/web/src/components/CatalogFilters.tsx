import { SORT_OPTIONS, type CatalogSearch, type SortValue } from '@/lib/catalogSearch';
import { LabeledSelect, type SelectOption } from './LabeledSelect';
import { SearchForm } from './SearchForm';

const ALL_CATEGORIES = 'All categories';

export function CatalogFilters({
  search,
  categories,
  onChange,
}: {
  search: CatalogSearch;
  categories: readonly { slug: string; name: string }[];
  onChange: (change: Partial<CatalogSearch>) => void;
}) {
  const categoryOptions: SelectOption[] = [
    { value: '', label: ALL_CATEGORIES },
    ...categories.map(({ slug, name }) => ({ value: slug, label: name })),
  ];

  return (
    <div className="grid gap-4 md:grid-cols-[1fr_auto_auto] md:items-end">
      <SearchForm
        key={search.q ?? ''}
        initialQuery={search.q ?? ''}
        onSearch={(query) => onChange({ q: query === '' ? undefined : query })}
      />
      <LabeledSelect
        label="Category"
        value={search.category ?? ''}
        options={categoryOptions}
        onChange={(slug) => onChange({ category: slug === '' ? undefined : slug })}
      />
      <LabeledSelect
        label="Sort by"
        value={search.sort ?? (search.q ? 'relevance' : 'name')}
        options={SORT_OPTIONS}
        onChange={(sort) => onChange({ sort: sort as SortValue })}
      />
    </div>
  );
}
