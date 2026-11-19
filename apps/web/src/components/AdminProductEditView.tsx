import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { adminProductQuery } from '@/api/admin';
import { ApiError } from '@/api/catalog';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { LoadingMessage } from './LoadingMessage';
import { NoAccessPanel } from './NoAccessPanel';
import { NotFoundPanel } from './NotFoundPanel';
import { ProductForm } from './ProductForm';
import { SignInPrompt } from './SignInPrompt';
import { StockForm } from './StockForm';

export function AdminProductEditView({ id, justCreated }: { id: number; justCreated: boolean }) {
  useDocumentTitle('Edit product');
  const { data, error, isPending, refetch } = useQuery(adminProductQuery(id));
  const [initiallySaved, setInitiallySaved] = useState(justCreated);
  const [generation, setGeneration] = useState(0);

  if (error instanceof ApiError && error.status === 401) return <SignInPrompt />;
  if (error instanceof ApiError && error.status === 403) return <NoAccessPanel />;
  if (error instanceof ApiError && (error.status === 404 || error.status === 400)) {
    return <NotFoundPanel title="Product not found" message="This product does not exist." />;
  }

  /** Forms keep what was typed, so a reload remounts them from the fresh product. */
  const reload = () =>
    void refetch().then(() => {
      setInitiallySaved(false);
      setGeneration((current) => current + 1);
    });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Edit product</h1>
      {isPending && <LoadingMessage text="Loading product" />}
      {error && <p role="alert">The product could not be loaded.</p>}
      {data && (
        <>
          <ProductForm
            key={`product-${generation}`}
            mode="edit"
            product={data}
            onReload={reload}
            initiallySaved={initiallySaved}
          />
          <h2 className="text-xl font-semibold">Stock</h2>
          <StockForm key={`stock-${generation}`} product={data} onReload={reload} />
        </>
      )}
    </div>
  );
}
