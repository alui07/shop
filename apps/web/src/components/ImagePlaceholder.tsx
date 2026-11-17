export function ImagePlaceholder({ className = '' }: { className?: string }) {
  return <div aria-hidden="true" className={`rounded-md bg-muted ${className}`} />;
}
