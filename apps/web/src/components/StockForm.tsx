import { useForm, useStore } from '@tanstack/react-form';
import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { adminProductQuery, type AdminProblemError, refreshAfterSave, setStock } from '@/api/admin';
import type { components } from '@/api/schema';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  CONFLICT_MESSAGE,
  STOCK_SERVER_FIELDS,
  toStockFormValues,
  toStockInput,
  toSubmitErrors,
} from '@/lib/adminForms';
import { stockFormSchema } from '@/lib/adminSchemas';
import { FieldFrame, FormAlert, submitAndFocus } from './FormField';

type AdminProduct = components['schemas']['AdminProduct'];

export function StockForm({ product, onReload }: { product: AdminProduct; onReload: () => void }) {
  const queryClient = useQueryClient();
  const saved = useRef<AdminProduct>(undefined);
  const [savedVisible, setSavedVisible] = useState(false);

  const [defaultValues] = useState(() => toStockFormValues(product));

  const form = useForm({
    defaultValues,
    validators: {
      onChange: stockFormSchema,
      onSubmit: stockFormSchema,
      onSubmitAsync: async ({ value }) => {
        setSavedVisible(false);
        try {
          saved.current = await setStock(product.id, toStockInput(value));
          return undefined;
        } catch (error) {
          const failure = error as AdminProblemError;
          if (failure.status >= 500) {
            void queryClient.invalidateQueries({
              queryKey: adminProductQuery(product.id).queryKey,
            });
          }
          return toSubmitErrors(failure, STOCK_SERVER_FIELDS);
        }
      },
    },
    onSubmit: ({ formApi }) => {
      const updated = saved.current as AdminProduct;
      refreshAfterSave(queryClient, updated);
      formApi.reset(toStockFormValues(updated), { keepDefaultValues: true });
      setSavedVisible(true);
    },
  });

  // Inputs stay locked until the saved values are back in the form, so nothing typed mid-save is overwritten.
  const isSubmitting = useStore(form.store, (state) => state.isSubmitting);
  const formErrors = useStore(form.store, (state) =>
    state.errors.filter((error): error is string => typeof error === 'string'),
  );

  return (
    <form
      noValidate
      className="max-w-xl space-y-4"
      onSubmit={(event) => void submitAndFocus(event, form)}
    >
      <FormAlert messages={formErrors}>
        {formErrors.includes(CONFLICT_MESSAGE) && (
          <Button type="button" variant="outline" size="sm" onClick={onReload}>
            Reload
          </Button>
        )}
      </FormAlert>
      <fieldset disabled={isSubmitting} className="m-0 min-w-0 space-y-4 border-0 p-0">
        <p className="text-sm">Reserved: {product.stock.reserved}</p>
        <form.Field name="onHand">
          {(field) => (
            <FieldFrame id="on-hand" label="On hand" errors={field.state.meta.errors}>
              {(control) => (
                <Input
                  {...control}
                  inputMode="numeric"
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              )}
            </FieldFrame>
          )}
        </form.Field>
        <div className="flex items-center gap-4">
          <Button type="submit">Save stock</Button>
          {savedVisible && <p role="status">Stock saved</p>}
        </div>
      </fieldset>
    </form>
  );
}
