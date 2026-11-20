import { useForm, useStore } from '@tanstack/react-form';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import {
  adminProductQuery,
  type AdminProblemError,
  createProduct,
  refreshAfterSave,
  updateProduct,
} from '@/api/admin';
import { categoriesQuery } from '@/api/catalog';
import type { components } from '@/api/schema';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  CONFLICT_MESSAGE,
  EMPTY_PRODUCT_VALUES,
  PRODUCT_SERVER_FIELDS,
  toProductFormValues,
  toProductInput,
  toProductUpdateInput,
  toSubmitErrors,
} from '@/lib/adminForms';
import { productFormSchema } from '@/lib/adminSchemas';
import { FieldFrame, FormAlert, SELECT_CLASS, submitAndFocus, TEXTAREA_CLASS } from './FormField';
import { LoadingMessage } from './LoadingMessage';

type AdminProduct = components['schemas']['AdminProduct'];

export type ProductFormProps =
  | { mode: 'create'; onCreated: (product: AdminProduct) => void }
  | { mode: 'edit'; product: AdminProduct; onReload: () => void; initiallySaved: boolean };

export function ProductForm(props: ProductFormProps) {
  const categories = useQuery(categoriesQuery);
  if (categories.isPending) return <LoadingMessage text="Loading categories" />;
  if (categories.isError) return <p role="alert">The categories could not be loaded.</p>;
  return <ProductFields {...props} categories={categories.data} />;
}

function ProductFields({
  categories,
  ...props
}: ProductFormProps & { categories: components['schemas']['Category'][] }) {
  const queryClient = useQueryClient();
  const saved = useRef<AdminProduct>(undefined);
  const [savedVisible, setSavedVisible] = useState(props.mode === 'edit' && props.initiallySaved);

  const form = useForm({
    defaultValues:
      props.mode === 'edit' ? toProductFormValues(props.product) : EMPTY_PRODUCT_VALUES,
    validators: {
      onChange: productFormSchema,
      onSubmit: productFormSchema,
      onSubmitAsync: async ({ value }) => {
        setSavedVisible(false);
        try {
          saved.current =
            props.mode === 'edit'
              ? await updateProduct(props.product.id, toProductUpdateInput(value))
              : await createProduct(toProductInput(value));
          return undefined;
        } catch (error) {
          const failure = error as AdminProblemError;
          if (failure.status >= 500 && props.mode === 'edit') {
            void queryClient.invalidateQueries({
              queryKey: adminProductQuery(props.product.id).queryKey,
            });
          }
          return toSubmitErrors(failure, PRODUCT_SERVER_FIELDS);
        }
      },
    },
    onSubmit: ({ formApi }) => {
      const product = saved.current as AdminProduct;
      refreshAfterSave(queryClient, product);
      formApi.reset(toProductFormValues(product), { keepDefaultValues: true });
      if (props.mode === 'create') props.onCreated(product);
      else setSavedVisible(true);
    },
  });

  // Inputs stay locked until the saved values are back in the form, so nothing typed mid-save is overwritten.
  const isSubmitting = useStore(form.store, (state) => state.isSubmitting);
  const formErrors = useStore(form.store, (state) =>
    state.errors.filter((error): error is string => typeof error === 'string'),
  );
  const conflict = formErrors.includes(CONFLICT_MESSAGE);

  return (
    <form
      noValidate
      className="max-w-xl space-y-4"
      onSubmit={(event) => void submitAndFocus(event, form)}
    >
      <FormAlert messages={formErrors}>
        {conflict && props.mode === 'edit' && (
          <Button type="button" variant="outline" size="sm" onClick={props.onReload}>
            Reload
          </Button>
        )}
      </FormAlert>
      <fieldset disabled={isSubmitting} className="m-0 min-w-0 space-y-4 border-0 p-0">
        <form.Field name="slug">
          {(field) => (
            <FieldFrame id="slug" label="Slug" errors={field.state.meta.errors}>
              {(control) => (
                <Input
                  {...control}
                  value={field.state.value}
                  readOnly={props.mode === 'edit'}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              )}
            </FieldFrame>
          )}
        </form.Field>
        <form.Field name="name">
          {(field) => (
            <FieldFrame id="name" label="Name" errors={field.state.meta.errors}>
              {(control) => (
                <Input
                  {...control}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              )}
            </FieldFrame>
          )}
        </form.Field>
        <form.Field name="description">
          {(field) => (
            <FieldFrame id="description" label="Description" errors={field.state.meta.errors}>
              {(control) => (
                <textarea
                  {...control}
                  className={TEXTAREA_CLASS}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              )}
            </FieldFrame>
          )}
        </form.Field>
        <form.Field name="price">
          {(field) => (
            <FieldFrame id="price" label="Price (EUR)" errors={field.state.meta.errors}>
              {(control) => (
                <Input
                  {...control}
                  inputMode="decimal"
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              )}
            </FieldFrame>
          )}
        </form.Field>
        <form.Field name="categorySlug">
          {(field) => (
            <FieldFrame id="category" label="Category" errors={field.state.meta.errors}>
              {(control) => (
                <select
                  {...control}
                  className={SELECT_CLASS}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                >
                  <option value="">Choose a category</option>
                  {categories.map((category) => (
                    <option key={category.slug} value={category.slug}>
                      {category.name}
                    </option>
                  ))}
                </select>
              )}
            </FieldFrame>
          )}
        </form.Field>
        <form.Field name="active">
          {(field) => (
            <label className="flex items-center gap-2 text-sm font-medium">
              <input
                type="checkbox"
                checked={field.state.value}
                onBlur={field.handleBlur}
                onChange={(event) => field.handleChange(event.target.checked)}
              />
              Active
            </label>
          )}
        </form.Field>
        <div className="flex items-center gap-4">
          <Button type="submit">{props.mode === 'edit' ? 'Save changes' : 'Create product'}</Button>
          {savedVisible && <p role="status">Product saved</p>}
        </div>
      </fieldset>
    </form>
  );
}
