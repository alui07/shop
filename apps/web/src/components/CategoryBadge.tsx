export function CategoryBadge({ name }: { name: string }) {
  return (
    <span className="inline-block rounded-full bg-secondary px-2 py-0.5 text-xs font-medium text-secondary-foreground">
      {name}
    </span>
  );
}
